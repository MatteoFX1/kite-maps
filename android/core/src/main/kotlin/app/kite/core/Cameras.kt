package app.kite.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.math.*

/** A mapped camera from OpenStreetMap: a fixed speed camera, or a license plate reader (DeFlock). */
data class RoadCamera(
    val id: Long,
    val kind: Kind,
    val location: LatLng,
    /** Who runs a plate reader ("Flock Safety"), when mapped. */
    val operatorName: String? = null,
    /** The limit a speed camera checks, when mapped on it. */
    val maxspeedKmh: Double? = null,
    /** One of a pair or chain timing average speed (how Britain usually maps them). */
    val average: Boolean = false,
    /** The way traffic is going when this camera checks it, as compass bearings; null = both ways. */
    val directions: List<Double>? = null,
    /** Along a route: likely the other side's camera, shown without a chime or voice. */
    val quiet: Boolean = false,
) {
    enum class Kind { SPEED, PLATE }

    /** Whether the map says this camera checks traffic going [heading]: false only for one mapped clearly the other way. */
    fun checks(heading: Double): Boolean {
        val d = directions
        if (d.isNullOrEmpty()) return true
        return d.any { Geo.diff(it, heading) <= 100 }
    }

    companion object {
        /** OSM `direction` as compass bearings: "90", "NE", "45;225", "0-90" (its middle). Null for "both", "forward" and the like. */
        fun bearings(raw: String?): List<Double>? {
            raw ?: return null
            val points = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
            val out = mutableListOf<Double>()
            for (part in raw.split(';', ',')) {
                val p = part.trim().uppercase()
                if (p.isEmpty()) continue
                val i = points.indexOf(p)
                if (i >= 0) { out.add(i * 22.5); continue }
                val n = p.toDoubleOrNull()
                if (n != null) { out.add(n % 360); continue }
                // A range, "315-45": its middle.
                val ends = p.split("-").mapNotNull { it.trim().toDoubleOrNull() }
                if (ends.size != 2) return null
                val span = (ends[1] - ends[0] + 360) % 360
                out.add((ends[0] + span / 2) % 360)
            }
            return if (out.isEmpty()) null else out.map { if (it < 0) it + 360 else it }
        }
    }
}

/** A stretch of road where average speed is checked between cameras (common in Britain). */
data class AverageSpeedZone(val startMeters: Double, val endMeters: Double, val kmh: Double?)

/**
 * Speed and plate cameras from Overpass, the way Vela gets them: keyless, failing over across the
 * public mirrors. Fixed installations only; coverage is OSM's.
 */
object Overpass {
    val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    private val client = Http.client(45)
    private const val UA = "Kite-Maps-Android (https://github.com/matteofx1/kite-maps)"

    /** A mapped road with a posted limit (OSM `maxspeed`), in km/h, and its shape. */
    data class LimitWay(val id: Long = 0, val kmh: Double?, val line: List<LatLng>)

    data class SignalNode(val id: Long, val at: LatLng)

    data class AlongRoute(val cameras: List<RoadCamera>, val ways: List<LimitWay>, val zones: List<AverageSpeedZone>, val signals: List<LatLng>)

    /** Cameras in a box. Null when every mirror failed, so the caller can retry later. */
    suspend fun cameras(south: Double, west: Double, north: Double, east: Double, speed: Boolean, plate: Boolean): List<RoadCamera>? {
        val box = String.format(java.util.Locale.US, "(%.5f,%.5f,%.5f,%.5f)", south, west, north, east)
        return camerasFilter(box, speed, plate)
    }

    /**
     * Cameras, posted speed limits and traffic lights along a route in one query. [limits] false leaves
     * the roads' limits out: the question is then far lighter. Null when every mirror failed.
     */
    suspend fun alongRoute(line: List<LatLng>, speed: Boolean, plate: Boolean, limits: Boolean = true): AlongRoute? {
        if (line.size < 2) return AlongRoute(emptyList(), emptyList(), emptyList(), emptyList())
        val cams = LinkedHashMap<Long, RoadCamera>()
        val ways = mutableListOf<LimitWay>()
        val zones = mutableListOf<AverageSpeedZone>()
        val signals = mutableListOf<LatLng>()
        var answered = false
        for (piece in pieces(line, 40_000.0)) {
            val data = alongQuery(piece, speed, plate, limits) ?: continue
            answered = true
            val found = parse(data) ?: emptyList()
            val w = parseWays(data)
            val lights = parseSignalNodes(data)
            for (c in found) cams[c.id] = c
            ways += w
            zones += averageZones(data, line)
            signals += lights.map { it.at }
            RoadCache.shared.add(w, found, lights)
        }
        if (!answered) return null
        return AlongRoute(cams.values.toList(), ways, CameraAlerts.merge(zones, emptyList()), signalsOnRoute(signals, line))
    }

