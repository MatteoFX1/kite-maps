package app.kite.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.kite.core.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.camera.CameraPosition as MlCamera

/** Where the camera is: like MapLibre Android's, but with the padding in dp (left, top, right, bottom). */
data class CameraPosition(
    val target: LatLng = LatLng(51.5074, -0.1278),
    val zoom: Double = 11.0,
    val bearing: Double = 0.0,
    val tilt: Double = 0.0,
    val padding: DoubleArray = DoubleArray(4),
) {
    class Builder(from: CameraPosition = CameraPosition()) {
        private var p = from
        fun target(t: LatLng) = apply { p = p.copy(target = t) }
        fun zoom(z: Double) = apply { p = p.copy(zoom = z) }
        fun bearing(b: Double) = apply { p = p.copy(bearing = b) }
        fun tilt(t: Double) = apply { p = p.copy(tilt = t) }
        /** In dp. */
        fun padding(left: Double, top: Double, right: Double, bottom: Double) = apply { p = p.copy(padding = doubleArrayOf(left, top, right, bottom)) }
        fun build() = p
    }

    internal fun ml() = MlCamera(Position(target.lng, target.lat), zoom, bearing, tilt,
        DpPadding(padding[0].dp, padding[1].dp, padding[2].dp, padding[3].dp))

    override fun equals(other: Any?) = other is CameraPosition && target == other.target && zoom == other.zoom &&
        bearing == other.bearing && tilt == other.tilt && padding.contentEquals(other.padding)
    override fun hashCode() = target.hashCode() * 31 + zoom.hashCode()

    companion object {
        internal fun from(c: MlCamera) = CameraPosition(LatLng(c.center.latitude, c.center.longitude), c.zoom, c.bearing, c.pitch,
            doubleArrayOf(c.padding.left.value.toDouble(), c.padding.top.value.toDouble(), c.padding.right.value.toDouble(), c.padding.bottom.value.toDouble()))
    }
}

/** A change to the camera, made from where it is now. */
fun interface CameraChange { fun apply(now: CameraPosition): CameraPosition }

object CameraUpdateFactory {
    fun newCameraPosition(p: CameraPosition) = CameraChange { p }
    fun newLatLngZoom(t: LatLng, zoom: Double) = CameraChange { it.copy(target = t, zoom = zoom) }
}

data class LatLngBounds(val latitudeNorth: Double, val longitudeEast: Double, val latitudeSouth: Double, val longitudeWest: Double) {
    class Builder {
        private val pts = mutableListOf<LatLng>()
        fun include(p: LatLng) = apply { pts.add(p) }
        fun build() = LatLngBounds(pts.maxOf { it.lat }, pts.maxOf { it.lng }, pts.minOf { it.lat }, pts.minOf { it.lng })
    }
}

/** A spot on the screen, in dp from the map's top left. */
data class ScreenPoint(val x: Float, val y: Float)

/** A feature found on the map at a spot: its shape and its properties. */
class FoundFeature(private val props: Map<String, Any?>, val point: LatLng?, val lines: List<List<LatLng>>) {
    fun string(k: String): String? = props[k]?.let { it as? String ?: it.toString() }
    fun number(k: String): Double? = (props[k] as? Number)?.toDouble() ?: (props[k] as? String)?.toDoubleOrNull()
    fun has(k: String) = props.containsKey(k)
}

/**
 * The running map, for code that moves the camera, asks what's at a spot, or changes the style:
 * a thin layer over maplibre-compose's [MapState], shaped like MapLibre Android's map object.
 */
class MapHandle internal constructor(val state: MapState, private val scope: CoroutineScope) {
    var style: Style? = null
        internal set
    private var animation: Job? = null

    val cameraPosition: CameraPosition get() = CameraPosition.from(state.cameraPosition)

    fun moveCamera(change: CameraChange) {
        animation?.cancel(); animation = null
        runCatching { state.setCameraPosition(change.apply(cameraPosition).ml()) }
    }

    fun animateCamera(change: CameraChange, ms: Int = 300) {
        val to = change.apply(cameraPosition)
        if (ms <= 0) { moveCamera(change); return }
        animation?.cancel()
        animation = scope.launch {
            runCatching {
                val c = to.ml()
                state.animateCamera(CameraUpdate(c.center, c.zoom, c.bearing, c.pitch, c.padding),
                    CameraAnimation.Ease { duration = ms.milliseconds })
            }
        }
    }

    /** The camera that shows [b] with [padding] dp round it (left, top, right, bottom), facing north and flat. */
    suspend fun cameraFor(b: LatLngBounds, padding: IntArray): CameraPosition? = runCatching {
        val box = BoundingBox(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth)
        CameraPosition.from(state.cameraForBounds(box, CameraFit(0.0, 0.0, DpPadding.Zero,
            DpPadding(padding[0].dp, padding[1].dp, padding[2].dp, padding[3].dp))))
    }.getOrNull()

    fun toScreenLocation(p: LatLng): ScreenPoint? =
        state.screenLocationFromPosition(Position(p.lng, p.lat))?.let { ScreenPoint(it.x.value, it.y.value) }

    fun fromScreenLocation(p: ScreenPoint): LatLng? =
        state.positionFromScreenLocation(DpOffset(p.x.dp, p.y.dp))?.let { LatLng(it.latitude, it.longitude) }

    /** What's on screen now, or null before the map has a size. */
    val visibleBounds: LatLngBounds?
        get() = state.getVisibleBounds()?.let { LatLngBounds(it.north, it.east, it.south, it.west) }

    /** The features of [layers] drawn inside the box (in dp), top first. */
    suspend fun queryRenderedFeatures(left: Float, top: Float, right: Float, bottom: Float, vararg layers: String): List<FoundFeature> =
        runCatching {
            state.queryRenderedFeatures(DpRect(left.dp, top.dp, right.dp, bottom.dp), layers.toSet()).map { f -> found(f) }
        }.getOrDefault(emptyList())

    private fun found(f: org.maplibre.spatialk.geojson.Feature<org.maplibre.spatialk.geojson.Geometry, kotlinx.serialization.json.JsonObject?>): FoundFeature {
        val props = f.properties?.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.let { p -> if (p.isString) p.contentOrNull else p.booleanOrNull ?: p.doubleOrNull ?: p.contentOrNull }
        } ?: emptyMap()
        fun ll(p: Position) = LatLng(p.latitude, p.longitude)
        return when (val g = f.geometry) {
            is org.maplibre.spatialk.geojson.Point -> FoundFeature(props, ll(g.coordinates), emptyList())
            is org.maplibre.spatialk.geojson.LineString -> FoundFeature(props, null, listOf(g.coordinates.map(::ll)))
            is org.maplibre.spatialk.geojson.MultiLineString -> FoundFeature(props, null, g.coordinates.map { l -> l.map(::ll) })
            else -> FoundFeature(props, null, emptyList())
        }
    }
}
