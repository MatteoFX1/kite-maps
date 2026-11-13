import Foundation

/**
 * Turn-by-turn that doesn't leave anything out. Kite's steps come from the open router run along
 * Google's line, and only when the two run on the very same roads all the way; on a November 2026
 * check of 40 tricky British drives that held for only half of them, and the rest fell back on
 * Google's plainer steps (no mapped lanes, "Take the exit" with no destination). Even when it held,
 * the open router left out turns Google gives ("Keep right to stay on A38(M)" at Spaghetti
 * Junction, the second of the Six Ways roundabouts in Erdington).
 */
/**
 * A line's segments filed by 100 m squares, so "how far is this point from the line" only looks at
 * the few segments nearby instead of every one. Only answers for points within [reach] (≤ 60 m) of
 * the line; further off it says "nowhere near" (offset infinity). The same maths as Geo.project.
 */
struct NearLine {
    let line: [LatLng]
    let cum: [Double]
    private let lat0: Double, k0: Double
    private var cells: [Int64: [Int]] = [:]
    private static let cell = 100.0

    init(_ line: [LatLng], cumulative: [Double]? = nil) {
        self.line = line
        cum = cumulative ?? Geo.cumulative(line)
        lat0 = line.first?.lat ?? 0
        k0 = cos(lat0 * .pi / 180) * 111_320.0
        guard line.count >= 2 else { return }
        for i in 0..<(line.count - 1) {
            let (ax, ay) = xy(line[i]), (bx, by) = xy(line[i + 1])
            for cx in Int(floor(min(ax, bx) / Self.cell))...Int(floor(max(ax, bx) / Self.cell)) {
                for cy in Int(floor(min(ay, by) / Self.cell))...Int(floor(max(ay, by) / Self.cell)) {
                    cells[Self.key(cx, cy), default: []].append(i)
                }
            }
        }
    }

    private func xy(_ p: LatLng) -> (Double, Double) { ((p.lng - line[0].lng) * k0, (p.lat - lat0) * 110_540) }
    private static func key(_ x: Int, _ y: Int) -> Int64 { (Int64(x) << 32) ^ Int64(UInt32(bitPattern: Int32(truncatingIfNeeded: y))) }

    /** Like Geo.project, for a point near the line; (0, infinity) when nothing is within about 60 m. */
    func project(_ p: LatLng) -> (along: Double, offset: Double) {
        guard line.count >= 2 else { return Geo.project(p, on: line, cumulative: cum) }
        let (px, py) = xy(p)
        let cx = Int(floor(px / Self.cell)), cy = Int(floor(py / Self.cell))
        let k = cos(p.lat * .pi / 180) * 111_320.0
        var best = (along: 0.0, offset: Double.infinity)
        var seen = Set<Int>()
        for x in (cx - 1)...(cx + 1) {
            for y in (cy - 1)...(cy + 1) {
                for i in cells[Self.key(x, y)] ?? [] where seen.insert(i).inserted {
                    let a = line[i], b = line[i + 1]
                    let ax = (a.lng - p.lng) * k, ay = (a.lat - p.lat) * 110_540
                    let bx = (b.lng - p.lng) * k, by = (b.lat - p.lat) * 110_540
                    let dx = bx - ax, dy = by - ay
                    let len2 = dx * dx + dy * dy
                    let t = len2 > 0 ? min(max(-(ax * dx + ay * dy) / len2, 0), 1) : 0
                    let ex = ax + t * dx, ey = ay + t * dy
                    let d = (ex * ex + ey * ey).squareRoot()
                    if d < best.offset { best = (cum[i] + t * (cum[i + 1] - cum[i]), d) }
                }
            }
        }
        return best
    }
}

extension StepMerge {
    static let turnKinds: Set<ManeuverType> = [.turnLeft, .turnRight, .slightLeft, .slightRight, .sharpLeft, .sharpRight, .uturn,
                                               .keepLeft, .keepRight, .forkLeft, .forkRight, .rampLeft, .rampRight, .roundabout]

    /** -1 for a step to the left, 1 to the right, 0 for neither. */
    static func side(_ t: ManeuverType) -> Int {
        if [.turnLeft, .sharpLeft, .slightLeft, .keepLeft, .forkLeft, .rampLeft].contains(t) { return -1 }
        if [.turnRight, .sharpRight, .slightRight, .keepRight, .forkRight, .rampRight].contains(t) { return 1 }
        return 0
    }

