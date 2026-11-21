package app.kite.probe

import app.kite.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Live checks of Kite's turn-by-turn against the real servers, run by the build workflow:
 * `./gradlew :core:probe --args="turns"` (also uk, audit, merges, ramps). The same engine code
 * both phones use, so what's flagged here is what a driver would see.
 */
fun main(args: Array<String>) {
    runBlocking { run(args) }
    // OkHttp's threads would keep the program alive otherwise.
    kotlin.system.exitProcess(0)
}

private suspend fun run(args: Array<String>) {
    when (args.firstOrNull()) {
        "uk" -> ukRoutes()
        "audit" -> navAudit()
        "turns" -> turnAudit(args.getOrNull(1)?.toIntOrNull())
        "merges" -> mergeCheck()
        "ramps" -> rampAudit(args.getOrNull(1)?.toIntOrNull())
        "live" -> liveCheck()
        else -> println("Usage: probe uk | audit | turns [n] | merges | ramps [n] | live   (TURNSET=2 for the second junction set)")
    }
}

private fun f0(d: Double) = d.toInt().toString()
private fun signed(d: Double) = (if (d >= 0) "+" else "") + d.toInt()
private fun ll(p: LatLng) = "%.5f,%.5f".format(p.lat, p.lng)
private fun lanesText(l: List<Lane>) = l.joinToString("|") { (if (it.valid) "*" else "") + it.indications.joinToString("/") }

private val LEFT = setOf(ManeuverType.TURN_LEFT, ManeuverType.SHARP_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.FORK_LEFT, ManeuverType.RAMP_LEFT)
private val RIGHT = setOf(ManeuverType.TURN_RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.RAMP_RIGHT)
private fun side(t: ManeuverType) = when (t) { in LEFT -> -1; in RIGHT -> 1; else -> 0 }

/** Headings and bends along a route line. */
private class Line(val line: List<LatLng>) {
    val cum = Geo.cumulative(line)
    val total = cum.lastOrNull() ?: 0.0
    fun heading(a: Double, b: Double): Double? {
        val p = Geo.point(line, max(0.0, a), cum); val q = Geo.point(line, min(total, b), cum)
        return if (p.distanceTo(q) > 3) Geo.bearing(p, q) else null
    }
    fun turn(at: Double, before: Double = 40.0, after: Double = 60.0): Double? {
        val a = heading(at - before, at - 3) ?: return null
        val b = heading(at + 5, at + after) ?: return null
        return ((b - a + 540) % 360) - 180
    }
}

/** The banner's lane logic (LaneGuide in the app), mirrored so the probe sees the same lanes. */
private object LaneWords {
    fun lanes(m: Maneuver): Pair<List<Lane>, String>? {
        if (m.lanes.size > 1) return named(m.lanes)
        val hint = m.laneHint
        if (hint != null && !hint.contains("any lane", ignoreCase = true) && m.type != ManeuverType.ROUNDABOUT) return emptyList<Lane>() to hint
        if (m.type != ManeuverType.ROUNDABOUT || m.leavesRoundabout) return null
        val lanes = m.roundabout?.ruleLanes(Geo.drivesOnLeft(m.location), m.laneHint) ?: return null
        return named(lanes)
    }
    fun named(lanes: List<Lane>): Pair<List<Lane>, String>? {
        val valid = lanes.indices.filter { lanes[it].valid }
        val first = valid.firstOrNull() ?: return null
        val last = valid.last()
        if (valid.size >= lanes.size) return null
        val n = lanes.size; val count = valid.size
        val many = if (count > 1) "$count lanes" else "lane"
        return lanes to when {
            first == 0 -> "Left $many"
            last == n - 1 -> "Right $many"
            count == 1 && n > 3 -> "Lane ${first + 1} from left"
            else -> if (count > 1) "Middle lanes" else "Middle lane"
        }
    }
}

private fun row(m: Maneuver): String {
    val extra = mutableListOf<String>()
    m.exit?.let { extra += "exit $it" }
    m.laneHint?.let { extra += "hint '$it'" }
    if (m.silent) extra += "silent"
    if (m.lanes.isNotEmpty()) extra += "lanes " + lanesText(m.lanes)
    return "  %6.0f m  %-12s %s  %s".format(m.alongMeters, m.type.name, m.instruction, if (extra.isEmpty()) "" else "[" + extra.joinToString(", ") + "]")
}

