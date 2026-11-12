import Foundation
import VelaCore

/**
 * `swift run vela-probe turns [n]`: every step Kite would show and say on short drives through
 * tricky British junctions (gyratories, spiral and magic roundabouts, motorway interchanges,
 * mini-roundabouts, city one-way systems), checked against the route line itself: an arrow that
 * points the other way from the road, lanes that don't go the way of the turn, a roundabout whose
 * picture counts a different exit from the words, two turns too close together to hear apart, and
 * a sharp change of direction with no step at all.
 */
let turnDrives: [(String, LatLng, LatLng)] = [
        // Matteo's own trouble spots.
        ("Chestnut Avenue roundabout, Eastleigh", LatLng(50.9640, -1.3700), LatLng(50.9560, -1.3560)),
        ("M27 east to M3 north (J4)", LatLng(50.9370, -1.4400), LatLng(51.0100, -1.3350)),
        ("Normandy Way mini-roundabout, Marchwood", LatLng(50.8880, -1.4420), LatLng(50.8790, -1.4320)),
        ("Redbridge roundabout from the M271", LatLng(50.9450, -1.4650), LatLng(50.9200, -1.4300)),
        ("Hedge End Tesco roundabouts to M27 (J7)", LatLng(50.9100, -1.2950), LatLng(50.8900, -1.3300)),
        ("Bursledon to Southampton centre", LatLng(50.8900, -1.3050), LatLng(50.9050, -1.4040)),
        // Famous awkward junctions.
        ("Swindon Magic Roundabout", LatLng(51.5590, -1.7760), LatLng(51.5650, -1.7650)),
        ("Hemel Hempstead Magic Roundabout", LatLng(51.7470, -0.4790), LatLng(51.7560, -0.4640)),
        ("Hanger Lane gyratory to the A40 west", LatLng(51.5230, -0.2920), LatLng(51.5450, -0.3300)),
        ("Chiswick roundabout onto the M4", LatLng(51.4890, -0.2600), LatLng(51.4870, -0.3200)),
        ("Hyde Park Corner, Park Lane to Knightsbridge", LatLng(51.5080, -0.1540), LatLng(51.5010, -0.1640)),
        ("Elephant and Castle", LatLng(51.4990, -0.1050), LatLng(51.4890, -0.0920)),
        ("Old Street roundabout", LatLng(51.5280, -0.0820), LatLng(51.5230, -0.0920)),
        ("Spaghetti Junction, A38(M) to M6 north", LatLng(52.4950, -1.8800), LatLng(52.5400, -1.8400)),
        ("M6 to M1 at Catthorpe", LatLng(52.4300, -1.2700), LatLng(52.3900, -1.1700)),
        ("M4 to M5 south at Almondsbury", LatLng(51.5500, -2.5200), LatLng(51.5100, -2.6900)),
        ("M25 to M1 north (J21)", LatLng(51.7080, -0.3400), LatLng(51.7600, -0.4100)),
        ("M25 to M4 west (J15)", LatLng(51.4500, -0.5100), LatLng(51.4900, -0.6200)),
        ("M62 to M6 at Croft (J10)", LatLng(53.4300, -2.5300), LatLng(53.4100, -2.6100)),
        ("M8 Charing Cross, Glasgow", LatLng(55.8650, -4.2700), LatLng(55.8700, -4.2400)),
        ("Sheriffhall roundabout, Edinburgh bypass", LatLng(55.9000, -3.1300), LatLng(55.8800, -3.0800)),
        ("Bristol Temple Meads to Cabot Circus", LatLng(51.4490, -2.5810), LatLng(51.4590, -2.5840)),
        ("Mancunian Way to Piccadilly", LatLng(53.4700, -2.2500), LatLng(53.4770, -2.2310)),
        ("Leeds city loop", LatLng(53.7950, -1.5550), LatLng(53.8010, -1.5400)),
        ("Oxford ring road, Headington roundabout", LatLng(51.7600, -1.2100), LatLng(51.7400, -1.2200)),
        ("Cambridge Elizabeth Way roundabout", LatLng(52.2150, 0.1350), LatLng(52.2050, 0.1450)),
        ("Brighton Preston Circus to the seafront", LatLng(50.8350, -0.1390), LatLng(50.8200, -0.1400)),
        ("Plymouth Charles Cross roundabout", LatLng(50.3720, -4.1350), LatLng(50.3690, -4.1500)),
        ("Cardiff Gabalfa interchange", LatLng(51.5000, -3.2000), LatLng(51.5100, -3.2200)),
        ("Newcastle Swan House roundabout", LatLng(54.9740, -1.6070), LatLng(54.9690, -1.6020)),
        ("Reading IDR to the A33", LatLng(51.4580, -0.9800), LatLng(51.4400, -0.9800)),
        ("Portsmouth M275 into the city", LatLng(50.8350, -1.0850), LatLng(50.8000, -1.0880)),
        ("Winchester Spitfire Link and M3 J9", LatLng(51.0700, -1.2850), LatLng(51.0600, -1.3200)),
        ("Basingstoke ring road roundabouts", LatLng(51.2700, -1.1200), LatLng(51.2500, -1.0600)),
        ("Milton Keynes grid roads", LatLng(52.0400, -0.7600), LatLng(52.0250, -0.7200)),
        ("Country lanes, New Forest (Lyndhurst one-way)", LatLng(50.8700, -1.5600), LatLng(50.8650, -1.5900)),
        ("A3(M) to the A27 east, Havant", LatLng(50.8800, -1.0300), LatLng(50.8500, -0.9700)),
        ("Hindhead tunnel A3", LatLng(51.1300, -0.7100), LatLng(51.1000, -0.7500)),
        ("Silverstone area, A43 roundabouts", LatLng(52.1000, -1.0500), LatLng(52.0600, -1.0000)),
        ("Belfast Westlink", LatLng(54.6000, -5.9400), LatLng(54.5900, -5.9600)),
    ]

