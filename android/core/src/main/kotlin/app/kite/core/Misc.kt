package app.kite.core

import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.CRC32
import kotlin.math.*

/** A police plane or helicopter overhead, from open ADS-B flight tracking (adsb.lol, airplanes.live as backup). */
data class PoliceAircraft(
    val id: String,
    val callsign: String? = null,
    val registration: String? = null,
    val operatorName: String? = null,
    val location: LatLng,
    val altitudeFeet: Double? = null,
    val speedKnots: Double? = null,
    /** Heading over the ground, in degrees. */
    val track: Double? = null,
    val helicopter: Boolean = false,
) {
    val title: String get() = if (helicopter) "Police helicopter" else "Police aircraft"
}

object Aircraft {
    val endpoints = listOf("https://api.adsb.lol/v2", "https://api.airplanes.live/v2")
    private val client = Http.client(15)
    private val policeWords = listOf("POLICE", "SHERIFF", "HIGHWAY PATROL", "STATE PATROL", "CONSTABULARY", "NPAS",
        "LAW ENFORCEMENT", "POLIZEI", "POLIZIA", "POLICIA", "POLÍCIA", "GENDARMERIE", "GUARDIA CIVIL", "CARABINIERI",
        "POLITIE", "POLIISI", "POLISI", "POLITI", "GARDA", "MARECHAUSSEE", "PUBLIC SAFETY", "DEPT OF PUBLIC SAFETY")
    private val policeCallsigns = listOf("NPAS", "UKP", "POLICE", "SHERIFF")
    private val heliTypes = setOf("EC35", "EC45", "EC30", "EC55", "EC135", "H135", "H145", "AS50", "AS55", "AS65", "B407", "B429",
        "B06", "A109", "A119", "A139", "R44", "MD52", "MD60")

    fun url(base: String, p: LatLng, nauticalMiles: Int): String =
        if (base.contains("airplanes.live")) String.format(java.util.Locale.US, "%s/point/%.4f/%.4f/%d", base, p.lat, p.lng, nauticalMiles)
        else String.format(java.util.Locale.US, "%s/lat/%.4f/lon/%.4f/dist/%d", base, p.lat, p.lng, nauticalMiles)

    /** Police aircraft within [nauticalMiles] of [p]. Null when no tracker answered. */
    suspend fun police(p: LatLng, nauticalMiles: Int = 15): List<PoliceAircraft>? {
        for (base in endpoints) {
            val r = Http.getOrNull(url(base, p, nauticalMiles), mapOf("User-Agent" to "Kite-Maps (https://github.com/matteofx1/kite-maps)"), client) ?: continue
            if (r.code != 200) continue
            return parse(r.body) ?: continue
        }
        return null
    }

    fun isPolice(callsign: String?, operatorName: String?): Boolean {
        val op = (operatorName ?: "").uppercase()
        if (policeWords.any { op.contains(it) }) return true
        val cs = (callsign ?: "").uppercase().trim()
        return policeCallsigns.any { cs.startsWith(it) }
    }

    fun parse(data: ByteArray): List<PoliceAircraft>? {
        val root = Json.obj(data) ?: return null
        val list = root["ac"].asMaps() ?: root["aircraft"].asMaps() ?: emptyList()
        return list.mapNotNull { a ->
            val lat = a["lat"].num() ?: return@mapNotNull null
            val lon = a["lon"].num() ?: return@mapNotNull null
            val callsign = (a["flight"] as? String)?.trim()
            val op = a["ownOp"] as? String
            if (!isPolice(callsign, op)) return@mapNotNull null
            if (a["alt_baro"] == "ground") return@mapNotNull null
            val type = (a["t"] as? String ?: "").uppercase()
            val desc = (a["desc"] as? String ?: "").uppercase()
            val heli = a["category"] == "A7" || desc.contains("HELICOPTER") || type in heliTypes
            PoliceAircraft(a["hex"] as? String ?: "$lat,$lon", callsign?.ifEmpty { null }, a["r"] as? String, op, LatLng(lat, lon),
                a["alt_baro"].num(), a["gs"].num(), a["track"].num(), heli)
        }
    }
}

