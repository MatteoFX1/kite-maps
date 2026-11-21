package app.kite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kite.core.*
import app.kite.map.*
import app.kite.map.Expression.Companion.any
import app.kite.map.Expression.Companion.coalesce
import app.kite.map.Expression.Companion.color
import app.kite.map.Expression.Companion.eq
import app.kite.map.Expression.Companion.get
import app.kite.map.Expression.Companion.has
import app.kite.map.Expression.Companion.interpolate
import app.kite.map.Expression.Companion.lineProgress
import app.kite.map.Expression.Companion.linear
import app.kite.map.Expression.Companion.literal
import app.kite.map.Expression.Companion.not
import app.kite.map.Expression.Companion.step
import app.kite.map.Expression.Companion.stop
import app.kite.map.Expression.Companion.switchCase
import app.kite.map.Expression.Companion.toColor
import app.kite.map.Expression.Companion.zoom
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.BaseStyle
import kotlin.math.*

/**
 * The full-screen map: OpenFreeMap Liberty painted in Kite's colors, plus Kite's own layers
 * (result pins, routes with traffic, the navigation puck, live traffic, satellite). The same
 * layers, in the same order, on both phones.
 */
class KiteMap(private val model: AppModel) {
    companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        /** How see-through cameras and reports off your route are while driving. */
        const val OFF_ROUTE_OPACITY = 0.3
        /** Reports that always keep their own icon, never joining a numbered bubble. */
        val NEVER_GROUPED = setOf(WazeAlert.Kind.POLICE, WazeAlert.Kind.ACCIDENT)
        private fun c(hex: Long, a: Float = 1f) = Color.argb((a * 255).toInt(), ((hex shr 16) and 0xFF).toInt(), ((hex shr 8) and 0xFF).toInt(), (hex and 0xFF).toInt())
        private fun hex(l: Long) = fmt("#%06X", l and 0xFFFFFF)
    }

    var dark = true
        private set
    private var map: MapHandle? = null
    private var style: Style? = null
    /** The style the map loads: empty until Kite's is ready, then Kite's (built once per launch). */
    private val emptyStyle = BaseStyle.Json("{\"version\":8,\"sources\":{},\"layers\":[]}")
    var baseStyle by mutableStateOf<BaseStyle>(emptyStyle)
        private set
    private var building: Job? = null
    private val scope get() = model.scope
    private var styleReady = false
    private var movedToUser = false
    private var pinDroppedAt = 0L
    private val originalLabels = mutableMapOf<String, Triple<PropertyValue<*>, PropertyValue<*>, PropertyValue<*>>>()
    private var roadQuery: Job? = null
    private var lastResultsKey = ""
    private var lastRouteKey = ""
    private var lastMineKey = ""
    private var lastPuckKey = ""
    private var lastHeadKey = ""
    private var lastNextRoadKey = ""
    private var lastRoadNameKey = ""
    private var styledDriving: Boolean? = null
    private var lastCameraKey = ""
    private var lastSignalsKey = ""
    private val cameras = mutableMapOf<Long, RoadCamera>()
    private val cameraCells = mutableSetOf<String>()
    private var cameraFetch: Job? = null
    private var wazeCameraFetch: Job? = null
    private var surveillanceFetch: Job? = null
    private var surveillanceWasOn = false
    private val surveillanceCells = mutableSetOf<String>()
    private val surveillance = mutableMapOf<Long, SurveillanceCamera>()
    private var railFetch: Job? = null
    private var wazeAlerts: List<WazeAlert> = emptyList()
    /** Screenshot runs only: made-up reports stay put instead of being replaced by live ones. */
    private var fakeWaze = false

    fun showDemoWaze(list: List<WazeAlert>) {
        fakeWaze = true
        wazeAlerts = list
        wazeAt = now()
        model.wazeLiveCount = list.size
        model.updateAreaRisk(list)
        lastWazeKey = ""
        render()
    }
    private var wazeBox: Box? = null
    private var wazeAt = 0L
    private var wazeFetch: Job? = null
    private var wazeFailedAt = 0L
    private var wazeAskedAt = 0L
    private var wazeRetryQueued = false
    private var lastWazeKey = ""
    private data class RailBox(val s: Double, val w: Double, val n: Double, val e: Double, val z: Int)
    private var railBox: RailBox? = null
    private fun now() = nowMillis()

    /**
     * Builds Kite's style once: Liberty (fresh, or the copy saved last time when offline) with
     * Kite's layers and colours, then hands it to the map to load.
     */
    fun buildStyle() {
        if (building != null || style != null) return
        building = scope.launch {
            val cache = cacheDir() + "/liberty-style.json"
            var base: Map<String, Any?>? = null
            for (attempt in 0 until 20) {
                val fresh = withContext(Dispatchers.Default) {
                    runCatching { Http.get(STYLE_URL).takeIf { it.code == 200 }?.body }.getOrNull()
                }
                base = fresh?.let { Json.obj(it) }?.also { Disk.write(cache, fresh) }
                    ?: Disk.read(cache)?.let { Json.obj(it) }
                if (base != null) break
                delay(min(30_000L, 2_000L * (attempt + 1)))
            }
            val b = base ?: return@launch
            val st = Style(b)
            for (l in st.layers) if (l.isSymbol) originalLabels[l.id] = Triple(l.textColor, l.textHaloColor, l.textHaloWidth)
            MapStyler.reset()
            MapIcons.register(st, dark)
            addVelaLayers(st)
            style = st
            styleReady = false
            repaint(force = true)
            baseStyle = BaseStyle.Json(st.json())
        }
    }

    /** Whether [b] is Kite's own style (built), not the empty one shown while it's fetched. */
    fun isKiteStyle(b: BaseStyle) = style != null && b === baseStyle && b !== emptyStyle

    /** The map on screen has started (or come back): keep a handle to it for the camera and the style. */
    fun attach(state: MapState, mapScope: CoroutineScope) {
        val m = MapHandle(state, mapScope)
        map = m
        model.map = m
        model.glide.frames = mapScope
        m.style = style
        if (!movedToUser) state.setCameraPosition(CameraPosition(LatLng(51.5074, -0.1278), 11.0).let {
            org.maplibre.compose.camera.CameraPosition(org.maplibre.spatialk.geojson.Position(it.target.lng, it.target.lat), it.zoom) })
        WazeHistory.onChange = { render() }
        LiveCamStore.onChange = { render() }
        LocationService.last?.let { moveToUserOnce(it) }
    }

    fun detach() {
        style?.detach()
        styleReady = false
        map = null
        model.map = null
    }

    /** The map has loaded Kite's style: changes go to it from now on. */
    fun styleLoaded() {
        val m = map ?: return
        val st = style ?: return
        st.goLive(m.state)
        m.style = st
        styleReady = true
        styledDriving = null
        lastResultsKey = ""; lastRouteKey = ""; lastMineKey = ""; lastPuckKey = ""; lastHeadKey = ""; lastNextRoadKey = ""; lastCameraKey = ""; lastSignalsKey = ""; lastWazeKey = ""; lastLiveKey = ""; lastMeKey = ""
        LocationService.last?.let { pushFix(it) }
        render()
        loadCameras(m); loadWazeCameras(m); loadWaze(m); loadLiveCams(m)
    }

    /** The camera stopped moving: fetch what the new view needs and redraw. */
    fun cameraIdle() {
        val m = map ?: return
        loadCameras(m); loadWazeCameras(m); loadSurveillance(m); loadWaze(m); loadLiveCams(m); render(); loadRail(m); noteHeading(m)
    }

    fun cameraMoved() { map?.let { noteHeading(it) } }

    /** Dragging or turning the map by hand. */
    @Suppress("UNUSED_PARAMETER")
    fun gestureStarted(pan: Boolean) {
        if (model.nav == null && model.browseFollowing) model.browseFollowing = false
    }

    /** Any move of the map by hand while navigating stops following the car. */
    fun gestureMove() { model.nav?.let { if (it.following) it.following = false } }

    private var lastMeKey = ""

    /** Your blue dot (off a route; while navigating the car arrow shows instead). */
    fun pushFix(f: Fix) {
        val st = style ?: return
        moveToUserOnce(f)
        val show = model.nav == null && LocationService.authorized
        val key = if (show) fmt("%.6f,%.6f,%.0f,%.0f", f.lat, f.lng, f.accuracy, if (f.courseKnown) f.course else -1.0) else ""
        if (key == lastMeKey) return
        lastMeKey = key
        if (!show) { setShape(st, "vela-me", emptyList()); return }
        // Metres to screen points at zoom 0, so the map can grow the accuracy ring with the zoom.
        val r0 = f.accuracy * 512 / (40_075_016.686 * cos(f.lat * PI / 180))
        setShape(st, "vela-me", listOf(point(f.coord, mapOf("r0" to r0, "bearing" to if (f.courseKnown && f.speed > 0.5) f.course else null))))
    }

    /** A PNG of the map as it is on screen (for "Share diagnostics"), or null if it can't be made within a few seconds. */
    suspend fun picture(): ByteArray? {
        val m = map ?: return null
        val st = style ?: return null
        val cam = m.state.cameraPosition
        return withTimeoutOrNull(8000) {
            runCatching {
                val shot = org.maplibre.compose.map.DefaultMapRuntime.instance.createSnapshotter(BaseStyle.Json(st.json()))
                try {
                    val img = shot.capture(androidx.compose.ui.unit.DpSize(model.mapWidthDp.dp, model.mapHeightDp.dp)) {
                        cameraPosition = cam
                        density = androidx.compose.ui.unit.Density(MapIcons.density)
                    }
                    val px = IntArray(img.width * img.height).also { img.readPixels(it) }
                    withContext(Dispatchers.Default) { Png.encode(px, img.width, img.height) }
                } finally { shot.close() }
            }.getOrNull()
        }
    }

    /** Screenshot runs: the demo has put the map somewhere, so the first fix mustn't move it back. */
    fun keepCamera() { movedToUser = true }

    private fun moveToUserOnce(f: Fix) {
        val m = map ?: return
        if (!styleReady) return
        if (movedToUser) return
        movedToUser = true
        if (model.results.isNotEmpty() || model.selected != null || model.directions != null) return
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(f.coord, 15.0))
    }

    fun applyDark(d: Boolean) {
        if (dark == d) return
        dark = d
        repaint()
    }

    private fun repaint(force: Boolean = false) {
        val style = style ?: return
        if (!styleReady && !force) return
        if (!dark) {
            for (l in style.layers) if (l.isSymbol) originalLabels[l.id]?.let { l.setProperties(it.first, it.second, it.third) }
        }
        MapStyler.apply(style, dark)
        MapIcons.swapPuck(style, dark)
        style.getLayer("vela-rail-case")?.setProperties(lineColor(if (dark) c(0x000000, 0.9f) else c(0xFFFFFF, 0.9f)))
        val text = if (dark) c(0xE8EAED) else c(0x202124)
        val halo = if (dark) c(0x0B1526) else Color.WHITE
        for (id in listOf("vela-mine", "vela-results", "vela-selected")) {
            style.getLayer(id)?.setProperties(textColor(text), textHaloColor(halo), textHaloWidth(1.5f))
        }
        lastResultsKey = ""
        lastRouteKey = ""
    }

    private fun firstSymbolLayer(style: Style): String? = style.layers.firstOrNull { it.isSymbol }?.id

    private fun line(id: String, src: String, color: Int, width: Float) = LineLayer(id, src).withProperties(
        lineColor(color), lineWidth(width), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND))

    /** Icons close together join into one bubble up to zoom 13 (a whole town in view); closer in, every icon shows. */
    private fun Style.groupedSource(id: String) {
        if (getSource(id) == null) addSource(GeoJsonSource(id, emptyList(),
            GeoJsonOptions().withCluster(true).withClusterRadius(44).withClusterMaxZoom(13)))
    }

    /** The numbered bubble for a group: a dark circle with a colored ring, bigger for more. */
    private fun groupBubbles(style: Style, id: String, ring: Int, from: Float = 11f) {
        val count = get("point_count")
        style.addLayer(CircleLayer("$id-bubble", id).withProperties(
            circleColor(Color.rgb(0x0F, 0x17, 0x2A)), circleOpacity(0.92f),
            circleStrokeColor(Color.rgb(ring shr 16 and 0xFF, ring shr 8 and 0xFF, ring and 0xFF)), circleStrokeWidth(3f),
            circleRadius(step(count, literal(14f), stop(10, 17f), stop(50, 21f)))).apply { minZoom = from; setFilter(has("point_count")) })
        style.addLayer(SymbolLayer("$id-count", id).withProperties(
            textField(Expression.toString(count)), textFont(arrayOf("Noto Sans Bold")), textSize(13f),
            textColor(Color.WHITE), textAllowOverlap(true), textIgnorePlacement(true)).apply { minZoom = from; setFilter(has("point_count")) })
    }

    private fun addVelaLayers(style: Style) {
        fun grouped(id: String) = style.groupedSource(id)
        fun src(id: String, metrics: Boolean = false) {
            if (style.getSource(id) == null) style.addSource(GeoJsonSource(id, emptyList(), GeoJsonOptions().withLineMetrics(metrics)))
        }
        // Satellite (Esri World Imagery) under the roads, off unless picked in Layers.
        style.addSource(RasterSource("vela-sat", "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            19, "Imagery © Esri"))
        val satLayer = RasterLayer("vela-sat", "vela-sat").withProperties(visibility(Property.NONE))
        style.layers.firstOrNull { it.id.startsWith("tunnel_") }?.let { style.addLayerBelow(satLayer, it.id) } ?: style.addLayer(satLayer)

        style.addSource(RasterSource("vela-terrain", "https://services.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer/tile/{z}/{y}/{x}",
            16, "Hillshade © Esri"))
        val hill = RasterLayer("vela-terrain", "vela-terrain").withProperties(rasterOpacity(if (dark) 0.22f else 0.32f), visibility(Property.NONE))
        style.layers.firstOrNull { it.id.startsWith("tunnel_") || it.id.startsWith("road_") }?.let { style.addLayerBelow(hill, it.id) } ?: style.addLayer(hill)

        // Google's live traffic tiles, the same keyless overlay Vela draws.
        val trafficURL = "https://www.google.com/maps/vt/pb=!1m4!1m3!1i{z}!2i{x}!3i{y}!2m9!1e2!2straffic!3i999999" +
            "!4m2!1sincidents!2s1!4m2!1sincidents_text!2s1!3m8!2sen!3sus!5e1105!12m4!1e68!2m2!1sset!2sRoadmap!4e0!5m1!1e0"
        style.addSource(RasterSource("vela-traffic", trafficURL, 16))
        val trafficLayer = RasterLayer("vela-traffic", "vela-traffic").withProperties(rasterOpacity(0.6f), rasterFadeDuration(900f), visibility(Property.NONE))
        (style.getLayer("building-3d") ?: style.getLayer("building"))?.let { style.addLayerAbove(trafficLayer, it.id) } ?: style.addLayer(trafficLayer)

        val labels = firstSymbolLayer(style)
        fun insert(l: Layer) { if (labels != null) style.addLayerBelow(l, labels) else style.addLayer(l) }
        src("vela-route-alt"); src("vela-route", metrics = true); src("vela-route-traffic"); src("vela-route-done")

        val done = line("vela-route-done", "vela-route-done", c(0x64748B), 10f).withProperties(lineOpacity(0.5f))
        val altEdge = line("vela-route-alt-edge", "vela-route-alt", c(0x115E59), 11f).withProperties(lineOpacity(0.6f))
        val altFill = line("vela-route-alt", "vela-route-alt", c(0x5EEAD4), 7f).withProperties(lineOpacity(0.6f))
        // The light look: one plain teal line, no darker edge and no chevrons along it.
        val edge = line("vela-route-edge", "vela-route", c(0x0F766E), 15f).withProperties(lineOpacity(0f))
        val fill = line("vela-route-line", "vela-route", c(0x2DD4BF), 10f).withProperties(
            lineGradient(interpolate(linear(), lineProgress(), stop(0, color(c(0x2DD4BF))), stop(1, color(c(0x22D3EE))))))
        val chevrons = SymbolLayer("vela-route-chevrons", "vela-route").withProperties(
            symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), symbolSpacing(70f), iconImage("route-chevron"),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconAllowOverlap(true), iconIgnorePlacement(true),
            iconKeepUpright(false), iconOpacity(0.55f), visibility(Property.NONE)).apply { minZoom = 13f }
        listOf(done, altEdge, altFill, edge, fill, chevrons).forEach { insert(it) }

        src("vela-transit")
        val walk = line("vela-transit-walk", "vela-transit", c(0x5F6368), 5f).withProperties(lineDasharray(arrayOf(0.1f, 1.8f)))
        walk.setFilter(eq(get("walk"), literal(1)))
        val rideEdge = line("vela-transit-edge", "vela-transit", c(0x1A1A1A, 0.55f), 10f)
        rideEdge.setFilter(eq(get("walk"), literal(0)))
        val ride = line("vela-transit-ride", "vela-transit", c(0x1F6FEB), 7f).withProperties(lineColor(toColor(get("color"))))
        ride.setFilter(eq(get("walk"), literal(0)))
        listOf(walk, rideEdge, ride).forEach { insert(it) }
        for ((level, col) in listOf(1 to 0xF59E0BL, 2 to 0xEF4444L, 3 to 0xB91C1CL)) {
            val l = line("vela-route-traffic-$level", "vela-route-traffic", c(col), 10f)
            l.setFilter(eq(get("level"), literal(level)))
            insert(l)
        }

        src("vela-rail")
        val railCase = line("vela-rail-case", "vela-rail", if (dark) c(0x000000, 0.9f) else c(0xFFFFFF, 0.9f), 5.5f)
        val rail = line("vela-rail", "vela-rail", Color.GRAY, 3.2f).withProperties(lineColor(toColor(get("color"))))
        for (l in listOf(railCase, rail)) { l.minZoom = 10f; l.setProperties(visibility(Property.NONE)); insert(l) }

        src("vela-signals")
        style.addLayer(SymbolLayer("vela-signals", "vela-signals").withProperties(
            iconImage("traffic-light"), iconAllowOverlap(true), iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            iconSize(interpolate(linear(), zoom(), stop(14, 0.75f), stop(17, 1.0f)))).apply { minZoom = 14f })

        // Live camera photos, under the speed cameras: Windy webcams from zoom 6 (a region), road
        // cameras from zoom 9 (a city). Zoomed out over a busy city (central London) they join into
        // numbered blue bubbles, like the speed cameras (Matteo, 2026-11-13).
        grouped("vela-livecams")
        style.addLayer(SymbolLayer("vela-livecams", "vela-livecams").withProperties(
            iconImage(get("icon")), iconAllowOverlap(false), iconPadding(2f),
            iconSize(interpolate(linear(), zoom(), stop(6, 0.6f), stop(9, 0.7f), stop(13, 0.9f), stop(16, 1.05f))))
            .apply { minZoom = LiveCams.WEBCAM_MIN_ZOOM.toFloat(); setFilter(not(has("point_count"))) })
        groupBubbles(style, "vela-livecams", 0x3B82F6, LiveCams.WEBCAM_MIN_ZOOM.toFloat())

        // Zoomed out over a busy city, cameras close together join into one bubble with a number.
        grouped("vela-cameras")
        val big = eq(get("big"), literal(1))
        style.addLayer(SymbolLayer("vela-cameras", "vela-cameras").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true),
            iconSize(interpolate(linear(), zoom(),
                stop(11, switchCase(big, literal(1.0), literal(0.7))),
                stop(14, switchCase(big, literal(1.33), literal(0.92))),
                stop(17, switchCase(big, literal(1.65), literal(1.15))))),
            // While driving, cameras off your route fade back (see renderCameras).
            iconOpacity(coalesce(get("op"), literal(1.0)))).apply { minZoom = 11f; setFilter(not(has("point_count"))) })
        groupBubbles(style, "vela-cameras", 0xFF8A00)

        src("vela-waze-heat")
        val heat = HeatmapLayer("vela-waze-heat", "vela-waze-heat").withProperties(visibility(Property.NONE))
        style.getLayer("vela-route-alt-edge")?.let { style.addLayerBelow(heat, "vela-route-alt-edge") } ?: insert(heat)
        // Highway Radar's police heat map: tiles the map asks Kite for, coloured on the phone (see HighwayHeat).
        style.addSource(RasterSource("hr-heat", HighwayHeat.TILES, HighwayHeat.MAX_ZOOM, minZoom = HighwayHeat.MIN_ZOOM))
        style.addLayerBelow(RasterLayer("hr-heat", "hr-heat").withProperties(
            rasterOpacity(interpolate(linear(), zoom(), stop(10, 0.9f), stop(13, 0.8f), stop(16, 0.6f))),
            rasterFadeDuration(0f), visibility(Property.NONE)), "vela-waze-heat")
        src("vela-waze")
        style.addLayer(SymbolLayer("vela-waze", "vela-waze").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true),
            iconSize(interpolate(linear(), zoom(), stop(11, 0.62f), stop(15, 0.95f))),
            iconOpacity(get("op"))).apply { minZoom = 11f })
        // Every other report (closures, hazards, jams) joins into numbered bubbles when zoomed
        // out, so a busy city stays readable. Police and crashes above always stay on their own.
        grouped("vela-waze-group")
        style.addLayerBelow(SymbolLayer("vela-waze-group", "vela-waze-group").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true),
            iconSize(interpolate(linear(), zoom(), stop(11, 0.62f), stop(15, 0.95f))),
            iconOpacity(get("op"))).apply { minZoom = 11f; setFilter(not(has("point_count"))) }, "vela-waze")
        groupBubbles(style, "vela-waze-group", 0xEF4444)

        src("vela-mine")
        style.addLayer(SymbolLayer("vela-mine", "vela-mine").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true), textField(get("label")), textFont(arrayOf("Noto Sans Bold")),
            textSize(11f), textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 1.1f)), textOptional(true),
            textMaxWidth(8f)).apply { minZoom = 9f })

        src("vela-results")
        style.addLayer(SymbolLayer("vela-results", "vela-results").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true), textField(get("label")), textFont(arrayOf("Noto Sans Bold")),
            textSize(12f), textAnchor(Property.TEXT_ANCHOR_LEFT), textOffset(arrayOf(1.4f, 0f)), textOptional(true),
            textMaxWidth(8f), textJustify(Property.TEXT_JUSTIFY_LEFT), symbolSortKey(get("rank"))))

        src("vela-selected")
        style.addLayer(SymbolLayer("vela-selected", "vela-selected").withProperties(
            iconImage(get("icon")), iconAnchor(get("anchor")), iconAllowOverlap(true), textAllowOverlap(false),
            textField(get("label")), textFont(arrayOf("Noto Sans Bold")), textSize(13f), textAnchor(Property.TEXT_ANCHOR_TOP),
            textOffset(arrayOf(0f, 0.4f)), textOptional(true)))

        src("vela-route-bubbles")
        for (selected in listOf(false, true)) {
            val b = SymbolLayer("vela-route-bubble-${if (selected) 1 else 0}", "vela-route-bubbles").withProperties(
                iconImage(if (selected) "bubble-sel" else "bubble-alt"), iconTextFit(Property.ICON_TEXT_FIT_BOTH),
                iconTextFitPadding(arrayOf(6f, 12f, 6f, 12f)), textField(get("label")), textFont(arrayOf("Noto Sans Bold")),
                textSize(15f), textColor(if (selected) Color.WHITE else c(0x202124)), iconAllowOverlap(true), textAllowOverlap(true))
            b.setFilter(eq(get("sel"), literal(if (selected) 1 else 0)))
            style.addLayer(b)
        }

        src("vela-turn-arrow")
        val shaft = eq(get("part"), literal("shaft"))
        val arrowShadow = line("vela-turn-arrow-shadow", "vela-turn-arrow", Color.BLACK, 22f).withProperties(
            lineOpacity(0.35f), lineBlur(4f), lineTranslate(arrayOf(0f, 2f)))
        arrowShadow.setFilter(shaft)
        // A big white arrow with a teal edge, like Waze's, so it's clear at a glance.
        val arrowEdge = line("vela-turn-arrow-edge", "vela-turn-arrow", c(0x0F766E), 23f); arrowEdge.setFilter(shaft)
        val arrowLine = line("vela-turn-arrow", "vela-turn-arrow", Color.WHITE, 15f); arrowLine.setFilter(shaft)
        // The head has a source of its own, updated only when the turn changes: the map fades a symbol
        // in afresh each time its source changes, which would keep the head see-through.
        src("vela-turn-head")
        val head = SymbolLayer("vela-turn-arrow-head", "vela-turn-head").withProperties(
            iconImage("turn-head"), iconRotate(get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconAllowOverlap(true), iconIgnorePlacement(true))
        listOf(arrowShadow, arrowEdge, arrowLine, head).forEach { style.addLayer(it) }

        // The name of the road you're about to turn onto, lit up in a teal pill on the road itself.
        // Its own source, updated only when the turn changes, like the head.
        src("vela-next-road")
        style.addLayer(SymbolLayer("vela-next-road", "vela-next-road").withProperties(
            iconImage("road-pill"), iconTextFit(Property.ICON_TEXT_FIT_BOTH), iconTextFitPadding(arrayOf(5f, 11f, 5f, 11f)),
            textField(get("label")), textFont(arrayOf("Noto Sans Bold")), textSize(16f), textColor(c(0x06201C)),
            textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT), iconAllowOverlap(true), textAllowOverlap(true)))

        // The road you're on, written on the road just ahead of the car like a painted marking
        // (Matteo's pick over a box on the screen). Lies flat and follows the road's bends.
        // The map's own named roads, drawn too faintly to see: read back in the driving view with no
        // route, so the name follows the road you're on round its bends.
        if (style.hasSource("openmaptiles")) {
            style.addLayer(LineLayer("vela-road-query", "openmaptiles").apply {
                sourceLayer = "transportation_name"
                // Not quite 0: a layer that draws nothing at all can't be asked what's under it.
                setProperties(lineOpacity(0.01f), lineWidth(3f))
            })
        }
        src("vela-road-name")
        style.addLayer(SymbolLayer("vela-road-name", "vela-road-name").withProperties(
            textField(get("label")), symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
            textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_MAP), textRotationAlignment(Property.TEXT_ROTATION_ALIGNMENT_MAP),
            textKeepUpright(true), textFont(arrayOf("Noto Sans Bold")),
            textSize(interpolate(linear(), zoom(), stop(13, 15f), stop(15, 18f), stop(17, 22f), stop(18, 26f))),
            textLetterSpacing(0.08f), textTransform(Property.TEXT_TRANSFORM_UPPERCASE), textColor(c(0x0E3B36)),
            textHaloColor("rgba(255,255,255,0.95)"), textHaloWidth(2f), textAllowOverlap(true), textIgnorePlacement(true)))

        // Your blue dot off a route: how sure the GPS is as a soft ring, then the dot, with a pointer
        // the way you're going once you're moving.
        src("vela-me")
        style.addLayer(CircleLayer("vela-me-accuracy", "vela-me").withProperties(
            circleColor(c(0x14B8A6)), circleOpacity(0.15f),
            circleRadius(Expression(listOf("interpolate", listOf("exponential", 2), listOf("zoom"),
                0, listOf("get", "r0"), 22, listOf("*", listOf("get", "r0"), 4_194_304))))))
        style.addLayer(SymbolLayer("vela-me-heading", "vela-me").withProperties(
            iconImage("me-heading"), iconRotate(get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP), iconAllowOverlap(true), iconIgnorePlacement(true))
            .apply { setFilter(has("bearing")) })
        style.addLayer(SymbolLayer("vela-me", "vela-me").withProperties(
            iconImage("me-dot"), iconAllowOverlap(true), iconIgnorePlacement(true)))

        src(NavGlide.PUCK)
        style.addLayer(SymbolLayer(NavGlide.PUCK, NavGlide.PUCK).withProperties(
            iconImage("puck"), iconRotate(get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP), iconAllowOverlap(true), iconIgnorePlacement(false)))
    }

    // Render

    /** The road a turn goes onto, for the pill on the map: "Stoneham Way" from "Turn left onto Stoneham Way". */
    private fun nextRoadName(m: Maneuver): String? {
        val text = SpokenText.forScreen(m.instruction)
        val at = text.lastIndexOf(" onto ")
        val road = m.road
        val name = when {
            at >= 0 -> text.substring(at + 6)
            !road.isNullOrEmpty() && text.contains(road) -> SpokenText.forScreen(road)
            else -> null
        }?.trim()?.trimEnd('.')?.trim()
        return name?.takeIf { it.isNotEmpty() && it.length <= 40 }
    }

    private fun setShape(style: Style, id: String, features: List<Feature>) {
        (style.getSource(id) as? GeoJsonSource)?.setGeoJson(features)
    }

    private fun point(p: LatLng, props: Map<String, Any?> = emptyMap()) = Feature.point(p, props)

    fun polyline(pts: List<LatLng>, props: Map<String, Any?> = emptyMap()) = Feature.line(pts, props)

    private fun visible(style: Style, id: String, on: Boolean) {
        style.getLayer(id)?.setProperties(visibility(if (on) Property.VISIBLE else Property.NONE))
    }

    fun render() {
        val m = map ?: return
        val style = style ?: return
        if (!styleReady) return
        val s = model.settings
        val navigating = model.nav != null
        // The driving view with no route gets the driving map too, without the route line.
        val driving = navigating || model.cruise != null
        LocationService.last?.let { pushFix(it) } ?: run { if (lastMeKey != "") { lastMeKey = ""; setShape(style, "vela-me", emptyList()) } }
        // Not before the welcome screen's choice: "Open data only" means nothing ever went to Google.
        visible(style, "vela-traffic", s.traffic && !navigating && !s.noGoogle && s.welcomed)
        // The Surveillance layer just switched on: fetch it now, not at the next pan (only on the
        // switch, so a failed fetch keeps its 30-second pause).
        if (s.surveillanceCameras && !surveillanceWasOn) loadSurveillance(m)
        surveillanceWasOn = s.surveillanceCameras
        visible(style, "vela-sat", s.satellite)
        visible(style, "vela-terrain", s.terrain && !s.satellite)
        for (id in listOf("vela-rail", "vela-rail-case")) visible(style, id, s.transitLines && !navigating)
        if (styledDriving != driving) {
            MapStyler.drive(style, driving)
            styledDriving = driving
            style.getLayer("vela-route-line")?.setProperties(lineWidth(if (navigating) 12f else 10f))
            style.getLayer("vela-route-edge")?.setProperties(lineWidth(if (navigating) 22f else 15f))
        }
        for (id in listOf("building", "building-3d")) visible(style, id, !s.satellite && !driving)
        renderResults(style)
        renderRoutes(style)
        renderPuck(style)
        renderCameras(style)
        renderLiveCams(m, style)
        renderSignals(style)
        renderRoadName(style)
        renderWaze(style)
        updateHeat()
        renderMine(style)
        if (s.transitLines) loadRail(m)
    }

    /**
     * The road's name on the road ahead: 40 to 220 m along the route while navigating (left off
     * when the next turn is close, where the turn arrow and the new road's name need the room),
     * or straight ahead along your heading in the driving view with no route.
     */
    private fun renderRoadName(style: Style) {
        var line: List<LatLng> = emptyList()
        var name: String? = null
        var asked = false
        val n = model.nav
        val c = model.cruise
        if (n != null) {
            val p = n.progress
            val road = n.currentRoad
            val next = n.nextManeuver
            if (n.following && !n.arrived && p != null && road != null &&
                (next == null || next.alongMeters - p.alongMeters > 260 || next.type == ManeuverType.STRAIGHT)) {
                val cum = Geo.cumulative(n.route.polyline)
                val total = cum.last()
                val a = p.alongMeters + 40; val b = minOf(p.alongMeters + 220, total)
                if (b - a > 80) { line = Geo.slice(n.route.polyline, a, b, cum); name = road }
            }
        } else if (c != null && model.browseFollowing) {
            val f = c.fix
            val road = c.road ?: c.ref
            val m = map
            if (f != null && f.courseKnown && f.speed > 2 && road != null && m != null) {
                // Along the map's own line for the road you're on, so it stays on the road round a bend;
                // nothing when the map doesn't have it here (never floating off over a field).
                val pt = m.toScreenLocation(f.coord)
                if (pt != null) {
                    asked = true
                    if (roadQuery?.isActive != true) roadQuery = scope.launch {
                        val found = m.queryRenderedFeatures(pt.x - 300, pt.y - 450, pt.x + 300, pt.y + 250, "vela-road-query")
                        val lines = found.filter { (c.road != null && it.string("name") == c.road) || (c.ref != null && it.string("ref") == c.ref) }
                            .flatMap { it.lines }
                        roadQuery = null
                        val path = RoadAhead.path(lines, f.coord, f.course)
                        showRoadName(style, if (path != null) road else null, path ?: emptyList())
                    }
                }
            }
        }
        if (asked) return
        showRoadName(style, name, line)
    }

    private fun showRoadName(style: Style, name: String?, line: List<LatLng>) {
        val key = (name ?: "") + (line.firstOrNull()?.let { "${(it.lat * 20_000).toInt()},${(it.lng * 20_000).toInt()}" } ?: "")
        if (key == lastRoadNameKey) return
        lastRoadNameKey = key
        setShape(style, "vela-road-name", if (name != null && line.size >= 2) listOf(polyline(line, mapOf("label" to name))) else emptyList())
    }

    private fun renderMine(style: Style) {
        val s = model.settings
        val hidden = model.nav != null || model.directions != null
        val items = mutableListOf<Triple<Place, String, String>>()
        if (!hidden) {
            s.home?.let { items.add(Triple(it, "mine-home", "Home")) }
            s.work?.let { items.add(Triple(it, "mine-work", "Work")) }
            s.parking?.let { items.add(Triple(it, "mine-parking", "Parked car")) }
            for (p in s.saved.take(200)) if (items.none { it.first.id == p.id }) items.add(Triple(p, "mine-saved", p.name))
        }
        val selected = model.selected
        // The label too, so a renamed place shows its new name.
        val key = items.joinToString(",") { it.first.id + it.second + it.third } + "|" + (selected?.id ?: "")
        if (key == lastMineKey) return
        lastMineKey = key
        setShape(style, "vela-mine", items.filter { selected == null || !it.first.matchesNearby(selected) }.map { (p, icon, label) ->
            point(p.location, mapOf("id" to p.id, "icon" to icon, "label" to label))
        })
    }

    private fun renderCameras(style: Style) {
        val s = model.settings
        val line = model.nav?.route?.polyline ?: model.directions?.route?.polyline ?: emptyList()
        val waze = s.speedCameras && s.wazeOn
        // While driving, only what's on your way stands out: everything off the route fades back.
        val driving = model.nav?.route?.mode == TravelMode.DRIVE
        val key = "${s.speedCameras}${s.plateCameras}${s.surveillanceCameras}${surveillance.size}${cameras.size}${line.size}${line.firstOrNull()?.lat ?: 0}$waze${WazeCameras.shared.version}$driving"
        if (key == lastCameraKey) return
        lastCameraKey = key
        val mapped = cameras.values.filter { if (it.kind == RoadCamera.Kind.SPEED) s.speedCameras else s.plateCameras }
        // Waze's speed cameras join OpenStreetMap's; one both maps hold shows once.
        val shown = if (waze) WazeCameras.merge(mapped, WazeCameras.shared.known()) else mapped
        val cum = if (line.size >= 2) Geo.cumulative(line) else null
        val features = shown.map { cam ->
            val onRoute = cum != null && Geo.project(cam.location, line, cum).offset < 40
            point(cam.location, mapOf("icon" to if (cam.kind == RoadCamera.Kind.SPEED) "cam-speed" else "cam-plate",
                "big" to if (onRoute) 1 else 0, "op" to if (driving && !onRoute) OFF_ROUTE_OPACITY else 1.0,
                "surveillanceId" to if (cam.kind == RoadCamera.Kind.PLATE) cam.id.toString() else ""))
        }.toMutableList()
        if (s.surveillanceCameras) {
            for (camera in surveillance.values) {
                if (camera.isALPR && s.plateCameras && cameras[camera.id] != null) continue
                features.add(point(camera.location, mapOf("icon" to if (camera.isALPR) "cam-plate" else "cam-surveillance", "big" to 0,
                    "op" to if (driving) OFF_ROUTE_OPACITY else 1.0, "surveillanceId" to camera.id.toString())))
            }
        }
        setShape(style, "vela-cameras", features)
    }

    private fun renderSignals(style: Style) {
        val lights = model.nav?.routeSignals ?: emptyList()
        val key = "${lights.size}${lights.firstOrNull()?.lat ?: 0}"
        if (key == lastSignalsKey) return
        lastSignalsKey = key
        setShape(style, "vela-signals", lights.map { point(it) })
    }

    private fun renderWaze(style: Style) {
        val s = model.settings
        val history = WazeHistory.all
        val kinds = s.wazeKinds.map { it.key }.sorted().joinToString("")
        val live = model.nav?.liveAlerts ?: (if (model.directions != null) model.routeReports else emptyList())
        // While driving, reports off your route fade back so the ones you'll meet stand out.
        val route = if (model.nav?.route?.mode == TravelMode.DRIVE) model.nav?.route?.polyline ?: emptyList() else emptyList()
        val key = "${s.wazeOn}${s.wazeOnMap}${s.wazeHeatmap}${s.wazeMaxAge}$kinds$wazeAt${history.size}${live.joinToString("") { it.id }}${route.size}${route.firstOrNull()?.lat ?: 0}"
        if (key == lastWazeKey) return
        lastWazeKey = key
        val ids = wazeAlerts.map { it.id }.toSet()
        val all = wazeAlerts + live.filter { !ids.contains(it.id) }
        val shown = if (s.wazeOn && s.wazeOnMap) all.filter { s.shows(it) } else emptyList()
        val cum = if (route.size >= 2) Geo.cumulative(route) else null
        fun feature(a: WazeAlert): Feature {
            val age = (now() - a.reportedAtMillis) / 1000.0
            var op = max(0.5, 1 - age / 14_400)
            if (cum != null && Geo.project(a.location, route, cum).offset > 60) op = OFF_ROUTE_OPACITY
            return point(a.location, mapOf("id" to a.id, "icon" to "waze-${a.kind.key}", "op" to op))
        }
        // Police and crashes matter most, so they never get grouped into a bubble.
        val (alone, grouped) = shown.partition { it.kind in NEVER_GROUPED }
        setShape(style, "vela-waze", alone.map(::feature))
        setShape(style, "vela-waze-group", grouped.map(::feature))
        visible(style, "vela-waze-heat", false)
    }

    /** Highway Radar's police heat map for what's on screen, when it's switched on. */
    fun updateHeat() {
        val s = model.settings
        val st = style ?: return
        visible(st, "hr-heat", styleReady && s.wazeOn && s.wazeHeatmap && s.wazePolice)
    }

    /** Live Waze reports for the view and a margin, asked again after two minutes or when the map leaves the box. */
    fun loadWaze(m: MapHandle) {
        val s = model.settings
        if (s.wazeOn && (s.wazeHeatmap || s.policeRisk)) {
            // Only around where you are: Waze is never asked about saved Home and Work.
            val places = listOfNotNull(LocationService.last?.coord)
            WazeHistory.sweepIfDue(places, scope)
        }
        if (fakeWaze || !s.wazeOn || !(s.wazeOnMap || s.wazeHeatmap) || m.cameraPosition.zoom < 10 || wazeFetch != null || now() - wazeFailedAt <= 60_000) return
        val waitMs = (if (model.nav != null) 90_000 else 5_000) - (now() - wazeAskedAt)
        if (waitMs > 0) {
            if (!wazeRetryQueued) {
                wazeRetryQueued = true
                scope.launch { delay(waitMs + 100); wazeRetryQueued = false; loadWaze(m) }
            }
            return
        }
        val b = m.visibleBounds ?: return
        wazeBox?.let { r ->
            if (b.latitudeSouth >= r.s && b.longitudeWest >= r.w && b.latitudeNorth <= r.n && b.longitudeEast <= r.e && now() - wazeAt < 120_000) return
        }
        val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.3; val dLng = (b.longitudeEast - b.longitudeWest) * 0.3
        val box = Box(b.latitudeSouth - dLat, b.longitudeWest - dLng, b.latitudeNorth + dLat, b.longitudeEast + dLng)
        if (box.n - box.s >= 1.5 || box.e - box.w >= 2.5) return
        val mid = LatLng((b.latitudeNorth + b.latitudeSouth) / 2, (b.longitudeEast + b.longitudeWest) / 2)
        val hLat = min((box.n - box.s) / 2, 0.15); val hLng = min((box.e - box.w) / 2, 0.25)
        val liveBox = Box(mid.lat - hLat, mid.lng - hLng, mid.lat + hLat, mid.lng + hLng)
        val askLive = model.nav == null
        wazeAskedAt = now()
        wazeFetch = scope.launch {
            val closures = async { runCatching { Waze.alerts(box.s, box.w, box.n, box.e) }.getOrNull() }
            val live = if (askLive) runCatching { WazeLive.shared.alerts(listOf(liveBox), mid) }.getOrNull() else null
            val fromMap = closures.await()
            wazeFetch = null
            if (fakeWaze) return@launch
            if (fromMap == null && live == null) { klog("KiteTrace", "waze browse: both failed (Waze: ${Waze.lastStatus?.summary ?: "no answer"}; ${WazeWeb.note})"); wazeFailedAt = now(); return@launch }
            val merged = linkedMapOf<String, WazeAlert>()
            for (a in (fromMap ?: emptyList()) + (live ?: emptyList())) merged[a.id] = a
            val found = merged.values.toList()
            klog("KiteTrace", "waze browse: live map ${fromMap?.size ?: "failed"}, drivers' reports ${if (askLive) live?.size ?: "failed" else "not asked"}" +
                if (fromMap == null) " (Waze: ${Waze.lastStatus?.summary ?: "no answer"}; ${WazeWeb.note})" else "")
            wazeAlerts = found
            wazeBox = if (live != null) liveBox else box
            wazeAt = now()
            WazeHistory.record(found)
            model.wazeLiveCount = found.count { model.settings.shows(it) }
            model.updateAreaRisk(found)
            render()
        }
    }

    /** Transit lines for the view and a margin around it, when the layer is on. */
    fun loadRail(m: MapHandle) {
        if (!model.settings.transitLines || m.cameraPosition.zoom < 10 || railFetch != null || model.nav != null) return
        val b = m.visibleBounds ?: return
        val z = m.cameraPosition.zoom.toInt()
        railBox?.let { r -> if (b.latitudeSouth >= r.s && b.longitudeWest >= r.w && b.latitudeNorth <= r.n && b.longitudeEast <= r.e && abs(z - r.z) <= 2) return }
        val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.5; val dLng = (b.longitudeEast - b.longitudeWest) * 0.5
        val box = RailBox(b.latitudeSouth - dLat, b.longitudeWest - dLng, b.latitudeNorth + dLat, b.longitudeEast + dLng, z)
        railFetch = scope.launch {
            val lines = runCatching { Transitous.lines(box.s, box.w, box.n, box.e, min(z, 14)) }.getOrNull()
            klog("KiteTrace", "transit lines: ${lines?.size ?: "failed"} at zoom $z (${Transitous.linesNote})")
            railFetch = null
            if (lines != null) {
                railBox = box
                style?.let { setShape(it, "vela-rail", lines.map { l -> polyline(l.points, mapOf("color" to hex(l.color))) }) }
                loadRail(m)
            }
        }
    }

    private fun cells(m: MapHandle, done: Set<String>): List<Pair<Int, Int>>? {
        val b = m.visibleBounds ?: return null; val cell = 0.1
        val y0 = floor(b.latitudeSouth / cell).toInt(); val y1 = floor(b.latitudeNorth / cell).toInt()
        val x0 = floor(b.longitudeWest / cell).toInt(); val x1 = floor(b.longitudeEast / cell).toInt()
        if (y1 < y0 || x1 < x0 || (y1 - y0 + 1) * (x1 - x0 + 1) > 12) return null
        val missing = mutableListOf<Pair<Int, Int>>()
        for (y in y0..y1) for (x in x0..x1) if (!done.contains("$y,$x")) missing.add(y to x)
        return missing.ifEmpty { null }
    }

    /** Separate OSM surveillance layer, opt-in; bounded to the same cells as camera lookups. */
    fun loadSurveillance(m: MapHandle) {
        if (!model.settings.surveillanceCameras || m.cameraPosition.zoom < 12 || surveillanceFetch != null) return
        val missing = cells(m, surveillanceCells) ?: return
        val ys = missing.map { it.first }; val xs = missing.map { it.second }; val cell = 0.1
        surveillanceFetch = scope.launch {
            val found = runCatching { SurveillanceCameras.inBox(ys.min() * cell, xs.min() * cell, (ys.max() + 1) * cell, (xs.max() + 1) * cell) }.getOrNull()
            surveillanceFetch = null
            if (found == null) { delay(30_000); loadSurveillance(m); return@launch }
            missing.forEach { surveillanceCells.add("${it.first},${it.second}") }
            found.forEach { surveillance[it.id] = it }
            render(); loadSurveillance(m)
        }
    }

    /** Ask Overpass for the cameras in view, a few 0.1° cells at a time, each cell once. */
    fun loadCameras(m: MapHandle) {
        val s = model.settings
        if (!(s.speedCameras || s.plateCameras) || m.cameraPosition.zoom < 12 || cameraFetch != null) return
        val missing = cells(m, cameraCells) ?: return
        val ys = missing.map { it.first }; val xs = missing.map { it.second }; val cell = 0.1
        cameraFetch = scope.launch {
            val found = runCatching { Overpass.cameras(ys.min() * cell, xs.min() * cell, (ys.max() + 1) * cell, (xs.max() + 1) * cell, true, true) }.getOrNull()
            cameraFetch = null
            if (found == null) { delay(30_000); loadCameras(m); return@launch }
            missing.forEach { cameraCells.add("${it.first},${it.second}") }
            found.forEach { cameras[it.id] = it }
            render(); loadCameras(m)
        }
    }

    /** Waze's speed cameras for what's on screen, once zoomed in to a town; each piece of its map once a week. */
    fun loadWazeCameras(m: MapHandle) {
        val s = model.settings
        WazeCameras.enabled = s.wazeOn
        if (!s.speedCameras || !s.wazeOn || m.cameraPosition.zoom < 13 || wazeCameraFetch != null) return
        val b = m.visibleBounds ?: return
        wazeCameraFetch = scope.launch {
            val fresh = runCatching { WazeCameras.load(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast) }.getOrDefault(false)
            wazeCameraFetch = null
            if (fresh) render()
        }
    }

    private fun renderResults(style: Style) {
        val showList = model.directions == null && model.nav == null
        val list = if (showList) model.visibleResults else emptyList()
        val target = model.nav?.destination ?: model.directions?.destination ?: model.selected
        val stops = model.nav?.stops ?: model.directions?.stops ?: emptyList()
        val key = "${list.joinToString(",") { it.id }}|${target?.id ?: ""}|${model.directions?.reversed ?: false}|${model.nav == null}|${stops.joinToString(",") { it.id }}"
        if (key == lastResultsKey) return
        lastResultsKey = key
        setShape(style, "vela-results", list.mapIndexedNotNull { i, p ->
            if (p.id == target?.id) return@mapIndexedNotNull null
            val rating = p.rating?.let { fmt("%.1f", it) }
            point(p.location, mapOf("id" to p.id, "icon" to MapIcons.iconName(p), "label" to listOfNotNull(p.name, rating).joinToString("\n"), "rank" to i))
        })
        val sel = mutableListOf<Feature>()
        if (target != null) {
            sel.add(point(target.location, mapOf("id" to target.id, "icon" to "pin-selected", "anchor" to "bottom", "label" to target.name)))
            stops.forEachIndexed { i, stop -> sel.add(point(stop.location, mapOf("id" to "stop$i", "icon" to "pin-stop", "anchor" to "center", "label" to stop.name))) }
            val d = model.directions
            val me = model.userLatLng
            if (d != null && !d.reversed && model.nav == null && me != null) sel.add(point(me, mapOf("id" to "origin", "icon" to "pin-origin", "anchor" to "center", "label" to "")))
        }
        setShape(style, "vela-selected", sel)
    }

    private fun renderRoutes(style: Style) {
        var alts = emptyList<Route>()
        var main: Route? = null
        var from = 0.0
        val n = model.nav
        val d = model.directions
        if (n != null) { main = n.route; from = n.progress?.alongMeters ?: 0.0 }
        else if (d != null) { main = d.route; alts = d.current.filter { it.id != main?.id } }
        val trip = if (n == null) d?.pickedTrip else null
        val key = (if (n == null) "" else "nav") + (trip?.id ?: "") + (main?.id ?: "") + alts.joinToString("") { it.id } + "${(from / 5).toInt()}" +
            (n?.nextManeuver?.alongMeters ?: "") + (main?.trafficSpans?.size ?: 0)
        if (key == lastRouteKey) return
        lastRouteKey = key
        setShape(style, "vela-route-alt", alts.map { polyline(it.polyline) })
        setShape(style, "vela-transit", trip?.legs?.filter { it.path.size >= 2 }?.map { leg ->
            polyline(leg.path, mapOf("walk" to if (leg.isWalk) 1 else 0, "color" to hex(leg.color ?: 0x1F6FEB)))
        } ?: emptyList())
        val r = main
        if (r == null) {
            for (id in listOf("vela-route", "vela-route-traffic", "vela-route-bubbles", "vela-route-done", "vela-turn-arrow", "vela-turn-head", "vela-next-road")) setShape(style, id, emptyList())
            lastHeadKey = ""; lastNextRoadKey = ""
            return
        }
        val cum = Geo.cumulative(r.polyline)
        val total = cum.last()
        val ahead = if (from > 1) Geo.slice(r.polyline, from, total, cum) else r.polyline
        setShape(style, "vela-route", if (ahead.size >= 2) listOf(polyline(ahead)) else emptyList())
        val driven = if (n != null && from > 1) Geo.slice(r.polyline, 0.0, from, cum) else emptyList()
        setShape(style, "vela-route-done", if (driven.size >= 2) listOf(polyline(driven)) else emptyList())
        setShape(style, "vela-route-traffic", r.trafficSpans.mapNotNull { s ->
            val a = max(s.startMeters, from); val b = min(s.startMeters + s.lengthMeters, total)
            if (b <= a) return@mapNotNull null
            val piece = Geo.slice(r.polyline, a, b, cum)
            if (piece.size >= 2) polyline(piece, mapOf("level" to min(s.level, 3))) else null
        })
        // Turn arrow: 50 m of road before the next turn and 40 m into the new road.
        val m = n?.nextManeuver
        val p = n?.progress
        var arrow = emptyList<Feature>()
        var head: Pair<LatLng, Double>? = null
        var nextRoad: Pair<LatLng, String>? = null
        if (n != null && m != null && p != null && m.type != ManeuverType.ARRIVE && m.type != ManeuverType.DEPART &&
            m.type != ManeuverType.STRAIGHT && m.alongMeters - p.alongMeters < 600) {
            var end = m.alongMeters + 40
            if (m.type == ManeuverType.ROUNDABOUT) {
                n.route.maneuvers.firstOrNull { it.leavesRoundabout && it.alongMeters > m.alongMeters }?.let {
                    if (it.alongMeters - m.alongMeters < 250) end = it.alongMeters + 40
                }
            }
            val a = max(m.alongMeters - 50, from); val b = min(end, total)
            val shaft = Geo.slice(r.polyline, a, b, cum)
            if (shaft.size >= 2) {
                val tip = shaft.last(); val prev = Geo.point(r.polyline, max(a, b - 4), cum)
                arrow = listOf(polyline(shaft, mapOf("part" to "shaft")))
                head = tip to Geo.bearing(prev, tip)
            }
            // The new road's name, a little way into it, clear of the arrow head.
            nextRoadName(m)?.let { nextRoad = Geo.point(r.polyline, min(end + 55, total), cum) to it }
        }
        setShape(style, "vela-turn-arrow", arrow)
        val headKey = head?.let { (t, b) -> fmt("%.6f,%.6f,%.0f", t.lat, t.lng, b) } ?: ""
        if (headKey != lastHeadKey) {
            lastHeadKey = headKey
            setShape(style, "vela-turn-head", head?.let { (t, b) -> listOf(point(t, mapOf("bearing" to b))) } ?: emptyList())
        }
        val nextRoadKey = nextRoad?.let { (at, name) -> fmt("%.6f,%.6f,%s", at.lat, at.lng, name) } ?: ""
        if (nextRoadKey != lastNextRoadKey) {
            lastNextRoadKey = nextRoadKey
            setShape(style, "vela-next-road", nextRoad?.let { (at, name) -> listOf(point(at, mapOf("label" to name))) } ?: emptyList())
        }
        if (n != null || d == null) { setShape(style, "vela-route-bubbles", emptyList()); return }
        val bubbles = d.current.mapIndexed { i, route ->
            val others = d.current.filterIndexed { j, _ -> j != i }.map { it.polyline }
            val cc = Geo.cumulative(route.polyline)
            val len = cc.last()
            var spot = Geo.point(route.polyline, len * 0.5, cc)
            for (f in listOf(0.5, 0.6, 0.7, 0.8, 0.9)) {
                val pt = Geo.point(route.polyline, len * f, cc)
                if (others.none { o -> o.any { it.distanceTo(pt) < 120 } }) { spot = pt; break }
            }
            point(spot, mapOf("label" to Fmt.duration(route.durationSeconds), "sel" to if (route.id == r.id) 1 else 0, "idx" to i))
        }
        setShape(style, "vela-route-bubbles", bubbles)
    }

    private fun renderPuck(style: Style) {
        if (model.nav != null) { lastPuckKey = "nav"; return }
        if (lastPuckKey == "") return
        setShape(style, NavGlide.PUCK, emptyList())
        lastPuckKey = ""
    }

    // Gestures

    /** A box 22 dp round a tapped spot, in dp on the screen. */
    private fun box(m: MapHandle, p: LatLng): FloatArray? {
        val pt = m.toScreenLocation(p) ?: return null
        val r = 22f
        return floatArrayOf(pt.x - r, pt.y - r, pt.x + r, pt.y + r)
    }

    private suspend fun MapHandle.at(b: FloatArray, vararg layers: String) = queryRenderedFeatures(b[0], b[1], b[2], b[3], *layers)

    private var lastLiveKey = ""
    /** The box the drawn live camera dots cover, so panning inside it redraws nothing. */
    private var liveCamBox: Box? = null

    /** Live camera dots for what's on screen (plus a margin), never while driving. */
    private fun renderLiveCams(m: MapHandle, style: Style) {
        val s = model.settings
        // Road cameras only from a city's zoom (there are thousands); webcams from a region's.
        val zoom = m.cameraPosition.zoom
        val roadNow = s.showsRoadCams && zoom >= LiveCams.ROAD_MIN_ZOOM
        val webNow = s.showsWebcams && zoom >= LiveCams.WEBCAM_MIN_ZOOM
        val on = model.nav == null && (roadNow || webNow)
        val b = m.visibleBounds ?: return
        val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.5
        val dLng = (b.longitudeEast - b.longitudeWest) * 0.5
        val inside = liveCamBox?.let { b.latitudeSouth >= it.s && b.latitudeNorth <= it.n && b.longitudeWest >= it.w && b.longitudeEast <= it.e } ?: false
        // Switched on from Layers or Settings: ask for the cameras now, not only after the next pan.
        if (on) loadLiveCams(m)
        val key = "$on$roadNow$webNow${LiveCamStore.version}"
        if (key == lastLiveKey && (!on || inside)) return
        lastLiveKey = key
        liveCamBox = if (on) Box(b.latitudeSouth - dLat, b.longitudeWest - dLng, b.latitudeNorth + dLat, b.longitudeEast + dLng) else null
        val lb = liveCamBox
        val features = if (lb == null) emptyList() else
            LiveCamStore.cams(lb.s, lb.w, lb.n, lb.e, roadNow, webNow).map { c ->
                point(c.location, mapOf("id" to c.id, "icon" to if (c.source.isRoad) "livecam-road" else "livecam-web"))
            }
        setShape(style, "vela-livecams", features)
    }

    /** The road camera list (once a day) and Windy's webcams around what's on screen. */
    fun loadLiveCams(m: MapHandle) {
        val s = model.settings
        if (model.nav != null || !s.liveCams) return
        if (s.showsRoadCams || s.liveCamsOnRoute) LiveCamStore.ensureLoaded()
        if (!s.showsWebcams || m.cameraPosition.zoom < LiveCams.WEBCAM_MIN_ZOOM) return
        val b = m.visibleBounds ?: return
        val c = LatLng((b.latitudeSouth + b.latitudeNorth) / 2, (b.longitudeWest + b.longitudeEast) / 2)
        val span = LatLng(b.latitudeSouth, b.longitudeWest).distanceTo(LatLng(b.latitudeNorth, b.longitudeEast)) / 1000
        LiveCamStore.loadWindy(c, span)
    }

    private fun str(f: FoundFeature, k: String): String? = f.string(k)

    /** A tap on the map: the thing tapped opens (looked up once the map says what's there). */
    fun tapped(at: LatLng) {
        val m = map ?: return
        if (model.nav != null || now() - pinDroppedAt < 1000) return
        scope.launch { tapped(m, at) }
    }

    private suspend fun tapped(m: MapHandle, at: LatLng): Boolean {
        val box = box(m, at) ?: return false
        if (model.directions != null) {
            val hits = m.at(box, "vela-route-bubble-0", "vela-route-alt", "vela-route-alt-edge")
            hits.firstOrNull { it.has("idx") }?.let { f -> f.number("idx")?.let { model.selectRoute(it.toInt()); return true } }
            val d = model.directions ?: return true
            if (hits.isNotEmpty()) {
                val tappedAt = at
                val i = d.current.indexOfFirst { r -> r.id != d.route?.id && r.polyline.any { it.distanceTo(tappedAt) < 300 } }
                if (i >= 0) { model.selectRoute(i); return true }
            }
            // A tap on the picked route itself: offer to avoid that road (GitHub idea, 0.7.17).
            if (m.at(box, "vela-route-line", "vela-route-edge").isNotEmpty()) model.askAvoid(at)
            return true
        }
        // Police and crashes sit on top and never join a bubble: a tap that touches one opens it,
        // even with a bubble right next to it.
        m.at(box, "vela-waze").firstOrNull()?.let { f ->
            val id = str(f, "id")
            wazeAlerts.firstOrNull { it.id == id }?.let { haptic(); model.wazeInfo = it; return true }
        }
        // A numbered bubble: zoom in until it splits into its icons.
        m.at(box, "vela-waze-group-bubble", "vela-cameras-bubble", "vela-livecams-bubble").firstOrNull()?.let { f ->
            f.point?.let { p ->
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(p, min(m.cameraPosition.zoom + 2, 15.0)))
                return true
            }
        }
        m.at(box, "vela-waze-group").firstOrNull()?.let { f ->
            val id = str(f, "id")
            wazeAlerts.firstOrNull { it.id == id }?.let { haptic(); model.wazeInfo = it; return true }
        }
        m.at(box, "vela-livecams").firstOrNull()?.let { f ->
            str(f, "id")?.let { LiveCamStore.cam(it) }?.let {
                haptic()
                model.liveCamRoute = emptyList()
                model.liveCam = it
                return true
            }
        }
        m.at(box, "vela-cameras").firstOrNull()?.let { f ->
            str(f, "surveillanceId")?.toLongOrNull()?.let { id -> surveillance[id]?.let { model.surveillanceInfo = it; return true } }
        }
        m.at(box, "vela-mine").firstOrNull()?.let { f ->
            str(f, "id")?.let { model.myPlace(it) }?.let { model.select(it); return true }
        }
        m.at(box, "vela-results").firstOrNull()?.let { f ->
            val id = str(f, "id")
            model.results.firstOrNull { it.id == id }?.let { model.select(it); return true }
        }
        m.at(box, "poi_r1", "poi_r7", "poi_r20", "poi_transit", "airport").firstOrNull()?.let { poi ->
            val name = str(poi, "name:en") ?: str(poi, "name")
            if (name != null) {
                model.resolveTapped(name, poi.point ?: at)
                return true
            }
        }
        if (model.selected != null) model.closePlace()
        else if (model.results.isNotEmpty()) model.detent = SheetDetent.PEEK
        return true
    }

    /** A long press drops a pin. */
    fun pressed(at: LatLng) {
        pinDroppedAt = now()
        if (model.nav != null || model.directions != null) return
        haptic(long = true)
        model.dropPin(at)
    }

    private fun noteHeading(m: MapHandle) {
        val h = m.cameraPosition.bearing
        val north = (h % 360) == 0.0
        if (abs(h - model.mapHeading) > 2 || north != (model.mapHeading == 0.0)) model.mapHeading = if (north) 0.0 else h
    }
}
