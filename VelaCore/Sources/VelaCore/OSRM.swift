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
            for leg in (r["legs"] as? [[String: Any]]) ?? [] {
                for step in (leg["steps"] as? [[String: Any]]) ?? [] {
                    let m = step["maneuver"] as? [String: Any] ?? [:]
                    let type = m["type"] as? String ?? ""
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
            return Route(mode: mode, polyline: line, distanceMeters: distance, typicalSeconds: duration,
                         trafficSeconds: nil, summary: via, maneuvers: maneuvers, source: "osm")
        }
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
                if let o = try? await osrmTask.value {
                    for i in g.indices {
                        if let steps = StepMerge.adopt(o, onto: g[i].polyline) { g[i].maneuvers = steps }
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
                guard p.offset < 25, p.along >= last - 5, p.along < total - 5 else { continue }
                last = max(last, p.along)
                var m2 = m
                m2.alongMeters = p.along
                m2.location = Geo.point(along: line, meters: p.along, cumulative: cum)
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
        return bestScore >= 0.85 ? best : nil
    }
}
