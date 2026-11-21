package app.kite.map

import app.kite.core.Json
import app.kite.core.LatLng
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSourceHandle
import org.maplibre.compose.util.PreparedImage
import androidx.compose.ui.graphics.ImageBitmap

/*
 * Kite's map style, kept as plain JSON: OpenFreeMap Liberty's layers with Kite's own sources and
 * layers added in the right places. It is handed to the map whole when the map starts; after that
 * every change (a colour for dark mode, a layer shown or hidden, new route data) goes straight to
 * the running map as well as into the JSON, so both always agree. The names follow MapLibre's
 * Android ones, which the map code was first written against.
 */

/** A MapLibre style expression, as the JSON the style spec uses. */
class Expression internal constructor(val json: Any?) {
    class Stop internal constructor(val input: Any?, val output: Any?)

    companion object {
        fun get(key: String) = Expression(listOf("get", key))
        fun has(key: String) = Expression(listOf("has", key))
        fun not(e: Expression) = Expression(listOf("!", e.json))
        fun zoom() = Expression(listOf("zoom"))
        fun lineProgress() = Expression(listOf("line-progress"))
        fun linear() = Expression(listOf("linear"))
        fun exponential(base: Number) = Expression(listOf("exponential", base))
        fun literal(v: Any?): Expression = Expression(when (v) {
            is Expression -> v.json
            is Array<*> -> listOf("literal", v.map { plain(it) })
            is List<*> -> listOf("literal", v.map { plain(it) })
            else -> plain(v)
        })
        fun stop(input: Any, output: Any?) = Stop(plain(input), plain(output))
        fun interpolate(type: Expression, input: Expression, vararg stops: Stop) =
            Expression(listOf("interpolate", type.json, input.json) + stops.flatMap { listOf(it.input, it.output) })
        fun step(input: Expression, default: Any?, vararg stops: Stop) =
            Expression(listOf("step", input.json, plain(default)) + stops.flatMap { listOf(it.input, it.output) })
        fun eq(a: Any?, b: Any?) = Expression(listOf("==", plain(a), plain(b)))
        fun any(vararg e: Expression) = Expression(listOf("any") + e.map { it.json })
        fun all(vararg e: Expression) = Expression(listOf("all") + e.map { it.json })
        fun switchCase(vararg parts: Any?) = Expression(listOf("case") + parts.map { plain(it) })
        fun coalesce(vararg e: Any?) = Expression(listOf("coalesce") + e.map { plain(it) })
        fun color(argb: Int) = Expression(rgba(argb))
        fun toColor(e: Expression) = Expression(listOf("to-color", e.json))
        fun toString(e: Expression) = Expression(listOf("to-string", e.json))
        /** An expression written as JSON text. */
        fun raw(json: String) = Expression(Json.parse(json))

        /** A number, string or expression as JSON. Floats are written with their usual digits (0.6, not 0.6000000238). */
        internal fun plain(v: Any?): Any? = when (v) {
            is Expression -> v.json
            is Float -> v.toString().toDouble()
            is FloatArray -> v.map { it.toString().toDouble() }
            is Array<*> -> v.map { plain(it) }
            else -> v
        }

        /** An Android-style ARGB colour as the style spec's "rgba(...)" list. */
        internal fun rgba(argb: Int): List<Any> = listOf("rgba", (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF,
            ((argb ushr 24) and 0xFF) / 255.0)
    }
}

/** One layout or paint property and its value (JSON, or null for "back to the style's default"). */
class PropertyValue<T>(val name: String, val value: Any?) {
    val isLayout: Boolean get() = isLayoutProperty(name)
    override fun toString() = "$name=${Json.write(value)}"

