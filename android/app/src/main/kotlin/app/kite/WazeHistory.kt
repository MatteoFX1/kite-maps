package app.kite

import app.kite.core.*
import kotlinx.coroutines.*
import java.io.File

/**
 * Every police report Kite has seen from Waze, kept on this phone for the heat map. Waze only
 * shares what's live right now, so the map of where police usually are builds up as you use Kite.
 */
object WazeHistory {
    data class Sighting(val id: String, val lat: Double, val lng: Double, val t: Long)

    var sightings: List<Sighting> = emptyList(); private set
    private val ids = mutableSetOf<String>()
    private val file by lazy { File(KiteApp.app.filesDir, "waze-police.json") }
    /** Called after new reports are added, so the map can redraw the heat map. */
    var onChange: (() -> Unit)? = null
    private const val LIMIT = 6000
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        val cutoff = System.currentTimeMillis() - 90L * 86_400_000
        sightings = runCatching { Json.parse(file.readText()).asMaps() }.getOrNull()?.mapNotNull {
            Sighting(it["id"].text() ?: return@mapNotNull null, it["lat"].num() ?: return@mapNotNull null,
                it["lng"].num() ?: return@mapNotNull null, it["t"].long() ?: return@mapNotNull null)
        }?.filter { it.t > cutoff } ?: emptyList()
        ids.clear(); ids.addAll(sightings.map { it.id })
    }

    /** For the risk score. */
    val reports: List<PoliceRisk.Report> get() { load(); return sightings.map { PoliceRisk.Report(LatLng(it.lat, it.lng), it.t) } }

    val all: List<Sighting> get() { load(); return sightings }

    /** Adds the police reports not seen before. True when anything was added. */
    fun record(alerts: List<WazeAlert>): Boolean {
        load()
        val fresh = alerts.filter { it.kind == WazeAlert.Kind.POLICE && !ids.contains(it.id) }
        if (fresh.isEmpty()) return false
        var list = sightings + fresh.map { Sighting(it.id, it.location.lat, it.location.lng, it.reportedAtMillis) }
        fresh.forEach { ids.add(it.id) }
        if (list.size > LIMIT) {
            list = list.takeLast(LIMIT)
            ids.clear(); ids.addAll(list.map { it.id })
        }
        sightings = list
        save()
        onChange?.invoke()
        return true
    }

    /** Forget every remembered report (Settings › Road alerts). */
    fun clear() {
        sightings = emptyList()
        ids.clear()
        save()
        onChange?.invoke()
    }

    private var sweptAt = 0L
    private var sweeping = false

    /** While Kite is open, every 10 minutes, also asks Waze about the areas around [places]. */
    fun sweepIfDue(places: List<LatLng>, scope: CoroutineScope) {
        if (sweeping || System.currentTimeMillis() - sweptAt < 600_000 || places.isEmpty()) return
        sweeping = true
        sweptAt = System.currentTimeMillis()
        scope.launch {
            for (p in places.take(4)) {
                val d = 0.12
                runCatching { Waze.alerts(p.lat - d, p.lng - d * 1.6, p.lat + d, p.lng + d * 1.6) }.getOrNull()?.let { record(it) }
            }
            sweeping = false
        }
    }

    /** One write at a time, in order, so two quick saves never mix up the same file. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = Dispatchers.IO.limitedParallelism(1)

    private fun save() {
        val data = Json.write(sightings.map { mapOf("id" to it.id, "lat" to it.lat, "lng" to it.lng, "t" to it.t) })
        CoroutineScope(writer).launch {
            runCatching {
                val tmp = File(file.parentFile, "waze-police.tmp")
                tmp.writeText(data)
                tmp.renameTo(file)
            }
        }
    }
}