/** How likely police are on the road ahead, 0 to 100, from past Waze reports (time of day, recency) plus live ones. */
object PoliceRisk {
    enum class Level { LOW, MEDIUM, HIGH }
    data class Report(val location: LatLng, val timeMillis: Long)

    fun level(score: Int) = if (score >= 60) Level.HIGH else if (score >= 30) Level.MEDIUM else Level.LOW

    fun score(ahead: List<LatLng>, past: List<Report>, live: Int, nowMillis: Long = System.currentTimeMillis(),
              radius: Double = 400.0, zone: ZoneId = ZoneId.systemDefault()): Int {
        if (ahead.size < 2) return if (live > 0) 70 else 0
        val cum = Geo.cumulative(ahead)
        val pad = radius / 111_000 * 1.5
        val s = ahead.minOf { it.lat } - pad
        val n = ahead.maxOf { it.lat } + pad
        val padLng = pad / max(cos(ahead[0].lat * PI / 180), 0.2)
        val w = ahead.minOf { it.lng } - padLng
        val e = ahead.maxOf { it.lng } + padLng
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val hour = now.hour
        val weekend = now.dayOfWeek.value >= 6
        var weight = 0.0
        for (r in past) {
            if (r.location.lat < s || r.location.lat > n || r.location.lng < w || r.location.lng > e) continue
            if (Geo.project(r.location, ahead, cum).offset > radius) continue
            val days = max((nowMillis - r.timeMillis) / 86_400_000.0, 0.0)
            var x = 0.5.pow(days / 30)
            val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(r.timeMillis), zone)
            val dh = min(abs(t.hour - hour), 24 - abs(t.hour - hour))
            x *= if (dh <= 1) 1.5 else if (dh <= 3) 1.0 else 0.5
            if ((t.dayOfWeek.value >= 6) == weekend) x *= 1.2
            weight += x
        }
        val fromPast = 1 - exp(-weight / 4)
        val fromLive = 1 - 0.3.pow(live.toDouble())
        val combined = 1 - (1 - fromPast * 0.8) * (1 - fromLive * 0.95)
        return (combined * 100).roundToInt()
    }
}

/** When to say you're over the posted limit: a blip never speaks, a long stretch speaks once, slowing down re-arms it. */
class SpeedingAlerts {
    var toleranceKmh = 5.0
    var hold = 4.0
    var rearm = 8.0
    var minGap = 45.0
    private var overSince: Double? = null
    private var underSince: Double? = null
    private var armed = true
    private var lastSpoken = -Double.MAX_VALUE

    /** Feed each fix ([now] in seconds); true when the alert should be spoken now. */
    fun update(speedKmh: Double?, limitKmh: Double?, now: Double): Boolean {
        if (speedKmh == null || limitKmh == null || limitKmh <= 0) {
            overSince = null
            return false
        }
        if (speedKmh <= limitKmh + toleranceKmh) {
            overSince = null
            if (underSince == null) underSince = now
            if (now - (underSince ?: now) >= rearm) armed = true
            return false
        }
        underSince = null
        if (overSince == null) overSince = now
        if (!armed || now - (overSince ?: now) < hold || now - lastSpoken < minGap) return false
        armed = false
        lastSpoken = now
        return true
    }
}

/** Road names the way they should be said ("Burgess Rd" is "Burgess Road"). */
object SpokenText {
    private val words = listOf(
        "Rd" to "Road", "Wy" to "Way", "Ln" to "Lane", "Ave" to "Avenue", "Av" to "Avenue",
        "Dr" to "Drive", "Pl" to "Place", "Cres" to "Crescent", "Cl" to "Close", "Gdns" to "Gardens",
        "Sq" to "Square", "Hwy" to "Highway", "Pkwy" to "Parkway", "Blvd" to "Boulevard", "Ct" to "Court",
        "Gr" to "Grove", "Tce" to "Terrace", "Terr" to "Terrace", "Rdbt" to "roundabout", "Rbt" to "roundabout",
        "Jct" to "junction", "Brg" to "Bridge", "Hl" to "Hill", "Nr" to "near", "Ctr" to "Centre", "Ind Est" to "Industrial Estate",
        "So'ton" to "Southampton", "Soton" to "Southampton", "Pompey" to "Portsmouth",
    ).map { (short, long) -> Regex("(?<![\\p{L}'])" + Regex.escape(short) + "(?![\\p{L}'])") to long }

