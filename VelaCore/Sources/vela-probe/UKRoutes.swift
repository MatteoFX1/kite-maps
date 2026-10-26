import Foundation
import VelaCore

/** `swift run vela-probe uk`: real British drives through the app's own route code, every step printed. */
func ukRoutes() async {
    let drives: [(String, LatLng, LatLng)] = [
        ("M271 into Redbridge roundabout, 1st exit A33", LatLng(50.9890, -1.4970), LatLng(50.9077, -1.4136)),
        ("Park Gate to Portsmouth Uni", LatLng(50.8705, -1.2690), LatLng(50.7953, -1.0938)),
        ("Park Gate to Southampton Uni", LatLng(50.8705, -1.2690), LatLng(50.9346, -1.3966)),
        ("Fareham to Winchester", LatLng(50.8527, -1.1773), LatLng(51.0632, -1.3080)),
        ("Central London: Kings Cross to Waterloo", LatLng(51.5308, -0.1238), LatLng(51.5031, -0.1132)),
        ("Cardiff centre to Penarth", LatLng(51.4816, -3.1791), LatLng(51.4386, -3.1737)),
        ("Glasgow to Edinburgh", LatLng(55.8609, -4.2514), LatLng(55.9533, -3.1883)),
    ]
    for (name, a, b) in drives {
        let r = await Directions.routes(from: a, to: b, mode: .drive)
        guard let route = r.routes.first else { print("=== \(name): NO ROUTE (\(r.error ?? "-"))"); continue }
        print("=== \(name): \(Int(route.distanceMeters)) m, \(Int(route.durationSeconds / 60)) min, \(route.maneuvers.count) steps, via \(route.summary ?? "-")")
        for m in route.maneuvers {
            var extra: [String] = []
            if let e = m.exit { extra.append("exit \(e)") }
            if m.leavesRoundabout { extra.append("leaves") }
            if let road = m.road { extra.append("road '\(road)'") }
            if !m.lanes.isEmpty { extra.append("lanes " + m.lanes.map { ($0.valid ? "*" : "") + $0.indications.joined(separator: "/") }.joined(separator: " | ")) }
            print(String(format: "  %6.0f m  %-12@ %@  [%@]", m.alongMeters, m.type.rawValue as NSString, m.instruction as NSString, extra.joined(separator: ", ") as NSString))
        }
        try? await Task.sleep(nanoseconds: 1_500_000_000)
    }
    // A reroute while driving north up The Avenue (A33) in Southampton, to somewhere behind the car:
    // the new route must carry on north and turn round properly, never start with "head south".
    let here = LatLng(50.9260, -1.4035), behind = LatLng(50.9050, -1.4045)
    for heading in [0.0, 180.0] {
        let r = await Directions.routes(from: here, to: behind, mode: .drive, heading: heading)
        guard let route = r.routes.first else { print("=== Reroute heading \(Int(heading)): NO ROUTE"); continue }
        let bad = Directions.startsBehind(route.polyline, heading: heading)
        print("=== Reroute heading \(Int(heading)): \(Int(route.distanceMeters)) m, source \(route.source)" +
              (bad ? "  FAIL: starts back the way the car came" : "  OK: sets off the way the car faces"))
        for m in route.maneuvers.prefix(4) { print("    \(m.instruction)") }
        try? await Task.sleep(nanoseconds: 1_500_000_000)
    }
}
