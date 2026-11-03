package app.kite

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.kite.ui.*
import org.maplibre.android.maps.MapView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Connectivity.start(applicationContext)
        LocationService.refreshPermission()
        Voice.warmUp()
        WazeWeb.install(this)
        handle(intent)
        setContent { KiteTheme { KiteScreen(model, this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        LocationService.visible = true
        LocationService.refreshPermission()
    }

    /** Out of sight and not driving: GPS off to save the battery (back on at onResume). */
    override fun onStop() {
        super.onStop()
        LocationService.visible = false
        LocationService.stop()
    }

    override fun onDestroy() {
        WazeWeb.uninstall(this)
        super.onDestroy()
    }

    /** Links shared from other apps, map links opened in Kite, and the Home, Work and Car shortcuts. */
    private fun handle(i: Intent?) {
        i ?: return
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

    /** The screen stays on while driving, like every sat nav. */
    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        /** One model for the app's life, so turning the phone or switching apps keeps everything as it was. */
        val model by lazy { AppModel() }
    }
}

@Composable
fun KiteScreen(model: AppModel, activity: MainActivity) {
    val ctx = activity
    val s = AppSettings
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val kiteMap = remember { KiteMap(model, ctx.applicationContext).also { DemoScript.kite = it } }
    val dark = LocalDark.current

    // The map's own location dot follows the same fixes the drive uses.
    DisposableEffect(Unit) {
        val modelUpdate = LocationService.onUpdate
        LocationService.onUpdate = { f -> modelUpdate?.invoke(f); kiteMap.pushFix(f) }
        onDispose { LocationService.onUpdate = modelUpdate }
    }
    LaunchedEffect(dark) { kiteMap.applyDark(dark) }

    // Redraw the map's layers whenever anything they show changes.
    LaunchedEffect(Unit) {
        snapshotFlow {
            listOf(model.mapTick, model.results, model.selected?.id, model.directions, model.nav, model.openNowOnly, model.rating4Only,
                s.traffic, s.satellite, s.terrain, s.transitLines, s.speedCameras, s.plateCameras, s.wazeOnMap, s.wazeHeatmap,
                s.home, s.work, s.parking, s.saved, LocationService.authorized, model.tab,
                s.welcomed, s.noGoogle, s.surveillanceCameras,
                s.liveCams, s.liveRoadCams, s.liveWebcams, LiveCamStore.version)
        }.collect { kiteMap.render() }
    }
    LaunchedEffect(model.nav != null) { activity.keepScreenOn(model.nav != null) }
    LaunchedEffect(s.wazeOn) { if (!s.wazeOn) WazeWeb.shutDown() }

    val ready = s.welcomed && !s.needsWazeChoice
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        LocationService.refreshPermission()
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == false && r[Manifest.permission.ACCESS_COARSE_LOCATION] == false) LocationService.denied = true
    }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        LocationService.refreshPermission()
        if (!LocationService.authorized) {
            val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
            perms.launch(list.toTypedArray())
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        model.mapWidthDp = maxWidth.value
        model.mapHeightDp = maxHeight.value
        model.glide.heightDp = maxHeight.value
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            MapView(c).also { v ->
                v.onCreate(null)
                kiteMap.attach(v)
                lifecycle.addObserver(LifecycleEventObserver { _, e ->
                    when (e) {
                        Lifecycle.Event.ON_START -> v.onStart()
                        Lifecycle.Event.ON_RESUME -> v.onResume()
                        Lifecycle.Event.ON_PAUSE -> v.onPause()
                        Lifecycle.Event.ON_STOP -> v.onStop()
                        Lifecycle.Event.ON_DESTROY -> v.onDestroy()
                        else -> {}
                    }
                })
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) v.onStart()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) v.onResume()
            }
        })

        val nav = model.nav
        when {
            nav != null -> NavigationOverlay(model, nav)
            model.directions != null -> DirectionsOverlay(model)
            else -> when (model.tab) {
                AppTab.MAP -> BrowseOverlay(model)
                AppTab.SAVED -> SavedPage(model)
                AppTab.SETTINGS -> SettingsPage(model)
            }
        }
        val info = model.wazeInfo
        if (info != null && nav == null && model.tab == AppTab.MAP) {
            WazeInfoToast(model, info)
        }
        val cam = model.liveCam
        if (cam != null && nav == null && model.tab == AppTab.MAP) {
            LiveCamCard(model, cam)
        }
    }

    model.surveillanceInfo?.let { cam ->
        val details = listOfNotNull(cam.operatorName?.let { "Operator: $it" }, cam.manufacturer?.let { "Manufacturer: $it" },
            cam.surveillance?.let { "Watches: $it" }, cam.mount?.let { "Mount: $it" }, cam.direction?.let { "Direction: $it" }).joinToString("\n")
        AlertDialog(onDismissRequest = { model.surveillanceInfo = null },
            confirmButton = { TextButton({ model.surveillanceInfo = null }) { Text("OK") } },
            title = { Text(if (cam.isALPR) "Plate reader" else "Surveillance camera") },
            text = { Text(details.ifEmpty { "Details not mapped in OpenStreetMap." }) })
    }
    if (model.showLayers) LayersSheet { model.showLayers = false }
    if (model.addingStop) StopPicker(model)
    model.picking?.let { PlacePicker(model, it) }
    model.streetView?.let { sv -> StreetViewScreen(sv) { model.streetView = null } }
    if (!s.welcomed || s.needsWazeChoice) {
        if (s.welcomed) WazeIntroScreen() else WelcomeScreen()
    }
    if (DemoScript.step == "icons") DemoIcons()
}
