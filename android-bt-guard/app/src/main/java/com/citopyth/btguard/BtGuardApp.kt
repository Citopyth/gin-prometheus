package com.citopyth.btguard

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class BtGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceStore.init(this)
        Alarm.ensureChannels(this)
        Guard.resetAfterReboot(this)
        // Lives as long as the process; see BondReceiver for why it is not in the manifest.
        ContextCompat.registerReceiver(
            this,
            BondReceiver(),
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Switching Bluetooth off ends every link; don't leave stale "verbunden" flags.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_OFF) DeviceStore.markAllDisconnected(context)
                }
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
