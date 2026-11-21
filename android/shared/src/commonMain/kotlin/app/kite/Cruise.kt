package app.kite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kite.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Driving with no route: Kite noticed you set off (faster than about 15 mph for a few seconds) and
 * switched to its driving view by itself. No turns and no arrival time, but the road you're on, its
 * speed limit, your speed, speed cameras and drivers' reports ahead. Ends by itself once you've been
 * parked for two minutes, or with the ✕. The same as the iPhone's Cruise.
 */
class Cruise(private val scope: CoroutineScope) {
    /** The road under you ("Bassett Avenue") and its number ("A33"), from the road matcher. */
    var road by mutableStateOf<String?>(null); private set
    var ref by mutableStateOf<String?>(null); private set
    /** The posted limit here, in km/h; null when the map doesn't say (never guessed). */
    var limitKmh by mutableStateOf<Double?>(null); private set
    var speedMps by mutableStateOf(0.0); private set
    var cameraAhead by mutableStateOf<CameraAlert?>(null); private set
    var wazeAhead by mutableStateOf<WazeAhead?>(null); private set
    /** The last good fix, for the map. */
    var fix by mutableStateOf<Fix?>(null); private set

    private val trace = mutableListOf<Fix>()
    private var askedAt = 0L
    private var askedFrom: LatLng? = null
    private var asking = false
    private val cameras = HashMap<Long, RoadCamera>()
    private var camerasFrom: LatLng? = null
    private var camerasLoading = false
    private val warned = HashSet<Long>()
    private var cameraId: Long? = null
    private var stoppedSince: Long? = null
    private var waze: List<WazeAlert> = emptyList()
    private var wazeFrom: LatLng? = null
    private var wazeAt = 0L
    private var wazeLoading = false
    private val wazeWarned = HashSet<String>()
    private val wazeHidden = HashSet<String>()
    private var stopped = false
    /** Called when you've been parked long enough for the driving view to end. */
    var onParked: (() -> Unit)? = null

    fun stop() {
        stopped = true
        Voice.stop()
    }

    /** Every GPS fix while the driving view is up. */
    fun handle(f: Fix) {
        if (stopped || f.accuracy < 0 || f.accuracy > 65) return
        fix = f
        speedMps = max(0.0, f.speed)
        // Parked: two minutes under walking pace ends it (a red light or a queue is shorter than that).
        if (f.speedKnown && f.speed < 1.5) {
            val since = stoppedSince ?: f.timeMillis.also { stoppedSince = it }
            if (f.timeMillis - since > 120_000) { onParked?.invoke(); return }
        } else {
            stoppedSince = null
        }
        val last = trace.lastOrNull()
        // Barely moved: no new point, so a stop at the lights doesn't fill the trace with one spot.
        if (last == null || last.coord.distanceTo(f.coord) >= 8) trace.add(f)
        // About 400 m of road is plenty to know which road you're on.
        while (trace.size > 2 && trace.first().coord.distanceTo(trace.last().coord) > 400) trace.removeAt(0)
        whereAmI(f)
        loadCameras(f)
        checkCameras(f)
        loadWaze(f)
        checkWaze(f)
    }

    /** Asks the road matcher every few seconds while moving (and never two at once). */
    private fun whereAmI(f: Fix) {
        val now = nowMillis()
        if (asking || trace.size < 3 || now - askedAt < 4_000) return
        if (askedFrom?.let { it.distanceTo(f.coord) <= 40 } == true) return
        asking = true
        askedAt = now
        askedFrom = f.coord
        val points = trace.map { it.coord }
        scope.launch {
            val here = runCatching { Valhalla.here(points) }.getOrNull()
            asking = false
            // No answer keeps what was known: a blank sign every time the server is slow helps no one.
            if (here == null || stopped) return@launch
            road = here.road
            ref = here.ref
            limitKmh = here.kmh
        }
    }

    /** The speed cameras within about 5 km, asked again every 3 km driven. */
    private fun loadCameras(f: Fix) {
        val s = AppSettings
        if (!s.speedCameras || camerasLoading) return
        if (camerasFrom?.let { it.distanceTo(f.coord) <= 3_000 } == true) return
        camerasLoading = true
        camerasFrom = f.coord
        val dLat = 0.045
        val dLng = 0.045 / max(cos(f.lat * PI / 180), 0.2)
        WazeCameras.enabled = s.wazeOn
        scope.launch {
            val found = runCatching { Overpass.cameras(f.lat - dLat, f.lng - dLng, f.lat + dLat, f.lng + dLng, speed = true, plate = false) }.getOrNull()
            val fromWaze = if (s.wazeOn) runCatching { WazeCameras.along(listOf(LatLng(f.lat - dLat, f.lng), LatLng(f.lat + dLat, f.lng))) }.getOrNull() else null
            camerasLoading = false
            if (found == null) { camerasFrom = f.coord; return@launch }
            for (c in WazeCameras.merge(found, fromWaze ?: emptyList())) cameras[c.id] = c
        }
    }

