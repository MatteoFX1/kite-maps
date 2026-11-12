package app.kite.core

import kotlinx.coroutines.*
import kotlin.math.*

/**
 * The FOSSGIS OSRM servers (routing.openstreetmap.de), which Vela uses for turn-by-turn steps.
 * Here they're the open-data router: used when Google gives no route.
 */
object OSRM {
    const val userAgent = "KiteMaps-Android/0.7 (+https://github.com/matteofx1/kite-maps)"
    private val client = Http.client(20)

    private suspend fun fetch(url: String): ByteArray = Http.get(url, mapOf("User-Agent" to userAgent), client).body

    suspend fun route(a: LatLng, b: LatLng, mode: TravelMode, via: List<LatLng> = emptyList(), heading: Double? = null): List<Route> =
        parse(fetch(routeURL(a, b, mode, via, heading)), mode)

    /** With [heading] (the way the car is moving), the route starts off that way: never a U-turn back down the road. */
    fun routeURL(a: LatLng, b: LatLng, mode: TravelMode, via: List<LatLng> = emptyList(), heading: Double? = null): String {
        val pts = listOf(a) + via + listOf(b)
        val coords = pts.joinToString(";") { "${it.lng.plain()},${it.lat.plain()}" }
        var s = "https://routing.openstreetmap.de/${mode.osrmBackend}/route/v1/${mode.osrmProfile}/$coords" +
            "?overview=full&geometries=polyline6&steps=true&alternatives=${mode == TravelMode.DRIVE && via.isEmpty()}"
        if (heading != null) s += "&bearings=${heading.roundToInt() % 360},60" + ";".repeat(pts.size - 1)
        return s
    }

    /** The open router's turn-by-turn for exactly [line] (Google's route), by map matching. */
    suspend fun match(line: List<LatLng>, mode: TravelMode): Route? {
        if (line.size < 2) return null
        val url = matchURL(line, mode)
        val root = Json.obj(fetch(url)) ?: return null
        val matchings = root["matchings"].asMaps() ?: return null
        if (matchings.size != 1) return null
        val m = parseRoot(mapOf("routes" to matchings), mode).firstOrNull() ?: return null
        val total = Geo.length(line)
        return if (abs(m.distanceMeters - total) < max(150.0, total * 0.04)) m else null
    }

    fun matchURL(line: List<LatLng>, mode: TravelMode): String {
        val cum = Geo.cumulative(line)
        val total = cum.last()
        val n = min(99, max(1, (total / 150).toInt()))
        val pts = (0..n).map { Geo.point(line, total * it / n, cum) }
        val coords = pts.joinToString(";") { String.format(java.util.Locale.US, "%.6f,%.6f", it.lng, it.lat) }
        val radii = List(pts.size) { "25" }.joinToString(";")
        return "https://routing.openstreetmap.de/${mode.osrmBackend}/match/v1/${mode.osrmProfile}/$coords" +
            "?overview=full&geometries=polyline6&steps=true&tidy=true&gaps=ignore&radiuses=$radii&waypoints=0;${pts.size - 1}"
    }

    /** The open router forced through points sampled off [line] (Google's route), so its steps follow the same roads. */
    suspend fun routeVia(line: List<LatLng>, mode: TravelMode, spacing: Double = 1_500.0, maxVias: Int = 12): Route? {
        if (line.size < 2) return null
        val cum = Geo.cumulative(line)
        val total = cum.last()
        val vias = min(maxVias, (total / spacing).toInt())
        val pts = mutableListOf(line[0])
        val bearings = mutableListOf(bearing(line, cum, 0.0))
        for (i in 1..vias) {
            val m = total * i / (vias + 1)
            pts.add(Geo.point(line, m, cum))
            bearings.add(bearing(line, cum, m))
        }
        pts.add(line[line.size - 1])
        bearings.add(null)
        val coords = pts.joinToString(";") { String.format(java.util.Locale.US, "%.6f,%.6f", it.lng, it.lat) }
        val b = bearings.joinToString(";") { it?.let { v -> "${v.toInt()},45" } ?: "" }
        val radii = pts.indices.joinToString(";") { if (it == 0 || it == pts.size - 1) "unlimited" else "30" }
        val url = "https://routing.openstreetmap.de/${mode.osrmBackend}/route/v1/${mode.osrmProfile}/$coords" +
            "?overview=full&geometries=polyline6&steps=true&continue_straight=true&bearings=$b&radiuses=$radii"
        return parse(fetch(url), mode).firstOrNull()
    }

