package app.kite.core

import kotlinx.coroutines.CancellationException
import java.net.URLDecoder

/** A lenient URL, split the way the iPhone app's URL/URLComponents split one. */
data class Link(val raw: String) {
    val scheme: String? = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(raw)?.groupValues?.get(1)
    private val afterScheme = scheme?.let { raw.substring(it.length + 1) } ?: raw
    private val hasAuthority = afterScheme.startsWith("//")
    private val authorityEnd = if (hasAuthority) afterScheme.indexOfAny(charArrayOf('/', '?', '#'), 2).let { if (it < 0) afterScheme.length else it } else 0
    val host: String? = if (hasAuthority) afterScheme.substring(2, authorityEnd).substringAfter('@').substringBefore(':').ifEmpty { null } else null
    private val rest = afterScheme.substring(authorityEnd)
    /** The path, percent-decoded. */
    val rawPath: String = rest.substringBefore('#').substringBefore('?')
    val path: String = pct(rawPath)
    val query: String? = rest.substringBefore('#').let { if (it.contains('?')) it.substringAfter('?') else null }

    /** The query's items, first of each name kept, values percent-decoded ('+' left as is). */
    val queryItems: Map<String, String>
        get() {
            val out = LinkedHashMap<String, String>()
            for (part in (query ?: return out).split('&')) {
                if (part.isEmpty()) continue
                val k = pct(part.substringBefore('='))
                if (!part.contains('=')) continue
                if (k !in out) out[k] = pct(part.substringAfter('='))
            }
            return out
        }

    val absolute: String get() = raw

    /** [loc] (a redirect's Location) resolved against this link. */
    fun resolve(loc: String): Link? = try {
        Link(java.net.URI(raw.replace(" ", "%20").replace("|", "%7C")).resolve(loc.replace(" ", "%20").replace("|", "%7C")).toString())
    } catch (e: Exception) {
        if (loc.startsWith("http")) Link(loc) else null
    }

    companion object {
        /** Percent-decoding that leaves '+' alone and bad escapes as they are. */
        fun pct(s: String): String {
            if (!s.contains('%')) return s
            return try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }
        }
    }
}

/**
 * Map links pasted into the search bar: Google Maps (full and short links), Waze, Apple Maps,
 * geo: links and plain "51.5, -0.12" coordinates. Short links are followed to the full link first.
 */
object MapLinks {
    sealed class Target {
        data class Spot(val at: LatLng, val name: String?) : Target()
        data class Search(val text: String, val near: LatLng?) : Target()
    }

    private val linkRe = Regex(
        "(https?://)?[A-Za-z0-9.-]*(google\\.[a-z.]+/maps|maps\\.google\\.[a-z.]+|goo\\.gl|share\\.google|waze\\.com|maps\\.apple\\.com)[^\\s]*",
        RegexOption.IGNORE_CASE,
    )

    /** The first link in [text] (share text often has the place's name before it), or null. */
    fun link(text: String): Link? {
        val t = text.trim()
        if (t.lowercase().startsWith("geo:")) return Link(t)
        val m = linkRe.find(t) ?: return null
        var s = m.value
        if (!s.lowercase().startsWith("http")) s = "https://$s"
        return Link(s)
    }

    fun looksLikeLink(text: String): Boolean = link(text) != null || coordinates(text) != null

    /** Plain coordinates typed or pasted: "51.50072, -0.12462" or "51.5 -0.12". */
    fun coordinates(text: String): LatLng? {
        val parts = text.trim().split(',', ' ').filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        // "52, -1.5" is fine with its comma; without one both need a decimal point, so "M27 5" isn't a place.
        if (!text.contains(',') && !parts.all { it.contains('.') }) return null
        return valid(lat, lng)
    }

    /** Where [text] points, following short links over the network. */
    suspend fun resolve(text: String): Target? {
        coordinates(text)?.let { return Target.Spot(it, null) }
        val url = link(text) ?: return null
        parse(url)?.let { return it }
        for (next in redirects(url)) parse(next)?.let { return it }
        return null
    }

