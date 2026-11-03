package app.kite

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

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
        // Always go foreground first, even when this start is only to stop: Android ends the app if a
        // service started with startForegroundService() stops before it calls startForeground().
        val foreground = runCatching {
            ServiceCompat.startForeground(this, 1, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        }.isSuccess
        if (!foreground || intent?.action == STOP) {
            running = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "drive"
        private const val STOP = "app.kite.STOP_DRIVE"
        private var running = false

        fun start(c: Context) {
            // Without location access Android refuses a location service, and that refusal ends the app.
            if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            running = runCatching { c.startForegroundService(Intent(c, DriveService::class.java)) }.isSuccess
        }

        /**
         * Stops through the service itself rather than stopService(), so a drive ended straight after it
         * began (already at the destination) still goes foreground first, in order.
         */
        fun stop(c: Context) {
            if (!running) return
            running = false
            runCatching { c.startService(Intent(c, DriveService::class.java).setAction(STOP)) }
                .onFailure { runCatching { c.stopService(Intent(c, DriveService::class.java)) } }
        }
    }
}