    fun forVoice(text: String): String {
        var s = text
        for ((re, long) in words) s = s.replace(re, Regex.escapeReplacement(long))
        s = s.replace(Regex("(?<=\\p{Lu}[\\p{L}']{1,30} )St(?![\\p{L}'])(?! \\p{Lu})"), "Street")
        s = s.replace(Regex("(?<![\\p{L}'])St\\.?(?= \\p{Lu})"), "Saint")
        s = s.replace(Regex("(?<=\\d)\\(M\\)"), " M")
        for ((d, w) in listOf("N" to "north", "S" to "south", "E" to "east", "W" to "west")) {
            s = s.replace(Regex("(?<=\\d) $d(?![\\p{L}'])"), " $w")
        }
        return compass(s)
    }

    /** For the screen: "W Approach Rd" reads "West Approach Rd". */
    fun forScreen(text: String): String = compass(text)

    fun compass(text: String): String {
        var s = text
        for ((d, w) in listOf("N" to "North", "S" to "South", "E" to "East", "W" to "West")) {
            s = s.replace(Regex("(?<=(?:Road|Rd|Street|St|Avenue|Ave|Lane|Ln|Way|Drive|Dr|Bridge|Hill|Parade) )$d(?![\\p{L}'])"), w)
        }
        for ((d, w) in listOf("N" to "North", "S" to "South", "E" to "East", "W" to "West")) {
            s = s.replace(Regex("(?<![\\p{L}\\d'.])(?<!\\d )$d(?= \\p{Lu}\\p{Ll})"), w)
        }
        return s
    }
}

/** OpenStreetMap surveillance cameras. Generic CCTV is not necessarily an ALPR. */
data class SurveillanceCamera(
    val id: Long, val location: LatLng, val isALPR: Boolean, val operatorName: String?, val manufacturer: String?,
    val direction: String?, val surveillance: String?, val mount: String?,
)

object SurveillanceCameras {
    fun query(south: Double, west: Double, north: Double, east: Double): String {
        val box = String.format(java.util.Locale.US, "(%.5f,%.5f,%.5f,%.5f)", south, west, north, east)
        return "[out:json][timeout:25];(node[\"man_made\"=\"surveillance\"]$box;node[\"surveillance:type\"=\"ALPR\"]$box;);out body 4000;"
    }

    fun parse(data: ByteArray): List<SurveillanceCamera>? {
        val elements = Json.obj(data)?.get("elements").asMaps() ?: return null
        val seen = HashSet<Long>()
        return elements.mapNotNull { e ->
            val id = e["id"].long() ?: return@mapNotNull null
            val lat = e["lat"].num() ?: return@mapNotNull null
            val lon = e["lon"].num() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || !seen.add(id)) return@mapNotNull null
            val tags = e.tags()
            val alpr = tags["surveillance:type"]?.uppercase() == "ALPR"
            if (!alpr && tags["man_made"] != "surveillance") return@mapNotNull null
            SurveillanceCamera(id, LatLng(lat, lon), alpr, tags["operator"], tags["manufacturer"], tags["camera:direction"],
                tags["surveillance"], tags["camera:mount"])
        }
    }

    suspend fun inBox(south: Double, west: Double, north: Double, east: Double): List<SurveillanceCamera>? =
        Overpass.firstAnswer(query(south, west, north, east)) { parse(it) != null }?.let { parse(it) }
}

/** Small ZIP writer for diagnostic files: stored entries and CRC-32 (from Ausbloke's Kite fork, GPL-3.0). */
object DiagnosticZip {
    data class Entry(val name: String, val data: ByteArray)

