package app.kite.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turn-by-turn that doesn't leave anything out (the iPhone's StepFill.swift). Kite's steps come
 * from the open router run along Google's line, and only when the two run on the very same roads
 * all the way; on a November 2026 check of 40 tricky British drives that held for only half of
 * them, and the rest fell back on Google's plainer steps. Even when it held, the open router left
 * out turns Google gives ("Keep right to stay on A38(M)" at Spaghetti Junction, the second of the
 * Six Ways roundabouts in Erdington).
 */
private val turnKinds = setOf(
    ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT,
    ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT, ManeuverType.UTURN, ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT,
    ManeuverType.FORK_LEFT, ManeuverType.FORK_RIGHT, ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.ROUNDABOUT,
)
private val leftKinds = setOf(ManeuverType.TURN_LEFT, ManeuverType.SHARP_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.FORK_LEFT, ManeuverType.RAMP_LEFT)
private val rightKinds = setOf(ManeuverType.TURN_RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.RAMP_RIGHT)

/** -1 for a step to the left, 1 to the right, 0 for neither. */
internal fun side(t: ManeuverType): Int = if (t in leftKinds) -1 else if (t in rightKinds) 1 else 0

/** The same kind of step: both roundabouts, both merges, or both off to the same side. */
internal fun alike(a: Maneuver, b: Maneuver): Boolean {
    if (a.type == ManeuverType.ROUNDABOUT || b.type == ManeuverType.ROUNDABOUT || a.type == ManeuverType.MERGE || b.type == ManeuverType.MERGE) return a.type == b.type
    return side(a.type) != 0 && side(a.type) == side(b.type)
}

private fun isEnd(m: Maneuver) = m.type == ManeuverType.DEPART || m.type == ManeuverType.ARRIVE

/**
 * When no open-router route runs on Google's roads all the way: its steps wherever it does, and
 * Google's own steps on the stretches where it went its own way (150 m either side of them).
 * Null when no route follows at least half the line.
 */
fun StepMerge.adoptPiecewise(candidates: List<Route>, google: List<Maneuver>, line: List<LatLng>): MutableList<Maneuver>? {
    if (line.size < 2 || google.isEmpty()) return null
    val cum = Geo.cumulative(line)
    val total = cum.last()
    if (total <= 0) return null
    var best: MutableList<Maneuver>? = null
    var bestCover = 0.0
    for (o in candidates) {
        if (o.polyline.size < 2) continue
        val oc = Geo.cumulative(o.polyline)
        val bad = mutableListOf<ClosedFloatingPointRange<Double>>()
        var m = 0.0
        while (m <= total) {
            if (Geo.project(Geo.point(line, m, cum), o.polyline, oc).offset > 30) {
                val r = max(0.0, m - 150)..min(total, m + 150)
                val last = bad.lastOrNull()
                if (last != null && last.endInclusive >= r.start) bad[bad.size - 1] = last.start..r.endInclusive else bad.add(r)
            }
            m += 25
        }
        val cover = 1 - bad.sumOf { it.endInclusive - it.start } / total
        if (cover < 0.5 || cover <= bestCover) continue
        fun onBad(a: Double) = bad.any { a in it }
        val placed = mutableListOf<Maneuver>()
        var last = 0.0
        for (s in o.maneuvers) {
            if (isEnd(s)) continue
            val p = Geo.project(s.location, line, cum)
            if (!(p.offset < 40 && p.along >= last - 30 && p.along < total - 5) || onBad(p.along)) continue
            // The open route around the step runs on the line too, not just the step's own point.
            val oa = Geo.project(s.location, o.polyline, oc).along
            var d = max(0.0, oa - 150)
            var near = true
            while (d <= min(oc.last(), oa + 150)) {
                if (Geo.project(Geo.point(o.polyline, d, oc), line, cum).offset > 30) { near = false; break }
                d += 25
            }
            if (!near) continue
            last = max(last, p.along)
            placed.add(s.copy(alongMeters = p.along, location = Geo.point(line, p.along, cum)))
        }
        for (g in google.drop(1)) {
            if (isEnd(g) || !onBad(g.alongMeters)) continue
            // Not where the open router already has the same step just outside the stretch.
            if (placed.any { alike(it, g) && abs(it.alongMeters - g.alongMeters) < 80 }) continue
            placed.add(g.copy())
        }
        placed.sortBy { it.alongMeters }
        // Google's first step is its "Head south-west", whatever kind it's marked.
        val depart = if (onBad(0.0)) google.first().copy(type = ManeuverType.DEPART) else o.maneuvers.firstOrNull { it.type == ManeuverType.DEPART }?.copy()
        val arrive = if (onBad(total)) google.lastOrNull { it.type == ManeuverType.ARRIVE }?.copy() else o.maneuvers.lastOrNull { it.type == ManeuverType.ARRIVE }?.copy()
        best = finishSteps(placed, depart, arrive, line)
        bestCover = cover
    }
    return best
}

