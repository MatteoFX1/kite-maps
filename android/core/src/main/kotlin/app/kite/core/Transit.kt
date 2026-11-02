package app.kite.core

import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt

/** One part of a transit trip: a walk, or a ride on one line between two stops. Times are epoch millis. */
data class TransitLeg(
    val isWalk: Boolean,
    val mode: String,
    var line: String = "",
    var color: Long? = null,
    var textColor: Long? = null,
    var headsign: String? = null,
    val fromName: String = "",
    val toName: String = "",
    val depart: Long? = null,
    val arrive: Long? = null,
    val durationSeconds: Double = 0.0,
    var distanceMeters: Double = 0.0,
    var stops: Int = 0,
    var delayMinutes: Int? = null,
    var path: List<LatLng> = emptyList(),
) {
    val vehicle: Vehicle get() = Vehicle.of(mode)

    enum class Vehicle {
        WALK, BUS, METRO, TRAM, TRAIN, FERRY, OTHER;

        companion object {
            fun of(mode: String): Vehicle = when (mode) {
                "WALK", "BIKE", "CAR", "RENTAL" -> WALK
                "BUS", "COACH", "ODM", "FLEX" -> BUS
                "SUBWAY", "METRO" -> METRO
                "TRAM", "CABLE_CAR", "FUNICULAR", "AERIAL_LIFT" -> TRAM
                "FERRY" -> FERRY
                else -> if (mode.contains("RAIL") || mode == "SUBURBAN" || mode == "LONG_DISTANCE") TRAIN else OTHER
            }
        }
    }
}

/** One way to get there by public transit. */
data class TransitTrip(
    val depart: Long?,
    val arrive: Long?,
    val durationSeconds: Double,
    val transfers: Int,
    val legs: List<TransitLeg>,
    val id: String = UUID.randomUUID().toString(),
) {
    val rides: List<TransitLeg> get() = legs.filter { !it.isWalk }
    val walkSeconds: Double get() = legs.filter { it.isWalk }.sumOf { it.durationSeconds }
    val path: List<LatLng> get() = legs.flatMap { it.path }
}

/** Public transit directions from Transitous (transitous.org), keyless, with live delays where agencies send them. */
object Transitous {
    const val base = "https://api.transitous.org"
    private val client = Http.client(25)
    private val headers = mapOf("User-Agent" to "Kite-Maps-Android (https://github.com/matteofx1/kite-maps)")

    fun planURL(from: LatLng, to: LatLng, max: Int = 5, timeMillis: Long? = null, arriveBy: Boolean = false): String {
        var s = "$base/api/v1/plan?fromPlace=${from.lat.plain()},${from.lng.plain()}&toPlace=${to.lat.plain()},${to.lng.plain()}&numItineraries=$max"
        if (timeMillis != null) {
            s += "&time=" + Instant.ofEpochMilli(timeMillis).toString().replace(Regex("\\.\\d+Z$"), "Z")
            if (arriveBy) s += "&arriveBy=true"
        }
        return s
    }

    /** Trips leaving now, or around [timeMillis]. Throws on a network error. */
    suspend fun plan(from: LatLng, to: LatLng, timeMillis: Long? = null, arriveBy: Boolean = false): List<TransitTrip> {
        val r = Http.get(planURL(from, to, timeMillis = timeMillis, arriveBy = arriveBy), headers, client)
        if (r.code != 200) throw KiteError.http(r.code)
        return parse(r.body)
    }

    data class Departure(
        val line: String, val mode: String, val color: Long?, val textColor: Long?, val headsign: String,
        val time: Long, val delayMinutes: Int?,
    ) {
        val vehicle get() = TransitLeg.Vehicle.of(mode)
    }

    data class Board(val stop: String, val departures: List<Departure>)