    /** [line] cut into runs of about [meters], each sharing its first point with the last run's end. */
    fun pieces(line: List<LatLng>, meters: Double): List<List<LatLng>> {
        val out = mutableListOf<List<LatLng>>()
        var cur = mutableListOf(line[0])
        var len = 0.0
        for (p in line.drop(1)) {
            len += cur.last().distanceTo(p)
            cur.add(p)
            if (len >= meters) {
                out.add(cur)
                cur = mutableListOf(p)
                len = 0.0
            }
        }
        if (cur.size >= 2) out.add(cur)
        return out
    }

    /** At most [maxPts] points spread evenly along [line], first and last kept. */
    fun thinTo(line: List<LatLng>, maxPts: Int): List<LatLng> =
        if (line.size <= maxPts) line
        else (0 until maxPts).map { line[min(line.size - 1, ((it.toDouble() * (line.size - 1) / (maxPts - 1))).roundToInt())] }

    private fun coords(pts: List<LatLng>) = pts.joinToString(",") { String.format(java.util.Locale.US, "%.5f,%.5f", it.lat, it.lng) }

    private suspend fun alongQuery(line: List<LatLng>, speed: Boolean, plate: Boolean, limits: Boolean): ByteArray? {
        val c = coords(thinTo(line, 400))
        val parts = mutableListOf<String>()
        if (speed) parts.add("node[\"highway\"=\"speed_camera\"](around:150,$c);")
        if (plate) parts.add("node[\"surveillance:type\"=\"ALPR\"](around:150,$c);")
        if (limits) parts.add("way[\"highway\"][\"maxspeed\"](around:12,$c);")
        if (speed) parts.add("relation[\"enforcement\"=\"average_speed\"](around:200,$c);")
        parts.add("node[\"highway\"=\"traffic_signals\"](around:50,$c);")
        val q = "[out:json][timeout:40];(" + parts.joinToString("") + ");out body geom 8000;"
        return firstAnswer(q) { parse(it) != null }
    }

    /**
     * Asks the mirrors one after another, but without waiting out a slow one: each next mirror is
     * asked [stagger] seconds after the last, and the first good answer wins.
     */
    suspend fun firstAnswer(q: String, stagger: Double = 8.0, valid: (ByteArray) -> Boolean): ByteArray? = coroutineScope {
        val results = Channel<ByteArray?>(endpoints.size)
        val jobs = endpoints.mapIndexed { i, ep ->
            launch {
                if (i > 0) delay((i * stagger * 1000).toLong())
                val r = fetch(ep, q)
                results.send(if (r != null && r.second == 200 && !timedOut(r.first) && valid(r.first)) r.first else null)
            }
        }
        var answer: ByteArray? = null
        repeat(endpoints.size) {
            val r = results.receive()
            if (r != null && answer == null) {
                answer = r
                jobs.forEach { it.cancel() }
                return@coroutineScope r
            }
        }
        answer
    }

    /** Average speed check zones along [line] from enforcement relations whose points lie on the route. */
    fun averageZones(data: ByteArray, line: List<LatLng>): List<AverageSpeedZone> {
        if (line.size < 2) return emptyList()
        val elements = Json.obj(data)?.get("elements").asMaps() ?: return emptyList()
        val cum = Geo.cumulative(line)
        val out = mutableListOf<AverageSpeedZone>()
        for (e in elements) {
            if (e["type"] != "relation") continue
            val tags = e.tags()
            if (tags["enforcement"] != "average_speed") continue
            val pts = mutableListOf<LatLng>()
            for (m in e["members"].asMaps() ?: emptyList()) {
                val lat = m["lat"].num()
                val lon = m["lon"].num()
                if (lat != null && lon != null) pts.add(LatLng(lat, lon))
                for (g in m["geometry"].asMaps() ?: emptyList()) {
                    val a = g["lat"].num()
                    val b = g["lon"].num()
                    if (a != null && b != null) pts.add(LatLng(a, b))
                }
            }
            val along = pts.map { Geo.project(it, line, cum) }.filter { it.offset < 60 }.map { it.along }
            val a = along.minOrNull() ?: continue
            val b = along.maxOrNull() ?: continue
            if (b - a <= 300) continue
            out.add(AverageSpeedZone(a, b, maxspeedKmh(tags["maxspeed"])))
        }
        return out.sortedBy { it.startMeters }
    }

