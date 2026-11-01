package app.kite

import android.view.Choreographer
import app.kite.core.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlin.math.*

/**
 * Moves the navigation arrow and the camera every screen frame instead of once per GPS fix.
 * Between fixes it keeps the arrow rolling along the route at the current speed, then eases
 * onto each new fix, so driving looks smooth like Google Maps or Waze.
 */
class NavGlide : Choreographer.FrameCallback {
    var map: MapLibreMap? = null
    /** The map's height in dp. */
    var heightDp = 800f

    companion object {
        /** Where the car sits on screen while following: 70% of the way down. */
        const val TOP_FRACTION = 0.7f
        const val BOTTOM_INSET = 170f
        fun topInset(height: Float): Float =
            if (height < 500) max(0f, min(height * TOP_FRACTION, height - BOTTOM_INSET - 100)) else height * TOP_FRACTION
        /** The car's height on a screen [height] dp tall, for things drawn next to it. */
        fun carY(height: Float): Float = (topInset(height) + height - BOTTOM_INSET) / 2
        const val PUCK = "vela-puck"
    }

    private var nav: Navigator? = null
    private var running = false
    private var lastFrame = 0L

    private var fixAt = 0L
    private var fixAlong = 0.0
    private var fixCoord = LatLng(0.0, 0.0)
    private var fixBearing = 0.0
    private var fixSpeed = 0.0
    private var fixCourse = -1.0
    private var onRoute = false
    private var line: List<LatLng> = emptyList()
    private var cum = DoubleArray(1)
    private var targetAcross = 600.0

    private var shownAlong: Double? = null
    private var shownCoord: LatLng? = null
    private var shownBearing = 0.0
    private var shownAcross = 600.0

    private fun nowS() = System.nanoTime() / 1e9

    /** Called on every GPS fix while navigating. */
    fun fix(n: Navigator, loc: Fix, across: Double, jump: Boolean) {
        nav = n
        val p = n.progress
        val poly = n.route.polyline
        if (poly.size != line.size || poly.firstOrNull() != line.firstOrNull() || poly.lastOrNull() != line.lastOrNull()) {
            line = poly
            cum = Geo.cumulative(poly)
            shownAlong = null
        }
        onRoute = p?.let { !it.offRoute && it.offsetMeters < 40 } ?: false
        fixAt = System.nanoTime()
        fixAlong = p?.alongMeters ?: 0.0
        fixCoord = loc.coord
        fixSpeed = if (loc.speed > 0.5) loc.speed else 0.0
        fixCourse = if (loc.courseKnown && loc.speed > 2) loc.course else -1.0
        fixBearing = if (fixCourse >= 0) fixCourse else (p?.routeBearing ?: shownBearing)
        targetAcross = across
        if (jump || shownCoord == null) {
            shownAlong = if (onRoute) fixAlong else null
            shownCoord = if (onRoute) coord(fixAlong) else fixCoord
            shownBearing = fixBearing
            shownAcross = across
        }
        start()
        if (jump) step(0.0)
    }

    fun stop() {
        if (running) Choreographer.getInstance().removeFrameCallback(this)
        running = false
        nav = null
        shownAlong = null
        shownCoord = null
        lastFrame = 0
        (map?.style?.getSource(PUCK) as? GeoJsonSource)?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList()))
    }

    private fun start() {
        if (running) return
        running = true
        lastFrame = 0
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrame == 0L) 0.0 else min(0.1, (frameTimeNanos - lastFrame) / 1e9)
        lastFrame = frameTimeNanos
        step(dt)
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }

    private fun step(dt: Double) {
        val n = nav; val m = map
        if (n == null || m == null) { stop(); return }
        val ahead = fixSpeed * min(2.0, max(0.0, (System.nanoTime() - fixAt) / 1e9))
        val k = if (dt == 0.0) 1.0 else 1 - exp(-dt / 0.35)

        val coordNow: LatLng
        var bearingTarget = fixBearing
        if (onRoute && line.isNotEmpty()) {
            val want = min(fixAlong + ahead, cum.last())
            var along = shownAlong ?: want
            if (abs(want - along) > 150) along = want
            val next = along + (want - along) * k
            along = if (next < along && along - want < 15) along else next
            shownAlong = along
            coordNow = coord(along)
            bearingTarget = roadBearing(along)
        } else {
            shownAlong = null
            var want = fixCoord
            if (fixCourse >= 0) want = moved(fixCoord, ahead, fixCourse)
            val from = shownCoord ?: want
            coordNow = if (from.distanceTo(want) > 200) want
            else LatLng(from.lat + (want.lat - from.lat) * k, from.lng + (want.lng - from.lng) * k)
        }
        shownCoord = coordNow
        var turn = (bearingTarget - shownBearing) % 360
        if (turn > 180) turn -= 360 else if (turn < -180) turn += 360
        shownBearing = (shownBearing + turn * (if (dt == 0.0) 1.0 else 1 - exp(-dt / 0.4)) + 360) % 360
        shownAcross += (targetAcross - shownAcross) * (if (dt == 0.0) 1.0 else 1 - exp(-dt / 1.0))

        val style = m.style ?: return
        (style.getSource(PUCK) as? GeoJsonSource)?.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(coordNow.lng, coordNow.lat)).apply { addNumberProperty("bearing", shownBearing) })
        if (n.following) {
            val d = KiteApp.app.resources.displayMetrics.density.toDouble()
            // A pitched camera sees farther than the same zoom flat: zoom in a little to match the iPhone's distance.
            val zoom = MapCam.zoomFor(shownAcross, coordNow.lat, 400f) + 0.35
            m.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(coordNow.ml()).zoom(zoom).tilt(50.0).bearing(shownBearing)
                    .padding(0.0, topInset(heightDp) * d, 0.0, BOTTOM_INSET * d).build()))
        }
    }

    private fun coord(along: Double): LatLng = Geo.point(line, along, cum)

    private fun roadBearing(along: Double): Double {
        val a = Geo.point(line, max(0.0, along - 5), cum)
        val b = Geo.point(line, along + 15, cum)
        return if (a == b) shownBearing else Geo.bearing(a, b)
    }

    private fun moved(c: LatLng, meters: Double, bearing: Double): LatLng {
        val r = bearing * PI / 180
        val dLat = meters * cos(r) / 111_320
        val dLng = meters * sin(r) / (111_320 * max(0.2, cos(c.lat * PI / 180)))
        return LatLng(c.lat + dLat, c.lng + dLng)
    }
}
