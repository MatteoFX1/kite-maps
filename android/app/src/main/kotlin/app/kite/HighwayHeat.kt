package app.kite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import app.kite.core.Http
import kotlinx.coroutines.*
import okhttp3.Cache
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import java.io.File
import kotlin.math.*

/**
 * The police heat map from Highway Radar's own tile server: where police get reported, built from
 * its users' reports over time, for the whole UK at once. Each tile is a 256 px greyscale WebP:
 * brighter is more reports. Kite colours it itself, clear where there's nothing, then glowing
 * blue through violet ("blue lights"). The same as the iPhone app.
 */
class HighwayHeat(private val scope: CoroutineScope) {
    data class Tile(val z: Int, val x: Int, val y: Int) { val key get() = "$z/$x/$y" }

    private val images = LinkedHashMap<String, Bitmap>()
    private var shown = setOf<String>()
    private var loading: Job? = null
    private var wanted = setOf<String>()
    /** How the last ask went, for Settings: true when tiles came back. */
    var lastOK: Boolean? = null; private set

    companion object {
        const val MIN_ZOOM = 5
        // The server goes to zoom 14; up to 13 its tiles follow the roads cleanly, and the "quiet"
        // cut-off in blueLights hides its few faint blocky patches. Closer in, the 13 tiles stretch.
        const val MAX_ZOOM = 13
        private val client by lazy {
            Http.client(20).newBuilder().cache(Cache(File(KiteApp.app.cacheDir, "heat"), 40L shl 20)).build()
        }

        fun url(t: Tile) = "https://tiles.cmbe.cc/heat_map/get/${t.z}/${t.x}/${t.y}.webp"

        /** The tiles covering [b], at the deepest zoom up to the map's own that needs at most 16. */
        fun tiles(b: LatLngBounds, mapZoom: Double): List<Tile> {
            var z = min(max(floor(mapZoom).toInt(), MIN_ZOOM), MAX_ZOOM)
            while (z >= MIN_ZOOM) {
                val (x0, y0) = index(b.latitudeNorth, b.longitudeWest, z)
                val (x1, y1) = index(b.latitudeSouth, b.longitudeEast, z)
                val n = 1 shl z
                if (x1 >= x0 && y1 >= y0 && (x1 - x0 + 1) * (y1 - y0 + 1) <= 16) {
                    val out = mutableListOf<Tile>()
                    for (y in max(y0, 0)..min(y1, n - 1)) for (x in x0..x1) out.add(Tile(z, ((x % n) + n) % n, y))
                    return out
                }
                z--
            }
            return emptyList()
        }

        fun index(lat: Double, lng: Double, z: Int): Pair<Int, Int> {
            val n = (1 shl z).toDouble()
            val la = lat.coerceIn(-85.05, 85.05) * PI / 180
            val x = floor((lng + 180) / 360 * n).toInt()
            val y = floor((1 - ln(tan(la) + 1 / cos(la)) / PI) / 2 * n).toInt()
            return x to y
        }

        fun corner(x: Int, y: Int, z: Int): org.maplibre.android.geometry.LatLng {
            val n = (1 shl z).toDouble()
            val lng = x / n * 360 - 180
            val lat = atan(sinh(PI * (1 - 2 * y / n))) * 180 / PI
            return org.maplibre.android.geometry.LatLng(lat, lng)
        }

        fun quad(t: Tile) = LatLngQuad(corner(t.x, t.y, t.z), corner(t.x + 1, t.y, t.z), corner(t.x + 1, t.y + 1, t.z), corner(t.x, t.y + 1, t.z))

        /**
         * The grey tile, coloured "blue lights" style: roads where police get reported glow blue,
         * turning violet and then bright pink where reports are densest, with a soft halo so the
         * roads read at a glance. Blue so it's never mistaken for traffic (amber and red). Quiet
         * roads (faint grey) stay clear. The same maths as the iPhone app.
         */
        fun colour(data: ByteArray, zoom: Int): Bitmap? {
            val src = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
            val w = src.width; val h = src.height
            if (w <= 0 || h <= 0) return null
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val grey = IntArray(w * h) { i ->
                val p = px[i]
                (((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 + (p and 0xFF) * 0.114).toInt()
            }
            return Bitmap.createBitmap(blueLights(grey, w, h, zoom), w, h, Bitmap.Config.ARGB_8888)
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

    /** Shows the heat map for what's on screen ([on] false clears it). Below [below], so it never hides the route. */
    fun update(map: MapLibreMap, on: Boolean, below: String) {
        val style = map.style ?: return
        if (!on) { clear(style); return }
        val want = tiles(map.projection.visibleRegion.latLngBounds, map.cameraPosition.zoom)
        val keys = want.map { it.key }.toSet()
        if (keys == wanted && (shown.containsAll(keys) || loading != null)) return
        wanted = keys
        loading?.cancel()
        val cached = want.mapNotNull { t -> images[t.key]?.let { t to it } }
        val missing = want.filter { images[it.key] == null }
        loading = scope.launch {
            val got = missing.map { t ->
                async(Dispatchers.IO) {
                    val r = runCatching { Http.get(url(t), client = client) }.getOrNull()
                    if (r != null && r.code == 200) t to colour(r.body, t.z) else t to null
                }
            }.awaitAll()
            loading = null
            val fresh = linkedMapOf<Tile, Bitmap>()
            cached.forEach { fresh[it.first] = it.second }
            for ((t, img) in got) if (img != null) { fresh[t] = img; remember(t.key, img) }
            if (want.isNotEmpty()) {
                lastOK = fresh.isNotEmpty()
                Log.i("KiteTrace", "heat map: ${fresh.size} of ${want.size} tiles at zoom ${want.first().z}")
            }
            val s = map.style ?: return@launch
            show(fresh, s, below)
        }
    }

    private fun remember(key: String, img: Bitmap) {
        images.remove(key)
        images[key] = img
        while (images.size > 80) images.remove(images.keys.first())
    }

    private fun show(tiles: Map<Tile, Bitmap>, style: Style, below: String) {
        val keep = tiles.keys.map { it.key }.toSet()
        for (key in shown - keep) remove(key, style)
        for ((t, img) in tiles) {
            if (shown.contains(t.key)) continue
            val id = "hr-heat-${t.z}-${t.x}-${t.y}"
            if (style.getSource(id) != null) continue
            style.addSource(ImageSource(id, quad(t), img))
            val layer = RasterLayer(id, id).withProperties(
                rasterOpacity(interpolate(linear(), zoom(), stop(10, 0.9f), stop(13, 0.8f), stop(16, 0.6f))),
                rasterFadeDuration(0f),
            )
            if (style.getLayer(below) != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
        }
        shown = keep
    }

    private fun remove(key: String, style: Style) {
        val id = "hr-heat-" + key.replace("/", "-")
        style.getLayer(id)?.let { style.removeLayer(it) }
        style.getSource(id)?.let { style.removeSource(it) }
    }

    fun clear(style: Style) {
        loading?.cancel()
        loading = null
        wanted = emptySet()
        for (key in shown) remove(key, style)
        shown = emptySet()
    }

    /** After the style is reloaded (light/dark switch) its layers are gone: start again. */
    fun styleReloaded() { shown = emptySet(); wanted = emptySet() }
}
