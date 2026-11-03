package app.kite.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import java.io.IOException

class KiteError(message: String) : IOException(message) {
    companion object {
        fun http(code: Int) = KiteError("The server answered with error $code.")
        fun unexpected() = KiteError("The search answer came back in a shape Kite doesn't understand.")
    }
}

/**
 * Keyless Google Maps web search, the way Vela's Android extractor does it: warm a session once
 * (cookies), then call `/search?tbm=map` with Vela's `pb` and a desktop Chrome header set. No key
 * and no account. EU consent cookies are pre-seeded so no consent wall appears.
 */
class GoogleClient {
    companion object {
        val shared = GoogleClient()
        const val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"
        const val secChUa = "\"Not:A-Brand\";v=\"8\", \"Chromium\";v=\"156\", \"Google Chrome\";v=\"156\""
        const val searchEndpoint = "https://www.google.com/search?tbm=map&authuser=0&hl=en-GB&gl=gb"
        const val directionsEndpoint = "https://www.google.com/maps/preview/directions?authuser=0&hl=en-GB&gl=gb"
        const val suggestEndpoint = "https://www.google.com/s?tbm=map&gs_ri=maps&suggest=p&authuser=0&hl=en-GB&gl=gb"
        const val suggestPb = "!2i5!4m12!1m3!1d{SPAN}!2d{LNG}!3d{LAT}!2m3!1f0!2f0!3f0!3m2!1i1024!2i768!4f13.1" +
            "!7i20!10b1!12m6!1m2!18b1!30b1!2m2!1i203!2i100!19m4!1m3!1i1!2i1!3i1!20m1!1e1"
        const val sessionWarmUrl = "https://www.google.com/maps?hl=en-GB&gl=gb"
        private const val acceptLanguage = "en-GB,en;q=0.9"
    }

    private val cookies = Http.MemoryCookies(listOf("SOCS" to "CAESHAgBEhIaAB", "CONSENT" to "YES+").map { (n, v) ->
        Cookie.Builder().domain("google.com").path("/").name(n).value(v).secure()
            .expiresAt(System.currentTimeMillis() + 365L * 86_400_000).build()
    })
    private val client = Http.client(15, cookies)
    private val warmLock = Mutex()
    @Volatile private var warmed = false
    private var ech = 0

    /** Search Google around [center]; a full first page fetches pages two and three too, as Vela does. */
    suspend fun search(query: String, center: LatLng, spanMeters: Double?, pages: Int = 3): List<Place> = coroutineScope {
        warm()
        val first = page(query, center, spanMeters, 0)
        if (pages <= 1 || first.size < 18) return@coroutineScope first
        val more = (1 until pages).map { i -> async { runCatching { page(query, center, spanMeters, 20 * i) }.getOrDefault(emptyList()) } }.awaitAll()
        val seen = first.map { it.id }.toMutableSet()
        first + more.flatten().filter { seen.add(it.id) }
    }

    private suspend fun page(query: String, center: LatLng, spanMeters: Double?, offset: Int): List<Place> {
        val pb = SearchPb.build(query, center, spanMeters, offset)
        val body = get(searchEndpoint + "&q=" + enc(query) + "&pb=" + enc(pb), "https://www.google.com/", true)
        val root = GoogleJSON.parse(body) ?: throw KiteError.unexpected()
        return SearchParser.parse(root, center)
    }

    /** Google's autocomplete while typing: places with a position, and suggested searches. */
    suspend fun suggest(query: String, near: LatLng, spanMeters: Double = 20_000.0): SuggestParser.Result {
        warm()
        ech += 1
        val span = spanMeters.coerceIn(2_000.0, 500_000.0)
        val pb = suggestPb.replace("{SPAN}", span.toString()).replace("{LNG}", near.lng.plain()).replace("{LAT}", near.lat.plain())
        val body = get(suggestEndpoint + "&pb=" + enc(pb) + "&q=" + enc(query) + "&tch=1&ech=$ech", "https://www.google.com/", true)
        return SuggestParser.parse(body, near)
    }

    /** Driving, cycling or walking routes with live traffic times, alternates first-to-last. */
    suspend fun directions(
        origin: LatLng, destination: LatLng, mode: TravelMode,
        avoidHighways: Boolean = false, avoidTolls: Boolean = false, via: List<LatLng> = emptyList(),
    ): List<Route> {
        val body = directionsBody(origin, destination, mode, avoidHighways, avoidTolls, via)
        val root = GoogleJSON.parse(body) ?: throw KiteError.unexpected()
        return DirectionsParser.parse(root, mode)
    }

