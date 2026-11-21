package app.kite

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The Android shell: everything on screen is the shared app ([KiteScreen]), the same as on the iPhone.
 * This only passes in what only an Android activity can do: permission prompts, the speech window and links.
 */
class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        LocationService.refreshPermission()
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == false && r[Manifest.permission.ACCESS_COARSE_LOCATION] == false) LocationService.denied = true
    }
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> Dictation.deliver(r.data) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AndroidHost.activity = this
        Kite.start()
        GpsHardware.asker = { list -> permissions.launch(list) }
        Dictation.launcher = { i -> speech.launch(i) }
        handle(intent)
        setContent { KiteTheme { KiteScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        AndroidHost.activity = this
        Kite.foreground()
    }

    override fun onStop() {
        super.onStop()
        Kite.background()
    }

    override fun onDestroy() {
        if (AndroidHost.activity === this) {
            GpsHardware.asker = null
            Dictation.launcher = null
        }
        super.onDestroy()
    }

    /** Links shared from other apps, map links opened in Kite, and the Home, Work and Car shortcuts. */
    private fun handle(i: Intent?) {
        i ?: return
        val model = Kite.model
        when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { model.openLink(it) }
            Intent.ACTION_VIEW -> i.dataString?.let { model.openLink(it) }
            "app.kite.HOME" -> model.perform(LaunchKind.HOME)
            "app.kite.WORK" -> model.perform(LaunchKind.WORK)
            "app.kite.CAR" -> model.perform(LaunchKind.CAR)
        }
        // The screenshot scripts, for the build's emulator only: on a real phone another app could
        // otherwise start Kite in a demo that mutes the voice.
        if (onEmulator) i.getStringExtra("demo")?.let { DemoScript.start(model, it) }
    }

    private val onEmulator: Boolean
        get() = android.os.Build.HARDWARE in setOf("ranchu", "goldfish") || android.os.Build.PRODUCT.startsWith("sdk_")
}