    fun archive(entries: List<Entry>): ByteArray {
        require(entries.size <= 0xFFFF)
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        fun u16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff) }
        fun u32(o: ByteArrayOutputStream, v: Long) { for (i in 0 until 4) o.write(((v shr (8 * i)) and 0xff).toInt()) }
        for (e in entries) {
            val name = e.name.toByteArray(Charsets.UTF_8)
            require(e.name.isNotEmpty() && !e.name.contains("/") && !e.name.contains("\\") && name.size <= 0xFFFF)
            val crc = CRC32().apply { update(e.data) }.value
            val size = e.data.size.toLong()
            val offset = out.size().toLong()
            u32(out, 0x04034b50); u16(out, 20); u16(out, 0x800); u16(out, 0); u16(out, 0); u16(out, 0)
            u32(out, crc); u32(out, size); u32(out, size); u16(out, name.size); u16(out, 0)
            out.write(name); out.write(e.data)
            u32(central, 0x02014b50); u16(central, 20); u16(central, 20); u16(central, 0x800)
            u16(central, 0); u16(central, 0); u16(central, 0); u32(central, crc)
            u32(central, size); u32(central, size); u16(central, name.size); u16(central, 0)
            u16(central, 0); u16(central, 0); u16(central, 0); u32(central, 0); u32(central, offset)
            central.write(name)
        }
        val offset = out.size().toLong()
        val cd = central.toByteArray()
        out.write(cd)
        u32(out, 0x06054b50); u16(out, 0); u16(out, 0); u16(out, entries.size); u16(out, entries.size)
        u32(out, cd.size.toLong()); u32(out, offset); u16(out, 0)
        return out.toByteArray()
    }
}

/** Follows the driver along a route: snaps each fix to the line, distance to the next turn, trip left, off route. */
class RouteTracker(val route: Route) {
    data class Progress(
        val snapped: LatLng,
        val alongMeters: Double,
        val offsetMeters: Double,
        val routeBearing: Double,
        val nextIndex: Int?,
        val distanceToNext: Double,
        val remainingMeters: Double,
        val remainingSeconds: Double,
        val offRoute: Boolean,
        val arrived: Boolean,
    )

    private val cum = Geo.cumulative(route.polyline)
    private val total = cum.last()
    private var segment = 0
    private var offCount = 0
    private var wrongWayCount = 0

    private data class Near(val seg: Int, val t: Double, val dist: Double)

    /** [heading] is the way the car is moving, only when it's moving fast enough for the course to mean something. */
    fun update(fix: LatLng, accuracy: Double = 10.0, heading: Double? = null): Progress {
        val line = route.polyline
        var best = nearest(fix, max(0, segment - 3), min(line.size - 2, segment + 40))
        if (best.dist > 60) {
            val all = nearest(fix, 0, line.size - 2)
            if (all.dist < best.dist - 15) best = all
        }
        segment = best.seg
        val along = cum[best.seg] + best.t * (cum[best.seg + 1] - cum[best.seg])
        val a0 = line[best.seg]
        val b0 = line[best.seg + 1]
        val snapped = LatLng(a0.lat + (b0.lat - a0.lat) * best.t, a0.lng + (b0.lng - a0.lng) * best.t)
        val limit = max(40.0, min(accuracy, 60.0) + 25)
        offCount = if (best.dist > limit * 2) offCount + 2 else if (best.dist > limit) offCount + 1 else 0
        val routeBearing = Geo.bearing(a0, b0)
        val a = Geo.point(line, max(0.0, along - 10), cum)
        val b = Geo.point(line, min(total, along + 20), cum)
        val roadBearing = if (a.distanceTo(b) > 5) Geo.bearing(a, b) else routeBearing
        wrongWayCount = if (heading != null && best.dist <= limit) {
            if (Geo.diff(heading, roadBearing) > 120) wrongWayCount + 1 else 0
        } else 0
        val next = route.maneuvers.indexOfFirst { !it.silent && it.alongMeters > along + 8 }.takeIf { it >= 0 }
        val toNext = next?.let { route.maneuvers[it].alongMeters - along } ?: (total - along)
        val remaining = max(0.0, total - along)
        return Progress(snapped, along, best.dist, routeBearing, next, max(0.0, toNext), remaining,
            if (total > 0) route.durationSeconds * remaining / total else 0.0,
            offCount >= 3 || wrongWayCount >= 4, remaining < 25 && best.dist < limit)
    }

