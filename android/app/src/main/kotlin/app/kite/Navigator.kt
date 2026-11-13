package app.kite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import app.kite.core.*
import kotlinx.coroutines.*
import kotlin.math.*

data class CameraAlert(val kind: RoadCamera.Kind, val meters: Double, val startMeters: Double, val limitKmh: Double?)
data class WazeAhead(val alert: WazeAlert, val meters: Double, val startMeters: Double)
data class AverageCheck(val limitKmh: Double?, val averageKmh: Double, val metersLeft: Double)
/** The speaker button's three choices, like Waze. */
enum class VoiceMode(val title: String, val detail: String) {
    ALL("Voice on", "Turns and warnings"),
    ALERTS("Alerts only", "Cameras and police, no turns"),
    OFF("Voice off", "Nothing spoken");

    val next: VoiceMode get() = when (this) { ALL -> ALERTS; ALERTS -> OFF; OFF -> ALL }
}

data class AircraftNear(val aircraft: PoliceAircraft, val meters: Double)

private fun now() = System.currentTimeMillis()
private fun since(t: Long) = (now() - t) / 1000.0
private fun String.lowercasedFirst() = if (isEmpty()) this else substring(0, 1).lowercase() + substring(1)

/**
 * One drive, walk or ride: follows the user along the route, speaks the turns, and reroutes
 * when they leave it. The same rules as the iPhone app, line for line.
 */
class Navigator(route0: Route, val destination: Place, stops0: List<Place>, private val scope: CoroutineScope) {
    var route by mutableStateOf(route0); private set
    var progress by mutableStateOf<RouteTracker.Progress?>(null); private set
    var rerouting by mutableStateOf(false); private set
    var arrived by mutableStateOf(false); private set
    private var voiceState = mutableStateOf(if (AppSettings.voice) VoiceMode.ALL else VoiceMode.OFF)
    /** What the voice says: everything, only warnings (cameras, police, hazards), or nothing. */
    var voiceMode: VoiceMode
        get() = voiceState.value
        set(v) {
            voiceState.value = v
            if (v == VoiceMode.OFF || (v == VoiceMode.ALERTS && !speakingAlert)) Voice.stop()
        }
    val muted: Boolean get() = voiceMode == VoiceMode.OFF
    /** Whether what's being said now is a warning, so switching to "Alerts only" doesn't cut it off. */
    private var speakingAlert = false
    /** The camera follows the user until they pan the map. */
    var following by mutableStateOf(true)
    var speedMps by mutableStateOf(0.0); private set
    var lastFix by mutableStateOf<Fix?>(null); private set
    /** No GPS fix for a while (a tunnel, a car park): the screen says so instead of looking live. */
    var gpsLost by mutableStateOf(false); private set
    var cameraAhead by mutableStateOf<CameraAlert?>(null); private set
    /** How far along the route the card's camera is. */
    private var cameraAheadAt = 0.0
    var wazeAhead by mutableStateOf<WazeAhead?>(null); private set
    var wazeWarned by mutableIntStateOf(0); private set
    var policeRisk by mutableStateOf<Int?>(null); private set
    var aircraftNear by mutableStateOf<AircraftNear?>(null); private set
    var averageCheck by mutableStateOf<AverageCheck?>(null); private set
    /** Traffic lights on the route, one per junction, drawn on the map like Waze. */
    var routeSignals by mutableStateOf<List<LatLng>>(emptyList()); private set
    /** Stops still ahead, in trip order; each is dropped once reached. */
    var stops by mutableStateOf(stops0); private set
    /** Drivers' reports from Waze's app servers, asked every 90 seconds. */
    var liveAlerts by mutableStateOf<List<WazeAlert>>(emptyList()); private set
    /** Bumped when the route's cameras change, so the map redraws them. */
    var camerasTick by mutableIntStateOf(0); private set

    private var tracker = RouteTracker(route0)
    private var rerouteSpokenAt = 0L
    private var toldNoSignal = false
    private var spokenStage = mutableMapOf<Int, Int>()
    private var stoppedNearEnd: Long? = null
    private var trafficScale = 1.0
    private var trafficAt = now()
    private var trafficLoading = false
    private var cameraSpokenAt = 0L
    private var cameraSpokenLimit: Double? = null
    private var joinedRoute = false
    private var fullRoadData = false
    private var matchedLimits = false
    private var matchedWays: List<Overpass.LimitWay> = emptyList()
    /** The road matcher's lane counts into each roundabout on this route. */
    private var entryLanes: List<RoundaboutEntry> = emptyList()
    var onFinished: (() -> Unit)? = null
    private var toldContinue = mutableSetOf<Int>()
    private var lastReroute = 0L
    private var rerouteRequest = 0
    private var stopped = false
    private val avoidHighways = AppSettings.avoidHighways
    private val avoidTolls = AppSettings.avoidTolls
    var routeCameras: List<PlacedCamera> = emptyList(); private set
    private val spokenCameras = mutableSetOf<Long>()
    private var zones: List<AverageSpeedZone> = emptyList()
    /** The average speed check being driven through: its stretch of the route, and when and where it began. */
    private data class ZoneEntry(val start: Double, val end: Double, val time: Long, val along: Double, val kmh: Double?)
    private var zoneEntry: ZoneEntry? = null
    /**
     * Rerouted (or a faster way taken) inside an average speed check: when it began, how far was
     * driven in it, and the rest of its road from the old route, so the same check carries on on
     * the new route instead of starting again from nothing. Dropped once the car leaves that road.
     */
    private data class ZoneCarry(val time: Long, val driven: Double, val rest: List<LatLng>, val at: Long, val kmh: Double?)
    private var zoneCarry: ZoneCarry? = null
    private val speeding = SpeedingAlerts()
    private var routeWaze: List<PlacedAlert> = emptyList()
    private val spokenWaze = mutableSetOf<String>()
    private var wazeAt = 0L
    private var wazeLoading = false
    private var liveAt = 0L
    private var closureAlerts: List<WazeAlert> = emptyList()
    private var riskAt = 0L
    private var riskPast: List<PoliceRisk.Report>? = null
    private var riskSpokenAt = 0L
    private var aircraftAt = 0L
    /** When the aircraft service last answered: an old sighting isn't "nearby" for long. */
    private var aircraftSeenAt = 0L
    private var aircraftLoading = false
    private val spokenAircraft = mutableSetOf<String>()
    private var tracedAt = 0L
    private val recentFixes = mutableListOf<Fix>()

