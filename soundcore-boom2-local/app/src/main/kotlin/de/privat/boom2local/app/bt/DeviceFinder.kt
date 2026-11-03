package de.privat.boom2local.app.bt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import de.privat.boom2local.protocol.DeviceProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** A paired Bluetooth device, as far as the app is allowed to see it. */
class FoundDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    /** True when the name matches the loaded profile. */
    val matchesProfile: Boolean,
    /** An advertised service matching the profile's vendor prefix, if there is one. */
    val vendorUuid: String?,
    /** Everything the device's SDP record offers. */
    val allUuids: List<String>,
) {
    /**
     * Advertised services that are not standard Bluetooth profiles.
     *
     * A UUID ending in the Bluetooth base suffix is a registered profile — A2DP, AVRCP,
     * handsfree and so on. Anything else was made up by the manufacturer, which makes it
     * the only plausible place for a proprietary control channel. This matters more than
     * the profile's vendor prefix: that prefix is a guess from other models, while this
     * is a property of the service itself.
     */
    val customUuids: List<String> = allUuids.filterNot { it.endsWith(BLUETOOTH_BASE_SUFFIX) }

    /** The standard Serial Port Profile, a common fallback for vendor protocols. */
    val sppUuid: String? = allUuids.firstOrNull { it.startsWith("00001101") }

    companion object {
        /** Registered Bluetooth profile UUIDs all end in this. */
        const val BLUETOOTH_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
    }
}

/** Outcome of a fresh SDP query. */
class SdpResult(val uuids: List<String>, val note: String?, val fresh: Boolean)

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
            .map { describe(it, profile, cachedUuids(it)) }
            .sortedWith(
                compareByDescending<FoundDevice> { it.matchesProfile }
                    .thenByDescending { it.customUuids.isNotEmpty() }
                    .thenBy { it.name },
            )
    }

    fun describe(
        device: BluetoothDevice,
        profile: DeviceProfile,
        uuids: List<String>,
    ): FoundDevice {
        val name = try {
            device.name
        } catch (e: SecurityException) {
            null
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

    /** The SDP record Android already has, which can be stale or incomplete. */
    fun cachedUuids(device: BluetoothDevice): List<String> = try {
        device.uuids?.mapNotNull { it?.uuid?.toString()?.lowercase() } ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    /**
     * Asks the speaker itself which services it offers, instead of trusting the cache.
     *
     * `BluetoothDevice.uuids` returns whatever Android stored at pairing time, and for a
     * speaker paired for audio that can be just the audio profiles. `fetchUuidsWithSdp`
     * queries the device over the air and answers through a broadcast, which is the only
     * way to see a control service the cache never recorded.
     */
    suspend fun refreshSdp(device: BluetoothDevice, timeoutMs: Long = 15_000): SdpResult {
        if (!hasConnectPermission()) {
            return SdpResult(emptyList(), "Bluetooth-Berechtigung fehlt.", false)
        }
        val answer = CompletableDeferred<List<String>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_UUID) return
                @Suppress("DEPRECATION")
                val from = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (from?.address != device.address) return
                @Suppress("DEPRECATION")
                val extra = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                val uuids = extra.orEmpty()
                    .filterIsInstance<ParcelUuid>()
                    .map { it.uuid.toString().lowercase() }
                if (!answer.isCompleted) answer.complete(uuids)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_UUID),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            val started = try {
                device.fetchUuidsWithSdp()
            } catch (e: SecurityException) {
                false
            }
            if (!started) {
                return SdpResult(
                    cachedUuids(device),
                    "Android hat die SDP-Abfrage nicht gestartet. Angezeigt ist die " +
                        "zwischengespeicherte Liste.",
                    false,
                )
            }
            val fresh = withTimeoutOrNull(timeoutMs) { answer.await() }
            return if (fresh == null) {
                SdpResult(
                    cachedUuids(device),
                    "Keine Antwort innerhalb von ${timeoutMs / 1000} Sekunden. Die Box muss " +
                        "eingeschaltet und in Reichweite sein. Angezeigt ist die " +
                        "zwischengespeicherte Liste.",
                    false,
                )
            } else {
                SdpResult(fresh, null, true)
            }
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Already gone; nothing to do.
            }
        }
    }

    /**
     * Every RFCOMM service worth trying, best first.
     *
     * Order matters, and it puts what the device actually says ahead of what the profile
     * guesses:
     *
     *  1. a service matching the profile's vendor prefix — a guess confirmed by the device
     *  2. every other non-standard service the device advertises — real, and the only
     *     place a proprietary protocol can live
     *  3. the profile's fallback candidates — pure guesses from other models
     *  4. Serial Port Profile, if offered
     *  5. with [includeStandardProfiles], the registered profiles too, as a last resort
     */
    fun resolveServiceUuids(
        found: FoundDevice,
        profile: DeviceProfile,
        includeStandardProfiles: Boolean = false,
    ): List<UuidChoice> {
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

        found.vendorUuid?.let { add(it, "Dienst der Box, passt zum Hersteller-Praefix") }
        for (uuid in found.customUuids) {
            add(uuid, "herstellerspezifischer Dienst der Box")
        }
        for (candidate in profile.transport.fallbackUuids) {
            add(candidate, "Rueckfall-Kandidat aus dem Profil (geraten)")
        }
        found.sppUuid?.let { add(it, "serieller Standarddienst (SPP) der Box") }
        if (includeStandardProfiles) {
            for (uuid in found.allUuids) {
                add(uuid, "Standardprofil der Box (letzter Versuch)")
            }
        }
        return choices
    }

    class UuidChoice(val uuid: UUID, val origin: String)
}