    private fun nearest(p: LatLng, from: Int, to: Int): Near {
        val line = route.polyline
        if (line.size < 2 || from > to) return Near(0, 0.0, Double.MAX_VALUE)
        val k = cos(p.lat * PI / 180) * 111_320.0
        var best = Near(from, 0.0, Double.MAX_VALUE)
        for (i in from..to) {
            val a = line[i]
            val b = line[i + 1]
            val ax = (a.lng - p.lng) * k
            val ay = (a.lat - p.lat) * 110_540
            val bx = (b.lng - p.lng) * k
            val by = (b.lat - p.lat) * 110_540
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) min(max(-(ax * dx + ay * dy) / len2, 0.0), 1.0) else 0.0
            val x = ax + t * dx
            val y = ay + t * dy
            val d = sqrt(x * x + y * y)
            if (d < best.dist) best = Near(i, t, d)
        }
        return best
    }
}

/** Google Street View, keyless: the panorama lookup Google's own pages use, a thumbnail, and image tiles. */
object StreetView {
    data class Panorama(val id: String, val location: LatLng, val width: Int = 16384, val height: Int = 8192, val heading: Double = 0.0) {
        fun size(zoom: Int): Pair<Int, Int> {
            val f = 1 shl max(0, 5 - zoom)
            return (width / f) to (height / f)
        }
    }

    const val searchEndpoint = "https://maps.googleapis.com/\$rpc/google.internal.maps.mapsjs.v1.MapsJsInternalService/SingleImageSearch"
    private val client = Http.client(12)

    fun searchBody(p: LatLng, radius: Int): String =
        "[[\"apiv3\",null,null,null,\"US\",null,null,null,null,null,[[0]]]," +
            "[[null,null,${p.lat.plain()},${p.lng.plain()}],$radius]," +
            "[null,[\"en\",\"US\"],null,null,null,null,null,null,[2],null,[[[2,true,2]]]]," +
            "[[1,2,3,4,8,6]]]"

    /** The nearest official Google panorama within [radius] meters, or null. */
    suspend fun find(p: LatLng, radius: Int = 60): Panorama? = try {
        val r = Http.post(searchEndpoint, searchBody(p, radius).toByteArray(), "application/json+protobuf",
            mapOf("User-Agent" to GoogleClient.userAgent, "Referer" to "https://www.google.com/"), client)
        parse(r.text)
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    fun parse(text: String): Panorama? {
        val idMatch = Regex("\\[2,\\s*\"[A-Za-z0-9_-]{22}\"\\]").find(text) ?: return null
        val id = idMatch.value.filter { it != ' ' }.drop(4).dropLast(2)
        val r = Regex("\\[\\[null,null,-?\\d+\\.\\d+,\\s*-?\\d+\\.\\d+\\]").find(text) ?: return null
        val nums = r.value.drop(12).dropLast(1).split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (nums.size != 2) return null
        var pano = Panorama(id, LatLng(nums[0], nums[1]))
        val after = text.substring(idMatch.range.last + 1)
        Regex("^,\\[\\d+,\\d+,\\[\\d+,\\d+\\]").find(after)?.let { m ->
            val n = m.value.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }
            if (n.size == 4 && n[3] >= 1024) pano = pano.copy(height = n[2], width = n[3])
        }
        val rest = text.substring(r.range.last + 1)
        Regex("^,\\[[^\\]]*\\],\\[-?\\d+(\\.\\d+)?").find(rest)?.let { m ->
            m.value.split("[").last().toDoubleOrNull()?.let { pano = pano.copy(heading = it) }
        }
        return pano
    }

    fun tileUrl(pano: Panorama, x: Int, y: Int, zoom: Int) =
        "https://streetviewpixels-pa.googleapis.com/v1/tile?cb_client=maps_sv.tactile&panoid=${pano.id}&x=$x&y=$y&zoom=$zoom&nbt=1&fover=2"

    fun thumbnailUrl(pano: Panorama, heading: Double, width: Int = 640, height: Int = 360) =
        "https://streetviewpixels-pa.googleapis.com/v1/thumbnail?panoid=${pano.id}&cb_client=maps_sv.tactile.gps&w=$width&h=$height&yaw=${heading.roundToInt()}&pitch=0&thumbfov=100"
}

/** Posted speed limits along a route from the public Valhalla server (FOSSGIS): a whole drive in under a second. */
object Valhalla {
    const val endpoint = "https://valhalla1.openstreetmap.de/trace_attributes"
    private val client = Http.client(15)

    data class Limits(val limits: List<SpeedLimit>, val ways: List<Overpass.LimitWay>)