    /** "left" or "right": which side of the road the place is, at the end of the route. */
    private val destinationSide: String? get() = if (route.mode == TravelMode.DRIVE) Geo.sideAtEnd(destination.location, route.polyline) else null

    private fun onOurSide(cams: List<PlacedCamera>): List<PlacedCamera> {
        if (matchedWays.isEmpty()) return cams
        return cams.filter { c -> matchedWays.any { w -> w.line.size >= 2 && Geo.project(c.camera.location, w.line).offset < 15 } }
    }

    private val gpsWatch = scope.launch {
        while (true) {
            delay(2_000)
            val fix = lastFix ?: continue
            if (demoAlerts) continue
            val lost = fix.ageMs > 8_000
            if (lost != gpsLost) gpsLost = lost
        }
    }

    init {
        Voice.warmUp()
        val first = route0.maneuvers.firstOrNull { it.type != ManeuverType.DEPART } ?: route0.maneuvers.firstOrNull()
        loadCameras()
        loadWaze()
        loadRoundabouts()
        // The first step with how far it is ("In 300 yards, turn left"), so a turn a little way off
        // doesn't sound like it's right here.
        val firstWords = first?.let { f ->
            if (f.alongMeters < 40 || f.type == ManeuverType.ARRIVE) "${f.spokenWords}."
            else "In ${Fmt.spokenDistance(f.alongMeters)}, ${f.spokenWords.lowercasedFirst()}."
        } ?: ""
        speak("Starting route to ${destination.name}. $firstWords")
        // That was the heads-up for the first step: not again a moment later, but still the turn itself.
        if (first != null && first.alongMeters < 1500) {
            val i = route0.maneuvers.indexOf(first)
            if (i >= 0) spokenStage[i] = if (first.alongMeters < 40) 3 else if (first.alongMeters < 300) 2 else 1
        }
    }

    fun stop() {
        stopped = true
        gpsWatch.cancel()
        Voice.stop()
    }

    /** The posted limit where the user is now, in km/h. */
    val speedLimitKmh: Double? get() = demoLimitKmh ?: route.speedLimit(progress?.alongMeters ?: 0.0)

    val nextManeuver: Maneuver?
        get() {
            demoManeuver?.let { return it }
            val i = progress?.nextIndex
            if (i == null || i >= route.maneuvers.size) return route.maneuvers.firstOrNull()
            return route.maneuvers[i]
        }

    /** The road being driven now, for the name under the arrow. */
    val currentRoad: String?
        get() {
            val along = progress?.alongMeters ?: return null
            val i = route.maneuvers.indexOfLast { it.alongMeters <= along + 8 }
            if (i < 0) return null
            return route.maneuvers[i].road?.trim()?.ifEmpty { null }
        }

    /**
     * ", then turn right onto King Street" when the step after [i] comes so soon after it (70 m)
     * there's no time to be told on its own, the way Waze says both together. Not for leaving a
     * roundabout (said with the roundabout) or arriving.
     */
    private fun thenWords(i: Int): String {
        if (route.mode != TravelMode.DRIVE || i >= route.maneuvers.size) return ""
        val j = (i + 1 until route.maneuvers.size).firstOrNull { !route.maneuvers[it].silent } ?: return ""
        val a = route.maneuvers[i]; val b = route.maneuvers[j]
        if (b.leavesRoundabout || b.type == ManeuverType.ARRIVE || b.alongMeters - a.alongMeters >= 70) return ""
        return ", then ${b.spokenWords.lowercasedFirst()}"
    }

    /** The one after next, for the "Then" line when two turns come close together. */
    val thenManeuver: Maneuver?
        get() {
            val i = progress?.nextIndex ?: return null
            if (i >= route.maneuvers.size) return null
            val j = (i + 1 until route.maneuvers.size).firstOrNull { !route.maneuvers[it].silent } ?: return null
            val a = route.maneuvers[i]; val b = route.maneuvers[j]
            if (b.leavesRoundabout) return null
            return if (b.alongMeters - a.alongMeters < 300) b else null
        }

    private fun update(fix: Fix, heading: Double? = null): RouteTracker.Progress {
        val p = tracker.update(fix.coord, max(fix.accuracy, 5.0), heading)
        return p.copy(remainingSeconds = p.remainingSeconds * trafficScale)
    }

