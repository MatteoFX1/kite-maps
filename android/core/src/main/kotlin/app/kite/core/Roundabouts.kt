package app.kite.core

import kotlin.math.*

/**
 * The real layout of the roundabouts on a route, from OpenStreetMap: the circle, every road that
 * meets it, which of those you can leave by, and the one the route takes.
 */
object Roundabouts {
    data class Way(val id: Long, val nodes: List<Long>, val points: List<LatLng>, val tags: Map<String, String>) {
        val isRing: Boolean get() = (tags["junction"] ?: "") in setOf("roundabout", "circular")
    }

    /** A road meeting the circle: where (compass bearing from the middle), and which ways traffic can go. */
    data class Arm(val angle: Double, val canExit: Boolean, val canEnter: Boolean, val name: String?)

    private fun norm(a: Double) = Geo.norm(a)
    fun angleGap(a: Double, b: Double) = Geo.angleGap(a, b)
    private fun signedGap(a: Double, b: Double) = Geo.signedGap(a, b)

    /** Fills in [Maneuver.roundabout] on every roundabout of [route] that OpenStreetMap answers for. */
    suspend fun apply(route: Route): Route? {
        val spots = route.maneuvers.filter { it.type == ManeuverType.ROUNDABOUT && !it.leavesRoundabout }.map { it.location }
        if (spots.isEmpty()) return null
        val data = fetch(spots) ?: return null
        val ways = parse(data)
        if (ways.isEmpty()) return null
        return applyWays(route, ways, Geo.drivesOnLeft(spots[0]))
    }

    fun applyWays(route: Route, ways: List<Way>, clockwise: Boolean): Route? {
        val r = route.deep()
        val cum = Geo.cumulative(route.polyline)
        var changed = false
        for (i in r.maneuvers.indices) {
            val m = r.maneuvers[i]
            if (m.type != ManeuverType.ROUNDABOUT) continue
            if (m.leavesRoundabout) {
                if (i > 0 && r.maneuvers[i - 1].type == ManeuverType.ROUNDABOUT) {
                    r.maneuvers[i - 1].roundabout?.let { prev ->
                        m.roundabout = prev
                        m.exit = m.exit ?: r.maneuvers[i - 1].exit
                    }
                }
                continue
            }
            val leave = leaving(ways, m.location, route.polyline, cum, m.alongMeters) ?: wayOff(r.maneuvers, i)
            val out = exitAngle(route.polyline, cum, m.alongMeters, leave)
            val s = shape(ways, m.location, route.polyline, cum, m.alongMeters, clockwise, m.exit)
            val sketch = m.roundabout
            if (s != null && (m.exit == null || s.taken + 1 == m.exit)) {
                m.roundabout = out?.let { aim(s, it) } ?: s
                changed = true
            } else if (out != null && sketch != null && sketch.taken in sketch.exits.indices && angleGap(sketch.exits[sketch.taken], out) > 1) {
                m.roundabout = if (angleGap(sketch.exits[sketch.taken], out) < 50 && (m.exit == null || sketch.taken + 1 == m.exit)) aim(sketch, out)
                else spread(out, m.exit ?: 1, clockwise)
                changed = true
            }
            if (m.roundabout != null) {
                entryLanes(ways, route.polyline, cum, m.alongMeters)?.let { e ->
                    m.roundabout = m.roundabout!!.copy(entryLanes = e.first, entryArrows = e.second)
                    changed = true
                }
            }
        }
        return if (changed) r else null
    }

    /** Makes every roundabout's picture point the way the route really leaves it. */
    /**
     * The lanes of the road into each roundabout, from the road matcher, where OpenStreetMap's own
     * answer hasn't given them (the Overpass servers were down for much of 12 and 13 November 2026,
     * and every roundabout fell back on a guess of 2 lanes). Null when nothing changes.
     */
    fun withEntryLanes(steps: List<Maneuver>, entries: List<RoundaboutEntry>): MutableList<Maneuver>? {
        var changed = false
        val out = steps.map { m ->
            val s = m.roundabout
            if (m.type != ManeuverType.ROUNDABOUT || m.leavesRoundabout || s == null || s.entryLanes != null || s.mini) return@map m
            val e = entries.minByOrNull { it.at.distanceTo(m.location) }
            if (e == null || e.at.distanceTo(m.location) >= 40) return@map m
            changed = true
            m.copy(roundabout = s.copy(entryLanes = e.lanes))
        }.toMutableList()
        return if (changed) out else null
    }