    suspend fun directionsBody(
        origin: LatLng, destination: LatLng, mode: TravelMode,
        avoidHighways: Boolean = false, avoidTolls: Boolean = false, via: List<LatLng> = emptyList(),
    ): String {
        warm()
        val pb = DirectionsPb.build(origin, destination, mode, avoidHighways, avoidTolls, via)
        return get(directionsEndpoint + "&pb=" + enc(pb), "https://www.google.com/", true)
    }

    private suspend fun warm() = warmLock.withLock {
        if (warmed) return@withLock
        // Only counts once Google answered: opened with no signal, try again on the next search.
        try {
            get(sessionWarmUrl, null, false)
            warmed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    private suspend fun get(url: String, referer: String?, xhr: Boolean): String {
        val h = mutableMapOf(
            "User-Agent" to userAgent,
            "Accept" to if (xhr) "*/*" else "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to acceptLanguage,
            "Sec-CH-UA" to secChUa,
            "Sec-CH-UA-Mobile" to "?0",
            "Sec-CH-UA-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to if (xhr) "empty" else "document",
            "Sec-Fetch-Mode" to if (xhr) "cors" else "navigate",
            "Sec-Fetch-Site" to if (xhr) "same-origin" else "none",
        )
        if (referer != null) h["Referer"] = referer
        val r = Http.get(url, h, client)
        if (!r.ok) throw KiteError.http(r.code)
        return r.text
    }

    /** Percent-encoding like the iPhone app's: everything but unreserved and a few safe marks. */
    private fun enc(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-._~!$'()*,;:@/")) sb.append(ch)
            else sb.append(String.format("%%%02X", c))
        }
        return sb.toString()
    }
}

/** Builds the `pb` parameter for Google's `/search?tbm=map` (port of Vela's SearchPb). */
object SearchPb {
    const val minSpanMeters = 1_000.0

    const val template = "!1s{QUERY}!4m8!1m3!1d25229.167291701906!2d{LNG}!3d{LAT}!3m2!1i1024!2i768!4f13.1!7i20!10b1!" +
        "12m52!1m5!18b1!30b1!31m1!1b1!34e1!2m4!5m1!6e2!20e3!39b1!6m25!32i1!49b1!63m0!66b1!85b1!114b" +
        "1!149b1!206b1!209b1!212b1!216b1!222b1!223b1!232b1!234b1!235b1!244b1!246b1!250b1!253b1!260b" +
        "1!266b1!273b1!281b1!291m0!10b1!12b1!13b1!14b1!16b1!17m1!3e1!20m3!5e2!6b1!14b1!46m1!1b0!96b" +
        "1!99b1!19m4!2m3!1i360!2i120!4i8!20m57!2m2!1i203!2i100!3m2!2i4!5b1!6m6!1m2!1i86!2i86!1m2!1i" +
        "408!2i240!7m33!1m3!1e1!2b0!3e3!1m3!1e2!2b1!3e2!1m3!1e2!2b0!3e3!1m3!1e8!2b0!3e3!1m3!1e10!2b" +
        "0!3e3!1m3!1e10!2b1!3e2!1m3!1e10!2b0!3e4!1m3!1e9!2b1!3e2!2b1!9b0!15m8!1m7!1m2!1m1!1e2!2m2!1" +
        "i195!2i195!3i20!15i9937!24m107!1m25!13m9!2b1!3b1!4b1!6i1!8b1!9b1!14b1!20b1!25b1!18m14!3b1!" +
        "4b1!5b1!6b1!13b1!14b1!17b1!21b1!22b1!32b1!33m1!1b1!34b1!36e2!10m1!8e3!11m1!3e1!17b1!20m2!1" +
        "e3!1e6!24b1!25b1!26b1!27b1!29b1!30m1!2b1!36b1!37b1!39m3!2m2!2i1!3i1!43b1!52b1!54m1!1b1!55b" +
        "1!56m1!1b1!61m2!1m1!1e1!65m5!3m4!1m3!1m2!1i224!2i298!72m22!1m8!2b1!5b1!7b1!12m4!1b1!2b1!4m" +
        "1!1e1!4b1!8m10!1m6!4m1!1e1!4m1!1e3!4m1!1e4!3sother_user_google_review_posts__and__hotel_an" +
        "d_vr_partner_review_posts!6m1!1e1!9b1!89b1!90m2!1m1!1e2!98m3!1b1!2b1!3b1!103b1!113b1!114m3" +
        "!1b1!2m1!1b1!117b1!122m1!1b1!126b1!127b1!128m1!1b0!26m4!2m3!1i80!2i92!4i8!30m28!1m6!1m2!1i" +
        "0!2i0!2m2!1i530!2i768!1m6!1m2!1i974!2i0!2m2!1i1024!2i768!1m6!1m2!1i0!2i0!2m2!1i1024!2i20!1" +
        "m6!1m2!1i0!2i748!2m2!1i1024!2i768!34m19!2b1!3b1!4b1!6b1!8m6!1b1!3b1!4b1!5b1!6b1!7b1!9b1!12" +
        "b1!14b1!20b1!23b1!25b1!26b1!31b1!37m1!1e81!42b1!49m10!3b1!6m2!1b1!2b1!7m2!1e3!2b1!8b1!9b1!" +
        "10e2!50m3!2e2!3m1!3b1!61b1!67m5!7b1!10b1!14b1!15m1!1b0!69i782!77b1"

