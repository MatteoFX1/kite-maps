package app.kite.core

import java.time.Instant
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos

/**
 * A camera that shows a live photo: a road traffic camera (UK) or a scenic webcam (Windy).
 * Asked for GitHub issue #15, "webcams nearby like Windy". Same as the iPhone's LiveCam.
 */
data class LiveCam(
    /** "oh:207", "tfl:JamCams_00001.02151" or "windy:1234": unique across sources. */
    val id: String,
    val source: Source,
    val name: String,
    /** "M25", for a road camera, when known. */
    val road: String? = null,
    /** Which way it faces or which carriageway, as the source words it. */
    val direction: String? = null,
    val location: LatLng,
    /** The latest photo. Windy's links stop working after about 15 minutes. */
    val imageUrl: String,
    /** A page with more: Windy's timelapse player, or TfL's short video clip. */
    val moreUrl: String? = null,
    /** When the list said the photo was last updated (Windy only), in milliseconds. */
    val updatedAtMillis: Long? = null,
) {
    enum class Source(val raw: String, val credit: String) {
        NATIONAL_HIGHWAYS("national_highways", "National Highways"),
        TFL("tfl", "Transport for London"),
        TRAFFIC_SCOTLAND("traffic_scotland", "Traffic Scotland"),
        TRAFFIC_WALES("traffic_wales", "Traffic Wales"),
        NORTHERN_IRELAND("northern_ireland", "TrafficWatch NI"),
        ESSEX("essex", "Essex Highways"),
        WINDY("windy", "Windy.com");

        val isRoad: Boolean get() = this != WINDY

        companion object { fun of(raw: String?) = entries.firstOrNull { it.raw == raw } }
    }

    /** A second line under the name: "M25 · Clockwise", "Webcam · Keswick". */
    val detail: String
        get() {
            val parts = mutableListOf<String>()
            if (source == Source.WINDY) parts += "Webcam"
            else if (!road.isNullOrEmpty() && !name.contains(road, ignoreCase = true)) parts += road
            if (!direction.isNullOrEmpty()) parts += direction
            return parts.joinToString(" · ")
        }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id, "source" to source.raw, "name" to name, "road" to road, "direction" to direction,
        "lat" to location.lat, "lng" to location.lng, "image" to imageUrl, "more" to moreUrl, "updated" to updatedAtMillis,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): LiveCam? {
            val src = Source.of(m["source"].text()) ?: return null
            return LiveCam(
                m["id"].text() ?: return null, src, m["name"].text() ?: return null, m["road"].text(), m["direction"].text(),
                LatLng(m["lat"].num() ?: return null, m["lng"].num() ?: return null), m["image"].text() ?: return null,
                m["more"].text(), m["updated"].long(),
            )
        }
    }
}

/** Reading the camera lists, and which cameras belong on a route. */
object LiveCams {
    const val OPEN_HIGHWAYS_URL = "https://openhighways.uk/api/cameras"
    const val TFL_URL = "https://api.tfl.gov.uk/Place/Type/JamCam"

    /**
     * OpenHighways (openhighways.uk, MIT): every public UK road camera with a place on the map,
     * from National Highways, TfL, Traffic Scotland, Traffic Wales, TrafficWatch NI and Essex.
     */
    fun parseOpenHighways(data: ByteArray): List<LiveCam>? {
        val list = Json.parse(data).asMaps() ?: return null
        val seen = HashSet<Long>()
        return list.mapNotNull { o ->
            if (o["active"] == false) return@mapNotNull null
            val n = o["id"].long() ?: return@mapNotNull null
            val lat = o["latitude"].num() ?: return@mapNotNull null
            val lng = o["longitude"].num() ?: return@mapNotNull null
            if (!valid(lat, lng)) return@mapNotNull null
            val url = absolute(o["image_url"].text() ?: return@mapNotNull null, "https://openhighways.uk") ?: return@mapNotNull null
            if (!seen.add(n)) return@mapNotNull null
            val source = LiveCam.Source.of(o["source"].text()) ?: LiveCam.Source.NATIONAL_HIGHWAYS
            if (source == LiveCam.Source.WINDY) return@mapNotNull null
            val road = clean(o["road"].text())
            val name = clean(o["name"].text()) ?: road ?: "Road camera"
            LiveCam("oh:$n", source, tidy(name), road, clean(o["direction"].text()), LatLng(lat, lng), url)
        }
    }

