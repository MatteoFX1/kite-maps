package app.kite.core

import kotlin.math.abs

/**
 * Where to write the road's name in the driving view with no route: along the road itself, from the
 * map's own road lines, rather than straight ahead (which floats off the road on a bend).
 */
object RoadAhead {
    /**
     * The stretch of road [from] to [to] metres ahead of [here] going [heading], following [lines]
     * (the map's pieces of the road you're on, in any order and direction). Null when you're not on
     * one of them (30 m), or there isn't room ahead for the name.
     */
    fun path(lines: List<List<LatLng>>, here: LatLng, heading: Double, from: Double = 40.0, to: Double = 200.0): List<LatLng>? {
        var bestI = -1; var bestAlong = 0.0; var bestOffset = Double.MAX_VALUE
        lines.forEachIndexed { i, l ->
            if (l.size < 2) return@forEachIndexed
            val p = Geo.project(here, l)
            if (p.offset <= 30 && p.offset < bestOffset) { bestI = i; bestAlong = p.along; bestOffset = p.offset }
        }
        if (bestI < 0) return null
        var line = lines[bestI]
        var along = bestAlong
        val len = Geo.length(line)
        // Which way along it you're going.
        val ahead = Geo.bearing(Geo.point(line, maxOf(0.0, along - 5)), Geo.point(line, minOf(len, along + 5)))
        if (turn(ahead, heading) > 90) {
            line = line.reversed()
            along = len - along
        }
        var path = Geo.slice(line, along, len)
        if (path.size < 2) return null
        // On into the next piece (the map cuts roads at tile edges and junctions).
        val used = mutableSetOf(bestI)
        while (Geo.length(path) < to && used.size < 8 && path.size >= 2) {
            val end = path.last()
            val dir = Geo.bearing(path[path.size - 2], end)
            var nextI = -1; var nextLine: List<LatLng> = emptyList(); var nextTurn = Double.MAX_VALUE
            lines.forEachIndexed { i, l ->
                if (i in used || l.size < 2) return@forEachIndexed
                val c = when {
                    l.last().distanceTo(end) < 5 -> l.reversed()
                    l.first().distanceTo(end) < 5 -> l
                    else -> return@forEachIndexed
                }
                val t = turn(Geo.bearing(c[0], c[1]), dir)
                if (t < 100 && t < nextTurn) { nextI = i; nextLine = c; nextTurn = t }
            }
            if (nextI < 0) break
            used.add(nextI)
            path = path + nextLine.drop(1)
        }
        val total = Geo.length(path)
        if (total < from + 60) return null
        return Geo.slice(path, from, minOf(to, total))
    }

    private fun turn(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}
