import Foundation

/**
 * "Merge onto M5" only where you really have to merge. Where a slip road joins and its lane
 * carries on as a lane of the main road (a lane gain: the M5 at Almondsbury has 3 lanes, the slip
 * road from the M4 adds a 4th), there's nothing to merge into, and Waze says nothing; Kite told
 * Matteo to merge at places like that (13 November 2026). The road matcher knows the lanes of every
 * road meeting at the join: a lane gain is when the road out has as many lanes as the roads in
 * put together.
 */
public enum Merges {
    static let trace = URL(string: "https://valhalla1.openstreetmap.de/trace_attributes")!
    static let locate = URL(string: "https://valhalla1.openstreetmap.de/locate")!

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 15
        c.httpAdditionalHeaders = ["User-Agent": OSRM.userAgent]
        return URLSession(configuration: c)
    }()

    /** A join on the route: its place, the lanes of the route's road in and out, and the ways the other roads in come from. */
    struct Join {
        var at: LatLng
        var inLanes: Int
        var outLanes: Int
        /** Headings from the join back along each other road coming in. */
        var others: [Double]
    }

    /** [steps] with every merge that's really a lane gain made quiet; nil when there's none, or no answer. */
    public static func quietLaneGains(_ steps: [Maneuver], line: [LatLng]) async -> [Maneuver]? {
        let merges = steps.indices.filter { steps[$0].type == .merge && !steps[$0].silent }
        guard !merges.isEmpty, line.count >= 2 else { return nil }
        let cum = Geo.cumulative(line)
        var out = steps
        var changed = false
        for i in merges.prefix(12) {
            let a = steps[i].alongMeters
            // The router's merge can be a few hundred metres off where the roads actually join
            // (Almondsbury, 330 m on): the join nearest it, within 400 m either way.
            let from = max(0, a - 400), to = min(cum.last ?? 0, a + 400)
            let piece = Geo.slice(line, from: from, to: to, cumulative: cum)
            guard piece.count >= 2, let data = await post(trace, traceBody(Valhalla.thin(piece))) else { continue }
            let pc = Geo.cumulative(piece)
            guard let join = joins(data).filter({ !$0.others.isEmpty && Geo.project($0.at, on: piece, cumulative: pc).offset < 40 })
                    .min(by: { abs(Geo.project($0.at, on: piece, cumulative: pc).along - (a - from)) < abs(Geo.project($1.at, on: piece, cumulative: pc).along - (a - from)) }),
                  let joining = await othersLanes(join), joining.count == join.others.count,
                  join.outLanes >= join.inLanes + joining.reduce(0, +) else { continue }
            out[i].silent = true
            changed = true
        }
        return changed ? out : nil
    }

    static func traceBody(_ shape: [LatLng]) -> [String: Any] {
        [
            "shape": shape.map { ["lat": $0.lat, "lon": $0.lng] },
            "costing": "auto", "shape_match": "map_snap",
            "filters": ["attributes": ["edge.lane_count", "edge.begin_shape_index", "edge.end_shape_index", "shape",
                                       "node.intersecting_edge.begin_heading", "node.intersecting_edge.driveability"], "action": "include"],
        ]
    }

    private static func post(_ url: URL, _ body: Any) async -> Data? {
        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        guard let (data, resp) = try? await session.data(for: r), (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return data
    }

    /** Every point on the matched road where another road only comes in (one way, toward the route). */
    static func joins(_ data: Data) -> [Join] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let edges = root["edges"] as? [[String: Any]] else { return [] }
        let shape = (root["shape"] as? String).map { Geo.decodePolyline($0) } ?? []
        var out: [Join] = []
        for k in 0..<max(0, edges.count - 1) {
            let e = edges[k], next = edges[k + 1]
            guard let inLanes = (e["lane_count"] as? NSNumber)?.intValue, inLanes > 0,
                  let outLanes = (next["lane_count"] as? NSNumber)?.intValue, outLanes > 0,
                  let b = (e["end_shape_index"] as? NSNumber)?.intValue, shape.indices.contains(b),
                  let node = e["end_node"] as? [String: Any] else { continue }
            // "backward": traffic only comes toward the join along it.
            let others = ((node["intersecting_edges"] as? [[String: Any]]) ?? []).compactMap { x -> Double? in
                guard x["driveability"] as? String == "backward" else { return nil }
                return (x["begin_heading"] as? NSNumber)?.doubleValue
            }
            out.append(Join(at: shape[b], inLanes: inLanes, outLanes: outLanes, others: others))
        }
        return out
    }

    /** The lanes of each other road coming in, 40 m back along it; nil when the matcher doesn't answer. */
    static func othersLanes(_ join: Join) async -> [Int]? {
        let k = cos(join.at.lat * .pi / 180) * 111_320.0
        let points = join.others.map { h in
            LatLng(join.at.lat + 40 * cos(h * .pi / 180) / 110_540, join.at.lng + 40 * sin(h * .pi / 180) / k)
        }
        let body: [String: Any] = ["locations": points.map { ["lat": $0.lat, "lon": $0.lng] }, "costing": "auto", "verbose": true]
        guard let data = await post(locate, body),
              let results = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]], results.count == points.count else { return nil }
        var lanes: [Int] = []
        for (r, h) in zip(results, join.others) {
            // The road heading toward the join: the opposite of the way back along it.
            let toward = (h + 180).truncatingRemainder(dividingBy: 360)
            let found = ((r["edges"] as? [[String: Any]]) ?? []).compactMap { e -> (lanes: Int, gap: Double)? in
                guard let edge = e["edge"] as? [String: Any], let n = (edge["lane_count"] as? NSNumber)?.intValue, n > 0,
                      let heading = (e["heading"] as? NSNumber)?.doubleValue else { return nil }
                return (n, Roundabouts.angleGap(heading, toward))
            }.filter { $0.gap < 40 }.min { $0.gap < $1.gap }
            guard let found else { return nil }
            lanes.append(found.lanes)
        }
        return lanes
    }
}
