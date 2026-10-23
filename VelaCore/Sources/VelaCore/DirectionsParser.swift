import Foundation

/**
 * Google `/maps/preview/directions` request and response (port of Vela's DirectionsPb and
 * DirectionsParser). Routes are at `root[0][1]`; route geometry at `root[0][7][i]` as E7 deltas;
 * per route summary at `[0]`: distance `[2][0]`, typical seconds `[3][0]`, traffic seconds
 * `[10][0][0]`, summary text `[1]`; congestion spans at `route[3][5][0]`; steps are
 * `<step maneuver='..' meters='..'>` markup strings anywhere under the route.
 */
enum DirectionsPb {
    static let template = """
        !1m4!3m2!3d{OLAT}!4d{OLNG}!6e2!1m4!3m2!3d{DLAT}!4d{DLNG}!6e2!3m12!1m3!1d24960.741896132306\
        !2d-121.7527808!3d38.554674999999996!2m3!1f0.0!2f0.0!3f0.0!3m2!1i1024!2i768!4f13.1!6m56!1m\
        5!18b1!30b1!31m1!1b1!34e1!2m4!5m1!6e2!20e3!39b1!6m27!32i1!49b1!63m0!66b1!85b1!114b1!149b1!\
        206b1!209b1!212b1!216b1!222b1!223b1!232b1!234b1!235b1!244b1!246b1!250b1!253b1!260b1!266b1!\
        270b1!273b1!279b1!281b1!291m0!10b1!12b1!13b1!14b1!16b1!17m1!3e1!20m5!1e{MODE}!2e3!5e2!6b1!\
        14b1!46m1!1b0!96b1!99b1!15i10142!20m28!1m6!1m2!1i0!2i0!2m2!1i530!2i768!1m6!1m2!1i974!2i0!2\
        m2!1i1024!2i768!1m6!1m2!1i0!2i0!2m2!1i1024!2i20!1m6!1m2!1i0!2i748!2m2!1i1024!2i768!27b1!40\
        i783!47m2!8b1!10e2
        """

    static func build(origin: LatLng, destination: LatLng, mode: TravelMode,
                      avoidHighways: Bool = false, avoidTolls: Bool = false) -> String {
        var pb = template
        // Center the request's map on the trip, the way a browser's map sits around it.
        if let r = pb.range(of: #"!3m12!1m3!1d[0-9.]+!2d-?[0-9.]+!3d-?[0-9.]+"#, options: .regularExpression) {
            let s = min(origin.lat, destination.lat), n = max(origin.lat, destination.lat)
            let w = min(origin.lng, destination.lng), e = max(origin.lng, destination.lng)
            let lat = (s + n) / 2, lng = (w + e) / 2
            let span = max((n - s) * 111_320, (e - w) * 111_320 * cos(lat * .pi / 180))
            pb.replaceSubrange(r, with: "!3m12!1m3!1d\(max(span * 1.35, 1500))!2d\(lng)!3d\(lat)")
        }
        if mode == .drive, avoidHighways || avoidTolls,
           let r = pb.range(of: "!6m56!1m5!18b1!30b1!31m1!1b1!34e1!2m4") {
            let flags = (avoidHighways ? "!1b1" : "") + (avoidTolls ? "!2b1" : "")
            let added = (avoidHighways ? 1 : 0) + (avoidTolls ? 1 : 0)
            pb.replaceSubrange(r, with: "!6m\(56 + added)!1m5!18b1!30b1!31m1!1b1!34e1!2m\(4 + added)\(flags)")
        }
        return pb
            .replacingOccurrences(of: "{OLAT}", with: String(origin.lat))
            .replacingOccurrences(of: "{OLNG}", with: String(origin.lng))
            .replacingOccurrences(of: "{DLAT}", with: String(destination.lat))
            .replacingOccurrences(of: "{DLNG}", with: String(destination.lng))
            .replacingOccurrences(of: "{MODE}", with: String(mode.rawValue))
    }
}