/** `probe uk`: real British drives through the app's own route code, every step printed, and a reroute check. */
suspend fun ukRoutes() {
    val drives = listOf(
        Triple("M271 into Redbridge roundabout, 1st exit A33", LatLng(50.9890, -1.4970), LatLng(50.9077, -1.4136)),
        Triple("Millbrook up the A33 past Redbridge to Romsey (merge then exit)", LatLng(50.9180, -1.4550), LatLng(50.9890, -1.4970)),
        Triple("Park Gate to Portsmouth Uni", LatLng(50.8705, -1.2690), LatLng(50.7953, -1.0938)),
        Triple("Park Gate to Southampton Uni", LatLng(50.8705, -1.2690), LatLng(50.9346, -1.3966)),
        Triple("Fareham to Winchester", LatLng(50.8527, -1.1773), LatLng(51.0632, -1.3080)),
        Triple("Central London: Kings Cross to Waterloo", LatLng(51.5308, -0.1238), LatLng(51.5031, -0.1132)),
        Triple("Cardiff centre to Penarth", LatLng(51.4816, -3.1791), LatLng(51.4386, -3.1737)),
        Triple("Glasgow to Edinburgh", LatLng(55.8609, -4.2514), LatLng(55.9533, -3.1883)),
    )
    for ((name, a, b) in drives) {
        val r = Directions.routes(a, b, TravelMode.DRIVE)
        val route = r.routes.firstOrNull()
        if (route == null) { println("=== $name: NO ROUTE (${r.error ?: "-"})"); continue }
        println("=== $name: ${f0(route.distanceMeters)} m, ${(route.durationSeconds / 60).toInt()} min, ${route.maneuvers.size} steps, via ${route.summary ?: "-"}")
        for (m in route.maneuvers) {
            val extra = mutableListOf<String>()
            m.exit?.let { extra += "exit $it" }
            if (m.leavesRoundabout) extra += "leaves"
            m.road?.let { extra += "road '$it'" }
            if (m.lanes.isNotEmpty()) extra += "lanes " + lanesText(m.lanes)
            println("  %6.0f m  %-12s %s  [%s]".format(m.alongMeters, m.type.name, m.instruction, extra.joinToString(", ")))
        }
        delay(1500)
    }
    // A reroute while driving north up The Avenue (A33) in Southampton, to somewhere behind the car:
    // the new route must carry on north and turn round properly, never start with "head south".
    val here = LatLng(50.9260, -1.4035); val behind = LatLng(50.9050, -1.4045)
    for (heading in listOf(0.0, 180.0)) {
        val route = Directions.routes(here, behind, TravelMode.DRIVE, heading = heading).routes.firstOrNull()
        if (route == null) { println("=== Reroute heading ${heading.toInt()}: NO ROUTE"); continue }
        val bad = Directions.startsBehind(route.polyline, heading)
        println("=== Reroute heading ${heading.toInt()}: ${f0(route.distanceMeters)} m, source ${route.source}" +
            if (bad) "  FAIL: starts back the way the car came" else "  OK: sets off the way the car faces")
        route.maneuvers.take(4).forEach { println("    ${it.instruction}") }
        delay(1500)
    }
}

/** `probe audit`: Google's own steps, the open router's along Google's line, and what Kite says, on Matteo's drives. */
suspend fun navAudit() {
    val drives = listOf(
        Triple("M271 south into Redbridge, A33 east", LatLng(50.9890, -1.4970), LatLng(50.9077, -1.4136)),
        Triple("Millbrook up the A33 past Redbridge to Romsey", LatLng(50.9180, -1.4550), LatLng(50.9890, -1.4970)),
        Triple("Totton to Southampton Uni", LatLng(50.9190, -1.4900), LatLng(50.9346, -1.3966)),
        Triple("Park Gate to Southampton Uni", LatLng(50.8705, -1.2690), LatLng(50.9346, -1.3966)),
    )
    for ((name, a, b) in drives) {
        println("##### $name")
        val g = runCatching { GoogleClient.shared.directions(a, b, TravelMode.DRIVE) }.getOrNull()?.firstOrNull()
        if (g != null) {
            println("--- Google's own steps (${f0(g.distanceMeters)} m, complete ${g.stepsComplete}):")
            g.maneuvers.forEach { println(row(it)) }
            delay(1000)
            val mt = runCatching { OSRM.match(g.polyline, TravelMode.DRIVE) }
            when {
                mt.isFailure -> println("--- Open router match FAILED: ${mt.exceptionOrNull()}")
                mt.getOrNull() == null -> println("--- Open router matched to Google's line: NO SINGLE MATCH")
                else -> { println("--- Open router matched to Google's line (${f0(mt.getOrNull()!!.distanceMeters)} m):"); mt.getOrNull()!!.maneuvers.forEach { println(row(it)) } }
            }
            delay(1000)
            val v = runCatching { OSRM.routeVia(g.polyline, TravelMode.DRIVE) }.getOrNull()
            if (v != null) { println("--- Open router forced along Google's line (${f0(v.distanceMeters)} m):"); v.maneuvers.forEach { println(row(it)) } }
            else println("--- Open router along Google's line: FAILED")
        } else println("--- Google: no route")
        delay(1000)
        runCatching { OSRM.route(a, b, TravelMode.DRIVE).firstOrNull() }.getOrNull()?.let { o ->
            println("--- Open router on its own (${f0(o.distanceMeters)} m):"); o.maneuvers.forEach { println(row(it)) }
        }
        delay(1000)
        Directions.routes(a, b, TravelMode.DRIVE).routes.firstOrNull()?.let { f ->
            println("--- What Kite says (source ${f.source}):"); f.maneuvers.forEach { println(row(it)) }
        }
        delay(1500)
    }
}

