package com.citopyth.btguard

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlin.concurrent.thread

@Suppress("DEPRECATION")
fun Intent.bluetoothDevice(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

/** Logs every Bluetooth link to this phone, also while the app is closed. */
class AclReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.bluetoothDevice() ?: return
        val address = device.address?.uppercase() ?: return
        val name = Bt.safeName(context, device)

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Guard.onAclConnected(context, address, name, Bt.describe(context, device))
                if (DeviceStore.state.value.devices[address]?.vendorChecked == false) {
                    val pending = goAsync()
                    thread {
                        try {
                            Vendor.lookupAndStore(context, address)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                DeviceStore.recordDisconnect(context, address, name)
                Guard.checkBonds(context)
            }
        }
    }
}

/**
 * Pairing attempts. BOND_STATE_CHANGED is not exempt from the Android 8
 * background limits, so this receiver is registered at runtime by [BtGuardApp]
 * and only hears attempts while the app process is alive. Completed pairings
 * are still caught later by [Guard.checkBonds].
 */
class BondReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
        val device = intent.bluetoothDevice() ?: return
        Guard.onBondStateChanged(
            context, device,
            intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR),
            intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR),
        )
    }
}

/** Handles the "Blockieren"/"Vertrauen" buttons in alarm notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return
        when (intent.action) {
            ACTION_BLOCK -> DeviceStore.setTrust(context, address, Trust.BLOCKED)
            ACTION_TRUST -> DeviceStore.setTrust(context, address, Trust.TRUSTED)
        }
        Alarm.cancel(context, address)
    }

    companion object {
        const val ACTION_BLOCK = "com.citopyth.btguard.BLOCK"
        const val ACTION_TRUST = "com.citopyth.btguard.TRUST"
        private const val EXTRA_ADDRESS = "address"

        fun pending(context: Context, action: String, address: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                (action + address).hashCode(),
                Intent(context, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_ADDRESS, address),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