    fun handle(loc: Fix) {
        if (arrived) return
        lastFix = loc
        if (!demoAlerts) speedMps = max(0.0, loc.speed)
        val course = heading(loc)
        noteFix(loc)
        val p = update(loc, course)
        progress = p
        val toPin = loc.coord.distanceTo(destination.location)
        // Near the pin only counts near the end of the route too: a route can pass right by the
        // place (or by it on the way to a stop) before looping round to its way in.
        // And only once every stop is done: a round trip starts right by its own destination.
        val nearPin = p.remainingMeters < 400 && stops.isEmpty()
        if (loc.speedKnown && loc.speed < 1 && (p.remainingMeters < 150 || toPin < 150)) {
            if (stoppedNearEnd == null) stoppedNearEnd = loc.timeMillis
        } else stoppedNearEnd = null
        val offRoad = p.offsetMeters > 20 || p.remainingMeters < 40 || (toPin < 60 && nearPin)
        // Parked by the pin with the route still going round (a way in the map doesn't have): three minutes.
        val parked = stoppedNearEnd?.let {
            (loc.timeMillis - it) / 1000.0 > (if (p.remainingMeters >= 150 && !nearPin) 180 else if (offRoad) 20 else 90)
        } ?: false
        if (p.arrived || parked || (route.mode == TravelMode.DRIVE && toPin < 40 && nearPin)) {
            arrived = true
            speak("You have arrived at ${destination.name}" + (destinationSide?.let { ", on the $it." } ?: "."))
            scope.launch {
                delay(180_000)
                if (arrived && !stopped) onFinished?.invoke()
            }
            return
        }
        if (p.offsetMeters < 40) joinedRoute = true
        if (p.offRoute && !rerouting && (toPin > 150 || !nearPin) && (joinedRoute || p.offsetMeters > 400) && since(lastReroute) > 8) {
            reroute(loc)
            return
        }
        if (since(tracedAt) >= 3) {
            tracedAt = now()
            Log.i("KiteTrace", String.format(java.util.Locale.US, "at %.5f,%.5f along %d off %d next %d in %d offRoute %b speed %d",
                loc.lat, loc.lng, p.alongMeters.toInt(), p.offsetMeters.toInt(), p.nextIndex ?: -1, p.distanceToNext.toInt(), p.offRoute, speedMps.toInt()))
        }
        announce(p)
        checkTraffic(p, loc)
        checkCameras(p)
        checkWaze(p)
        checkRisk(p)
        checkAircraft(loc)
        checkZones(p)
        checkStops(p)
        if (route.mode == TravelMode.DRIVE && AppSettings.speedingAlert &&
            speeding.update(if (loc.speedKnown) loc.speed * 3.6 else null, speedLimitKmh, loc.timeMillis / 1000.0)) {
            speak("You're over the speed limit.", alert = true)
        }
    }

    /** A stop picked on the way (search along the route): go there first, then on. */
    fun addStop(p: Place) {
        stops = listOf(p) + stops
        speak("Adding a stop at ${p.name}.")
        lastFix?.let { reroute(it, quiet = true, adding = p) }
    }

    private fun checkStops(p: RouteTracker.Progress) {
        val next = stops.firstOrNull() ?: return
        val at = Geo.project(next.location, route.polyline)
        if (at.offset >= 150 || p.alongMeters < at.along - 40) return
        stops = stops.drop(1)
        speak("You've reached ${next.name}. Continuing to ${stops.firstOrNull()?.name ?: destination.name}.")
    }

    /** The real shape of each roundabout ahead, for the picture in the banner. */
    private fun loadRoundabouts() {
        if (route.mode == TravelMode.WALK) return
        val start = route
        scope.launch {
            var r = start
            Roundabouts.addMinis(r.deep())?.let { withMinis ->
                if (route.id == r.id) { useSteps(withMinis.maneuvers); r = route }
            }
            if (r.maneuvers.none { it.type == ManeuverType.ROUNDABOUT }) return@launch
            val drawn = Roundabouts.apply(r.deep()) ?: return@launch
            if (route.id != r.id || route.maneuvers.size != drawn.maneuvers.size) return@launch
            val steps = route.maneuvers.mapIndexed { i, m -> m.copy(roundabout = drawn.maneuvers[i].roundabout) }.toMutableList()
            // Only the pictures change, not the steps: the tracker keeps its place on the route,
            // like the iPhone's (a new one could snap to the wrong part of a route that loops back).
            route = route.copy(maneuvers = steps)
            // The road matcher's lane counts, where OpenStreetMap's layout came without them.
            Roundabouts.withEntryLanes(route.maneuvers, entryLanes)?.let { route = route.copy(maneuvers = it) }
        }
    }

    /** New steps on the same route line: what was already said stays said, step by step. */
    private fun useSteps(steps: List<Maneuver>) {
        val old = route.maneuvers
        fun moved(i: Int): Int? {
            if (i >= old.size) return null
            return steps.indexOfFirst { it.alongMeters == old[i].alongMeters }.takeIf { it >= 0 }
        }
        val stages = mutableMapOf<Int, Int>()
        spokenStage.forEach { (k, v) -> moved(k)?.let { stages[it] = max(stages[it] ?: 0, v) } }
        spokenStage = stages
        toldContinue = toldContinue.mapNotNull { moved(it) }.toMutableSet()
        route = route.copy(maneuvers = steps.toMutableList())
        tracker = RouteTracker(route)
        lastFix?.let { progress = update(it) }
    }

    private fun setLimits(l: List<SpeedLimit>) { route = route.copy(speedLimits = l) }

