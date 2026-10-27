import Foundation
import VelaCore

/**
 * `swift run vela-probe drive`: Matteo's real drives replayed second by second like a phone in a
 * holder would see them: GPS fixes with a little wobble at road speed, through the same tracker,
 * spoken-turn timing and reroutes the app uses. Prints what the driver would hear and when, how
 * long a wrong turn takes to notice and fix, and how long speed limits take to arrive.
 */
/** `swift run vela-probe route lat lng lat lng`: every route Kite would offer, with its steps. */
/** `swift run vela-probe body lat lng lat lng`: Google's raw route answer, to look through. */
func rawBody() async {
    let a = CommandLine.arguments.dropFirst(2).compactMap(Double.init)
    guard a.count == 4, let body = try? await GoogleClient.shared.directionsBody(from: LatLng(a[0], a[1]), to: LatLng(a[2], a[3]), mode: .drive) else { return }
    print(body)
}

func routeCheck() async {
    let a = CommandLine.arguments.dropFirst(2).compactMap(Double.init)
    guard a.count >= 4 else { print("usage: route lat lng lat lng [heading]"); return }
    let r = await Directions.routes(from: LatLng(a[0], a[1]), to: LatLng(a[2], a[3]), mode: .drive,
                                    heading: a.count > 4 ? a[4] : nil, alternatives: true)
    for (k, x) in r.routes.enumerated() {
        print(String(format: "== route %d: %.0f m, %.0f min, source %@", k, x.distanceMeters, x.durationSeconds / 60, x.source))
        for m in x.maneuvers where !m.silent { print(String(format: "  %6.0f m  %@", m.alongMeters, m.instruction)) }
    }
    if let e = r.error { print("error: \(e)") }
    // The first route's line, for `xcrun simctl location booted start - < file`.
    if let out = ProcessInfo.processInfo.environment["LINE_OUT"], let x = r.routes.first {
        try? x.polyline.map { String(format: "%.6f,%.6f", $0.lat, $0.lng) }.joined(separator: "\n").write(toFile: out, atomically: true, encoding: .utf8)
    }
}