    /** The same kind of step: both roundabouts, or both off to the same side. */
    static func alike(_ a: Maneuver, _ b: Maneuver) -> Bool {
        if [a.type, b.type].contains(.roundabout) || [a.type, b.type].contains(.merge) { return a.type == b.type }
        return side(a.type) != 0 && side(a.type) == side(b.type)
    }

    /**
     * When no open-router route runs on Google's roads all the way: its steps wherever it does,
     * and Google's own steps on the stretches where it went its own way (150 m either side of
     * them, so a turn into or out of such a stretch comes from one or the other, never both).
     * Nil when no route follows at least half the line.
     */
    public static func adoptPiecewise(_ candidates: [Route], google: [Maneuver], onto line: [LatLng]) -> [Maneuver]? {
        guard line.count >= 2, !google.isEmpty else { return nil }
        let cum = Geo.cumulative(line)
        let total = cum.last ?? 0
        guard total > 0 else { return nil }
        var best: (steps: [Maneuver], cover: Double)?
        // Looked up by nearby squares, not segment by segment: a 550 km drive's line is checked every
        // 25 m, and comparing each check with every segment of the other line took minutes ("Finding
        // routes…" for good on Southampton to Newcastle when the open router was slow at night).
        let near = NearLine(line, cumulative: cum)
        for o in candidates where o.polyline.count >= 2 {
            let oc = Geo.cumulative(o.polyline)
            let onO = NearLine(o.polyline, cumulative: oc)
            var bad: [ClosedRange<Double>] = []
            var m = 0.0
            while m <= total {
                if onO.project(Geo.point(along: line, meters: m, cumulative: cum)).offset > 30 {
                    let r = max(0, m - 150)...min(total, m + 150)
                    if let last = bad.last, last.upperBound >= r.lowerBound { bad[bad.count - 1] = last.lowerBound...r.upperBound } else { bad.append(r) }
                }
                m += 25
            }
            let cover = 1 - bad.reduce(0) { $0 + $1.upperBound - $1.lowerBound } / total
            guard cover >= 0.5, cover > (best?.cover ?? 0) else { continue }
            func onBad(_ a: Double) -> Bool { bad.contains { $0.contains(a) } }
            var placed: [Maneuver] = []
            var last = 0.0
            for s in o.maneuvers where s.type != .depart && s.type != .arrive {
                let p = near.project(s.location)
                guard p.offset < 40, p.along >= last - 30, p.along < total - 5, !onBad(p.along) else { continue }
                // The open route around the step runs on the line too, not just the step's own point.
                let oa = Geo.project(s.location, on: o.polyline, cumulative: oc).along
                let close = stride(from: max(0, oa - 150), through: min(oc.last ?? 0, oa + 150), by: 25).allSatisfy {
                    near.project(Geo.point(along: o.polyline, meters: $0, cumulative: oc)).offset <= 30
                }
                guard close else { continue }
                last = max(last, p.along)
                var s2 = s
                s2.alongMeters = p.along
                s2.location = Geo.point(along: line, meters: p.along, cumulative: cum)
                placed.append(s2)
            }
            for g in google.dropFirst() where g.type != .depart && g.type != .arrive && onBad(g.alongMeters) {
                // Not where the open router already has the same step just outside the stretch.
                if placed.contains(where: { alike($0, g) && abs($0.alongMeters - g.alongMeters) < 80 }) { continue }
                placed.append(g)
            }
            placed.sort { $0.alongMeters < $1.alongMeters }
            // Google's first step is its "Head south-west", whatever kind it's marked.
            let depart = onBad(0) ? google.first.map { g -> Maneuver in var d = g; d.type = .depart; return d } : o.maneuvers.first { $0.type == .depart }
            let arrive = onBad(total) ? google.last { $0.type == .arrive } : o.maneuvers.last { $0.type == .arrive }
            best = (finish(placed, depart: depart, arrive: arrive, line: line), cover)
        }
        return best?.steps
    }