enum DirectionsParser {
    static func parse(root: Any, mode: TravelMode) -> [Route] {
        guard let routes = arr(at(root, 0, 1)) else { return [] }
        let geoms = arr(at(root, 0, 7))
        return routes.enumerated().compactMap { i, r in
            parseRoute(r, geometry: decodeGeometry(geoms.flatMap { i < $0.count ? $0[i] : nil }), mode: mode)
        }
    }

    private static func parseRoute(_ route: Any, geometry: [LatLng]?, mode: TravelMode) -> Route? {
        guard let summary = at(route, 0),
              let distance = dbl(at(summary, 2, 0)),
              let typical = dbl(at(summary, 3, 0)) else { return nil }
        let traffic = dbl(at(summary, 10, 0, 0))
        let start = coord(at(summary, 7, 3, 2)), end = coord(at(summary, 7, 3, 3))
        let line = (geometry?.count ?? 0) >= 2 ? geometry! : [start, end].compactMap { $0 }
        guard line.count >= 2 else { return nil }
        let spans: [TrafficSpan] = (arr(at(route, 3, 5, 0)) ?? []).compactMap { s in
            guard let level = int(at(s, 0)), let st = dbl(at(s, 1)), let len = dbl(at(s, 2)), len > 0, level >= 1 else { return nil }
            return TrafficSpan(level: level, startMeters: st, lengthMeters: len)
        }.sorted { $0.startMeters < $1.startMeters }
        var steps: [Maneuver] = []
        collectSteps(route, into: &steps)
        return Route(mode: mode, polyline: line, distanceMeters: distance, typicalSeconds: typical,
                     trafficSeconds: traffic, summary: str(at(summary, 1)),
                     maneuvers: place(steps, on: line), trafficSpans: spans, source: "google")
    }

    private static func coord(_ n: Any?) -> LatLng? {
        guard let lat = dbl(at(n, 2)), let lng = dbl(at(n, 3)) else { return nil }
        return LatLng(lat, lng)
    }

    /** `[0]` latitude deltas, `[1]` longitude deltas, E7, the first absolute. */
    static func decodeGeometry(_ node: Any?) -> [LatLng]? {
        guard let lat = arr(at(node, 0)), let lng = arr(at(node, 1)), lat.count == lng.count, lat.count >= 2 else { return nil }
        var la = 0.0, ln = 0.0
        return lat.indices.map { i in
            la += dbl(lat[i]) ?? 0
            ln += dbl(lng[i]) ?? 0
            return LatLng(la / 1e7, ln / 1e7)
        }
    }

    private static func collectSteps(_ n: Any, into out: inout [Maneuver]) {
        if let a = n as? [Any] {
            for e in a { collectSteps(e, into: &out) }
        } else if let s = n as? String, s.contains("<step ") {
            out.append(parseStep(s))
        }
    }

