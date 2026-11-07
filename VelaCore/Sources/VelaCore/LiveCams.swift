import Foundation

/**
 * A camera that shows a live photo: a road traffic camera (UK) or a scenic webcam (Windy).
 * Asked for GitHub issue #15, "webcams nearby like Windy".
 */
public struct LiveCam: Hashable, Codable, Sendable, Identifiable {
    public enum Source: String, Codable, Sendable, CaseIterable {
        case nationalHighways = "national_highways", tfl, trafficScotland = "traffic_scotland"
        case trafficWales = "traffic_wales", northernIreland = "northern_ireland", essex, windy

        /** Who the photo comes from, for the credit under it. */
        public var credit: String {
            switch self {
            case .nationalHighways: return "National Highways"
            case .tfl: return "Transport for London"
            case .trafficScotland: return "Traffic Scotland"
            case .trafficWales: return "Traffic Wales"
            case .northernIreland: return "TrafficWatch NI"
            case .essex: return "Essex Highways"
            case .windy: return "Windy.com"
            }
        }

        public var isRoad: Bool { self != .windy }
    }

    /** "oh:207", "tfl:JamCams_00001.02151" or "windy:1234": unique across sources. */
    public var id: String
    public var source: Source
    public var name: String
    /** "M25", for a road camera, when known. */
    public var road: String?
    /** Which way it faces or which carriageway, as the source words it. */
    public var direction: String?
    public var location: LatLng
    /** The latest photo. Windy's links stop working after about 15 minutes. */
    public var imageURL: URL
    /** A page with more: Windy's timelapse player, or TfL's short video clip. */
    public var moreURL: URL?
    /** When the list said the photo was last updated (Windy only). */
    public var updatedAt: Date?

    public init(id: String, source: Source, name: String, road: String? = nil, direction: String? = nil,
                location: LatLng, imageURL: URL, moreURL: URL? = nil, updatedAt: Date? = nil) {
        self.id = id; self.source = source; self.name = name; self.road = road; self.direction = direction
        self.location = location; self.imageURL = imageURL; self.moreURL = moreURL; self.updatedAt = updatedAt
    }

    /** A second line under the name: "M25 · Clockwise", "Scenic webcam · Keswick". */
    public var detail: String {
        var parts: [String] = []
        if source == .windy {
            parts.append("Webcam")
        } else if let road, !road.isEmpty, !name.localizedCaseInsensitiveContains(road) {
            parts.append(road)
        }
        if let direction, !direction.isEmpty { parts.append(direction) }
        return parts.joined(separator: " · ")
    }
}

/** Reading the camera lists, and which cameras belong on a route. */
public enum LiveCams {
    public static let openHighwaysURL = URL(string: "https://openhighways.uk/api/cameras")!
    /** Map zoom from which road cameras show (a city: there are thousands) and webcams (a region). */
    public static let roadMinZoom = 9.0
    public static let webcamMinZoom = 6.0
    /** How far off the route a webcam can be and still count as on it: one in a town you drive through. */
    public static let webcamRouteOffset = 2500.0
    public static let tflURL = URL(string: "https://api.tfl.gov.uk/Place/Type/JamCam")!