    /** [placed] between a start and an end on [line], each step as long as the way to the next. */
    static func finish(_ placed: [Maneuver], depart: Maneuver?, arrive: Maneuver?, line: [LatLng]) -> [Maneuver] {
        let total = Geo.length(line)
        var d = depart ?? Maneuver(type: .depart, instruction: "Head out", location: line[0], alongMeters: 0, distanceMeters: 0, road: nil, laneHint: nil)
        d.alongMeters = 0
        d.location = line[0]
        var a = arrive ?? Maneuver(type: .arrive, instruction: "You have arrived", location: line[line.count - 1], alongMeters: total, distanceMeters: 0, road: nil, laneHint: nil)
        a.alongMeters = total
        a.location = line[line.count - 1]
        var all = [d] + placed + [a]
        respace(&all)
        return all
    }

    static func respace(_ all: inout [Maneuver]) {
        guard !all.isEmpty else { return }
        for i in 0..<(all.count - 1) { all[i].distanceMeters = max(0, all[i + 1].alongMeters - all[i].alongMeters) }
        all[all.count - 1].distanceMeters = 0
    }

    /**
     * A "Make a U-turn" from the open router where the route line itself doesn't turn back: its
     * start was put on the wrong side of a junction, so it drove 20 m on, turned round and came
     * back, and Kite said "Make a U-turn", then "Turn sharp right onto Mill Lane" where Google
     * says "Slight left to stay on Mill Ln" (Romsey, 2026-11-12). The U-turn goes, and so does
     * the turn straight after it that only undoes it.
     */
    public static func dropFalseUTurns(_ steps: inout [Maneuver], line: [LatLng]) {
        let cum = Geo.cumulative(line)
        let total = cum.last ?? 0
        func heading(_ a: Double, _ b: Double) -> Double? {
            let p = Geo.point(along: line, meters: max(0, a), cumulative: cum), q = Geo.point(along: line, meters: min(total, b), cumulative: cum)
            return p.distance(to: q) > 3 ? Geo.bearing(p, q) : nil
        }
        var i = 0
        while i < steps.count {
            let m = steps[i]
            guard m.type == .uturn, !m.silent else { i += 1; continue }
            // The line's own way round over the 40 m either side: a real U-turn goes back the way it came.
            var back = false
            if let a = heading(m.alongMeters - 40, m.alongMeters), let b = heading(m.alongMeters, m.alongMeters + 40) {
                back = Roundabouts.angleGap(a, b) > 135
            }
            if back { i += 1; continue }
            steps.remove(at: i)
            if let j = steps[i...].firstIndex(where: { !$0.silent }), j < steps.count, steps[j].type != .arrive,
               steps[j].alongMeters - m.alongMeters < 40 {
                steps.remove(at: j)
            }
        }
        respace(&steps)
    }

