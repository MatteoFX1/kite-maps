package app.kite

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*

/**
 * Paints OpenFreeMap Liberty in Kite's colors: a calm Waze-like night palette and the classic day
 * look. While driving, [drive] strips the map down to roads and names. The same as the iPhone app.
 */
object MapStyler {
    class Palette(
        val land: Int, val water: Int, val park: Int, val pitch: Int, val landuse: Int, val landuseOpacity: Float,
        val building: Int, val buildingOutline: Int,
        val minor: Int, val major: Int, val motorway: Int, val service: Int, val path: Int,
        val casing: Int, val motorwayCasing: Int, val rail: Int, val airport: Int, val runway: Int,
        val label: Int?, val halo: Int?, val roadLabel: Int, val roadHalo: Int, val border: Int,
    )

    private fun c(hex: Long) = (0xFF000000 or hex).toInt()

    // Night: near-black land with quiet grey roads, so the teal route and the warnings are what you see.
    val dark = Palette(
        land = c(0x12161D), water = c(0x15273B), park = c(0x15291F), pitch = c(0x15291F), landuse = c(0x161B23), landuseOpacity = 0.5f,
        building = c(0x1A2029), buildingOutline = c(0x202733),
        minor = c(0x29313D), major = c(0x353F4E), motorway = c(0x465264), service = c(0x1F2630), path = c(0x1C222B),
        casing = c(0x12161D), motorwayCasing = c(0x12161D), rail = c(0x252C37), airport = c(0x161B23), runway = c(0x29313D),
        label = c(0xAEB7C4), halo = c(0x12161D), roadLabel = c(0xD5DBE5), roadHalo = c(0x12161D), border = c(0x5F6977),
    )

    val light = Palette(
        land = c(0xF2F1EE), water = c(0x90DAEE), park = c(0xCFECCD), pitch = c(0xD3F8E2), landuse = c(0xF2F1EE), landuseOpacity = 1f,
        building = c(0xDDE1E7), buildingOutline = c(0xC4C9D1),
        minor = c(0xFFFFFF), major = c(0xFFFFFF), motorway = c(0xF9D27A), service = c(0xFFFFFF), path = c(0xDCDFE4),
        casing = c(0xDADDE2), motorwayCasing = c(0xF0B85A), rail = c(0xC4C9D1), airport = c(0xE6E8EC), runway = c(0xD0D4DA),
        label = null, halo = null, roadLabel = c(0x5F6368), roadHalo = c(0xFFFFFF), border = c(0x9AA0A6),
    )

    fun apply(style: Style, isDark: Boolean) {
        val p = if (isDark) dark else light
        for (layer in style.layers) {
            val id = layer.id
            if (id.startsWith("vela") || id.startsWith("hr-heat")) continue
            if (id == "natural_earth" || id == "road_area_pattern") { layer.setProperties(visibility(Property.NONE)); continue }
            when (layer) {
                is BackgroundLayer -> layer.setProperties(backgroundColor(p.land))
                is FillLayer -> when (id) {
                    "water" -> layer.setProperties(fillColor(p.water))
                    "park" -> layer.setProperties(fillColor(p.park), fillOutlineColor(p.park), fillOpacity(1f))
                    "landcover_grass", "landcover_wood", "landcover_wetland" -> {
                        runCatching { layer.setProperties(PropertyValue<String?>("fill-pattern", null)) }
                        layer.setProperties(fillColor(p.park), fillOutlineColor(p.park), fillOpacity(0.9f))
                    }
                    "landuse_pitch", "landuse_track" -> layer.setProperties(fillColor(p.pitch))
                    "building" -> layer.setProperties(fillColor(p.building), fillOutlineColor(p.buildingOutline))
                    "aeroway_fill" -> layer.setProperties(fillColor(p.airport))
                    else -> if (id.startsWith("landuse") || id.startsWith("landcover")) layer.setProperties(fillColor(p.landuse), fillOpacity(p.landuseOpacity))
                }
                is FillExtrusionLayer -> if (id == "building-3d") layer.setProperties(fillExtrusionColor(p.building))
                is LineLayer -> {
                    when {
                        id.startsWith("waterway") -> layer.setProperties(lineColor(p.water))
                        id == "park_outline" || id.startsWith("landcover") || id.startsWith("park") -> layer.setProperties(visibility(Property.NONE))
                        id.startsWith("aeroway") -> layer.setProperties(lineColor(p.runway))
                        id.startsWith("boundary") -> layer.setProperties(lineColor(p.border))
                        id.contains("rail") -> layer.setProperties(lineColor(p.rail))
                        !(id.startsWith("road_") || id.startsWith("bridge_") || id.startsWith("tunnel_")) -> {}
                        id.contains("casing") -> layer.setProperties(lineColor(if (id.contains("motorway")) p.motorwayCasing else p.casing))
                        id.contains("motorway") -> layer.setProperties(lineColor(p.motorway))
                        id.contains("trunk_primary") -> layer.setProperties(lineColor(p.major))
                        id.contains("service_track") -> layer.setProperties(lineColor(p.service))
                        id.contains("path_pedestrian") -> layer.setProperties(lineColor(p.path))
                        else -> layer.setProperties(lineColor(p.minor))
                    }
                }
                is SymbolLayer -> {
                    if (id.startsWith("highway-name")) {
                        layer.setProperties(textColor(p.roadLabel), textHaloColor(p.roadHalo), textHaloWidth(1.6f))
                    } else if (p.label != null && p.halo != null) {
                        layer.setProperties(textColor(p.label), textHaloColor(p.halo), textHaloWidth(1.1f))
                    }
                }
            }
        }
    }

