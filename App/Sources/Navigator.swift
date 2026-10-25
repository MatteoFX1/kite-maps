import AudioToolbox
import AVFoundation
import CoreLocation
import Foundation
import UIKit
import VelaCore

/**
 * One drive, walk or ride: follows the user along the route, speaks the turns, and reroutes
 * when they leave it.
 */
@MainActor
final class Navigator: ObservableObject {
    @Published private(set) var route: Route
    @Published private(set) var progress: RouteTracker.Progress?
    @Published private(set) var rerouting = false
    @Published private(set) var arrived = false
    @Published var muted: Bool { didSet { if muted { speech.stopSpeaking(at: .immediate) } } }
    /** The camera follows the user until they pan the map. */
    @Published var following = true
    @Published private(set) var speedMps: Double = 0
    @Published private(set) var lastFix: CLLocation?
    /** A camera coming up within about twelve seconds, for the warning card. */
    @Published private(set) var cameraAhead: CameraAlert?
    /** A Waze report coming up on the route (police, a crash…), for its warning card. */
    @Published private(set) var wazeAhead: WazeAhead?
    /** How many Waze reports this drive warned about, for the arrival card. */
    @Published private(set) var wazeWarned = 0
    /** How likely police are on the next few km, 0-100, when the risk score is on. */
    @Published private(set) var policeRisk: Int?
    /** The nearest police plane or helicopter within about 10 km, and how far it is. */
    @Published private(set) var aircraftNear: (aircraft: PoliceAircraft, meters: Double)?
    /** An average speed check the user is driving through, with their average so far. */
    @Published private(set) var averageCheck: AverageCheck?

    let destination: Place
    /** Stops still ahead, in trip order; each is dropped once reached. */
    @Published private(set) var stops: [Place]
    private var tracker: RouteTracker
    private let speech = AVSpeechSynthesizer()
    private var spokenStage: [Int: Int] = [:]
    private var lastReroute = Date.distantPast
    private var avoidHighways: Bool
    private var avoidTolls: Bool
    private var routeCameras: [(camera: RoadCamera, along: Double)] = []
    private var spokenCameras: Set<Int> = []
    private var zones: [AverageSpeedZone] = []
    private var zoneEntry: (index: Int, time: Date, along: Double)?
    private var demoAlerts = false
    private var speeding = SpeedingAlerts()
    private var routeWaze: [(alert: WazeAlert, along: Double)] = []
    /** Reports already warned about, by Waze's id, so a refresh doesn't warn again. */
    private var spokenWaze: Set<String> = []
    private var wazeAt = Date.distantPast
    private var wazeLoading = false
    private var riskAt = Date.distantPast
    private var riskPast: [PoliceRisk.Report]?
    private var riskSpokenAt = Date.distantPast
    private var aircraftAt = Date.distantPast
    private var aircraftLoading = false
    private var spokenAircraft: Set<String> = []