    private fun bearing(line: List<LatLng>, cum: DoubleArray, m: Double): Double? {
        val a = Geo.point(line, max(0.0, m - 10), cum)
        val b = Geo.point(line, min(cum.last(), m + 10), cum)
        return if (a.distanceTo(b) > 1) Geo.bearing(a, b) else null
    }

    fun parse(data: ByteArray, mode: TravelMode): List<Route> = Json.obj(data)?.let { parseRoot(it, mode) } ?: emptyList()

    private fun loc(x: Any?): LatLng? = x.asList()?.let { if (it.size == 2) LatLng(it[1].num() ?: return null, it[0].num() ?: return null) else null }

    fun parseRoot(root: Map<String, Any?>, mode: TravelMode): List<Route> {
        val routes = root["routes"].asMaps() ?: return emptyList()
        return routes.mapNotNull { r ->
            val geo = r["geometry"] as? String ?: return@mapNotNull null
            val distance = r["distance"].num() ?: return@mapNotNull null
            val duration = r["duration"].num() ?: return@mapNotNull null
            val line = Geo.decodePolyline(geo, 1e6)
            if (line.size < 2) return@mapNotNull null
            val cum = Geo.cumulative(line)
            var maneuvers = mutableListOf<Maneuver>()
            val mergeSides = mutableMapOf<Int, String>()
            val nameOnly = mutableSetOf<Int>()
            var along = 0.0
            val roads = LinkedHashMap<String, Double>()
            val legs = r["legs"].asMaps() ?: emptyList()
            for ((li, leg) in legs.withIndex()) {
                val steps = leg["steps"].asMaps() ?: emptyList()
                for ((si, step) in steps.withIndex()) {
                    val m = step["maneuver"].asMap() ?: emptyMap()
                    val type = m["type"] as? String ?: ""
                    val dist = step["distance"].num() ?: 0.0
                    if ((type == "arrive" && li < legs.size - 1) || (type == "depart" && li > 0)) {
                        maneuvers.lastOrNull()?.let { it.distanceMeters += dist }
                        along += dist
                        continue
                    }
                    val modifier = m["modifier"] as? String
                    val ref0 = (step["ref"] as? String)?.ifEmpty { null }
                    val ref = ref0?.split(";")?.firstOrNull()?.trim()
                    val motorway = ref?.takeIf { Regex("^(M\\d+|A\\d+\\(M\\))$").containsMatchIn(it) }
                    val name = motorway ?: (step["name"] as? String)?.ifEmpty { null }
                    (name ?: ref)?.let { roads[it] = (roads[it] ?: 0.0) + dist }
                    val at = loc(m["location"]) ?: Geo.point(line, along, cum)
                    val mt = maneuverType(type, modifier, Geo.drivesOnLeft(at))
                    val signs = Signs(step["destinations"] as? String, step["exits"] as? String, maneuvers.lastOrNull()?.road)
                    val exit = m["exit"].int()
                    val man = Maneuver(mt, instruction(type, modifier, name ?: ref, exit, mode, signs), at,
                        min(along, cum.last()), dist, name ?: ref)
                    man.lanes = lanes(step)
                    man.exit = exit
                    if (type == "roundabout" || type == "rotary") {
                        if (man.lanes.isEmpty() && si > 0) {
                            steps[si - 1]["intersections"].asMaps()?.lastOrNull()?.let {
                                man.lanes = approachLanes(it, at, modifier, Geo.drivesOnLeft(at))
                            }
                        }
                        man.roundabout = roundaboutSketch(step, steps.getOrNull(si + 1), Geo.drivesOnLeft(at))
                    }
                    if (type == "exit roundabout" || type == "exit rotary") {
                        man.leavesRoundabout = true
                        man.lanes = emptyList()
                        val prev = maneuvers.lastOrNull()
                        if (prev != null && prev.type == ManeuverType.ROUNDABOUT) {
                            man.exit = man.exit ?: prev.exit
                            man.roundabout = prev.roundabout
                        }
                    }
                    if (type == "merge" && modifier != null) mergeSides[maneuvers.size] = modifier
                    if ((type == "new name" || type == "continue") && (modifier ?: "straight") == "straight") nameOnly.add(maneuvers.size)
                    maneuvers.add(man)
                    along += dist
                }
            }
            for (i in nameOnly) if (i > 0 && maneuvers[i].lanes.isEmpty()) maneuvers[i].silent = true
            maneuvers = keepLaneThroughMerges(maneuvers, mergeSides)
            maneuvers.lastOrNull()?.alongMeters = cum.last()
            val via = roads.maxByOrNull { it.value }?.key
            val route = Route(mode, line, distance, duration, null, via, maneuvers, source = "osm")
            route.speedLimits = speedLimits(legs, cum)
            route
        }
    }

