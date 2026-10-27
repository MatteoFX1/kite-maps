import AudioToolbox
import AVFoundation
import CallKit
import CoreLocation
import Foundation
import OSLog
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
    /** Traffic lights on the route, one per junction, drawn on the map like Waze. */
    @Published private(set) var routeSignals: [LatLng] = []

    let destination: Place
    /** Stops still ahead, in trip order; each is dropped once reached. */
    @Published private(set) var stops: [Place]
    private var tracker: RouteTracker
    private let speech = AVSpeechSynthesizer()
    private let speechDone = SpeechDone()
    private var rerouteSpokenAt = Date.distantPast
    private var toldNoSignal = false
    private var spokenStage: [Int: Int] = [:]
    private var stoppedNearEnd: Date?
    private static let log = Logger(subsystem: "app.vela.ios", category: "voice")
    private let calls = CXCallObserver()
    /** A phone (or FaceTime, WhatsApp...) call is going on. */
    private var onCall: Bool { calls.calls.contains { !$0.hasEnded } }
    /** "left" or "right": which side of the road the place is, at the end of the route. */
    private var destinationSide: String? { route.mode == .drive ? Geo.sideAtEnd(destination.location, of: route.polyline) : nil }
    /** The whole route's road data has come in live, so what's remembered no longer stands in. */
    private var fullRoadData = false
    /** The limits came from the road matcher, which knows every road on the route. */
    private var matchedLimits = false
    /** Called a few minutes after arriving, to end the trip. */
    var onFinished: (() -> Void)?
    /** Steps already given a "Continue for 7 miles". */
    private var toldContinue: Set<Int> = []
    private var lastReroute = Date.distantPast
    /** Counts reroutes, so only the newest answer is used (adding a stop while one is loading). */
    private var rerouteRequest = 0
    /** Set once the trip is ended, so a late answer never talks or moves anything. */
    private var stopped = false
    private var avoidHighways: Bool
    private var avoidTolls: Bool
    private var routeCameras: [(camera: RoadCamera, along: Double)] = []
    /** Cameras already warned about, by OSM id: the list is replaced as road data comes in, so not by place in it. */
    private var spokenCameras: Set<Int64> = []
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
        speech.delegate = speechDone
        let first = route.maneuvers.first { $0.type != .depart } ?? route.maneuvers.first
        loadCameras()
        loadWaze()
        loadRoundabouts()
        speak("Starting route to \(destination.name). " + (first.map { "\($0.instruction)." } ?? ""))
        // That was the heads-up for the first step: don't say it again a moment later.
        if let f = first, f.alongMeters < 1500, let i = route.maneuvers.firstIndex(of: f) { spokenStage[i] = f.alongMeters < 300 ? 2 : 1 }
    }

    func stop() {
        stopped = true
        speech.stopSpeaking(at: .immediate)
        UIApplication.shared.isIdleTimerDisabled = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /** The posted limit where the user is now, in km/h. */
    var speedLimitKmh: Double? {
        demoLimitKmh ?? route.speedLimit(at: progress?.alongMeters ?? 0)
    }

    var nextManeuver: Maneuver? {
        if let demoManeuver { return demoManeuver }
        guard let i = progress?.nextIndex, i < route.maneuvers.count else { return route.maneuvers.first }
        return route.maneuvers[i]
    }

    /** The road being driven now, for the name under the arrow. */
    var currentRoad: String? {
        // The last step passed, a name change included.
        guard let along = progress?.alongMeters, let i = route.maneuvers.lastIndex(where: { $0.alongMeters <= along + 8 }) else { return nil }
        let road = route.maneuvers[i].road?.trimmingCharacters(in: .whitespaces)
        return road?.isEmpty == false ? road : nil
    }

    /** The one after next, for the "Then" line when two turns come close together. */
    var thenManeuver: Maneuver? {
        guard let i = progress?.nextIndex, let j = route.maneuvers[(i + 1)...].firstIndex(where: { !$0.silent }) else { return nil }
        let a = route.maneuvers[i], b = route.maneuvers[j]
        // Leaving a roundabout is part of the roundabout, not a turn of its own.
        if b.leavesRoundabout { return nil }
        return b.alongMeters - a.alongMeters < 300 ? b : nil
    }

    func handle(_ loc: CLLocation) {
        guard !arrived else { return }
        lastFix = loc
        // The build's screenshots keep their made-up speed.
        if !demoAlerts { speedMps = max(0, loc.speed) }
        let p = tracker.update(LatLng(loc.coordinate.latitude, loc.coordinate.longitude),
                               accuracy: max(loc.horizontalAccuracy, 5), heading: Self.heading(loc))
        progress = p
        // Parked in a car park short of the pin, or on the far side of the building: that's
        // arriving too, or the trip (and full-power GPS) would never end.
        if loc.speed >= 0 && loc.speed < 1, p.remainingMeters < 150 {
            if stoppedNearEnd == nil { stoppedNearEnd = loc.timestamp }
        } else {
            stoppedNearEnd = nil
        }
        let parked = stoppedNearEnd.map { loc.timestamp.timeIntervalSince($0) > 20 } ?? false
        if p.arrived || parked {
            arrived = true
            speak("You have arrived at \(destination.name)" + (destinationSide.map { ", on the \($0)." } ?? "."))
            // Parked, the phone goes in a pocket: let the screen sleep, and a few minutes on, end
            // the trip so full-power GPS doesn't run all day in the background.
            UIApplication.shared.isIdleTimerDisabled = false
            DispatchQueue.main.asyncAfter(deadline: .now() + 180) { [weak self] in
                guard let self, self.arrived, !self.stopped else { return }
                self.onFinished?()
            }
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
        // Kept across a reroute: a camera already warned about isn't warned about again.
        cameraAhead = nil
        zones = []
        zoneEntry = nil
        averageCheck = nil
        routeSignals = []
        let s = AppSettings.shared
        guard route.mode == .drive else { return }
        let line = route.polyline, id = route.id
        let speed = s.speedCameras, plate = s.plateCameras
        fullRoadData = false
        matchedLimits = false
        // Speed limits from the road matcher first: it answers in about a second.
        Task {
            guard let found = await Valhalla.speedLimits(along: line), self.route.id == id, !found.limits.isEmpty else { return }
            self.matchedLimits = true
            self.route.speedLimits = found.limits
            RoadCache.shared.add(ways: found.ways, cameras: [], signals: [])
        }
        // What's already known about these roads shows straight away; the live answer replaces it.
        func useKnown() async {
            let known = await Task.detached(priority: .userInitiated) { () -> (cams: [(camera: RoadCamera, along: Double)], limits: [SpeedLimit], signals: [LatLng]) in
                let k = RoadCache.shared.along(line)
                let cams = CameraAlerts.project(k.cameras.filter { $0.kind == .speed ? speed : plate }, on: line)
                return (cams, k.ways.isEmpty ? [] : Overpass.speedLimits(k.ways, on: line), k.signals)
            }.value
            // Only while the whole route's live answer hasn't come in yet.
            guard self.route.id == id, !self.fullRoadData else { return }
            if !known.limits.isEmpty, !self.matchedLimits { self.route.speedLimits = known.limits }
            self.routeCameras = known.cams
            self.zones = CameraAlerts.averageZones(known.cams)
            self.routeSignals = known.signals
        }
        Task { await useKnown() }
        // A small question about the first few miles gets answered far sooner by a busy server than
        // the whole route (17 s against a minute or a time-out at night), so the limit shows early.
        let cum = Geo.cumulative(line)
        if (cum.last ?? 0) > 8000 {
            let start = Geo.slice(line, from: 0, to: 6000, cumulative: cum)
            Task {
                guard await Overpass.alongRoute(start, speed: speed, plate: plate) != nil else { return }
                await useKnown()
            }
        }
        Task {
            // The public Overpass servers are sometimes busy: try again a couple of times.
            var result = await Overpass.alongRoute(line, speed: speed, plate: plate)
            for _ in 0..<2 where result == nil && self.route.id == id {
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                result = await Overpass.alongRoute(line, speed: speed, plate: plate)
            }
            guard let found = result, self.route.id == id else { return }
            self.fullRoadData = true
            self.routeCameras = CameraAlerts.project(found.cameras, on: line)
            if !self.matchedLimits { self.route.speedLimits = Overpass.speedLimits(found.ways, on: line) }
            self.zones = CameraAlerts.merge(found.zones, CameraAlerts.averageZones(self.routeCameras))
            self.routeSignals = found.signals
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
        let spoken = Set(routeCameras.indices.filter { spokenCameras.contains(routeCameras[$0].camera.id) })
        guard let i = CameraAlerts.due(cameraMeters: meters, traveled: p.alongMeters, speedMps: speedMps, spoken: spoken) else { return }
        // Cameras a few meters apart are one warning.
        for (j, m) in meters.enumerated() where m >= meters[i] && m - meters[i] < 40 { spokenCameras.insert(routeCameras[j].camera.id) }
        let cam = routeCameras[i].camera
        let ahead = meters[i] - p.alongMeters
        let limit = cam.kind == .speed ? (cam.maxspeedKmh ?? route.speedLimit(at: meters[i])) : nil
        cameraAhead = CameraAlert(kind: cam.kind, meters: ahead, startMeters: max(ahead, 1), limitKmh: limit)
        // Spoken for speed cameras only: number plate cameras are everywhere in Britain, and
        // saying each one would bury the turns. Those get the card alone.
        if AppSettings.shared.cameraWarnings, cam.kind == .speed {
            chime()
            speak((cam.average ? "Average speed camera ahead" : "Speed camera ahead") + (limit.map { ", limit \(Fmt.spokenSpeed($0))." } ?? "."))
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
        guard route.mode == .drive, s.wazeOn, s.policeRisk, s.wazePolice else { policeRisk = nil; return }
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
            guard let found, !self.stopped else { return }
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
    private var demoLimitKmh: Double?

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
        // 38 in a 30, so the screenshots show the speed sign gone red.
        showDemoSpeed(limitKmh: 48.3, mph: 38)
    }

    /** For the build's screenshots: a made-up speed and limit. */
    func showDemoSpeed(limitKmh: Double, mph: Double) {
        demoAlerts = true
        demoLimitKmh = limitKmh
        speedMps = mph / 2.23694
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
        guard !muted, !stopped, !onCall else { return }
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
            // Arriving is said once, with the place's name, when it's reached.
            if m.type == .arrive { return }
            // Off a small roundabout seconds after "take the 2nd exit onto ..." was said: nothing new to say.
            if m.leavesRoundabout, let r = route.maneuvers[..<i].last(where: { $0.type == .roundabout }),
               m.alongMeters - r.alongMeters < 150 { return }
            // The turn itself matters more than whatever is still being said.
            speak(m.instruction, urgent: true)
        } else if m.leavesRoundabout {
            // Already said with the roundabout ("take the 3rd exit"): only "exit" itself, as it comes up.
            return
        } else if d <= near && d > now + 30 && stage < 2 {
            spokenStage[i] = 2
            // Which lane, like the banner shows, so it's known without looking.
            let lane = LaneGuide.lanes(for: m, leftTraffic: Geo.drivesOnLeft(m.location)).map { g -> String in
                g.words.hasPrefix("Lane") ? ". Use " + g.words.lowercased().replacingOccurrences(of: "from left", with: "from the left")
                    : ". Use the \(g.words.lowercased())"
            } ?? ""
            // "In 200 yards, your destination is on the left", the way Google says it.
            let words = m.type == .arrive ? destinationSide.map { "your destination is on the \($0)" } ?? m.instruction.lowercasedFirst
                : m.instruction.lowercasedFirst
            speak("In \(Fmt.spokenDistance(d)), \(words)\(lane)")
        } else if d <= far && d > near + 150 && stage < 1 && m.type != .arrive {
            spokenStage[i] = 1
            // On a phone call only what's needed now is said; the early heads-up would talk over it.
            if onCall { return }
            speak("In \(Fmt.spokenDistance(d)), \(m.instruction.lowercasedFirst)")
        } else if drive && d > 3000 && stage < 1 && !toldContinue.contains(i) && justPassed(p) {
            // A long way to the next turn, like after joining a motorway: say so once, the way
            // Google does ("Continue on M27 for 7 miles"), so a quiet stretch doesn't feel lost.
            toldContinue.insert(i)
            if !onCall { speak(continueWords(d)) }
        }
    }

    /** Just past a step the driver was told about (or the start), so "continue" follows on from it. */
    private func justPassed(_ p: RouteTracker.Progress) -> Bool {
        guard let i = p.nextIndex else { return false }
        let last = route.maneuvers[..<i].last { !$0.silent }?.alongMeters ?? 0
        return p.alongMeters - last < 500
    }

    private func continueWords(_ meters: Double) -> String {
        "Continue " + (currentRoad.map { "on \($0) " } ?? "") + "for \(Fmt.spokenDistance(meters))."
    }

    /** Right after a reroute: the first thing to do on the new route, so "Rerouting" isn't the last word. */
    private func sayNewRoute() {
        guard let p = progress, let i = p.nextIndex, i < route.maneuvers.count, (spokenStage[i] ?? 0) == 0,
              !toldContinue.contains(i) else { return }
        let m = route.maneuvers[i], d = p.distanceToNext
        let now = max(40, speedMps * 4), near = max(250, speedMps * 12)
        guard d > now + 30, m.type != .arrive else { return }
        if d > 3000 {
            toldContinue.insert(i)
            speak(continueWords(d))
        } else {
            spokenStage[i] = d <= near ? 2 : 1
            speak("In \(Fmt.spokenDistance(d)), \(m.instruction.lowercasedFirst)")
        }
    }

    /** The way the phone is moving, once it's fast enough (about 9 mph) for that to be trusted. */
    private static func heading(_ loc: CLLocation) -> Double? {
        loc.course >= 0 && loc.speed >= 4 && loc.courseAccuracy >= 0 && loc.courseAccuracy < 45 ? loc.course : nil
    }

    private func reroute(from loc: CLLocation, quiet: Bool = false) {
        rerouting = true
        lastReroute = Date()
        if !Connectivity.shared.online {
            // Without signal there's no new route to get: say so once, not every few seconds.
            if !quiet && !toldNoSignal {
                toldNoSignal = true
                speak("No signal. Keep going, and Kite will find a new route when it's back.")
            }
        } else if !quiet && Date().timeIntervalSince(rerouteSpokenAt) > 45 {
            rerouteSpokenAt = Date()
            speak("Rerouting.")
        }
        let from = LatLng(loc.coordinate.latitude, loc.coordinate.longitude)
        // Only while driving: on foot or a bike, turning round is fine.
        let heading = route.mode == .drive ? Self.heading(loc) : nil
        let mode = route.mode
        let to = destination.location
        let hw = avoidHighways, tolls = avoidTolls
        rerouteRequest += 1
        let req = rerouteRequest
        Task {
            let r = await Directions.routes(from: from, to: to, mode: mode, avoidHighways: hw, avoidTolls: tolls,
                                            useGoogle: !AppSettings.shared.noGoogle, via: self.stops.map(\.location),
                                            heading: heading)
            guard !self.stopped, req == self.rerouteRequest else { return }
            self.rerouting = false
            guard let fresh = r.routes.first else { return }
            self.toldNoSignal = false
            self.route = fresh
            self.tracker = RouteTracker(route: fresh)
            self.spokenStage = [:]
            self.toldContinue = []
            self.loadCameras()
            self.loadRoundabouts()
            self.wazeAt = .distantPast
            self.routeWaze = []
            self.wazeAhead = nil
            self.loadWaze()
            if let fix = self.lastFix { self.handle(fix) }
            if !quiet { self.sayNewRoute() }
        }
    }

    private func speak(_ text: String, urgent: Bool = false) {
        guard !muted, !stopped else { return }
        if urgent && speech.isSpeaking { speech.stopSpeaking(at: .word) }
        try? AVAudioSession.sharedInstance().setActive(true)
        let said = SpokenText.forVoice(text)
        // What was said, for checking a drive afterwards (Console, or `log stream` on a simulator).
        Self.log.info("say: \(said, privacy: .public)")
        let u = AVSpeechUtterance(string: said)
        u.voice = Self.voice
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 0.95
        speech.speak(u)
    }

    /** The phone's English voice for its region, the better-sounding download when there is one. */
    static let voice: AVSpeechSynthesisVoice? = {
        let lang = Locale.preferredLanguages.first.flatMap { $0.hasPrefix("en") ? $0 : nil } ?? (Fmt.yards ? "en-GB" : "en-US")
        let better = AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language == lang && $0.quality != .default }
            .max { $0.quality.rawValue < $1.quality.rawValue }
        return better ?? AVSpeechSynthesisVoice(language: lang)
    }()
}

/** Hands the sound back once Kite stops talking, so music comes back up between instructions. */
final class SpeechDone: NSObject, AVSpeechSynthesizerDelegate {
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) { release(synthesizer) }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) { release(synthesizer) }

    private func release(_ synthesizer: AVSpeechSynthesizer) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            guard !synthesizer.isSpeaking else { return }
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
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