func turnAudit() async {
    let drives = turnDrives
    let only = CommandLine.arguments.count > 2 ? Int(CommandLine.arguments[2]) : nil
    var flags: [String: Int] = [:]
    var steps = 0
    for (n, d) in drives.enumerated() where only == nil || only == n {
        let google = (try? await GoogleClient.shared.directions(from: d.1, to: d.2, mode: .drive))?.first
        let r = await Directions.routes(from: d.1, to: d.2, mode: .drive)
        guard var x = r.routes.first else { print("##### \(n): \(d.0)\nFLAG no route: \(r.error ?? "")"); continue }
        // What the app does once driving: mini-roundabouts added, then each roundabout's real layout.
        if ProcessInfo.processInfo.environment["NO_OSM"] == nil {
            if let m = await Roundabouts.addMinis(to: x) { x.maneuvers = m.maneuvers }
            if let a = await Roundabouts.apply(to: x) { x = a }
        }
        print(String(format: "##### %d: %@  (%.1f km, %@, %d steps)", n, d.0, x.distanceMeters / 1000, x.source, x.maneuvers.count))
        let line = x.polyline, cum = Geo.cumulative(line), total = cum.last ?? 0
        func heading(_ a: Double, _ b: Double) -> Double? {
            let p = Geo.point(along: line, meters: max(0, a), cumulative: cum), q = Geo.point(along: line, meters: min(total, b), cumulative: cum)
            return p.distance(to: q) > 3 ? Geo.bearing(p, q) : nil
        }
        func turn(at m: Double, before: Double = 40, after: Double = 60) -> Double? {
            guard let a = heading(m - before, m - 3), let b = heading(m + 5, m + after) else { return nil }
            return (b - a + 540).truncatingRemainder(dividingBy: 360) - 180
        }
        func flag(_ kind: String, _ text: String) {
            flags[kind, default: 0] += 1
            print("    FLAG [\(kind)] \(text)")
        }
        let spoken = x.maneuvers.filter { !$0.silent }
        for (k, m) in spoken.enumerated() {
            steps += 1
            let lane = LaneWords.lanes(for: m)
            var extra: [String] = []
            if let e = m.exit { extra.append("exit \(e)") }
            if let s = m.roundabout { extra.append("pic \(s.taken + 1)/\(s.exits.count)\(s.mini ? " mini" : "")\(s.complete ? "" : " sketch")") }
            if let l = lane { extra.append("lanes " + l.lanes.map { ($0.valid ? "*" : "") + $0.indications.joined(separator: "/") }.joined(separator: "|") + " '\(l.words)'") }
            if let h = m.laneHint { extra.append("hint '\(h)'") }
            let t = turn(at: m.alongMeters)
            print(String(format: "  %6.0f m  %-11@ %@  turn=%@  %@  %.5f,%.5f", m.alongMeters, m.type.rawValue, m.instruction,
                         t.map { String(format: "%+.0f", $0) } ?? "?", extra.joined(separator: ", "), m.location.lat, m.location.lng))
            // Where the road really bends near the step: the step placed off the junction is said at the wrong moment.
            if ![.depart, .arrive, .roundabout].contains(m.type) {
                var best: (at: Double, t: Double)?
                // Only up to halfway to the steps either side, so their bends aren't counted.
                let prevAt = k > 0 ? spoken[k - 1].alongMeters : -1e9, nextAt = k + 1 < spoken.count ? spoken[k + 1].alongMeters : 1e9
                let lo = -min(80, (m.alongMeters - prevAt) / 2), hi = min(80, (nextAt - m.alongMeters) / 2)
                for o in stride(from: lo, through: hi, by: 5) {
                    if let tt = turn(at: m.alongMeters + o, before: 25, after: 25), abs(tt) > abs(best?.t ?? 0) { best = (o, tt) }
                }
                if let b = best, abs(b.t) > 45, abs(b.at) > 20 { flag("placement", String(format: "road bends %+.0f at %+.0f m from the step", b.t, b.at)) }
            }
            // Arrow against the road.
            if let t, m.type != .roundabout, m.type != .uturn {
                let left: [ManeuverType] = [.turnLeft, .sharpLeft, .slightLeft, .keepLeft, .forkLeft, .rampLeft]
                let right: [ManeuverType] = [.turnRight, .sharpRight, .slightRight, .keepRight, .forkRight, .rampRight]
                if [.turnLeft, .sharpLeft].contains(m.type) && t > -25 { flag("arrow", "left turn but the line goes \(Int(t))") }
                if [.turnRight, .sharpRight].contains(m.type) && t < 25 { flag("arrow", "right turn but the line goes \(Int(t))") }
                if left.contains(m.type) && t > 25 { flag("arrow", "left arrow, line turns right \(Int(t))") }
                if right.contains(m.type) && t < -25 { flag("arrow", "right arrow, line turns left \(Int(t))") }
                if m.type == .straight && abs(t) > 50 { flag("arrow", "straight arrow but the line turns \(Int(t))") }
                if [.slightLeft, .slightRight].contains(m.type) && abs(t) > 75 { flag("arrow", "slight arrow for a full turn \(Int(t))") }
                if [.turnLeft, .turnRight].contains(m.type) && abs(t) < 35 && !m.instruction.contains("roundabout") { flag("arrow", "full turn arrow for a bend of \(Int(t))") }
            }
            // Words against the arrow.
            let w = m.instruction.lowercased()
            if w.contains("left") && !w.contains("right") && [.turnRight, .slightRight, .sharpRight, .keepRight, .forkRight, .rampRight].contains(m.type) { flag("words", "says left, arrow right") }
            if w.contains("right") && !w.contains("left") && [.turnLeft, .slightLeft, .sharpLeft, .keepLeft, .forkLeft, .rampLeft].contains(m.type) { flag("words", "says right, arrow left") }
            if let r = m.road, k > 0, let prev = spoken[..<k].last(where: { $0.road != nil })?.road, r == prev, w.contains("onto \(r.lowercased())"), m.type != .roundabout { flag("words", "onto the road it's already on") }
            if w.range(of: #"\b(\w+) \1\b"#, options: .regularExpression) != nil { flag("words", "repeated word") }
            // Lanes against the turn.
            if let l = lane, m.type != .roundabout {
                let goesLeft = [.turnLeft, .sharpLeft, .slightLeft, .keepLeft, .forkLeft, .rampLeft].contains(m.type)
                let goesRight = [.turnRight, .sharpRight, .slightRight, .keepRight, .forkRight, .rampRight].contains(m.type)
                let valid = l.lanes.enumerated().filter(\.element.valid)
                if goesLeft, let first = valid.first?.offset, first == l.lanes.count - 1, l.lanes.count > 1,
                   !valid.contains(where: { $0.element.indications.contains { $0.contains("left") } }) { flag("lanes", "left turn from the right lane only") }
                if goesRight, let last = valid.last?.offset, last == 0, l.lanes.count > 1,
                   !valid.contains(where: { $0.element.indications.contains { $0.contains("right") } }) { flag("lanes", "right turn from the left lane only") }
                if valid.contains(where: { $0.element.indications.allSatisfy { i in (goesLeft && i.contains("right")) || (goesRight && i.contains("left")) } }) { flag("lanes", "a picked lane only points the other way") }
            }
            // Roundabouts: picture, words and lanes.
            if m.type == .roundabout && !m.leavesRoundabout {
                if let s = m.roundabout, let e = m.exit, s.taken + 1 != e { flag("roundabout", "says exit \(e), picture shows \(s.taken + 1)") }
                if m.exit == nil && !w.contains("straight") && !w.contains("turn") { flag("roundabout", "no exit number") }
                if let s = m.roundabout, s.exits.indices.contains(s.taken) {
                    let tt = s.turn(s.taken)
                    if let e = m.exit, e == 1, tt > 200 { flag("roundabout", "1st exit drawn going right round (\(Int(tt)))") }
                    if let e = m.exit, e >= 3, tt < 60 { flag("roundabout", "exit \(e) drawn as a sharp left (\(Int(tt)))") }
                    if s.mini, lane != nil { flag("roundabout", "lane advice at a mini-roundabout") }
                }
                // Real way out from the line, against the count.
                if let s = m.roundabout, s.exits.indices.contains(s.taken), let out = Roundabouts.exitAngle(line, cumulative: cum, along: m.alongMeters, leaving: Roundabouts.wayOff(x.maneuvers, x.maneuvers.firstIndex(of: m) ?? 0)) {
                    let drawn = s.exits[s.taken]
                    if Roundabouts.angleGap(drawn, out) > 60 { flag("roundabout", "picture's exit at \(Int(drawn)), road leaves at \(Int(out))") }
                }
            }
            // Two turns too close to say apart.
            if k + 1 < spoken.count {
                let nx = spoken[k + 1]
                let gap = nx.alongMeters - m.alongMeters
                if gap < 60, nx.type != .arrive, !nx.leavesRoundabout, m.type != .depart {
                    flag("close", String(format: "next step '%@' only %.0f m on", nx.instruction, gap))
                }
            }
        }
        // Google's own steps on the same line that Kite has nothing for, and Google's words where Kite's arrow disagrees.
        if let g = google, g.stepsComplete, abs(g.distanceMeters - x.distanceMeters) < 50 {
            for gm in g.maneuvers where ![.depart, .arrive].contains(gm.type) {
                let near = spoken.filter { abs($0.alongMeters - gm.alongMeters) < 70 }
                if near.isEmpty { flag("google only", String(format: "%.0f m '%@' (%@)", gm.alongMeters, gm.instruction, gm.type.rawValue)) }
            }
            for m in spoken where ![.depart, .arrive, .roundabout].contains(m.type) {
                guard let gm = g.maneuvers.filter({ abs($0.alongMeters - m.alongMeters) < 50 }).min(by: { abs($0.alongMeters - m.alongMeters) < abs($1.alongMeters - m.alongMeters) }) else { continue }
                func side(_ t: ManeuverType) -> Int {
                    if [.turnLeft, .sharpLeft, .slightLeft, .keepLeft, .forkLeft, .rampLeft].contains(t) { return -1 }
                    if [.turnRight, .sharpRight, .slightRight, .keepRight, .forkRight, .rampRight].contains(t) { return 1 }
                    return 0
                }
                if side(gm.type) != 0, side(m.type) != 0, side(gm.type) != side(m.type) { flag("vs google", "Kite '\(m.instruction)' (\(m.type)), Google '\(gm.instruction)' (\(gm.type))") }
                if side(gm.type) != 0, side(m.type) == 0, m.type != .merge { flag("vs google", "Kite '\(m.instruction)' (\(m.type)), Google '\(gm.instruction)' (\(gm.type))") }
            }
        }
        // A sharp change of way with no step near it (not on a roundabout's ring).
        let rbs = x.maneuvers.filter { $0.type == .roundabout }
        var s = 60.0
        var lastFlag = -1000.0
        while s < total - 60 {
            if let t = turn(at: s, before: 30, after: 30), abs(t) > 55, s - lastFlag > 150,
               !x.maneuvers.contains(where: { abs($0.alongMeters - s) < 90 && ($0.type != .depart || s < 150) }),
               !rbs.contains(where: { s > $0.alongMeters - 40 && s < $0.alongMeters + 250 }) {
                let p = Geo.point(along: line, meters: s, cumulative: cum)
                flag("silent turn", String(format: "line turns %+.0f at %.0f m with no step (%.5f,%.5f)", t, s, p.lat, p.lng))
                lastFlag = s
            }
            s += 10
        }
        try? await Task.sleep(nanoseconds: 1_500_000_000)
    }
    print("SUMMARY \(steps) steps; flags: " + flags.sorted { $0.key < $1.key }.map { "\($0.key) \($0.value)" }.joined(separator: ", "))
}

/** The banner's lane logic (LaneGuide in the app), mirrored here so the probe sees the same lanes. */
enum LaneWords {
    static func lanes(for m: Maneuver) -> (lanes: [Lane], words: String)? {
        let left = Geo.drivesOnLeft(m.location)
        if m.lanes.count > 1 { return named(m.lanes) }
        if let hint = m.laneHint, !hint.localizedCaseInsensitiveContains("any lane"), m.type != .roundabout {
            return ([], hint)
        }
        guard m.type == .roundabout, !m.leavesRoundabout, let lanes = m.roundabout?.ruleLanes(leftTraffic: left, hint: m.laneHint) else { return nil }
        return named(lanes)
    }

    static func named(_ lanes: [Lane]) -> (lanes: [Lane], words: String)? {
        let valid = lanes.enumerated().filter(\.element.valid).map(\.offset)
        guard let first = valid.first, let last = valid.last, valid.count < lanes.count else { return nil }
        let n = lanes.count, count = valid.count, many = count > 1 ? "\(count) lanes" : "lane"
        if first == 0 { return (lanes, "Left \(many)") }
        if last == n - 1 { return (lanes, "Right \(many)") }
        if count == 1 && n > 3 { return (lanes, "Lane \(first + 1) from left") }
        return (lanes, count > 1 ? "Middle lanes" : "Middle lane")
    }
}

/** `swift run vela-probe steps lat lng lat lng`: Google's own steps, the open router's along Google's line, and Kite's. */
func stepsCompare() async {
    let a = CommandLine.arguments.dropFirst(2).compactMap(Double.init)
    guard a.count == 4 else { print("usage: steps lat lng lat lng"); return }
    let from = LatLng(a[0], a[1]), to = LatLng(a[2], a[3])
    func row(_ m: Maneuver) -> String {
        var extra: [String] = []
        if let e = m.exit { extra.append("exit \(e)") }
        if let h = m.laneHint { extra.append("hint '\(h)'") }
        if m.silent { extra.append("silent") }
        if let r = m.road { extra.append("road '\(r)'") }
        if !m.lanes.isEmpty { extra.append("lanes " + m.lanes.map { ($0.valid ? "*" : "") + $0.indications.joined(separator: "/") }.joined(separator: "|")) }
        return String(format: "  %6.0f m  %-11@ %@  [%@]  %.5f,%.5f", m.alongMeters, m.type.rawValue, m.instruction, extra.joined(separator: ", "), m.location.lat, m.location.lng)
    }
    guard let g = (try? await GoogleClient.shared.directions(from: from, to: to, mode: .drive))?.first else { print("no google"); return }
    print("--- Google (\(Int(g.distanceMeters)) m, complete \(g.stepsComplete))"); g.maneuvers.forEach { print(row($0)) }
    if let v = try? await OSRM.routeVia(g.polyline, mode: .drive) { print("--- open router along Google's line (\(Int(v.distanceMeters)) m)"); v.maneuvers.forEach { print(row($0)) } }
    if let k = await Directions.routes(from: from, to: to, mode: .drive).routes.first { print("--- Kite (\(k.source))"); k.maneuvers.forEach { print(row($0)) } }
}

/** `vela-probe adoptcheck`: how often the open router's steps can be put on Google's line, by method. */
func adoptCheck() async {
    let drives = turnDrives
    var ok: [String: Int] = [:]
    for (n, d) in drives.enumerated() {
        guard let g = (try? await GoogleClient.shared.directions(from: d.1, to: d.2, mode: .drive))?.first else { continue }
        let line = g.polyline, cum = Geo.cumulative(line)
        func check(_ r: Route?) -> String {
            guard let r else { return "none" }
            let a = StepMerge.follows(r.polyline, line, cumulative: cum), b = StepMerge.follows(line, r.polyline, cumulative: Geo.cumulative(r.polyline))
            return a && b ? "OK" : "strays"
        }
        var row = "\(n) \(d.0):"
        for (name, f) in [("via1500", { try? await OSRM.routeVia(line, mode: .drive) }),
                          ("via500", { try? await OSRM.routeVia(line, mode: .drive, spacing: 500, maxVias: 60) }),
                          ("via250", { try? await OSRM.routeVia(line, mode: .drive, spacing: 250, maxVias: 90) }),
                          ("match", { try? await OSRM.match(line, mode: .drive) })] as [(String, () async -> Route??)] {
            let r = await f()
            let c = check(r ?? nil)
            if c == "OK" { ok[name, default: 0] += 1 }
            row += " \(name)=\(c)"
            try? await Task.sleep(nanoseconds: 700_000_000)
        }
        print(row)
    }
    print("OK counts of \(drives.count): \(ok)")
}