    private fun loadCameras() {
        routeCameras = emptyList()
        cameraAhead = null
        zones = emptyList()
        zoneEntry = null
        averageCheck = null
        routeSignals = emptyList()
        camerasTick++
        val s = AppSettings
        if (route.mode != TravelMode.DRIVE) return
        val line = route.polyline; val id = route.id
        val speed = s.speedCameras; val plate = s.plateCameras
        fullRoadData = false
        matchedLimits = false
        matchedWays = emptyList()
        entryLanes = emptyList()
        val matcher = scope.async {
            val found = Valhalla.speedLimits(line) { partial ->
                withContext(Dispatchers.Main) {
                    if (route.id == id && partial.isNotEmpty()) { matchedLimits = true; setLimits(partial) }
                }
            }
            if (found == null || route.id != id || found.limits.isEmpty()) {
                // A long drive's later miles didn't come back (the server timed out on one piece): the
                // first miles were already shown, but the rest must still come from the road data below,
                // or the sign stays blank past the first 25 km for the whole drive.
                if (route.id == id) matchedLimits = false
                return@async false
            }
            matchedLimits = true
            setLimits(found.limits)
            matchedWays = found.ways
            // How many lanes go into each roundabout, in case OpenStreetMap's own answer doesn't come.
            entryLanes = found.entries
            Roundabouts.withEntryLanes(route.maneuvers, found.entries)?.let { route = route.copy(maneuvers = it) }
            routeCameras = onOurSide(routeCameras)
            camerasTick++
            withContext(Dispatchers.IO) { RoadCache.shared.add(found.ways.filter { it.kmh != null }, emptyList(), emptyList()) }
            true
        }
        suspend fun useKnown() {
            val known = withContext(Dispatchers.Default) {
                val k = RoadCache.shared.along(line)
                val cams = CameraAlerts.project(k.cameras.filter { if (it.kind == RoadCamera.Kind.SPEED) speed else plate }, line)
                Triple(cams, if (k.ways.isEmpty()) emptyList() else Overpass.speedLimits(k.ways, line), k.signals)
            }
            if (route.id != id || fullRoadData) return
            if (known.second.isNotEmpty() && !matchedLimits) setLimits(known.second)
            routeCameras = onOurSide(known.first)
            zones = CameraAlerts.averageZones(known.first)
            routeSignals = known.third
            camerasTick++
        }
        scope.launch { useKnown() }
        val cum = Geo.cumulative(line)
        if (cum.last() > 8000) {
            val start = Geo.slice(line, 0.0, 6000.0, cum)
            scope.launch {
                val limits = !matcher.await()
                Overpass.alongRoute(start, speed, plate, limits) ?: return@launch
                useKnown()
            }
        }
        scope.launch {
            val limits = !matcher.await()
            var result = Overpass.alongRoute(line, speed, plate, limits)
            repeat(2) {
                if (result == null && route.id == id) {
                    delay(20_000)
                    result = Overpass.alongRoute(line, speed, plate, limits)
                }
            }
            val found = result ?: return@launch
            if (route.id != id) return@launch
            fullRoadData = true
            routeCameras = onOurSide(CameraAlerts.project(found.cameras, line))
            if (!matchedLimits) setLimits(Overpass.speedLimits(found.ways, line))
            zones = CameraAlerts.merge(found.zones, CameraAlerts.averageZones(routeCameras))
            routeSignals = found.signals
            camerasTick++
        }
    }

    private fun checkCameras(p: RouteTracker.Progress) {
        if (demoAlerts) return
        val meters = routeCameras.map { it.along }
        // Keep the card up, counting down, until its own camera is passed.
        cameraAhead?.let { ahead ->
            val left = cameraAheadAt - p.alongMeters
            cameraAhead = if (left > 0 && left < CameraAlerts.maxLead) ahead.copy(meters = left) else null
        }
        val spoken = routeCameras.indices.filter { spokenCameras.contains(routeCameras[it].camera.id) }.toSet()
        val i = CameraAlerts.due(meters, p.alongMeters, speedMps, spoken) ?: return
        val cam = routeCameras[i].camera
        // A speed camera's card stays up until it's passed: a plate camera just after it waits its turn.
        if (cam.kind != RoadCamera.Kind.SPEED && cameraAhead?.kind == RoadCamera.Kind.SPEED) return
        if (cam.quiet) spokenCameras.add(cam.id)
        // Only the same kind, though: a number plate camera just before a speed camera mustn't use up its warning.
        else meters.forEachIndexed { j, m ->
            if (m >= meters[i] && m - meters[i] < 200 && routeCameras[j].camera.kind == cam.kind) spokenCameras.add(routeCameras[j].camera.id)
        }
        val ahead = meters[i] - p.alongMeters
        val limit = if (cam.kind == RoadCamera.Kind.SPEED) (cam.maxspeedKmh ?: route.speedLimit(meters[i])) else null
        cameraAhead = CameraAlert(cam.kind, ahead, max(ahead, 1.0), limit)
        cameraAheadAt = meters[i]
        val repeatRun = since(cameraSpokenAt) < 150 && limit == cameraSpokenLimit
        if (AppSettings.cameraWarnings && cam.kind == RoadCamera.Kind.SPEED && !cam.quiet) {
            chime()
            if (!repeatRun) {
                speak((if (cam.average) "Average speed camera ahead" else "Speed camera ahead") + (limit?.let { ", limit ${Fmt.spokenSpeed(it)}." } ?: "."), alert = true)
            }
            cameraSpokenAt = now()
            cameraSpokenLimit = limit
        }
    }

