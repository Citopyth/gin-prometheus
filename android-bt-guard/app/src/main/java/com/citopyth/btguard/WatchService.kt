package com.citopyth.btguard

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * "Wächter dauerhaft aktiv": a foreground service whose only job is to keep
 * the app process alive, so that pairing attempts (only reported to running
 * apps) and the decoy keep working. It shows a quiet, permanent notification.
 */
class WatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Alarm.ensureChannels(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, Alarm.CHANNEL_WATCH)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BT-Wächter aktiv")
            .setContentText("Überwacht Bluetooth-Verbindungen und Kopplungsversuche.")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // Missing Bluetooth permission or a background-start restriction.
            stopSelf()
            return START_NOT_STICKY
        }
        thread { Guard.checkBonds(applicationContext) }
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 4711

        fun startIfEnabled(context: Context): Boolean {
            if (!Prefs.watchEnabled(context) || !Bt.canConnect(context)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
                true
            } catch (e: Exception) {
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}

/** Restarts the watcher after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Guard.resetAfterReboot(context)
                WatchService.startIfEnabled(context)
            }
        }
    }
}
