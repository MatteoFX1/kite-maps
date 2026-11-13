package app.kite.core

import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "Merge onto M5" only where you really have to merge (the iPhone's Merges.swift). Where a slip
 * road joins and its lane carries on as a lane of the main road (the M5 at Almondsbury has 3
 * lanes, the slip road from the M4 adds a 4th), there's nothing to merge into, and Waze says
 * nothing. The road matcher knows the lanes of every road meeting at the join: a lane gain is
 * when the road out has as many lanes as the roads in put together.
 */
object Merges {
    private const val trace = "https://valhalla1.openstreetmap.de/trace_attributes"
    private const val locate = "https://valhalla1.openstreetmap.de/locate"
    private val client = Http.client(15)

    /** A join on the route: its place, the lanes of the route's road in and out, and headings back along each other road coming in. */
    data class Join(val at: LatLng, val inLanes: Int, val outLanes: Int, val others: List<Double>)

    /** Where along the route each merge that's really a lane gain is; null when there's none, or no answer. */
    suspend fun laneGains(steps: List<Maneuver>, line: List<LatLng>): Set<Double>? {
        val merges = steps.filter { it.type == ManeuverType.MERGE && !it.silent }
        if (merges.isEmpty() || line.size < 2) return null
        val cum = Geo.cumulative(line)
        val out = mutableSetOf<Double>()
        for (m in merges.take(12)) {
            val a = m.alongMeters
            // The router's merge can be a few hundred metres off where the roads actually join
            // (Almondsbury, 330 m on): the join nearest it, within 400 m either way.
            val from = max(0.0, a - 400)
            val to = min(cum.last(), a + 400)
            val piece = Geo.slice(line, from, to, cum)
            if (piece.size < 2) continue
            val data = post(trace, traceBody(Valhalla.thin(piece))) ?: continue
            val pc = Geo.cumulative(piece)
            val join = joins(data).filter { it.others.isNotEmpty() && Geo.project(it.at, piece, pc).offset < 40 }
                .minByOrNull { abs(Geo.project(it.at, piece, pc).along - (a - from)) } ?: continue
            val joining = othersLanes(join) ?: continue
            if (joining.size == join.others.size && join.outLanes >= join.inLanes + joining.sum()) out.add(a)
        }
        return out.ifEmpty { null }
    }

    fun traceBody(shape: List<LatLng>): Map<String, Any?> = mapOf(
        "shape" to shape.map { mapOf("lat" to it.lat, "lon" to it.lng) },
        "costing" to "auto", "shape_match" to "map_snap",
        "filters" to mapOf(
            "attributes" to listOf("edge.lane_count", "edge.begin_shape_index", "edge.end_shape_index", "shape",
                "node.intersecting_edge.begin_heading", "node.intersecting_edge.driveability"),
            "action" to "include",
        ),
    )

    private suspend fun post(url: String, body: Any?): ByteArray? = try {
        val r = Http.post(url, Json.write(body).toByteArray(), "application/json", mapOf("User-Agent" to OSRM.userAgent), client)
        if (r.code == 200) r.body else null
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /** Every point on the matched road where another road only comes in (one way, toward the route). */
    fun joins(data: ByteArray): List<Join> {
        val root = Json.obj(data) ?: return emptyList()
        val edges = root["edges"].asMaps() ?: return emptyList()
        val shape = (root["shape"] as? String)?.let { Geo.decodePolyline(it) } ?: emptyList()
        val out = mutableListOf<Join>()
        for (k in 0 until edges.size - 1) {
            val e = edges[k]
            val inLanes = e["lane_count"].int()?.takeIf { it > 0 } ?: continue
            val outLanes = edges[k + 1]["lane_count"].int()?.takeIf { it > 0 } ?: continue
            val b = e["end_shape_index"].int()?.takeIf { it in shape.indices } ?: continue
            val node = e["end_node"] as? Map<*, *> ?: continue
            // "backward": traffic only comes toward the join along it.
            val others = ((node["intersecting_edges"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { x ->
                val m = x as? Map<*, *> ?: return@mapNotNull null
                if (m["driveability"] != "backward") null else m["begin_heading"].num()
            }
            out.add(Join(shape[b], inLanes, outLanes, others))
        }
        return out
    }

    /** The lanes of each other road coming in, 40 m back along it; null when the matcher doesn't answer. */
    private suspend fun othersLanes(join: Join): List<Int>? {
        val k = cos(Math.toRadians(join.at.lat)) * 111_320.0
        val points = join.others.map { h -> LatLng(join.at.lat + 40 * cos(Math.toRadians(h)) / 110_540, join.at.lng + 40 * sin(Math.toRadians(h)) / k) }
        val data = post(locate, mapOf("locations" to points.map { mapOf("lat" to it.lat, "lon" to it.lng) }, "costing" to "auto", "verbose" to true)) ?: return null
        val results = (Json.parse(data) as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: return null
        if (results.size != points.size) return null
        return results.zip(join.others).map { (r, h) ->
            // The road heading toward the join: the opposite of the way back along it.
            val toward = (h + 180) % 360
            r["edges"].asMaps().orEmpty().mapNotNull { e ->
                val n = (e["edge"] as? Map<*, *>)?.get("lane_count").int()?.takeIf { it > 0 } ?: return@mapNotNull null
                val heading = e["heading"].num() ?: return@mapNotNull null
                n to Geo.angleGap(heading, toward)
            }.filter { it.second < 40 }.minByOrNull { it.second }?.first ?: return null
        }
    }
}
