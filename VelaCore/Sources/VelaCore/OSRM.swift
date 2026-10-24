import Foundation

/**
 * The FOSSGIS OSRM servers (routing.openstreetmap.de), which Vela uses for turn-by-turn steps.
 * Here they're the open-data router: used when Google gives no route.
 */
public enum OSRM {
    static let userAgent = "VelaMaps-iOS/0.2 (+https://github.com/matteofx1/vela-ios)"

    public static func route(from a: LatLng, to b: LatLng, mode: TravelMode) async throws -> [Route] {
        let profile = mode == .drive ? "driving" : (mode == .bike ? "bike" : "foot")
        let coords = "\(a.lng),\(a.lat);\(b.lng),\(b.lat)"
        let s = "https://routing.openstreetmap.de/\(mode.osrmBackend)/route/v1/\(profile)/\(coords)" +
            "?overview=full&geometries=polyline6&steps=true&alternatives=\(mode == .drive)"
        var r = URLRequest(url: URL(string: s)!)
        r.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        r.timeoutInterval = 20
        let (data, _) = try await URLSession.shared.data(for: r)
        return parse(data, mode: mode)
    }

    /**
     * The open router forced through points sampled off [line] (Google's route), so its
     * turn-by-turn follows the same roads, the way Vela gets readable steps for Google routes.
     */
    public static func routeVia(_ line: [LatLng], mode: TravelMode) async throws -> Route? {
        guard line.count >= 2 else { return nil }
        let cum = Geo.cumulative(line)
        let total = cum.last ?? 0
        let vias = min(12, Int(total / 1_500))
        var pts = [line[0]]
        var bearings = [bearing(line, cum, at: 0)]
        for i in stride(from: 1, through: vias, by: 1) {
            let m = total * Double(i) / Double(vias + 1)
            pts.append(Geo.point(along: line, meters: m, cumulative: cum))
            bearings.append(bearing(line, cum, at: m))
        }
        pts.append(line[line.count - 1])
        bearings.append(nil)
        let profile = mode == .drive ? "driving" : (mode == .bike ? "bike" : "foot")
        let coords = pts.map { String(format: "%.6f,%.6f", $0.lng, $0.lat) }.joined(separator: ";")
        let b = bearings.map { $0.map { "\(Int($0)),45" } ?? "" }.joined(separator: ";")
        let radii = pts.indices.map { $0 == 0 || $0 == pts.count - 1 ? "unlimited" : "30" }.joined(separator: ";")
        let base = "https://routing.openstreetmap.de/\(mode.osrmBackend)/route/v1/\(profile)/\(coords)" +
            "?overview=full&geometries=polyline6&steps=true&continue_straight=true&bearings=\(b)&radiuses=\(radii)"
        guard let url = URL(string: base) else { return nil }
        var r = URLRequest(url: url)
        r.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        r.timeoutInterval = 20
        let (data, _) = try await URLSession.shared.data(for: r)
        return parse(data, mode: mode).first
    }

    private static func bearing(_ line: [LatLng], _ cum: [Double], at m: Double) -> Double? {
        let a = Geo.point(along: line, meters: max(0, m - 10), cumulative: cum)
        let b = Geo.point(along: line, meters: min(cum.last ?? 0, m + 10), cumulative: cum)
        return a.distance(to: b) > 1 ? Geo.bearing(a, b) : nil
    }

