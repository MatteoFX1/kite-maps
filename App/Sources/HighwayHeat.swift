import CoreGraphics
import Foundation
import MapLibre
import os
import UIKit

/**
 * The police heat map from Highway Radar's own tile server: where police get reported, built from
 * its users' reports over time, for the whole UK at once (no week of driving first). Found by
 * Ausbloke's fork of Kite (github.com/NotDiscordOfficial/kite-maps). It's a private server with no
 * public API, so it may stop answering one day; then the heat map is simply empty.
 *
 * Each tile is a 256 px greyscale WebP: brighter is more reports. Kite colours it itself, clear
 * where there's nothing, then glowing blue through violet ("blue lights").
 */
@MainActor
final class HighwayHeat {
    private static let trace = Logger(subsystem: "app.vela.ios", category: "trace")
    nonisolated private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        c.requestCachePolicy = .returnCacheDataElseLoad
        c.urlCache = URLCache(memoryCapacity: 8 << 20, diskCapacity: 40 << 20)
        return URLSession(configuration: c)
    }()

    struct Tile: Hashable, Sendable {
        var z: Int, x: Int, y: Int
        var key: String { "\(z)/\(x)/\(y)" }
    }

    /** Coloured tiles already made, by key, so panning back is instant. */
    private var images: [String: UIImage] = [:]
    private var order: [String] = []
    /** Tiles on the map now, by key. */
    private var shown: Set<String> = []
    private var loading: Task<Void, Never>?
    private var wanted: Set<String> = []
    /** How the last ask went, for Settings: true when tiles came back. */
    private(set) var lastOK: Bool?

    // The server goes to zoom 14. Up to 13 its tiles follow the roads cleanly (checked in London and
    // Southampton); its few blocky patches are faint, so the "quiet" cut-off in blueLights hides
    // them. Closer in, Kite stretches the zoom 13 tiles.
    static let minZoom = 5, maxZoom = 13

    nonisolated static func url(_ t: Tile) -> URL {
        URL(string: "https://tiles.cmbe.cc/heat_map/get/\(t.z)/\(t.x)/\(t.y).webp")!
    }

    /** The tiles covering [b], at the deepest zoom up to the map's own that needs at most 16. */
    nonisolated static func tiles(for b: MLNCoordinateBounds, mapZoom: Double) -> [Tile] {
        var z = min(max(Int(mapZoom.rounded(.down)), minZoom), maxZoom)
        while z >= minZoom {
            let (x0, y0) = index(lat: b.ne.latitude, lng: b.sw.longitude, z: z)
            let (x1, y1) = index(lat: b.sw.latitude, lng: b.ne.longitude, z: z)
            let n = 1 << z
            if x1 >= x0, y1 >= y0, (x1 - x0 + 1) * (y1 - y0 + 1) <= 16 {
                var out: [Tile] = []
                for y in max(y0, 0)...min(y1, n - 1) {
                    for x in x0...x1 { out.append(Tile(z: z, x: ((x % n) + n) % n, y: y)) }
                }
                return out
            }
            z -= 1
        }
        return []
    }

    nonisolated static func index(lat: Double, lng: Double, z: Int) -> (Int, Int) {
        let n = Double(1 << z)
        let la = min(max(lat, -85.05), 85.05) * .pi / 180
        let x = Int(floor((lng + 180) / 360 * n))
        let y = Int(floor((1 - log(tan(la) + 1 / cos(la)) / .pi) / 2 * n))
        return (x, y)
    }

    nonisolated static func corner(x: Int, y: Int, z: Int) -> CLLocationCoordinate2D {
        let n = Double(1 << z)
        let lng = Double(x) / n * 360 - 180
        let lat = atan(sinh(.pi * (1 - 2 * Double(y) / n))) * 180 / .pi
        return CLLocationCoordinate2D(latitude: lat, longitude: lng)
    }

    nonisolated static func quad(_ t: Tile) -> MLNCoordinateQuad {
        MLNCoordinateQuad(topLeft: corner(x: t.x, y: t.y, z: t.z),
                          bottomLeft: corner(x: t.x, y: t.y + 1, z: t.z),
                          bottomRight: corner(x: t.x + 1, y: t.y + 1, z: t.z),
                          topRight: corner(x: t.x + 1, y: t.y, z: t.z))
    }

    /**
     * The grey tile, coloured "blue lights" style: roads where police get reported glow blue,
     * turning violet and then bright pink where reports are densest, with a soft halo so the
     * roads read at a glance. Blue so it's never mistaken for traffic (amber and red). Quiet
     * roads (faint grey) stay clear. The same maths as the Android app.
     */
    nonisolated static func colour(_ data: Data) -> UIImage? {
        guard let src = UIImage(data: data)?.cgImage else { return nil }
        let w = src.width, h = src.height
        guard w > 0, h > 0 else { return nil }
        var grey = [UInt8](repeating: 0, count: w * h)
        let drawn: Bool = grey.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
            ctx.draw(src, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return nil }
        var rgba = blueLights(grey, w: w, h: h)
        let image: CGImage? = rgba.withUnsafeMutableBytes { buf in
            CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)?.makeImage()
        }
        return image.map { UIImage(cgImage: $0) }
    }

    /** Below this grey level a road has too few reports to show. */
    nonisolated static let quiet = 40.0
    /** The colour ramp: blue for some reports, violet for many, bright pink for the most. */
    nonisolated static let ramp: [(Double, Double, Double, Double)] = [(0, 59, 130, 246), (0.55, 139, 92, 246), (1, 232, 121, 249)]

    /** Greyscale (0 to 255, w×h) to premultiplied RGBA. Pure, so it's easy to test. */
    nonisolated static func blueLights(_ grey: [UInt8], w: Int, h: Int) -> [UInt8] {
        let n = w * h
        var t = [Double](repeating: 0, count: n)
        for i in 0..<n { t[i] = min(max((Double(grey[i]) - quiet) / (200 - quiet), 0), 1) }
        // The halo: the strength blurred a few pixels out (two box blurs come close to a soft glow).
        var halo = t
        for _ in 0..<2 { halo = boxBlur(halo, w: w, h: h, r: 3) }
        var rgba = [UInt8](repeating: 0, count: n * 4)
        for i in 0..<n {
            let core = t[i] > 0 ? 0.45 + 0.5 * t[i] : 0
            let a = max(core, min(halo[i] * 1.4, 0.5))
            guard a > 0.01 else { continue }
            let (r, g, b) = rampColour(max(t[i], halo[i] * 0.7))
            rgba[i * 4] = UInt8(r * a); rgba[i * 4 + 1] = UInt8(g * a); rgba[i * 4 + 2] = UInt8(b * a)
            rgba[i * 4 + 3] = UInt8(a * 255)
        }
        return rgba
    }

    nonisolated static func rampColour(_ x: Double) -> (Double, Double, Double) {
        let x = min(max(x, 0), 1)
        for k in 1..<ramp.count where x <= ramp[k].0 {
            let (p0, r0, g0, b0) = ramp[k - 1], (p1, r1, g1, b1) = ramp[k]
            let f = (x - p0) / (p1 - p0)
            return (r0 + (r1 - r0) * f, g0 + (g1 - g0) * f, b0 + (b1 - b0) * f)
        }
        let last = ramp[ramp.count - 1]
        return (last.1, last.2, last.3)
    }

    /** A box blur of radius [r] across then down, with the edges held. */
    nonisolated static func boxBlur(_ src: [Double], w: Int, h: Int, r: Int) -> [Double] {
        var mid = [Double](repeating: 0, count: w * h)
        var out = [Double](repeating: 0, count: w * h)
        let span = Double(2 * r + 1)
        for y in 0..<h {
            for x in 0..<w {
                var sum = 0.0
                for d in -r...r { sum += src[y * w + min(max(x + d, 0), w - 1)] }
                mid[y * w + x] = sum / span
            }
        }
        for y in 0..<h {
            for x in 0..<w {
                var sum = 0.0
                for d in -r...r { sum += mid[min(max(y + d, 0), h - 1) * w + x] }
                out[y * w + x] = sum / span
            }
        }
        return out
    }

    /** Shows the heat map for what's on screen ([on] false clears it). Below [below], so it never hides the route. */
    func update(_ map: MLNMapView, on: Bool, below: String) {
        guard let style = map.style else { return }
        guard on else { clear(style); return }
        let want = Self.tiles(for: map.visibleCoordinateBounds, mapZoom: map.zoomLevel)
        let keys = Set(want.map(\.key))
        // Already showing these, or already fetching them.
        if keys == wanted && (keys.isSubset(of: shown) || loading != nil) { return }
        wanted = keys
        loading?.cancel()
        let cached = want.compactMap { t in images[t.key].map { (t, $0) } }
        let missing = want.filter { images[$0.key] == nil }
        loading = Task { [weak self, weak map] in
            var got: [(Tile, UIImage)] = []
            await withTaskGroup(of: (Tile, UIImage?).self) { group in
                for t in missing {
                    group.addTask {
                        guard let (data, resp) = try? await Self.session.data(from: Self.url(t)),
                              (resp as? HTTPURLResponse)?.statusCode == 200 else { return (t, nil) }
                        // Coloured here, off the main thread: the glow takes a few milliseconds a tile.
                        return (t, Self.colour(data))
                    }
                }
                for await (t, img) in group { if let img { got.append((t, img)) } }
            }
            guard let self, !Task.isCancelled else { return }
            self.loading = nil
            var fresh: [Tile: UIImage] = Dictionary(cached, uniquingKeysWith: { a, _ in a })
            for (t, img) in got { fresh[t] = img; self.remember(t.key, img) }
            if !want.isEmpty { self.lastOK = !fresh.isEmpty }
            if !want.isEmpty { Self.trace.info("heat map: \(fresh.count) of \(want.count) tiles at zoom \(want.first?.z ?? 0)") }
            guard let map, let style = map.style else { return }
            self.show(fresh, in: style, below: below)
        }
    }

    private func remember(_ key: String, _ img: UIImage) {
        if images[key] == nil { order.append(key) }
        images[key] = img
        while order.count > 80 { images[order.removeFirst()] = nil }
    }

    private func show(_ tiles: [Tile: UIImage], in style: MLNStyle, below: String) {
        let keep = Set(tiles.keys.map(\.key))
        for key in shown.subtracting(keep) { remove(key, from: style) }
        for (t, img) in tiles where !shown.contains(t.key) {
            let id = "hr-heat-\(t.z)-\(t.x)-\(t.y)"
            let src = MLNImageSource(identifier: id, coordinateQuad: Self.quad(t), image: img)
            style.addSource(src)
            let layer = MLNRasterStyleLayer(identifier: id, source: src)
            // Close in, a hotspot city centre would otherwise be one bright sheet over the streets.
            layer.rasterOpacity = NSExpression(mglJSONObject: ["interpolate", ["linear"], ["zoom"], 10, 0.9, 13, 0.8, 16, 0.6])
            layer.rasterFadeDuration = NSExpression(forConstantValue: 0)
            if let under = style.layer(withIdentifier: below) { style.insertLayer(layer, below: under) } else { style.addLayer(layer) }
        }
        shown = keep
    }

    private func remove(_ key: String, from style: MLNStyle) {
        let id = "hr-heat-" + key.replacingOccurrences(of: "/", with: "-")
        if let l = style.layer(withIdentifier: id) { style.removeLayer(l) }
        if let s = style.source(withIdentifier: id) { style.removeSource(s) }
    }

    func clear(_ style: MLNStyle) {
        loading?.cancel()
        loading = nil
        wanted = []
        for key in shown { remove(key, from: style) }
        shown = []
    }

    /** After the style is reloaded (light/dark switch) its layers are gone: start again. */
    func styleReloaded() {
        shown = []
        wanted = []
    }
}
