package app.kite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.kite.core.KeyValue
import app.kite.core.LatLng
import app.kite.core.RoadCache
import app.kite.core.WazeCameraStore
import org.maplibre.compose.map.DefaultMapRuntime
import app.kite.ui.*
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.AttributionDefaults
import org.maplibre.compose.overlay.AttributionLinks
import org.maplibre.compose.overlay.attributions
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.spatialk.geojson.Position

/** Kite's start-up, the same on both phones; each phone's own shell calls [start] first thing. */
object Kite {
    /** One model for the app's life, so turning the phone or switching apps keeps everything as it was. */
    val model by lazy { AppModel() }
    private var started = false

    /** Hands the engine its storage and starts the background helpers, once per launch. */
    fun start() {
        if (started) return
        started = true
        KeyValue.store = SavedValues
        RoadCache.defaultFile = cacheDir() + "/roads.json"
        WazeCameraStore.defaultFile = cacheDir() + "/waze-cameras.json"
        // The heat map's tiles come from Kite itself (see HighwayHeat), so the map must know before it starts.
        runCatching { DefaultMapRuntime.configure { resourceProvider = HighwayHeat.provider } }
        Connectivity.start()
        LocationService.refreshPermission()
        Voice.warmUp()
        WazeWeb.install()
    }

    /** Kite is on screen again. */
    fun foreground() {
        LocationService.visible = true
        LocationService.refreshPermission()
    }

    /** Out of sight and not driving: GPS off to save the battery (back on when Kite is opened again). */
    fun background() {
        LocationService.visible = false
        LocationService.stop()
    }
}

/** The engine's small saved values, kept with the app's settings under "core.". */
object SavedValues : KeyValue {
    private val store by lazy { PrefStore("kite") }
    override fun getString(key: String): String? = store.getString("core.$key")
    override fun putString(key: String, value: String?) = store.putString("core.$key", value)
}

/** The whole app: the map, and whichever screen is over it. The same on both phones. */
@Composable
fun KiteScreen(model: AppModel = Kite.model) {
    val s = AppSettings
    val kiteMap = remember { KiteMap(model).also { DemoScript.kite = it; model.kiteMap = it } }
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
                s.liveCams, s.liveRoadCams, s.liveWebcams, LiveCamStore.version, model.cruise, model.routeReports)
        }.collect { kiteMap.render() }
    }
    // Kept on while driving; once arrived the phone goes in a pocket, so the screen may sleep.
    val screenOn = model.nav?.let { !it.arrived } ?: (model.cruise != null)
    LaunchedEffect(screenOn) { keepScreenOn(screenOn) }
    LaunchedEffect(s.wazeOn) { if (!s.wazeOn) WazeWeb.shutDown() }

    val ready = s.welcomed && !s.needsWazeChoice
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        LocationService.refreshPermission()
        if (!LocationService.authorized) LocationService.ask()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        model.mapWidthDp = maxWidth.value
        model.mapHeightDp = maxHeight.value
        model.glide.heightDp = maxHeight.value
        KiteMapView(model, kiteMap, Modifier.fillMaxSize())

        val nav = model.nav
        when {
            nav != null -> NavigationOverlay(model, nav)
            model.cruise != null -> model.cruise?.let { CruiseOverlay(model, it) }
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
    if (model.showLayers) LayersSheet(model.wazeLiveCount) { model.showLayers = false }
    if (model.addingStop) StopPicker(model)
    if (model.pickingOrigin) StopPicker(model, origin = true)
    model.picking?.let { PlacePicker(model, it) }
    model.streetView?.let { sv -> StreetViewScreen(sv) { model.streetView = null } }
    if (!s.welcomed || s.needsWazeChoice) {
        if (s.welcomed) WazeIntroScreen() else WelcomeScreen()
    }
    if (DemoScript.step == "icons") DemoIcons()
}

/** The map itself: Kite's style on MapLibre, with taps, long presses and camera moves handed to [kiteMap]. */
@Composable
fun KiteMapView(model: AppModel, kiteMap: KiteMap, modifier: Modifier) {
    val density = LocalDensity.current.density
    remember(density) { MapIcons.density = density; 0 }
    LaunchedEffect(Unit) { kiteMap.buildStyle() }
    val state = rememberMapState(baseStyle = kiteMap.baseStyle,
        initialCameraPosition = CameraPosition(center = Position(-0.1278, 51.5074), zoom = 11.0))
    val scope = rememberCoroutineScope()
    DisposableEffect(state) {
        kiteMap.attach(state, scope)
        onDispose { kiteMap.detach() }
    }
    // Kite's own style has loaded (not the empty one shown while it's fetched): go live.
    LaunchedEffect(state) {
        snapshotFlow { state.style.loadState to state.style.baseStyle }.collect { (load, base) ->
            if (load == StyleLoadState.Ready && kiteMap.isKiteStyle(base)) kiteMap.styleLoaded()
        }
    }
    LaunchedEffect(state) {
        state.events.collect { e ->
            when (e) {
                is MapEvent.CameraMoveStarted -> if (state.cameraMoveReason == CameraMoveReason.Gesture) kiteMap.gestureMove()
                is MapEvent.CameraMoved -> kiteMap.cameraMoved()
                is MapEvent.CameraMoveEnded -> kiteMap.cameraIdle()
                else -> {}
            }
        }
    }
    val interactions = remember(kiteMap) {
        MapInteractions {
            callbacks {
                click { onEvent { e -> e.position?.let { kiteMap.tapped(LatLng(it.latitude, it.longitude)) }; ClickResult.Consume } }
                longClick { onEvent { e -> e.position?.let { kiteMap.pressed(LatLng(it.latitude, it.longitude)) }; ClickResult.Consume } }
            }
            camera {
                pan { onStart { kiteMap.gestureStarted(pan = true) } }
                rotate { onStart { kiteMap.gestureStarted(pan = false) } }
            }
        }
    }
    MaplibreMap(modifier, state, interactions = interactions, overlay = {
        Box(Modifier.fillMaxSize().navigationBarsPadding()) {
            MapCredits(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp))
        }
    })
}

/**
 * The map's small "i" with its data credits (OpenStreetMap, Esri...). It starts folded, as the old apps
 * did, so the credits never sit over the tabs at the bottom; a tap opens them, moving the map folds them.
 */
@Composable
private fun MapCredits(modifier: Modifier) {
    val map = LocalMapState.current ?: return
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(map) {
        snapshotFlow { map.isCameraMoving && map.cameraMoveReason == CameraMoveReason.Gesture }.collect { if (it) open = false }
    }
    val credits by remember(map.style) { derivedStateOf { map.style.attributions() } }
    if (credits.isEmpty()) return
    Row(modifier.background(if (open) AttributionDefaults.ContainerColor else Color.Transparent, RoundedCornerShape(24.dp))
        .pointerInput(Unit) {}, verticalAlignment = Alignment.CenterVertically) {
        AttributionDefaults.button { open = !open }
        if (open) AttributionLinks(credits, Modifier.padding(end = 16.dp, top = 8.dp, bottom = 8.dp))
    }
}