    /** The limits along [line], and the roads it drives. Null when the server didn't answer. [progress] gets limits after each piece. */
    suspend fun speedLimits(line: List<LatLng>, progress: (suspend (List<SpeedLimit>) -> Unit)? = null): Limits? {
        if (line.size < 2) return Limits(emptyList(), emptyList())
        val cum = Geo.cumulative(line)
        val total = cum.last()
        val merged = mutableListOf<SpeedLimit>()
        val ways = mutableListOf<Overpass.LimitWay>()
        var start = 0.0
        while (start < total) {
            val end = min(total, start + 25_000)
            val piece = Geo.slice(line, start, end, cum)
            val data = fetch(thin(piece)) ?: return null
            val parsed = parse(data) ?: return null
            val placed = Overpass.speedLimits(parsed.second, piece)
            val scale = if (parsed.third > 0) (end - start) / parsed.third else 1.0
            var m = 0.0
            while (m < end - start) {
                val kmh = placed.lastOrNull { it.startMeters <= m }?.kmh ?: parsed.first.lastOrNull { it.startMeters * scale <= m }?.kmh
                if (merged.lastOrNull()?.let { it.kmh != kmh } != false) merged.add(SpeedLimit(start + m, kmh))
                m += 25
            }
            ways += parsed.second
            start = end
            if (start < total) progress?.invoke(merged + SpeedLimit(start, null))
        }
        return Limits(merged, ways)
    }

    fun thin(line: List<LatLng>): List<LatLng> {
        var last = line.firstOrNull() ?: return emptyList()
        val out = mutableListOf(last)
        for (p in line.drop(1)) if (last.distanceTo(p) >= 15) { out.add(p); last = p }
        val end = line.last()
        if (out.last() != end) out.add(end)
        return out
    }

    private suspend fun fetch(shape: List<LatLng>): ByteArray? {
        val body = mapOf(
            "shape" to shape.map { mapOf("lat" to it.lat, "lon" to it.lng) },
            "costing" to "auto",
            "shape_match" to "map_snap",
            "filters" to mapOf(
                "attributes" to listOf("edge.speed_limit", "edge.way_id", "edge.names", "edge.length", "edge.begin_shape_index", "edge.end_shape_index", "shape"),
                "action" to "include",
            ),
        )
        return try {
            val r = Http.post(endpoint, Json.write(body).toByteArray(), "application/json", mapOf("User-Agent" to OSRM.userAgent), client)
            if (r.code == 200) r.body else null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    /** Limit changes along the matched roads, the roads themselves, and their length. */
    fun parse(data: ByteArray): Triple<List<SpeedLimit>, List<Overpass.LimitWay>, Double>? {
        val root = Json.obj(data) ?: return null
        val edges = root["edges"].asMaps() ?: return null
        val shape = (root["shape"] as? String)?.let { Geo.decodePolyline(it) } ?: emptyList()
        val limits = mutableListOf<SpeedLimit>()
        val ways = mutableListOf<Overpass.LimitWay>()
        var along = 0.0
        var carried: Pair<Double, Set<String>>? = null
        for (e in edges) {
            val raw = e["speed_limit"].num()
            val mapped = Overpass.britishLimit(raw?.takeIf { it in 1.0..200.0 }, shape.firstOrNull() ?: LatLng(0.0, 0.0))
            val names = (e["names"].asList()?.mapNotNull { it as? String } ?: emptyList()).toSet()
            val c = carried
            if (mapped != null && names.isNotEmpty()) carried = mapped to names
            else if (mapped == null && c != null && names.intersect(c.second).isEmpty()) carried = null
            else if (mapped != null) carried = null
            val kmh = mapped ?: carried?.first
            if (limits.lastOrNull()?.let { it.kmh != kmh } != false) limits.add(SpeedLimit(along, kmh))
            along += (e["length"].num() ?: 0.0) * 1000
            val a = e["begin_shape_index"].int()
            val b = e["end_shape_index"].int()
            if (a != null && b != null && a >= 0 && b < shape.size && b > a) {
                ways.add(Overpass.LimitWay(e["way_id"].long() ?: 0, mapped, shape.subList(a, b + 1)))
            }
        }
        return Triple(limits, ways, along)
    }
}
