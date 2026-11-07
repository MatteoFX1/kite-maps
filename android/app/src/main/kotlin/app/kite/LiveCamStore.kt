package app.kite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kite.core.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The free Windy Webcams API key (api.windy.com/keys, "Webcams API", Free plan). Empty means no
 * scenic webcams: Kite then shows only the road cameras, which need no key. Same as the iPhone's.
 */
object WindyKey {
    const val VALUE = "oCX0gC54CF52fMI1VoXtP9BBNxKW94mn"
    val present: Boolean get() = VALUE.isNotEmpty()
}

/**
 * Live camera photos (GitHub issue #15): every UK road camera, listed once a day from OpenHighways
 * (TfL's own list if that doesn't answer) and kept on the phone, plus Windy's scenic webcams around
 * what's on screen. Photos are only fetched when someone opens a camera. Same as the iPhone's.
 */
object LiveCamStore {
    private const val TAG = "KiteLiveCams"
    const val USER_AGENT = "Kite-Maps (https://github.com/matteofx1/kite-maps)"
    private val scope = MainScope()
    private val client by lazy { Http.client(25) }

    /** Every road camera Kite knows of; [version] goes up when it changes. */
    var road: List<LiveCam> = emptyList(); private set
    private val windy = HashMap<String, Pair<LiveCam, Long>>()
    /** The circles already asked Windy about, and when: a view inside one asks nothing. */
    /** What Windy was asked about: [minReach] > 0 when its answer hit the 50-camera limit, so only views as wide as that count as covered. */
    private class Asked(val center: LatLng, val km: Double, val at: Long, val minReach: Double)
    private val windyAsked = ArrayList<Asked>()
    /** The latest view asked for while another ask was still out: asked as soon as that one ends. */
    private var windyPending: Pair<LatLng, Double>? = null
    private var windyFetch: Job? = null
    private var windyFailedAt = 0L
    var version by mutableIntStateOf(0); private set
    /** How the last list update went, for Settings. */
    var status by mutableStateOf<String?>(null); private set
    /** Called when cameras arrive, so the map can draw them. */
    var onChange: (() -> Unit)? = null
    private var loading: Job? = null
    private var listedAt: Long? = null
    /** After a failed list, not again before this, even with no list at all yet. */
    private var roadRetryAt = 0L

    private val cacheFile get() = File(KiteApp.app.cacheDir, "livecams.json")

    private fun bump() { version++; onChange?.invoke() }

    /** Read the saved list, then ask for a new one if it's a day old (or missing). */
    fun ensureLoaded() {
        if (loading != null) return
        val now = System.currentTimeMillis()
        if (road.isEmpty()) {
            runCatching {
                val saved = Json.obj(cacheFile.readBytes())
                val cams = saved?.get("cams").asMaps()?.mapNotNull { LiveCam.fromMap(it) }
                if (!cams.isNullOrEmpty()) { road = cams; listedAt = saved?.get("at").long(); bump() }
            }
        }
        val at = listedAt
        if (at != null && now - at < 86_400_000L && road.isNotEmpty()) return
        if (now < roadRetryAt) return
        loading = scope.launch {
            val fresh = withContext(Dispatchers.IO) { fetchRoadList() }
            loading = null
            if (!fresh.isNullOrEmpty()) {
                road = fresh
                listedAt = System.currentTimeMillis()
                status = "${fresh.size} road cameras"
                bump()
                withContext(Dispatchers.IO) {
                    runCatching { cacheFile.writeText(Json.write(mapOf("at" to listedAt, "cams" to fresh.map { it.toMap() }))) }
                }
            } else {
                status = if (road.isEmpty()) "Couldn't load the camera list" else "${road.size} road cameras (saved list)"
                // Try again in ten minutes rather than on every pan.
                listedAt = System.currentTimeMillis() - 86_400_000L + 600_000L
                roadRetryAt = System.currentTimeMillis() + 600_000L
            }
        }
    }

