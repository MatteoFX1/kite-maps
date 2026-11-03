package app.kite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.location.Location
import android.util.Log
import android.view.HapticFeedbackConstants
import app.kite.core.*
import kotlinx.coroutines.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.*

/**
 * The full-screen map: OpenFreeMap Liberty painted in Kite's colors, plus Kite's own layers
 * (result pins, routes with traffic, the navigation puck, live traffic, satellite). The same
 * layers, in the same order, as the iPhone app's map.
 */
class KiteMap(private val model: AppModel, private val context: Context) {
    companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private fun c(hex: Long, a: Float = 1f) = Color.argb((a * 255).toInt(), ((hex shr 16) and 0xFF).toInt(), ((hex shr 8) and 0xFF).toInt(), (hex and 0xFF).toInt())
        private fun hex(l: Long) = String.format("#%06X", l and 0xFFFFFF)
    }

    var dark = true
        private set
    private var map: MapLibreMap? = null
    private var view: MapView? = null
    private val scope get() = model.scope
    private var styleReady = false
    private var movedToUser = false
    private var pinDroppedAt = 0L
    private val originalLabels = mutableMapOf<String, Triple<PropertyValue<*>, PropertyValue<*>, PropertyValue<*>>>()
    private var lastResultsKey = ""
    private var lastRouteKey = ""
    private var lastMineKey = ""
    private var lastPuckKey = ""
    private var lastHeadKey = ""
    private var styledDriving: Boolean? = null
    private var lastCameraKey = ""
    private var lastSignalsKey = ""
    private val cameras = mutableMapOf<Long, RoadCamera>()
    private val cameraCells = mutableSetOf<String>()
    private var cameraFetch: Job? = null
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
    val heatTiles = HighwayHeat(model.scope)
    private var wazeRetryQueued = false
    private var lastWazeKey = ""
    private data class RailBox(val s: Double, val w: Double, val n: Double, val e: Double, val z: Int)
    private var railBox: RailBox? = null
    private var locationOn = false

    private fun now() = System.currentTimeMillis()

    fun attach(v: MapView) {
        view = v
        v.getMapAsync { m ->
            map = m
            model.map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.setAttributionMargins(24, 0, 0, 24)
            m.uiSettings.isAttributionEnabled = true
            m.cameraPosition = CameraPosition.Builder().target(org.maplibre.android.geometry.LatLng(51.5074, -0.1278)).zoom(11.0).build()
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { style -> styleLoaded(m, style) }
            m.addOnMapClickListener { tapped(m, it) }
            m.addOnMapLongClickListener { pressed(m, it); true }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) model.nav?.let { if (it.following) it.following = false }
            }
            m.addOnCameraIdleListener {
                loadCameras(m); loadSurveillance(m); loadWaze(m); updateHeat(m); loadRail(m); noteHeading(m)
            }
            m.addOnCameraMoveListener { noteHeading(m) }
            WazeHistory.onChange = { render() }
            LocationService.last?.let { moveToUserOnce(it) }
        }
    }

    // Style

    private fun styleLoaded(m: MapLibreMap, style: Style) {
        originalLabels.clear()
        for (l in style.layers) if (l is SymbolLayer) originalLabels[l.id] = Triple(l.textColor, l.textHaloColor, l.textHaloWidth)
        MapStyler.reset()
        MapIcons.register(style, dark)
        addVelaLayers(style)
        styleReady = true
        heatTiles.styleReloaded()
        styledDriving = null
        repaint()
        lastResultsKey = ""; lastRouteKey = ""; lastMineKey = ""; lastPuckKey = ""; lastHeadKey = ""; lastCameraKey = ""; lastSignalsKey = ""; lastWazeKey = ""
        enableLocation(style)
        render()
        loadCameras(m); loadWaze(m); updateHeat(m)
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation(style: Style) {
        val m = map ?: return
        if (!LocationService.authorized) return
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) {
            val opts = LocationComponentOptions.builder(context)
                .foregroundTintColor(c(0x14B8A6)).backgroundTintColor(Color.WHITE)
                .accuracyColor(c(0x14B8A6)).accuracyAlpha(0.15f)
                .bearingTintColor(c(0x14B8A6)).pulseEnabled(false).build()
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(opts).useDefaultLocationEngine(false).build())
        }
        lc.isLocationComponentEnabled = model.nav == null
        lc.cameraMode = CameraMode.NONE
        lc.renderMode = RenderMode.NORMAL
        locationOn = true
        LocationService.last?.let { pushFix(it) }
    }

    /** Hands a GPS fix to the map's own blue dot. */
    fun pushFix(f: Fix) {
        val m = map ?: return
        val style = m.style ?: return
        if (!locationOn) { if (LocationService.authorized) enableLocation(style); if (!locationOn) return }
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) return
        lc.forceLocationUpdate(Location("kite").apply {
            latitude = f.lat; longitude = f.lng; accuracy = f.accuracy.toFloat(); time = f.timeMillis
            if (f.courseKnown) bearing = f.course.toFloat()
            if (f.speedKnown) speed = f.speed.toFloat()
        })
        moveToUserOnce(f)
    }

    /** Screenshot runs: the demo has put the map somewhere, so the first fix mustn't move it back. */
    fun keepCamera() { movedToUser = true }

    private fun moveToUserOnce(f: Fix) {
        val m = map ?: return
        if (movedToUser) return
        movedToUser = true
        if (model.results.isNotEmpty() || model.selected != null || model.directions != null) return
        m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(f.coord.ml(), 15.0))
    }

    fun applyDark(d: Boolean) {
        if (dark == d) return
        dark = d
        repaint()
    }

    private fun repaint() {
        val style = map?.style ?: return
        if (!styleReady) return
        if (!dark) {
            for (l in style.layers) if (l is SymbolLayer) originalLabels[l.id]?.let { l.setProperties(it.first, it.second, it.third) }
        }
        MapStyler.apply(style, dark)
        MapIcons.swapPuck(style, dark)
        (style.getLayer("vela-rail-case") as? LineLayer)?.setProperties(lineColor(if (dark) c(0x000000, 0.9f) else c(0xFFFFFF, 0.9f)))
        val text = if (dark) c(0xE8EAED) else c(0x202124)
        val halo = if (dark) c(0x0B1526) else Color.WHITE
        for (id in listOf("vela-mine", "vela-results", "vela-selected")) {
            (style.getLayer(id) as? SymbolLayer)?.setProperties(textColor(text), textHaloColor(halo), textHaloWidth(1.5f))
        }
        lastResultsKey = ""
        lastRouteKey = ""
    }

    private fun firstSymbolLayer(style: Style): String? = style.layers.firstOrNull { it is SymbolLayer }?.id

    private fun line(id: String, src: String, color: Int, width: Float) = LineLayer(id, src).withProperties(
        lineColor(color), lineWidth(width), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND))

    private fun addVelaLayers(style: Style) {
        fun src(id: String, metrics: Boolean = false) {
            if (style.getSource(id) == null) style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList()),
                GeoJsonOptions().withLineMetrics(metrics)))
        }
        // Satellite (Esri World Imagery) under the roads, off unless picked in Layers.
        style.addSource(RasterSource("vela-sat", TileSet("tiles", "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}")
            .apply { maxZoom = 19f; attribution = "Imagery © Esri" }, 256))
        val satLayer = RasterLayer("vela-sat", "vela-sat").withProperties(visibility(Property.NONE))
        style.layers.firstOrNull { it.id.startsWith("tunnel_") }?.let { style.addLayerBelow(satLayer, it.id) } ?: style.addLayer(satLayer)

        style.addSource(RasterSource("vela-terrain", TileSet("tiles", "https://services.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer/tile/{z}/{y}/{x}")
            .apply { maxZoom = 16f; attribution = "Hillshade © Esri" }, 256))
        val hill = RasterLayer("vela-terrain", "vela-terrain").withProperties(rasterOpacity(if (dark) 0.22f else 0.32f), visibility(Property.NONE))
        style.layers.firstOrNull { it.id.startsWith("tunnel_") || it.id.startsWith("road_") }?.let { style.addLayerBelow(hill, it.id) } ?: style.addLayer(hill)

        // Google's live traffic tiles, the same keyless overlay Vela draws.
        val trafficURL = "https://www.google.com/maps/vt/pb=!1m4!1m3!1i{z}!2i{x}!3i{y}!2m9!1e2!2straffic!3i999999" +
            "!4m2!1sincidents!2s1!4m2!1sincidents_text!2s1!3m8!2sen!3sus!5e1105!12m4!1e68!2m2!1sset!2sRoadmap!4e0!5m1!1e0"
        style.addSource(RasterSource("vela-traffic", TileSet("tiles", trafficURL).apply { maxZoom = 16f }, 256))
        val trafficLayer = RasterLayer("vela-traffic", "vela-traffic").withProperties(rasterOpacity(0.6f), rasterFadeDuration(900f), visibility(Property.NONE))
        (style.getLayer("building-3d") ?: style.getLayer("building"))?.let { style.addLayerAbove(trafficLayer, it.id) } ?: style.addLayer(trafficLayer)

        val labels = firstSymbolLayer(style)
        fun insert(l: Layer) { if (labels != null) style.addLayerBelow(l, labels) else style.addLayer(l) }
        src("vela-route-alt"); src("vela-route", metrics = true); src("vela-route-traffic"); src("vela-route-done")

        val done = line("vela-route-done", "vela-route-done", c(0x64748B), 10f).withProperties(lineOpacity(0.5f))
        val altEdge = line("vela-route-alt-edge", "vela-route-alt", c(0x115E59), 11f).withProperties(lineOpacity(0.6f))
        val altFill = line("vela-route-alt", "vela-route-alt", c(0x5EEAD4), 7f).withProperties(lineOpacity(0.6f))
        val edge = line("vela-route-edge", "vela-route", c(0x0F766E), 15f)
        val fill = line("vela-route-line", "vela-route", c(0x2DD4BF), 10f).withProperties(
            lineGradient(interpolate(linear(), lineProgress(), stop(0, color(c(0x2DD4BF))), stop(1, color(c(0x22D3EE))))))
        val chevrons = SymbolLayer("vela-route-chevrons", "vela-route").withProperties(
            symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), symbolSpacing(70f), iconImage("route-chevron"),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconAllowOverlap(true), iconIgnorePlacement(true),
            iconKeepUpright(false), iconOpacity(0.55f)).apply { minZoom = 13f }
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

        src("vela-cameras")
        val big = eq(get("big"), literal(1))
        style.addLayer(SymbolLayer("vela-cameras", "vela-cameras").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true),
            iconSize(interpolate(linear(), zoom(),
                stop(11, switchCase(big, literal(1.0), literal(0.7))),
                stop(14, switchCase(big, literal(1.33), literal(0.92))),
                stop(17, switchCase(big, literal(1.65), literal(1.15)))))).apply { minZoom = 11f })

        src("vela-waze-heat")
        val heat = HeatmapLayer("vela-waze-heat", "vela-waze-heat").withProperties(visibility(Property.NONE))
        style.getLayer("vela-route-alt-edge")?.let { style.addLayerBelow(heat, "vela-route-alt-edge") } ?: insert(heat)
        src("vela-waze")
        style.addLayer(SymbolLayer("vela-waze", "vela-waze").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true),
            iconSize(interpolate(linear(), zoom(), stop(11, 0.62f), stop(15, 0.95f))),
            iconOpacity(get("op"))).apply { minZoom = 11f })

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
        val arrowShadow = line("vela-turn-arrow-shadow", "vela-turn-arrow", Color.BLACK, 17f).withProperties(
            lineOpacity(0.35f), lineBlur(4f), lineTranslate(arrayOf(0f, 2f)))
        arrowShadow.setFilter(shaft)
        val arrowEdge = line("vela-turn-arrow-edge", "vela-turn-arrow", c(0x0F766E), 15f); arrowEdge.setFilter(shaft)
        val arrowLine = line("vela-turn-arrow", "vela-turn-arrow", Color.WHITE, 9f); arrowLine.setFilter(shaft)
        // The head has a source of its own, updated only when the turn changes: the map fades a symbol
        // in afresh each time its source changes, which would keep the head see-through.
        src("vela-turn-head")
        val head = SymbolLayer("vela-turn-arrow-head", "vela-turn-head").withProperties(
            iconImage("turn-head"), iconRotate(get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconAllowOverlap(true), iconIgnorePlacement(true))
        listOf(arrowShadow, arrowEdge, arrowLine, head).forEach { style.addLayer(it) }

        src(NavGlide.PUCK)
        style.addLayer(SymbolLayer(NavGlide.PUCK, NavGlide.PUCK).withProperties(
            iconImage("puck"), iconRotate(get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP), iconAllowOverlap(true), iconIgnorePlacement(false)))
    }

    // Render

    private fun setShape(style: Style, id: String, features: List<Feature>) {
        (style.getSource(id) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun point(p: LatLng, props: Map<String, Any?> = emptyMap()) = Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).also { f ->
        props.forEach { (k, v) -> when (v) {
            is String -> f.addStringProperty(k, v)
            is Number -> f.addNumberProperty(k, v)
            is Boolean -> f.addBooleanProperty(k, v)
        } }
    }

    fun polyline(pts: List<LatLng>, props: Map<String, Any?> = emptyMap()) =
        Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lng, it.lat) })).also { f ->
            props.forEach { (k, v) -> when (v) { is String -> f.addStringProperty(k, v); is Number -> f.addNumberProperty(k, v) } }
        }

    private fun visible(style: Style, id: String, on: Boolean) {
        style.getLayer(id)?.setProperties(visibility(if (on) Property.VISIBLE else Property.NONE))
    }

    fun render() {
        val m = map ?: return
        val style = m.style ?: return
        if (!styleReady) return
        val s = model.settings
        val navigating = model.nav != null
        if (m.locationComponent.isLocationComponentActivated) {
            if (m.locationComponent.isLocationComponentEnabled == navigating) m.locationComponent.isLocationComponentEnabled = !navigating
        } else if (LocationService.authorized) enableLocation(style)
        // Not before the welcome screen's choice: "Open data only" means nothing ever went to Google.
        visible(style, "vela-traffic", s.traffic && !navigating && !s.noGoogle && s.welcomed)
        // The Surveillance layer just switched on: fetch it now, not at the next pan (only on the
        // switch, so a failed fetch keeps its 30-second pause).
        if (s.surveillanceCameras && !surveillanceWasOn) loadSurveillance(m)
        surveillanceWasOn = s.surveillanceCameras
        visible(style, "vela-sat", s.satellite)
        visible(style, "vela-terrain", s.terrain && !s.satellite)
        for (id in listOf("vela-rail", "vela-rail-case")) visible(style, id, s.transitLines && !navigating)
        if (styledDriving != navigating) {
            MapStyler.drive(style, navigating)
            styledDriving = navigating
            (style.getLayer("vela-route-line") as? LineLayer)?.setProperties(lineWidth(if (navigating) 16f else 10f))
            (style.getLayer("vela-route-edge") as? LineLayer)?.setProperties(lineWidth(if (navigating) 22f else 15f))
        }
        for (id in listOf("building", "building-3d")) visible(style, id, !s.satellite && !navigating)
        renderResults(style)
        renderRoutes(style)
        renderPuck(style)
        renderCameras(style)
        renderSignals(style)
        renderWaze(style)
        updateHeat(m)
        renderMine(style)
        if (s.transitLines) loadRail(m)
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
        val key = "${s.speedCameras}${s.plateCameras}${s.surveillanceCameras}${surveillance.size}${cameras.size}${line.size}${line.firstOrNull()?.lat ?: 0}"
        if (key == lastCameraKey) return
        lastCameraKey = key
        val shown = cameras.values.filter { if (it.kind == RoadCamera.Kind.SPEED) s.speedCameras else s.plateCameras }
        val cum = if (line.size >= 2) Geo.cumulative(line) else null
        val features = shown.map { cam ->
            val onRoute = cum != null && Geo.project(cam.location, line, cum).offset < 40
            point(cam.location, mapOf("icon" to if (cam.kind == RoadCamera.Kind.SPEED) "cam-speed" else "cam-plate",
                "big" to if (onRoute) 1 else 0, "surveillanceId" to if (cam.kind == RoadCamera.Kind.PLATE) cam.id.toString() else ""))
        }.toMutableList()
        if (s.surveillanceCameras) {
            for (camera in surveillance.values) {
                if (camera.isALPR && s.plateCameras && cameras[camera.id] != null) continue
                features.add(point(camera.location, mapOf("icon" to if (camera.isALPR) "cam-plate" else "cam-surveillance", "big" to 0, "surveillanceId" to camera.id.toString())))
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
        val live = model.nav?.liveAlerts ?: emptyList()
        val key = "${s.wazeOn}${s.wazeOnMap}${s.wazeHeatmap}${s.wazeMaxAge}$kinds$wazeAt${history.size}${live.joinToString("") { it.id }}"
        if (key == lastWazeKey) return
        lastWazeKey = key
        val ids = wazeAlerts.map { it.id }.toSet()
        val all = wazeAlerts + live.filter { !ids.contains(it.id) }
        val shown = if (s.wazeOn && s.wazeOnMap) all.filter { s.shows(it) } else emptyList()
        setShape(style, "vela-waze", shown.map { a ->
            val age = (now() - a.reportedAtMillis) / 1000.0
            point(a.location, mapOf("id" to a.id, "icon" to "waze-${a.kind.key}", "op" to max(0.5, 1 - age / 14_400)))
        })
        visible(style, "vela-waze-heat", false)
    }

    /** Highway Radar's police heat map for what's on screen, when it's switched on. */
    fun updateHeat(m: MapLibreMap) {
        val s = model.settings
        heatTiles.update(m, styleReady && s.wazeOn && s.wazeHeatmap && s.wazePolice, "vela-waze-heat")
    }

    /** Live Waze reports for the view and a margin, asked again after two minutes or when the map leaves the box. */
    fun loadWaze(m: MapLibreMap) {
        val s = model.settings
        if (s.wazeOn && (s.wazeHeatmap || s.policeRisk)) {
            val places = listOfNotNull(LocationService.last?.coord, s.home?.location, s.work?.location)
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
        val b = m.projection.visibleRegion.latLngBounds
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
            if (fromMap == null && live == null) { Log.i("KiteTrace", "waze browse: both failed (Waze: ${Waze.lastStatus?.summary ?: "no answer"}; ${WazeWeb.note})"); wazeFailedAt = now(); return@launch }
            val merged = linkedMapOf<String, WazeAlert>()
            for (a in (fromMap ?: emptyList()) + (live ?: emptyList())) merged[a.id] = a
            val found = merged.values.toList()
            Log.i("KiteTrace", "waze browse: live map ${fromMap?.size ?: "failed"}, drivers' reports ${if (askLive) live?.size ?: "failed" else "not asked"}" +
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
    fun loadRail(m: MapLibreMap) {
        if (!model.settings.transitLines || m.cameraPosition.zoom < 10 || railFetch != null || model.nav != null) return
        val b = m.projection.visibleRegion.latLngBounds
        val z = m.cameraPosition.zoom.toInt()
        railBox?.let { r -> if (b.latitudeSouth >= r.s && b.longitudeWest >= r.w && b.latitudeNorth <= r.n && b.longitudeEast <= r.e && abs(z - r.z) <= 2) return }
        val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.5; val dLng = (b.longitudeEast - b.longitudeWest) * 0.5
        val box = RailBox(b.latitudeSouth - dLat, b.longitudeWest - dLng, b.latitudeNorth + dLat, b.longitudeEast + dLng, z)
        railFetch = scope.launch {
            val lines = runCatching { Transitous.lines(box.s, box.w, box.n, box.e, min(z, 14)) }.getOrNull()
            Log.i("KiteTrace", "transit lines: ${lines?.size ?: "failed"} at zoom $z (${Transitous.linesNote})")
            railFetch = null
            if (lines != null) {
                railBox = box
                m.style?.let { setShape(it, "vela-rail", lines.map { l -> polyline(l.points, mapOf("color" to hex(l.color))) }) }
                loadRail(m)
            }
        }
    }

    private fun cells(m: MapLibreMap, done: Set<String>): List<Pair<Int, Int>>? {
        val b = m.projection.visibleRegion.latLngBounds; val cell = 0.1
        val y0 = floor(b.latitudeSouth / cell).toInt(); val y1 = floor(b.latitudeNorth / cell).toInt()
        val x0 = floor(b.longitudeWest / cell).toInt(); val x1 = floor(b.longitudeEast / cell).toInt()
        if (y1 < y0 || x1 < x0 || (y1 - y0 + 1) * (x1 - x0 + 1) > 12) return null
        val missing = mutableListOf<Pair<Int, Int>>()
        for (y in y0..y1) for (x in x0..x1) if (!done.contains("$y,$x")) missing.add(y to x)
        return missing.ifEmpty { null }
    }

    /** Separate OSM surveillance layer, opt-in; bounded to the same cells as camera lookups. */
    fun loadSurveillance(m: MapLibreMap) {
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
    fun loadCameras(m: MapLibreMap) {
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
            val rating = p.rating?.let { String.format(java.util.Locale.US, "%.1f", it) }
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
            for (id in listOf("vela-route", "vela-route-traffic", "vela-route-bubbles", "vela-route-done", "vela-turn-arrow", "vela-turn-head")) setShape(style, id, emptyList())
            lastHeadKey = ""
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
        // Turn arrow: 35 m of road before the next turn and 22 m after it.
        val m = n?.nextManeuver
        val p = n?.progress
        var arrow = emptyList<Feature>()
        var head: Pair<LatLng, Double>? = null
        if (n != null && m != null && p != null && m.type != ManeuverType.ARRIVE && m.type != ManeuverType.DEPART &&
            m.type != ManeuverType.STRAIGHT && m.alongMeters - p.alongMeters < 600) {
            var end = m.alongMeters + 22
            if (m.type == ManeuverType.ROUNDABOUT) {
                n.route.maneuvers.firstOrNull { it.leavesRoundabout && it.alongMeters > m.alongMeters }?.let {
                    if (it.alongMeters - m.alongMeters < 250) end = it.alongMeters + 22
                }
            }
            val a = max(m.alongMeters - 35, from); val b = min(end, total)
            val shaft = Geo.slice(r.polyline, a, b, cum)
            if (shaft.size >= 2) {
                val tip = shaft.last(); val prev = Geo.point(r.polyline, max(a, b - 4), cum)
                arrow = listOf(polyline(shaft, mapOf("part" to "shaft")))
                head = tip to Geo.bearing(prev, tip)
            }
        }
        setShape(style, "vela-turn-arrow", arrow)
        val headKey = head?.let { (t, b) -> String.format(java.util.Locale.US, "%.6f,%.6f,%.0f", t.lat, t.lng, b) } ?: ""
        if (headKey != lastHeadKey) {
            lastHeadKey = headKey
            setShape(style, "vela-turn-head", head?.let { (t, b) -> listOf(point(t, mapOf("bearing" to b))) } ?: emptyList())
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

    private fun box(m: MapLibreMap, p: org.maplibre.android.geometry.LatLng): RectF {
        val pt: PointF = m.projection.toScreenLocation(p)
        val r = 22 * context.resources.displayMetrics.density
        return RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r)
    }

    private fun str(f: Feature, k: String): String? = f.getProperty(k)?.takeIf { it.isJsonPrimitive }?.asString

    private fun tapped(m: MapLibreMap, at: org.maplibre.android.geometry.LatLng): Boolean {
        if (model.nav != null || now() - pinDroppedAt < 1000) return false
        val box = box(m, at)
        if (model.directions != null) {
            val hits = m.queryRenderedFeatures(box, "vela-route-bubble-0", "vela-route-alt", "vela-route-alt-edge")
            hits.firstOrNull { it.hasProperty("idx") }?.let { model.selectRoute(it.getNumberProperty("idx").toInt()); return true }
            val d = model.directions ?: return true
            if (hits.isNotEmpty()) {
                val tappedAt = at.kite()
                val i = d.current.indexOfFirst { r -> r.id != d.route?.id && r.polyline.any { it.distanceTo(tappedAt) < 300 } }
                if (i >= 0) model.selectRoute(i)
            }
            return true
        }
        m.queryRenderedFeatures(box, "vela-waze").firstOrNull()?.let { f ->
            val id = str(f, "id")
            wazeAlerts.firstOrNull { it.id == id }?.let { view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); model.wazeInfo = it; return true }
        }
        m.queryRenderedFeatures(box, "vela-cameras").firstOrNull()?.let { f ->
            str(f, "surveillanceId")?.toLongOrNull()?.let { id -> surveillance[id]?.let { model.surveillanceInfo = it; return true } }
        }
        m.queryRenderedFeatures(box, "vela-mine").firstOrNull()?.let { f ->
            str(f, "id")?.let { model.myPlace(it) }?.let { model.select(it); return true }
        }
        m.queryRenderedFeatures(box, "vela-results").firstOrNull()?.let { f ->
            val id = str(f, "id")
            model.results.firstOrNull { it.id == id }?.let { model.select(it); return true }
        }
        m.queryRenderedFeatures(box, "poi_r1", "poi_r7", "poi_r20", "poi_transit", "airport").firstOrNull()?.let { poi ->
            val name = str(poi, "name:en") ?: str(poi, "name")
            val g = poi.geometry() as? Point
            if (name != null) {
                model.resolveTapped(name, g?.let { LatLng(it.latitude(), it.longitude()) } ?: at.kite())
                return true
            }
        }
        if (model.selected != null) model.closePlace()
        else if (model.results.isNotEmpty()) model.detent = SheetDetent.PEEK
        return true
    }

    private fun pressed(m: MapLibreMap, at: org.maplibre.android.geometry.LatLng) {
        pinDroppedAt = now()
        if (model.nav != null || model.directions != null) return
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        model.dropPin(at.kite())
    }

    private fun noteHeading(m: MapLibreMap) {
        val h = m.cameraPosition.bearing
        val north = (h % 360) == 0.0
        if (abs(h - model.mapHeading) > 2 || north != (model.mapHeading == 0.0)) model.mapHeading = if (north) 0.0 else h
    }
}
