import Foundation

/** A mapped camera from OpenStreetMap: a fixed speed camera, or a license plate reader (DeFlock). */
public struct RoadCamera: Hashable, Sendable, Identifiable {
    public enum Kind: String, Sendable { case speed, plate }
    public var id: Int64
    public var kind: Kind
    public var location: LatLng
    /** Who runs a plate reader ("Flock Safety"), when mapped. */
    public var operatorName: String?
    /** The limit a speed camera checks, when mapped on it. */
    public var maxspeedKmh: Double?
}

/** A stretch of road where average speed is checked between cameras (common in Britain). */
public struct AverageSpeedZone: Hashable, Sendable {
    public var startMeters: Double
    public var endMeters: Double
    public var kmh: Double?
}

/**
 * Speed and plate cameras from Overpass, the way Vela gets them: keyless, failing over across the
 * public mirrors. Fixed installations only; coverage is OSM's.
 */
public enum Overpass {
    public static let endpoints = [
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    ]

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 45
        c.httpAdditionalHeaders = ["User-Agent": "Vela-iOS (https://github.com/matteofx1/vela-ios)"]
        return URLSession(configuration: c)
    }()

    /** Cameras in a box. Nil when every mirror failed, so the caller can retry later. */
    public static func cameras(south: Double, west: Double, north: Double, east: Double,
                               speed: Bool, plate: Bool) async -> [RoadCamera]? {
        let box = String(format: "(%.5f,%.5f,%.5f,%.5f)", south, west, north, east)
        return await cameras(filter: box, speed: speed, plate: plate)
    }

    /** Cameras within [radius] meters of a route, one query per route. */
    public static func cameras(along line: [LatLng], radius: Int = 150, speed: Bool, plate: Bool) async -> [RoadCamera]? {
        guard line.count >= 2 else { return [] }
        let maxPts = 250
        let pts = line.count <= maxPts ? line : (0..<maxPts).map {
            line[min(line.count - 1, Int((Double($0) * Double(line.count - 1) / Double(maxPts - 1)).rounded()))]
        }
        let coords = pts.map { String(format: "%.5f,%.5f", $0.lat, $0.lng) }.joined(separator: ",")
        return await cameras(filter: "(around:\(radius),\(coords))", speed: speed, plate: plate)
    }

    /** A mapped road with a posted limit (OSM `maxspeed`), in km/h, and its shape. */
    public struct LimitWay: Sendable {
        public var kmh: Double?
        public var line: [LatLng]
    }

    /**
     * Cameras and posted speed limits along a route in one query: cameras within 150 m, roads
     * with a `maxspeed` within 12 m. Nil when every mirror failed.
     */
    public static func alongRoute(_ line: [LatLng], speed: Bool, plate: Bool) async -> (cameras: [RoadCamera], ways: [LimitWay], zones: [AverageSpeedZone])? {
        guard line.count >= 2 else { return ([], [], []) }
        let maxPts = 250
        let pts = line.count <= maxPts ? line : (0..<maxPts).map {
            line[min(line.count - 1, Int((Double($0) * Double(line.count - 1) / Double(maxPts - 1)).rounded()))]
        }
        let coords = pts.map { String(format: "%.5f,%.5f", $0.lat, $0.lng) }.joined(separator: ",")
        var parts: [String] = []
        if speed { parts.append("node[\"highway\"=\"speed_camera\"](around:150,\(coords));") }
        if plate { parts.append("node[\"surveillance:type\"=\"ALPR\"](around:150,\(coords));") }
        parts.append("way[\"highway\"][\"maxspeed\"](around:12,\(coords));")
        if speed { parts.append("relation[\"enforcement\"=\"average_speed\"](around:200,\(coords));") }
        let q = "[out:json][timeout:40];(" + parts.joined() + ");out body geom 6000;"
        for ep in endpoints {
            guard let (data, status) = await fetch(ep, query: q), status == 200,
                  let cams = parse(data) else { continue }
            return (cams, parseWays(data), averageZones(data, on: line))
        }
        return nil
    }

    /**
     * Average speed check zones along [line]: each enforcement relation's mapped points (its
     * cameras, its start and end) that lie on the route, from the first to the last.
     */
    public static func averageZones(_ data: Data, on line: [LatLng]) -> [AverageSpeedZone] {
        guard line.count >= 2, let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let elements = root["elements"] as? [[String: Any]] else { return [] }
        let cum = Geo.cumulative(line)
        var out: [AverageSpeedZone] = []
        for e in elements where e["type"] as? String == "relation" {
            let tags = e["tags"] as? [String: String] ?? [:]
            guard tags["enforcement"] == "average_speed" else { continue }
            var pts: [LatLng] = []
            for m in e["members"] as? [[String: Any]] ?? [] {
                if let lat = m["lat"] as? Double, let lon = m["lon"] as? Double { pts.append(LatLng(lat, lon)) }
                for g in m["geometry"] as? [[String: Any]] ?? [] {
                    if let lat = g["lat"] as? Double, let lon = g["lon"] as? Double { pts.append(LatLng(lat, lon)) }
                }
            }
            let along = pts.map { Geo.project($0, on: line, cumulative: cum) }.filter { $0.offset < 60 }.map(\.along)
            guard let a = along.min(), let b = along.max(), b - a > 300 else { continue }
            out.append(AverageSpeedZone(startMeters: a, endMeters: b, kmh: maxspeedKmh(tags["maxspeed"])))
        }
        return out.sorted { $0.startMeters < $1.startMeters }
    }

    static func parseWays(_ data: Data) -> [LimitWay] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let elements = root["elements"] as? [[String: Any]] else { return [] }
        return elements.compactMap { e in
            guard e["type"] as? String == "way", let geom = e["geometry"] as? [[String: Any]] else { return nil }
            let tags = e["tags"] as? [String: String] ?? [:]
            let line = geom.compactMap { g -> LatLng? in
                guard let lat = g["lat"] as? Double, let lon = g["lon"] as? Double else { return nil }
                return LatLng(lat, lon)
            }
            return line.count >= 2 ? LimitWay(kmh: maxspeedKmh(tags["maxspeed"]), line: line) : nil
        }
    }

    /** OSM `maxspeed` to km/h, as Vela reads it: "50", "30 mph", "50 km/h", Britain's "national"; nil for "none", "DE:urban" and the like. */
    public static func maxspeedKmh(_ raw: String?) -> Double? {
        guard let s = raw?.trimmingCharacters(in: .whitespaces).lowercased(), !s.isEmpty else { return nil }
        if s == "walk" { return 7 }
        // Britain's implied limits, as they are mapped: national single and dual carriageway, motorway, built-up.
        switch s {
        case "national", "gb:nsl_single", "uk:nsl_single": return 60 * 1.609344
        case "gb:nsl_dual", "uk:nsl_dual", "gb:motorway", "uk:motorway": return 70 * 1.609344
        case "gb:nsl_restricted", "uk:nsl_restricted", "gb:urban": return 30 * 1.609344
        default: break
        }
        guard let r = s.range(of: #"^\d+(\.\d+)?"#, options: .regularExpression), let n = Double(s[r]) else { return nil }
        let kmh = s.contains("mph") ? n * 1.609344 : n
        return (1...200).contains(kmh) ? kmh : nil
    }

    /**
     * Turn the roads into limit changes along [line]: each road covers the stretch of the route
     * its points lie on. A crossing street touches the route at one point and is skipped.
     */
    public static func speedLimits(_ ways: [LimitWay], on line: [LatLng]) -> [SpeedLimit] {
        guard line.count >= 2 else { return [] }
        let cum = Geo.cumulative(line)
        var spans: [(from: Double, to: Double, kmh: Double?)] = []
        for w in ways {
            let hits = w.line.map { Geo.project($0, on: line, cumulative: cum) }.filter { $0.offset < 12 }.map(\.along)
            guard let lo = hits.min(), let hi = hits.max(), hi - lo > 20 else { continue }
            spans.append((lo, hi, w.kmh))
        }
        guard !spans.isEmpty else { return [] }
        // Sample every 25 m; where roads overlap, the shorter (more specific) one wins.
        var out: [SpeedLimit] = []
        let total = cum.last ?? 0
        var m = 0.0
        while m <= total {
            let here = spans.filter { $0.from - 15 <= m && m <= $0.to + 15 }.min { ($0.to - $0.from) < ($1.to - $1.from) }
            let kmh = here?.kmh
            if out.last.map({ $0.kmh != kmh }) ?? true { out.append(SpeedLimit(startMeters: m, kmh: kmh)) }
            m += 25
        }
        return out
    }

    public static func query(filter: String, speed: Bool, plate: Bool) -> String {
        var parts: [String] = []
        if speed { parts.append("node[\"highway\"=\"speed_camera\"]\(filter);") }
        if plate { parts.append("node[\"surveillance:type\"=\"ALPR\"]\(filter);") }
        return "[out:json][timeout:25];(" + parts.joined() + ");out body 4000;"
    }

    private static func cameras(filter: String, speed: Bool, plate: Bool) async -> [RoadCamera]? {
        guard speed || plate else { return [] }
        let q = query(filter: filter, speed: speed, plate: plate)
        for ep in endpoints {
            guard let (data, status) = await fetch(ep, query: q), status == 200, let list = parse(data) else { continue }
            return list
        }
        return nil
    }

    /** One GET to one mirror, the way Vela asks (`?data=` in the URL). Nil on a network error. */
    public static func fetch(_ endpoint: String, query: String) async -> (Data, Int)? {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        guard let url = URL(string: endpoint + "?data=" + (query.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")),
              let (data, resp) = try? await session.data(from: url) else { return nil }
        return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
    }

    static func parse(_ data: Data) -> [RoadCamera]? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let elements = root["elements"] as? [[String: Any]] else { return nil }
        return elements.compactMap { e in
            guard let lat = e["lat"] as? Double, let lon = e["lon"] as? Double else { return nil }
            let tags = e["tags"] as? [String: String] ?? [:]
            let id = (e["id"] as? NSNumber)?.int64Value ?? 0
            let plate = tags["surveillance:type"] == "ALPR"
            return RoadCamera(id: id, kind: plate ? .plate : .speed, location: LatLng(lat, lon),
                              operatorName: plate ? (tags["operator"] ?? tags["manufacturer"]) : nil,
                              maxspeedKmh: plate ? nil : maxspeedKmh(tags["maxspeed"]))
        }
    }
}

/**
 * When to warn about a camera ahead (ported from Vela's CameraAlerts): about twelve seconds out,
 * once per camera, never for one already passed and never while stopped.
 */
public enum CameraAlerts {
    public static let leadSeconds = 12.0
    public static let minLead = 150.0
    public static let maxLead = 600.0
    public static let movingFloor = 2.0

    public static func leadDistance(speedMps: Double) -> Double {
        min(max(speedMps * leadSeconds, minLead), maxLead)
    }

    /** The index of the camera to announce now, or nil. [cameraMeters] are along the route. */
    public static func due(cameraMeters: [Double], traveled: Double, speedMps: Double, spoken: Set<Int>) -> Int? {
        guard speedMps >= movingFloor else { return nil }
        let lead = leadDistance(speedMps: speedMps)
        var best: Int?
        var bestAhead = Double.greatestFiniteMagnitude
        for (i, m) in cameraMeters.enumerated() where !spoken.contains(i) {
            let ahead = m - traveled
            if ahead <= 0 || ahead > lead { continue }
            if ahead < bestAhead { bestAhead = ahead; best = i }
        }
        return best
    }

    /**
     * Where each camera sits along [line], dropping any farther than [maxOffset] from it
     * (a camera on a parallel street isn't on the route).
     */
    public static func project(_ cams: [RoadCamera], on line: [LatLng], maxOffset: Double = 35) -> [(camera: RoadCamera, along: Double)] {
        guard line.count >= 2 else { return [] }
        let cum = Geo.cumulative(line)
        var out: [(RoadCamera, Double)] = []
        for c in cams {
            let p = Geo.project(c.location, on: line, cumulative: cum)
            if p.offset <= maxOffset { out.append((c, p.along)) }
        }
        return out.sorted { $0.1 < $1.1 }
    }
}
