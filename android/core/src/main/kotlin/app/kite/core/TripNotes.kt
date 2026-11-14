package app.kite.core

/**
 * A place that shuts before (or just as) you'd get there. Kite already knows both halves, the
 * place's "Open · Closes 5 pm" line and the arrival time, and this puts them together.
 */
object ClosingCheck {
    /** [closes]: the closing time as the place's own line writes it. [before]: already shut on arriving (else it shuts within 15 minutes after). */
    data class Warning(val closes: String, val before: Boolean)

    private val re = Regex("""closes(?:\s+soon)?[^0-9a-z]*(?:at\s+)?((\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?|midnight|noon)""", RegexOption.IGNORE_CASE)

    /** The closing time in a status line like "Open · Closes 10 pm" or "Closes soon · 5:30 pm": its words, and minutes after midnight. */
    fun closing(status: String?): Pair<String, Int>? {
        val m = re.find(status ?: return null) ?: return null
        val text = m.groupValues[1].trim()
        when (text.lowercase()) {
            "midnight" -> return text to 24 * 60
            "noon" -> return text to 12 * 60
        }
        var h = m.groupValues[2].toIntOrNull() ?: return null
        val min = m.groupValues[3].toIntOrNull() ?: 0
        val ampm = m.groupValues[4]
        if (ampm.isNotEmpty()) {
            if (h < 1 || h > 12) return null
            h = h % 12 + if (ampm.lowercase().startsWith("p")) 12 else 0
        }
        if (h > 24 || min >= 60) return null
        return text to h * 60 + min
    }

    /**
     * [nowMinutes] is the time now in minutes after midnight, [travelSeconds] how long the trip takes.
     * Null when the place has no closing time, or you'd be there with more than 15 minutes to spare.
     */
    fun warning(status: String?, nowMinutes: Int, travelSeconds: Double): Warning? {
        val c = closing(status) ?: return null
        var close = c.second
        // "Closes 1 am" read at 10 in the evening is tomorrow's 1 am.
        if (close <= nowMinutes) close += 24 * 60
        val arrive = nowMinutes + travelSeconds / 60
        if (arrive >= close) return Warning(c.first, true)
        if (arrive > close - 15) return Warning(c.first, false)
        return null
    }
}

/** A charge on the way that has no barrier to remind you: you pay online afterwards, or get a fine. */
data class RoadCharge(val name: String, val note: String)

/**
 * Britain's pay-later road charges. A short fixed list, not looked up live: each is a spot in the
 * middle of a river crossing (a route within a few hundred metres of it can only be on the
 * crossing), or for London's Congestion Charge the area inside the Inner Ring Road.
 * No prices: they change every year, and the point is only that you know to pay.
 */
object RoadCharges {
    private class Crossing(val name: String, val note: String, val at: List<LatLng>, val radius: Double)

    private val crossings = listOf(
        Crossing("Dartford Crossing", "No barriers. Charged 6 am to 10 pm. Pay the Dart Charge online by midnight the day after.",
            listOf(LatLng(51.4647, 0.2586)), 450.0),
        Crossing("Mersey Gateway", "No barriers. Pay online by midnight the day after.", listOf(LatLng(53.3528, -2.7131)), 400.0),
        Crossing("Silver Jubilee Bridge", "No barriers. Pay online by midnight the day after.", listOf(LatLng(53.3467, -2.7378)), 250.0),
        Crossing("Tyne Tunnels", "No barriers. Pay online by midnight the day after.", listOf(LatLng(54.9877, -1.4859)), 300.0),
        Crossing("Blackwall Tunnel", "No barriers. Charged 6 am to 10 pm. Pay online afterwards.", listOf(LatLng(51.5044, -0.0031)), 250.0),
        Crossing("Silvertown Tunnel", "No barriers. Charged 6 am to 10 pm. Pay online afterwards.", listOf(LatLng(51.5047, 0.0081)), 250.0),
    )

    /** The Inner Ring Road, clockwise from Edgware Road. The ring itself is free; inside it is charged. */
    private val congestionZone = listOf(
        LatLng(51.5203, -0.1700), LatLng(51.5238, -0.1440), LatLng(51.5304, -0.1238), LatLng(51.5322, -0.1058),
        LatLng(51.5257, -0.0876), LatLng(51.5240, -0.0775), LatLng(51.5100, -0.0730), LatLng(51.5075, -0.0745),
        LatLng(51.4950, -0.0850), LatLng(51.4946, -0.1000), LatLng(51.4862, -0.1230), LatLng(51.4950, -0.1440),
        LatLng(51.5027, -0.1527), LatLng(51.5131, -0.1589), LatLng(51.5203, -0.1700),
    )
    private val congestion = RoadCharge("London Congestion Charge zone", "Charged in the daytime. Pay online.")

    /** The charges a route runs into, in the order above. Empty for nearly every route. */
    fun on(line: List<LatLng>): List<RoadCharge> {
        if (line.size < 2) return emptyList()
        val minLat = line.minOf { it.lat }; val maxLat = line.maxOf { it.lat }
        val minLng = line.minOf { it.lng }; val maxLng = line.maxOf { it.lng }
        fun near(p: LatLng) = p.lat > minLat - 0.01 && p.lat < maxLat + 0.01 && p.lng > minLng - 0.02 && p.lng < maxLng + 0.02
        val found = mutableListOf<RoadCharge>()
        var cum: DoubleArray? = null
        for (c in crossings) {
            if (c.at.none { near(it) }) continue
            val k = cum ?: Geo.cumulative(line).also { cum = it }
            if (c.at.any { near(it) && Geo.project(it, line, k).offset < c.radius }) found.add(RoadCharge(c.name, c.note))
        }
        if (line.any { insideCongestionZone(it) }) found.add(congestion)
        return found
    }

    /** Well inside the zone (300 m from its edge), so driving the ring road round it never counts. */
    fun insideCongestionZone(p: LatLng): Boolean {
        if (p.lat <= 51.486 || p.lat >= 51.533 || p.lng <= -0.171 || p.lng >= -0.072) return false
        val ring = congestionZone
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]; val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) && p.lng < (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng) inside = !inside
            j = i
        }
        return inside && Geo.project(p, ring).offset > 300
    }
}