    /** The roundabout as far as the router knows it, placed around a circle fitted through the points. */
    fun roundaboutSketch(step: Map<String, Any?>, next: Map<String, Any?>?, clockwise: Boolean): RoundaboutShape? {
        val ints = step["intersections"].asMaps() ?: return null
        if (ints.isEmpty()) return null
        val exitInt = next?.get("intersections").asMaps()?.firstOrNull() ?: return null
        val entry = loc(ints[0]["location"]) ?: return null
        val out = loc(exitInt["location"]) ?: return null
        val passed = mutableListOf<LatLng>()
        val inOnly = mutableListOf<LatLng>()
        for (x in ints.drop(1)) {
            val p = loc(x["location"]) ?: continue
            val bearings = x["bearings"].asList() ?: continue
            val entries = x["entry"].asList()?.map { it == true } ?: emptyList()
            val into = x["in"].int()
            val onward = x["out"].int()
            for (k in bearings.indices) {
                if (k == into || k == onward) continue
                if (k < entries.size && entries[k]) passed.add(p) else inOnly.add(p)
            }
        }
        val ring = listOf(entry) + passed + inOnly + listOf(out)
        val center = fitCircle(ring) ?: return null
        val e = Geo.bearing(center, entry)
        fun rel(p: LatLng): Double {
            val a = Geo.bearing(center, p) - e + 180
            return ((a % 360) + 360) % 360
        }
        val exits = passed.map { rel(it) } + listOf(rel(out))
        return RoundaboutShape(exits, exits.size - 1, inOnly.map { rel(it) }, clockwise, false)
    }