/**
 * `probe turns [n]`: every step Kite would show and say on short drives through tricky British
 * junctions, checked against the route line itself: an arrow that points the other way from the
 * road, lanes that don't go the way of the turn, a roundabout whose picture counts a different exit
 * from the words, two turns too close together to hear apart, and a sharp bend with no step at all.
 */
suspend fun turnAudit(only: Int?) {
    val drives = if (System.getenv("TURNSET") == "2") Junctions.set2 else Junctions.set1
    val flags = sortedMapOf<String, Int>()
    var steps = 0
    for ((n, d) in drives.withIndex()) {
        if (only != null && only != n) continue
        val (name, from, to) = d
        val google = runCatching { GoogleClient.shared.directions(from, to, TravelMode.DRIVE) }.getOrNull()?.firstOrNull()
        val r = Directions.routes(from, to, TravelMode.DRIVE)
        var x = r.routes.firstOrNull()
        if (x == null) { println("##### $n: $name\nFLAG no route: ${r.error ?: ""}"); continue }
        // What the app does once driving: mini-roundabouts added, then each roundabout's real layout.
        if (System.getenv("NO_OSM") == null) {
            Roundabouts.addMinis(x)?.let { x!!.maneuvers = it.maneuvers }
            Roundabouts.apply(x)?.let { x = it }
        }
        val route = x!!
        println("##### %d: %s  (%.1f km, %s, %d steps)".format(n, name, route.distanceMeters / 1000, route.source, route.maneuvers.size))
        val L = Line(route.polyline)
        fun flag(kind: String, text: String) { flags[kind] = (flags[kind] ?: 0) + 1; println("    FLAG [$kind] $text") }
        val spoken = route.maneuvers.filter { !it.silent }
        for ((k, m) in spoken.withIndex()) {
            steps++
            val lane = LaneWords.lanes(m)
            val extra = mutableListOf<String>()
            m.exit?.let { extra += "exit $it" }
            m.roundabout?.let { s -> extra += "pic ${s.taken + 1}/${s.exits.size}${if (s.mini) " mini" else ""}${if (s.complete) "" else " sketch"}" }
            lane?.let { extra += "lanes " + lanesText(it.first) + " '${it.second}'" }
            m.laneHint?.let { extra += "hint '$it'" }
            val t = L.turn(m.alongMeters)
            println("  %6.0f m  %-12s %s  turn=%s  %s  %s".format(m.alongMeters, m.type.name, m.instruction, t?.let(::signed) ?: "?", extra.joinToString(", "), ll(m.location)))
            // Where the road really bends near the step: a step placed off the junction is said at the wrong moment.
            if (m.type !in setOf(ManeuverType.DEPART, ManeuverType.ARRIVE, ManeuverType.ROUNDABOUT)) {
                var best: Pair<Double, Double>? = null
                val prevAt = if (k > 0) spoken[k - 1].alongMeters else -1e9
                val nextAt = if (k + 1 < spoken.size) spoken[k + 1].alongMeters else 1e9
                val lo = -min(80.0, (m.alongMeters - prevAt) / 2); val hi = min(80.0, (nextAt - m.alongMeters) / 2)
                var o = lo
                while (o <= hi) {
                    val tt = L.turn(m.alongMeters + o, 25.0, 25.0)
                    if (tt != null && abs(tt) > abs(best?.second ?: 0.0)) best = o to tt
                    o += 5
                }
                best?.let { (at, bt) -> if (abs(bt) > 45 && abs(at) > 20) flag("placement", "road bends ${signed(bt)} at ${signed(at)} m from the step") }
            }
            // Arrow against the road.
            if (t != null && m.type != ManeuverType.ROUNDABOUT && m.type != ManeuverType.UTURN) {
                if (m.type in setOf(ManeuverType.TURN_LEFT, ManeuverType.SHARP_LEFT) && t > -25) flag("arrow", "left turn but the line goes ${t.toInt()}")
                if (m.type in setOf(ManeuverType.TURN_RIGHT, ManeuverType.SHARP_RIGHT) && t < 25) flag("arrow", "right turn but the line goes ${t.toInt()}")
                if (m.type in LEFT && t > 25) flag("arrow", "left arrow, line turns right ${t.toInt()}")
                if (m.type in RIGHT && t < -25) flag("arrow", "right arrow, line turns left ${t.toInt()}")
                if (m.type == ManeuverType.STRAIGHT && abs(t) > 50) flag("arrow", "straight arrow but the line turns ${t.toInt()}")
                if (m.type in setOf(ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT) && abs(t) > 75) flag("arrow", "slight arrow for a full turn ${t.toInt()}")
                if (m.type in setOf(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT) && abs(t) < 35 && !m.instruction.contains("roundabout")) flag("arrow", "full turn arrow for a bend of ${t.toInt()}")
            }
            // Words against the arrow.
            val w = m.instruction.lowercase()
            if (w.contains("left") && !w.contains("right") && m.type in RIGHT) flag("words", "says left, arrow right")
            if (w.contains("right") && !w.contains("left") && m.type in LEFT) flag("words", "says right, arrow left")
            val road = m.road
            val prevRoad = spoken.subList(0, k).lastOrNull { it.road != null }?.road
            if (road != null && k > 0 && road == prevRoad && w.contains("onto ${road.lowercase()}") && m.type != ManeuverType.ROUNDABOUT) flag("words", "onto the road it's already on")
            if (Regex("""\b(\w+) \1\b""").containsMatchIn(w)) flag("words", "repeated word")
            // Lanes against the turn.
            if (lane != null && m.type != ManeuverType.ROUNDABOUT) {
                val l = lane.first
                val goesLeft = m.type in LEFT; val goesRight = m.type in RIGHT
                val valid = l.withIndex().filter { it.value.valid }
                if (goesLeft && valid.firstOrNull()?.index == l.size - 1 && l.size > 1 && valid.none { v -> v.value.indications.any { it.contains("left") } }) flag("lanes", "left turn from the right lane only")
                if (goesRight && valid.lastOrNull()?.index == 0 && l.size > 1 && valid.none { v -> v.value.indications.any { it.contains("right") } }) flag("lanes", "right turn from the left lane only")
                if (valid.any { v -> v.value.indications.all { i -> (goesLeft && i.contains("right")) || (goesRight && i.contains("left")) } }) flag("lanes", "a picked lane only points the other way")
            }
            // Roundabouts: picture, words and lanes.
            if (m.type == ManeuverType.ROUNDABOUT && !m.leavesRoundabout) {
                val s = m.roundabout; val e = m.exit
                if (s != null && e != null && s.taken + 1 != e) flag("roundabout", "says exit $e, picture shows ${s.taken + 1}")
                if (e == null && !w.contains("straight") && !w.contains("turn")) flag("roundabout", "no exit number")
                if (s != null && s.taken in s.exits.indices) {
                    val tt = s.turn(s.taken)
                    if (e == 1 && tt > 200) flag("roundabout", "1st exit drawn going right round (${tt.toInt()})")
                    if (e != null && e >= 3 && tt < 60) flag("roundabout", "exit $e drawn as a sharp left (${tt.toInt()})")
                    if (s.mini && lane != null) flag("roundabout", "lane advice at a mini-roundabout")
                    val out = Roundabouts.exitAngle(L.line, L.cum, m.alongMeters, Roundabouts.wayOff(route.maneuvers, route.maneuvers.indexOf(m).coerceAtLeast(0)))
                    if (out != null) {
                        val drawn = s.exits[s.taken]
                        if (Roundabouts.angleGap(drawn, out) > 60) flag("roundabout", "picture's exit at ${drawn.toInt()}, road leaves at ${out.toInt()}")
                    }
                }
            }
            // Two turns too close to say apart.
            if (k + 1 < spoken.size) {
                val nx = spoken[k + 1]
                val gap = nx.alongMeters - m.alongMeters
                if (gap < 60 && nx.type != ManeuverType.ARRIVE && !nx.leavesRoundabout && m.type != ManeuverType.DEPART)
                    flag("close", "next step '${nx.instruction}' only ${f0(gap)} m on")
            }
        }
        // Google's own steps on the same line that Kite has nothing for, and Google's words where Kite's arrow disagrees.
        if (google != null && google.stepsComplete && abs(google.distanceMeters - route.distanceMeters) < 50) {
            for (gm in google.maneuvers) {
                if (gm.type == ManeuverType.DEPART || gm.type == ManeuverType.ARRIVE) continue
                if (spoken.none { abs(it.alongMeters - gm.alongMeters) < 70 }) flag("google only", "${f0(gm.alongMeters)} m '${gm.instruction}' (${gm.type})")
            }
            for (m in spoken) {
                if (m.type in setOf(ManeuverType.DEPART, ManeuverType.ARRIVE, ManeuverType.ROUNDABOUT)) continue
                val gm = google.maneuvers.filter { abs(it.alongMeters - m.alongMeters) < 50 }.minByOrNull { abs(it.alongMeters - m.alongMeters) } ?: continue
                if (side(gm.type) != 0 && side(m.type) != 0 && side(gm.type) != side(m.type)) flag("vs google", "Kite '${m.instruction}' (${m.type}), Google '${gm.instruction}' (${gm.type})")
                if (side(gm.type) != 0 && side(m.type) == 0 && m.type != ManeuverType.MERGE) flag("vs google", "Kite '${m.instruction}' (${m.type}), Google '${gm.instruction}' (${gm.type})")
            }
        }
        // A sharp change of way with no step near it (not on a roundabout's ring).
        val rbs = route.maneuvers.filter { it.type == ManeuverType.ROUNDABOUT }
        var s = 60.0; var lastFlag = -1000.0
        while (s < L.total - 60) {
            val tt = L.turn(s, 30.0, 30.0)
            if (tt != null && abs(tt) > 55 && s - lastFlag > 150 &&
                route.maneuvers.none { abs(it.alongMeters - s) < 90 && (it.type != ManeuverType.DEPART || s < 150) } &&
                rbs.none { s > it.alongMeters - 40 && s < it.alongMeters + 250 }) {
                flag("silent turn", "line turns ${signed(tt)} at ${f0(s)} m with no step (${ll(Geo.point(L.line, s, L.cum))})")
                lastFlag = s
            }
            s += 10
        }
        delay(1500)
    }
    println("SUMMARY $steps steps; flags: " + flags.entries.joinToString(", ") { "${it.key} ${it.value}" })
}