    fun build(query: String, center: LatLng, spanMeters: Double? = null, offset: Int = 0): String {
        var pb = template.replace("{QUERY}", query.replace("!", " ").trim())
            .replace("{LNG}", center.lng.plain()).replace("{LAT}", center.lat.plain())
        if (spanMeters != null) {
            Regex("!1d[0-9.]+").find(pb)?.let { pb = pb.replaceRange(it.range, "!1d${spanMeters.coerceIn(minSpanMeters, 500_000.0)}") }
        }
        if (offset > 0) {
            val i = pb.indexOf("!7i20")
            if (i >= 0) pb = pb.substring(0, i) + "!7i20!8i$offset" + pb.substring(i + 5)
        }
        return pb
    }
}

/** Reads Google's `/search?tbm=map` answer (port of Vela's SearchParser). */
object SearchParser {
    val paths: Map<String, List<Int>> = mapOf(
        "results" to listOf(64),
        "single" to listOf(0, 1, 0, 14),
        "atThisPlace" to listOf(0, 1, 0, 14, 68),
        "name" to listOf(1, 11),
        "lat" to listOf(1, 9, 2),
        "lng" to listOf(1, 9, 3),
        "address" to listOf(1, 39),
        "addressComponents" to listOf(1, 2),
        "category" to listOf(1, 13, 0),
        "rating" to listOf(1, 4, 7),
        "reviewCount" to listOf(1, 4, 8),
        "priceText" to listOf(1, 4, 2),
        "website" to listOf(1, 7, 0),
        "phone" to listOf(1, 178, 0, 0),
        "featureId" to listOf(1, 10),
        "placeId" to listOf(1, 78),
        "photos" to listOf(1, 72, 0),
        "featuredReview" to listOf(1, 142, 1, 0, 1, 0, 0),
        "about" to listOf(1, 100, 1),
        "editorialSummary" to listOf(1, 32, 1, 1),
        "openStatus" to listOf(1, 203, 1, 8, 0),
        "statusRich" to listOf(1, 203, 1, 4, 0),
        "status118" to listOf(1, 118, 0, 3, 1, 4, 0),
        "hours203" to listOf(1, 203, 0),
        "hours118" to listOf(1, 118, 0, 3, 0),
        "closedFlag" to listOf(1, 23),
    )

    fun parse(root: Any?, near: LatLng?): List<Place> {
        val entries: List<Any?> = arr(atPath(root, paths["results"]!!))?.takeIf { it.isNotEmpty() }
            ?: atThisPlaceEntries(root) ?: singleEntry(root) ?: emptyList()
        val seen = HashSet<String>()
        return entries.mapNotNull { toPlace(it, near) }.filter { seen.add(it.featureId ?: it.id) }
    }

    private fun singleEntry(root: Any?): List<Any?>? {
        val node = atPath(root, paths["single"]!!) ?: return null
        val entry = listOf(null, node)
        return if (gstr(field(entry, "name")) == null) null else listOf(entry)
    }

    private fun atThisPlaceEntries(root: Any?): List<Any?>? {
        val list = arr(atPath(root, paths["atThisPlace"]!!)) ?: return null
        val entries = list.mapNotNull { e ->
            val node = at(e, 0) ?: return@mapNotNull null
            val entry = listOf(null, node)
            if (gstr(field(entry, "name")) == null) null else entry
        }
        return entries.ifEmpty { null }
    }

    private fun field(entry: Any?, key: String) = atPath(entry, paths[key]!!)