    companion object {
        private val paintSuffixes = listOf("-opacity", "-color", "-halo-color", "-halo-width", "-halo-blur", "-translate", "-translate-anchor")
        fun isLayoutProperty(name: String): Boolean {
            if (name == "visibility" || name == "line-cap" || name == "line-join" || name == "line-miter-limit" ||
                name == "line-round-limit" || name.endsWith("-sort-key")) return true
            if (name.startsWith("icon-") || name.startsWith("text-")) return paintSuffixes.none { name.endsWith(it) }
            return name.startsWith("symbol-")
        }
    }
}

object Property {
    const val VISIBLE = "visible"
    const val NONE = "none"
    const val LINE_CAP_ROUND = "round"
    const val LINE_JOIN_ROUND = "round"
    const val SYMBOL_PLACEMENT_LINE = "line"
    const val SYMBOL_PLACEMENT_LINE_CENTER = "line-center"
    const val ICON_ROTATION_ALIGNMENT_MAP = "map"
    const val ICON_PITCH_ALIGNMENT_MAP = "map"
    const val ICON_ANCHOR_BOTTOM = "bottom"
    const val ICON_TEXT_FIT_BOTH = "both"
    const val TEXT_ANCHOR_TOP = "top"
    const val TEXT_ANCHOR_LEFT = "left"
    const val TEXT_JUSTIFY_LEFT = "left"
    const val TEXT_PITCH_ALIGNMENT_MAP = "map"
    const val TEXT_PITCH_ALIGNMENT_VIEWPORT = "viewport"
    const val TEXT_ROTATION_ALIGNMENT_MAP = "map"
    const val TEXT_TRANSFORM_UPPERCASE = "uppercase"
}

/** A layer: its definition as JSON, and (once the map runs) the live layer it writes through to. */
open class Layer internal constructor(val id: String, val type: String, source: String?, json: Map<String, Any?>? = null) {
    internal val root: MutableMap<String, Any?> = json?.toMutableMap() ?: linkedMapOf<String, Any?>("id" to id, "type" to type).also {
        if (source != null) it["source"] = source
    }
    private val layout: MutableMap<String, Any?> = (root["layout"] as? Map<String, Any?>)?.toMutableMap() ?: linkedMapOf()
    private val paint: MutableMap<String, Any?> = (root["paint"] as? Map<String, Any?>)?.toMutableMap() ?: linkedMapOf()
    internal var style: Style? = null

    val sourceId: String? get() = root["source"] as? String

    var sourceLayer: String?
        get() = root["source-layer"] as? String
        set(v) { root["source-layer"] = v }

    var minZoom: Float
        get() = (root["minzoom"] as? Number)?.toFloat() ?: 0f
        set(v) { root["minzoom"] = Expression.plain(v); style?.liveRoot(this, "minzoom", root["minzoom"]) }

    fun setFilter(e: Expression) { root["filter"] = e.json; style?.liveRoot(this, "filter", e.json) }

    fun setProperties(vararg props: PropertyValue<*>) {
        for (p in props) {
            val target = if (p.isLayout) layout else paint
            if (p.value == null) target.remove(p.name) else target[p.name] = p.value
            style?.liveProperty(this, p)
        }
    }

    fun withProperties(vararg props: PropertyValue<*>): Layer { setProperties(*props); return this }

    /** The value the style has now for [name], or null when it uses the default. */
    fun property(name: String): PropertyValue<Any?> =
        PropertyValue(name, if (PropertyValue.isLayoutProperty(name)) layout[name] else paint[name])

    val visibility get() = property("visibility")
    val symbolSpacing get() = property("symbol-spacing")
    val iconPadding get() = property("icon-padding")
    val lineOpacity get() = property("line-opacity")
    val textSize get() = property("text-size")
    val textColor get() = property("text-color")
    val textHaloColor get() = property("text-halo-color")
    val textHaloWidth get() = property("text-halo-width")

