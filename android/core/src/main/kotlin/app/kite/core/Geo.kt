package app.kite.core

import kotlin.math.*

data class LatLng(val lat: Double, val lng: Double) {
    /** Great-circle distance in meters. */
    fun distanceTo(o: LatLng): Double {
        val r = 6_371_000.0
        val p1 = lat * PI / 180
        val p2 = o.lat * PI / 180
        val dp = (o.lat - lat) * PI / 180
        val dl = (o.lng - lng) * PI / 180
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** Where a point falls on a line: meters along it, and how far off it. */
data class Projection(val along: Double, val offset: Double)

/** Swift's truncatingRemainder: keeps the sign of the dividend, like Kotlin's %. */
fun Double.rem360(): Double = this % 360.0

object Geo {
    /** Whether traffic keeps left here (Britain, Ireland, Japan, Australia and the like), roughly by area. */
    fun drivesOnLeft(p: LatLng): Boolean {
        val boxes = arrayOf(
            doubleArrayOf(49.8, -11.0, 61.0, 1.8),     // Britain and Ireland
            doubleArrayOf(24.0, 122.9, 45.6, 146.0),   // Japan
            doubleArrayOf(-44.0, 112.0, -10.0, 154.0), // Australia
            doubleArrayOf(-47.5, 166.0, -34.0, 179.0), // New Zealand
            doubleArrayOf(6.5, 68.0, 35.5, 97.5),      // India, Sri Lanka, Bangladesh, Nepal
            doubleArrayOf(-35.0, 16.0, -22.0, 33.0),   // South Africa and neighbors
            doubleArrayOf(-11.0, 95.0, 7.5, 141.0),    // Indonesia, Malaysia, Singapore
            doubleArrayOf(5.5, 97.3, 20.5, 105.7),     // Thailand
            doubleArrayOf(22.1, 113.8, 22.6, 114.4),   // Hong Kong
            doubleArrayOf(-5.0, 33.9, 5.0, 41.9),      // Kenya
        )
        return boxes.any { p.lat >= it[0] && p.lat <= it[2] && p.lng >= it[1] && p.lng <= it[3] }
    }

    /** Cumulative distance at each vertex. */
    fun cumulative(line: List<LatLng>): DoubleArray {
        val out = DoubleArray(max(line.size, 1))
        for (i in 1 until line.size) out[i] = out[i - 1] + line[i - 1].distanceTo(line[i])
        return out
    }

    fun length(line: List<LatLng>): Double = cumulative(line).last()

    /** The point [meters] along [line]. */
    fun point(line: List<LatLng>, meters: Double, cum: DoubleArray? = null): LatLng {
        if (line.size < 2) return line.firstOrNull() ?: LatLng(0.0, 0.0)
        val c = cum ?: cumulative(line)
        if (meters <= 0) return line[0]
        for (i in 1 until line.size) {
            if (c[i] >= meters) {
                val seg = c[i] - c[i - 1]
                val f = if (seg > 0) (meters - c[i - 1]) / seg else 0.0
                val a = line[i - 1]
                val b = line[i]
                return LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
            }
        }
        return line[line.size - 1]
    }

    /** The part of [line] between two along-distances. */
    fun slice(line: List<LatLng>, from: Double, to: Double, cum: DoubleArray? = null): List<LatLng> {
        if (line.size < 2 || to <= from) return emptyList()
        val c = cum ?: cumulative(line)
        val out = mutableListOf(point(line, from, c))
        for (i in line.indices) if (c[i] > from && c[i] < to) out.add(line[i])
        out.add(point(line, to, c))
        return out
    }

    /** Where [p] falls on [line]: meters along it, and how far off it. */
    fun project(p: LatLng, line: List<LatLng>, cum: DoubleArray? = null): Projection {
        if (line.size < 2) return Projection(0.0, line.firstOrNull()?.let { p.distanceTo(it) } ?: Double.MAX_VALUE)
        val c = cum ?: cumulative(line)
        val k = cos(p.lat * PI / 180) * 111_320.0
        var bestAlong = 0.0
        var bestOff = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
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
            if (d < bestOff) {
                bestOff = d
                bestAlong = c[i] + t * (c[i + 1] - c[i])
            }
        }
        return Projection(bestAlong, bestOff)
    }

    /**
     * Which side of the road [p] is on at the end of [line] ("your destination is on the left"):
     * null when it's on the line itself or behind/past its end.
     */
    fun sideAtEnd(p: LatLng, line: List<LatLng>): String? {
        val cum = cumulative(line)
        val total = cum.last()
        if (line.size < 2 || total <= 20) return null
        val a = point(line, max(0.0, total - 30), cum)
        val b = line[line.size - 1]
        val k = cos(b.lat * PI / 180) * 111_320.0
        val dx = (b.lng - a.lng) * k
        val dy = (b.lat - a.lat) * 110_540
        val px = (p.lng - a.lng) * k
        val py = (p.lat - a.lat) * 110_540
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 5) return null
        val across = (dx * py - dy * px) / len
        val ahead = (dx * px + dy * py) / len
        if (!(abs(across) > 8 && ahead > -20 && ahead < len + 60)) return null
        return if (across > 0) "left" else "right"
    }

    /** Compass bearing from a to b, degrees. */
    fun bearing(a: LatLng, b: LatLng): Double {
        val p1 = a.lat * PI / 180
        val p2 = b.lat * PI / 180
        val dl = (b.lng - a.lng) * PI / 180
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180 / PI + 360) % 360
    }

    /** Decode an encoded polyline (precision 5 or 6). */
    fun decodePolyline(s: String, precision: Double = 1e6): List<LatLng> {
        val out = mutableListOf<LatLng>()
        val bytes = s.toByteArray(Charsets.UTF_8)
        var i = 0
        var lat = 0L
        var lng = 0L
        fun next(): Long? {
            var result = 0L
            var shift = 0
            while (i < bytes.size) {
                val b = (bytes[i].toInt() and 0xff) - 63
                i++
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) return if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
            }
            return null
        }
        while (i < bytes.size) {
            val dlat = next() ?: break
            val dlng = next() ?: break
            lat += dlat
            lng += dlng
            out.add(LatLng(lat / precision, lng / precision))
        }
        return out
    }

    /** Degrees in 0..<360. */
    fun norm(a: Double): Double = ((a % 360) + 360) % 360

    /** From a to b the short way round, -180 to 180. */
    fun signedGap(a: Double, b: Double): Double {
        var d = norm(b - a)
        if (d > 180) d -= 360
        return d
    }

    fun angleGap(a: Double, b: Double): Double = abs(signedGap(a, b))

    /** How far apart two bearings are, 0 to 180, the way the Swift code writes it. */
    fun diff(a: Double, b: Double): Double = abs(((a - b + 540) % 360) - 180)
}

/**
 * A coordinate written out in plain digits for a web address. Kotlin writes very small numbers
 * like 0.0001 as "1.0E-4" (right by the Greenwich line, in London), which servers turn away.
 */
fun Double.plain(): String = java.math.BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
