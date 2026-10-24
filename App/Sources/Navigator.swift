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
    @Published private(set) var cameraAhead: (kind: RoadCamera.Kind, meters: Double)?

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
    private var speeding = SpeedingAlerts()

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
    private func loadCameras() {
        routeCameras = []
        spokenCameras = []
        cameraAhead = nil
        let s = AppSettings.shared
        guard route.mode == .drive else { return }
        let line = route.polyline, id = route.id
        let speed = s.speedCameras, plate = s.plateCameras
        Task {
            guard let found = await Overpass.alongRoute(line, speed: speed, plate: plate),
                  self.route.id == id else { return }
            self.routeCameras = CameraAlerts.project(found.cameras, on: line)
            self.route.speedLimits = Overpass.speedLimits(found.ways, on: line)
        }
    }

    private func checkCameras(_ p: RouteTracker.Progress) {
        let meters = routeCameras.map(\.along)
        if let ahead = cameraAhead, let next = meters.first(where: { $0 > p.alongMeters }) {
            // Keep the card up until the camera is passed.
            cameraAhead = (ahead.kind, next - p.alongMeters)
        }
        if cameraAhead != nil && !meters.contains(where: { $0 > p.alongMeters && $0 - p.alongMeters < CameraAlerts.maxLead }) {
            cameraAhead = nil
        }
        guard let i = CameraAlerts.due(cameraMeters: meters, traveled: p.alongMeters, speedMps: speedMps, spoken: spokenCameras) else { return }
        // Cameras a few meters apart are one warning.
        for (j, m) in meters.enumerated() where m >= meters[i] && m - meters[i] < 40 { spokenCameras.insert(j) }
        let kind = routeCameras[i].camera.kind
        cameraAhead = (kind, meters[i] - p.alongMeters)
        if AppSettings.shared.cameraWarnings {
            speak(kind == .speed ? "Speed camera ahead." : "License plate camera ahead.")
        }
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