    /** Live Waze reports along the route; asked again every 90 seconds, since they come and go. */
    private fun loadWaze() {
        if (demoAlerts) return
        if (route.mode != TravelMode.DRIVE || !AppSettings.wazeOn || wazeLoading) return
        wazeLoading = true
        wazeAt = now()
        val line = route.polyline; val id = route.id
        val at = progress?.alongMeters ?: 0.0
        val window = Geo.slice(line, max(at - 200, 0.0), at + 60_000)
        val here = progress?.let { Geo.point(line, it.alongMeters) } ?: line[0]
        val ahead = Geo.slice(line, max(at - 200, 0.0), at + 40_000)
        scope.launch {
            val closures = async { runCatching { Waze.alongRoute(if (window.size >= 2) window else line) }.getOrNull() }
            val live = runCatching { WazeLive.shared.alongRoute(if (ahead.size >= 2) ahead else line, here) }.getOrNull()
            val found = closures.await()
            wazeLoading = false
            if (route.id != id) return@launch
            if (found != null) { WazeHistory.record(found); closureAlerts = found }
            if (live != null) {
                WazeHistory.record(live)
                liveAlerts = live
                liveAt = now()
            } else if (liveAlerts.isNotEmpty() && since(liveAt) > 300) {
                liveAlerts = emptyList()
            }
            val merged = linkedMapOf<String, WazeAlert>()
            for (a in closureAlerts + liveAlerts) merged[a.id] = a
            routeWaze = Waze.project(merged.values.toList(), line)
        }
    }

    private fun checkWaze(p: RouteTracker.Progress) {
        if (demoAlerts) return
        val s = AppSettings
        if (since(wazeAt) > 90) loadWaze()
        wazeAhead?.let { ahead ->
            val hit = routeWaze.firstOrNull { it.alert.id == ahead.alert.id }
            wazeAhead = if (hit != null && hit.along > p.alongMeters - 10) {
                val a = ahead.copy(meters = max(hit.along - p.alongMeters, 0.0))
                if (a.meters > 0) a else null
            } else null
        }
        if (!s.wazeOn || speedMps < CameraAlerts.movingFloor) return
        val lead = min(max(speedMps * 20, 250.0), 1200.0)
        val next = routeWaze.firstOrNull {
            !spokenWaze.contains(it.alert.id) && s.shows(it.alert) && it.along > p.alongMeters && it.along - p.alongMeters <= lead
        } ?: return
        // A police car or a crash is never folded into a lesser report next to it, though.
        val major = setOf(WazeAlert.Kind.POLICE, WazeAlert.Kind.ACCIDENT)
        for (w in routeWaze) if ((abs(w.along - next.along) < 60 && (w.alert.kind == next.alert.kind || w.alert.kind !in major)) ||
            (w.alert.kind == next.alert.kind && abs(w.along - next.along) < 400)) spokenWaze.add(w.alert.id)
        val ahead = next.along - p.alongMeters
        wazeAhead = WazeAhead(next.alert, ahead, max(ahead, 1.0))
        wazeWarned++
        Voice.buzz()
        if (s.wazeVoice && !Voice.onCall) {
            chime()
            speak("${next.alert.spoken.dropLast(1)}, in ${Fmt.spokenDistance(ahead)}.", alert = true)
        }
    }

    /** The police risk for the next 3 km, worked out again every 20 seconds. */
    private fun checkRisk(p: RouteTracker.Progress) {
        if (demoAlerts) return
        val s = AppSettings
        if (route.mode != TravelMode.DRIVE || !s.wazeOn || !s.policeRisk || !s.wazePolice) { policeRisk = null; return }
        if (since(riskAt) <= 20) return
        riskAt = now()
        if (riskPast == null) riskPast = WazeHistory.reports
        val to = p.alongMeters + 3000
        val ahead = Geo.slice(route.polyline, p.alongMeters, to)
        val live = routeWaze.count { it.alert.kind == WazeAlert.Kind.POLICE && s.shows(it.alert) && it.along > p.alongMeters && it.along < to }
        val score = PoliceRisk.score(ahead, riskPast ?: emptyList(), live)
        val was = policeRisk?.let { PoliceRisk.level(it) } ?: PoliceRisk.Level.LOW
        policeRisk = score
        if (PoliceRisk.level(score) == PoliceRisk.Level.HIGH && was != PoliceRisk.Level.HIGH && s.wazeVoice && !Voice.onCall &&
            since(riskSpokenAt) > 600 &&
            (live == 0 || PoliceRisk.level(PoliceRisk.score(ahead, riskPast ?: emptyList(), 0)) == PoliceRisk.Level.HIGH)) {
            riskSpokenAt = now()
            speak("Police hotspot ahead.", alert = true)
        }
    }

    /** Police aircraft near the user, asked every 30 seconds while driving. */
    private fun checkAircraft(loc: Fix) {
        if (demoAlerts) return
        if (route.mode != TravelMode.DRIVE || !AppSettings.policeAircraft) { aircraftNear = null; return }
        val here = loc.coord
        aircraftNear?.let {
            val d = it.aircraft.location.distanceTo(here)
            // No answer for two minutes (no signal), or 10 km past where it was: it's no longer nearby.
            aircraftNear = if (d < 10_000 && since(aircraftSeenAt) < 120) it.copy(meters = d) else null
        }
        if (aircraftLoading || since(aircraftAt) <= 30) return
        aircraftLoading = true
        aircraftAt = now()
        scope.launch {
            val found = runCatching { Aircraft.police(here, 10) }.getOrNull()
            aircraftLoading = false
            if (found == null || stopped) return@launch
            aircraftSeenAt = now()
            val me = lastFix?.coord ?: here
            val nearest = found.map { it to it.location.distanceTo(me) }.filter { it.second < 10_000 }.minByOrNull { it.second }
            aircraftNear = nearest?.let { AircraftNear(it.first, it.second) }
            val a = nearest?.first ?: return@launch
            if (spokenAircraft.add(a.id)) {
                Voice.buzz()
                chime()
                if (!Voice.onCall) speak("${a.title} nearby.", alert = true)
            }
        }
    }