    private suspend fun fetchRoadList(): List<LiveCam>? {
        get(LiveCams.OPEN_HIGHWAYS_URL)?.let { LiveCams.parseOpenHighways(it) }?.takeIf { it.size > 100 }?.let {
            Log.i(TAG, "openhighways ${it.size}"); return it
        }
        get(LiveCams.TFL_URL)?.let { LiveCams.parseTfL(it) }?.takeIf { it.isNotEmpty() }?.let {
            Log.i(TAG, "tfl ${it.size}"); return it
        }
        return null
    }

    private suspend fun get(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        Http.getOrNull(url, mapOf("User-Agent" to USER_AGENT) + headers, client)?.takeIf { it.code == 200 }?.body

    /**
     * Ask Windy about the webcams around [center] (a view [spanKm] corner to corner), unless an ask in
     * the last 10 minutes already covered all of it. A view asked for while another ask is out is
     * kept and asked next, so a quick pan never leaves the new place empty.
     */
    fun loadWindy(center: LatLng, spanKm: Double) {
        val now = System.currentTimeMillis()
        if (!WindyKey.present) return
        // In the minute's pause after a failure: remember the newest view and ask once the pause is over.
        if (now - windyFailedAt < 60_000) { windyPending = center to spanKm; retryWindyLater(); return }
        windyAsked.removeAll { now - it.at >= 600_000 }
        // Windy answers at most 250 km out; a wider view only needs its middle covered.
        val reach = minOf(spanKm / 2, 250.0)
        if (windyAsked.any { it.center.distanceTo(center) / 1000 + reach <= it.km && reach >= it.minReach }) return
        if (windyFetch != null) { windyPending = center to spanKm; return }
        // A margin around the view, so small pans ask nothing.
        val radius = (reach * 1.4).coerceIn(15.0, 250.0).toInt()
        windyFetch = scope.launch {
            val data = withContext(Dispatchers.IO) { get(LiveCams.windyNearbyUrl(center, radius), mapOf("x-windy-api-key" to WindyKey.VALUE)) }
            windyFetch = null
            val cams = data?.let { LiveCams.parseWindy(it) }
            if (cams != null) {
                val t = System.currentTimeMillis()
                // A full answer (the 50 limit) may have left cameras out: zooming in asks again.
                windyAsked += Asked(center, radius.toDouble(), t, if (data != null && LiveCams.windyListed(data) >= 50) reach * 0.8 else 0.0)
                cams.forEach { windy[it.id] = it to t }
                bump()
            } else {
                windyFailedAt = System.currentTimeMillis()
                // The view asked for meanwhile is tried again after the minute's pause, not dropped.
                if (windyPending != null) retryWindyLater()
                return@launch
            }
            windyPending?.let { (c, sp) -> windyPending = null; loadWindy(c, sp) }
        }
    }

    private var windyRetry: Job? = null

    /** One retry at the end of the pause, with the newest view asked for in it. */
    private fun retryWindyLater() {
        if (windyRetry != null) return
        val wait = (61_000 - (System.currentTimeMillis() - windyFailedAt)).coerceAtLeast(1_000)
        windyRetry = scope.launch {
            delay(wait)
            windyRetry = null
            windyPending?.let { (c, sp) -> windyPending = null; loadWindy(c, sp) }
        }
    }

    /** The route last asked about, so reopening the same route asks Windy nothing new. */
    private var windyRouteKey: String? = null
    private var windyRouteFetch: Job? = null

    /** Every Windy webcam seen in the last while (their photo links are renewed when opened). */
    val webcams: List<LiveCam> get() = windy.values.map { it.first }

    /**
     * Before you go: ask Windy about the webcams along a route, a few circles spread along it,
     * once per route. Stretches already asked about in the last 10 minutes are skipped.
     */
    fun loadWindyRoute(key: String, line: List<LatLng>) {
        if (!WindyKey.present || line.size < 2 || key == windyRouteKey) return
        if (System.currentTimeMillis() - windyFailedAt < 60_000) return
        windyRouteKey = key
        windyRouteFetch?.cancel()
        val asks = LiveCams.windyRouteAsks(line)
        val radius = asks.radiusKm.toDouble()
        windyRouteFetch = scope.launch {
            for (p in asks.points) {
                val now = System.currentTimeMillis()
                windyAsked.removeAll { now - it.at >= 600_000 }
                if (windyAsked.any { it.center.distanceTo(p) / 1000 + radius <= it.km && radius >= it.minReach }) continue
                val data = withContext(Dispatchers.IO) { get(LiveCams.windyNearbyUrl(p, asks.radiusKm), mapOf("x-windy-api-key" to WindyKey.VALUE)) }
                val cams = data?.let { LiveCams.parseWindy(it) }
                if (data == null || cams == null) {
                    // Asked again next time the route is shown, after the minute's pause.
                    windyFailedAt = System.currentTimeMillis()
                    windyRouteKey = null
                    return@launch
                }
                val t = System.currentTimeMillis()
                windyAsked += Asked(p, radius, t, if (LiveCams.windyListed(data) >= 50) radius * 0.8 else 0.0)
                cams.forEach { windy[it.id] = it to t }
                // Shown as each stretch arrives.
                bump()
            }
        }
    }

    /** The cameras to draw in a box, as the settings want them. */
    fun cams(south: Double, west: Double, north: Double, east: Double, road: Boolean, webcams: Boolean): List<LiveCam> {
        fun inside(p: LatLng) = p.lat in south..north && p.lng in west..east
        val out = ArrayList<LiveCam>()
        if (road) this.road.filterTo(out) { inside(it.location) }
        if (webcams) windy.values.map { it.first }.filterTo(out) { inside(it.location) }
        return out
    }

    fun cam(id: String): LiveCam? = road.firstOrNull { it.id == id } ?: windy[id]?.first

    /** A Windy webcam with a photo link that still works (they last about 15 minutes); road cameras as they are. */
    suspend fun current(cam: LiveCam): LiveCam {
        if (cam.source != LiveCam.Source.WINDY) return cam
        windy[cam.id]?.let { if (System.currentTimeMillis() - it.second < 600_000) return it.first }
        val url = LiveCams.windyOneUrl(cam.id) ?: return cam
        val fresh = withContext(Dispatchers.IO) { get(url, mapOf("x-windy-api-key" to WindyKey.VALUE)) }
            ?.let { LiveCams.parseWindy(it)?.firstOrNull() } ?: return cam
        windy[cam.id] = fresh to System.currentTimeMillis()
        return fresh
    }

    /** The photo, and when the camera took it (from the server's Last-Modified, when it says). */
    suspend fun photo(cam: LiveCam): Pair<Bitmap, Long?>? = withContext(Dispatchers.IO) {
        val r = Http.getOrNull(cam.imageUrl, mapOf("User-Agent" to USER_AGENT, "Cache-Control" to "no-cache"), client)
            ?.takeIf { it.code == 200 } ?: return@withContext null
        val bmp = BitmapFactory.decodeByteArray(r.body, 0, r.body.size)?.takeIf { it.width > 8 && !isPlaceholder(it) } ?: return@withContext null
        val taken = r.headers["Last-Modified"]?.let { lm ->
            runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(lm)?.time
            }.getOrNull()
        } ?: cam.updatedAtMillis
        bmp to taken
    }

    /**
     * National Highways sends a "CAMERA UNAVAILABLE" card (white, with a red cross) instead of a
     * photo when a camera is off. That card is mostly pale with a red cross over a black camera; treat it as no photo.
     */
    private fun isPlaceholder(bmp: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bmp, 32, 18, true)
        val px = IntArray(32 * 18)
        small.getPixels(px, 0, 32, 0, 0, 32, 18)
        if (small !== bmp) small.recycle()
        var light = 0; var red = 0; var black = 0
        for (c in px) {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val lo = minOf(r, g, b); val hi = maxOf(r, g, b)
            if (lo > 195 && hi - lo < 45) light++
            if (r > 140 && g < 70 && b < 100) red++
            if (hi < 45) black++
        }
        // A pale card, a red cross and a black camera all at once: not a real road photo.
        val n = px.size
        return light * 10 > n * 3 && red * 40 > n && black * 12 > n
    }
}