/** `probe merges`: every merge step on the junction drives, and which ones the lane check makes quiet. */
suspend fun mergeCheck() {
    var said = 0; var quiet = 0
    val only = System.getenv("ONLY")
    for ((name, a, b) in Junctions.set1 + Junctions.set2) {
        if (only != null && !name.contains(only)) continue
        val x = Directions.routes(a, b, TravelMode.DRIVE).routes.firstOrNull() ?: continue
        val merges = x.maneuvers.filter { it.type == ManeuverType.MERGE && !it.silent }
        if (merges.isEmpty()) continue
        val gains = Merges.laneGains(x.maneuvers, x.polyline) ?: emptySet()
        for (m in merges) {
            val q = m.alongMeters in gains
            if (q) quiet++ else said++
            println("%s  %s  '%s'  %s".format(if (q) "QUIET" else "MERGE", name, m.instruction, ll(m.location)))
        }
    }
    println("merges said $said, lane gains made quiet $quiet")
}

/** `probe ramps [n]`: every slip road, merge, fork and keep step on motorway drives, against the way the line really goes. */
suspend fun rampAudit(only: Int?) {
    var flags = 0; var checked = 0
    for ((n, d) in Junctions.ramps.withIndex()) {
        if (only != null && only != n) continue
        val (name, from, to) = d
        println("##### $n: $name")
        val r = Directions.routes(from, to, TravelMode.DRIVE)
        var x = r.routes.firstOrNull()
        if (x == null) { println("FLAG no route: ${r.error ?: ""}"); continue }
        Roundabouts.apply(x)?.let { x = it }
        val route = x!!
        for (m in route.maneuvers) {
            if (m.type != ManeuverType.ROUNDABOUT || m.leavesRoundabout) continue
            val s = m.roundabout
            val rule = if (s == null) "no picture" else "in=${s.entryLanes ?: "?"}${s.entryArrows?.let { " arrows=" + it.joinToString("|") } ?: ""} rule=" +
                (s.ruleLanes(true, m.laneHint)?.joinToString("") { if (it.valid) "*" else "." } ?: "none")
            val mapped = if (m.lanes.isEmpty()) "" else " mapped=" + lanesText(m.lanes)
            println("  RB %6.0f m  \"%s\"  hint=%s  %s%s  %s".format(m.alongMeters, m.instruction, m.laneHint ?: "-", rule, mapped, ll(m.location)))
        }
        val L = Line(route.polyline)
        println("  %.1f km, source %s, %d steps".format(route.distanceMeters / 1000, route.source, route.maneuvers.size))
        val kinds = setOf(ManeuverType.MERGE, ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.FORK_LEFT, ManeuverType.FORK_RIGHT, ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT)
        for (m in route.maneuvers) {
            if (m.silent) continue
            val words = m.instruction.lowercase()
            if (m.type !in kinds && !words.contains("slip road") && !(words.contains("exit") && !words.contains("roundabout"))) continue
            checked++
            // Which way the line goes: the 60 m before against 30 to 150 m after.
            val a = L.heading(m.alongMeters - 60, m.alongMeters); val b = L.heading(m.alongMeters + 30, m.alongMeters + 150)
            val turn = if (a != null && b != null) ((b - a + 540) % 360) - 180 else null
            var note = ""
            if (m.type == ManeuverType.MERGE && !words.startsWith("merge")) note = "merge arrow for a step that isn't a merge"
            if (turn != null && m.type in setOf(ManeuverType.RAMP_LEFT, ManeuverType.FORK_LEFT, ManeuverType.KEEP_LEFT) && turn > 20) note = "left arrow but the route goes right"
            if (turn != null && m.type in setOf(ManeuverType.RAMP_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.KEEP_RIGHT) && turn < -20) note = "right arrow but the route goes left"
            if (note.isNotEmpty()) flags++
            println("%s %6.0f m  %-10s turn=%s  \"%s\"  %s%s".format(if (note.isEmpty()) "  ok  " else "FLAG", m.alongMeters, m.type.name,
                turn?.let(::signed) ?: "?", m.instruction, ll(m.location), if (note.isEmpty()) "" else "  <- $note"))
        }
        delay(1500)
    }
    println("SUMMARY $checked slip road, merge and fork steps, $flags flagged")
}