/** [placed] between a start and an end on [line], each step as long as the way to the next. */
internal fun finishSteps(placed: List<Maneuver>, depart: Maneuver?, arrive: Maneuver?, line: List<LatLng>): MutableList<Maneuver> {
    val total = Geo.length(line)
    val d = (depart ?: Maneuver(ManeuverType.DEPART, "Head out", line[0], 0.0, 0.0)).apply { alongMeters = 0.0; location = line[0] }
    val a = (arrive ?: Maneuver(ManeuverType.ARRIVE, "You have arrived", line.last(), total, 0.0)).apply { alongMeters = total; location = line.last() }
    val all = (listOf(d) + placed + listOf(a)).toMutableList()
    respace(all)
    return all
}

internal fun respace(all: MutableList<Maneuver>) {
    if (all.isEmpty()) return
    for (i in 0 until all.size - 1) all[i].distanceMeters = max(0.0, all[i + 1].alongMeters - all[i].alongMeters)
    all.last().distanceMeters = 0.0
}

/**
 * Google's turns that the open router has nothing for, each lined up first by the nearest turn
 * both of them have (Google counts its steps back from the end, which can drift by tens of
 * metres). A quiet "the road's name changes" step right there takes the turn instead. Merges too
 * ("Merge onto M8"), except one into a lane that carries on to the next exit, which the router's
 * steps leave out on purpose.
 */
fun StepMerge.addGoogleMissing(steps: MutableList<Maneuver>, google: List<Maneuver>, line: List<LatLng>) {
    val total = Geo.length(line)
    val cum = Geo.cumulative(line)
    val laneOn = setOf(ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.FORK_LEFT, ManeuverType.FORK_RIGHT, ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT)
    val wanted = google.filter { g ->
        if (g.type == ManeuverType.MERGE) {
            val next = steps.firstOrNull { !it.silent && it.alongMeters > g.alongMeters + 70 && it.type != ManeuverType.DEPART }
            !(next != null && next.type in laneOn && next.alongMeters - g.alongMeters < 1_000)
        } else g.type in turnKinds
    }
    if (wanted.isEmpty()) return
    fun spoken() = steps.filter { !it.silent && !isEnd(it) }
    val pairs = mutableListOf<Pair<Double, Double>>()
    for (g in wanted) {
        // Each other's nearest only: the second of two roundabouts mustn't line up on the first.
        val k = spoken().filter { alike(it, g) && !it.leavesRoundabout && abs(it.alongMeters - g.alongMeters) < 150 }
            .minByOrNull { abs(it.alongMeters - g.alongMeters) } ?: continue
        val back = wanted.filter { alike(k, it) }.minByOrNull { abs(it.alongMeters - k.alongMeters) } ?: continue
        if (back.alongMeters == g.alongMeters) pairs.add(g.alongMeters to k.alongMeters)
    }
    val pairedKite = pairs.map { it.second }.toSet()
    var changed = false
    for (g in wanted) {
        if (pairs.any { it.first == g.alongMeters }) continue
        val ref = pairs.firstOrNull { it.first >= g.alongMeters } ?: pairs.lastOrNull()
        val at = min(max(1.0, g.alongMeters + (ref?.let { it.second - it.first } ?: 0.0)), total - 1)
        // The same kind of step a little further off is that step, drifted; roundabouts come in
        // pairs a hundred metres apart (Six Ways, Erdington), so only the near one counts for them.
        if (spoken().any { abs(it.alongMeters - at) < 70 || (g.type != ManeuverType.ROUNDABOUT && alike(it, g) && it.alongMeters !in pairedKite &&
                abs(it.alongMeters - at) < (if (g.type == ManeuverType.MERGE) 300 else 150)) }) continue
        val quiet = steps.indexOfFirst { it.silent && abs(it.alongMeters - at) < 40 }
        if (quiet >= 0) {
            val q = steps[quiet]
            steps[quiet] = g.copy(alongMeters = q.alongMeters, location = q.location, silent = false, road = g.road ?: q.road)
        } else {
            // The road carries on as it was: the step before's road, unless Google names a new one.
            val road = g.road ?: steps.lastOrNull { it.alongMeters <= at }?.road
            val k = steps.indexOfFirst { it.alongMeters > at }.let { if (it < 0) steps.size else it }
            steps.add(k, g.copy(alongMeters = at, location = Geo.point(line, at, cum), silent = false, road = road))
        }
        changed = true
    }
    if (changed) respace(steps)
}

/**
 * Where Google and the open router call the same spot differently, Google's kind of step: a
 * roundabout Google knows where the router only says "Turn right", and a real turn where the
 * router says "Continue straight onto Gloucester Road". The router's road name is kept.
 */
