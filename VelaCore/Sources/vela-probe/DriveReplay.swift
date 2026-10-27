import Foundation
import VelaCore

/**
 * `swift run vela-probe drive`: Matteo's real drives replayed second by second like a phone in a
 * holder would see them: GPS fixes with a little wobble at road speed, through the same tracker,
 * spoken-turn timing and reroutes the app uses. Prints what the driver would hear and when, how
 * long a wrong turn takes to notice and fix, and how long speed limits take to arrive.
 */
/** `swift run vela-probe route lat lng lat lng`: every route Kite would offer, with its steps. */
func routeCheck() async {
    let a = CommandLine.arguments.dropFirst(2).compactMap(Double.init)
    guard a.count == 4 else { print("usage: route lat lng lat lng"); return }
    let r = await Directions.routes(from: LatLng(a[0], a[1]), to: LatLng(a[2], a[3]), mode: .drive)
    for (k, x) in r.routes.enumerated() {
        print(String(format: "== route %d: %.0f m, %.0f min, source %@", k, x.distanceMeters, x.durationSeconds / 60, x.source))
        for m in x.maneuvers where !m.silent { print(String(format: "  %6.0f m  %@", m.alongMeters, m.instruction)) }
    }
    if let e = r.error { print("error: \(e)") }
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
    ]
    let only = CommandLine.arguments.count > 2 ? Int(CommandLine.arguments[2]) : nil
    for (n, d) in drives.enumerated() where only == nil || only == n {
        print("##### \(n): \(d.name)")
        let t0 = Date()
        guard var route = await Directions.routes(from: d.from, to: d.to, mode: .drive).routes.first else {
            print("  no route"); continue
        }
        print(String(format: "  route %.0f m, source %@, %.1f s to get", route.distanceMeters, route.source, Date().timeIntervalSince(t0)))
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
            let fix = LatLng(truth.lat + Double.random(in: -4e-5...4e-5, using: &rng), truth.lng + Double.random(in: -6e-5...6e-5, using: &rng))
            let heading = Geo.bearing(prev, truth)
            prev = truth
            let p = tracker.update(fix, accuracy: 8, heading: heading)
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
                        route.speedLimits = Overpass.speedLimits(RoadCache.shared.along(fresh.polyline).ways, on: fresh.polyline)
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