    fun parseSignalNodes(data: ByteArray): List<SignalNode> {
        val elements = Json.obj(data)?.get("elements").asMaps() ?: return emptyList()
        return elements.mapNotNull { e ->
            if (e.tags()["highway"] != "traffic_signals") return@mapNotNull null
            val lat = e["lat"].num() ?: return@mapNotNull null
            val lon = e["lon"].num() ?: return@mapNotNull null
            SignalNode(e["id"].long() ?: 0, LatLng(lat, lon))
        }
    }

    /** The traffic lights on the route itself, one per junction (lights within 40 m along the route drawn once). */
    fun signalsOnRoute(signals: List<LatLng>, line: List<LatLng>): List<LatLng> {
        if (line.size < 2) return emptyList()
        val cum = Geo.cumulative(line)
        val on = signals.map { it to Geo.project(it, line, cum) }.filter { it.second.offset < 20 }.sortedBy { it.second.along }
        val out = mutableListOf<Pair<LatLng, Double>>()
        for ((p, at) in on) if (out.lastOrNull()?.let { at.along - it.second >= 40 } != false) out.add(p to at.along)
        return out.map { it.first }
    }

    fun parseWays(data: ByteArray): List<LimitWay> {
        val elements = Json.obj(data)?.get("elements").asMaps() ?: return emptyList()
        return elements.mapNotNull { e ->
            if (e["type"] != "way") return@mapNotNull null
            val geom = e["geometry"].asMaps() ?: return@mapNotNull null
            val tags = e.tags()
            val line = geom.mapNotNull { g ->
                val lat = g["lat"].num()
                val lon = g["lon"].num()
                if (lat != null && lon != null) LatLng(lat, lon) else null
            }
            if (line.size >= 2) LimitWay(e["id"].long() ?: 0, britishLimit(maxspeedKmh(tags["maxspeed"]), line[0]), line) else null
        }
    }

    /**
     * In Great Britain limits are signed in mph, but some roads are mapped "maxspeed=30" with no unit
     * (which by the map's rules is km/h). No British limit is a round 10 to 70 km/h, so there those
     * are read as the mph the sign says.
     */
    fun britishLimit(kmh: Double?, p: LatLng): Double? {
        if (kmh == null || !inGreatBritain(p)) return kmh
        val n = Math.rint(kmh)
        return if (abs(kmh - n) < 0.01 && n.toInt() % 10 == 0 && n in 10.0..70.0) n * 1.609344 else kmh
    }

    /** England, Wales and Scotland, roughly (Ireland, north and south, left out). */
    fun inGreatBritain(p: LatLng): Boolean =
        p.lat in 49.8..61.0 && p.lng in -8.0..2.0 && !(p.lat < 55.4 && p.lng < -5.4)

    /** OSM `maxspeed` to km/h: "50", "30 mph", "50 km/h", Britain's "national"; null for "none", "DE:urban" and the like. */
    fun maxspeedKmh(raw: String?): Double? {
        val s = raw?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        if (s == "walk") return 7.0
        when (s) {
            "national", "gb:nsl_single", "uk:nsl_single" -> return 60 * 1.609344
            "gb:nsl_dual", "uk:nsl_dual", "gb:motorway", "uk:motorway" -> return 70 * 1.609344
            "gb:nsl_restricted", "uk:nsl_restricted", "gb:urban" -> return 30 * 1.609344
        }
        val m = Regex("^\\d+(\\.\\d+)?").find(s) ?: return null
        val n = m.value.toDoubleOrNull() ?: return null
        val kmh = if (s.contains("mph")) n * 1.609344 else n
        return if (kmh in 1.0..200.0) kmh else null
    }