    static func parseStep(_ s: String) -> Maneuver {
        func attr(_ pattern: String) -> String? {
            guard let r = s.range(of: pattern, options: [.regularExpression, .caseInsensitive]) else { return nil }
            let m = String(s[r])
            guard let q1 = m.firstIndex(of: "'") else { return nil }
            return String(m[m.index(after: q1)...].dropLast())
        }
        let token = attr(#"maneuver='[^']+'"#)
        let side = attr(#"side='[^']+'"#)
        let severity = attr(#"\btype='[^']+'"#)
        let meters = Double(attr(#"meters='[0-9]+'"#) ?? "") ?? 0
        let road = s.range(of: #"<road>[^<]+</road>"#, options: .regularExpression).map {
            String(s[$0]).replacingOccurrences(of: "<road>", with: "").replacingOccurrences(of: "</road>", with: "")
        }
        var text = s.replacingOccurrences(of: #"<[^>]+>"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        var lane: String?
        if let r = text.range(of: #"^(Use (the .+? lanes?|any lane)) to "#, options: [.regularExpression, .caseInsensitive]) {
            lane = String(text[r]).replacingOccurrences(of: " to ", with: "").trimmingCharacters(in: .whitespaces)
            text.removeSubrange(r)
        }
        text = text.capitalizedFirst
        return Maneuver(type: mapType(token, side: side, severity: severity),
                        instruction: text.isEmpty ? "Continue" : text,
                        location: LatLng(0, 0), alongMeters: 0, distanceMeters: meters, road: road, laneHint: lane)
    }

    static func mapType(_ token: String?, side: String?, severity: String?) -> ManeuverType {
        let left = side?.uppercased() == "LEFT", right = side?.uppercased() == "RIGHT"
        let slight = severity?.uppercased() == "SLIGHT", sharp = severity?.uppercased() == "SHARP"
        func lr(_ l: ManeuverType, _ r: ManeuverType, _ f: ManeuverType) -> ManeuverType { left ? l : (right ? r : f) }
        switch token?.uppercased() ?? "" {
        case "DEPART": return .depart
        case "DESTINATION", "ARRIVE": return .arrive
        case "TURN_LEFT": return .turnLeft
        case "TURN_RIGHT": return .turnRight
        case "TURN_SLIGHT_LEFT": return .slightLeft
        case "TURN_SLIGHT_RIGHT": return .slightRight
        case "TURN_SHARP_LEFT": return .sharpLeft
        case "TURN_SHARP_RIGHT": return .sharpRight
        case "TURN":
            if slight { return lr(.slightLeft, .slightRight, .straight) }
            if sharp { return lr(.sharpLeft, .sharpRight, .straight) }
            return lr(.turnLeft, .turnRight, .straight)
        case "UTURN", "UTURN_LEFT", "UTURN_RIGHT": return .uturn
        case "STRAIGHT", "CONTINUE", "NAME_CHANGE": return .straight
        case "MERGE", "MERGE_LEFT", "MERGE_RIGHT": return .merge
        case "FORK", "FORK_LEFT", "FORK_RIGHT": return lr(.forkLeft, .forkRight, .straight)
        case "ON_RAMP", "OFF_RAMP", "RAMP", "RAMP_LEFT", "ON_RAMP_LEFT", "OFF_RAMP_LEFT",
             "RAMP_RIGHT", "ON_RAMP_RIGHT", "OFF_RAMP_RIGHT": return lr(.rampLeft, .rampRight, .merge)
        case "KEEP", "KEEP_LEFT", "KEEP_RIGHT": return lr(.keepLeft, .keepRight, .straight)
        case let t where t.contains("ROUNDABOUT"): return .roundabout
        default: return (left || right) ? lr(.turnLeft, .turnRight, .unknown) : .unknown
        }
    }

    /** Put each step on the line at its share of the summed step distances; the last one ends it. */
    static func place(_ steps: [Maneuver], on line: [LatLng]) -> [Maneuver] {
        guard !steps.isEmpty, line.count >= 2 else { return steps }
        let cum = Geo.cumulative(line)
        let total = cum.last ?? 0
        let stepTotal = max(steps.reduce(0) { $0 + $1.distanceMeters }, 1)
        var acc = 0.0
        var out = steps.enumerated().map { i, m -> Maneuver in
            var m = m
            let frac = min(max(acc / stepTotal, 0), 1)
            acc += m.distanceMeters
            m.alongMeters = frac * total
            m.location = Geo.point(along: line, meters: m.alongMeters, cumulative: cum)
            return m
        }
        if out.last?.type != .arrive {
            out.append(Maneuver(type: .arrive, instruction: "You have arrived", location: line[line.count - 1],
                                alongMeters: total, distanceMeters: 0, road: nil, laneHint: nil))
        } else {
            out[out.count - 1].alongMeters = total
            out[out.count - 1].location = line[line.count - 1]
        }
        return out
    }
}