    /** TfL's JamCams, straight from TfL: London only, used when OpenHighways doesn't answer. */
    fun parseTfL(data: ByteArray): List<LiveCam>? {
        val list = Json.parse(data).asMaps() ?: return null
        return list.mapNotNull { o ->
            val id = o["id"].text() ?: return@mapNotNull null
            val lat = o["lat"].num() ?: return@mapNotNull null
            val lng = o["lon"].num() ?: return@mapNotNull null
            if (!valid(lat, lng)) return@mapNotNull null
            val props = HashMap<String, String>()
            for (p in o["additionalProperties"].asMaps() ?: emptyList()) {
                val k = p["key"].text(); val v = p["value"].text()
                if (k != null && v != null) props[k] = v
            }
            if (props["available"]?.lowercase() == "false") return@mapNotNull null
            val image = props["imageUrl"] ?: return@mapNotNull null
            val name = clean(o["commonName"].text()) ?: "London camera"
            LiveCam("tfl:$id", LiveCam.Source.TFL, tidy(name), null, clean(props["view"]), LatLng(lat, lng), image, props["videoUrl"])
        }
    }

    /** Windy's webcams (Webcams API v3), from a "nearby" or single-webcam answer. */
    /** How many webcams Windy's answer listed before any were left out (inactive, no photo): 50 means the limit was hit. */
    fun windyListed(data: ByteArray): Int = Json.parse(data).asMap()?.get("webcams")?.asMaps()?.size ?: 0

    fun parseWindy(data: ByteArray): List<LiveCam>? {
        val root = Json.parse(data).asMap() ?: return null
        val list = root["webcams"].asMaps() ?: if (root["webcamId"] != null) listOf(root) else return null
        return list.mapNotNull { o ->
            val n = o["webcamId"].long() ?: o["id"].long() ?: return@mapNotNull null
            if ((o["status"].text() ?: "active") != "active") return@mapNotNull null
            val loc = o["location"].asMap() ?: return@mapNotNull null
            val lat = loc["latitude"].num() ?: return@mapNotNull null
            val lng = loc["longitude"].num() ?: return@mapNotNull null
            if (!valid(lat, lng)) return@mapNotNull null
            val current = o["images"].asMap()?.get("current").asMap() ?: return@mapNotNull null
            val image = current["preview"].text() ?: current["thumbnail"].text() ?: return@mapNotNull null
            val title = clean(o["title"].text()) ?: "Webcam"
            val place = listOf(loc["city"].text(), loc["region"].text()).firstNotNullOfOrNull { clean(it) }
            val more = o["urls"].asMap()?.get("detail").text() ?: "https://www.windy.com/webcams/$n"
            val updated = o["lastUpdatedOn"].text()?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            LiveCam("windy:$n", LiveCam.Source.WINDY, title, null, place, LatLng(lat, lng), image, more, updated)
        }
    }

    /** The Windy "nearby" ask: up to 50 webcams within [radiusKm] (Windy allows up to 250). */
    fun windyNearbyUrl(center: LatLng, radiusKm: Int): String =
        String.format(Locale.US, "https://api.windy.com/webcams/api/v3/webcams?nearby=%.4f,%.4f,%d&limit=50&include=images,location,urls",
            center.lat, center.lng, radiusKm.coerceIn(1, 250))

    fun windyOneUrl(id: String): String? =
        if (id.startsWith("windy:")) "https://api.windy.com/webcams/api/v3/webcams/${id.removePrefix("windy:")}?include=images,location,urls" else null

    /** Map zoom from which road cameras show (a city: there are thousands) and webcams (a region). */
    const val ROAD_MIN_ZOOM = 9.0
    const val WEBCAM_MIN_ZOOM = 6.0

    /** How far off the route a webcam can be and still count as on it: one in a town you drive through. */
    const val WEBCAM_ROUTE_OFFSET = 2500.0

    /** A camera along a route, [along] meters from the start. */
    data class OnRoute(val cam: LiveCam, val along: Double)

