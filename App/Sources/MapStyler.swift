import MapLibre
import UIKit

/**
 * Paints OpenFreeMap Liberty in Vela's colors: the navy "Modern" night palette sampled from
 * the Google app, and Vela's classic day look (white streets, yellow motorways).
 * Layer ids are Liberty's; anything not named here keeps Liberty's own style.
 */
enum MapStyler {
    struct Palette {
        var land, water, park, pitch, landuse: UIColor
        var landuseOpacity: Double
        var building, buildingOutline: UIColor
        var minor, major, motorway, service, path: UIColor
        var casing, motorwayCasing: UIColor
        var rail: UIColor
        var airport, runway: UIColor
        var label: UIColor?, halo: UIColor?
        var roadLabel: UIColor, roadHalo: UIColor
        var border: UIColor
    }

    static let dark = Palette(
        land: UIColor(hex: 0x162640), water: UIColor(hex: 0x000D2A), park: UIColor(hex: 0x0D3847),
        pitch: UIColor(hex: 0x0D4956), landuse: UIColor(hex: 0x2A3546), landuseOpacity: 0.5,
        building: UIColor(hex: 0x1C3B69), buildingOutline: UIColor(hex: 0x2E3D6D),
        minor: UIColor(hex: 0x3D5A77), major: UIColor(hex: 0x476789), motorway: UIColor(hex: 0x476789),
        service: UIColor(hex: 0x2A4056), path: UIColor(hex: 0x2A4056),
        casing: UIColor(hex: 0x07090E), motorwayCasing: UIColor(hex: 0x07090E),
        rail: UIColor(hex: 0x3A4A68),
        airport: UIColor(hex: 0x1C2638), runway: UIColor(hex: 0x2A4056),
        label: UIColor(hex: 0xC3CAD6), halo: UIColor(hex: 0x1A2230),
        roadLabel: UIColor(hex: 0xC3CAD6), roadHalo: UIColor(hex: 0x162640),
        border: UIColor(hex: 0x8C95A3))

    static let light = Palette(
        land: UIColor(hex: 0xF2F1EE), water: UIColor(hex: 0x90DAEE), park: UIColor(hex: 0xCFECCD),
        pitch: UIColor(hex: 0xD3F8E2), landuse: UIColor(hex: 0xF2F1EE), landuseOpacity: 1,
        building: UIColor(hex: 0xDDE1E7), buildingOutline: UIColor(hex: 0xC4C9D1),
        minor: .white, major: .white, motorway: UIColor(hex: 0xF9D27A),
        service: .white, path: UIColor(hex: 0xDCDFE4),
        casing: UIColor(hex: 0xDADDE2), motorwayCasing: UIColor(hex: 0xF0B85A),
        rail: UIColor(hex: 0xC4C9D1),
        airport: UIColor(hex: 0xE6E8EC), runway: UIColor(hex: 0xD0D4DA),
        label: nil, halo: nil,
        roadLabel: UIColor(hex: 0x5F6368), roadHalo: .white,
        border: UIColor(hex: 0x9AA0A6))

    private static func c(_ u: UIColor) -> NSExpression { NSExpression(forConstantValue: u) }

    static func apply(_ style: MLNStyle, dark: Bool) {
        let p = dark ? Self.dark : Self.light
        for layer in style.layers {
            let id = layer.identifier
            // Vela's own layers keep their colors (the transit lines' ids contain "rail").
            if id.hasPrefix("vela") { continue }
            if id == "natural_earth" || id == "road_area_pattern" { layer.isVisible = false; continue }
            if let bg = layer as? MLNBackgroundStyleLayer {
                bg.backgroundColor = c(p.land)
            } else if let f = layer as? MLNFillStyleLayer {
                switch id {
                case "water": f.fillColor = c(p.water)
                case "park":
                    f.fillColor = c(p.park); f.fillOutlineColor = c(p.park); f.fillOpacity = NSExpression(forConstantValue: 1)
                case "landcover_grass", "landcover_wood", "landcover_wetland":
                    f.fillColor = c(p.park); f.fillOutlineColor = c(p.park); f.fillOpacity = NSExpression(forConstantValue: 0.9)
                case "landuse_pitch", "landuse_track": f.fillColor = c(p.pitch)
                case "building":
                    f.fillColor = c(p.building); f.fillOutlineColor = c(p.buildingOutline)
                case "aeroway_fill": f.fillColor = c(p.airport)
                default:
                    if id.hasPrefix("landuse") || id.hasPrefix("landcover") {
                        f.fillColor = c(p.landuse); f.fillOpacity = NSExpression(forConstantValue: p.landuseOpacity)
                    }
                }
            } else if let e = layer as? MLNFillExtrusionStyleLayer, id == "building-3d" {
                e.fillExtrusionColor = c(p.building)
            } else if let l = layer as? MLNLineStyleLayer {
                if id.hasPrefix("waterway") { l.lineColor = c(p.water); continue }
                if id == "park_outline" || id.hasPrefix("landcover") || id.hasPrefix("park") { l.isVisible = false; continue }
                if id.hasPrefix("aeroway") { l.lineColor = c(p.runway); continue }
                if id.hasPrefix("boundary") { l.lineColor = c(p.border); continue }
                if id.contains("rail") { l.lineColor = c(p.rail); continue }
                guard id.hasPrefix("road_") || id.hasPrefix("bridge_") || id.hasPrefix("tunnel_") else { continue }
                if id.contains("casing") {
                    l.lineColor = c(id.contains("motorway") ? p.motorwayCasing : p.casing)
                } else if id.contains("motorway") {
                    l.lineColor = c(p.motorway)
                } else if id.contains("trunk_primary") {
                    l.lineColor = c(p.major)
                } else if id.contains("service_track") {
                    l.lineColor = c(p.service)
                } else if id.contains("path_pedestrian") {
                    l.lineColor = c(p.path)
                } else {
                    l.lineColor = c(p.minor)
                }
            } else if let s = layer as? MLNSymbolStyleLayer {
                if id.hasPrefix("highway-name") {
                    s.textColor = c(p.roadLabel); s.textHaloColor = c(p.roadHalo)
                    s.textHaloWidth = NSExpression(forConstantValue: 1.6)
                } else if let label = p.label, let halo = p.halo, !id.hasPrefix("vela") {
                    s.textColor = c(label); s.textHaloColor = c(halo)
                    s.textHaloWidth = NSExpression(forConstantValue: 1.1)
                }
            }
        }
    }
}
