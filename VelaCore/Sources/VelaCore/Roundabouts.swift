import Foundation

/**
 * The real layout of the roundabouts on a route, from OpenStreetMap: the circle, every road that
 * meets it, which of those you can leave by, and the one the route takes. Drawn in the driving
 * banner so "6th exit" comes with a picture of all six.
 */
public enum Roundabouts {
    struct Way {
        var id: Int64
        var nodes: [Int64]
        var points: [LatLng]
        var tags: [String: String]
        var isRing: Bool { ["roundabout", "circular"].contains(tags["junction"] ?? "") }
    }

    /** A road meeting the circle: where (compass bearing from the middle), and which ways traffic can go. */
    struct Arm {
        var angle: Double
        var canExit: Bool
        var canEnter: Bool
        var name: String?
    }

    /** Fills in [Maneuver.roundabout] on every roundabout of [route] that OpenStreetMap answers for. */
    public static func apply(to route: Route) async -> Route? {
        let spots = route.maneuvers.filter { $0.type == .roundabout && !$0.leavesRoundabout }.map(\.location)
        guard !spots.isEmpty, let data = await fetch(spots) else { return nil }
        var r = route
        let ways = parse(data)
        guard !ways.isEmpty else { return nil }
        let clockwise = Geo.drivesOnLeft(spots[0])
        let cum = Geo.cumulative(route.polyline)
        var changed = false
        for i in r.maneuvers.indices where r.maneuvers[i].type == .roundabout {
            let m = r.maneuvers[i]
            if m.leavesRoundabout {
                // The way off: the same picture as the way on.
                if i > 0, r.maneuvers[i - 1].type == .roundabout, let prev = r.maneuvers[i - 1].roundabout {
                    r.maneuvers[i].roundabout = prev
                    r.maneuvers[i].exit = r.maneuvers[i].exit ?? r.maneuvers[i - 1].exit
                }
                continue
            }
            if let s = shape(ways, entry: m.location, line: route.polyline, cumulative: cum, along: m.alongMeters,
                             clockwise: clockwise, exitNumber: m.exit) {
                r.maneuvers[i].roundabout = s
                changed = true
            }
        }
        return changed ? r : nil
    }

    static func fetch(_ spots: [LatLng]) async -> Data? {
        // At most 40 roundabouts per drive; a long one rarely has more in the next stretch that matters.
        let around = spots.prefix(40).map {
            String(format: "way[\"junction\"~\"^(roundabout|circular)$\"](around:35,%.6f,%.6f);", $0.lat, $0.lng)
        }.joined()
        let q = "[out:json][timeout:25];(" + around + ")->.r;.r out geom;node(w.r)->.n;" +
            "way(bn.n)[\"highway\"][\"highway\"!~\"^(footway|cycleway|path|pedestrian|steps|bridleway|corridor|construction|proposed|platform|bus_guideway|escape|raceway)$\"];out geom;"
        return await Overpass.query(q)
    }