    /** The departure board for the stop nearest [point] (its parent station, so every platform is on one board). */
    suspend fun departures(point: LatLng, radius: Double = 150.0): Board? {
        val dLat = radius / 111_320
        val dLng = radius / (111_320 * cos(point.lat * PI / 180))
        val r = Http.getOrNull("$base/api/v1/map/stops?min=${(point.lat - dLat).plain()},${(point.lng - dLng).plain()}&max=${(point.lat + dLat).plain()},${(point.lng + dLng).plain()}", headers, client) ?: return null
        val stops = Json.parse(r.body).asMaps() ?: return null
        data class S(val name: String, val id: String, val d: Double)
        val nearest = stops.mapNotNull { s ->
            val id = (s["parentId"] as? String) ?: (s["stopId"] as? String) ?: return@mapNotNull null
            val p = point(s) ?: return@mapNotNull null
            S(s["name"] as? String ?: "", id, p.distanceTo(point))
        }.sortedBy { it.d }
        val first = nearest.firstOrNull() ?: return null
        val ids = nearest.filter { it.name == first.name || it.d - first.d < 25 }.map { it.id }.distinct().take(4)
        val all = mutableListOf<Departure>()
        for (id in ids) {
            val d = Http.getOrNull("$base/api/v1/stoptimes?stopId=${urlEnc(id)}&n=30", headers, client) ?: continue
            all += parseStopTimes(d.body, first.name)
        }
        if (all.isEmpty()) return null
        val seen = HashSet<String>()
        return Board(first.name, all.sortedBy { it.time }.filter { seen.add("${it.line}|${it.headsign}|${it.time}") })
    }

