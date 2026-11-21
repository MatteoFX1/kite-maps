package app.kite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kite.core.*
import androidx.compose.runtime.withFrameNanos
import app.kite.map.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * Moves the navigation arrow and the camera every screen frame instead of once per GPS fix.
 * Between fixes it keeps the arrow rolling along the route at the current speed, then eases
 * onto each new fix, so driving looks smooth like Google Maps or Waze.
 */
class NavGlide {
    var map: MapHandle? = null
    /** The map screen's own scope, whose frame clock paces the glide (one step per screen frame). */
    var frames: CoroutineScope? = null
    private var loop: Job? = null
    /** The map's height in dp. */
    var heightDp = 800f
    /**
     * True while the camera follows the car: the car then always sits at the same spot on screen,
     * so the navigation screen draws it there itself (steady, never fading) instead of the map.
     */
    var carOnScreen by mutableStateOf(false); private set
    private var puckOnMap = false
    private var puckPushedAt = 0L
    private var puckPushed: LatLng? = null
    private var puckPushedBearing = 0.0

    companion object {
        /** Where the car sits on screen while following: 70% of the way down. */
        const val TOP_FRACTION = 0.7f
        const val BOTTOM_INSET = 170f
        fun topInset(height: Float): Float =
            if (height < 500) max(0f, min(height * TOP_FRACTION, height - BOTTOM_INSET - 100)) else height * TOP_FRACTION
        /** The car's height on a screen [height] dp tall, for things drawn next to it. */
        fun carY(height: Float, north: Float = 0f): Float = (topInset(height) * (1 - north) + height - BOTTOM_INSET) / 2
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
    private var targetPitch = 50.0
    /** 3D, 2D or north up, from the map button. */
    var look = MapLook.TILTED
    /** 0 facing the way you go, 1 north up; eased so a tap turns the map rather than jumping. */
    private var northBlend = 0.0

    /**
     * For the car drawn on screen: how far up toward the middle it has moved (1 in north up), how far it
     * turns (only north up turns it), and how flat it lies (squashed on the tilted map, round in 2D).
     * Read inside drawing lambdas, so each frame only redraws the car.
     */
    var carNorth by mutableFloatStateOf(0f); private set
    var carTurn by mutableFloatStateOf(0f); private set
    var carSquash by mutableFloatStateOf(0.64f); private set

    private var shownAlong: Double? = null
    private var shownCoord: LatLng? = null
    private var shownBearing = 0.0
    private var shownAcross = 600.0
    private var shownPitch = 50.0

    /** Called on every GPS fix while navigating. */
    fun fix(n: Navigator, loc: Fix, across: Double, pitch: Double = 50.0, jump: Boolean) {
        nav = n
        val p = n.progress
        val poly = n.route.polyline
        if (poly.size != line.size || poly.firstOrNull() != line.firstOrNull() || poly.lastOrNull() != line.lastOrNull()) {
            line = poly
            cum = Geo.cumulative(poly)
            shownAlong = null
        }
        onRoute = p?.let { !it.offRoute && it.offsetMeters < 40 } ?: false
        fixAt = nanoNow()
        fixAlong = p?.alongMeters ?: 0.0
        fixCoord = loc.coord
        fixSpeed = if (loc.speed > 0.5) loc.speed else 0.0
        fixCourse = if (loc.courseKnown && loc.speed > 2) loc.course else -1.0
        fixBearing = if (fixCourse >= 0) fixCourse else (p?.routeBearing ?: shownBearing)
        targetAcross = across
        targetPitch = pitch
        if (jump || shownCoord == null) {
            shownAlong = if (onRoute) fixAlong else null
            shownCoord = if (onRoute) coord(fixAlong) else fixCoord
            shownBearing = fixBearing
            shownAcross = across
            shownPitch = pitch
            northBlend = if (look == MapLook.NORTH) 1.0 else 0.0
        }
        start()
        if (jump) step(0.0)
    }

    fun stop() {
        loop?.cancel(); loop = null
        running = false
        nav = null
        shownAlong = null
        shownCoord = null
        lastFrame = 0
        carOnScreen = false
        clearPuck()
        puckOnMap = false
        puckPushed = null
    }

    private fun clearPuck() {
        (map?.style?.getSource(PUCK) as? GeoJsonSource)?.setGeoJson(emptyList())
    }

    private fun start() {
        if (running) return
        val scope = frames ?: return
        running = true
        lastFrame = 0
        loop = scope.launch {
            while (isActive && running) withFrameNanos { doFrame(it) }
        }
    }

    private fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrame == 0L) 0.0 else min(0.1, (frameTimeNanos - lastFrame) / 1e9)
        lastFrame = frameTimeNanos
        step(dt)
    }

    private fun nanoNow() = elapsedRealtime() * 1_000_000

    private fun step(dt: Double) {
        val n = nav; val m = map
        if (n == null || m == null) { stop(); return }
        val ahead = fixSpeed * min(2.0, max(0.0, (nanoNow() - fixAt) / 1e9))
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
        // Zooming in for a turn takes about a second; easing back out afterwards is gentler.
        val zoomTime = if (targetAcross < shownAcross) 1.0 else 2.0
        shownAcross += (targetAcross - shownAcross) * (if (dt == 0.0) 1.0 else 1 - exp(-dt / zoomTime))
        shownPitch += (targetPitch - shownPitch) * (if (dt == 0.0) 1.0 else 1 - exp(-dt / zoomTime))

        val style = m.style ?: return
        if (n.following) {
            if (!carOnScreen) carOnScreen = true
            if (puckOnMap) { clearPuck(); puckOnMap = false }
        } else {
            if (carOnScreen) carOnScreen = false
            // On the map, moved only now and then: each new spot makes the map fade the arrow in again.
            val now = nanoNow()
            val last = puckPushed
            val turned = abs(((shownBearing - puckPushedBearing + 540) % 360) - 180)
            if (last == null || now - puckPushedAt > 1_000_000_000L || last.distanceTo(coordNow) > 25 || turned > 20) {
                (style.getSource(PUCK) as? GeoJsonSource)?.setGeoJson(Feature.point(coordNow, mapOf("bearing" to shownBearing)))
                puckPushed = coordNow; puckPushedAt = now; puckPushedBearing = shownBearing; puckOnMap = true
            }
        }
        val northTarget = if (look == MapLook.NORTH) 1.0 else 0.0
        northBlend += (northTarget - northBlend) * (if (dt == 0.0) 1.0 else 1 - exp(-dt / 0.3))
        if (abs(northTarget - northBlend) < 0.001) northBlend = northTarget
        if (n.following) {
            // North up: the map turns to face north and the car moves up to the middle, as the road
            // ahead can be any way round. Otherwise it faces the way you go with the car low down.
            var toNorth = (-shownBearing) % 360
            if (toNorth > 180) toNorth -= 360 else if (toNorth < -180) toNorth += 360
            val heading = (shownBearing + toNorth * northBlend + 360) % 360
            var carAngle = (shownBearing - heading) % 360
            if (carAngle > 180) carAngle -= 360 else if (carAngle < -180) carAngle += 360
            carNorth = northBlend.toFloat()
            carTurn = carAngle.toFloat()
            carSquash = (1 - 0.36 * (shownPitch / 50.0).coerceIn(0.0, 1.0)).toFloat()
            // A pitched camera sees farther than the same zoom flat: zoom in a little to match the iPhone's distance.
            val zoom = MapCam.zoomFor(shownAcross, coordNow.lat, 400f) + 0.35
            m.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(coordNow).zoom(zoom).tilt(shownPitch).bearing(heading)
                    .padding(0.0, topInset(heightDp) * (1 - northBlend), 0.0, BOTTOM_INSET.toDouble()).build()))
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