    static func parse(_ data: Data, mode: TravelMode) -> [Route] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let routes = root["routes"] as? [[String: Any]] else { return [] }
        return routes.compactMap { r in
            guard let geo = r["geometry"] as? String,
                  let distance = r["distance"] as? Double,
                  let duration = r["duration"] as? Double else { return nil }
            let line = Geo.decodePolyline(geo, precision: 1e6)
            guard line.count >= 2 else { return nil }
            let cum = Geo.cumulative(line)
            var maneuvers: [Maneuver] = []
            var along = 0.0
            var roads: [String: Double] = [:]
            let legs = (r["legs"] as? [[String: Any]]) ?? []
            for (li, leg) in legs.enumerated() {
                for step in (leg["steps"] as? [[String: Any]]) ?? [] {
                    let m = step["maneuver"] as? [String: Any] ?? [:]
                    let type = m["type"] as? String ?? ""
                    // A route through vias stops and restarts at each one: those aren't turns.
                    if (type == "arrive" && li < legs.count - 1) || (type == "depart" && li > 0) {
                        if let i = maneuvers.indices.last { maneuvers[i].distanceMeters += step["distance"] as? Double ?? 0 }
                        along += step["distance"] as? Double ?? 0
                        continue
                    }
                    let modifier = m["modifier"] as? String
                    let name = (step["name"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                    let ref = (step["ref"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                    let dist = step["distance"] as? Double ?? 0
                    if let n = name ?? ref { roads[n, default: 0] += dist }
                    let loc = (m["location"] as? [Double]).map { LatLng($0[1], $0[0]) } ?? Geo.point(along: line, meters: along, cumulative: cum)
                    let mt = maneuverType(type, modifier)
                    maneuvers.append(Maneuver(type: mt, instruction: instruction(type: type, modifier: modifier, road: name ?? ref,
                                                                                 exit: m["exit"] as? Int, mode: mode),
                                              location: loc, alongMeters: min(along, cum.last ?? along),
                                              distanceMeters: dist, road: name ?? ref, laneHint: nil))
                    along += dist
                }
            }
            if let last = maneuvers.indices.last {
                maneuvers[last].alongMeters = cum.last ?? along
            }
            let via = roads.max { $0.value < $1.value }?.key
            var route = Route(mode: mode, polyline: line, distanceMeters: distance, typicalSeconds: duration,
                              trafficSeconds: nil, summary: via, maneuvers: maneuvers, source: "osm")
            route.speedLimits = speedLimits(legs, line: line, cumulative: cum)
            return route
        }
    }

    /**
     * Per-segment `maxspeed` annotations ({speed, unit} or {unknown}/{none}) turned into
     * limit changes along the line. The legs' segments run end to end along the overview line.
     */
    static func speedLimits(_ legs: [[String: Any]], line: [LatLng], cumulative cum: [Double]) -> [SpeedLimit] {
        var out: [SpeedLimit] = []
        var seg = 0
        for leg in legs {
            let speeds = ((leg["annotation"] as? [String: Any])?["maxspeed"] as? [[String: Any]]) ?? []
            for sp in speeds {
                defer { seg += 1 }
                guard seg < cum.count else { break }
                var kmh: Double?
                if let v = sp["speed"] as? Double {
                    kmh = (sp["unit"] as? String) == "mph" ? v * 1.609344 : v
                }
                if out.last.map({ $0.kmh != kmh }) ?? true {
                    out.append(SpeedLimit(startMeters: cum[seg], kmh: kmh))
                }
            }
        }
        return out.contains { $0.kmh != nil } ? out : []
    }

    static func maneuverType(_ type: String, _ modifier: String?) -> ManeuverType {
        switch type {
        case "depart": return .depart
        case "arrive": return .arrive
        case "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary": return .roundabout
        case "merge": return .merge
        case "on ramp", "off ramp":
            return (modifier ?? "").contains("left") ? .rampLeft : .rampRight
        case "fork":
            return (modifier ?? "").contains("left") ? .forkLeft : .forkRight
        default: break
        }
        switch modifier {
        case "left": return .turnLeft
        case "right": return .turnRight
        case "slight left": return type == "continue" || type == "new name" ? .keepLeft : .slightLeft
        case "slight right": return type == "continue" || type == "new name" ? .keepRight : .slightRight
        case "sharp left": return .sharpLeft
        case "sharp right": return .sharpRight
        case "uturn": return .uturn
        default: return .straight
        }
    }

    static func instruction(type: String, modifier: String?, road: String?, exit: Int?, mode: TravelMode) -> String {
        let onto = road.map { " onto \($0)" } ?? ""
        let dir = modifier ?? "straight"
        switch type {
        case "depart":
            let verb = mode == .drive ? "Drive" : (mode == .bike ? "Ride" : "Walk")
            return road.map { "\(verb) along \($0)" } ?? "Head out"
        case "arrive": return "You have arrived"
        case "roundabout", "rotary":
            let n = exit.map { "take the \(ordinal($0)) exit" } ?? "go through it"
            return "At the roundabout, \(n)" + onto
        case "exit roundabout", "exit rotary": return "Exit the roundabout" + onto
        case "merge": return "Merge" + onto
        case "on ramp": return "Take the ramp" + (road.map { " to \($0)" } ?? "")
        case "off ramp": return "Take the exit" + (road.map { " toward \($0)" } ?? "")
        case "fork": return "Keep \(dir.contains("left") ? "left" : "right") at the fork" + onto
        case "end of road": return "At the end of the road, turn \(dir.replacingOccurrences(of: "slight ", with: ""))" + onto
        case "continue", "new name":
            if dir.contains("slight") { return "Keep \(dir.contains("left") ? "left" : "right")" + onto }
            return "Continue" + (road.map { " on \($0)" } ?? "")
        default:
            switch dir {
            case "uturn": return "Make a U-turn" + onto
            case "straight": return "Continue straight" + onto
            default: return "Turn \(dir)" + onto
            }
        }
    }

    private static func ordinal(_ n: Int) -> String {
        switch n {
        case 1: return "1st"
        case 2: return "2nd"
        case 3: return "3rd"
        default: return "\(n)th"
        }
    }
}

/** What the app asks for routes: Google (traffic, alternates), else the open router. */
public enum Directions {
    public struct Result: Sendable {
        public var routes: [Route]
        public var error: String?
        public init(routes: [Route], error: String? = nil) {
            self.routes = routes
            self.error = error
        }
    }

    public static func routes(from a: LatLng, to b: LatLng, mode: TravelMode,
                              avoidHighways: Bool = false, avoidTolls: Bool = false,
                              useGoogle: Bool = true) async -> Result {
        // Ask both at once: Google's route knows traffic, OSRM's steps read better (Vela puts
        // the open router's turn-by-turn on Google's line the same way).
        let osrmTask = Task { try await OSRM.route(from: a, to: b, mode: mode) }
        var err: String?
        if useGoogle { do {
            var g = try await GoogleClient.shared.directions(from: a, to: b, mode: mode,
                                                              avoidHighways: avoidHighways, avoidTolls: avoidTolls)
            if !g.isEmpty {
                // One at a time: the FOSSGIS server turns away bursts.
                var vias: [Int: Route] = [:]
                for i in g.indices {
                    if let r = try? await OSRM.routeVia(g[i].polyline, mode: mode) { vias[i] = r }
                }
                let plain = (try? await osrmTask.value) ?? []
                for i in g.indices {
                    if let steps = StepMerge.adopt(vias[i].map { [$0] + plain } ?? plain, onto: g[i].polyline) {
                        g[i].maneuvers = steps
                    }
                }
                return Result(routes: g)
            }
        } catch {
            err = error.localizedDescription
        } }
        do {
            let o = try await osrmTask.value
            if !o.isEmpty { return Result(routes: o) }
        } catch {
            err = error.localizedDescription
        }
        return Result(routes: [], error: err ?? "No route found.")
    }
}

/** Puts an open router's steps on Google's route line, when they follow the same roads. */
public enum StepMerge {
    /** The steps of the candidate that best fits [line], placed on it; nil if none fits well. */
    public static func adopt(_ candidates: [Route], onto line: [LatLng]) -> [Maneuver]? {
        guard line.count >= 2 else { return nil }
        let cum = Geo.cumulative(line)
        let total = cum.last ?? 0
        var best: [Maneuver]?
        var bestScore = 0.0
        for o in candidates {
            let mids = o.maneuvers.filter { $0.type != .depart && $0.type != .arrive }
            var placed: [Maneuver] = []
            var last = 0.0
            for m in mids {
                let p = Geo.project(m.location, on: line, cumulative: cum)
                guard p.offset < 40, p.along >= last - 30, p.along < total - 5 else { continue }
                last = max(last, p.along)
                var m2 = m
                m2.alongMeters = last
                m2.location = Geo.point(along: line, meters: last, cumulative: cum)
                placed.append(m2)
            }
            let score = mids.isEmpty ? (abs(Geo.length(o.polyline) - total) < 100 ? 1 : 0) : Double(placed.count) / Double(mids.count)
            guard score > bestScore else { continue }
            bestScore = score
            var depart = o.maneuvers.first { $0.type == .depart }
                ?? Maneuver(type: .depart, instruction: "Head out", location: line[0], alongMeters: 0, distanceMeters: 0, road: nil, laneHint: nil)
            depart.alongMeters = 0
            depart.location = line[0]
            var arrive = o.maneuvers.last { $0.type == .arrive }
                ?? Maneuver(type: .arrive, instruction: "You have arrived", location: line[line.count - 1], alongMeters: total, distanceMeters: 0, road: nil, laneHint: nil)
            arrive.alongMeters = total
            arrive.location = line[line.count - 1]
            var all = [depart] + placed + [arrive]
            for i in 0..<(all.count - 1) { all[i].distanceMeters = all[i + 1].alongMeters - all[i].alongMeters }
            all[all.count - 1].distanceMeters = 0
            best = all
        }
        return bestScore >= 0.7 ? best : nil
    }
}
