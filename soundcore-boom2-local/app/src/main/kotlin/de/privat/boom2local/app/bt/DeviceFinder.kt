package de.privat.boom2local.app.bt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import de.privat.boom2local.protocol.DeviceProfile
import java.util.UUID

/** A paired Bluetooth device, as far as the app is allowed to see it. */
class FoundDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    /** True when the name matches the loaded profile. */
    val matchesProfile: Boolean,
    /** The vendor control UUID from the device's own SDP record, if it advertises one. */
    val vendorUuid: String?,
    val allUuids: List<String>,
)

/**
 * Finds the speaker among the phone's paired devices.
 *
 * It deliberately never starts a Bluetooth discovery scan. Scanning is what forces an
 * app to ask for the location permission on Android, and it is unnecessary here: the
 * speaker has to be paired for audio anyway, so the pairing list is enough. That one
 * design decision removes the location permission from the app entirely.
 */
class DeviceFinder(private val context: Context) {

    private val manager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    val adapter: BluetoothAdapter? get() = manager?.adapter

    val bluetoothSupported: Boolean get() = adapter != null

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    /** The one runtime permission this app uses, and only from Android 12 on. */
    val connectPermission: String get() = Manifest.permission.BLUETOOTH_CONNECT

    fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            true
        } else {
            context.checkSelfPermission(connectPermission) == PackageManager.PERMISSION_GRANTED
        }

    /** Paired devices, the ones matching the profile first. */
    fun pairedDevices(profile: DeviceProfile): List<FoundDevice> {
        val a = adapter ?: return emptyList()
        if (!hasConnectPermission()) return emptyList()
        val bonded = try {
            a.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            return emptyList()
        }
        return bonded
            .map { describe(it, profile) }
            .sortedWith(
                compareByDescending<FoundDevice> { it.matchesProfile }
                    .thenByDescending { it.vendorUuid != null }
                    .thenBy { it.name },
            )
    }

    private fun describe(device: BluetoothDevice, profile: DeviceProfile): FoundDevice {
        val name = try {
            device.name
        } catch (e: SecurityException) {
            null
        }
        val uuids = try {
            device.uuids?.map { it.uuid.toString().lowercase() } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        val prefix = profile.transport.uuidPrefix.lowercase()
        return FoundDevice(
            device = device,
            name = name ?: "(Name nicht lesbar)",
            address = device.address,
            matchesProfile = profile.match.matches(name),
            vendorUuid = uuids.firstOrNull { prefix.isNotEmpty() && it.startsWith(prefix) },
            allUuids = uuids,
        )
    }

    /**
     * Every RFCOMM service worth trying, best first.
     *
     * The device's own SDP record comes first, so the app does not depend on anyone
     * having guessed the last four digits of the vendor UUID correctly. The profile's
     * candidates follow as fallbacks, and the caller tries them in turn — listing three
     * fallbacks would be pointless if only the first were ever attempted.
     */
    fun resolveServiceUuids(found: FoundDevice, profile: DeviceProfile): List<UuidChoice> {
        val choices = mutableListOf<UuidChoice>()
        val seen = mutableSetOf<UUID>()

        fun add(text: String, origin: String) {
            val uuid = try {
                UUID.fromString(text)
            } catch (e: IllegalArgumentException) {
                return
            }
            if (seen.add(uuid)) choices += UuidChoice(uuid, origin)
        }

        found.vendorUuid?.let { add(it, "aus dem SDP-Eintrag des Geraets") }
        for (candidate in profile.transport.fallbackUuids) {
            add(candidate, "Rueckfall-Kandidat aus dem Profil (unbestaetigt)")
        }
        return choices
    }

    class UuidChoice(val uuid: UUID, val origin: String)
}
