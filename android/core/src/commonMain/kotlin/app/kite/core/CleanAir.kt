package app.kite.core

/**
 * Britain's clean air zones that cars can pay or be fined in (GitHub #75): London's ULEZ (all of
 * Greater London), Birmingham, Bristol, Oxford's zero emission zone, and Scotland's low emission
 * zones. The shapes are from OpenStreetMap, made simpler, and kept in the app (they rarely change).
 * Zones that only charge buses, lorries and taxis (Bath, Sheffield, Newcastle, Portsmouth, Bradford)
 * aren't listed, since a car never pays there. No prices: they change, and the point is only to know.
 */
object CleanAirZones {
    class Zone(
        val name: String,
        val note: String,
        /** Charged even when the car meets the usual rules (Oxford: only electric cars are free). */
        val everyCar: Boolean = false,
        /** How far inside the edge counts: the road round a zone is usually free. */
        val depth: Double = 100.0,
        flat: DoubleArray,
    ) {
        val ring: List<LatLng> = buildList {
            var i = 0
            while (i + 1 < flat.size) { add(LatLng(flat[i], flat[i + 1])); i += 2 }
            if (isNotEmpty() && first() != last()) add(first())
        }
        private val minLat = ring.minOfOrNull { it.lat } ?: 0.0
        private val maxLat = ring.maxOfOrNull { it.lat } ?: 0.0
        private val minLng = ring.minOfOrNull { it.lng } ?: 0.0
        private val maxLng = ring.maxOfOrNull { it.lng } ?: 0.0

        fun overlaps(aLat: Double, bLat: Double, aLng: Double, bLng: Double) = maxLat > aLat && minLat < bLat && maxLng > aLng && minLng < bLng

        fun contains(p: LatLng): Boolean {
            if (ring.size < 4 || p.lat <= minLat || p.lat >= maxLat || p.lng <= minLng || p.lng >= maxLng) return false
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val a = ring[i]; val b = ring[j]
                if ((a.lat > p.lat) != (b.lat > p.lat) && p.lng < (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng) inside = !inside
                j = i
            }
            return inside && Geo.project(p, ring).offset > depth
        }
    }

    /**
     * The zones a route goes into. With [cleanCar] ("My car meets the rules" in Settings) only those
     * that charge every car are left. Empty for nearly every route.
     */
    fun on(line: List<LatLng>, cleanCar: Boolean): List<RoadCharge> {
        if (line.size < 2) return emptyList()
        val minLat = line.minOf { it.lat }; val maxLat = line.maxOf { it.lat }
        val minLng = line.minOf { it.lng }; val maxLng = line.maxOf { it.lng }
        return zones.filter { (!cleanCar || it.everyCar) && it.overlaps(minLat, maxLat, minLng, maxLng) && line.any { p -> it.contains(p) } }
            .map { RoadCharge(it.name, it.note, zone = true) }
    }

    const val older = "Older cars pay a daily charge: most petrol cars from before 2006 and diesels from before late 2015. Pay online."
    const val scotland = "Older cars aren't allowed in, and get a fine: most petrol cars from before 2006 and diesels from before late 2015. No barriers."

    val zones: List<Zone> by lazy { CleanAirShapes.all }
}