    /** Where a long link points, with no network. */
    fun parse(url: Link): Target? {
        if (url.scheme?.lowercase() == "geo") return parseGeo(url.raw)
        val host = url.host?.lowercase() ?: ""
        val q = url.queryItems
        for (key in listOf("continue", "link", "url")) {
            val inner = q[key] ?: continue
            val u = Link(inner)
            if (u.host != null && u.raw != url.raw) parse(u)?.let { return it }
        }
        if (host.contains("waze.com")) return parseWaze(url, q)
        if (host.contains("maps.apple.com")) return parseApple(q)
        if (host.contains("google.")) return parseGoogle(url, q)
        return null
    }

    fun parseGoogle(url: Link, q: Map<String, String>): Target? {
        val path = Link.pct(url.path)
        val full = Link.pct(url.raw)
        val segments = path.split('/').filter { it.isNotEmpty() }
        var name: String? = null
        val pi = segments.indexOf("place")
        if (pi >= 0 && pi + 1 < segments.size && !segments[pi + 1].startsWith("@")) name = segments[pi + 1].replace("+", " ")
        // A directions link has a pin for each end: the destination is the last.
        match(full, "!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)", last = "dir" in segments)?.let { m ->
            valid(m.first, m.second)?.let { return Target.Spot(it, name?.let { n -> nameIfNotCoordinates(n) }) }
        }
        val at = match(path, "@(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)")?.let { valid(it.first, it.second) }
        for (key in listOf("q", "query", "daddr", "destination")) {
            val v = q[key]?.replace("+", " ")
            if (v.isNullOrEmpty()) continue
            (coordinates(v) ?: latLngPair(v))?.let { return Target.Spot(it, null) }
            return Target.Search(v, at ?: q["ll"]?.let { latLngPair(it) } ?: q["center"]?.let { latLngPair(it) })
        }
        val si = segments.indexOf("search")
        if (si >= 0 && si + 1 < segments.size && !segments[si + 1].startsWith("@")) {
            val v = segments[si + 1].replace("+", " ")
            (coordinates(v) ?: latLngPair(v))?.let { return Target.Spot(it, null) }
            return Target.Search(v, at)
        }
        val di = segments.indexOf("dir")
        if (di >= 0) {
            val last = segments.drop(di + 1).lastOrNull { !it.startsWith("@") && !it.startsWith("data=") && it.isNotEmpty() }
            if (last != null) {
                val v = last.replace("+", " ")
                (coordinates(v) ?: latLngPair(v))?.let { return Target.Spot(it, null) }
                return Target.Search(v, at)
            }
        }
        if (name != null && at != null) return Target.Spot(at, nameIfNotCoordinates(name))
        (q["ll"]?.let { latLngPair(it) } ?: q["center"]?.let { latLngPair(it) } ?: at)?.let { return Target.Spot(it, null) }
        return null
    }

    fun parseWaze(url: Link, q: Map<String, String>): Target? {
        for (key in listOf("ll", "latlng")) q[key]?.let { latLngPair(it) }?.let { return Target.Spot(it, q["q"]) }
        val to = q["to"] ?: q["from"]
        if (to != null && to.startsWith("ll.")) latLngPair(to.drop(3))?.let { return Target.Spot(it, null) }
        val parts = url.path.split('/').filter { it.isNotEmpty() }
        if (parts.size >= 2 && parts[parts.size - 2] == "ul" && parts.last().startsWith("h")) {
            geohash(parts.last().drop(1))?.let { return Target.Spot(it, null) }
        }
        q["q"]?.let { if (it.isNotEmpty()) return Target.Search(it, null) }
        return null
    }

