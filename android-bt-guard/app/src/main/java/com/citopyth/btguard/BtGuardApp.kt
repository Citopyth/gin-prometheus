package com.citopyth.btguard

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class BtGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceStore.init(this)
        Alarm.ensureChannels(this)
        // Lives as long as the process; see BondReceiver for why it is not in the manifest.
        ContextCompat.registerReceiver(
            this,
            BondReceiver(),
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