    static func parse(_ data: Data) -> [Way] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let elements = root["elements"] as? [[String: Any]] else { return [] }
        var seen = Set<Int64>()
        var out: [Way] = []
        for e in elements where e["type"] as? String == "way" {
            guard let id = (e["id"] as? NSNumber)?.int64Value, seen.insert(id).inserted,
                  let nodes = (e["nodes"] as? [NSNumber])?.map(\.int64Value),
                  let geom = e["geometry"] as? [[String: Any]], geom.count == nodes.count, nodes.count >= 2 else { continue }
            let pts = geom.compactMap { g -> LatLng? in
                guard let lat = g["lat"] as? Double, let lon = g["lon"] as? Double else { return nil }
                return LatLng(lat, lon)
            }
            guard pts.count == nodes.count else { continue }
            out.append(Way(id: id, nodes: nodes, points: pts, tags: e["tags"] as? [String: String] ?? [:]))
        }
        return out
    }

    /**
     * The roundabout entered at [entry]: the ring ways near it and every road touching them. The
     * exit taken is where [line] leaves the circle after [along].
     */
    static func shape(_ ways: [Way], entry: LatLng, line: [LatLng], cumulative cum: [Double], along: Double,
                      clockwise: Bool, exitNumber: Int?) -> RoundaboutShape? {
        let rings = ways.filter(\.isRing)
        // The ring way(s) passing the entry, and any joined to them.
        guard let first = rings.min(by: { distance(entry, $0) < distance(entry, $1) }), distance(entry, first) < 40 else { return nil }
        var ring = [first]
        var nodes = Set(first.nodes)
        var grew = true
        while grew {
            grew = false
            for w in rings where !ring.contains(where: { $0.id == w.id }) && w.nodes.contains(where: nodes.contains) {
                ring.append(w)
                nodes.formUnion(w.nodes)
                grew = true
            }
        }
        // The middle: the centroid of the circle's outline, by length, so bunched-up nodes don't pull it.
        var sx = 0.0, sy = 0.0, total = 0.0
        for w in ring {
            for k in 1..<w.points.count {
                let a = w.points[k - 1], b = w.points[k]
                let len = a.distance(to: b)
                sx += (a.lat + b.lat) / 2 * len
                sy += (a.lng + b.lng) / 2 * len
                total += len
            }
        }
        guard total > 10 else { return nil }
        let center = LatLng(sx / total, sy / total)
        let radius = ring.flatMap(\.points).map { $0.distance(to: center) }.reduce(0, +) / Double(ring.flatMap(\.points).count)
        guard radius > 3, radius < 250 else { return nil }

        // Every road meeting the circle, both ways out from where it touches.
        var arms: [Arm] = []
        for w in ways where !w.isRing {
            if let access = w.tags["access"] ?? w.tags["motor_vehicle"], ["no", "private"].contains(access) { continue }
            let oneway = onewayDirection(w.tags)
            for (i, n) in w.nodes.enumerated() where nodes.contains(n) {
                let p = w.points[i]
                // Away from the ring along the way's own order (forward) or against it.
                for dir in [1, -1] {
                    let j = i + dir
                    guard j >= 0, j < w.nodes.count, !nodes.contains(w.nodes[j]) else { continue }
                    // Traffic leaves the circle along this arm when it may flow in that direction.
                    let leaves = oneway == 0 || oneway == dir
                    let comes = oneway == 0 || oneway == -dir
                    arms.append(Arm(angle: Geo.bearing(center, p), canExit: leaves, canEnter: comes, name: w.tags["ref"] ?? w.tags["name"]))
                }
            }
        }
        arms = merge(arms)
        guard !arms.isEmpty else { return nil }

        // Turn everything so the way in sits at the bottom (180 degrees).
        let entryAngle = Geo.bearing(center, entry)
        func rel(_ a: Double) -> Double { norm(a - entryAngle + 180) }
        // How far round from the way in, the way traffic goes; the same road back out is a full lap.
        func travel(_ r: Double) -> Double {
            let t = norm(clockwise ? r - 180 : 180 - r)
            return t < 15 ? t + 360 : t
        }
        let exits = arms.filter(\.canExit).map { rel($0.angle) }.sorted { travel($0) < travel($1) }
        guard !exits.isEmpty else { return nil }
        // Roads that only come in, except the one you're on.
        let inOnly = arms.filter { !$0.canExit && angleGap(rel($0.angle), 180) > 25 }.map { rel($0.angle) }

        // Where the route leaves: its first point after the entry clearly outside the circle.
        guard let outPoint = leavingPoint(line, cumulative: cum, after: along, center: center, radius: radius) else { return nil }
        let outAngle = rel(Geo.bearing(center, outPoint))
        guard let taken = exits.indices.min(by: { angleGap(exits[$0], outAngle) < angleGap(exits[$1], outAngle) }),
              angleGap(exits[taken], outAngle) < 50 else { return nil }
        return RoundaboutShape(exits: exits, taken: taken, entriesOnly: inOnly, clockwise: clockwise, complete: true)
    }

    /** 1 when traffic may only go along the way's node order, -1 only against it, 0 both ways. */
    static func onewayDirection(_ tags: [String: String]) -> Int {
        switch tags["oneway"] ?? "" {
        case "yes", "true", "1": return 1
        case "-1", "reverse": return -1
        default: return tags["highway"] == "motorway" ? 1 : 0
        }
    }

    /**
     * A dual carriageway meets the circle twice, once going in and once coming out, close
     * together: that's one road in the picture.
     */
    static func merge(_ arms: [Arm]) -> [Arm] {
        var list = arms.sorted { $0.angle < $1.angle }
        var i = 0
        while list.count > 1 && i < list.count {
            let j = (i + 1) % list.count
            let a = list[i], b = list[j]
            let pair = (a.canExit && !a.canEnter && b.canEnter && !b.canExit) || (b.canExit && !b.canEnter && a.canEnter && !a.canExit)
            let sameRoad = a.name == nil || b.name == nil || a.name == b.name
            if i != j, pair, sameRoad, angleGap(a.angle, b.angle) < 40 {
                let mid = norm(a.angle + signedGap(a.angle, b.angle) / 2)
                list[i] = Arm(angle: mid, canExit: true, canEnter: true, name: a.name ?? b.name)
                list.remove(at: j)
                if j < i { i -= 1 }
            } else {
                i += 1
            }
        }
        return list
    }

    static func leavingPoint(_ line: [LatLng], cumulative cum: [Double], after along: Double, center: LatLng, radius: Double) -> LatLng? {
        var m = along + 5
        let end = min((cum.last ?? 0), along + radius * 8 + 200)
        while m <= end {
            let p = Geo.point(along: line, meters: m, cumulative: cum)
            if p.distance(to: center) > radius + max(12, radius * 0.4) { return p }
            m += 4
        }
        return nil
    }

    static func distance(_ p: LatLng, _ w: Way) -> Double {
        Geo.project(p, on: w.points).offset
    }

    static func norm(_ a: Double) -> Double {
        (a.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
    }

    static func angleGap(_ a: Double, _ b: Double) -> Double {
        abs(signedGap(a, b))
    }

    /** From a to b the short way round, -180 to 180. */
    static func signedGap(_ a: Double, _ b: Double) -> Double {
        var d = norm(b - a)
        if d > 180 { d -= 360 }
        return d
    }
}