    /** The middle of the circle best through [pts] (least squares), or null when they don't make one. */
    fun fitCircle(pts: List<LatLng>): LatLng? {
        if (pts.size < 3) return null
        val o = pts[0]
        val k = cos(o.lat * PI / 180) * 111_320.0
        val xy = pts.map { Pair((it.lng - o.lng) * k, (it.lat - o.lat) * 110_540) }
        val a = Array(3) { DoubleArray(3) }
        val b = DoubleArray(3)
        for ((x, y) in xy) {
            val row = doubleArrayOf(x, y, 1.0)
            val z = -(x * x + y * y)
            for (i in 0 until 3) {
                for (j in 0 until 3) a[i][j] += row[i] * row[j]
                b[i] += row[i] * z
            }
        }
        fun det(m: Array<DoubleArray>) =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        val d = det(a)
        if (abs(d) <= 1e-6) return null
        fun col(c: Int) = Array(3) { i -> DoubleArray(3) { j -> if (j == c) b[i] else a[i][j] } }
        val dd = det(col(0)) / d
        val ee = det(col(1)) / d
        val cx = -dd / 2
        val cy = -ee / 2
        val r = xy.sumOf { (x, y) -> sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) } / xy.size
        if (r <= 3 || r >= 300) return null
        return LatLng(o.lat + cy / 110_540, o.lng + cx / k)
    }

    /** The turn lanes at a step's maneuver point (its first intersection), left to right. */
    fun lanes(step: Map<String, Any?>): List<Lane> {
        val first = step["intersections"].asMaps()?.firstOrNull() ?: return emptyList()
        val raw = first["lanes"].asMaps() ?: return emptyList()
        if (raw.size <= 1) return emptyList()
        val lanes = raw.map { l ->
            Lane(l["indications"].asList()?.mapNotNull { it as? String } ?: listOf("none"), l["valid"] as? Boolean ?: false)
        }
        return if (lanes.all { it.valid }) emptyList() else lanes
    }

    private val leftSide = setOf(ManeuverType.RAMP_LEFT, ManeuverType.FORK_LEFT, ManeuverType.KEEP_LEFT)
    private val rightSide = setOf(ManeuverType.RAMP_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.KEEP_RIGHT)

    /** A slip road that joins a main road and leaves again at the next exit on the same side: no merge, keep the lane. */
    fun keepLaneThroughMerges(steps: List<Maneuver>, mergeSides: Map<Int, String>): MutableList<Maneuver> {
        val out = mutableListOf<Maneuver>()
        var dropped = false
        for ((i, m0) in steps.withIndex()) {
            val side = mergeSides[i]
            if (side != null && i > 0) {
                val n = (i + 1 until steps.size).firstOrNull { !steps[it].silent }
                if (n != null) {
                    val next = steps[n]
                    val fromLeft = side.endsWith("right")
                    val exitSameSide = if (fromLeft) next.type in leftSide else next.type in rightSide
                    if (exitSameSide && next.alongMeters - m0.alongMeters < 1_000 && out.isNotEmpty()) {
                        out.last().distanceMeters += m0.distanceMeters
                        dropped = true
                        continue
                    }
                }
            }
            val m = m0.copy()
            if (dropped && m.silent) { out.add(m); continue }
            if (dropped && m.lanes.isEmpty()) {
                val left = m.type in leftSide
                val exit = Lane(listOf(if (left) "slight left" else "slight right"), true)
                val main = Lane(listOf("straight"), false)
                m.lanes = if (left) listOf(exit, main) else listOf(main, exit)
            }
            dropped = false
            out.add(m)
        }
        return out
    }

    /** Lanes mapped at a junction just before a roundabout, marked again for the way round it. */
    fun approachLanes(x: Map<String, Any?>, roundabout: LatLng, modifier: String?, leftTraffic: Boolean): List<Lane> {
        val raw = x["lanes"].asMaps() ?: return emptyList()
        if (raw.size <= 1) return emptyList()
        val at = loc(x["location"]) ?: return emptyList()
        if (at.distanceTo(roundabout) >= 200) return emptyList()
        val out = x["out"].int() ?: return emptyList()
        val entry = x["entry"].asList()?.map { it == true } ?: emptyList()
        val into = x["in"].int()
        if (entry.indices.any { it != out && it != into && entry[it] }) return emptyList()
        val m = modifier ?: "straight"
        val way = when {
            m.endsWith("left") -> "left"
            m.endsWith("right") -> "right"
            m == "uturn" -> if (leftTraffic) "right" else "left"
            else -> "straight"
        }
        val lanes = raw.map { r ->
            val ind = r["indications"].asList()?.mapNotNull { it as? String } ?: listOf("none")
            Lane(ind, ind.any { it == way || it.endsWith(" $way") })
        }
        val valid = lanes.count { it.valid }
        return if (valid == 0 || valid == lanes.size) emptyList() else lanes
    }

    /** Per-segment `maxspeed` annotations turned into limit changes along the line. */
    fun speedLimits(legs: List<Map<String, Any?>>, cum: DoubleArray): List<SpeedLimit> {
        val out = mutableListOf<SpeedLimit>()
        var seg = 0
        outer@ for (leg in legs) {
            val speeds = leg["annotation"].asMap()?.get("maxspeed").asMaps() ?: emptyList()
            for (sp in speeds) {
                if (seg >= cum.size) break@outer
                var kmh: Double? = null
                sp["speed"].num()?.let { v -> kmh = if (sp["unit"] == "mph") v * 1.609344 else v }
                if (out.lastOrNull()?.let { it.kmh != kmh } != false) out.add(SpeedLimit(cum[seg], kmh))
                seg++
            }
        }
        return if (out.any { it.kmh != null }) out else emptyList()
    }

    fun maneuverType(type: String, modifier: String?, leftTraffic: Boolean = false): ManeuverType {
        when (type) {
            "depart" -> return ManeuverType.DEPART
            "arrive" -> return ManeuverType.ARRIVE
            "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> return ManeuverType.ROUNDABOUT
            "merge" -> return ManeuverType.MERGE
            "on ramp", "off ramp" -> {
                val m = modifier ?: ""
                return if (m.contains("left")) ManeuverType.RAMP_LEFT else if (m.contains("right")) ManeuverType.RAMP_RIGHT
                else if (leftTraffic) ManeuverType.RAMP_LEFT else ManeuverType.RAMP_RIGHT
            }
            "fork" -> return if ((modifier ?: "").contains("left")) ManeuverType.FORK_LEFT else ManeuverType.FORK_RIGHT
        }
        return when (modifier) {
            "left" -> ManeuverType.TURN_LEFT
            "right" -> ManeuverType.TURN_RIGHT
            "slight left" -> if (type == "continue" || type == "new name") ManeuverType.KEEP_LEFT else ManeuverType.SLIGHT_LEFT
            "slight right" -> if (type == "continue" || type == "new name") ManeuverType.KEEP_RIGHT else ManeuverType.SLIGHT_RIGHT
            "sharp left" -> ManeuverType.SHARP_LEFT
            "sharp right" -> ManeuverType.SHARP_RIGHT
            "uturn" -> ManeuverType.UTURN
            else -> ManeuverType.STRAIGHT
        }
    }

    /** What the road signs at a junction say, and the road being left, for slip road wording. */
    data class Signs(val destinations: String? = null, val exits: String? = null, val from: String? = null) {
        /** "Eastleigh and Southampton" from "A335: Eastleigh, Southampton"; the road numbers if that's all there is. */
        val toward: String?
            get() {
                val d = destinations?.replace(";", ",")?.trim()
                if (d.isNullOrEmpty()) return null
                val parts = d.split(":", limit = 2).map { it.trim() }
                val names = (if (parts.size == 2) parts[1] else parts[0]).split(",")
                    .map { it.replace(Regex("\\([^)]*\\)"), "").trim() }.filter { it.isNotEmpty() }
                val refs = if (parts.size == 2) parts[0].split(",").map { it.trim() } else emptyList()
                var pick = names.take(2)
                if (pick.isEmpty()) pick = refs.take(2)
                if (pick.isEmpty()) return null
                return if (pick.size == 2) "${pick[0]} and ${pick[1]}" else pick[0]
            }

        /** Junction number from the sign, like "5" or "9A". */
        val number: String? get() = exits?.split(";")?.firstOrNull()?.trim()?.ifEmpty { null }
    }

    fun instruction(type: String, modifier: String?, road: String?, exit: Int?, mode: TravelMode, signs: Signs = Signs()): String {
        val onto = road?.let { " onto $it" } ?: ""
        val dir = modifier ?: "straight"
        val newRoad = road?.takeIf { it != signs.from }
        return when {
            type == "depart" -> {
                val verb = if (mode == TravelMode.DRIVE) "Drive" else if (mode == TravelMode.BIKE) "Ride" else "Walk"
                road?.let { "$verb along $it" } ?: "Head out"
            }
            type == "arrive" -> "You have arrived"
            type == "roundabout turn" && exit == null -> {
                val turn = if (dir.contains("left")) "turn left" else if (dir.contains("right")) "turn right" else "go straight on"
                "At the roundabout, $turn$onto"
            }
            type == "roundabout" || type == "rotary" || type == "roundabout turn" -> {
                if (road != null && road.lowercase().contains("roundabout")) {
                    exit?.let { "At $road, take the ${ordinal(it)} exit" } ?: "Go straight through $road"
                } else {
                    val n = exit?.let { "take the ${ordinal(it)} exit" } ?: "go straight through"
                    "At the roundabout, $n$onto"
                }
            }
            type == "exit roundabout" || type == "exit rotary" -> "Exit the roundabout$onto"
            type == "merge" -> "Merge$onto"
            type == "on ramp" -> newRoad?.let { "Take the slip road onto $it" } ?: ("Take the slip road" + (signs.toward?.let { " toward $it" } ?: ""))
            type == "off ramp" -> {
                val base = signs.number?.let { "Take exit $it" } ?: "Take the exit"
                signs.toward?.let { "$base toward $it" } ?: (base + (newRoad?.let { " onto $it" } ?: ""))
            }
            type == "fork" -> {
                val toward = signs.toward?.let { " toward $it" } ?: ""
                "Keep ${if (dir.contains("left")) "left" else "right"} at the fork" + (newRoad?.let { " onto $it" } ?: toward)
            }
            type == "end of road" -> "At the end of the road, turn ${dir.replace("slight ", "")}$onto"
            type == "continue" || type == "new name" -> {
                val stay = road?.let { " to stay on $it" } ?: ""
                when {
                    dir == "uturn" -> "Make a U-turn" + (road?.let { " on $it" } ?: "")
                    dir.contains("slight") -> "Keep ${if (dir.contains("left")) "left" else "right"}" + (if (type == "continue") stay else onto)
                    dir.contains("left") || dir.contains("right") -> "Turn $dir" + (if (type == "continue") stay else onto)
                    else -> "Continue" + (road?.let { " on $it" } ?: "")
                }
            }
            else -> when (dir) {
                "uturn" -> "Make a U-turn$onto"
                "straight" -> "Continue straight$onto"
                else -> "Turn $dir$onto"
            }
        }
    }

    fun ordinal(n: Int): String = when (n) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        else -> "${n}th"
    }
}

