package app.kite

import android.app.Application
import app.kite.core.KeyValue
import app.kite.core.RoadCache
import java.io.File

/** Starts once per launch: hands the engine its storage before any screen asks for it. */
class KiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        KeyValue.store = PrefsStore(getSharedPreferences("kite", MODE_PRIVATE))
        RoadCache.defaultFile = File(cacheDir, "roads.json")
        org.maplibre.android.MapLibre.getInstance(this)
    }

    companion object {
        lateinit var app: KiteApp
            private set
    }
}