    /** Turn the roads into limit changes along [line]; a crossing street touching at one point is skipped. */
    fun speedLimits(ways: List<LimitWay>, line: List<LatLng>): List<SpeedLimit> {
        if (line.size < 2) return emptyList()
        val cum = Geo.cumulative(line)
        data class Span(val from: Double, val to: Double, val kmh: Double?)
        val spans = mutableListOf<Span>()
        for (w in ways) {
            val hits = w.line.map { Geo.project(it, line, cum) }.filter { it.offset < 12 }.map { it.along }
            val lo = hits.minOrNull() ?: continue
            val hi = hits.maxOrNull() ?: continue
            if (hi - lo <= 20) continue
            spans.add(Span(lo, hi, britishLimit(w.kmh, w.line[0])))
        }
        if (spans.isEmpty()) return emptyList()
        val out = mutableListOf<SpeedLimit>()
        val total = cum.last()
        var m = 0.0
        while (m <= total) {
            val here = spans.filter { it.from - 15 <= m && m <= it.to + 15 }.minByOrNull { it.to - it.from }
            val kmh = here?.kmh
            if (out.lastOrNull()?.let { it.kmh != kmh } != false) out.add(SpeedLimit(m, kmh))
            m += 25
        }
        return out
    }

    fun query(filter: String, speed: Boolean, plate: Boolean): String {
        val parts = mutableListOf<String>()
        if (speed) parts.add("node[\"highway\"=\"speed_camera\"]$filter;")
        if (plate) parts.add("node[\"surveillance:type\"=\"ALPR\"]$filter;")
        return "[out:json][timeout:25];(" + parts.joinToString("") + ");out body 4000;"
    }

    private suspend fun camerasFilter(filter: String, speed: Boolean, plate: Boolean): List<RoadCamera>? {
        if (!speed && !plate) return emptyList()
        return firstAnswer(query(filter, speed, plate)) { parse(it) != null }?.let { parse(it) }
    }

    /** One request to one mirror. A POST, since a route's query is too long for a URL. Null on a network error. */
    suspend fun fetch(endpoint: String, query: String): Pair<ByteArray, Int>? = try {
        val r = Http.post(endpoint, ("data=" + urlEnc(query)).toByteArray(), "application/x-www-form-urlencoded",
            mapOf("User-Agent" to UA), client)
        r.body to r.code
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /** Any query, failing over across the mirrors; null when none answered with data. */
    suspend fun query(q: String): ByteArray? = firstAnswer(q) { Json.parse(it) != null }

    /** A busy server can answer 200 with a "remark" that the query ran out of time, and no data. */
    fun timedOut(data: ByteArray): Boolean {
        val remark = Json.obj(data)?.get("remark") as? String ?: return false
        return remark.contains("runtime error") || remark.contains("timed out") || remark.contains("out of memory")
    }

    fun parse(data: ByteArray): List<RoadCamera>? {
        val elements = Json.obj(data)?.get("elements").asMaps() ?: return null
        return elements.mapNotNull { e ->
            val lat = e["lat"].num() ?: return@mapNotNull null
            val lon = e["lon"].num() ?: return@mapNotNull null
            val tags = e.tags()
            if (tags["highway"] == "traffic_signals") return@mapNotNull null
            val id = e["id"].long() ?: 0
            val plate = tags["surveillance:type"] == "ALPR"
            val at = LatLng(lat, lon)
            RoadCamera(
                id = id, kind = if (plate) RoadCamera.Kind.PLATE else RoadCamera.Kind.SPEED, location = at,
                operatorName = if (plate) (tags["operator"] ?: tags["manufacturer"]) else null,
                maxspeedKmh = if (plate) null else britishLimit(maxspeedKmh(tags["maxspeed"]), at),
                average = !plate && tags["enforcement"] == "average_speed",
                directions = if (plate) null else RoadCamera.bearings(tags["direction"]),
            )
        }
    }
}

/** A camera placed along a route. */
data class PlacedCamera(val camera: RoadCamera, val along: Double)

/**
 * When to warn about a camera ahead (ported from Vela's CameraAlerts): about twelve seconds out,
 * once per camera, never for one already passed and never while stopped.
 */
object CameraAlerts {
    const val leadSeconds = 12.0
    const val minLead = 150.0
    const val maxLead = 600.0
    const val movingFloor = 2.0