    /**
     * Google's turns that the open router has nothing for (it leaves out a turn it thinks is
     * obvious, like keeping right where the A38(M) splits at Spaghetti Junction, or the second of
     * two roundabouts close together). Google places its steps by counting back from the end of
     * the route, which can drift by tens of metres, so each is first lined up by the nearest turn
     * both of them have. A quiet "the road's name changes" step right there takes the turn instead.
     */
    public static func addGoogleMissing(_ steps: inout [Maneuver], from google: [Maneuver], line: [LatLng]) {
        // Near M6 J11 Google says "Use the lane 2nd from the left to stay on M6" (no turn type) and
        // "Take the exit towards M6, Use the left lane" (no type at all) where the open router has
        // nothing, as to it you just stay on the M6: lane advice like that is worth keeping.
        let google = google.map { g -> Maneuver in
            var g = g
            if g.type == .unknown, g.instruction.hasPrefix("Take the exit") || g.instruction.hasPrefix("Exit at junction") {
                let w = (g.instruction + " " + (g.laneHint ?? "")).lowercased()
                if w.contains("left lane") || w.contains("from the left") { g.type = .rampLeft }
                else if w.contains("right lane") || w.contains("from the right") { g.type = .rampRight }
            }
            return g
        }
        let total = Geo.length(line)
        let cum = Geo.cumulative(line)
        // Merges too ("Merge onto M8"), except one into a lane that carries on to the next exit,
        // which the open router's steps leave out on purpose (keepLaneThroughMerges).
        let laneOn: Set<ManeuverType> = [.rampLeft, .rampRight, .forkLeft, .forkRight, .keepLeft, .keepRight]
        let wanted = google.filter { g in
            if g.type == .merge {
                let next = steps.first { !$0.silent && $0.alongMeters > g.alongMeters + 70 && $0.type != .depart }
                return !(next.map { laneOn.contains($0.type) && $0.alongMeters - g.alongMeters < 1_000 } ?? false)
            }
            return turnKinds.contains(g.type) || (g.type == .straight && g.instruction.hasPrefix("Use the lane"))
        }
        guard !wanted.isEmpty else { return }
        func spoken() -> [Maneuver] { steps.filter { !$0.silent && $0.type != .depart && $0.type != .arrive } }
        var pairs: [(g: Double, k: Double)] = []
        for g in wanted {
            let near = spoken().filter { alike($0, g) && !$0.leavesRoundabout && abs($0.alongMeters - g.alongMeters) < 150 }
            // Each other's nearest only: the second of two roundabouts mustn't line up on the first.
            guard let k = near.min(by: { abs($0.alongMeters - g.alongMeters) < abs($1.alongMeters - g.alongMeters) }),
                  let back = wanted.filter({ alike(k, $0) }).min(by: { abs($0.alongMeters - k.alongMeters) < abs($1.alongMeters - k.alongMeters) }),
                  back.alongMeters == g.alongMeters else { continue }
            pairs.append((g.alongMeters, k.alongMeters))
        }
        var changed = false
        let pairedKite = Set(pairs.map(\.k))
        for g in wanted where !pairs.contains(where: { $0.g == g.alongMeters }) {
            let ref = pairs.first { $0.g >= g.alongMeters } ?? pairs.last
            let at = min(max(1, g.alongMeters + (ref.map { $0.k - $0.g } ?? 0)), total - 1)
            // The same kind of step a little further off is that step, drifted; roundabouts come in
            // pairs a hundred metres apart (Six Ways, Erdington), so only the near one counts for them.
            if spoken().contains(where: { abs($0.alongMeters - at) < 70 || (g.type != .roundabout && alike($0, g) && !pairedKite.contains($0.alongMeters) && abs($0.alongMeters - at) < (g.type == .merge ? 300 : 150)) }) { continue }
            var m = g
            m.alongMeters = at
            m.location = Geo.point(along: line, meters: at, cumulative: cum)
            m.silent = false
            if let i = steps.firstIndex(where: { $0.silent && abs($0.alongMeters - at) < 40 }) {
                m.road = m.road ?? steps[i].road
                m.alongMeters = steps[i].alongMeters
                m.location = steps[i].location
                steps[i] = m
            } else {
                // The road carries on as it was: the step before's road, unless Google names a new one.
                m.road = m.road ?? steps.last { $0.alongMeters <= at }?.road
                let k = steps.firstIndex { $0.alongMeters > at } ?? steps.count
                steps.insert(m, at: k)
            }
            changed = true
        }
        if changed { respace(&steps) }
    }