    internal fun json(): Map<String, Any?> = LinkedHashMap(root).also {
        if (layout.isNotEmpty()) it["layout"] = LinkedHashMap(layout) else it.remove("layout")
        if (paint.isNotEmpty()) it["paint"] = LinkedHashMap(paint) else it.remove("paint")
    }
}

class LineLayer(id: String, source: String) : Layer(id, "line", source)
class SymbolLayer(id: String, source: String) : Layer(id, "symbol", source)
class FillLayer(id: String, source: String) : Layer(id, "fill", source)
class CircleLayer(id: String, source: String) : Layer(id, "circle", source)
class HeatmapLayer(id: String, source: String) : Layer(id, "heatmap", source)
class RasterLayer(id: String, source: String) : Layer(id, "raster", source)

internal fun layerFrom(json: Map<String, Any?>): Layer =
    Layer(json["id"] as? String ?: "", json["type"] as? String ?: "", json["source"] as? String, json)

val Layer.isSymbol get() = type == "symbol"
val Layer.isLine get() = type == "line"
val Layer.isFill get() = type == "fill"
val Layer.isFillExtrusion get() = type == "fill-extrusion"
val Layer.isBackground get() = type == "background"

// Properties, by MapLibre's Android names.

private fun <T> p(name: String, v: Any?) = PropertyValue<T>(name, Expression.plain(v))
private fun colour(name: String, argb: Int) = PropertyValue<String>(name, Expression.rgba(argb))

fun visibility(v: String) = p<String>("visibility", v)
fun lineColor(c: Int) = colour("line-color", c)
fun lineColor(e: Expression) = p<String>("line-color", e)
fun lineWidth(v: Float) = p<Float>("line-width", v)
fun lineWidth(e: Expression) = p<Float>("line-width", e)
fun lineOpacity(v: Float) = p<Float>("line-opacity", v)
fun lineOpacity(e: Expression) = p<Float>("line-opacity", e)
fun lineCap(v: String) = p<String>("line-cap", v)
fun lineJoin(v: String) = p<String>("line-join", v)
fun lineBlur(v: Float) = p<Float>("line-blur", v)
fun lineTranslate(v: Array<Float>) = p<Array<Float>>("line-translate", v)
fun lineDasharray(v: Array<Float>) = p<Array<Float>>("line-dasharray", v)
fun lineGradient(e: Expression) = p<String>("line-gradient", e)
fun fillColor(c: Int) = colour("fill-color", c)
fun fillColor(e: Expression) = p<String>("fill-color", e)
fun fillOutlineColor(c: Int) = colour("fill-outline-color", c)
fun fillOpacity(v: Float) = p<Float>("fill-opacity", v)
fun fillExtrusionColor(c: Int) = colour("fill-extrusion-color", c)
fun backgroundColor(c: Int) = colour("background-color", c)
fun circleColor(c: Int) = colour("circle-color", c)
fun circleOpacity(v: Float) = p<Float>("circle-opacity", v)
fun circleStrokeColor(c: Int) = colour("circle-stroke-color", c)
fun circleStrokeWidth(v: Float) = p<Float>("circle-stroke-width", v)
fun circleRadius(e: Expression) = p<Float>("circle-radius", e)
fun circleRadius(v: Float) = p<Float>("circle-radius", v)
fun rasterOpacity(v: Float) = p<Float>("raster-opacity", v)
fun rasterOpacity(e: Expression) = p<Float>("raster-opacity", e)
fun rasterFadeDuration(v: Float) = p<Float>("raster-fade-duration", v)
fun iconImage(v: String) = p<String>("icon-image", v)
fun iconImage(e: Expression) = p<String>("icon-image", e)
fun iconAllowOverlap(v: Boolean) = p<Boolean>("icon-allow-overlap", v)
fun iconIgnorePlacement(v: Boolean) = p<Boolean>("icon-ignore-placement", v)
fun iconKeepUpright(v: Boolean) = p<Boolean>("icon-keep-upright", v)
fun iconSize(v: Float) = p<Float>("icon-size", v)
fun iconSize(e: Expression) = p<Float>("icon-size", e)
fun iconOpacity(v: Float) = p<Float>("icon-opacity", v)
fun iconOpacity(e: Expression) = p<Float>("icon-opacity", e)
fun iconRotate(e: Expression) = p<Float>("icon-rotate", e)
fun iconRotationAlignment(v: String) = p<String>("icon-rotation-alignment", v)
fun iconPitchAlignment(v: String) = p<String>("icon-pitch-alignment", v)
fun iconAnchor(v: String) = p<String>("icon-anchor", v)
fun iconAnchor(e: Expression) = p<String>("icon-anchor", e)
fun iconPadding(v: Float) = p<Float>("icon-padding", v)
fun iconTextFit(v: String) = p<String>("icon-text-fit", v)
fun iconTextFitPadding(v: Array<Float>) = p<Array<Float>>("icon-text-fit-padding", v)
fun textField(v: String) = p<String>("text-field", v)
fun textField(e: Expression) = p<String>("text-field", e)
fun textFont(v: Array<String>) = p<Array<String>>("text-font", v)
fun textSize(v: Float) = p<Float>("text-size", v)
fun textSize(e: Expression) = p<Float>("text-size", e)
fun textColor(c: Int) = colour("text-color", c)
fun textHaloColor(c: Int) = colour("text-halo-color", c)
fun textHaloColor(css: String) = p<String>("text-halo-color", css)
fun textHaloWidth(v: Float) = p<Float>("text-halo-width", v)
fun textAnchor(v: String) = p<String>("text-anchor", v)
fun textOffset(v: Array<Float>) = p<Array<Float>>("text-offset", v)
fun textOptional(v: Boolean) = p<Boolean>("text-optional", v)
fun textMaxWidth(v: Float) = p<Float>("text-max-width", v)
fun textJustify(v: String) = p<String>("text-justify", v)
fun textAllowOverlap(v: Boolean) = p<Boolean>("text-allow-overlap", v)
fun textIgnorePlacement(v: Boolean) = p<Boolean>("text-ignore-placement", v)
fun textPitchAlignment(v: String) = p<String>("text-pitch-alignment", v)
fun textRotationAlignment(v: String) = p<String>("text-rotation-alignment", v)
fun textKeepUpright(v: Boolean) = p<Boolean>("text-keep-upright", v)
fun textLetterSpacing(v: Float) = p<Float>("text-letter-spacing", v)
fun textTransform(v: String) = p<String>("text-transform", v)
fun symbolPlacement(v: String) = p<String>("symbol-placement", v)
fun symbolSpacing(v: Float) = p<Float>("symbol-spacing", v)
fun symbolSortKey(e: Expression) = p<Float>("symbol-sort-key", e)

// GeoJSON

class Feature internal constructor(val geometry: Map<String, Any?>, val properties: MutableMap<String, Any?> = linkedMapOf()) {
    fun addStringProperty(k: String, v: String) { properties[k] = v }
    fun addNumberProperty(k: String, v: Number) { properties[k] = v }
    fun addBooleanProperty(k: String, v: Boolean) { properties[k] = v }
    fun json(): Map<String, Any?> = mapOf("type" to "Feature", "geometry" to geometry, "properties" to properties)

