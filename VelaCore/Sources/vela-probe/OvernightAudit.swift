import Foundation
import VelaCore

/**
 * `swift run vela-probe roundabouts [n]`: drives across Hampshire, Wales, London and country lanes,
 * every roundabout checked the way the banner draws it (the picture's exit against the way the
 * route really leaves, the number against the picture), and every stretch without a speed limit
 * listed with its OpenStreetMap road. Lines starting "FLAG" are worth a look.
 */
func overnightAudit() async {
    let drives: [(String, LatLng, LatLng)] = [
        ("Park Gate to Southampton Uni", LatLng(50.8705, -1.2690), LatLng(50.9346, -1.3966)),
        ("Totton to Southampton Uni", LatLng(50.9190, -1.4900), LatLng(50.9346, -1.3966)),
        ("Eastleigh to Marchwood", LatLng(50.9677, -1.3500), LatLng(50.8957, -1.4469)),
        ("Fareham to Whiteley", LatLng(50.8520, -1.1790), LatLng(50.8850, -1.2440)),
        ("Hedge End to Chandler's Ford", LatLng(50.9120, -1.3000), LatLng(50.9840, -1.3830)),
        ("Romsey to Winchester", LatLng(50.9890, -1.4970), LatLng(51.0630, -1.3080)),
        ("Basingstoke ring", LatLng(51.2660, -1.0870), LatLng(51.2480, -1.1300)),
        ("Portsmouth to Havant", LatLng(50.8000, -1.0900), LatLng(50.8560, -0.9830)),
        ("Lymington to Lyndhurst (New Forest)", LatLng(50.7580, -1.5450), LatLng(50.8720, -1.5750)),
        ("Bishop's Waltham lanes to Droxford", LatLng(50.9550, -1.2120), LatLng(50.9620, -1.1370)),
        ("M27 J3 to M3 J9 Winchester", LatLng(50.9420, -1.4380), LatLng(51.0700, -1.2850)),
        ("Cardiff Bay to Penarth (Wales)", LatLng(51.4630, -3.1650), LatLng(51.4380, -3.1740)),
        ("Swansea to Mumbles (Wales)", LatLng(51.6200, -3.9400), LatLng(51.5700, -3.9950)),
        ("Brecon lanes (Wales)", LatLng(51.9470, -3.3900), LatLng(51.9000, -3.2500)),
        ("Camden to Greenwich (London)", LatLng(51.5390, -0.1430), LatLng(51.4780, -0.0010)),
        ("Croydon to Wimbledon (London)", LatLng(51.3720, -0.1000), LatLng(51.4210, -0.2060)),
        ("Milton Keynes roundabouts", LatLng(52.0400, -0.7590), LatLng(52.0200, -0.7000)),
    ]
    let only = CommandLine.arguments.count > 2 ? Int(CommandLine.arguments[2]) : nil
    var flags = 0, roundabouts = 0
    var unknownWays: Set<Int64> = []
    for (n, d) in drives.enumerated() where only == nil || only == n {
        print("##### \(n): \(d.0)")
        let r = await Directions.routes(from: d.1, to: d.2, mode: .drive)
        guard var x = r.routes.first else { print("FLAG no route: \(r.error ?? "")"); continue }
        if let m = await Roundabouts.addMinis(to: x) { x = m }
        if let a = await Roundabouts.apply(to: x) { x = a }
        let cum = Geo.cumulative(x.polyline)
        print(String(format: "  %.1f km, source %@, %d steps", x.distanceMeters / 1000, x.source, x.maneuvers.count))
        for m in x.maneuvers where m.type == .roundabout && !m.leavesRoundabout {
            roundabouts += 1
            let line = Roundabouts.exitAngle(x.polyline, cumulative: cum, along: m.alongMeters)
            var notes: [String] = []
            if let s = m.roundabout {
                let drawn = s.exits[s.taken]
                if let line, Roundabouts.angleGap(drawn, line) > 45 { notes.append(String(format: "picture exit %.0f but route leaves at %.0f", drawn, line)) }
                if s.complete, let e = m.exit, s.taken + 1 != e { notes.append("number \(e) but picture shows exit \(s.taken + 1)") }
            } else { notes.append("no picture") }
            if m.exit == nil { notes.append("no exit number") }
            let shape = m.roundabout.map { s in "[\(s.exits.map { String(Int($0)) }.joined(separator: ","))] took \(s.taken + 1)\(s.complete ? " osm" : "")\(s.mini ? " mini" : "")" } ?? "-"
            let lanes = m.lanes.isEmpty ? "" : " lanes " + m.lanes.map { ($0.valid ? "*" : "") + $0.indications.joined(separator: "/") }.joined(separator: "|")
            print(String(format: "%@ %6.0f m  %@  exit=%@ line=%@ pic=%@%@  %.5f,%.5f%@", notes.isEmpty ? "  ok  " : "FLAG", m.alongMeters, m.instruction,
                         m.exit.map(String.init) ?? "-", line.map { String(Int($0)) } ?? "-", shape, lanes, m.location.lat, m.location.lng,
                         notes.isEmpty ? "" : "  <- " + notes.joined(separator: "; ")))
            if !notes.isEmpty { flags += 1 }
        }
        if let v = await Valhalla.speedLimits(along: x.polyline) {
            var known = 0.0
            for (i, l) in v.limits.enumerated() {
                let end = i + 1 < v.limits.count ? v.limits[i + 1].startMeters : x.distanceMeters
                if l.kmh != nil { known += end - l.startMeters }
                else if end - l.startMeters > 200 {
                    let p = Geo.point(along: x.polyline, meters: l.startMeters + 20, cumulative: cum)
                    print(String(format: "LIMIT none for %4.0f m from %6.0f m  %.5f,%.5f", end - l.startMeters, l.startMeters, p.lat, p.lng))
                }
                if let k = l.kmh, ![20.0, 30, 40, 50, 60, 70, 10, 5, 15].contains((k / 1.609344).rounded()) || abs(k / 1.609344 - (k / 1.609344).rounded()) > 0.3 {
                    print(String(format: "LIMIT odd %.1f mph from %6.0f m", k / 1.609344, l.startMeters))
                }
            }
            for w in v.ways where w.kmh == nil { unknownWays.insert(w.id) }
            print(String(format: "  limits known on %.0f%% of the drive", 100 * known / max(1, x.distanceMeters)))
        } else { print("FLAG limits: no answer") }
        try? await Task.sleep(nanoseconds: 1_500_000_000)
    }
    print("SUMMARY \(roundabouts) roundabouts, \(flags) flagged")
    try? unknownWays.map(String.init).joined(separator: "\n").write(toFile: "unknown-ways.txt", atomically: true, encoding: .utf8)
}
