import Foundation
import os
import UIKit
import VelaCore

/**
 * The free Windy Webcams API key (api.windy.com/keys, "Webcams API", Free plan). Empty means no
 * scenic webcams: Kite then shows only the road cameras, which need no key.
 */
enum WindyKey {
    static let value = "oCX0gC54CF52fMI1VoXtP9BBNxKW94mn"
    static var present: Bool { !value.isEmpty }
}

/**
 * Live camera photos (GitHub issue #15): every UK road camera, listed once a day from OpenHighways
 * (TfL's own list if that doesn't answer) and kept on the phone, plus Windy's scenic webcams around
 * what's on screen. Photos are only fetched when someone opens a camera.
 */
@MainActor
final class LiveCamStore: ObservableObject {
    static let shared = LiveCamStore()
    nonisolated private static let trace = Logger(subsystem: "app.vela.ios", category: "trace")
    nonisolated static let userAgent = "Kite-Maps (https://github.com/matteofx1/kite-maps)"

    nonisolated private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 25
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = ["User-Agent": userAgent]
        return URLSession(configuration: c)
    }()

    /** Every road camera Kite knows of; bumps [version] when it changes. */
    private(set) var road: [LiveCam] = []
    /** Windy's webcams seen so far, by id, and when each was asked (its photo link lasts ~15 minutes). */
    private var windy: [String: (cam: LiveCam, at: Date)] = [:]
    /** The circles already asked Windy about (center, radius in km), and when: a view inside one asks nothing. */
    /** What Windy was asked about: [minReach] > 0 when its answer hit the 50-camera limit, so only views as wide as that count as covered. */
    private var windyAsked: [(center: LatLng, km: Double, at: Date, minReach: Double)] = []
    /** The latest view asked for while another ask was still out: asked as soon as that one ends. */
    private var windyPending: (center: LatLng, spanKm: Double)?
    private var windyFetch: Task<Void, Never>?
    private var windyFailedAt = Date.distantPast
    @Published private(set) var version = 0 { didSet { onChange?() } }
    /** Called when cameras arrive, so the map can draw them. */
    var onChange: (() -> Void)?
    /** How the last list update went, for Settings. */
    @Published private(set) var status: String?
    private var loading: Task<Void, Never>?
    private var listedAt: Date?
    /** After a failed list, not again before this, even with no list at all yet. */
    private var roadRetryAt = Date.distantPast

    private var cacheURL: URL? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("livecams.json")
    }

    private struct Saved: Codable { var at: Date; var cams: [LiveCam] }

    /** Read the saved list, then ask for a new one if it's a day old (or missing). */
    func ensureLoaded() {
        guard loading == nil else { return }
        if road.isEmpty, let url = cacheURL, let data = try? Data(contentsOf: url),
           let saved = try? JSONDecoder().decode(Saved.self, from: data) {
            road = saved.cams
            listedAt = saved.at
            version += 1
        }
        if let listedAt, Date().timeIntervalSince(listedAt) < 86_400, !road.isEmpty { return }
        guard Date() >= roadRetryAt else { return }
        loading = Task { [weak self] in
            let fresh = await Self.fetchRoadList()
            guard let self else { return }
            self.loading = nil
            if let fresh, !fresh.isEmpty {
                self.road = fresh
                self.listedAt = Date()
                self.version += 1
                self.status = "\(fresh.count) road cameras"
                if let url = self.cacheURL, let data = try? JSONEncoder().encode(Saved(at: Date(), cams: fresh)) {
                    try? data.write(to: url, options: .atomic)
                }
            } else {
                self.status = self.road.isEmpty ? "Couldn't load the camera list" : "\(self.road.count) road cameras (saved list)"
                // Try again in ten minutes rather than on every pan.
                self.listedAt = Date().addingTimeInterval(-86_400 + 600)
                self.roadRetryAt = Date().addingTimeInterval(600)
            }
        }
    }

    nonisolated static func fetchRoadList() async -> [LiveCam]? {
        if let data = await get(LiveCams.openHighwaysURL), let cams = LiveCams.parseOpenHighways(data), cams.count > 100 {
            trace.info("livecams openhighways \(cams.count)")
            return cams
        }
        if let data = await get(LiveCams.tflURL), let cams = LiveCams.parseTfL(data), !cams.isEmpty {
            trace.info("livecams tfl \(cams.count)")
            return cams
        }
        return nil
    }

    nonisolated static func get(_ url: URL, headers: [String: String] = [:]) async -> Data? {
        var r = URLRequest(url: url)
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
        guard let (data, resp) = try? await session.data(for: r),
              (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return data
    }

    /**
     * Ask Windy about the webcams around [center] (a view [spanKm] corner to corner), unless an ask in
     * the last 10 minutes already covered all of it. A view asked for while another ask is out is
     * kept and asked next, so a quick pan never leaves the new place empty.
     */
    func loadWindy(center: LatLng, spanKm: Double) {
        guard WindyKey.present else { return }
        // In the minute's pause after a failure: remember the newest view and ask once the pause is over.
        guard Date().timeIntervalSince(windyFailedAt) > 60 else { windyPending = (center, spanKm); retryWindyLater(); return }
        let now = Date()
        windyAsked.removeAll { now.timeIntervalSince($0.at) >= 600 }
        // Windy answers at most 250 km out; a wider view only needs its middle covered.
        let reach = min(spanKm / 2, 250)
        if windyAsked.contains(where: { $0.center.distance(to: center) / 1000 + reach <= $0.km && reach >= $0.minReach }) { return }
        guard windyFetch == nil else { windyPending = (center, spanKm); return }
        // A margin around the view, so small pans ask nothing.
        let radius = min(max(reach * 1.4, 15), 250)
        windyFetch = Task { [weak self] in
            let data = await Self.get(LiveCams.windyNearbyURL(center, radiusKm: Int(radius)), headers: ["x-windy-api-key": WindyKey.value])
            guard let self else { return }
            self.windyFetch = nil
            if let data, let cams = LiveCams.parseWindy(data) {
                let t = Date()
                // A full answer (the 50 limit) may have left cameras out: zooming in asks again.
                self.windyAsked.append((center, Double(Int(radius)), t, LiveCams.windyListed(data) >= 50 ? reach * 0.8 : 0))
                for c in cams { self.windy[c.id] = (c, t) }
                self.version += 1
            } else {
                self.windyFailedAt = Date()
                // The view asked for meanwhile is tried again after the minute's pause, not dropped.
                if self.windyPending != nil { self.retryWindyLater() }
                return
            }
            if let p = self.windyPending {
                self.windyPending = nil
                self.loadWindy(center: p.center, spanKm: p.spanKm)
            }
        }
    }

    private var windyRetry: Task<Void, Never>?

    /** One retry at the end of the pause, with the newest view asked for in it. */
    private func retryWindyLater() {
        guard windyRetry == nil else { return }
        let wait = max(1, 61 - Date().timeIntervalSince(windyFailedAt))
        windyRetry = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(wait * 1e9))
            guard let self else { return }
            self.windyRetry = nil
            if let p = self.windyPending {
                self.windyPending = nil
                self.loadWindy(center: p.center, spanKm: p.spanKm)
            }
        }
    }

    /** The route last asked about, so reopening the same route asks Windy nothing new. */
    private var windyRouteKey: String?
    private var windyRouteFetch: Task<Void, Never>?

    /** Every Windy webcam seen in the last while (their photo links are renewed when opened). */
    var webcams: [LiveCam] { windy.values.map(\.cam) }

    /**
     * Before you go: ask Windy about the webcams along a route, a few circles spread along it,
     * once per route. Stretches already asked about in the last 10 minutes are skipped.
     */
    func loadWindyRoute(_ key: String, line: [LatLng]) {
        guard WindyKey.present, line.count >= 2, key != windyRouteKey,
              Date().timeIntervalSince(windyFailedAt) > 60 else { return }
        windyRouteKey = key
        windyRouteFetch?.cancel()
        windyRouteFetch = Task { [weak self] in
            let cum = Geo.cumulative(line)
            var queue = LiveCams.windyRouteAsks(line)
            // A full answer (Windy's 50 limit) is split into halves and asked again, up to this many asks in all.
            var budget = 16
            while !queue.isEmpty, budget > 0 {
                let ask = queue.removeFirst()
                guard let self, !Task.isCancelled else { return }
                let radius = Double(ask.radiusKm)
                let now = Date()
                self.windyAsked.removeAll { now.timeIntervalSince($0.at) >= 600 }
                switch LiveCams.windyPlan(ask, asked: self.windyAsked.map { ($0.center, $0.km, $0.minReach) }, line: line, cumulative: cum) {
                case .skip: continue
                case .closer(let halves): queue += halves; continue
                case .fetch: break
                }
                budget -= 1
                let data = await Self.get(LiveCams.windyNearbyURL(ask.center, radiusKm: ask.radiusKm), headers: ["x-windy-api-key": WindyKey.value])
                guard !Task.isCancelled else { return }
                guard let data, let cams = LiveCams.parseWindy(data) else {
                    // Asked again next time the route is shown, after the minute's pause.
                    self.windyFailedAt = Date()
                    self.windyRouteKey = nil
                    return
                }
                let t = Date()
                let full = LiveCams.windyListed(data) >= 50
                self.windyAsked.append((ask.center, radius, t, full ? radius * 0.8 : 0))
                for c in cams { self.windy[c.id] = (c, t) }
                if full, let halves = LiveCams.windySplit(ask, line: line, cumulative: cum) { queue += halves }
                // Shown as each stretch arrives.
                self.version += 1
            }
            // Out of asks with stretches still to look at: carry on the next time this route is shown.
            if !queue.isEmpty, let self, !Task.isCancelled { self.windyRouteKey = nil }
        }
    }

    /** The cameras to draw in a box, as the settings want them. */
    func cams(south: Double, west: Double, north: Double, east: Double, road showRoad: Bool, webcams: Bool) -> [LiveCam] {
        var out: [LiveCam] = []
        func inside(_ p: LatLng) -> Bool { p.lat >= south && p.lat <= north && p.lng >= west && p.lng <= east }
        if showRoad { out += road.filter { inside($0.location) } }
        if webcams { out += windy.values.map(\.cam).filter { inside($0.location) } }
        return out
    }

    func cam(_ id: String) -> LiveCam? { road.first { $0.id == id } ?? windy[id]?.cam }

    /** A Windy webcam with a photo link that still works (they last about 15 minutes); road cameras as they are. */
    func current(_ cam: LiveCam) async -> LiveCam {
        guard cam.source == .windy else { return cam }
        if let w = windy[cam.id], Date().timeIntervalSince(w.at) < 600 { return w.cam }
        guard let url = LiveCams.windyOneURL(cam.id),
              let data = await Self.get(url, headers: ["x-windy-api-key": WindyKey.value]),
              let fresh = LiveCams.parseWindy(data)?.first else { return cam }
        windy[cam.id] = (fresh, Date())
        return fresh
    }

    /** The photo, and when the camera took it (from the server's Last-Modified, when it says). */
    nonisolated static func photo(_ cam: LiveCam) async -> (UIImage, Date?)? {
        var r = URLRequest(url: cam.imageURL)
        r.cachePolicy = .reloadIgnoringLocalCacheData
        guard let (data, resp) = try? await session.data(for: r),
              let http = resp as? HTTPURLResponse, http.statusCode == 200,
              let img = UIImage(data: data), img.size.width > 8, !isPlaceholder(img) else { return nil }
        var taken = cam.updatedAt
        if let lm = http.value(forHTTPHeaderField: "Last-Modified") {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.timeZone = TimeZone(identifier: "GMT")
            f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
            if let d = f.date(from: lm) { taken = d }
        }
        return (img, taken)
    }

    /**
     * National Highways sends a "CAMERA UNAVAILABLE" card (white, with a red cross) instead of a
     * photo when a camera is off. That card is mostly pale with a red cross over a black camera; treat it as no photo.
     */
    nonisolated static func isPlaceholder(_ img: UIImage) -> Bool {
        guard let cg = img.cgImage else { return false }
        let w = 32, h = 18
        var px = [UInt8](repeating: 0, count: w * h * 4)
        let drawn = px.withUnsafeMutableBytes { buf -> Bool in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return false }
        var light = 0, red = 0, black = 0
        for i in stride(from: 0, to: px.count, by: 4) {
            let r = Int(px[i]), g = Int(px[i + 1]), b = Int(px[i + 2])
            let lo = min(r, g, b), hi = max(r, g, b)
            if lo > 195 && hi - lo < 45 { light += 1 }
            if r > 140 && g < 70 && b < 100 { red += 1 }
            if hi < 45 { black += 1 }
        }
        // A pale card, a red cross and a black camera all at once: not a real road photo.
        let n = w * h
        return light * 10 > n * 3 && red * 40 > n && black * 12 > n
    }
}