    fun settle(route: Route): Route {
        val r = route.deep()
        val cum = Geo.cumulative(route.polyline)
        for (i in r.maneuvers.indices) {
            val m = r.maneuvers[i]
            if (m.type != ManeuverType.ROUNDABOUT) continue
            if (m.leavesRoundabout) {
                if (i > 0 && r.maneuvers[i - 1].type == ManeuverType.ROUNDABOUT) m.roundabout = r.maneuvers[i - 1].roundabout
                continue
            }
            val out = exitAngle(route.polyline, cum, m.alongMeters, wayOff(r.maneuvers, i)) ?: continue
            val s = m.roundabout
            // A sketch that counts a different exit from the words is drawn again with the spoken count.
            m.roundabout = if (s != null && s.taken in s.exits.indices && angleGap(s.exits[s.taken], out) < 50 && (m.exit == null || s.taken + 1 == m.exit)) aim(s, out)
            else spread(out, m.exit ?: 1, Geo.drivesOnLeft(m.location))
        }
        return r
    }

    /** [shape] with your exit turned to point at [out]; the other roads move round with it in the same order. */
    fun aim(shape: RoundaboutShape, out: Double): RoundaboutShape {
        if (shape.taken !in shape.exits.indices) return shape
        val cw = shape.clockwise
        fun travel(a: Double): Double {
            val t = norm(if (cw) a - 180 else 180 - a)
            return if (t < 15) t + 360 else t
        }
        fun angle(t: Double) = norm(if (cw) 180 + t else 180 - t)
        val from = travel(shape.exits[shape.taken])
        if (from >= 345) return shape
        val to = min(max(travel(out), 10.0), 350.0)
        fun move(a: Double): Double {
            val t = travel(a)
            return angle(if (t <= from) t * to / from else to + (t - from) * (375 - to) / (375 - from))
        }
        return shape.copy(
            exits = shape.exits.indices.map { if (it == shape.taken) angle(to) else move(shape.exits[it]) },
            entriesOnly = shape.entriesOnly.map { move(it) },
        )
    }

