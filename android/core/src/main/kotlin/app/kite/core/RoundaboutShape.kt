package app.kite.core

/**
 * A roundabout as you meet it: the way in at the bottom, every road around it by its angle
 * (degrees clockwise from straight ahead, so the way in is at 180), and the exit to take.
 */
data class RoundaboutShape(
    /** Roads you can leave by, in the order you pass them. */
    val exits: List<Double>,
    /** Which of [exits] to take. */
    val taken: Int,
    /** Roads that only come in (one way), drawn faint and not counted. */
    val entriesOnly: List<Double> = emptyList(),
    /** Traffic goes round clockwise (Britain, Ireland and other keep-left countries). */
    val clockwise: Boolean,
    /** False when only the exits up to yours are known. */
    val complete: Boolean,
    /** A mini-roundabout (a painted circle, one lane): no "which lane" advice. */
    val mini: Boolean = false,
    /** Lanes on the road in at the give-way line, when OpenStreetMap says. */
    val entryLanes: Int? = null,
    /** The arrows painted on those lanes, left to right, when OpenStreetMap maps them. */
    val entryArrows: List<String>? = null,
) {
    /** How far round the exit is, the way traffic goes, from the way in: 15 to 375. */
    fun turn(i: Int): Double {
        val a = exits[i]
        val d = if (clockwise) a - 180 else 180 - a
        val t = ((d % 360) + 360) % 360
        return if (t < 15) t + 360 else t
    }

    /** The lane the Highway Code says to use when no lanes are mapped: 0 nearest the kerb, 1 nearest the middle. */
    val ruleLane: Int get() = if (turn(taken) > 200) 1 else 0

    /**
     * The lanes to draw when the router has none, left to right: the road's painted arrows when
     * OpenStreetMap maps them, or else by the Highway Code. Null for a one-lane road in or a mini-roundabout.
     */
    fun ruleLanes(leftTraffic: Boolean, hint: String? = null): List<Lane>? {
        if (mini) return null
        var n = entryLanes ?: 2
        if (taken !in exits.indices) return null
        val t = turn(taken)
        val near = if (leftTraffic) "left" else "right"
        val far = if (leftTraffic) "right" else "left"
        if (hint != null) {
            val r = hintLanes(hint, n)
            if (r != null) {
                n = r.second
                val picked = r.first
                return (0 until n).map { k ->
                    val a = if (entryArrows?.size == n) entryArrows[k].split(";").map { if (it == "through") "straight" else it } else emptyList()
                    val ind = if (a.isNotEmpty() && !a.contains("none")) a
                    else if (k == 0) listOf(near, "straight") else if (k == n - 1) listOf(far, "straight") else listOf("straight")
                    Lane(ind, k in picked)
                }
            }
        }
        if (n <= 1) return null
        val arrows = entryArrows
        if (arrows != null && arrows.size == n) {
            val lanes = arrows.map { a ->
                val ind = a.split(";").filter { it.isNotEmpty() && it != "none" }
                val ok = (if (ind.isEmpty()) listOf("through") else ind).any { d ->
                    when {
                        d.endsWith(near) -> t <= 150
                        d == "through" -> t >= 120 && t <= 240
                        d.endsWith(far) -> t >= 210
                        d == "reverse" -> t >= 300
                        else -> false
                    }
                }
                Lane(
                    if (ind.isEmpty()) listOf("straight") else ind.map {
                        if (it == "through") "straight" else if (it == "reverse") "uturn" else it.replace("_", " ")
                    }, ok,
                )
            }
            if (lanes.any { it.valid }) return lanes
        }
        val good: IntRange = when {
            t > 300 || (t > 200 && n < 3) -> (n - 1)..(n - 1)
            t > 200 -> (n / 2)..(n - 1)
            t > 135 -> 0..maxOf(0, n - 2)
            else -> 0..0
        }
        val fromKerb = (0 until n).map { k ->
            val ind = if (k == 0) (if (n == 2) listOf(near, "straight") else listOf(near))
            else if (k == n - 1) listOf(far) else listOf("straight", far)
            Lane(ind, k in good)
        }
        return if (leftTraffic) fromKerb else fromKerb.reversed()
    }

    companion object {
        /**
         * Which lanes, left to right, Google's lane advice means, out of [count] (raised when the advice
         * needs more). Null for "any lane" or words it doesn't know. Returns the lanes and the new count.
         */
        fun hintLanes(hint: String, count: Int): Pair<IntRange, Int>? {
            var n = count
            val h = hint.lowercase()
            fun number(pattern: String): Int? = Regex(pattern).find(h)?.value?.filter { it.isDigit() }?.toIntOrNull()
            if (h.contains("any lane")) return null
            number("left \\d lanes")?.let { k -> n = maxOf(n, k + 1); return (0..k - 1) to n }
            number("right \\d lanes")?.let { k -> n = maxOf(n, k + 1); return (n - k..n - 1) to n }
            number("lane \\d(st|nd|rd|th) from the left")?.let { k -> n = maxOf(n, k + 1); return (k - 1..k - 1) to n }
            number("lane \\d(st|nd|rd|th) from the right")?.let { k -> n = maxOf(n, k + 1); return (n - k..n - k) to n }
            if (h.contains("left lane")) { n = maxOf(n, 2); return (0..0) to n }
            if (h.contains("right lane")) { n = maxOf(n, 2); return (n - 1..n - 1) to n }
            if (h.contains("middle lane")) return if (n == 3) (1..1) to n else null
            if (h.contains("middle 2 lanes")) return if (n == 4) (1..2) to n else null
            return null
        }
    }
}