/** `probe live`: Google search and routes, and Waze's live reports, answer as Kite expects. */
suspend fun liveCheck() {
    val nyc = LatLng(40.7484, -73.9857)
    val r = runCatching { PlaceSearch.search("pizza", nyc, 2_000.0) }
    r.onFailure { println("SEARCH FAILED: $it") }
    r.getOrNull()?.let { res ->
        println("SEARCH pizza near the Empire State: ${res.places.size} places (Google error: ${res.googleError ?: "none"})")
        res.places.take(5).forEach { println("   ${it.name} · ${it.rating ?: "-"}★ · ${it.address ?: "-"} · ${it.source}") }
    }
    val g = runCatching { GoogleClient.shared.directions(LatLng(50.9097, -1.4044), LatLng(51.0632, -1.3080), TravelMode.DRIVE) }
    g.onFailure { println("GOOGLE ROUTE FAILED: $it") }
    g.getOrNull()?.firstOrNull()?.let { println("GOOGLE ROUTE Southampton to Winchester: ${f0(it.distanceMeters)} m, ${it.maneuvers.size} steps, traffic ${it.trafficSeconds != null}") }
    val places = listOf("Southampton" to LatLng(50.905, -1.34), "M27 J5-J7" to LatLng(50.92, -1.30), "Central London" to LatLng(51.515, -0.13))
    for ((name, p) in places) {
        val found = runCatching { WazeLive.shared.alerts(listOf(WazeLive.box(p, 6_000.0)), p) }.getOrNull()
        val s = WazeLive.lastStatus
        println("WAZE $name: " + if (found == null) "FAILED (code ${s?.code ?: 0})" else s?.summary ?: "nothing")
        delay(1000)
    }
}
