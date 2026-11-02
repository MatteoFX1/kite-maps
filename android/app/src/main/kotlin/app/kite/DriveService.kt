package app.kite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps Kite running with the screen off or another app on top while a drive is going, the way
 * the iPhone keeps GPS on in the background. It shows a quiet "Driving with Kite" notification.
 */
class DriveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Driving", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Kite is guiding you"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shortcut_car)
            .setContentTitle("Driving with Kite")
            .setContentText("Tap to go back to the map")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            ServiceCompat.startForeground(this, 1, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "drive"

        fun start(c: Context) {
            runCatching { c.startForegroundService(Intent(c, DriveService::class.java)) }
        }

        fun stop(c: Context) {
            runCatching { c.stopService(Intent(c, DriveService::class.java)) }
        }
    }
}