    fun parseApple(q: Map<String, String>): Target? {
        // ll is the pin; sll is only where a search was made.
        val ll = q["ll"]?.let { latLngPair(it) }
        val sll = q["sll"]?.let { latLngPair(it) }
        // Directions: the destination, not wherever the map was.
        q["daddr"]?.takeIf { it.isNotEmpty() }?.let { d ->
            latLngPair(d)?.let { return Target.Spot(it, null) }
            return Target.Search(d, ll ?: sll)
        }
        (q["coordinate"]?.let { latLngPair(it) } ?: ll)?.let { return Target.Spot(it, q["q"] ?: q["name"]) }
        val v = q["q"] ?: q["address"]
        if (!v.isNullOrEmpty()) {
            latLngPair(v)?.let { return Target.Spot(it, null) }
            return Target.Search(v, sll)
        }
        sll?.let { return Target.Spot(it, null) }
        return null
    }

    fun parseGeo(s: String): Target? {
        val body = Link.pct(s.drop(4))
        val parts = body.split("?", limit = 2)
        val head = parts.firstOrNull()?.let { latLngPair(it.split(";").first()) }
        if (parts.size > 1) {
            val qv = parts[1].split("&").firstOrNull { it.startsWith("q=") }
            if (qv != null) {
                var v = qv.drop(2).replace("+", " ")
                var label: String? = null
                val open = v.indexOf('(')
                if (open >= 0 && v.endsWith(")")) {
                    label = v.substring(open + 1, v.length - 1)
                    v = v.substring(0, open)
                }
                latLngPair(v)?.let { return Target.Spot(it, label) }
                if (v.isNotEmpty()) return Target.Search(v, head?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) })
            }
        }
        if (head != null && !(head.lat == 0.0 && head.lng == 0.0)) return Target.Spot(head, null)
        return null
    }

    /** Decodes a geohash ("gcpvj0d6") to the middle of its box. */
    fun geohash(s: String): LatLng? {
        val alphabet = "0123456789bcdefghjkmnpqrstuvwxyz"
        var lat0 = -90.0; var lat1 = 90.0
        var lng0 = -180.0; var lng1 = 180.0
        var even = true
        if (s.length < 4) return null
        for (ch in s.lowercase()) {
            val v = alphabet.indexOf(ch)
            if (v < 0) return null
            for (bit in 4 downTo 0) {
                val on = (v shr bit) and 1 == 1
                if (even) {
                    val mid = (lng0 + lng1) / 2
                    if (on) lng0 = mid else lng1 = mid
                } else {
                    val mid = (lat0 + lat1) / 2
                    if (on) lat0 = mid else lat1 = mid
                }
                even = !even
            }
        }
        return LatLng((lat0 + lat1) / 2, (lng0 + lng1) / 2)
    }

    fun latLngPair(s: String): LatLng? {
        val parts = s.replace("%2C", ",").split(",").map { it.trim() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        return valid(lat, lng)
    }

    fun match(s: String, pattern: String, last: Boolean = false): Pair<Double, Double>? {
        val m = (if (last) Regex(pattern).findAll(s).lastOrNull() else Regex(pattern).find(s)) ?: return null
        val x = m.groupValues[1].toDoubleOrNull() ?: return null
        val y = m.groupValues[2].toDoubleOrNull() ?: return null
        return x to y
    }

    fun valid(lat: Double, lng: Double): LatLng? {
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) return null
        return LatLng(lat, lng)
    }

    fun nameIfNotCoordinates(n: String): String? = if (latLngPair(n) == null && n.isNotEmpty()) n else null

    private val noRedirects = Http.client(10, followRedirects = false)

    /** Each place a short link redirects to, in order, up to six hops. */
    suspend fun redirects(start: Link): List<Link> {
        val out = mutableListOf<Link>()
        var url = start
        for (i in 0 until 6) {
            val r = try {
                Http.get(url.raw, mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 16; Pixel 10 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36"), noRedirects)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { break }
            val loc = r.headers["Location"]
            if (loc != null) {
                val next = url.resolve(loc) ?: break
                out.add(next)
                if (parse(next) != null) break
                url = next
                continue
            }
            val html = String(r.body.copyOf(minOf(r.body.size, 200_000)), Charsets.UTF_8)
            Regex("https://www\\.google\\.[a-z.]+/maps/[^\"'\\s<>\\\\]+").find(html)?.let { out.add(Link(it.value.replace("&amp;", "&"))) }
            break
        }
        return out
    }
}