    private fun clutter(id: String) =
        id.startsWith("poi_") || id == "building" || id == "building-3d" || id.contains("path_pedestrian") ||
            id == "highway-name-path" || id.startsWith("road_one_way_arrow") || id.endsWith("_hatching") ||
            id in setOf("landuse_pitch", "landuse_track", "landuse_cemetery", "landuse_hospital", "landuse_school",
                "aeroway_fill", "aeroway_runway", "aeroway_taxiway", "airport")

    private val savedLabelSizes = mutableMapOf<String, PropertyValue<*>>()
    private val savedShieldSpacing = mutableMapOf<String, PropertyValue<*>>()
    private val savedShieldPadding = mutableMapOf<String, PropertyValue<*>>()

    /** Forget saved values after a new style loads. */
    fun reset() { savedLabelSizes.clear(); savedShieldSpacing.clear(); savedShieldPadding.clear() }

    /** Strips the map to roads, water, parks and big road names while navigating, and puts it back after. */
    fun drive(style: Style, on: Boolean) {
        for (layer in style.layers) {
            val id = layer.id
            if (id.startsWith("vela") || id.startsWith("hr-heat")) continue
            if (clutter(id)) { layer.setProperties(visibility(if (on) Property.NONE else Property.VISIBLE)); continue }
            if (layer is SymbolLayer && id.contains("shield")) {
                if (savedShieldSpacing[id] == null) {
                    savedShieldSpacing[id] = layer.symbolSpacing
                    savedShieldPadding[id] = layer.iconPadding
                }
                if (on) layer.setProperties(symbolSpacing(900f), iconPadding(30f))
                else { restore(layer, savedShieldSpacing[id]!!); restore(layer, savedShieldPadding[id]!!) }
                continue
            }
            if (layer is SymbolLayer && (id == "highway-name-major" || id == "highway-name-minor")) {
                if (savedLabelSizes[id] == null) savedLabelSizes[id] = layer.textSize
                if (on) layer.setProperties(textSize(if (id == "highway-name-major") 17f else 15f))
                else restore(layer, savedLabelSizes[id]!!)
            }
        }
    }

    /**
     * Puts back a value read from the style. One the style gave as an expression (a size that grows
     * with zoom, say) comes back as JSON, which the map only takes once turned into an expression again.
     */
    private fun restore(layer: Layer, v: PropertyValue<*>) {
        val value = v.value
        runCatching {
            when (value) {
                null -> layer.setProperties(PropertyValue<Any?>(v.name, null))
                is Number, is String, is Boolean, is Array<*> -> layer.setProperties(v)
                else -> {
                    val json = value.toString()
                    if (json.startsWith("[")) layer.setProperties(PropertyValue(v.name, org.maplibre.android.style.expressions.Expression.raw(json)))
                    else json.toFloatOrNull()?.let { layer.setProperties(PropertyValue(v.name, it)) }
                }
            }
        }
    }
}