    companion object {
        fun point(p: LatLng, props: Map<String, Any?> = emptyMap()) =
            Feature(mapOf("type" to "Point", "coordinates" to listOf(p.lng, p.lat)), props.filterValues { it != null }.toMutableMap())
        fun line(pts: List<LatLng>, props: Map<String, Any?> = emptyMap()) =
            Feature(mapOf("type" to "LineString", "coordinates" to pts.map { listOf(it.lng, it.lat) }), props.filterValues { it != null }.toMutableMap())
    }
}

class GeoJsonOptions {
    internal val json = linkedMapOf<String, Any?>()
    fun withCluster(on: Boolean) = apply { json["cluster"] = on }
    fun withClusterRadius(r: Int) = apply { json["clusterRadius"] = r }
    fun withClusterMaxZoom(z: Int) = apply { json["clusterMaxZoom"] = z }
    fun withLineMetrics(on: Boolean) = apply { if (on) json["lineMetrics"] = true }
}

private fun collection(features: List<Feature>) = mapOf("type" to "FeatureCollection", "features" to features.map { it.json() })

open class Source internal constructor(val id: String, internal val json: MutableMap<String, Any?>)

class GeoJsonSource(id: String, features: List<Feature> = emptyList(), options: GeoJsonOptions = GeoJsonOptions()) :
    Source(id, (linkedMapOf<String, Any?>("type" to "geojson", "data" to collection(features)) + options.json).toMutableMap()) {
    internal var style: Style? = null
    /** Set when new data came before the map was running; sent once it is. */
    internal var pending = false

    fun setGeoJson(features: List<Feature>) = setData(collection(features))
    fun setGeoJson(feature: Feature) = setData(feature.json())

    private fun setData(data: Map<String, Any?>) {
        json["data"] = data
        val s = style
        if (s == null || !s.liveData(this)) pending = true
    }
}

class RasterSource(id: String, tiles: String, maxZoom: Int, attribution: String? = null, tileSize: Int = 256, minZoom: Int = 0) :
    Source(id, linkedMapOf<String, Any?>("type" to "raster", "tiles" to listOf(tiles), "tileSize" to tileSize, "maxzoom" to maxZoom).also {
        if (minZoom > 0) it["minzoom"] = minZoom
        if (attribution != null) it["attribution"] = attribution
    })

/**
 * The whole style: the base map's sources and layers, Kite's added ones, and the icons. Built
 * before the map starts; [goLive] then ties it to the running map.
 */
class Style(base: Map<String, Any?>) {
    private val top: MutableMap<String, Any?> = base.toMutableMap()
    private val sources = linkedMapOf<String, Source>()
    private val baseSources: MutableMap<String, Any?> = ((base["sources"] as? Map<String, Any?>) ?: emptyMap()).toMutableMap()
    private val list: MutableList<Layer> = ((base["layers"] as? List<*>) ?: emptyList<Any?>())
        .mapNotNull { (it as? Map<String, Any?>)?.let(::layerFrom) }.toMutableList()
    private val images = linkedMapOf<String, ImageBitmap>()
    private var map: MapState? = null

