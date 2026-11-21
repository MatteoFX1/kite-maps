package app.kite.core

import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.IO
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Fixed speed cameras from Waze's own map. OpenStreetMap, where Kite's cameras came from until
 * now, is thin and old in places (Southampton's Thomas Lewis Way had none mapped), while Waze's
 * map is kept up by its drivers. The map comes in small pieces (a hundredth of a degree square,
 * a few kilobytes), each with a short table of cameras: where, which way, and the limit.
 * Read the way SABRE Plus reads the same pieces for their roads (MIT, see licenses).
 *
 * Only alongside OpenStreetMap's cameras, never instead: a camera both maps hold shows once.
 */
object WazeCameras {
    /** Set by the app: Waze's cameras are only asked for while Waze is switched on in Settings. */
    @Volatile var enabled = false

    private var store: WazeCameraStore? = null
    private val storeLock = KLock()
    /** The cameras read so far, kept on the phone. Swapped for an empty one in tests. */
    var shared: WazeCameraStore
        get() = storeLock.locked { store ?: WazeCameraStore().also { store = it } }
        set(v) = storeLock.locked { store = v }

    const val rows = 18_000
    /** A drive's pieces, nearest first: about 300 km of road. */
    const val maxRouteTiles = 700
    /** The most pieces one look at the map may ask for: a town, not a county. */
    const val maxBoxTiles = 80

    /** Waze's number for the piece of map holding [p]. */
    fun tileId(p: LatLng): Int =
        (((p.lng * 1_000_000).toInt() + 180_000_000) / 10_000) * rows + ((p.lat * 1_000_000).toInt() + 90_000_000) / 10_000

    /** The pieces within about 150 m of [line], from its start on, each once. */
    fun tileIds(line: List<LatLng>): List<Int> {
        val out = LinkedHashSet<Int>()
        val dLat = 0.0014; val dLng = 0.0022
        fun note(p: LatLng) {
            out.add(tileId(p))
            for (dy in listOf(dLat, -dLat)) for (dx in listOf(dLng, -dLng)) out.add(tileId(LatLng(p.lat + dy, p.lng + dx)))
        }
        var last = line.firstOrNull() ?: return emptyList()
        note(last)
        for (p in line.drop(1)) {
            val n = maxOf(1, ceil(last.distanceTo(p) / 250).toInt())
            for (i in 1..n) {
                val t = i.toDouble() / n
                note(LatLng(last.lat + (p.lat - last.lat) * t, last.lng + (p.lng - last.lng) * t))
            }
            last = p
        }
        return out.toList()
    }

    /** The pieces covering a box; empty when it would take more than [most]. */
    fun tileIds(south: Double, west: Double, north: Double, east: Double, most: Int = maxBoxTiles): List<Int> {
        val a = tileId(LatLng(south, west)); val b = tileId(LatLng(north, east))
        val x0 = a / rows; val x1 = b / rows; val y0 = a % rows; val y1 = b % rows
        if (x1 < x0 || y1 < y0 || (x1 - x0 + 1) * (y1 - y0 + 1) > most) return emptyList()
        val out = mutableListOf<Int>()
        for (x in x0..x1) for (y in y0..y1) out.add(x * rows + y)
        return out
    }

    // Reading a piece

    private fun u32(b: ByteArray, o: Int): Int =
        if (o >= 0 && o + 4 <= b.size) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24) else 0

    /** The speed cameras in one piece as Waze's server sends it; null when [data] isn't a map piece. */
    fun parse(data: ByteArray): List<RoadCamera>? {
        // "WZDF", then the packed and unpacked sizes, then the tables, zlib-packed.
        var at = 0
        while (at + 20 <= data.size && !(data[at].toInt() == 87 && data[at + 1].toInt() == 90 && data[at + 2].toInt() == 68 && data[at + 3].toInt() == 70)) at++
        if (at + 20 > data.size) return null
        val packed = u32(data, at + 12); val full = u32(data, at + 16)
        if (packed <= 6 || at + 20 + packed > data.size || full <= 0 || full > 8_000_000) return null
        val out = zlibInflate(data, at + 20, packed, full) ?: return null
        return cameras(out)
    }

    /**
     * The speed cameras in a piece's unpacked tables. The tables sit one after another behind a
     * list of where each ends; table 26 opens with the piece's number, and table 25 holds the
     * cameras, 16 bytes each: longitude and latitude in millionths of a degree, the way it faces,
     * its kind (2 is a speed camera), its limit in km/h, and Waze's number for it.
     */
    fun cameras(s: ByteArray): List<RoadCamera>? {
        val n = u32(s, 0); val bits = u32(s, 4)
        if (n <= 26 || n >= 200 || bits !in 0..15 || 8 + n * 4 > s.size) return null
        val base = 8 + n * 4; val mask = (1 shl bits) - 1
        fun table(i: Int): ByteArray {
            val start = if (i == 0) 0 else (u32(s, 8 + (i - 1) * 4) + mask) and mask.inv()
            val end = u32(s, 8 + i * 4)
            if (end <= start || start < 0 || base + end > s.size) return ByteArray(0)
            return s.copyOfRange(base + start, base + end)
        }
        val head = table(26)
        if (head.size < 4) return null
        val tile = u32(head, 0)
        val west = (tile / rows * 10_000 - 180_000_000) / 1e6; val south = (tile % rows * 10_000 - 90_000_000) / 1e6
        val b = table(25)
        val out = mutableListOf<RoadCamera>()
        var o = 0
        while (o + 16 <= b.size) {
            val lng = u32(b, o) / 1e6; val lat = u32(b, o + 4) / 1e6
            val kind = b[o + 10].toInt() and 0xFF; val kmh = b[o + 11].toInt() and 0xFF
            val number = (b[o + 12].toInt() and 0xFF) or ((b[o + 13].toInt() and 0xFF) shl 8)
            o += 16
            // Only a plain speed camera, and only where the piece itself is: anything else isn't one.
            if (kind != 2 || lat < south - 0.001 || lat > south + 0.011 || lng < west - 0.001 || lng > west + 0.011) continue
            out.add(RoadCamera(-(tile.toLong() * 100_000 + number + 1), RoadCamera.Kind.SPEED, LatLng(lat, lng),
                maxspeedKmh = if (kmh > 0) kmh.toDouble() else null))
        }
        return out
    }

    // Mixing with OpenStreetMap's

    /** Whether a camera came from Waze's map (OpenStreetMap's numbers are all above zero). */
    fun isWaze(c: RoadCamera) = c.id < 0

    /**
     * [base] plus the cameras only Waze has: one within 60 m of a speed camera already there is the
     * same camera (the two maps place it a few metres apart), and Waze's own doubles go too.
     */
    fun merge(base: List<RoadCamera>, waze: List<RoadCamera>): List<RoadCamera> {
        if (waze.isEmpty()) return base
        val out = base.toMutableList()
        val have = base.mapTo(HashSet()) { it.id }
        val mapped = base.filter { it.kind == RoadCamera.Kind.SPEED && !isWaze(it) }
        val added = base.filter { isWaze(it) }.toMutableList()
        for (c in waze) {
            if (c.id in have) continue
            if (mapped.any { abs(it.location.lat - c.location.lat) < 0.001 && it.location.distanceTo(c.location) < 60 }) continue
            if (added.any { abs(it.location.lat - c.location.lat) < 0.001 && it.location.distanceTo(c.location) < 12 }) continue
            added.add(c)
            out.add(c)
        }
        return out
    }

    // Asking Waze

    /**
     * Waze's speed cameras within 150 m of a route, asking for the pieces not yet on the phone.
     * Null when Waze couldn't be asked and nothing is known; empty when it's switched off.
     */
    suspend fun along(line: List<LatLng>): List<RoadCamera>? {
        if (!enabled || line.size < 2) return emptyList()
        return withContext(Dispatchers.IO) {
            val ok = load(tileIds(line).take(maxRouteTiles), line[0])
            val found = shared.known(line)
            if (ok || found.isNotEmpty()) found else null
        }
    }

    /** Asks for the pieces covering what the map shows, when zoomed in enough. True when new ones came. */
    suspend fun load(south: Double, west: Double, north: Double, east: Double): Boolean {
        if (!enabled) return false
        return withContext(Dispatchers.IO) {
            val ids = shared.stale(tileIds(south, west, north, east))
            if (ids.isEmpty()) false else load(ids, LatLng((south + north) / 2, (west + east) / 2))
        }
    }

    /** True when every piece asked for is now known (or already was). */
    suspend fun load(ids: List<Int>, near: LatLng): Boolean {
        val want = shared.stale(ids)
        if (want.isEmpty()) return true
        val got = try {
            WazeLive.shared.mapTiles(want, near)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return false
        val fresh = LinkedHashMap<Int, List<RoadCamera>>()
        val blank = mutableListOf<Int>()
        for ((id, data) in got) {
            val cams = parse(data)
            if (cams != null) fresh[id] = cams else blank.add(id)
        }
        shared.put(fresh, blank)
        return got.size == want.size
    }
}

/** Waze's cameras by map piece, kept on the phone: a piece is asked for again after a week. */
class WazeCameraStore(private val file: String? = defaultFile) {
    private class Tile(val at: Long, val cameras: List<RoadCamera>)

    private val lock = KLock()
    private var tiles: LinkedHashMap<Int, Tile>? = null
    private var saving = false
    private var changes = 0

    companion object {
        /** Set by the app at launch to its cache folder. */
        @Volatile var defaultFile: String? = null
        const val keep = 7 * 86_400_000L
        const val maxTiles = 8_000
    }

    /** Goes up whenever new pieces come in, so the map knows to draw again. */
    val version: Int get() = lock.locked { changes }

    private fun loaded(): LinkedHashMap<Int, Tile> {
        tiles?.let { return it }
        val t = LinkedHashMap<Int, Tile>()
        try {
            val f = file
            val bytes = f?.let { Disk.read(it) }
            if (bytes != null) {
                for (m in Json.obj(bytes)?.get("tiles").asMaps() ?: emptyList()) {
                    val id = m["id"].int() ?: continue
                    val cams = (m["cams"].asMaps() ?: emptyList()).mapNotNull { c ->
                        RoadCamera(c["id"].long() ?: return@mapNotNull null, RoadCamera.Kind.SPEED,
                            LatLng(c["lat"].num() ?: return@mapNotNull null, c["lng"].num() ?: return@mapNotNull null), maxspeedKmh = c["kmh"].num())
                    }
                    t[id] = Tile(m["at"].long() ?: 0, cams)
                }
            }
        } catch (e: Exception) {
            // A damaged file starts over.
        }
        tiles = t
        return t
    }

    /** Which of [ids] aren't on the phone, or were read more than a week ago. */
    fun stale(ids: List<Int>, now: Long = nowMillis()): List<Int> = lock.locked {
        val t = loaded()
        ids.filter { id -> t[id]?.let { now - it.at > keep } ?: true }
    }

    /** Remembers pieces just read. A [blank] one (no map came back) is only trusted for a day. */
    fun put(fresh: Map<Int, List<RoadCamera>>, blank: List<Int> = emptyList(), now: Long = nowMillis()) {
        if (fresh.isEmpty() && blank.isEmpty()) return
        var start: Boolean
        lock.locked {
            val t = loaded()
            for ((id, cams) in fresh) t[id] = Tile(now, cams)
            for (id in blank) if (t[id] == null) t[id] = Tile(now - keep + 86_400_000L, emptyList())
            if (t.size > maxTiles) {
                for (id in t.entries.sortedBy { it.value.at }.take(t.size - maxTiles / 2).map { it.key }) t.remove(id)
            }
            changes++
            start = !saving && file != null
            if (start) saving = true
        }
        if (start) background(3000) { save() }
    }

    private fun save() {
        val text: String
        lock.locked {
            saving = false
            val t = tiles ?: return
            text = Json.write(mapOf("tiles" to t.map { (id, tile) ->
                mapOf("id" to id, "at" to tile.at, "cams" to tile.cameras.map {
                    mapOf("id" to it.id, "lat" to it.location.lat, "lng" to it.location.lng, "kmh" to it.maxspeedKmh)
                })
            }))
        }
        val f = file ?: return
        Disk.write(f, text.encodeToByteArray())
    }

    /** Every Waze camera on the phone. */
    fun known(): List<RoadCamera> = lock.locked { loaded().values.flatMap { it.cameras } }

    /** Those within [meters] of a route. */
    fun known(line: List<LatLng>, meters: Double = 150.0): List<RoadCamera> {
        if (line.size < 2) return emptyList()
        val all = known()
        if (all.isEmpty()) return emptyList()
        val pad = 0.004
        val s0 = line.minOf { it.lat } - pad; val n0 = line.maxOf { it.lat } + pad
        val w0 = line.minOf { it.lng } - pad; val e0 = line.maxOf { it.lng } + pad
        val close = all.filter { it.location.lat in s0..n0 && it.location.lng in w0..e0 }
        if (close.isEmpty()) return emptyList()
        val cum = Geo.cumulative(line)
        return close.filter { Geo.project(it.location, line, cum).offset <= meters }
    }
}
