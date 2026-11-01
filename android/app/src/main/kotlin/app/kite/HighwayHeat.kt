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
 * brighter is more reports. Kite colours it itself, clear where there's nothing, then yellow
 * through orange to red. The same as the iPhone app.
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
        const val MAX_ZOOM = 10
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

        /** The grey tile, coloured: clear where there's nothing, yellow to red as reports get denser. */
        fun colour(data: ByteArray): Bitmap? {
            val src = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
            val w = src.width; val h = src.height
            if (w <= 0 || h <= 0) return null
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val p = px[i]
                val v = (((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 + (p and 0xFF) * 0.114)
                if (v <= 16) { px[i] = 0; continue }
                val t = min((v - 16) / 170, 1.0)
                val r: Double; val g: Double; val b: Double
                if (t < 0.5) {
                    val k = t / 0.5
                    r = 255 + (249 - 255) * k; g = 214 + (115 - 214) * k; b = 22 * k
                } else {
                    val k = (t - 0.5) / 0.5
                    r = 249 + (220 - 249) * k; g = 115 + (38 - 115) * k; b = 22 + (38 - 22) * k
                }
                val a = 0.12 + 0.43 * t
                px[i] = ((a * 255).toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
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
                    if (r != null && r.code == 200) t to colour(r.body) else t to null
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
                rasterOpacity(interpolate(linear(), zoom(), stop(10, 0.75f), stop(13, 0.5f), stop(16, 0.3f))),
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