/** What the app asks for routes: Google (traffic, alternates), else the open router. */
object Directions {
    data class Result(val routes: List<Route>, val error: String? = null)

    suspend fun routes(
        a: LatLng, b: LatLng, mode: TravelMode,
        avoidHighways: Boolean = false, avoidTolls: Boolean = false,
        useGoogle: Boolean = true, via: List<LatLng> = emptyList(), heading: Double? = null,
        alternatives: Boolean = false,
    ): Result = coroutineScope {
        // Ask both at once: Google's route knows traffic, OSRM's steps read better.
        val osrmTask = async(Dispatchers.IO) { runCatching { OSRM.route(a, b, mode, via, heading) } }
        var err: String? = null
        if (useGoogle) {
            try {
                var g = GoogleClient.shared.directions(a, b, mode, avoidHighways, avoidTolls, via).toMutableList()
                if (heading != null) {
                    var ahead = g.filter { !startsBehind(it.polyline, heading) }
                    if (ahead.isEmpty() && (avoidHighways || avoidTolls)) ahead = g
                    g = (if (alternatives) ahead else ahead.take(1)).toMutableList()
                }
                if (g.isNotEmpty()) {
                    val reroute = heading != null && !alternatives
                    val wait = if (reroute) 5.0 else 25.0
                    val vias = mutableMapOf<Int, Route>()
                    for (i in g.indices) {
                        val line = g[i].polyline
                        deadline(wait) { runCatching { OSRM.routeVia(line, mode) }.getOrNull() }?.let { vias[i] = it }
                    }
                    val plain = deadline(wait) { osrmTask.await().getOrNull() } ?: emptyList()
                    val spare = plain.toMutableList()
                    for (i in g.indices) {
                        val candidates = vias[i]?.let { listOf(it) + plain } ?: plain
                        // Google's step positions are only trustworthy when its steps cover the whole line.
                        val complete = g[i].stepsComplete
                        val steps = StepMerge.adopt(candidates, g[i].polyline)
                            ?: if (complete) StepMerge.adoptPiecewise(candidates, g[i].maneuvers, g[i].polyline) else null
                        if (steps != null) {
                            StepMerge.dropFalseUTurns(steps, g[i].polyline)
                            if (complete) {
                                StepMerge.addGoogleLaneHints(steps, g[i].maneuvers)
                                StepMerge.addGoogleExits(steps, g[i].maneuvers)
                                StepMerge.addGoogleKinds(steps, g[i].maneuvers)
                                StepMerge.addGoogleMissing(steps, g[i].maneuvers, g[i].polyline)
                                StepMerge.addGoogleSigns(steps, g[i].maneuvers)
                            }
                            g[i].maneuvers = steps
                            g[i].stepsComplete = true
                        } else if (!g[i].stepsComplete && spare.isNotEmpty()) {
                            val o = spare.removeAt(0).deep()
                            val t = g[i].trafficSeconds
                            if (t != null && g[i].typicalSeconds > 0) o.trafficSeconds = o.typicalSeconds * t / g[i].typicalSeconds
                            g[i] = o
                        }
                    }
                    for (i in g.indices) {
                        for (m in g[i].maneuvers) if (m.type == ManeuverType.ROUNDABOUT && m.exit == null) m.exit = DirectionsParser.exitNumber(m.instruction)
                        StepMerge.tidy(g[i].maneuvers)
                        g[i] = Roundabouts.settle(g[i])
                        // Britain and Ireland ("slip road" is British).
                        if (a.lat in 49.8..61.0 && a.lng in -11.0..2.0) for (m in g[i].maneuvers) m.instruction = British.words(m.instruction)
                    }
                    osrmTask.cancel()
                    return@coroutineScope Result(g)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err = e.message ?: "Google didn't answer."
            }
        }
        val o = osrmTask.await()
        o.getOrNull()?.let { if (it.isNotEmpty()) return@coroutineScope Result(it.map { r -> StepMerge.tidy(r.maneuvers); Roundabouts.settle(r) }) }
        o.exceptionOrNull()?.let { err = it.message }
        Result(emptyList(), err ?: "No route found.")
    }

    /** Whether [line] sets off more than a right angle away from [heading]: back the way the car came. */
    fun startsBehind(line: List<LatLng>, heading: Double): Boolean {
        if (line.size < 2) return false
        val cum = Geo.cumulative(line)
        val end = Geo.point(line, min(40.0, cum.last()), cum)
        if (line[0].distanceTo(end) <= 5) return false
        return Geo.diff(Geo.bearing(line[0], end), heading) > 100
    }
}

/** Puts an open router's steps on Google's route line, when they follow the same roads. */
object StepMerge {
    /** The steps of the candidate that best fits [line], placed on it; null if none fits well. */
    fun adopt(candidates: List<Route>, line: List<LatLng>): MutableList<Maneuver>? {
        if (line.size < 2) return null
        val cum = Geo.cumulative(line)
        val total = cum.last()
        var best: MutableList<Maneuver>? = null
        var bestScore = 0.0
        for (o in candidates) {
            if (!follows(o.polyline, line, cum) || !follows(line, o.polyline, Geo.cumulative(o.polyline))) continue
            val mids = o.maneuvers.filter { it.type != ManeuverType.DEPART && it.type != ManeuverType.ARRIVE }
            val placed = mutableListOf<Maneuver>()
            var last = 0.0
            for (m in mids) {
                val p = Geo.project(m.location, line, cum)
                if (!(p.offset < 40 && p.along >= last - 30 && p.along < total - 5)) continue
                last = max(last, p.along)
                placed.add(m.copy(alongMeters = last, location = Geo.point(line, last, cum)))
            }
            val score = if (mids.isEmpty()) (if (abs(Geo.length(o.polyline) - total) < 100) 1.0 else 0.0) else placed.size.toDouble() / mids.size
            if (score <= bestScore) continue
            bestScore = score
            val depart = (o.maneuvers.firstOrNull { it.type == ManeuverType.DEPART }?.copy()
                ?: Maneuver(ManeuverType.DEPART, "Head out", line[0], 0.0, 0.0)).apply { alongMeters = 0.0; location = line[0] }
            val arrive = (o.maneuvers.lastOrNull { it.type == ManeuverType.ARRIVE }?.copy()
                ?: Maneuver(ManeuverType.ARRIVE, "You have arrived", line.last(), total, 0.0)).apply { alongMeters = total; location = line.last() }
            val all = (listOf(depart) + placed + listOf(arrive)).toMutableList()
            for (i in 0 until all.size - 1) all[i].distanceMeters = all[i + 1].alongMeters - all[i].alongMeters
            all.last().distanceMeters = 0.0
            best = all
        }
        return if (bestScore >= 0.7) best else null
    }

