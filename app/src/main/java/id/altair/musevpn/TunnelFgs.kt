package id.altair.musevpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Foreground service bertipe specialUse (Android 14+ tidak punya tipe "vpn").
 * Tujuannya supaya proses tidak dibunuh OEM (HiOS/Xiaomi dll) saat tunnel aktif.
 */
class TunnelFgs : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.fgs_title))
            .setContentText(getString(R.string.fgs_text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        startForeground(NOTIF_ID, notification)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "tunnel"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TunnelFgs::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TunnelFgs::class.java))
        }
    }
}