    /**
     * Where Google and the open router call the same spot differently, Google's kind of step:
     * a roundabout Google knows where the router only says "Turn right" (Otterbourne Hill, and
     * small roundabouts the open map draws as a plain junction), and a real turn where the router
     * says "Continue straight onto Gloucester Road" because the main road bends away. The router's
     * road name and lanes are kept.
     */
    public static func addGoogleKinds(_ steps: inout [Maneuver], from google: [Maneuver], line: [LatLng] = []) {
        let cum = Geo.cumulative(line)
        let total = cum.last ?? 0
        /** Which way the line goes at [a]: degrees, left negative. */
        func bend(_ a: Double) -> Double? {
            guard line.count >= 2 else { return nil }
            let p0 = Geo.point(along: line, meters: max(0, a - 40), cumulative: cum), p1 = Geo.point(along: line, meters: a, cumulative: cum)
            let p2 = Geo.point(along: line, meters: min(total, a + 60), cumulative: cum)
            guard p0.distance(to: p1) > 3, p1.distance(to: p2) > 3 else { return nil }
            return (Geo.bearing(p1, p2) - Geo.bearing(p0, p1) + 540).truncatingRemainder(dividingBy: 360) - 180
        }
        for i in steps.indices where !steps[i].silent && ![.depart, .arrive, .roundabout].contains(steps[i].type) {
            let near = google.filter { abs($0.alongMeters - steps[i].alongMeters) < 50 && turnKinds.contains($0.type) }
            guard let g = near.min(by: { abs($0.alongMeters - steps[i].alongMeters) < abs($1.alongMeters - steps[i].alongMeters) }) else { continue }
            // Another of the router's steps is nearer that one of Google's: it belongs to that step.
            if steps.indices.contains(where: { $0 != i && !steps[$0].silent && abs(steps[$0].alongMeters - g.alongMeters) < abs(steps[i].alongMeters - g.alongMeters) }) { continue }
            let road = steps[i].road
            if g.type == .roundabout, let n = g.exit, [.turnLeft, .turnRight, .slightLeft, .slightRight, .sharpLeft, .sharpRight, .straight].contains(steps[i].type) {
                steps[i].type = .roundabout
                steps[i].exit = n
                steps[i].lanes = []
                steps[i].instruction = OSRM.instruction(type: "roundabout", modifier: nil, road: road, exit: n, mode: .drive)
            } else if side(steps[i].type) != 0, side(g.type) != 0, side(g.type) != side(steps[i].type),
                      let b = bend(steps[i].alongMeters), b * Double(side(g.type)) > 30 {
                // "Keep right at the fork onto Castle Lane West" where the road really goes 45 degrees
                // left (Ibbertson Road, Bournemouth): Google's "Turn left", the way the road goes.
                steps[i].type = g.type
                let verb = DirectionsParser.phrase(g.type).replacingOccurrences(of: "Slight", with: "Turn slight").replacingOccurrences(of: "Sharp", with: "Turn sharp")
                steps[i].instruction = verb + (road.map { " onto \($0)" } ?? "")
                steps[i].lanes = []
            } else if steps[i].type == .straight, side(g.type) != 0, [.turnLeft, .turnRight, .sharpLeft, .sharpRight, .slightLeft, .slightRight].contains(g.type) {
                steps[i].type = g.type
                let verb = DirectionsParser.phrase(g.type).replacingOccurrences(of: "Slight", with: "Turn slight").replacingOccurrences(of: "Sharp", with: "Turn sharp")
                steps[i].instruction = verb + (road.map { " onto \($0)" } ?? "")
            }
        }
    }