    /** Whether [route] keeps within 30 m of [line] everywhere (checked every 50 m), ends aside. */
    fun follows(route: List<LatLng>, line: List<LatLng>, cum: DoubleArray): Boolean {
        if (route.size < 2) return false
        val rc = Geo.cumulative(route)
        val total = rc.last()
        var m = 60.0
        while (m < total - 60) {
            if (Geo.project(Geo.point(route, m, rc), line, cum).offset > 30) return false
            m += 50
        }
        return true
    }

    /** Google's exit count for each roundabout, where the open router has none or a different one. */
    fun addGoogleExits(steps: MutableList<Maneuver>, google: List<Maneuver>) {
        for (i in steps.indices) {
            val s = steps[i]
            if (s.type != ManeuverType.ROUNDABOUT || s.leavesRoundabout) continue
            val g = google.filter { it.type == ManeuverType.ROUNDABOUT && it.exit != null && abs(it.alongMeters - s.alongMeters) < 120 }
                .minByOrNull { abs(it.alongMeters - s.alongMeters) } ?: continue
            val n = g.exit ?: continue
            if (n == s.exit) continue
            s.exit = n
            s.instruction = OSRM.instruction("roundabout", null, s.road, n, TravelMode.DRIVE)
            if (i + 1 < steps.size && steps[i + 1].leavesRoundabout) steps[i + 1].exit = n
        }
    }