    init { list.forEach { it.style = this } }

    val layers: List<Layer> get() = list
    val isLive get() = map != null

    fun getLayer(id: String): Layer? = list.firstOrNull { it.id == id }
    fun hasSource(id: String) = sources.containsKey(id) || baseSources.containsKey(id)
    fun getSource(id: String): Source? = sources[id]

    fun addSource(s: Source) {
        if (isLive) return // The running map can't take new sources here; every one is added before it starts.
        sources[s.id] = s
        (s as? GeoJsonSource)?.style = this
    }

    fun addLayer(l: Layer) { insert(l, list.size) }
    fun addLayerBelow(l: Layer, below: String) { insert(l, list.indexOfFirst { it.id == below }.takeIf { it >= 0 } ?: list.size) }
    fun addLayerAbove(l: Layer, above: String) { insert(l, list.indexOfFirst { it.id == above }.takeIf { it >= 0 }?.plus(1) ?: list.size) }

    private fun insert(l: Layer, at: Int) {
        if (isLive) return
        l.style = this
        list.add(at, l)
    }

    /** An icon for the map's layers; sent to the map now if it's running, else when it starts. */
    fun addImage(id: String, image: ImageBitmap) {
        images[id] = image
        map?.let { m -> runCatching { m.style.images.set(id, ResolvedStyleImage(PreparedImage.fromBitmap(image))) } }
    }

    /** The style as JSON text, for the map to load. */
    fun json(): String {
        val out = LinkedHashMap(top)
        out["sources"] = LinkedHashMap<String, Any?>(baseSources).also { all -> sources.values.forEach { all[it.id] = it.json } }
        out["layers"] = list.map { it.json() }
        sources.values.forEach { (it as? GeoJsonSource)?.pending = false }
        return Json.write(out)
    }

    /** The map has loaded this style: from now on changes go to it too. */
    fun goLive(state: MapState) {
        map = state
        if (images.isNotEmpty()) runCatching {
            state.style.images.setAll(images.mapValues { ResolvedStyleImage(PreparedImage.fromBitmap(it.value)) })
        }
        for (s in sources.values) if (s is GeoJsonSource && s.pending) { if (liveData(s)) s.pending = false }
    }

    fun detach() { map = null }

    internal fun liveData(s: GeoJsonSource): Boolean {
        val m = map ?: return false
        val h = m.style.sources[s.id] as? GeoJsonSourceHandle ?: return false
        val w = h.asMutable ?: return false
        return runCatching { w.setData(GeoJsonData.JsonString(Json.write(s.json["data"]))); true }.getOrDefault(false)
    }

    internal fun liveProperty(l: Layer, p: PropertyValue<*>) {
        val m = map ?: return
        val h = m.style.layers[l.id]?.asMutable ?: return
        val v = element(p.value)
        runCatching { if (p.isLayout) h.setLayoutProperty(p.name, v) else h.setPaintProperty(p.name, v) }
    }

    internal fun liveRoot(l: Layer, name: String, value: Any?) {
        val m = map ?: return
        val h = m.style.layers[l.id]?.asMutable ?: return
        runCatching { h.setRootProperty(name, element(value)) }
    }

    private fun element(v: Any?): JsonElement =
        if (v == null) JsonNull else kotlinx.serialization.json.Json.parseToJsonElement(Json.write(v))
}