    /**
     * What the signs say, from Google, where the open router's slip road or fork has nothing to
     * go by: "Take the exit" becomes Google's "Take the exit towards Emsworth" (A27, Havant).
     */
    public static func addGoogleSigns(_ steps: inout [Maneuver], from google: [Maneuver]) {
        let kinds: Set<ManeuverType> = [.rampLeft, .rampRight, .forkLeft, .forkRight, .keepLeft, .keepRight]
        for i in steps.indices where kinds.contains(steps[i].type) && !steps[i].silent {
            let w = steps[i].instruction.lowercased()
            guard w.range(of: #"exit \d|junction \d"#, options: .regularExpression) == nil else { continue }
            let near = google.filter { kinds.contains($0.type) && side($0.type) == side(steps[i].type) && abs($0.alongMeters - steps[i].alongMeters) < 150 }
            guard let g = near.min(by: { abs($0.alongMeters - steps[i].alongMeters) < abs($1.alongMeters - steps[i].alongMeters) }) else { continue }
            let gw = g.instruction.lowercased()
            // The junction number off Google's sign, with the router's destinations: "Take exit 4 toward The Midlands and London".
            if let r = gw.range(of: #"junction \d+[a-z]?"#, options: .regularExpression), w.hasPrefix("take the exit") {
                let n = gw[r].dropFirst("junction ".count).uppercased()
                steps[i].instruction = "Take exit \(n)" + steps[i].instruction.dropFirst("Take the exit".count)
                continue
            }
            guard !w.contains(" toward"), !w.contains(" onto ") else { continue }
            guard gw.contains("toward") || gw.range(of: #"junction \d"#, options: .regularExpression) != nil || gw.contains(" onto ") else { continue }
            steps[i].instruction = g.instruction
            if steps[i].laneHint == nil, steps[i].lanes.isEmpty { steps[i].laneHint = g.laneHint }
        }
    }

    /** Leaving by a slip road: "Exit at junction 10", "Take exit 6 onto M6", "Use the lane 2nd from the left to take the exit towards M25". */
    static func isExit(_ m: Maneuver) -> Bool {
        // Sometimes marked as a fork or a keep (Gravelly Hill J6): the words say it's the exit.
        [.rampLeft, .rampRight, .forkLeft, .forkRight, .keepLeft, .keepRight].contains(m.type)
            && m.instruction.range(of: #"\b[Ee]xit\b"#, options: .regularExpression) != nil
    }

    /** Says which junction, road or place, not just "Take the exit". */
    static func saysWhere(_ w: String) -> Bool {
        w.rangeOfCharacter(from: .decimalDigits) != nil || w.contains(" toward") || w.contains(" onto ")
    }

    /**
     * Steps that say the same thing twice, or nothing: "Turn right" then "Continue straight onto
     * Wide Ln" 11 m later is one turn, "Turn right onto Wide Ln"; "Turn slight right onto Coldhams
     * Lane" then "Turn right onto Coldhams Lane" 8 m on is one turn; "Continue onto" the road
     * you're already on says nothing; a turn that keeps you on the same road is "to stay on" it.
     */
    public static func tidy(_ steps: inout [Maneuver]) {
        var prev: Int?
        var road: String?
        for i in steps.indices {
            // A start's road is only where it heads ("Exit the car park towards Elmbank Cres"), not the road it's on.
            defer { if !steps[i].silent { prev = i }; if let r = steps[i].road, steps[i].type != .depart { road = r } }
            guard !steps[i].silent, steps[i].type != .depart, steps[i].type != .arrive else { continue }
            let w = steps[i].instruction
            let carryOn = steps[i].type == .straight && (w.hasPrefix("Continue") || w.hasPrefix("Stay on"))
            if let p = prev, steps[i].alongMeters - steps[p].alongMeters < 200, side(steps[p].type) == side(steps[i].type),
               isExit(steps[p]), isExit(steps[i]) {
                // One slip road told twice ("Exit at junction 10", then "Take the exit" 160 m on, one from
                // Google and one from the open router): the first, with whatever the second adds.
                if !saysWhere(steps[p].instruction), saysWhere(w) { steps[p].instruction = w }
                if steps[p].lanes.isEmpty { steps[p].lanes = steps[i].lanes }
                if steps[p].laneHint == nil { steps[p].laneHint = steps[i].laneHint }
                steps[i].silent = true
                continue
            }
            if let p = prev, steps[p].type != .depart, steps[i].alongMeters - steps[p].alongMeters < 35 {
                if carryOn, steps[i].lanes.isEmpty {
                    steps[i].silent = true
                    let pw = steps[p].instruction
                    if let r = steps[i].road, !pw.contains(" onto "), !pw.contains(" on "), !pw.contains(" toward"),
                       !(steps[p].type == .roundabout && !steps[p].leavesRoundabout) {
                        steps[p].instruction = pw + " onto \(r)"
                        steps[p].road = r
                    }
                    continue
                }
                if steps[p].type != .roundabout, steps[i].type != .roundabout, steps[i].alongMeters - steps[p].alongMeters < 20,
                   side(steps[p].type) != 0, side(steps[p].type) == side(steps[i].type) {
                    // One turn told twice: the second, with the first's lanes.
                    if steps[i].lanes.isEmpty { steps[i].lanes = steps[p].lanes }
                    if steps[i].laneHint == nil { steps[i].laneHint = steps[p].laneHint }
                    steps[p].silent = true
                    continue
                }
            }
            guard let r = road, steps[i].road == r, steps[i].type != .roundabout else { continue }
            if carryOn, w == "Continue onto \(r)" || w == "Continue straight onto \(r)", steps[i].lanes.isEmpty, steps[i].laneHint == nil {
                steps[i].silent = true
            } else if side(steps[i].type) != 0, w.hasSuffix(" onto \(r)"), w.hasPrefix("Turn") || w.hasPrefix("Keep") || w.hasPrefix("Slight") {
                steps[i].instruction = String(w.dropLast(" onto \(r)".count)) + " to stay on \(r)"
            }
        }
    }
}