    /** Google's own lane advice ("Use the right lane to merge onto Redbridge Rd") where the open map has no lanes. */
    fun addGoogleLaneHints(steps: MutableList<Maneuver>, google: List<Maneuver>) {
        // Each of Google's hints goes to one step only, and only when they're each other's nearest:
        // a left turn 80 m before a right one mustn't take the right turn's lanes.
        val open = steps.filter { !it.silent && it.lanes.isEmpty() && it.laneHint == null }
        val hints = google.filter { it.laneHint != null }
        for (g in hints) {
            val s = open.minByOrNull { abs(it.alongMeters - g.alongMeters) } ?: continue
            if (abs(s.alongMeters - g.alongMeters) >= 150) continue
            val back = hints.minByOrNull { abs(it.alongMeters - s.alongMeters) } ?: continue
            if (back.alongMeters == g.alongMeters) s.laneHint = g.laneHint
        }
    }
}

/** Google words its steps the American way even in Britain: "Take the ramp" is a slip road there. */
object British {
    fun words(text: String): String = text
        .replace(Regex("\\b(the|a) ramp\\b"), "$1 slip road")
        .replace(Regex("\\bramp\\b"), "slip road")
        .replace(Regex("\\bparking lot\\b"), "car park")
        .replace(Regex("\\bparking garage\\b"), "multi-storey car park")
        .replace(Regex("\\bfreeway\\b"), "motorway")
}