    fun toPlace(entry: Any?, near: LatLng?): Place? {
        val name = gstr(field(entry, "name")) ?: return null
        val lat = gdbl(field(entry, "lat")) ?: return null
        val lng = gdbl(field(entry, "lng")) ?: return null
        val loc = LatLng(lat, lng)
        val p = Place("g:" + (gstr(field(entry, "featureId")) ?: "$name|${(lat * 1e4).toInt()}|${(lng * 1e4).toInt()}"), name, loc, "google")
        p.category = gstr(field(entry, "category"))
        val components = arr(field(entry, "addressComponents"))?.mapNotNull { gstr(it) }?.filter { !it.equals(name, ignoreCase = true) }
        val joined = components?.joinToString(", ")?.ifEmpty { null }
        p.address = (gstr(field(entry, "address")) ?: joined)?.let { stripNamePrefix(it, name) }
        p.rating = gdbl(field(entry, "rating"))
        p.reviewCount = gint(field(entry, "reviewCount"))
        p.priceText = gstr(field(entry, "priceText"))
        p.website = gstr(field(entry, "website"))
        p.phone = gstr(field(entry, "phone"))
        val rich = gstr(field(entry, "statusRich"))
        val s118 = gstr(field(entry, "status118"))
        val open = gstr(field(entry, "openStatus"))
        val status = rich ?: s118 ?: open
        p.statusText = status
        p.openNow = openNow(status)
        p.permanentlyClosed = gint(field(entry, "closedFlag")) == 1 || listOf(rich, s118, open).any { it?.contains("Permanently", ignoreCase = true) == true }
        val h203 = readHours(field(entry, "hours203"))
        val h118 = readHours(field(entry, "hours118"))
        p.hours = if (rich == null && s118 != null) h118.ifEmpty { h203 } else h203.ifEmpty { h118 }
        p.photoUrls = photos(entry)
        p.featuredReview = gstr(field(entry, "featuredReview"))?.trim('"', '“', '”', ' ', '\n')?.nilIfBlank()
        p.editorialSummary = gstr(field(entry, "editorialSummary"))?.trim()?.nilIfBlank()
        p.about = (arr(field(entry, "about")) ?: emptyList()).mapNotNull { s ->
            val title = gstr(at(s, 1)) ?: return@mapNotNull null
            val items = (arr(at(s, 2)) ?: emptyList()).mapNotNull { gstr(at(it, 1))?.nilIfBlank() }
            if (items.isEmpty()) null else AboutSection(title, items)
        }
        p.featureId = gstr(field(entry, "featureId"))
        p.placeId = gstr(field(entry, "placeId"))
        p.distanceMeters = near?.distanceTo(loc)
        return p
    }

    /** Open or closed from the status text; closed words are tested first ("Opens 5 AM" means closed). */
    fun openNow(status: String?): Boolean? {
        val s = status?.trim()
        if (s.isNullOrEmpty()) return null
        if (listOf("Closed", "Opens", "Opening", "Temporarily", "Permanently").any { s.startsWith(it) }) return false
        if (listOf("Open", "Closes", "Closing").any { s.startsWith(it) }) return true
        return null
    }

    /** Seven days starting today; each day is name `[0]` and time ranges `[3][i][0]`. */
    fun readHours(days: Any?): List<String> = (arr(days) ?: emptyList()).mapNotNull { day ->
        val name = gstr(at(day, 0)) ?: return@mapNotNull null
        val ranges = (arr(at(day, 3)) ?: emptyList()).mapNotNull { gstr(at(it, 0))?.nilIfBlank() }
        if (ranges.isEmpty()) return@mapNotNull null
        val note = gstr(at(day, 6, 1))?.nilIfBlank()
        "$name: ${ranges.joinToString(", ")}" + (note?.let { " · $it" } ?: "")
    }

    private fun photos(entry: Any?): List<String> {
        val out = mutableListOf<String>()
        fun add(u: String?) {
            if (u == null || !u.contains("googleusercontent")) return
            val sized = u.replace(Regex("=w\\d+-h\\d+.*$"), "=w800-h600")
            if (sized !in out) out.add(sized)
        }
        (arr(field(entry, "photos")) ?: emptyList()).forEach { add(gstr(at(it, 6, 0))) }
        (arr(at(entry, 1, 204, 0)) ?: emptyList()).forEach { add(gstr(at(it, 1, 2, 0, 0))) }
        return out.take(12)
    }