    /** Average speed checks: say when one starts and ends, and keep the user's average on screen. */
    private fun checkZones(p: RouteTracker.Progress) {
        if (demoAlerts) return
        val at = p.alongMeters
        // How far along the carried check's road the car is now, while it's still on that road.
        var carried: Double? = null
        zoneCarry?.let { c ->
            val on = Geo.project(p.snapped, c.rest)
            if (c.rest.size >= 2 && on.offset < 40 && on.along < Geo.length(c.rest) - 10 && since(c.at) < 600) carried = c.driven + on.along
            // Off that road, past its end or long ago: a check from here on is a new one.
            else zoneCarry = null
        }
        val inside = zones.indexOfFirst { at >= it.startMeters && at <= it.endMeters }.takeIf { it >= 0 }
        zoneEntry?.let { entry ->
            // The list of checks is replaced as road data comes in: the same stretch of road is
            // the same check, not one ending and another starting ("ended" then "for 2 miles").
            val same = inside?.let { zones[it] }?.takeIf { it.startMeters <= entry.end && it.endMeters >= entry.start }
            if (same != null) {
                zoneEntry = entry.copy(start = same.startMeters, end = same.endMeters, kmh = same.kmh ?: entry.kmh)
            } else {
                zoneEntry = null
                averageCheck = null
                if (AppSettings.cameraWarnings) speak("Average speed check ended.", alert = true)
            }
        }
        val i = inside ?: return
        val z = zones[i]
        val limit = z.kmh ?: route.speedLimit(at)
        if (zoneEntry == null) {
            // Rerouted inside a check and still on its road: the same check, carried on quietly
            // with the average so far (every metre driven in it, the reroute included).
            val c = zoneCarry
            val driven = carried
            // A different limit is a different check, even on a road right next to it.
            val ck = c?.kmh; val zk = z.kmh
            if (c != null && driven != null && (ck == null || zk == null || abs(ck - zk) < 2)) {
                zoneEntry = ZoneEntry(z.startMeters, z.endMeters, c.time, at - driven, zk ?: ck)
            } else {
                zoneEntry = ZoneEntry(z.startMeters, z.endMeters, now(), at, zk)
                if (AppSettings.cameraWarnings) {
                    chime()
                    speak("Average speed check for ${Fmt.spokenDistance(z.endMeters - at)}" + (limit?.let { ", limit ${Fmt.spokenSpeed(it)}." } ?: "."), alert = true)
                }
            }
            zoneCarry = null
        }
        val entry = zoneEntry ?: return
        val secs = since(entry.time)
        val avg = if (secs > 5) (at - entry.along) / secs * 3.6 else speedMps * 3.6
        averageCheck = AverageCheck(limit, avg, z.endMeters - at)
    }

    /** Tapping the Waze card puts it away; the report still won't be warned about again. */
    fun dismissWaze() { wazeAhead = null }

    // For the build's screenshots only: made-up warnings, so every run shows the same thing.
    private var demoAlerts = false
    private var demoLimitKmh: Double? = null
    var demoManeuver by mutableStateOf<Maneuver?>(null); private set

    fun showDemoRoundabout() {
        demoManeuver = Maneuver(ManeuverType.ROUNDABOUT, "At the roundabout, take the 6th exit onto M27", LatLng(50.87, -1.27), 0.0, 400.0,
            road = "M27", exit = 6, roundabout = RoundaboutShape(exits = listOf(262.0, 300.0, 338.0, 20.0, 62.0, 108.0), taken = 5,
                entriesOnly = listOf(150.0), clockwise = true, complete = true))
    }

    fun showDemoAlerts() {
        demoAlerts = true
        cameraAhead = CameraAlert(RoadCamera.Kind.SPEED, 180.0, 400.0, 48.3)
        averageCheck = AverageCheck(80.5, 77.0, 2400.0)
        // 38 in a 30, so the screenshots show the speed sign gone red.
        showDemoSpeed(48.3, 38.0)
    }

    fun showDemoSpeed(limitKmh: Double, mph: Double) {
        demoAlerts = true
        demoLimitKmh = limitKmh
        speedMps = mph / 2.23694
    }

    fun showDemoWaze() {
        demoAlerts = true
        val here = progress?.let { Geo.point(route.polyline, it.alongMeters + 300) } ?: route.polyline.firstOrNull() ?: LatLng(0.0, 0.0)
        val a = WazeAlert(id = "demo-police", kind = WazeAlert.Kind.POLICE, subtype = "POLICE_HIDING", location = here, street = "A9",
            reportedAtMillis = System.currentTimeMillis() - 7 * 60_000, thumbsUp = 3, reliability = 8)
        wazeAhead = WazeAhead(a, 260.0, 600.0)
        policeRisk = 72
        aircraftNear = AircraftNear(PoliceAircraft(id = "demo-heli", callsign = "NPAS15", location = here, altitudeFeet = 1400.0, helicopter = true), 3200.0)
    }

    private fun chime() {
        if (muted || stopped || Voice.onCall) return
        Voice.chime()
    }

