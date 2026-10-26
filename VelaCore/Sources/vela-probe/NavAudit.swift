import Foundation
import VelaCore

/**
 * `swift run vela-probe audit`: every stage of turn-by-turn on Matteo's real Southampton drives,
 * side by side: Google's own steps, the open router's steps forced along Google's line, and what
 * the app ends up saying. For finding where wrong instructions come from.
 */
func navAudit() async {
    let drives: [(String, LatLng, LatLng)] = [
        ("M271 south into Redbridge, A33 east", LatLng(50.9890, -1.4970), LatLng(50.9077, -1.4136)),
        ("Millbrook up the A33 past Redbridge to Romsey", LatLng(50.9180, -1.4550), LatLng(50.9890, -1.4970)),
        ("Totton to Southampton Uni", LatLng(50.9190, -1.4900), LatLng(50.9346, -1.3966)),
        ("Park Gate to Southampton Uni", LatLng(50.8705, -1.2690), LatLng(50.9346, -1.3966)),
    ]
    func row(_ m: Maneuver) -> String {
        var extra: [String] = []
        if let e = m.exit { extra.append("exit \(e)") }
        if let h = m.laneHint { extra.append("hint '\(h)'") }
        if m.silent { extra.append("silent") }
        if !m.lanes.isEmpty { extra.append("lanes " + m.lanes.map { ($0.valid ? "*" : "") + $0.indications.joined(separator: "/") }.joined(separator: " | ")) }
        return String(format: "  %6.0f m  %-11@ %@  %@", m.alongMeters, m.type.rawValue as NSString,
                      m.instruction as NSString, (extra.isEmpty ? "" : "[" + extra.joined(separator: ", ") + "]") as NSString)
    }
    for (name, a, b) in drives {
        print("##### \(name)")
        let g = (try? await GoogleClient.shared.directions(from: a, to: b, mode: .drive))?.first
        if let g {
            print("--- Google's own steps (\(Int(g.distanceMeters)) m, complete \(g.stepsComplete)):")
            g.maneuvers.forEach { print(row($0)) }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            do {
                if let mt = try await OSRM.match(g.polyline, mode: .drive) {
                    print("--- Open router matched to Google's line (\(Int(mt.distanceMeters)) m):")
                    mt.maneuvers.forEach { print(row($0)) }
                } else {
                    var why = "?"
                    if let u = OSRM.matchURL(g.polyline, mode: .drive), let (d, _) = try? await URLSession.shared.data(from: u) {
                        why = String(decoding: d.prefix(300), as: UTF8.self)
                    }
                    print("--- Open router matched to Google's line: NO SINGLE MATCH: \(why)")
                }
            } catch { print("--- Open router match FAILED: \(error)") }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            if let v = try? await OSRM.routeVia(g.polyline, mode: .drive) {
                print("--- Open router forced along Google's line (\(Int(v.distanceMeters)) m):")
                v.maneuvers.forEach { print(row($0)) }
            } else { print("--- Open router along Google's line: FAILED") }
        } else { print("--- Google: no route") }
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        if let o = try? await OSRM.route(from: a, to: b, mode: .drive).first {
            print("--- Open router on its own (\(Int(o.distanceMeters)) m):")
            o.maneuvers.forEach { print(row($0)) }
        }
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        if let f = await Directions.routes(from: a, to: b, mode: .drive).routes.first {
            print("--- What Kite says (source \(f.source)):")
            f.maneuvers.forEach { print(row($0)) }
        }
        try? await Task.sleep(nanoseconds: 1_500_000_000)
    }
}
