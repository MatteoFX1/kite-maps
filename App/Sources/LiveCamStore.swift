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
    /** 0.5° cells already asked Windy about, and when. */
    private var windyCells: [String: Date] = [:]
    private var windyFetch: Task<Void, Never>?
    private var windyFailedAt = Date.distantPast
    @Published private(set) var version = 0 { didSet { onChange?() } }
    /** Called when cameras arrive, so the map can draw them. */
    var onChange: (() -> Void)?
    /** How the last list update went, for Settings. */
    @Published private(set) var status: String?
    private var loading: Task<Void, Never>?
    private var listedAt: Date?

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

    /** Ask Windy about the webcams around [center], each 0.5° cell at most once every 10 minutes. */
    func loadWindy(center: LatLng, spanKm: Double) {
        guard WindyKey.present, windyFetch == nil, Date().timeIntervalSince(windyFailedAt) > 60 else { return }
        let cell = "\(Int(floor(center.lat * 2))),\(Int(floor(center.lng * 2)))"
        if let at = windyCells[cell], Date().timeIntervalSince(at) < 600 { return }
        let radius = Int(min(max(spanKm / 2, 15), 120))
        windyFetch = Task { [weak self] in
            let data = await Self.get(LiveCams.windyNearbyURL(center, radiusKm: radius), headers: ["x-windy-api-key": WindyKey.value])
            guard let self else { return }
            self.windyFetch = nil
            guard let data, let cams = LiveCams.parseWindy(data) else { self.windyFailedAt = Date(); return }
            self.windyCells[cell] = Date()
            let now = Date()
            for c in cams { self.windy[c.id] = (c, now) }
            self.version += 1
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
              let img = UIImage(data: data), img.size.width > 8 else { return nil }
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
}
