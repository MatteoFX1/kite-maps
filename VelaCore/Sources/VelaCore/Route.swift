import Foundation

public enum TravelMode: Int, CaseIterable, Identifiable, Sendable {
    case drive = 0, bike = 1, walk = 2

    public var id: Int { rawValue }

    public var label: String {
        switch self {
        case .drive: return "Drive"
        case .bike: return "Bike"
        case .walk: return "Walk"
        }
    }

    /** FOSSGIS OSRM backend (routing.openstreetmap.de), the open router Vela uses. */
    var osrmBackend: String {
        switch self {
        case .drive: return "routed-car"
        case .bike: return "routed-bike"
        case .walk: return "routed-foot"
        }
    }
}

public enum ManeuverType: String, Sendable {
    case depart, arrive, straight
    case turnLeft, turnRight, slightLeft, slightRight, sharpLeft, sharpRight, uturn
    case merge, rampLeft, rampRight, forkLeft, forkRight, keepLeft, keepRight
    case roundabout, unknown
}

public struct Maneuver: Hashable, Sendable {
    public var type: ManeuverType
    public var instruction: String
    /** Where on the route line the maneuver happens. */
    public var location: LatLng
    /** Meters from the route start to this maneuver, along the line. */
    public var alongMeters: Double
    /** Length of the step this maneuver starts. */
    public var distanceMeters: Double
    public var road: String?
    public var laneHint: String?
}

/** The posted limit from [startMeters] on, in km/h; nil where it isn't mapped. */
public struct SpeedLimit: Hashable, Sendable {
    public var startMeters: Double
    public var kmh: Double?
}

/** A congested stretch of the route: level 1 slow, 2 heavy, 3 stopped. */
public struct TrafficSpan: Hashable, Sendable {
    public var level: Int
    public var startMeters: Double
    public var lengthMeters: Double
}

public struct Route: Identifiable, Hashable, Sendable {
    public var id = UUID()
    public var mode: TravelMode
    public var polyline: [LatLng]
    public var distanceMeters: Double
    /** Seconds without traffic. */
    public var typicalSeconds: Double
    /** Seconds in current traffic, when Google knows it. */
    public var trafficSeconds: Double?
    public var summary: String?
    public var maneuvers: [Maneuver]
    public var trafficSpans: [TrafficSpan] = []
    /** Speed limits along the route, from OpenStreetMap via the open router. */
    public var speedLimits: [SpeedLimit] = []
    /** "google" or "osm". */
    public var source: String

    public var durationSeconds: Double { trafficSeconds ?? typicalSeconds }

    /** The limit at [meters] along the route. */
    public func speedLimit(at meters: Double) -> Double? {
        speedLimits.last { $0.startMeters <= meters }?.kmh
    }

    /** How much slower than usual, 0 when free-flowing: 0 green, 1 amber, 2 red. */
    public var delayLevel: Int {
        guard let t = trafficSeconds, typicalSeconds > 0 else { return 0 }
        let r = t / typicalSeconds
        return r > 1.35 ? 2 : (r > 1.12 ? 1 : 0)
    }
}

public enum Geo {
    /** Cumulative distance at each vertex. */
    public static func cumulative(_ line: [LatLng]) -> [Double] {
        var out = [0.0]
        out.reserveCapacity(line.count)
        for i in 1..<max(line.count, 1) { out.append(out[i - 1] + line[i - 1].distance(to: line[i])) }
        return out
    }

    public static func length(_ line: [LatLng]) -> Double { cumulative(line).last ?? 0 }

    /** The point [meters] along [line]. */
    public static func point(along line: [LatLng], meters: Double, cumulative cum: [Double]? = nil) -> LatLng {
        guard line.count >= 2 else { return line.first ?? LatLng(0, 0) }
        let c = cum ?? cumulative(line)
        if meters <= 0 { return line[0] }
        for i in 1..<line.count where c[i] >= meters {
            let seg = c[i] - c[i - 1]
            let f = seg > 0 ? (meters - c[i - 1]) / seg : 0
            let a = line[i - 1], b = line[i]
            return LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
        }
        return line[line.count - 1]
    }

    /** The part of [line] between two along-distances. */
    public static func slice(_ line: [LatLng], from: Double, to: Double, cumulative cum: [Double]? = nil) -> [LatLng] {
        guard line.count >= 2, to > from else { return [] }
        let c = cum ?? cumulative(line)
        var out = [point(along: line, meters: from, cumulative: c)]
        for i in 0..<line.count where c[i] > from && c[i] < to { out.append(line[i]) }
        out.append(point(along: line, meters: to, cumulative: c))
        return out
    }

    /** Where [p] falls on [line]: meters along it, and how far off it. */
    public static func project(_ p: LatLng, on line: [LatLng], cumulative cum: [Double]? = nil) -> (along: Double, offset: Double) {
        guard line.count >= 2 else { return (0, line.first.map { p.distance(to: $0) } ?? .greatestFiniteMagnitude) }
        let c = cum ?? cumulative(line)
        let k = cos(p.lat * .pi / 180) * 111_320.0
        var best = (along: 0.0, offset: Double.greatestFiniteMagnitude)
        for i in 0..<(line.count - 1) {
            let a = line[i], b = line[i + 1]
            let ax = (a.lng - p.lng) * k, ay = (a.lat - p.lat) * 110_540
            let bx = (b.lng - p.lng) * k, by = (b.lat - p.lat) * 110_540
            let dx = bx - ax, dy = by - ay
            let len2 = dx * dx + dy * dy
            let t = len2 > 0 ? min(max(-(ax * dx + ay * dy) / len2, 0), 1) : 0
            let x = ax + t * dx, y = ay + t * dy
            let d = (x * x + y * y).squareRoot()
            if d < best.offset { best = (c[i] + t * (c[i + 1] - c[i]), d) }
        }
        return best
    }

    /** Compass bearing from a to b, degrees. */
    public static func bearing(_ a: LatLng, _ b: LatLng) -> Double {
        let p1 = a.lat * .pi / 180, p2 = b.lat * .pi / 180, dl = (b.lng - a.lng) * .pi / 180
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    /** Decode an encoded polyline (precision 5 or 6). */
    public static func decodePolyline(_ s: String, precision: Double = 1e6) -> [LatLng] {
        var out: [LatLng] = []
        let bytes = Array(s.utf8)
        var i = 0, lat = 0, lng = 0
        func next() -> Int? {
            var result = 0, shift = 0
            while i < bytes.count {
                let b = Int(bytes[i]) - 63
                i += 1
                result |= (b & 0x1f) << shift
                shift += 5
                if b < 0x20 { return (result & 1) != 0 ? ~(result >> 1) : (result >> 1) }
            }
            return nil
        }
        while i < bytes.count {
            guard let dlat = next(), let dlng = next() else { break }
            lat += dlat
            lng += dlng
            out.append(LatLng(Double(lat) / precision, Double(lng) / precision))
        }
        return out
    }
}