    fun leadDistance(speedMps: Double): Double = min(max(speedMps * leadSeconds, minLead), maxLead)

    /** The index of the camera to announce now, or null. [cameraMeters] are along the route. */
    fun due(cameraMeters: List<Double>, traveled: Double, speedMps: Double, spoken: Set<Int>): Int? {
        if (speedMps < movingFloor) return null
        val lead = leadDistance(speedMps)
        var best: Int? = null
        var bestAhead = Double.MAX_VALUE
        for ((i, m) in cameraMeters.withIndex()) {
            if (i in spoken) continue
            val ahead = m - traveled
            if (ahead <= 0 || ahead > lead) continue
            if (ahead < bestAhead) { bestAhead = ahead; best = i }
        }
        return best
    }

    /** Average speed checks from the cameras themselves: a run of average cameras within [maxGap] of each other. */
    fun averageZones(cams: List<PlacedCamera>, maxGap: Double = 15_000.0): List<AverageSpeedZone> {
        val out = mutableListOf<AverageSpeedZone>()
        var run = mutableListOf<PlacedCamera>()
        fun close() {
            val a = run.firstOrNull()
            val b = run.lastOrNull()
            if (a != null && b != null && b.along - a.along > 300) {
                out.add(AverageSpeedZone(a.along, b.along, run.mapNotNull { it.camera.maxspeedKmh }.firstOrNull()))
            }
            run = mutableListOf()
        }
        for (c in cams) {
            if (!c.camera.average || c.camera.quiet) continue
            val last = run.lastOrNull()
            if (last != null && c.along - last.along > maxGap) close()
            run.add(c)
        }
        close()
        return out
    }

    /** Zones from both sources, merging any that overlap. */
    fun merge(a: List<AverageSpeedZone>, b: List<AverageSpeedZone>): List<AverageSpeedZone> {
        val out = mutableListOf<AverageSpeedZone>()
        for (z in (a + b).sortedBy { it.startMeters }) {
            val last = out.lastOrNull()
            if (last != null && z.startMeters <= last.endMeters) {
                out[out.size - 1] = last.copy(endMeters = max(last.endMeters, z.endMeters), kmh = last.kmh ?: z.kmh)
            } else out.add(z)
        }
        return out
    }

    /** Where each camera sits along [line], dropping any farther than [maxOffset] from it. */
    fun project(cams: List<RoadCamera>, line: List<LatLng>, maxOffset: Double = 35.0): List<PlacedCamera> {
        if (line.size < 2) return emptyList()
        val cum = Geo.cumulative(line)
        val out = mutableListOf<PlacedCamera>()
        for (c in cams) {
            val p = Geo.project(c.location, line, cum)
            if (p.offset > maxOffset) continue
            out.add(PlacedCamera(c.copy(quiet = otherWay(c, p.along, p.offset, line, cum)), p.along))
        }
        return out.sortedBy { it.along }
    }

    /**
     * Likely a camera for the traffic coming the other way: the map says it checks the other way AND
     * it stands on the far side of the road. Such a camera is shown quietly rather than left out.
     */
    fun otherWay(c: RoadCamera, along: Double, offset: Double, line: List<LatLng>, cum: DoubleArray): Boolean {
        val h = heading(line, along, cum)
        if (c.checks(h) || offset < farSideMeters) return false
        val at = Geo.point(line, along, cum)
        val rel = ((Geo.bearing(at, c.location) - h + 540) % 360) - 180
        val onLeft = rel < 0
        return if (Geo.drivesOnLeft(at)) !onLeft else onLeft
    }

    const val farSideMeters = 5.0

    /** Which way the route is going [meters] along it, from the stretch 20 m either side. */
    fun heading(line: List<LatLng>, meters: Double, cum: DoubleArray): Double {
        val total = cum.last()
        val a = Geo.point(line, max(min(meters - 20, total - 40), 0.0), cum)
        val b = Geo.point(line, min(max(meters + 20, 40.0), total), cum)
        return Geo.bearing(a, b)
    }
}