    /**
     * The cameras along [line], in the order you'd pass them, at most [limit]: road cameras within
     * [maxOffset] meters of the route, webcams within [webcamOffset] (0 leaves them out), and not
     * two within [spacing] meters of each other along it.
     */
    fun along(cams: List<LiveCam>, line: List<LatLng>, maxOffset: Double = 120.0, webcamOffset: Double = 0.0,
              spacing: Double = 1500.0, limit: Int = 12): List<OnRoute> {
        if (line.size < 2) return emptyList()
        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
        for (p in line) { s = minOf(s, p.lat); n = maxOf(n, p.lat); w = minOf(w, p.lng); e = maxOf(e, p.lng) }
        // Wide enough for the farthest camera allowed; a degree of longitude shrinks toward the poles.
        val latPad = maxOf(0.01, maxOf(maxOffset, webcamOffset) / 110_000 * 1.2)
        val lngPad = latPad / maxOf(cos(maxOf(abs(s), abs(n)) * PI / 180), 0.05)
        val cum = Geo.cumulative(line)
        val hits = ArrayList<OnRoute>()
        for (c in cams) {
            val limitOff = if (c.source.isRoad) maxOffset else webcamOffset
            if (limitOff <= 0) continue
            val p = c.location
            if (p.lat < s - latPad || p.lat > n + latPad || p.lng < w - lngPad || p.lng > e + lngPad) continue
            val pr = Geo.project(p, line, cum)
            if (pr.offset <= limitOff) hits += OnRoute(c, pr.along)
        }
        hits.sortBy { it.along }
        val out = ArrayList<OnRoute>()
        for (h in hits) {
            val last = out.lastOrNull()
            if (last != null && h.along - last.along < spacing) continue
            out += h
            if (out.size >= limit) break
        }
        return out
    }

    /** One Windy "nearby" ask along a route: the stretch [from]..[to] meters, and the circle that covers it. */
    data class WindyRouteAsk(val from: Double, val to: Double, val center: LatLng, val radiusKm: Int)

    /**
     * The circle around one stretch of [line]: centered halfway along it, wide enough for the whole
     * stretch (no point on it is farther from its middle than half its length) plus a webcam's
     * distance off the road.
     */
    fun windyRouteAsk(line: List<LatLng>, cum: DoubleArray, from: Double, to: Double): WindyRouteAsk {
        val center = Geo.point(line, (from + to) / 2, cum)
        val radius = minOf(250, maxOf(10, ceil((to - from) / 2 / 1000).toInt() + 5))
        return WindyRouteAsk(from, to, center, radius)
    }

    /**
     * Where to ask Windy about the webcams along [line]: stretches of about 40 km, at most
     * [maxAsks] (longer stretches on a long trip). Short routes get one ask.
     */
    fun windyRouteAsks(line: List<LatLng>, maxAsks: Int = 8): List<WindyRouteAsk> {
        if (line.size < 2 || maxAsks <= 0) return emptyList()
        val cum = Geo.cumulative(line)
        val total = cum.last()
        val count = minOf(maxAsks, maxOf(1, ceil(total / 40_000).toInt()))
        val step = total / count
        return (0 until count).map { windyRouteAsk(line, cum, step * it, step * (it + 1)) }
    }

    /**
     * An ask whose answer hit Windy's 50-webcam limit, split into its two halves so the webcams it
     * left out can be found. Null when it's already small (about 10 km across), as far as it's worth going.
     */
    fun windySplit(ask: WindyRouteAsk, line: List<LatLng>, cum: DoubleArray): List<WindyRouteAsk>? {
        if (ask.to - ask.from <= 10_000) return null
        val mid = (ask.from + ask.to) / 2
        return listOf(windyRouteAsk(line, cum, ask.from, mid), windyRouteAsk(line, cum, mid, ask.to))
    }

    internal fun valid(lat: Double, lng: Double) = lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)

    internal fun absolute(raw: String, base: String): String? = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("/") -> base + raw
        else -> null
    }

    internal fun clean(s: String?): String? {
        val t = s?.trim() ?: return null
        return if (t.isEmpty() || t.equals("null", ignoreCase = true)) null else t
    }

    /** "A406 BILLET UPASS E" reads as shouting; all-capital names get title case, road numbers kept. */
    fun tidy(name: String): String {
        val letters = name.filter { it.isLetter() }
        if (letters.length < 6 || letters != letters.uppercase()) return name
        return name.split(" ").joinToString(" ") { word ->
            when {
                word.firstOrNull()?.let { it in "MAJB" } == true && word.getOrNull(1)?.isDigit() == true -> word
                word.length <= 2 && word.all { it.isLetter() } -> word
                else -> word.take(1) + word.drop(1).lowercase()
            }
        }
    }
}