    fun parseStopTimes(data: ByteArray, stopName: String = ""): List<Departure> {
        val list = Json.obj(data)?.get("stopTimes").asMaps() ?: return emptyList()
        return list.mapNotNull { st ->
            val place = st["place"].asMap() ?: emptyMap()
            if (st["tripCancelled"] == true || place["cancelled"] == true) return@mapNotNull null
            val time = date(place["departure"]) ?: return@mapNotNull null
            var delay: Int? = null
            if (st["realTime"] == true) date(place["scheduledDeparture"])?.let { delay = ((time - it) / 60_000.0).roundToInt() }
            val line = listOf(st["routeShortName"], st["displayName"], st["routeLongName"]).mapNotNull { (it as? String)?.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
            val end = st["tripTo"].asMap()?.get("name") as? String
            if (end != null && stopName.isNotEmpty() && end == stopName) return@mapNotNull null
            var headsign = st["headsign"] as? String ?: ""
            if (!end.isNullOrEmpty() && (headsign.isEmpty() || headsign == stopName)) headsign = end
            Departure(line, st["mode"] as? String ?: "", hex(st["routeColor"]), hex(st["routeTextColor"]), headsign, time, delay)
        }
    }

    /** A stretch of metro or rail track with its lines' color, for the transit map layer. */
    data class Line(val points: List<LatLng>, val color: Long, val metro: Boolean, val names: List<String>)

    /** How the last [lines] request went, for the build's log. */
    @Volatile var linesNote = ""

    suspend fun lines(south: Double, west: Double, north: Double, east: Double, zoom: Int): List<Line>? {
        val started = System.currentTimeMillis()
        val r = try { Http.get("$base/api/experimental/map/routes?min=${south.plain()},${west.plain()}&max=${north.plain()},${east.plain()}&zoom=$zoom", headers, client) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { linesNote = "${e.javaClass.simpleName}: ${e.message} after ${System.currentTimeMillis() - started} ms"; return null }
        linesNote = "HTTP ${r.code}, ${r.body.size} bytes in ${System.currentTimeMillis() - started} ms" +
            if (r.code != 200) ": " + r.text.take(120) else ""
        if (r.code != 200) return null
        return parseLines(r.body)
    }

    fun parseLines(data: ByteArray): List<Line> {
        val root = Json.obj(data) ?: return emptyList()
        val routes = root["routes"].asMaps() ?: return emptyList()
        val polys = root["polylines"].asMaps() ?: return emptyList()
        val out = mutableListOf<Line>()
        for (p in polys) {
            val rs = (p["routeIndexes"].asList() ?: emptyList()).mapNotNull { it.int()?.let { i -> routes.getOrNull(i) } }
            val rail = rs.filter { TransitLeg.Vehicle.of(it["mode"] as? String ?: "") in setOf(TransitLeg.Vehicle.METRO, TransitLeg.Vehicle.TRAM, TransitLeg.Vehicle.TRAIN) }
            if (rail.isEmpty()) continue
            val enc = p["polyline"].asMap() ?: continue
            val pts = enc["points"] as? String ?: continue
            val color = rail.firstNotNullOfOrNull { hex(it["transitRoutes"].asMaps()?.firstOrNull()?.get("color")) }
            val metro = rail.any { TransitLeg.Vehicle.of(it["mode"] as? String ?: "") != TransitLeg.Vehicle.TRAIN }
            val names = rail.mapNotNull { it["transitRoutes"].asMaps()?.firstOrNull()?.get("shortName") as? String }
            val precision = (enc["precision"].int() ?: 6).coerceIn(5, 7)
            val line = Geo.decodePolyline(pts, 10.0.pow(precision))
            if (line.size < 2) continue
            if (!metro && Geo.length(line) / (line.size - 1) > 700) continue
            out.add(Line(line, color ?: if (metro) 0x5F6368 else 0x8E8E93, metro, names.distinct().sorted()))
        }
        return out
    }

    fun parse(data: ByteArray): List<TransitTrip> {
        val its = Json.obj(data)?.get("itineraries").asMaps() ?: return emptyList()
        val trips = its.mapNotNull { itinerary(it) }
        val seen = HashSet<String>()
        return trips.filter { seen.add(it.rides.joinToString(",") { r -> r.line } + "${(it.depart ?: 0) / 1000}") }
    }

    private fun itinerary(it: Map<String, Any?>): TransitTrip? {
        val rawLegs = it["legs"].asMaps() ?: return null
        val legs = rawLegs.map { leg(it) }
        if (legs.none { !it.isWalk }) return null
        val start = date(it["startTime"])
        val end = date(it["endTime"])
        val dur = it["duration"].num() ?: if (start != null && end != null) (end - start) / 1000.0 else 0.0
        val transfers = it["transfers"].int() ?: maxOf(0, legs.count { !it.isWalk } - 1)
        return TransitTrip(start, end, dur, transfers, legs)
    }

    private fun leg(l: Map<String, Any?>): TransitLeg {
        val mode = l["mode"] as? String ?: ""
        val from = l["from"].asMap() ?: emptyMap()
        val to = l["to"].asMap() ?: emptyMap()
        val start = date(l["startTime"])
        val end = date(l["endTime"])
        val dur = l["duration"].num() ?: if (start != null && end != null) (end - start) / 1000.0 else 0.0
        val out = TransitLeg(TransitLeg.Vehicle.of(mode) == TransitLeg.Vehicle.WALK, mode, fromName = from["name"] as? String ?: "",
            toName = to["name"] as? String ?: "", depart = start, arrive = end, durationSeconds = dur,
            distanceMeters = l["distance"].num() ?: 0.0, path = path(l))
        if (out.path.size < 2) {
            val a = point(from)
            val b = point(to)
            if (a != null && b != null) out.path = listOf(a, b)
        }
        if (out.distanceMeters == 0.0) out.distanceMeters = Geo.length(out.path)
        if (out.isWalk) return out
        out.line = listOf(l["routeShortName"], l["displayName"], l["routeLongName"]).mapNotNull { (it as? String)?.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        out.color = hex(l["routeColor"])
        out.textColor = hex(l["routeTextColor"])
        out.headsign = (l["headsign"] as? String)?.ifEmpty { null }
        out.stops = (l["intermediateStops"].asList()?.size ?: 0) + 1
        if (l["realTime"] == true && start != null) date(l["scheduledStartTime"])?.let { out.delayMinutes = ((start - it) / 60_000.0).roundToInt() }
        return out
    }

    private fun path(l: Map<String, Any?>): List<LatLng> {
        val g = l["legGeometry"].asMap() ?: return emptyList()
        val pts = g["points"] as? String ?: return emptyList()
        if (pts.isEmpty()) return emptyList()
        val p = (g["precision"].int() ?: 6).coerceIn(5, 7)
        return Geo.decodePolyline(pts, 10.0.pow(p)).filter { it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }
    }

    private fun point(o: Map<String, Any?>): LatLng? {
        val lat = o["lat"].num() ?: return null
        val lon = o["lon"].num() ?: return null
        return LatLng(lat, lon)
    }

    fun hex(v: Any?): Long? {
        var s = (v as? String)?.trim() ?: return null
        if (s.isEmpty()) return null
        if (s.startsWith("#")) s = s.drop(1)
        return if (s.length == 6) s.toLongOrNull(16) else null
    }

    fun date(v: Any?): Long? {
        val s = v as? String ?: return null
        if (s.isEmpty()) return null
        return try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e: Exception) {
            try { Instant.parse(s).toEpochMilli() } catch (e2: Exception) { null }
        }
    }
}