    /** "Safeway, 1451 W Covell Blvd" reads the name twice under the title; drop the prefix. */
    fun stripNamePrefix(addr: String, name: String): String {
        if (name.isEmpty() || !addr.lowercase().startsWith(name.lowercase())) return addr
        val rest = addr.substring(name.length)
        rest.firstOrNull()?.let { if (it.isLetterOrDigit()) return addr }
        val trimmed = rest.trimStart(' ')
        trimmed.firstOrNull()?.let { if (!it.isDigit() && it !in ",·–-") return addr }
        val out = trimmed.trimStart(',', '·', '–', '-', ' ')
        return out.ifEmpty { addr }
    }
}

/** Google Maps' search-as-you-type answer, ported from Vela. */
object SuggestParser {
    data class Result(val places: List<Place> = emptyList(), val queries: List<String> = emptyList())

    /** The new list, with every row that's still there kept on the line it was on. */
    fun <T> steady(new: List<T>, old: List<T>, key: (T) -> String): List<T> {
        val newKeys = new.map(key).toSet()
        val slots = MutableList<T?>(new.size) { null }
        val placed = HashSet<String>()
        for ((i, o) in old.withIndex()) {
            if (i >= new.size || key(o) !in newKeys) continue
            slots[i] = new.first { key(it) == key(o) }
            placed.add(key(o))
        }
        val rest = new.filter { key(it) !in placed }.iterator()
        return slots.mapNotNull { it ?: if (rest.hasNext()) rest.next() else null }
    }

    private val fidRe = Regex("^0x[0-9a-f]+:0x[0-9a-f]+$")

    fun parse(body: String, near: LatLng?): Result {
        val payload = unwrap(body) ?: return Result()
        val root = GoogleJSON.parse(payload) ?: return Result()
        val rows = arr(at(root, 0, 1)) ?: return Result()
        val places = mutableListOf<Place>()
        val queries = mutableListOf<String>()
        for (row in rows) {
            val block = (row as? List<*>)?.firstOrNull { el -> ((el as? List<*>)?.firstOrNull() as? List<*>)?.firstOrNull() is String } ?: continue
            val title = gstr(at(block, 0, 0))?.nilIfBlank() ?: continue
            val primary = gstr(at(block, 1, 0))?.nilIfBlank() ?: title
            val secondary = gstr(at(block, 2, 0))?.nilIfBlank()
            val lat = gdbl(at(block, 11, 2)) ?: gdbl(at(block, 13, 0, 3, 2))
            val lng = gdbl(at(block, 11, 3)) ?: gdbl(at(block, 13, 0, 3, 3))
            if (lat == null || lng == null) { queries.add(title); continue }
            val fid = gstr(at(block, 13, 0, 0))?.takeIf { fidRe.matches(it) }
            val p = Place(fid ?: String.format(java.util.Locale.US, "suggest:%.5f,%.5f", lat, lng), primary, LatLng(lat, lng), "google")
            p.address = if (secondary == primary) null else secondary
            p.featureId = fid
            if (near != null) p.distanceMeters = near.distanceTo(p.location)
            places.add(p)
        }
        return Result(closeMatchesFirst(places, gstr(at(root, 0, 0)) ?: ""), queries)
    }

    /** Places whose name is close to what was typed come first, keeping Google's order otherwise. */
    fun closeMatchesFirst(places: List<Place>, query: String): List<Place> {
        val q = words(query)
        if (q.isEmpty()) return places
        fun tier(p: Place): Int {
            val n = words(p.name)
            val hits = q.withIndex().count { (i, w) -> w in n || (i == q.size - 1 && n.any { it.startsWith(w) }) }
            if (hits == q.size && hits == n.size) return 0
            val share = hits.toDouble() / q.size
            return if (share >= 0.6) 1 else if (share > 0) 2 else 3
        }
        return places.withIndex().sortedWith(compareBy({ tier(it.value) }, { it.index })).map { it.value }
    }

    fun words(s: String): List<String> =
        Place.fold(s).replace("'", "").replace("’", "")
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in setOf("the", "and", "of") }

    /** The `d` string of the first `{...}` object in the body. */
    fun unwrap(body: String): String? {
        val start = body.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        var end = -1
        var i = start
        while (i < body.length) {
            val c = body[i]
            if (inString) {
                if (escape) escape = false else if (c == '\\') escape = true else if (c == '"') inString = false
            } else if (c == '"') inString = true
            else if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) { end = i; break }
            }
            i++
        }
        if (end < 0) return null
        return Json.obj(body.substring(start, end + 1))?.get("d") as? String
    }
}