    /**
     * OpenHighways (openhighways.uk, MIT): every public UK road camera with a place on the map,
     * from National Highways, TfL, Traffic Scotland, Traffic Wales, TrafficWatch NI and Essex.
     * Its photo links go straight to each source, except for the few it has to fetch itself.
     */
    public static func parseOpenHighways(_ data: Data) -> [LiveCam]? {
        guard let list = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return nil }
        var seen = Set<String>()
        return list.compactMap { o in
            guard (o["active"] as? Bool) ?? true,
                  let n = o["id"] as? NSNumber,
                  let lat = (o["latitude"] as? NSNumber)?.doubleValue, let lng = (o["longitude"] as? NSNumber)?.doubleValue,
                  valid(lat, lng),
                  let raw = o["image_url"] as? String, !raw.isEmpty,
                  let url = absolute(raw, base: "https://openhighways.uk"),
                  seen.insert("\(n)").inserted else { return nil }
            let source = LiveCam.Source(rawValue: o["source"] as? String ?? "") ?? .nationalHighways
            guard source != .windy else { return nil }
            let road = (o["road"] as? String).flatMap(clean)
            let name = (o["name"] as? String).flatMap(clean) ?? road ?? "Road camera"
            return LiveCam(id: "oh:\(n)", source: source, name: tidy(name), road: road,
                           direction: (o["direction"] as? String).flatMap(clean),
                           location: LatLng(lat, lng), imageURL: url)
        }
    }

    /** TfL's JamCams, straight from TfL: London only, used when OpenHighways doesn't answer. */
    public static func parseTfL(_ data: Data) -> [LiveCam]? {
        guard let list = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return nil }
        return list.compactMap { o in
            guard let id = o["id"] as? String,
                  let lat = (o["lat"] as? NSNumber)?.doubleValue, let lng = (o["lon"] as? NSNumber)?.doubleValue,
                  valid(lat, lng) else { return nil }
            var props: [String: String] = [:]
            for p in o["additionalProperties"] as? [[String: Any]] ?? [] {
                if let k = p["key"] as? String, let v = p["value"] as? String { props[k] = v }
            }
            guard props["available"]?.lowercased() != "false",
                  let image = props["imageUrl"].flatMap(URL.init(string:)) else { return nil }
            let name = (o["commonName"] as? String).flatMap(clean) ?? "London camera"
            return LiveCam(id: "tfl:\(id)", source: .tfl, name: tidy(name), direction: props["view"].flatMap(clean),
                           location: LatLng(lat, lng), imageURL: image, moreURL: props["videoUrl"].flatMap(URL.init(string:)))
        }
    }

    /**
     * Windy's webcams (api.windy.com, Webcams API v3), from a "nearby" or single-webcam answer.
     * The free plan's photo links stop working after about 15 minutes, so they're asked again then.
     */
    /** How many webcams Windy's answer listed before any were left out (inactive, no photo): 50 means the limit was hit. */
    public static func windyListed(_ data: Data) -> Int {
        ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["webcams"].flatMap { $0 as? [Any] }?.count ?? 0
    }

    public static func parseWindy(_ data: Data) -> [LiveCam]? {
        guard let root = try? JSONSerialization.jsonObject(with: data) else { return nil }
        let list: [[String: Any]]
        if let o = root as? [String: Any], let w = o["webcams"] as? [[String: Any]] { list = w }
        else if let o = root as? [String: Any], o["webcamId"] != nil { list = [o] }
        else { return nil }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let isoPlain = ISO8601DateFormatter()
        return list.compactMap { o in
            guard let n = o["webcamId"] as? NSNumber ?? (o["id"] as? NSNumber),
                  (o["status"] as? String ?? "active") == "active",
                  let loc = o["location"] as? [String: Any],
                  let lat = (loc["latitude"] as? NSNumber)?.doubleValue, let lng = (loc["longitude"] as? NSNumber)?.doubleValue,
                  valid(lat, lng),
                  let images = o["images"] as? [String: Any],
                  let current = images["current"] as? [String: Any],
                  let raw = (current["preview"] as? String) ?? (current["thumbnail"] as? String),
                  let image = URL(string: raw) else { return nil }
            let title = (o["title"] as? String).flatMap(clean) ?? "Webcam"
            let place = [loc["city"] as? String, loc["region"] as? String].compactMap { $0.flatMap(clean) }.first
            let urls = o["urls"] as? [String: Any]
            let more = (urls?["detail"] as? String).flatMap(URL.init(string:))
                ?? URL(string: "https://www.windy.com/webcams/\(n)")
            let updated = (o["lastUpdatedOn"] as? String).flatMap { iso.date(from: $0) ?? isoPlain.date(from: $0) }
            return LiveCam(id: "windy:\(n)", source: .windy, name: title, direction: place,
                           location: LatLng(lat, lng), imageURL: image, moreURL: more, updatedAt: updated)
        }
    }

    /** The Windy "nearby" ask: up to 50 webcams within [radiusKm] (Windy allows up to 250). */
    public static func windyNearbyURL(_ center: LatLng, radiusKm: Int) -> URL {
        let r = min(max(radiusKm, 1), 250)
        return URL(string: String(format: "https://api.windy.com/webcams/api/v3/webcams?nearby=%.4f,%.4f,%d&limit=50&include=images,location,urls",
                                  center.lat, center.lng, r))!
    }

    public static func windyOneURL(_ id: String) -> URL? {
        guard id.hasPrefix("windy:") else { return nil }
        return URL(string: "https://api.windy.com/webcams/api/v3/webcams/\(id.dropFirst(6))?include=images,location,urls")
    }

    /**
     * The cameras along [line], in the order you'd pass them, at most [limit]: road cameras within
     * [maxOffset] meters of the route, webcams within [webcamOffset] (0 leaves them out: they look
     * at a town or a view, not at the road), and not two within [spacing] meters of each other
     * along it (a junction often has four cameras looking at the same traffic).
     */
    public static func along(_ cams: [LiveCam], line: [LatLng], maxOffset: Double = 120, webcamOffset: Double = 0,
                             spacing: Double = 1500, limit: Int = 12) -> [(cam: LiveCam, along: Double)] {
        guard line.count >= 2 else { return [] }
        // A cheap box test first: a whole country of cameras against a long route.
        var s = 90.0, n = -90.0, w = 180.0, e = -180.0
        for p in line { s = min(s, p.lat); n = max(n, p.lat); w = min(w, p.lng); e = max(e, p.lng) }
        // Wide enough for the farthest camera allowed; a degree of longitude shrinks toward the poles.
        let latPad = max(0.01, max(maxOffset, webcamOffset) / 110_000 * 1.2)
        let lngPad = latPad / max(cos(max(abs(s), abs(n)) * .pi / 180), 0.05)
        let cum = Geo.cumulative(line)
        var hits: [(LiveCam, Double)] = []
        for c in cams {
            let limitOff = c.source.isRoad ? maxOffset : webcamOffset
            guard limitOff > 0 else { continue }
            let p = c.location
            guard p.lat >= s - latPad, p.lat <= n + latPad, p.lng >= w - lngPad, p.lng <= e + lngPad else { continue }
            let pr = Geo.project(p, on: line, cumulative: cum)
            if pr.offset <= limitOff { hits.append((c, pr.along)) }
        }
        hits.sort { $0.1 < $1.1 }
        var out: [(cam: LiveCam, along: Double)] = []
        for h in hits {
            if let last = out.last, h.1 - last.along < spacing { continue }
            out.append((h.0, h.1))
            if out.count >= limit { break }
        }
        return out
    }

    /** One Windy "nearby" ask along a route: the stretch [from]..[to] meters, and the circle that covers it. */
    public struct WindyRouteAsk: Equatable, Sendable {
        public var from: Double
        public var to: Double
        public var center: LatLng
        public var radiusKm: Int
    }

    /**
     * The circle around one stretch of [line]: centered halfway along it, wide enough for the whole
     * stretch (no point on it is farther from its middle than half its length) plus a webcam's
     * distance off the road.
     */
    public static func windyRouteAsk(_ line: [LatLng], cumulative cum: [Double], from: Double, to: Double) -> WindyRouteAsk {
        let center = Geo.point(along: line, meters: (from + to) / 2, cumulative: cum)
        let radius = min(250, max(10, Int(((to - from) / 2 / 1000).rounded(.up)) + 5))
        return WindyRouteAsk(from: from, to: to, center: center, radiusKm: radius)
    }

    /**
     * Where to ask Windy about the webcams along [line]: stretches of about 40 km, at most
     * [maxAsks] (longer stretches on a long trip). Short routes get one ask.
     */
    public static func windyRouteAsks(_ line: [LatLng], maxAsks: Int = 8) -> [WindyRouteAsk] {
        guard line.count >= 2, maxAsks > 0 else { return [] }
        let cum = Geo.cumulative(line)
        let total = cum.last ?? 0
        let count = min(maxAsks, max(1, Int((total / 40_000).rounded(.up))))
        let step = total / Double(count)
        return (0..<count).map { windyRouteAsk(line, cumulative: cum, from: step * Double($0), to: step * Double($0 + 1)) }
    }

    /**
     * An ask whose answer hit Windy's 50-webcam limit, split into its two halves so the webcams it
     * left out can be found. Nil when it's already small (about 10 km across), as far as it's worth going.
     */
    public static func windySplit(_ ask: WindyRouteAsk, line: [LatLng], cumulative cum: [Double]) -> [WindyRouteAsk]? {
        guard ask.to - ask.from > 10_000 else { return nil }
        let mid = (ask.from + ask.to) / 2
        return [windyRouteAsk(line, cumulative: cum, from: ask.from, to: mid),
                windyRouteAsk(line, cumulative: cum, from: mid, to: ask.to)]
    }

    // MARK: Helpers

    static func valid(_ lat: Double, _ lng: Double) -> Bool {
        (-90...90).contains(lat) && (-180...180).contains(lng) && !(lat == 0 && lng == 0)
    }

    static func absolute(_ raw: String, base: String) -> URL? {
        if raw.hasPrefix("http://") || raw.hasPrefix("https://") { return URL(string: raw) }
        if raw.hasPrefix("/") { return URL(string: base + raw) }
        return nil
    }

    static func clean(_ s: String) -> String? {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty || t.lowercased() == "null" ? nil : t
    }

    /** "A406 BILLET UPASS E" reads as shouting; sources that send all capitals get title case, roads kept. */
    static func tidy(_ name: String) -> String {
        let letters = name.filter(\.isLetter)
        guard letters.count >= 6, letters == letters.uppercased() else { return name }
        return name.split(separator: " ", omittingEmptySubsequences: false).map { w -> String in
            let word = String(w)
            // Road numbers and junctions stay as they are: M25, A406, J10, A1(M).
            if word.first.map({ "MAJB".contains($0) }) == true, word.dropFirst().first?.isNumber == true { return word }
            if word.count <= 2, word.allSatisfy(\.isLetter) { return word }
            return String(word.prefix(1)) + word.dropFirst().lowercased()
        }.joined(separator: " ")
    }
}