    private fun announce(p: RouteTracker.Progress) {
        if (rerouting) return
        val i = p.nextIndex ?: return
        if (i >= route.maneuvers.size) return
        val m = route.maneuvers[i]
        val d = p.distanceToNext
        val stage = spokenStage[i] ?: 0
        val drive = route.mode == TravelMode.DRIVE
        val nowD = if (drive) max(40.0, speedMps * 4) else 15.0
        val near = if (drive) max(250.0, speedMps * 12) else 60.0
        val far = if (drive) max(1000.0, speedMps * 40) else 250.0
        if (d <= nowD && stage < 3) {
            spokenStage[i] = 3
            if (m.type == ManeuverType.ARRIVE) return
            if (m.leavesRoundabout) {
                val r = route.maneuvers.subList(0, i).lastOrNull { it.type == ManeuverType.ROUNDABOUT }
                if (r != null && m.alongMeters - r.alongMeters < 150) return
            }
            speak(m.spokenWords + thenWords(i), urgent = true)
        } else if (m.leavesRoundabout) {
            return
        } else if (d <= near && d > nowD + 30 && stage < 2) {
            spokenStage[i] = 2
            val lane = LaneGuide.lanes(m, Geo.drivesOnLeft(m.location))?.let { g ->
                if (g.second.startsWith("Lane")) ". Use " + g.second.lowercase().replace("from left", "from the left")
                else ". Use the ${g.second.lowercase()}"
            } ?: ""
            val words = if (m.type == ManeuverType.ARRIVE) destinationSide?.let { "your destination is on the $it" } ?: "your destination is ahead"
                else m.spokenWords.lowercasedFirst()
            speak("In ${Fmt.spokenDistance(d)}, $words${if (m.type == ManeuverType.ARRIVE) "" else thenWords(i)}$lane")
        } else if (d <= far && d > near + 150 && stage < 1 && m.type != ManeuverType.ARRIVE) {
            spokenStage[i] = 1
            if (Voice.onCall) return
            speak("In ${Fmt.spokenDistance(d)}, ${m.spokenWords.lowercasedFirst()}")
        } else if (drive && d > 3000 && stage < 1 && !toldContinue.contains(i) && justPassed(p)) {
            toldContinue.add(i)
            if (!Voice.onCall) speak(continueWords(d))
        }
    }

    /** Every few minutes on a drive, asks again for the way from here, like Waze. */
    private fun checkTraffic(p: RouteTracker.Progress, loc: Fix) {
        if (route.mode != TravelMode.DRIVE || AppSettings.noGoogle || rerouting || trafficLoading ||
            p.remainingMeters <= 5000 || p.offRoute || since(trafficAt) <= 240) return
        trafficLoading = true
        trafficAt = now()
        val from = loc.coord
        val course = heading(loc); val id = route.id; val planned = p.remainingSeconds / trafficScale
        val to = destination.location; val via = stops.map { it.location }
        scope.launch {
            val r = runCatching { Directions.routes(from, to, TravelMode.DRIVE, avoidHighways, avoidTolls, via = via, heading = course) }.getOrNull()
            trafficLoading = false
            val fresh = r?.routes?.firstOrNull() ?: return@launch
            val nowP = progress ?: return@launch
            // Arrived (or ended) while asking: no new route, and no "Found a faster route" in the car park.
            if (stopped || arrived || route.id != id || rerouting || planned <= 0 || fresh.trafficSeconds == null) return@launch
            val current = nowP.remainingSeconds
            val saving = current - fresh.durationSeconds
            val sameWay = abs(fresh.distanceMeters - nowP.remainingMeters) < max(300.0, nowP.remainingMeters * 0.03)
            if (!sameWay && saving > max(300.0, current * 0.1)) {
                adopt(fresh)
                speak("Found a faster route. It saves ${(saving / 60).roundToInt()} minutes.")
            } else if (sameWay) {
                trafficScale = min(3.0, max(0.5, fresh.durationSeconds / planned))
            }
        }
    }

    private fun justPassed(p: RouteTracker.Progress): Boolean {
        val i = p.nextIndex ?: return false
        val last = route.maneuvers.subList(0, i).lastOrNull { !it.silent }?.alongMeters ?: 0.0
        return p.alongMeters - last < 500
    }

    private fun continueWords(meters: Double): String {
        var road = currentRoad
        val p = progress
        val next = p?.nextIndex
        if (p != null && next != null && route.maneuvers.subList(0, next).any { it.alongMeters > p.alongMeters && it.road != null && it.road != road }) road = null
        return "Continue " + (road?.let { "on $it " } ?: "") + "for ${Fmt.spokenDistance(meters)}."
    }

    private fun sayNewRoute() {
        val p = progress ?: return
        val i = p.nextIndex ?: return
        if (i >= route.maneuvers.size || (spokenStage[i] ?: 0) != 0 || toldContinue.contains(i)) return
        val m = route.maneuvers[i]; val d = p.distanceToNext
        val nowD = max(40.0, speedMps * 4); val near = max(250.0, speedMps * 12)
        if (d <= nowD + 30 || m.type == ManeuverType.ARRIVE) return
        if (d > 3000) {
            toldContinue.add(i)
            speak(continueWords(d))
        } else {
            spokenStage[i] = if (d <= near) 2 else 1
            speak("In ${Fmt.spokenDistance(d)}, ${m.spokenWords.lowercasedFirst()}")
        }
    }

    /** The way the phone is moving, once it's fast enough for that to be trusted. */
    private fun heading(loc: Fix): Double? {
        if (loc.courseKnown && loc.speed >= 4) return loc.course
        if (loc.accuracy >= 20) return null
        val from = recentFixes.lastOrNull { loc.coord.distanceTo(it.coord) >= 20 && (loc.timeMillis - it.timeMillis) < 8000 } ?: return null
        return Geo.bearing(from.coord, loc.coord)
    }