    private fun ringAt(ways: List<Way>, entry: LatLng): Pair<List<Way>, Set<Long>>? {
        val rings = ways.filter { it.isRing }
        val first = rings.minByOrNull { distance(entry, it) } ?: return null
        if (distance(entry, first) >= 40) return null
        val ring = mutableListOf(first)
        val nodes = first.nodes.toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            for (w in rings) {
                if (ring.any { it.id == w.id } || w.nodes.none { it in nodes }) continue
                ring.add(w)
                nodes.addAll(w.nodes)
                grew = true
            }
        }
        return ring to nodes
    }

    /** Where [line] comes off the roundabout it enters at [along]: the first point more than 20 m from the circle. */
    fun leaving(ways: List<Way>, entry: LatLng, line: List<LatLng>, cum: DoubleArray, along: Double): Double? {
        val (ring, _) = ringAt(ways, entry) ?: return null
        val total = cum.last()
        var d = along + 10
        while (d < min(total, along + 2500)) {
            val p = Geo.point(line, d, cum)
            if (ring.all { distance(p, it) > 20 }) return max(along + 10, d - 15)
            d += 4
        }
        return null
    }

    /** Where the route comes off the roundabout entered at step [i]: the "exit roundabout" step after it. */
    fun wayOff(steps: List<Maneuver>, i: Int): Double? =
        if (i + 1 < steps.size && steps[i + 1].leavesRoundabout) steps[i + 1].alongMeters else null

    /** The lanes of the road the route comes in on, just before the roundabout. Null when the map doesn't say. */
    fun entryLanes(ways: List<Way>, line: List<LatLng>, cum: DoubleArray, along: Double): Pair<Int, List<String>?>? {
        if (along <= 30) return null
        val p = Geo.point(line, along - 15, cum)
        val back = Geo.point(line, along - 30, cum)
        val roads = ways.filter { !it.isRing && it.tags["highway"] != null }
        val w = roads.minByOrNull { distance(p, it) } ?: return null
        if (distance(p, w) >= 10) return null
        fun count(k: String) = w.tags[k]?.trim()?.toIntOrNull()
        val n: Int?
        val turns: String?
        if (onewayDirection(w.tags) != 0) {
            n = count("lanes")
            turns = w.tags["turn:lanes"]
        } else {
            val forward = Geo.project(p, w.points).along > Geo.project(back, w.points).along
            n = count(if (forward) "lanes:forward" else "lanes:backward") ?: count("lanes")?.let { max(1, it / 2) }
            turns = w.tags[if (forward) "turn:lanes:forward" else "turn:lanes:backward"]
        }
        val arrows = turns?.split("|")?.map { it.trim() }
        if (n == null || n !in 1..6) return null
        return n to (if (arrows?.size == n && arrows.any { it.isNotEmpty() && it != "none" }) arrows else null)
    }

    /** [exit] exits, the last one at [out] and the ones before it spaced evenly on the way round. */
    fun spread(out: Double, exit: Int, clockwise: Boolean): RoundaboutShape {
        val n = max(exit, 1)
        val t = norm(if (clockwise) out - 180 else 180 - out)
        val travel = if (t < 15) t + 360 else t
        val exits = (1..n).map { k ->
            val d = travel * k / n
            norm(if (clockwise) 180 + d else 180 - d)
        }
        return RoundaboutShape(exits, n - 1, clockwise = clockwise, complete = false)
    }

    /**
     * Which way [line] leaves the roundabout it reaches at [along], degrees clockwise from straight
     * ahead (0 straight on, 90 right, 270 left, 180 back).
     */
    fun exitAngle(line: List<LatLng>, cum: DoubleArray, along: Double, leaving: Double? = null): Double? {
        val total = cum.last()
        fun heading(a: Double, b: Double): Double? {
            val p = Geo.point(line, max(0.0, a), cum)
            val q = Geo.point(line, min(total, b), cum)
            return if (p.distanceTo(q) > 3) Geo.bearing(p, q) else null
        }
        val into = heading(along - 40, along - 6) ?: return null
        if (leaving != null && leaving > along && leaving < along + 1500 && leaving + 30 < total) {
            heading(leaving + 5, min(total, leaving + 65))?.let { return norm(it - into) }
        }
        val start = along + 6
        val steady = 5.0
        var d = start
        while (d + 40 <= min(total, start + 1000)) {
            val h = heading(d, d + 40)
            if (h != null) {
                var ok = true
                var a = d
                while (a < d + 40) {
                    val s = heading(a, a + 10)
                    if (s == null || angleGap(s, h) >= steady) { ok = false; break }
                    a += 10
                }
                if (ok) return norm(h - into)
            }
            d += 4
        }
        if (total < start + 60) return heading(along + 10, total)?.let { norm(it - into) }
        return heading(start, start + 60)?.let { norm(it - into) }
    }

    /** Mini-roundabouts on the route, from OpenStreetMap; each one crossed becomes a roundabout step. */
    suspend fun addMinis(route: Route): Route? {
        if (route.mode != TravelMode.DRIVE || route.polyline.size < 2) return null
        val line = mutableListOf(route.polyline[0])
        for (p in route.polyline.drop(1)) if (line.last().distanceTo(p) >= 40) line.add(p)
        line.add(route.polyline.last())
        val coords = line.take(3000).joinToString(",") { String.format(java.util.Locale.US, "%.5f,%.5f", it.lat, it.lng) }
        val q = "[out:json][timeout:25];node[\"highway\"=\"mini_roundabout\"](around:15,$coords)->.m;.m out;" +
            "way(bn.m)[\"highway\"][\"highway\"!~\"^(footway|cycleway|path|pedestrian|steps|bridleway|corridor|construction|proposed|platform)$\"];out geom;"
        val data = Overpass.query(q) ?: return null
        val minis = parseNodes(data)
        if (minis.isEmpty()) return null
        return withMinis(route, minis, parse(data))
    }

    fun parseNodes(data: ByteArray): List<Pair<Long, LatLng>> {
        val elements = Json.obj(data)?.get("elements").asMaps() ?: return emptyList()
        return elements.mapNotNull { e ->
            if (e["type"] != "node") return@mapNotNull null
            val id = e["id"].long() ?: return@mapNotNull null
            val lat = e["lat"].num() ?: return@mapNotNull null
            val lon = e["lon"].num() ?: return@mapNotNull null
            id to LatLng(lat, lon)
        }
    }

    fun withMinis(route: Route, minis: List<Pair<Long, LatLng>>, ways: List<Way>): Route? {
        val r = route.deep()
        val cum = Geo.cumulative(route.polyline)
        val total = cum.last()
        val clockwise = Geo.drivesOnLeft(route.polyline[0])
        var changed = false
        for ((id, at) in minis) {
            val p = Geo.project(at, route.polyline, cum)
            if (!(p.offset < 15 && p.along > 20 && p.along < total - 30)) continue
            val s = miniShape(id, at, ways, route.polyline, cum, p.along, clockwise) ?: continue
            val n = s.first.taken + 1
            val near = r.maneuvers.indices.filter { abs(r.maneuvers[it].alongMeters - p.along) < 30 && !r.maneuvers[it].silent }
            if (near.any { r.maneuvers[it].type == ManeuverType.ROUNDABOUT }) continue
            val i = near.firstOrNull { r.maneuvers[it].type != ManeuverType.DEPART && r.maneuvers[it].type != ManeuverType.ARRIVE }
            if (i != null) {
                val m = r.maneuvers[i]
                val road = m.road
                m.type = ManeuverType.ROUNDABOUT
                m.exit = n
                m.roundabout = s.first
                m.lanes = emptyList()
                m.laneHint = null
                m.instruction = OSRM.instruction("roundabout", null, road, n, TravelMode.DRIVE)
            } else {
                val k = r.maneuvers.indexOfLast { it.alongMeters <= p.along }.let { if (it < 0) 0 else it }
                val current = r.maneuvers.subList(0, k + 1).lastOrNull { it.road != null }?.road
                val onto = s.second?.takeIf { it != current }
                val m = Maneuver(ManeuverType.ROUNDABOUT, OSRM.instruction("roundabout", null, onto, n, TravelMode.DRIVE),
                    Geo.point(route.polyline, p.along, cum), p.along, 0.0, s.second ?: current, exit = n, roundabout = s.first)
                if (k + 1 < r.maneuvers.size) m.distanceMeters = r.maneuvers[k + 1].alongMeters - p.along
                r.maneuvers[k].distanceMeters = p.along - r.maneuvers[k].alongMeters
                r.maneuvers.add(k + 1, m)
            }
            changed = true
        }
        return if (changed) r else null
    }

    /** The picture of mini-roundabout [id]: every road meeting at the point, the way in at the bottom. */
    fun miniShape(id: Long, center: LatLng, ways: List<Way>, line: List<LatLng>, cum: DoubleArray, along: Double, clockwise: Boolean): Pair<RoundaboutShape, String?>? {
        data class A(val angle: Double, val canExit: Boolean, val name: String?)
        val arms = mutableListOf<A>()
        for (w in ways) {
            val oneway = onewayDirection(w.tags)
            for ((i, n) in w.nodes.withIndex()) {
                if (n != id) continue
                for (dir in listOf(1, -1)) {
                    var j = i + dir
                    while (j >= 0 && j < w.points.size && w.points[j].distanceTo(center) < 15 && j + dir >= 0 && j + dir < w.points.size) j += dir
                    if (j < 0 || j >= w.points.size || w.points[j].distanceTo(center) <= 2) continue
                    arms.add(A(Geo.bearing(center, w.points[j]), oneway == 0 || oneway == dir, w.tags["name"] ?: w.tags["ref"]))
                }
            }
        }
        if (arms.size < 3) return null
        val back = Geo.point(line, max(0.0, along - 20), cum)
        val ahead = Geo.point(line, along + 20, cum)
        if (back.distanceTo(center) <= 5 || ahead.distanceTo(center) <= 5) return null
        val inAngle = Geo.bearing(center, back)
        val outAngle = Geo.bearing(center, ahead)
        val entry = arms.indices.minByOrNull { angleGap(arms[it].angle, inAngle) } ?: return null
        if (angleGap(arms[entry].angle, inAngle) >= 45) return null
        fun rel(a: Double) = norm(a - arms[entry].angle + 180)
        fun travel(r: Double): Double {
            val t = norm(if (clockwise) r - 180 else 180 - r)
            return if (t < 15) t + 360 else t
        }
        val exits = arms.indices.filter { it != entry && arms[it].canExit }.sortedBy { travel(rel(arms[it].angle)) }
        val taken = exits.indices.minByOrNull { angleGap(arms[exits[it]].angle, outAngle) } ?: return null
        if (angleGap(arms[exits[taken]].angle, outAngle) >= 45) return null
        val inOnly = arms.indices.filter { it != entry && !arms[it].canExit }.map { rel(arms[it].angle) }
        val shape = RoundaboutShape(exits.map { rel(arms[it].angle) }, taken, inOnly, clockwise, true, mini = true)
        return shape to arms[exits[taken]].name
    }

    suspend fun fetch(spots: List<LatLng>): ByteArray? {
        val around = spots.take(40).joinToString("") {
            String.format(java.util.Locale.US, "way[\"junction\"~\"^(roundabout|circular)$\"](around:35,%.6f,%.6f);", it.lat, it.lng)
        }
        val q = "[out:json][timeout:25];($around)->.r;.r out geom;node(w.r)->.n;" +
            "way(bn.n)[\"highway\"][\"highway\"!~\"^(footway|cycleway|path|pedestrian|steps|bridleway|corridor|construction|proposed|platform|bus_guideway|escape|raceway)$\"];out geom;"
        return Overpass.query(q)
    }

    fun parse(data: ByteArray): List<Way> {
        val elements = Json.obj(data)?.get("elements").asMaps() ?: return emptyList()
        val seen = HashSet<Long>()
        val out = mutableListOf<Way>()
        for (e in elements) {
            if (e["type"] != "way") continue
            val id = e["id"].long() ?: continue
            if (!seen.add(id)) continue
            val nodes = e["nodes"].asList()?.mapNotNull { it.long() } ?: continue
            val geom = e["geometry"].asMaps() ?: continue
            if (geom.size != nodes.size || nodes.size < 2) continue
            val pts = geom.mapNotNull { g ->
                val lat = g["lat"].num()
                val lon = g["lon"].num()
                if (lat != null && lon != null) LatLng(lat, lon) else null
            }
            if (pts.size != nodes.size) continue
            out.add(Way(id, nodes, pts, e.tags()))
        }
        return out
    }

    /** The roundabout entered at [entry]: the ring ways near it and every road touching them. */
    fun shape(ways: List<Way>, entry: LatLng, line: List<LatLng>, cum: DoubleArray, along: Double, clockwise: Boolean, exitNumber: Int?): RoundaboutShape? {
        val (ring, nodes) = ringAt(ways, entry) ?: return null
        var sx = 0.0
        var sy = 0.0
        var total = 0.0
        for (w in ring) for (k in 1 until w.points.size) {
            val a = w.points[k - 1]
            val b = w.points[k]
            val len = a.distanceTo(b)
            sx += (a.lat + b.lat) / 2 * len
            sy += (a.lng + b.lng) / 2 * len
            total += len
        }
        if (total <= 10) return null
        val center = LatLng(sx / total, sy / total)
        val all = ring.flatMap { it.points }
        val radius = all.sumOf { it.distanceTo(center) } / all.size
        if (radius <= 3 || radius >= 250) return null
        var arms = mutableListOf<Arm>()
        for (w in ways) {
            if (w.isRing) continue
            val access = w.tags["access"] ?: w.tags["motor_vehicle"]
            if (access != null && access in setOf("no", "private")) continue
            val oneway = onewayDirection(w.tags)
            for ((i, n) in w.nodes.withIndex()) {
                if (n !in nodes) continue
                val p = w.points[i]
                for (dir in listOf(1, -1)) {
                    val j = i + dir
                    if (j < 0 || j >= w.nodes.size || w.nodes[j] in nodes) continue
                    val leaves = oneway == 0 || oneway == dir
                    val comes = oneway == 0 || oneway == -dir
                    arms.add(Arm(Geo.bearing(center, p), leaves, comes, w.tags["ref"] ?: w.tags["name"]))
                }
            }
        }
        arms = merge(arms).toMutableList()
        if (arms.isEmpty()) return null
        val entryAngle = Geo.bearing(center, entry)
        fun rel(a: Double) = norm(a - entryAngle + 180)
        fun travel(r: Double): Double {
            val t = norm(if (clockwise) r - 180 else 180 - r)
            return if (t < 15) t + 360 else t
        }
        val exits = arms.filter { it.canExit }.map { rel(it.angle) }.sortedBy { travel(it) }
        if (exits.isEmpty()) return null
        val inOnly = arms.filter { !it.canExit && angleGap(rel(it.angle), 180.0) > 25 }.map { rel(it.angle) }
        val outPoint = leavingPoint(line, cum, along, center, radius) ?: return null
        val outAngle = rel(Geo.bearing(center, outPoint))
        val taken = exits.indices.minByOrNull { angleGap(exits[it], outAngle) } ?: return null
        if (angleGap(exits[taken], outAngle) >= 50) return null
        return RoundaboutShape(exits, taken, inOnly, clockwise, true)
    }

    /** 1 when traffic may only go along the way's node order, -1 only against it, 0 both ways. */
    fun onewayDirection(tags: Map<String, String>): Int = when (tags["oneway"] ?: "") {
        "yes", "true", "1" -> 1
        "-1", "reverse" -> -1
        else -> if (tags["highway"] == "motorway") 1 else 0
    }

    /** A dual carriageway meets the circle twice close together: that's one road in the picture. */
    fun merge(arms: List<Arm>): List<Arm> {
        val list = arms.sortedBy { it.angle }.toMutableList()
        var i = 0
        while (list.size > 1 && i < list.size) {
            val j = (i + 1) % list.size
            val a = list[i]
            val b = list[j]
            val pair = (a.canExit && !a.canEnter && b.canEnter && !b.canExit) || (b.canExit && !b.canEnter && a.canEnter && !a.canExit)
            val sameRoad = a.name == null || b.name == null || a.name == b.name
            if (i != j && pair && sameRoad && angleGap(a.angle, b.angle) < 40) {
                val mid = norm(a.angle + signedGap(a.angle, b.angle) / 2)
                list[i] = Arm(mid, true, true, a.name ?: b.name)
                list.removeAt(j)
                if (j < i) i -= 1
            } else i += 1
        }
        return list
    }

    fun leavingPoint(line: List<LatLng>, cum: DoubleArray, along: Double, center: LatLng, radius: Double): LatLng? {
        var m = along + 5
        val end = min(cum.last(), along + radius * 8 + 200)
        while (m <= end) {
            val p = Geo.point(line, m, cum)
            if (p.distanceTo(center) > radius + max(12.0, radius * 0.4)) return p
            m += 4
        }
        return null
    }

    fun distance(p: LatLng, w: Way): Double = Geo.project(p, w.points).offset
}
