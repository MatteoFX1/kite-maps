package app.kite.core

import java.io.File
import kotlin.concurrent.thread
import kotlin.math.floor

/**
 * Every speed limit, camera and traffic light Kite has been told about, kept on the phone. The
 * public Overpass servers can take a minute or more to answer, so without this a drive starts, and
 * every reroute restarts, with no speed limit on screen.
 */
class RoadCache(private val file: File? = defaultFile) {
    private class Store {
        val ways = LinkedHashMap<Long, Overpass.LimitWay>()
        val cameras = LinkedHashMap<Long, RoadCamera>()
        val signals = LinkedHashMap<Long, LatLng>()
    }

    private val lock = Any()
    private var store: Store? = null
    private var saving = false

    companion object {
        /** Set by the app at launch to its cache folder. */
        @Volatile var defaultFile: File? = null
        val shared: RoadCache by lazy { RoadCache() }

        /** About a county's worth of roads; past that the oldest-added half goes. */
        const val maxWays = 40_000
    }

    private fun loaded(): Store {
        store?.let { return it }
        val s = Store()
        try {
            val f = file
            if (f != null && f.exists()) {
                val root = Json.obj(f.readBytes())
                for (w in root?.get("ways").asMaps() ?: emptyList()) {
                    val line = (w["line"].asList() ?: emptyList()).mapNotNull { p -> p.asList()?.let { LatLng(it[0].num()!!, it[1].num()!!) } }
                    val id = w["id"].long() ?: continue
                    s.ways[id] = Overpass.LimitWay(id, w["kmh"].num(), line)
                }
                for (c in root?.get("cameras").asMaps() ?: emptyList()) {
                    val id = c["id"].long() ?: continue
                    s.cameras[id] = RoadCamera(
                        id, if (c["kind"] == "plate") RoadCamera.Kind.PLATE else RoadCamera.Kind.SPEED,
                        LatLng(c["lat"].num()!!, c["lng"].num()!!), c["operator"].text(), c["kmh"].num(),
                        c["average"] == true, c["directions"].asList()?.mapNotNull { it.num() },
                    )
                }
                for (g in root?.get("signals").asMaps() ?: emptyList()) {
                    val id = g["id"].long() ?: continue
                    s.signals[id] = LatLng(g["lat"].num()!!, g["lng"].num()!!)
                }
            }
        } catch (e: Exception) {
            // A damaged file starts the cache over.
        }
        store = s
        return s
    }

    /** Remembers one Overpass answer. */
    fun add(ways: List<Overpass.LimitWay>, cameras: List<RoadCamera>, signals: List<Overpass.SignalNode>) {
        var start: Boolean
        synchronized(lock) {
            val s = loaded()
            for (w in ways) if (w.id != 0L) s.ways[w.id] = w
            for (c in cameras) if (c.id != 0L) s.cameras[c.id] = c.copy(quiet = false)
            for (g in signals) if (g.id != 0L) s.signals[g.id] = g.at
            if (s.ways.size > maxWays) {
                for (id in s.ways.keys.sorted().take(s.ways.size - maxWays / 2)) s.ways.remove(id)
            }
            start = !saving && file != null
            if (start) saving = true
        }
        // One write a few seconds later covers a burst of answers.
        if (start) thread(isDaemon = true) {
            Thread.sleep(3000)
            save()
        }
    }

    /**
     * Drops remembered cameras within [meters] of [line] that a live answer for that stretch no
     * longer holds ([kept] is what it did hold): a camera taken off the map, or marked not working,
     * used to be warned about at the start of every later drive. Only the kinds that were asked
     * for, and a little inside the 150 m the question covers.
     */
    fun forgetCameras(line: List<LatLng>, kept: Set<Long>, speed: Boolean, plate: Boolean, meters: Double = 100.0) {
        if (line.size < 2 || !(speed || plate)) return
        val pad = 0.003
        val s0 = line.minOf { it.lat } - pad
        val n0 = line.maxOf { it.lat } + pad
        val w0 = line.minOf { it.lng } - pad
        val e0 = line.maxOf { it.lng } + pad
        val cum = Geo.cumulative(line)
        var start: Boolean
        synchronized(lock) {
            val s = loaded()
            val gone = s.cameras.values.filter { c ->
                c.id !in kept && (if (c.kind == RoadCamera.Kind.SPEED) speed else plate) &&
                    c.location.lat in s0..n0 && c.location.lng in w0..e0 &&
                    Geo.project(c.location, line, cum).offset <= meters
            }
            if (gone.isEmpty()) return
            for (c in gone) s.cameras.remove(c.id)
            start = !saving && file != null
            if (start) saving = true
        }
        if (start) thread(isDaemon = true) {
            Thread.sleep(3000)
            save()
        }
    }

    /** Writes now, rather than a few seconds after the last answer. */
    fun flush() = save()

    private fun save() {
        val text: String
        synchronized(lock) {
            saving = false
            val s = store ?: return
            text = Json.write(mapOf(
                "ways" to s.ways.values.map { mapOf("id" to it.id, "kmh" to it.kmh, "line" to it.line.map { p -> listOf(p.lat, p.lng) }) },
                "cameras" to s.cameras.values.map {
                    mapOf("id" to it.id, "kind" to if (it.kind == RoadCamera.Kind.PLATE) "plate" else "speed",
                        "lat" to it.location.lat, "lng" to it.location.lng, "operator" to it.operatorName,
                        "kmh" to it.maxspeedKmh, "average" to it.average, "directions" to it.directions)
                },
                "signals" to s.signals.map { (id, p) -> mapOf("id" to id, "lat" to p.lat, "lng" to p.lng) },
            ))
        }
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
        } catch (e: Exception) {
        }
    }

    data class Known(val cameras: List<RoadCamera>, val ways: List<Overpass.LimitWay>, val signals: List<LatLng>)

    /** What's known within about 200 m of [line]: the ways, cameras and lights a live answer would hold. */
    fun along(line: List<LatLng>): Known {
        if (line.size < 2) return Known(emptyList(), emptyList(), emptyList())
        val s = synchronized(lock) { loaded() }
        val (ways0, cams0, sig0) = synchronized(lock) { Triple(s.ways.values.toList(), s.cameras.values.toList(), s.signals.values.toList()) }
        if (ways0.isEmpty() && cams0.isEmpty() && sig0.isEmpty()) return Known(emptyList(), emptyList(), emptyList())
        val cell = 0.005
        val cells = HashSet<Long>()
        fun key(lat: Double, lng: Double): Long = (floor(lat / cell).toLong() shl 32) or (floor(lng / cell).toLong() and 0xFFFF_FFFFL)
        val cum = Geo.cumulative(line)
        val samples = line.toMutableList()
        var m = 0.0
        while (m <= cum.last()) { samples.add(Geo.point(line, m, cum)); m += 200 }
        for (p in samples) for (dy in listOf(-cell, 0.0, cell)) for (dx in listOf(-cell, 0.0, cell)) cells.add(key(p.lat + dy, p.lng + dx))
        fun near(p: LatLng) = key(p.lat, p.lng) in cells
        return Known(cams0.filter { near(it.location) }, ways0.filter { w -> w.line.any { near(it) } },
            Overpass.signalsOnRoute(sig0.filter { near(it) }, line))
    }
}