    private fun noteFix(loc: Fix) {
        if (loc.accuracy >= 20) return
        recentFixes.add(loc)
        recentFixes.removeAll { loc.timeMillis - it.timeMillis > 20_000 }
    }

    private fun reroute(loc: Fix, quiet: Boolean = false, adding: Place? = null) {
        Log.i("KiteTrace", "reroute from ${loc.lat},${loc.lng}")
        rerouting = true
        lastReroute = now()
        if (!Connectivity.online) {
            if (!quiet && !toldNoSignal) {
                toldNoSignal = true
                speak("No signal. Keep going, and Kite will find a new route when it's back.")
            }
        } else if (!quiet && since(rerouteSpokenAt) > 45 && loc.coord.distanceTo(destination.location) > 150) {
            rerouteSpokenAt = now()
            speak("Rerouting.")
        }
        val from = loc.coord
        val course = if (route.mode == TravelMode.DRIVE) heading(loc) else null
        val mode = route.mode
        val to = destination.location
        rerouteRequest++
        val req = rerouteRequest
        scope.launch {
            val r = runCatching {
                Directions.routes(from, to, mode, avoidHighways, avoidTolls, !AppSettings.noGoogle, stops.map { it.location }, course)
            }.getOrNull()
            if (stopped || req != rerouteRequest) return@launch
            rerouting = false
            // Parked at the destination while the new route was loading: the trip is over, keep it that way.
            if (arrived) return@launch
            val fresh = r?.routes?.firstOrNull()
            if (fresh == null) {
                // The stop just added couldn't be routed to: say so, and don't keep it as if it were on the way.
                val i = if (adding != null) stops.indexOfFirst { it.id == adding.id } else -1
                if (adding != null && i >= 0) {
                    // Only the one just added (at the front), not the same place planned earlier.
                    stops = stops.toMutableList().also { it.removeAt(i) }
                    speak("Couldn't add a stop at ${adding.name}. Carrying on to ${stops.firstOrNull()?.name ?: destination.name}.")
                }
                return@launch
            }
            toldNoSignal = false
            adopt(fresh)
            if (!quiet) sayNewRoute()
        }
    }

    /** Drives [fresh] from here on: a reroute, or a faster way found on the way. */
    private fun adopt(fresh: Route) {
        // Inside an average speed check: keep the rest of its road from the route being left.
        val e = zoneEntry; val p = progress
        // With none showing (a second reroute before the cameras came back), one already kept stays.
        if (e != null && p != null) zoneCarry = if (e.end - p.alongMeters > 20)
            ZoneCarry(e.time, max(0.0, p.alongMeters - e.along), Geo.slice(route.polyline, p.alongMeters, e.end), now(), e.kmh) else null
        route = fresh
        tracker = RouteTracker(fresh)
        spokenStage = mutableMapOf()
        toldContinue = mutableSetOf()
        trafficScale = 1.0
        trafficAt = now()
        loadCameras()
        loadRoundabouts()
        wazeAt = 0
        routeWaze = emptyList()
        wazeAhead = null
        loadWaze()
        lastFix?.let { handle(it) }
    }

    /** Something the app needs to tell the driver mid-drive, where a notice wouldn't be seen. */
    fun say(text: String) = speak(text)

    private fun speak(text: String, urgent: Boolean = false, alert: Boolean = false) {
        if (stopped || !(voiceMode == VoiceMode.ALL || (voiceMode == VoiceMode.ALERTS && alert))) return
        speakingAlert = alert
        Voice.say(text, urgent)
    }
}

/** Which lanes to use for a step, and the words for them. */
object LaneGuide {
    fun lanes(m: Maneuver, leftTraffic: Boolean): Pair<List<Lane>, String>? {
        if (m.lanes.size > 1) return named(m.lanes)
        val hint = m.laneHint
        if (hint != null && !hint.contains("any lane", ignoreCase = true) && m.type != ManeuverType.ROUNDABOUT) {
            val words = hint.replace("use the ", "", ignoreCase = true)
            return emptyList<Lane>() to words.capitalizedFirst()
        }
        if (m.type != ManeuverType.ROUNDABOUT || m.leavesRoundabout) return null
        val lanes = m.roundabout?.ruleLanes(leftTraffic, m.laneHint) ?: return null
        return named(lanes)
    }

    /** The lanes with words for the good ones, by the edge they touch. */
    fun named(lanes: List<Lane>): Pair<List<Lane>, String>? {
        val valid = lanes.indices.filter { lanes[it].valid }
        val first = valid.firstOrNull() ?: return null
        val last = valid.last()
        if (valid.size >= lanes.size) return null
        val n = lanes.size; val count = valid.size
        val many = if (count > 1) "$count lanes" else "lane"
        val words = when {
            first == 0 -> "Left $many"
            last == n - 1 -> "Right $many"
            count == 1 && n > 3 -> "Lane ${first + 1} from left"
            else -> if (count > 1) "Middle lanes" else "Middle lane"
        }
        return lanes to words
    }
}

/**
 * What the voice says for this step: a roundabout's way off too, as the banner shows it, so it's
 * "take the 3rd exit onto West End Road" and not just "take the 3rd exit" (the open router's
 * roundabout steps often leave the road out of the words).
 */
val Maneuver.spokenWords: String
    get() {
        val r = road?.trim()
        if (type != ManeuverType.ROUNDABOUT || leavesRoundabout || r.isNullOrEmpty() || instruction.contains(" onto ") || instruction.contains(" toward")) return instruction
        return "$instruction onto $r"
    }
