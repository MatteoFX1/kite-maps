package app.kite

import android.app.Application

/** Starts once per launch: tells the shared app where it lives, then starts it. */
class KiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidHost.app = this
        DriveService.notificationIcon = R.drawable.ic_shortcut_car
        Kite.start()
    }
}
