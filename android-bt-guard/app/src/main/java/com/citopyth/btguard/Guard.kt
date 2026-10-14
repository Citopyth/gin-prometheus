package com.citopyth.btguard

import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import kotlin.math.abs

/** Decides which Bluetooth events look like unwanted access. */
object Guard {
    private const val BURST_WINDOW_MS = 10 * 60 * 1000L
    private const val BURST_THRESHOLD = 5

    fun onAclConnected(context: Context, address: String, name: String?, kind: DeviceKind?) {
        DeviceStore.init(context)
        val before = DeviceStore.state.value
        val trust = before.devices[address]?.trust ?: Trust.UNKNOWN
        val twin = before.devices.values.firstOrNull {
            it.trust == Trust.TRUSTED && MacUtil.likelySameDevice(it.address, address)
        }
        val now = System.currentTimeMillis()
        val recent = before.events.count {
            it.address == address &&
                (it.kind == EventKind.CONNECTED || it.kind == EventKind.BLOCKED_CONNECTED) &&
                now - it.time < BURST_WINDOW_MS
        }

        val input = kind?.input ?: before.devices[address]?.input ?: false
        val reasons = mutableListOf<String>()
        val suspicious = when {
            // Highest risk: an unknown keyboard or mouse can type and tap on the phone.
            // The "second address of a trusted device" excuse does not apply here.
            input && trust != Trust.TRUSTED -> {
                reasons += "Eingabegerät (${kind?.label ?: "Tastatur/Maus"}): kann dein Handy steuern!"
                if (trust == Trust.BLOCKED) reasons += "Blockiertes Gerät"
                true
            }
            trust == Trust.BLOCKED -> {
                reasons += "Blockiertes Gerät"
                true
            }
            trust == Trust.TRUSTED || twin != null -> false
            else -> {
                reasons += "Unbekanntes Gerät"
                true
            }
        }
        // Trusted devices (car, earbuds) connecting while the phone is locked is normal.
        if (suspicious && isLocked(context)) reasons += "Handy war gesperrt"
        if (suspicious && recent + 1 >= BURST_THRESHOLD) reasons += "${recent + 1}× in 10 Minuten"

        val note = when {
            reasons.isNotEmpty() -> reasons.joinToString(" · ")
            twin != null && trust != Trust.TRUSTED -> "Vermutlich zweite Adresse von ${twin.label}"
            else -> null
        }
        val rec = DeviceStore.recordConnect(context, address, name, note, suspicious, kind)
        Alarm.onConnected(context, rec, DeviceStore.state.value.devices.values, note)
        checkBonds(context)
    }

    /** Syncs the stored pairing list with Android and alarms on new, untrusted pairings. */
    fun checkBonds(context: Context) {
        if (!Bt.canConnect(context)) return
        val current = Bt.bondedDevices(context)
        val baseline = !Prefs.bondBaseline(context)
        val newUntrusted = DeviceStore.syncBonds(context, current, baseline)
        if (baseline) Prefs.setBondBaseline(context)
        DeviceStore.updateKinds(context, Bt.bondedKinds(context))
        // Re-read the records so the alarm knows whether a new pairing is an input device.
        val devices = DeviceStore.state.value.devices
        newUntrusted.forEach { Alarm.onPaired(context, devices[it.address] ?: it) }
    }

    /**
     * No Bluetooth link survives a reboot, but the "disconnected" broadcasts
     * never arrive for it. Detects a reboot by the boot timestamp, clears the
     * stale "verbunden" flags and re-reads the audio devices connected now.
     */
    fun resetAfterReboot(context: Context) {
        val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (abs(bootTime - Prefs.lastBootTime(context)) < 60_000) return
        Prefs.setLastBootTime(context, bootTime)
        DeviceStore.markAllDisconnected(context)
        Bt.queryConnected(context) { DeviceStore.markConnected(context, it) }
    }

    fun onBondStateChanged(context: Context, device: BluetoothDevice, state: Int, previous: Int) {
        val address = device.address?.uppercase() ?: return
        val name = Bt.safeName(context, device)
        val trusted = DeviceStore.state.value.devices[address]?.trust == Trust.TRUSTED
        when {
            state == BluetoothDevice.BOND_BONDING ->
                DeviceStore.logEvent(
                    context, address, name, EventKind.PAIRING_ATTEMPT,
                    note = if (trusted) null else "Ein Gerät wird gerade gekoppelt",
                )
            state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING -> {
                DeviceStore.logEvent(
                    context, address, name, EventKind.PAIRING_FAILED,
                    note = if (trusted) null else "Kopplungsversuch ohne Erfolg",
                    suspicious = !trusted,
                )
                if (!trusted) Alarm.onPairingFailed(context, address, name)
            }
            else -> checkBonds(context)
        }
    }

    private fun isLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
}
