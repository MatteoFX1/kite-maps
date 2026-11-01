package app.kite.core

import kotlinx.coroutines.CancellationException
import java.util.Locale

/** Photon, the OpenStreetMap geocoder: names, cities and addresses (not categories). Used when Google fails. */
object Photon {
    const val base = "https://photon.komoot.io/api/"
    private val langs = setOf("en", "de", "fr", "it")
    private const val UA = "KiteMaps-Android/0.7 (+https://github.com/matteofx1/kite-maps)"

    suspend fun search(query: String, near: LatLng?, limit: Int = 20): List<Place> {
        var url = base + "?q=" + urlEnc(query) + "&limit=$limit"
        if (near != null) url += "&lat=${near.lat}&lon=${near.lng}"
        val lang = Locale.getDefault().language
        url += "&lang=" + if (lang in langs) lang else "en"
        return parse(Http.get(url, mapOf("User-Agent" to UA), Http.client(15)).body, near)
    }

    /** The address at a point, for a dropped pin. */
    suspend fun reverse(p: LatLng): Place? {
        val r = Http.getOrNull("https://photon.komoot.io/reverse?lat=${p.lat}&lon=${p.lng}&limit=1", mapOf("User-Agent" to UA), Http.client(10)) ?: return null
        val place = parse(r.body, null).firstOrNull() ?: return null
        place.location = p
        place.id = "pin:${p.lat},${p.lng}"
        return place
    }

    fun parse(data: ByteArray, near: LatLng?): List<Place> {
        val features = Json.obj(data)?.get("features").asMaps() ?: return emptyList()
        return features.mapNotNull { f ->
            val coords = f["geometry"].asMap()?.get("coordinates").asList()?.mapNotNull { it.num() } ?: return@mapNotNull null
            if (coords.size < 2) return@mapNotNull null
            val p = f["properties"].asMap() ?: return@mapNotNull null
            val loc = LatLng(coords[1], coords[0])
            fun s(k: String) = p[k] as? String
            val street = listOfNotNull(s("housenumber"), s("street")).joinToString(" ").nilIfBlank()
            val locality = listOfNotNull(s("city") ?: s("district"), s("state"), s("postcode")).joinToString(", ").nilIfBlank()
            val named = s("name") != null && s("osm_key") != "highway" && !(s("osm_key") == "place" && s("osm_value") == "house")
            val title = (if (named) s("name") else (street ?: s("name"))) ?: return@mapNotNull null
            val osmId = p["osm_id"].long()?.toString() ?: "${loc.lat},${loc.lng}"
            val place = Place("osm:${s("osm_type") ?: ""}$osmId", title, loc, "osm")
            place.category = if (named) s("osm_value")?.replace("_", " ")?.capitalizedFirst() else "Address"
            place.address = listOfNotNull(if (named) street else null, locality, s("country")).joinToString(", ").nilIfBlank()
            place.distanceMeters = near?.distanceTo(loc)
            place
        }
    }
}

/** British postcodes ("SW1A 1AA") from postcodes.io: free, keyless, open data. */
object Postcodes {
    private val re = Regex("^[A-Z]{1,2}[0-9][A-Z0-9]?[0-9][A-Z]{2}$")

    /** The postcode in [query], normalized ("SW1A 1AA"), or null. */
    fun normalize(query: String): String? {
        val s = query.uppercase().filter { !it.isWhitespace() }
        if (!re.matches(s)) return null
        return s.dropLast(3) + " " + s.takeLast(3)
    }

    suspend fun lookup(query: String): Place? {
        val code = normalize(query) ?: return null
        val r = Http.getOrNull("https://api.postcodes.io/postcodes/" + code.replace(" ", "%20"), client = Http.client(10)) ?: return null
        return parse(r.body, code)
    }

    fun parse(data: ByteArray, code: String): Place? {
        val res = Json.obj(data)?.get("result").asMap() ?: return null
        val lat = res["latitude"].num() ?: return null
        val lng = res["longitude"].num() ?: return null
        val p = Place("postcode:$code", res["postcode"] as? String ?: code, LatLng(lat, lng), "postcodes.io")
        p.address = listOf(res["admin_ward"], res["admin_district"], res["country"]).mapNotNull { it as? String }.joinToString(", ")
        p.category = "Postcode"
        return p
    }
}

/** What the app calls: Google first (the full place cards), OpenStreetMap when Google fails. */
object PlaceSearch {
    data class Result(val places: List<Place>, val googleError: String? = null)

    const val nearMeters = 100_000.0
    const val farMeters = 300_000.0

    suspend fun search(query: String, center: LatLng, spanMeters: Double?, useGoogle: Boolean = true): Result {
        val q = query.trim()
        if (q.isEmpty()) return Result(emptyList())
        var googleError: String? = null
        if (useGoogle) {
            try {
                var places = GoogleClient.shared.search(q, center, spanMeters)
                if (places.isNotEmpty()) {
                    places = nearFirst(places, center)
                    val top = places.first()
                    if (top.location.distanceTo(center) > farMeters) {
                        val osm = runCatching { Photon.search(q, center) }.getOrDefault(emptyList())
                        val near = osm.filter { it.location.distanceTo(center) < nearMeters }
                        if (near.isNotEmpty()) return Result(near + places)
                    }
                    return Result(places)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                googleError = e.message
            }
        }
        Postcodes.lookup(q)?.let { return Result(listOf(it), googleError) }
        val osm = runCatching { Photon.search(q, center) }.getOrDefault(emptyList())
        return Result(osm, googleError)
    }

    /** Results within 100 km keep Google's order but go ahead of any more than 300 km away. */
    fun nearFirst(places: List<Place>, center: LatLng): List<Place> {
        val top = places.firstOrNull() ?: return places
        if (top.location.distanceTo(center) <= farMeters || places.none { it.location.distanceTo(center) < nearMeters }) return places
        val near = places.filter { it.location.distanceTo(center) <= farMeters }
        return near + places.filter { it.location.distanceTo(center) > farMeters }
    }
}
