import Foundation

/** A mapped camera from OpenStreetMap: a fixed speed camera, or a license plate reader (DeFlock). */
public struct RoadCamera: Hashable, Sendable, Identifiable {
    public enum Kind: String, Sendable { case speed, plate }
    public var id: Int64
    public var kind: Kind
    public var location: LatLng
    /** Who runs a plate reader ("Flock Safety"), when mapped. */
    public var operatorName: String?
}

/**
 * Speed and plate cameras from Overpass, the way Vela gets them: keyless, failing over across the
 * public mirrors. Fixed installations only; coverage is OSM's.
 */
public enum Overpass {
    static let endpoints = [
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    ]

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 15
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

    static func query(filter: String, speed: Bool, plate: Bool) -> String {
        var parts: [String] = []
        if speed { parts.append("node[\"highway\"=\"speed_camera\"]\(filter);") }
        if plate { parts.append("node[\"surveillance:type\"=\"ALPR\"]\(filter);") }
        return "[out:json][timeout:25];(" + parts.joined() + ");out body 4000;"
    }

    private static func cameras(filter: String, speed: Bool, plate: Bool) async -> [RoadCamera]? {
        guard speed || plate else { return [] }
        let q = query(filter: filter, speed: speed, plate: plate)
        for ep in endpoints {
            var req = URLRequest(url: URL(string: ep)!)
            req.httpMethod = "POST"
            req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            var allowed = CharacterSet.alphanumerics
            allowed.insert(charactersIn: "-._~")
            req.httpBody = ("data=" + (q.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")).data(using: .utf8)
            guard let (data, resp) = try? await session.data(for: req),
                  (resp as? HTTPURLResponse)?.statusCode == 200,
                  let list = parse(data) else { continue }
            return list
        }
        return nil
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
                              operatorName: plate ? (tags["operator"] ?? tags["manufacturer"]) : nil)
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
            let p = c.location
            let k = cos(p.lat * .pi / 180) * 111_320.0
            var best = (d: Double.greatestFiniteMagnitude, along: 0.0)
            for i in 0..<(line.count - 1) {
                let a = line[i], b = line[i + 1]
                let ax = (a.lng - p.lng) * k, ay = (a.lat - p.lat) * 110_540
                let bx = (b.lng - p.lng) * k, by = (b.lat - p.lat) * 110_540
                let dx = bx - ax, dy = by - ay
                let len2 = dx * dx + dy * dy
                let t = len2 > 0 ? min(max(-(ax * dx + ay * dy) / len2, 0), 1) : 0
                let x = ax + t * dx, y = ay + t * dy
                let d = (x * x + y * y).squareRoot()
                if d < best.d { best = (d, cum[i] + t * (cum[i + 1] - cum[i])) }
            }
            if best.d <= maxOffset { out.append((c, best.along)) }
        }
        return out.sorted { $0.1 < $1.1 }
    }
}