    init(route: Route, destination: Place, stops: [Place] = [], settings: AppSettings) {
        self.route = route
        self.destination = destination
        self.stops = stops
        tracker = RouteTracker(route: route)
        muted = !settings.voice
        avoidHighways = settings.avoidHighways
        avoidTolls = settings.avoidTolls
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .voicePrompt, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers])
        UIApplication.shared.isIdleTimerDisabled = true
        let first = route.maneuvers.first { $0.type != .depart } ?? route.maneuvers.first
        loadCameras()
        loadWaze()
        loadRoundabouts()
        speak("Starting route to \(destination.name). " + (first.map { "\($0.instruction)." } ?? ""))
    }

    func stop() {
        speech.stopSpeaking(at: .immediate)
        UIApplication.shared.isIdleTimerDisabled = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /** The posted limit where the user is now, in km/h. */
    var speedLimitKmh: Double? {
        route.speedLimit(at: progress?.alongMeters ?? 0)
    }

    var nextManeuver: Maneuver? {
        if let demoManeuver { return demoManeuver }
        guard let i = progress?.nextIndex, i < route.maneuvers.count else { return route.maneuvers.first }
        return route.maneuvers[i]
    }

    /** The one after next, for the "Then" line when two turns come close together. */
    var thenManeuver: Maneuver? {
        guard let i = progress?.nextIndex, i + 1 < route.maneuvers.count else { return nil }
        let a = route.maneuvers[i], b = route.maneuvers[i + 1]
        return b.alongMeters - a.alongMeters < 300 ? b : nil
    }

    func handle(_ loc: CLLocation) {
        guard !arrived else { return }
        lastFix = loc
        speedMps = max(0, loc.speed)
        let p = tracker.update(LatLng(loc.coordinate.latitude, loc.coordinate.longitude),
                               accuracy: max(loc.horizontalAccuracy, 5))
        progress = p
        if p.arrived {
            arrived = true
            speak("You have arrived at \(destination.name).")
            return
        }
        if p.offRoute && !rerouting && Date().timeIntervalSince(lastReroute) > 8 {
            reroute(from: loc)
            return
        }
        announce(p)
        checkCameras(p)
        checkWaze(p)
        checkRisk(p)
        checkAircraft(loc)
        checkZones(p)
        checkStops(p)
        if route.mode == .drive, AppSettings.shared.speedingAlert,
           speeding.update(speedKmh: loc.speed >= 0 ? loc.speed * 3.6 : nil, limitKmh: speedLimitKmh, now: loc.timestamp.timeIntervalSince1970) {
            speak("You're over the speed limit.")
        }
    }

    /** A stop picked on the way (search along the route): go there first, then on. */
    func addStop(_ p: Place) {
        stops.insert(p, at: 0)
        speak("Adding a stop at \(p.name).")
        if let fix = lastFix { reroute(from: fix, quiet: true) }
    }

    /** Says when a stop is reached (within 40 m of it along the route) and drops it. */
    private func checkStops(_ p: RouteTracker.Progress) {
        guard let next = stops.first else { return }
        let at = Geo.project(next.location, on: route.polyline)
        guard at.offset < 150, p.alongMeters >= at.along - 40 else { return }
        stops.removeFirst()
        speak("You've reached \(next.name). Continuing to \(stops.first?.name ?? destination.name).")
    }

    /** Cameras and speed limits along the route, from OpenStreetMap, in one query per route. */
    /** The real shape of each roundabout ahead, for the picture in the banner. */
    private func loadRoundabouts() {
        guard route.mode != .walk, route.maneuvers.contains(where: { $0.type == .roundabout }) else { return }
        let r = route
        Task {
            guard let drawn = await Roundabouts.apply(to: r), self.route.id == r.id else { return }
            // Only the roundabout layouts change; the line and steps are the same.
            for i in self.route.maneuvers.indices where i < drawn.maneuvers.count {
                self.route.maneuvers[i].roundabout = drawn.maneuvers[i].roundabout
            }
        }
    }

    private func loadCameras() {
        routeCameras = []
        spokenCameras = []
        cameraAhead = nil
        zones = []
        zoneEntry = nil
        averageCheck = nil
        let s = AppSettings.shared
        guard route.mode == .drive else { return }
        let line = route.polyline, id = route.id
        let speed = s.speedCameras, plate = s.plateCameras
        Task {
            // The public Overpass servers are sometimes busy: try again a couple of times.
            var result = await Overpass.alongRoute(line, speed: speed, plate: plate)
            for _ in 0..<2 where result == nil && self.route.id == id {
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                result = await Overpass.alongRoute(line, speed: speed, plate: plate)
            }
            guard let found = result, self.route.id == id else { return }
            self.routeCameras = CameraAlerts.project(found.cameras, on: line)
            self.route.speedLimits = Overpass.speedLimits(found.ways, on: line)
            self.zones = CameraAlerts.merge(found.zones, CameraAlerts.averageZones(self.routeCameras))
        }
    }

    private func checkCameras(_ p: RouteTracker.Progress) {
        guard !demoAlerts else { return }
        let meters = routeCameras.map(\.along)
        if let ahead = cameraAhead {
            // Keep the card up, counting down, until the camera is passed.
            if let next = meters.first(where: { $0 > p.alongMeters }), next - p.alongMeters < CameraAlerts.maxLead {
                var a = ahead
                a.meters = next - p.alongMeters
                cameraAhead = a
            } else {
                cameraAhead = nil
            }
        }
        guard let i = CameraAlerts.due(cameraMeters: meters, traveled: p.alongMeters, speedMps: speedMps, spoken: spokenCameras) else { return }
        // Cameras a few meters apart are one warning.
        for (j, m) in meters.enumerated() where m >= meters[i] && m - meters[i] < 40 { spokenCameras.insert(j) }
        let cam = routeCameras[i].camera
        let ahead = meters[i] - p.alongMeters
        let limit = cam.kind == .speed ? (cam.maxspeedKmh ?? route.speedLimit(at: meters[i])) : nil
        cameraAhead = CameraAlert(kind: cam.kind, meters: ahead, startMeters: max(ahead, 1), limitKmh: limit)
        if AppSettings.shared.cameraWarnings {
            chime()
            if cam.kind == .speed {
                speak((cam.average ? "Average speed camera ahead" : "Speed camera ahead") + (limit.map { ", limit \(Fmt.spokenSpeed($0))." } ?? "."))
            } else {
                speak(Fmt.yards ? "Number plate camera ahead." : "License plate camera ahead.")
            }
        }
    }

    /** Live Waze reports along the route; asked again every three minutes, since they come and go. */
    private func loadWaze() {
        guard route.mode == .drive, AppSettings.shared.wazeOn, !wazeLoading, !demoAlerts else { return }
        wazeLoading = true
        wazeAt = Date()
        let line = route.polyline, id = route.id
        // Only the next 60 km: on a long drive the rest will have changed by the time you get there.
        let at = progress?.alongMeters ?? 0
        let window = Geo.slice(line, from: max(at - 200, 0), to: at + 60_000)
        Task {
            let found = await Waze.alongRoute(window.count >= 2 ? window : line)
            self.wazeLoading = false
            guard let found, self.route.id == id else { return }
            WazeHistory.shared.record(found)
            self.routeWaze = Waze.project(found, on: line)
        }
    }

    private func checkWaze(_ p: RouteTracker.Progress) {
        guard !demoAlerts else { return }
        let s = AppSettings.shared
        if Date().timeIntervalSince(wazeAt) > 180 { loadWaze() }
        if let ahead = wazeAhead {
            // Keep the card up, counting down, until the report is passed.
            if let hit = routeWaze.first(where: { $0.alert.id == ahead.alert.id }), hit.along > p.alongMeters - 10 {
                var a = ahead
                a.meters = max(hit.along - p.alongMeters, 0)
                wazeAhead = a.meters > 0 ? a : nil
            } else {
                wazeAhead = nil
            }
        }
        guard s.wazeOn, speedMps >= CameraAlerts.movingFloor else { return }
        // A little earlier than a camera: police and crashes are worth more notice.
        let lead = min(max(speedMps * 20, 250), 1200)
        guard let next = routeWaze.first(where: {
            !spokenWaze.contains($0.alert.id) && s.shows($0.alert)
                && $0.along > p.alongMeters && $0.along - p.alongMeters <= lead
        }) else { return }
        // Reports a few meters apart are one warning.
        for w in routeWaze where abs(w.along - next.along) < 60 { spokenWaze.insert(w.alert.id) }
        let ahead = next.along - p.alongMeters
        wazeAhead = WazeAhead(alert: next.alert, meters: ahead, startMeters: max(ahead, 1))
        wazeWarned += 1
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
        if s.wazeVoice {
            chime()
            // "Police reported ahead, in 400 yards."
            speak("\(String(next.alert.spoken.dropLast())), in \(Fmt.spokenDistance(ahead)).")
        }
    }

    /** The police risk for the next 3 km, worked out again every 20 seconds. */
    private func checkRisk(_ p: RouteTracker.Progress) {
        guard !demoAlerts else { return }
        let s = AppSettings.shared
        guard route.mode == .drive, s.wazeOn, s.policeRisk else { policeRisk = nil; return }
        guard Date().timeIntervalSince(riskAt) > 20 else { return }
        riskAt = Date()
        if riskPast == nil { riskPast = WazeHistory.shared.reports }
        let to = p.alongMeters + 3000
        let ahead = Geo.slice(route.polyline, from: p.alongMeters, to: to)
        let live = routeWaze.filter { $0.alert.kind == .police && s.shows($0.alert) && $0.along > p.alongMeters && $0.along < to }.count
        let score = PoliceRisk.score(ahead: ahead, past: riskPast ?? [], live: live)
        let was = policeRisk.map(PoliceRisk.level) ?? .low
        policeRisk = score
        // Like Highway Radar's hot zone sound: once when it turns high, at most every 10 minutes. Only
        // for a place police keep being seen; a live report already has its own warning.
        if PoliceRisk.level(score) == .high, was != .high, s.wazeVoice, Date().timeIntervalSince(riskSpokenAt) > 600,
           live == 0 || PoliceRisk.level(PoliceRisk.score(ahead: ahead, past: riskPast ?? [], live: 0)) == .high {
            riskSpokenAt = Date()
            speak("Police hotspot ahead.")
        }
    }

    /** Police aircraft near the user, asked every 30 seconds while driving. */
    private func checkAircraft(_ loc: CLLocation) {
        guard !demoAlerts else { return }
        guard route.mode == .drive, AppSettings.shared.policeAircraft else { aircraftNear = nil; return }
        let here = LatLng(loc.coordinate.latitude, loc.coordinate.longitude)
        if let near = aircraftNear { aircraftNear = (near.aircraft, near.aircraft.location.distance(to: here)) }
        guard !aircraftLoading, Date().timeIntervalSince(aircraftAt) > 30 else { return }
        aircraftLoading = true
        aircraftAt = Date()
        Task {
            let found = await Aircraft.police(near: here, nauticalMiles: 10)
            self.aircraftLoading = false
            guard let found else { return }
            let me = self.lastFix.map { LatLng($0.coordinate.latitude, $0.coordinate.longitude) } ?? here
            let nearest = found.map { ($0, $0.location.distance(to: me)) }.filter { $0.1 < 10_000 }.min { $0.1 < $1.1 }
            self.aircraftNear = nearest.map { (aircraft: $0.0, meters: $0.1) }
            if let a = nearest?.0, !self.spokenAircraft.contains(a.id) {
                self.spokenAircraft.insert(a.id)
                UINotificationFeedbackGenerator().notificationOccurred(.warning)
                self.chime()
                self.speak("\(a.title) nearby.")
            }
        }
    }

    /** Average speed checks: say when one starts and ends, and keep the user's average on screen. */
    private func checkZones(_ p: RouteTracker.Progress) {
        guard !demoAlerts else { return }
        let at = p.alongMeters
        let inside = zones.firstIndex { at >= $0.startMeters && at <= $0.endMeters }
        if let entry = zoneEntry, entry.index != inside {
            zoneEntry = nil
            averageCheck = nil
            if AppSettings.shared.cameraWarnings { speak("Average speed check ended.") }
        }
        guard let i = inside else { return }
        let z = zones[i]
        let limit = z.kmh ?? route.speedLimit(at: at)
        if zoneEntry == nil {
            zoneEntry = (i, Date(), at)
            if AppSettings.shared.cameraWarnings {
                chime()
                speak("Average speed check for \(Fmt.spokenDistance(z.endMeters - at))" + (limit.map { ", limit \(Fmt.spokenSpeed($0))." } ?? "."))
            }
        }
        guard let entry = zoneEntry else { return }
        let secs = Date().timeIntervalSince(entry.time)
        let avg = secs > 5 ? (at - entry.along) / secs * 3.6 : speedMps * 3.6
        averageCheck = AverageCheck(limitKmh: limit, averageKmh: avg, metersLeft: z.endMeters - at)
    }

    /** For the build's screenshots: a camera coming up and an average speed check. */
    /** For the build's screenshots: the next turn swapped for a made-up one. */
    private(set) var demoManeuver: Maneuver?

    /** For the build's screenshots: the M27 roundabout at Park Gate, 6th exit, with its seven roads. */
    func showDemoRoundabout() {
        var m = Maneuver(type: .roundabout, instruction: "At the roundabout, take the 6th exit onto M27",
                         location: LatLng(50.87, -1.27), alongMeters: 0, distanceMeters: 400, road: "M27", laneHint: nil)
        m.exit = 6
        m.roundabout = RoundaboutShape(exits: [262, 300, 338, 20, 62, 108], taken: 5, entriesOnly: [150],
                                       clockwise: true, complete: true)
        demoManeuver = m
        objectWillChange.send()
    }

    func showDemoAlerts() {
        demoAlerts = true
        cameraAhead = CameraAlert(kind: .speed, meters: 180, startMeters: 400, limitKmh: 48.3)
        averageCheck = AverageCheck(limitKmh: 80.5, averageKmh: 77, metersLeft: 2400)
    }

    /** Tapping the Waze card puts it away; the report still won't be warned about again. */
    func dismissWaze() { wazeAhead = nil }

    /** For the build's screenshots: police reported up the road. */
    func showDemoWaze() {
        demoAlerts = true
        let here = progress.map { Geo.point(along: route.polyline, meters: $0.alongMeters + 300) } ?? route.polyline.first ?? LatLng(0, 0)
        let a = WazeAlert(id: "demo-police", kind: .police, subtype: "POLICE_HIDING", location: here,
                          street: "A9", reportedAt: Date().addingTimeInterval(-7 * 60), thumbsUp: 3, reliability: 8)
        wazeAhead = WazeAhead(alert: a, meters: 260, startMeters: 600)
        policeRisk = 72
        aircraftNear = (PoliceAircraft(id: "demo-heli", callsign: "NPAS15", location: here, altitudeFeet: 1400, helicopter: true), 3200)
    }

    /** A short tone before a camera warning, the way Waze does it. */
    private func chime() {
        guard !muted else { return }
        AudioServicesPlaySystemSound(1057)
    }

    private func announce(_ p: RouteTracker.Progress) {
        guard let i = p.nextIndex, i < route.maneuvers.count else { return }
        let m = route.maneuvers[i]
        let d = p.distanceToNext
        let stage = spokenStage[i] ?? 0
        let drive = route.mode == .drive
        let now = drive ? max(40, speedMps * 4) : 15
        let near = drive ? max(250, speedMps * 12) : 60
        let far = drive ? max(1000, speedMps * 40) : 250
        if d <= now && stage < 3 {
            spokenStage[i] = 3
            speak(m.instruction)
        } else if d <= near && d > now + 30 && stage < 2 {
            spokenStage[i] = 2
            speak("In \(Fmt.spokenDistance(d)), \(m.instruction.lowercasedFirst)")
        } else if d <= far && d > near + 150 && stage < 1 && m.type != .arrive {
            spokenStage[i] = 1
            speak("In \(Fmt.spokenDistance(d)), \(m.instruction.lowercasedFirst)")
        }
    }

    private func reroute(from loc: CLLocation, quiet: Bool = false) {
        rerouting = true
        lastReroute = Date()
        if !quiet { speak("Rerouting.") }
        let from = LatLng(loc.coordinate.latitude, loc.coordinate.longitude)
        let mode = route.mode
        let to = destination.location
        let hw = avoidHighways, tolls = avoidTolls
        Task {
            let r = await Directions.routes(from: from, to: to, mode: mode, avoidHighways: hw, avoidTolls: tolls,
                                            useGoogle: !AppSettings.shared.noGoogle, via: self.stops.map(\.location))
            self.rerouting = false
            guard let fresh = r.routes.first else { return }
            self.route = fresh
            self.tracker = RouteTracker(route: fresh)
            self.spokenStage = [:]
            self.loadCameras()
            self.loadRoundabouts()
            self.wazeAt = .distantPast
            self.routeWaze = []
            self.wazeAhead = nil
            self.loadWaze()
            if let fix = self.lastFix { self.handle(fix) }
        }
    }

    private func speak(_ text: String) {
        guard !muted else { return }
        try? AVAudioSession.sharedInstance().setActive(true)
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: Locale.preferredLanguages.first?.hasPrefix("en") == true
                                         ? Locale.preferredLanguages.first : "en-US")
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 0.95
        speech.speak(u)
    }
}

extension String {
    var lowercasedFirst: String { prefix(1).lowercased() + dropFirst() }
}

struct CameraAlert: Equatable {
    var kind: RoadCamera.Kind
    /** How far ahead the camera is now. */
    var meters: Double
    /** How far it was when first warned about, so the bar can count down. */
    var startMeters: Double
    /** The limit it checks, when known. */
    var limitKmh: Double?
}

struct WazeAhead: Equatable {
    var alert: WazeAlert
    var meters: Double
    var startMeters: Double
}

struct AverageCheck: Equatable {
    var limitKmh: Double?
    var averageKmh: Double
    var metersLeft: Double
}
