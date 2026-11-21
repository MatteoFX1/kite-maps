package app.kite.core

/** A car park near where you're going, from the open map. */
data class CarPark(
    val id: String, val name: String?, val location: LatLng,
    /** Metres from where you're going. */
    val meters: Double,
    /** True when the map says you pay, false when it says free; null when it doesn't say. */
    val fee: Boolean?, val spaces: Int?,
    /** Underground, multi-storey or open air. */
    val kind: String?,
)

/**
 * Help at the other end (GitHub #63): car parks near the destination, and a big place's main entrance
 * so the drive ends at the door rather than wherever the map's pin sits in a huge building.
 * The same as the iPhone's ArrivalHelp.
 */
object ArrivalHelp {
    /** Places big enough for the pin to be far from the way in. */
    fun isBigPlace(category: String?): Boolean {
        val c = (category ?: "").lowercase()
        return listOf("hospital", "shopping", "mall", "station", "airport", "terminal", "university", "college", "stadium", "arena",
            "retail park", "outlet", "museum", "zoo", "theme park", "leisure centre", "exhibition", "conference", "campus").any { c.contains(it) }
    }

    fun parkingQuery(near: LatLng, radius: Int = 500) =
        "[out:json][timeout:25];nwr[\"amenity\"=\"parking\"](around:$radius,${near.lat},${near.lng});out center tags 60;"

    fun entranceQuery(near: LatLng, radius: Int = 350) =
        "[out:json][timeout:25];node[\"entrance\"=\"main\"](around:$radius,${near.lat},${near.lng});out 30;"

    /** The car parks anyone can use, nearest first: not private, not just a few spaces along a street. */
    fun parseParking(data: ByteArray, near: LatLng, limit: Int = 8): List<CarPark>? {
        val elements = Json.obj(data)?.get("elements").asMaps() ?: return null
        val out = mutableListOf<CarPark>()
        for (e in elements) {
            @Suppress("UNCHECKED_CAST") val tags = (e["tags"] as? Map<String, Any?>)?.mapValues { it.value as? String } ?: emptyMap()
            val access = tags["access"]?.lowercase() ?: "yes"
            if (access in setOf("private", "no", "permit", "residents", "delivery")) continue
            val kind = tags["parking"]?.lowercase()
            if (kind != null && kind in setOf("street_side", "lane", "on_kerb", "half_on_kerb", "layby", "garage_boxes", "carports")) continue
            val center = e["center"].asMap()
            val lat = (e["lat"] ?: center?.get("lat")).num() ?: continue
            val lng = (e["lon"] ?: center?.get("lon")).num() ?: continue
            val at = LatLng(lat, lng)
            val fee = when (tags["fee"]?.lowercase()) { "yes", "true" -> true; "no", "false" -> false; else -> null }
            val id = "${e["type"] ?: "x"}${e["id"].num()?.toLong() ?: 0}"
            out.add(CarPark(id, tags["name"], at, at.distanceTo(near), fee, tags["capacity"]?.toIntOrNull(),
                when (kind) { "underground" -> "Underground"; "multi-storey" -> "Multi-storey"; else -> null }))
        }
        // Small unnamed ones (a few spaces behind a shop) last: a named or big car park is the useful answer.
        return out.sortedBy { it.meters + if (it.name == null && (it.spaces ?: 0) < 20) 250.0 else 0.0 }.take(limit)
    }

    /** The main entrance nearest the pin, if there's one at least 40 m from it (any closer and the pin is fine). */
    fun parseEntrance(data: ByteArray, near: LatLng): LatLng? {
        val elements = Json.obj(data)?.get("elements").asMaps() ?: return null
        val doors = elements.mapNotNull { e ->
            @Suppress("UNCHECKED_CAST") val tags = e["tags"] as? Map<String, Any?> ?: emptyMap()
            if ((tags["access"] as? String)?.lowercase() in setOf("private", "no")) return@mapNotNull null
            val lat = e["lat"].num() ?: return@mapNotNull null
            val lng = e["lon"].num() ?: return@mapNotNull null
            LatLng(lat, lng)
        }
        val door = doors.minByOrNull { it.distanceTo(near) } ?: return null
        return if (door.distanceTo(near) >= 40) door else null
    }

    suspend fun parking(near: LatLng): List<CarPark>? = Overpass.query(parkingQuery(near))?.let { parseParking(it, near) }

    suspend fun mainEntrance(near: LatLng): LatLng? = Overpass.query(entranceQuery(near))?.let { parseEntrance(it, near) }
}