fun StepMerge.addGoogleKinds(steps: MutableList<Maneuver>, google: List<Maneuver>) {
    val plainTurns = setOf(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT,
        ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT)
    for (i in steps.indices) {
        val s = steps[i]
        if (s.silent || isEnd(s) || s.type == ManeuverType.ROUNDABOUT) continue
        val g = google.filter { abs(it.alongMeters - s.alongMeters) < 50 && it.type in turnKinds }.minByOrNull { abs(it.alongMeters - s.alongMeters) } ?: continue
        // Another of the router's steps is nearer that one of Google's: it belongs to that step.
        if (steps.indices.any { it != i && !steps[it].silent && abs(steps[it].alongMeters - g.alongMeters) < abs(s.alongMeters - g.alongMeters) }) continue
        val n = g.exit
        if (g.type == ManeuverType.ROUNDABOUT && n != null && (s.type in plainTurns || s.type == ManeuverType.STRAIGHT)) {
            s.type = ManeuverType.ROUNDABOUT
            s.exit = n
            s.lanes = emptyList()
            s.instruction = OSRM.instruction("roundabout", null, s.road, n, TravelMode.DRIVE)
        } else if (s.type == ManeuverType.STRAIGHT && g.type in plainTurns) {
            s.type = g.type
            val verb = DirectionsParser.phrase(g.type).replace("Slight", "Turn slight").replace("Sharp", "Turn sharp")
            s.instruction = verb + (s.road?.let { " onto $it" } ?: "")
        }
    }
}

/**
 * What the signs say, from Google, where the open router's slip road or fork has nothing to go
 * by: the junction number ("Take exit 4 toward The Midlands and London"), or Google's own words
 * ("Take the exit towards Emsworth") where the router has no destination either.
 */
fun StepMerge.addGoogleSigns(steps: MutableList<Maneuver>, google: List<Maneuver>) {
    val kinds = setOf(ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.FORK_LEFT, ManeuverType.FORK_RIGHT, ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT)
    val numbered = Regex("exit \\d|junction \\d")
    val junction = Regex("junction \\d+[a-z]?")
    for (s in steps) {
        if (s.type !in kinds || s.silent) continue
        val w = s.instruction.lowercase()
        if (numbered.containsMatchIn(w)) continue
        val g = google.filter { it.type in kinds && side(it.type) == side(s.type) && abs(it.alongMeters - s.alongMeters) < 150 }
            .minByOrNull { abs(it.alongMeters - s.alongMeters) } ?: continue
        val gw = g.instruction.lowercase()
        val j = junction.find(gw)
        if (j != null && w.startsWith("take the exit")) {
            s.instruction = "Take exit ${j.value.removePrefix("junction ").uppercase()}" + s.instruction.drop("Take the exit".length)
            continue
        }
        if (w.contains(" toward") || w.contains(" onto ")) continue
        if (!(gw.contains("toward") || junction.containsMatchIn(gw) || gw.contains(" onto "))) continue
        s.instruction = g.instruction
        if (s.laneHint == null && s.lanes.isEmpty()) s.laneHint = g.laneHint
    }
}

/**
 * Steps that say the same thing twice, or nothing: "Turn right" then "Continue straight onto Wide
 * Ln" 11 m later is one turn; the same turn twice 8 m apart is one turn; "Continue onto" the road
 * you're already on says nothing; a turn that keeps you on the same road is "to stay on" it.
 */
fun StepMerge.tidy(steps: MutableList<Maneuver>) {
    var prev: Int? = null
    var road: String? = null
    for (i in steps.indices) {
        val s = steps[i]
        run step@{
            if (s.silent || isEnd(s)) return@step
            val w = s.instruction
            val carryOn = s.type == ManeuverType.STRAIGHT && (w.startsWith("Continue") || w.startsWith("Stay on"))
            val p = prev
            if (p != null && steps[p].type != ManeuverType.DEPART && s.alongMeters - steps[p].alongMeters < 35) {
                val a = steps[p]
                if (carryOn && s.lanes.isEmpty()) {
                    s.silent = true
                    val r = s.road
                    if (r != null && !a.instruction.contains(" onto ") && !a.instruction.contains(" on ") && !a.instruction.contains(" toward") &&
                        !(a.type == ManeuverType.ROUNDABOUT && !a.leavesRoundabout)) {
                        a.instruction = a.instruction + " onto $r"
                        a.road = r
                    }
                    return@step
                }
                if (a.type != ManeuverType.ROUNDABOUT && s.type != ManeuverType.ROUNDABOUT && s.alongMeters - a.alongMeters < 20 &&
                    side(a.type) != 0 && side(a.type) == side(s.type)) {
                    // One turn told twice: the second, with the first's lanes.
                    if (s.lanes.isEmpty()) s.lanes = a.lanes
                    if (s.laneHint == null) s.laneHint = a.laneHint
                    a.silent = true
                    return@step
                }
            }
            val r = road ?: return@step
            if (s.road != r || s.type == ManeuverType.ROUNDABOUT) return@step
            if (carryOn && (w == "Continue onto $r" || w == "Continue straight onto $r") && s.lanes.isEmpty() && s.laneHint == null) {
                s.silent = true
            } else if (side(s.type) != 0 && w.endsWith(" onto $r") && (w.startsWith("Turn") || w.startsWith("Keep") || w.startsWith("Slight"))) {
                s.instruction = w.dropLast(" onto $r".length) + " to stay on $r"
            }
        }
        if (!s.silent) prev = i
        // A start's road is only where it heads ("Exit the car park towards Elmbank Cres"), not the road it's on.
        if (s.type != ManeuverType.DEPART) s.road?.let { road = it }
    }
}