func driveReplay() async {
    struct Drive {
        var name: String
        var from: LatLng
        var to: LatLng
        /** Meters per second. */
        var speed: Double
        /** A point off the route to drive through, for a wrong turn. */
        var detour: LatLng?
    }
    let drives = [
        Drive(name: "M27 J5 to Whiteley at 70 mph", from: LatLng(50.9521, -1.3704), to: LatLng(50.8846, -1.2482), speed: 31),
        Drive(name: "Totton to Southampton Uni", from: LatLng(50.9190, -1.4900), to: LatLng(50.9346, -1.3966), speed: 13),
        Drive(name: "Park Gate to Southampton Uni", from: LatLng(50.8705, -1.2690), to: LatLng(50.9346, -1.3966), speed: 20),
        Drive(name: "M271 south, Redbridge, A33 east", from: LatLng(50.9890, -1.4970), to: LatLng(50.9077, -1.4136), speed: 22),
        Drive(name: "Millbrook to Uni, wrong turn into Shirley", from: LatLng(50.9205, -1.4470), to: LatLng(50.9346, -1.3966),
              speed: 12, detour: LatLng(50.9300, -1.4290)),
        Drive(name: "Totton to Uni, missed turn off the A35", from: LatLng(50.9190, -1.4900), to: LatLng(50.9346, -1.3966),
              speed: 13, detour: LatLng(50.9105, -1.4150)),
        Drive(name: "M27 at 70, missed junction 9, on to junction 10", from: LatLng(50.9521, -1.3704), to: LatLng(50.8846, -1.2482),
              speed: 31, detour: LatLng(50.8640, -1.2050)),
    ]
    let only = CommandLine.arguments.count > 2 ? Int(CommandLine.arguments[2]) : nil
    for (n, d) in drives.enumerated() where only == nil || only == n {
        print("##### \(n): \(d.name)")
        let t0 = Date()
        guard var route = await Directions.routes(from: d.from, to: d.to, mode: .drive).routes.first else {
            print("  no route"); continue
        }
        print(String(format: "  route %.0f m, source %@, %.1f s to get", route.distanceMeters, route.source, Date().timeIntervalSince(t0)))
        let tv = Date()
        let matched = await Valhalla.speedLimits(along: route.polyline)
        print(String(format: "  road matcher limits: %d changes, %.0f%% of the way known, %.1f s%@", matched?.limits.count ?? 0,
                     matched.map { m in coverage(m.limits, total: route.distanceMeters) } ?? 0, Date().timeIntervalSince(tv), matched == nil ? " (FAILED)" : ""))
        let cached = RoadCache.shared.along(route.polyline)
        print("  known before asking: \(Overpass.speedLimits(cached.ways, on: route.polyline).count) limit stretches, \(cached.signals.count) lights")
        let t1 = Date()
        let found = await Overpass.alongRoute(route.polyline, speed: true, plate: false)
        let limits = found.map { Overpass.speedLimits($0.ways, on: route.polyline) } ?? []
        print(String(format: "  speed limits: %d stretches, cameras %d, lights %d, %.1f s to get%@", limits.count,
                     found.map { CameraAlerts.project($0.cameras, on: route.polyline).count } ?? 0, found?.signals.count ?? 0,
                     Date().timeIntervalSince(t1), found == nil ? " (FAILED)" : ""))
        // Like the app: what's already known stays when the servers don't answer.
        route.speedLimits = found == nil ? Overpass.speedLimits(cached.ways, on: route.polyline) : limits
        print(String(format: "  overpass limits cover %.0f%%", coverage(limits, total: route.distanceMeters)))
        if let m = matched, !m.limits.isEmpty {
            // Where both know a limit, how often they agree (to the nearest 5 mph).
            var same = 0, both = 0
            for x in stride(from: 0.0, to: route.distanceMeters, by: 50) {
                let o = route.speedLimit(at: x)
                let v = m.limits.last { $0.startMeters <= x }?.kmh
                guard let o, let v else { continue }
                both += 1
                if abs(o - v) < 8 { same += 1 } else if both % 5 == 0 { print(String(format: "    differ at %.0f m: overpass %.0f, matcher %.0f km/h", x, o, v)) }
            }
            print("  agree on \(same) of \(both) samples")
            route.speedLimits = m.limits
        }

        // The road actually driven: the route itself, or the open router's way through the detour.
        var drivenLine = route.polyline
        if let via = d.detour, let o = try? await OSRM.route(from: d.from, to: d.to, mode: .drive, via: [via]).first {
            drivenLine = o.polyline
        }
        var cum = Geo.cumulative(drivenLine)
        var total = cum.last ?? 0
        var tracker = RouteTracker(route: route)
        var spoken: [Int: Int] = [:]
        var told = Set<Int>()
        var lastReroute = -100
        var reroutes = 0
        var offSince: Int?
        var noLimit = 0
        var rng = SystemRandomNumberGenerator()
        var s = 0
        var driven = 0.0
        var prev = drivenLine[0]
        while driven < total, s < 3600 {
            s += 1
            driven = min(total, driven + d.speed)
            let truth = Geo.point(along: drivenLine, meters: driven, cumulative: cum)
            // About 5 m of GPS wobble.
            // NOISE=4 for a city centre's 20 m.
            let k = Double(ProcessInfo.processInfo.environment["NOISE"] ?? "1") ?? 1
            let fix = LatLng(truth.lat + k * Double.random(in: -4e-5...4e-5, using: &rng), truth.lng + k * Double.random(in: -6e-5...6e-5, using: &rng))
            let heading = Geo.bearing(prev, truth)
            prev = truth
            let p = tracker.update(fix, accuracy: 8 * k, heading: heading)
            if p.arrived { print("  \(s)s arrived"); break }
            if route.speedLimit(at: p.alongMeters) == nil { noLimit += 1 }
            if p.offRoute {
                if offSince == nil { offSince = s }
                if s - lastReroute > 8 {
                    lastReroute = s
                    let r0 = Date()
                    let fresh = await Directions.routes(from: fix, to: d.to, mode: .drive, heading: heading).routes.first
                    let took = Date().timeIntervalSince(r0)
                    reroutes += 1
                    if let fresh {
                        // The car kept going while the new route was fetched.
                        let late = Int(took.rounded())
                        s += late
                        driven = min(total, driven + d.speed * Double(late))
                        route = fresh
                        // Like the app: the road matcher's limits for the new route, else what's remembered.
                        let m = await Valhalla.speedLimits(along: fresh.polyline)
                        route.speedLimits = m.map(\.limits) ?? Overpass.speedLimits(RoadCache.shared.along(fresh.polyline).ways, on: fresh.polyline)
                        tracker = RouteTracker(route: route)
                        // A driver who missed a turn follows the new route from here.
                        drivenLine = fresh.polyline
                        cum = Geo.cumulative(drivenLine)
                        total = cum.last ?? 0
                        driven = min(total, d.speed * Double(late))
                        spoken = [:]
                        told = []
                        let first = fresh.maneuvers.first { $0.type != .depart }
                        print(String(format: "  %ds REROUTE (off route for %ds, new route in %.1f s, %.0f m): first step '%@'",
                                     s, s - late - (offSince ?? s), took, fresh.distanceMeters, first?.instruction ?? "-"))
                        // No limits on the new route until they're asked again.
                        offSince = nil
                    } else {
                        print("  \(s)s REROUTE FAILED")
                    }
                }
                continue
            }
            offSince = nil
            // The app's spoken-turn timing (Navigator.announce).
            guard let i = p.nextIndex, i < route.maneuvers.count else { continue }
            let m = route.maneuvers[i]
            let dist = p.distanceToNext
            let stage = spoken[i] ?? 0
            let now = max(40, d.speed * 4), near = max(250, d.speed * 12), far = max(1000, d.speed * 40)
            func say(_ t: String) { print(String(format: "  %4ds %5.0f m  \"%@\"", s, dist, t)) }
            if dist <= now && stage < 3 {
                spoken[i] = 3
                let small = m.leavesRoundabout && (route.maneuvers[..<i].last { $0.type == .roundabout }).map { m.alongMeters - $0.alongMeters < 150 } == true
                if m.type != .arrive && !small { say(m.instruction) }
            } else if m.leavesRoundabout {
                continue
            } else if dist <= near && dist > now + 30 && stage < 2 {
                spoken[i] = 2
                say("In \(Int(dist)) m, \(m.instruction)")
            } else if dist <= far && dist > near + 150 && stage < 1 && m.type != .arrive {
                spoken[i] = 1
                say("In \(Int(dist)) m, \(m.instruction)")
            } else if dist > 3000 && stage < 1 && !told.contains(i),
                      p.alongMeters - (route.maneuvers[..<i].last { !$0.silent }?.alongMeters ?? 0) < 500 {
                told.insert(i)
                say("Continue for \(Int(dist)) m")
            }
        }
        print("  \(s)s end, reroutes \(reroutes), seconds with no speed limit shown \(noLimit)")
    }
    RoadCache.shared.flush()
}

/** How much of [total] meters has a known limit. */
func coverage(_ limits: [SpeedLimit], total: Double) -> Double {
    guard total > 0 else { return 0 }
    var known = 0.0
    for (i, l) in limits.enumerated() where l.kmh != nil {
        known += (i + 1 < limits.count ? limits[i + 1].startMeters : total) - l.startMeters
    }
    return known / total * 100
}
