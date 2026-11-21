package app.kite

import androidx.compose.ui.graphics.ImageBitmap
import app.kite.core.*
import app.kite.map.Png
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.maplibre.compose.resource.MapResourceError
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider
import kotlin.math.*

/**
 * The police heat map from Highway Radar's own tile server: where police get reported, built from
 * its users' reports over time, for the whole UK at once. Each tile is a 256 px greyscale WebP:
 * brighter is more reports. Kite colours it itself, clear where there's nothing, then glowing
 * blue through violet ("blue lights"). The same as the iPhone app.
 *
 * The map asks for the tiles itself, as "kiteheat://z/x/y"; [provider] fetches each one, colours it
 * and hands it back as a picture.
 */
object HighwayHeat {
    data class Tile(val z: Int, val x: Int, val y: Int) { val key get() = "$z/$x/$y" }

    /** How the last ask went, for Settings: true when tiles came back. */
    var lastOK: Boolean? = null; private set

        const val MIN_ZOOM = 5
        // The server goes to zoom 14; up to 13 its tiles follow the roads cleanly, and the "quiet"
        // cut-off in blueLights hides its few faint blocky patches. Closer in, the 13 tiles stretch.
        const val MAX_ZOOM = 13
        const val SCHEME = "kiteheat"
        const val TILES = "$SCHEME://{z}/{x}/{y}"
        private val client by lazy { Http.client(20) }

        fun url(t: Tile) = "https://tiles.cmbe.cc/heat_map/get/${t.z}/${t.x}/${t.y}.webp"

        /** Serves the map's "kiteheat://z/x/y" tiles. */
        val provider = MapResourceProvider(
            accepts = { it.url.startsWith("$SCHEME://") },
            load = { req ->
                val parts = req.url.removePrefix("$SCHEME://").split("/").mapNotNull { it.toIntOrNull() }
                if (parts.size != 3) MapResourceLoad.Failed(MapResourceError.NotFound, "bad tile")
                else {
                    val t = Tile(parts[0], parts[1], parts[2])
                    val r = runCatching { Http.get(url(t), client = client) }.getOrNull()
                    when {
                        r == null -> { lastOK = false; MapResourceLoad.Failed(MapResourceError.Connection, "no answer") }
                        r.code == 404 -> MapResourceLoad.NoContent()
                        r.code != 200 -> { lastOK = false; MapResourceLoad.Failed(MapResourceError.Server, "HTTP ${r.code}") }
                        else -> {
                            lastOK = true
                            val png = withContext(Dispatchers.Default) { colour(r.body, t.z) }
                            if (png == null) MapResourceLoad.NoContent() else MapResourceLoad.Bytes(png)
                        }
                    }
                }
            })

        /**
         * The grey tile, coloured "blue lights" style: roads where police get reported glow blue,
         * turning violet and then bright pink where reports are densest, with a soft halo so the
         * roads read at a glance. Blue so it's never mistaken for traffic (amber and red). Quiet
         * roads (faint grey) stay clear. The same maths as the iPhone app.
         */
        fun colour(data: ByteArray, zoom: Int): ByteArray? {
            val src: ImageBitmap = runCatching { data.decodeToImageBitmap() }.getOrNull() ?: return null
            val w = src.width; val h = src.height
            if (w <= 0 || h <= 0) return null
            val px = IntArray(w * h)
            src.readPixels(px)
            val grey = IntArray(w * h) { i ->
                val p = px[i]
                (((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 + (p and 0xFF) * 0.114).toInt()
            }
            return Png.encode(blueLights(grey, w, h, zoom), w, h)
        }

        // Colour only marks real police hotspots ("danger spots", Matteo's pick). Zoomed-out tiles
        // add up a bigger area and come out brighter, so they need a higher bar.
        fun quiet(zoom: Int): Double = if (zoom >= 12) 85.0 else if (zoom == 11) 100.0 else 120.0
        /** The colour ramp: blue for some reports, violet for many, bright pink for the most. */
        private val RAMP = arrayOf(doubleArrayOf(0.0, 59.0, 130.0, 246.0), doubleArrayOf(0.55, 139.0, 92.0, 246.0), doubleArrayOf(1.0, 232.0, 121.0, 249.0))

        /** Greyscale (0 to 255, w×h) to ARGB pixels (not premultiplied, as Bitmap wants). */
        fun blueLights(grey: IntArray, w: Int, h: Int, zoom: Int): IntArray {
            val n = w * h
            val q = quiet(zoom)
            val t = DoubleArray(n) { i -> ((grey[i] - q) / 130).coerceIn(0.0, 1.0) }
            // The halo: the strength blurred a few pixels out (two box blurs come close to a soft glow).
            var halo = t
            repeat(2) { halo = boxBlur(halo, w, h, 3) }
            val out = IntArray(n)
            for (i in 0 until n) {
                // Spots that pass the bar are drawn bold, so they stand out.
                val core = if (t[i] > 0) 0.7 + 0.3 * t[i] else 0.0
                val a = max(core, min(halo[i] * 1.8, 0.55))
                if (a <= 0.01) continue
                val c = rampColour(0.3 + 0.7 * max(t[i], halo[i] * 0.7))
                out[i] = ((a * 255).toInt() shl 24) or (c[0].toInt() shl 16) or (c[1].toInt() shl 8) or c[2].toInt()
            }
            return out
        }

        fun rampColour(x0: Double): DoubleArray {
            val x = x0.coerceIn(0.0, 1.0)
            for (k in 1 until RAMP.size) {
                if (x <= RAMP[k][0]) {
                    val a = RAMP[k - 1]; val b = RAMP[k]
                    val f = (x - a[0]) / (b[0] - a[0])
                    return doubleArrayOf(a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f)
                }
            }
            val l = RAMP.last()
            return doubleArrayOf(l[1], l[2], l[3])
        }

        /** A box blur of radius [r] across then down, with the edges held. */
        fun boxBlur(src: DoubleArray, w: Int, h: Int, r: Int): DoubleArray {
            val mid = DoubleArray(w * h)
            val out = DoubleArray(w * h)
            val span = (2 * r + 1).toDouble()
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0.0
                for (d in -r..r) sum += src[y * w + (x + d).coerceIn(0, w - 1)]
                mid[y * w + x] = sum / span
            }
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0.0
                for (d in -r..r) sum += mid[(y + d).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = sum / span
            }
            return out
        }
}
