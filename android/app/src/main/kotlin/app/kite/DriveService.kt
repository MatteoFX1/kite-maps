package app.kite

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Keeps Kite running with the screen off while a drive is going. */
class DriveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