    private fun checkCameras(f: Fix) {
        val s = AppSettings
        if (!s.speedCameras || !f.courseKnown || f.speed <= 3) {
            if (cameraAhead != null && f.speed <= 3) return
            cameraAhead = null
            return
        }
        val found = CruiseCameras.ahead(cameras.values.toList(), f.coord, f.course, f.speed)
        if (found == null) { cameraAhead = null; cameraId = null; return }
        val (cam, meters) = found
        val first = cameraId != cam.id
        cameraId = cam.id
        cameraAhead = CameraAlert(RoadCamera.Kind.SPEED, meters,
            if (first) meters else max(cameraAhead?.startMeters ?: meters, meters), cam.maxspeedKmh ?: limitKmh)
        if (first && warned.add(cam.id)) {
            Voice.buzz()
            if (s.voice && s.cameraWarnings && !Voice.onCall) Voice.say("Speed camera ahead.", urgent = true)
        }
    }

    /** Drivers' reports within about 4 km, asked again every 90 seconds or 2 km. */
    private fun loadWaze(f: Fix) {
        if (!AppSettings.wazeOn || wazeLoading) return
        val now = nowMillis()
        if (now - wazeAt < 90_000 && wazeFrom?.let { it.distanceTo(f.coord) <= 2_000 } == true) return
        wazeLoading = true
        wazeAt = now
        wazeFrom = f.coord
        val dLat = 0.036
        val dLng = 0.036 / max(cos(f.lat * PI / 180), 0.2)
        val box = Box(f.lat - dLat, f.lng - dLng, f.lat + dLat, f.lng + dLng)
        scope.launch {
            val closures = async { runCatching { Waze.alerts(box.s, box.w, box.n, box.e) }.getOrNull() }
            val live = runCatching { WazeLive.shared.alerts(listOf(box), f.coord) }.getOrNull()
            val fromMap = closures.await()
            wazeLoading = false
            if (fromMap == null && live == null) return@launch
            val merged = LinkedHashMap<String, WazeAlert>()
            for (a in (fromMap ?: emptyList()) + (live ?: emptyList())) merged[a.id] = a
            waze = merged.values.toList()
            WazeHistory.record(waze)
        }
    }

    /** The nearest report ahead worth a card: like on a route, a little earlier than a camera. */
    private fun checkWaze(f: Fix) {
        val s = AppSettings
        if (!s.wazeOn || !f.courseKnown || f.speed <= 3) {
            if (f.speed > 3 || !s.wazeOn) wazeAhead = null
            return
        }
        val reach = min(max(f.speed * 20, 250.0), 1200.0)
        var best: Pair<WazeAlert, Double>? = null
        for (a in waze) {
            if (a.kind == WazeAlert.Kind.JAM || a.id in wazeHidden || !s.shows(a)) continue
            val d = CruiseCameras.distanceAhead(a.location, f.coord, f.course, reach) ?: continue
            if (best == null || d < best.second) best = a to d
        }
        val (a, d) = best ?: run { wazeAhead = null; return }
        val same = wazeAhead?.alert?.id == a.id
        wazeAhead = WazeAhead(a, d, if (same) max(wazeAhead?.startMeters ?: d, d) else d)
        if (!same && wazeWarned.add(a.id)) {
            Voice.buzz()
            if (s.voice && s.wazeVoice && a.kind == WazeAlert.Kind.POLICE && !Voice.onCall) Voice.say("Police reported ahead.", urgent = true)
        }
    }

    /** Tapped away: that report stays hidden for the rest of this drive. */
    fun dismissWaze() {
        wazeAhead?.alert?.id?.let { wazeHidden.add(it) }
        wazeAhead = null
    }

    companion object {
        /**
         * Whether to switch to the driving view: going faster than a brisk run (about 15 mph) for six
         * seconds in a row, with a good fix. [since] is when that started, kept by the caller.
         */
        fun shouldStart(f: Fix, since: Long?): Pair<Boolean, Long?> {
            if (f.accuracy < 0 || f.accuracy > 50 || f.speed < 6.7) return false to null
            val start = since ?: f.timeMillis
            return (f.timeMillis - start >= 6_000) to start
        }
    }
}
