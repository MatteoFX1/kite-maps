import SwiftUI
import VelaCore

/** Turn-by-turn: the green instruction banner, map buttons, and the ETA bar. */
struct NavigationOverlay: View {
    @ObservedObject var model: AppModel
    @ObservedObject var nav: Navigator
    @State private var showSteps = false
    @State private var showAlong = false
    @State private var confirmEnd = false
    @Environment(\.colorScheme) private var scheme
    @Environment(\.sidePanel) private var panel

    /** "On my way to X, arriving around 5:42 PM (25 min)" with a map link. */
    private var etaText: String {
        let left = nav.progress?.remainingSeconds ?? nav.route.durationSeconds
        let link = nav.destination.shareURL.map { "\n\($0.absoluteString)" } ?? ""
        return "On my way to \(nav.destination.name), arriving around \(Fmt.arrival(left)) (\(Fmt.duration(left)) away).\(link)"
    }

    /** The voice choice just picked, shown by the speaker button for a moment. */
    @State private var voiceNote: VoiceMode?
    /** The warning shown for a few seconds after tapping the "also ahead" pill. */
    @State private var peek: NavAlert?

    /**
     * Every warning that's up, most important first. Only the first gets the big card, like Waze;
     * the rest wait in one small pill. Whatever you'll reach first along the route wins, except a
     * camera while you're over its limit, which always wins (that's the one that costs a fine).
     * An average check and a police aircraft aren't a spot on the road ahead, so they only get the
     * big card when nothing else is up.
     */
    private var alertsUp: [NavAlert] {
        guard !nav.arrived else { return [] }
        var ahead: [(NavAlert, Double)] = []
        if let c = nav.cameraAhead { ahead.append((.camera, c.meters)) }
        if let w = nav.wazeAhead { ahead.append((.waze, w.meters)) }
        var list = ahead.sorted { $0.1 < $1.1 }.map(\.0)
        if let c = nav.cameraAhead, let limit = c.limitKmh ?? nav.speedLimitKmh, nav.speedMps * 3.6 > limit + 3,
           let i = list.firstIndex(of: .camera), i > 0 {
            list.remove(at: i)
            list.insert(.camera, at: 0)
        }
        if nav.averageCheck != nil { list.append(.average) }
        if nav.aircraftNear != nil { list.append(.aircraft) }
        return list
    }

    /** The one warning on the big card. */
    private var mainAlert: NavAlert? {
        let list = alertsUp
        if let peek, list.contains(peek) { return peek }
        return list.first
    }

    /** The police risk chip, only worth a look while driving when it isn't low. */
    private var showRiskChip: Bool {
        guard let risk = nav.policeRisk, !nav.arrived else { return false }
        return PoliceRisk.level(risk) != .low
    }

    /** A chip above the speed badge sits where the road name goes, so the name steps aside for it. */
    private var chipShowing: Bool { showRiskChip }

    var body: some View {
        // One column, so however many warnings are up, they push the map buttons down rather than cover them.
        VStack(spacing: 10) {
            VStack(spacing: 10) {
                if nav.arrived {
                    ArrivedCard(name: nav.destination.name, onPark: nav.route.mode == .drive ? { model.saveParking() } : nil,
                                note: nav.wazeWarned > 0 ? "Waze drivers warned you about \(nav.wazeWarned) report\(nav.wazeWarned == 1 ? "" : "s") on the way" : nil) { model.endNavigation() }
                } else {
                    InstructionBanner(nav: nav)
                        .fixedSize(horizontal: false, vertical: true)
                }
                // One warning at a time; the others wait in the pill under it.
                switch mainAlert {
                case .camera?:
                    if let cam = nav.cameraAhead {
                        CameraCard(alert: cam, speedKmh: nav.speedMps * 3.6, roadLimitKmh: nav.speedLimitKmh)
                            .fixedSize(horizontal: false, vertical: true)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }
                case .waze?:
                    if let w = nav.wazeAhead {
                        WazeCard(ahead: w)
                            .fixedSize(horizontal: false, vertical: true)
                            .onTapGesture { withAnimation { nav.dismissWaze() } }
                            .accessibilityHint("Tap to hide")
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }
                case .average?:
                    if let check = nav.averageCheck { AverageCheckCard(check: check) }
                case .aircraft?:
                    if let air = nav.aircraftNear { AircraftCard(aircraft: air.aircraft, meters: air.meters) }
                case nil:
                    EmptyView()
                }
                let others = alertsUp.filter { $0 != mainAlert }
                if !others.isEmpty {
                    AlsoAheadPill(nav: nav, alerts: others) {
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { peek = others.first }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .transition(.opacity)
                }
                if nav.gpsLost, !nav.arrived {
                    // In a tunnel the speed and the next turn stop moving: say why, so it doesn't look live.
                    Label("GPS signal lost", systemImage: "location.slash.fill")
                        .font(.system(size: 18, weight: .bold))
                        .padding(.horizontal, 18).frame(height: 44)
                        .glass(Capsule())
                } else if nav.rerouting {
                    Label("Rerouting…", systemImage: "arrow.triangle.2.circlepath")
                        .font(.system(size: 18, weight: .bold))
                        .padding(.horizontal, 18).frame(height: 44)
                        .glass(Capsule())
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 4)
            .inSidePanel(panel)
            .animation(.spring(response: 0.35, dampingFraction: 0.8), value: nav.cameraAhead?.startMeters)
            .animation(.spring(response: 0.35, dampingFraction: 0.8), value: nav.wazeAhead?.alert.id)
            .animation(.spring(response: 0.35, dampingFraction: 0.8), value: mainAlert)
            .task(id: peek) {
                // A peek at the next warning lasts a few seconds, then the nearest one comes back.
                guard peek != nil else { return }
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { peek = nil }
            }

            Spacer(minLength: 0)

            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 10) {
                    // A Waze police card already says it louder; the chip comes back when it's gone.
                    // Only worth a look while driving when it isn't low; it would otherwise sit by the road name.
                    if showRiskChip, let risk = nav.policeRisk { PoliceRiskChip(score: risk) }
                    SpeedBadge(mps: nav.speedMps, limitKmh: nav.speedLimitKmh)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 10) {
                    if let note = voiceNote {
                        // Says which of the three the speaker is on now, for a moment.
                        VStack(alignment: .leading, spacing: 2) {
                            Text(note.title).font(.system(size: 18, weight: .bold)).foregroundColor(Vela.accentText(.dark))
                            Text(note.detail).font(.system(size: 15, weight: .medium)).foregroundColor(.white)
                        }
                        .padding(.horizontal, 14).padding(.vertical, 10)
                        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color(hex: 0x0F172A).opacity(0.95)))
                        .shadow(color: .black.opacity(0.3), radius: 6, y: 2)
                        .transition(.opacity.combined(with: .scale(scale: 0.9, anchor: .bottomTrailing)))
                    }
                    MapFab(icon: nav.voiceMode.icon, tint: nav.voiceMode == .alerts ? Vela.accentText(scheme) : .primary, size: 64) {
                        nav.voiceMode = nav.voiceMode.next
                        withAnimation(.easeOut(duration: 0.2)) { voiceNote = nav.voiceMode }
                    }
                    .accessibilityLabel("Voice: \(nav.voiceMode.title)")
                    .accessibilityHint("Switches between voice on, alerts only and voice off")
                }
                .task(id: voiceNote) {
                    guard voiceNote != nil else { return }
                    try? await Task.sleep(nanoseconds: 2_500_000_000)
                    withAnimation(.easeIn(duration: 0.25)) { voiceNote = nil }
                }
            }
            .padding(.horizontal, 16)
            if !nav.following {
                Button { model.recenterNav() } label: {
                    Label("Re-centre", systemImage: "location.north.line.fill")
                        .font(.system(size: 20, weight: .bold))
                        .padding(.horizontal, 26).frame(height: 60)
                        .foregroundColor(Vela.onAccent(scheme))
                        .background(Capsule().fill(Vela.accent(scheme)))
                        .shadow(color: .black.opacity(0.35), radius: 8, y: 3)
                }
                .buttonStyle(.plain)
                // Under the car, which drives beside the side panel when there is one.
                .frame(maxWidth: .infinity)
                .padding(.leading, panel)
            }
            // Arriving answers the question: the trip bar's Done takes over.
            if confirmEnd, !nav.arrived {
                EndTripCard(destination: nav.destination.name,
                            onKeep: { withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { confirmEnd = false } },
                            onEnd: { model.endNavigation() })
                    .inSidePanel(panel)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            } else {
                TripBar(nav: nav, onEnd: {
                    if nav.arrived { model.endNavigation() } else {
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { confirmEnd = true }
                    }
                }, onSteps: { showSteps = true })
                .inSidePanel(panel)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .background(
            // The road you're on, just under the arrow (the map keeps the arrow at this height while following).
            GeometryReader { g in
                if nav.following, !nav.arrived, !chipShowing, let road = nav.currentRoad {
                    Text(road)
                        .font(.system(size: 17, weight: .bold))
                        .lineLimit(1).minimumScaleFactor(0.7)
                        .padding(.horizontal, 14).frame(height: 36)
                        .foregroundColor(.white)
                        .background(Capsule().fill(Color(hex: 0x0F172A).opacity(0.92)))
                        .overlay(Capsule().strokeBorder(Color(hex: 0x2DD4BF).opacity(0.8), lineWidth: 1.5))
                        // Between the speed sign and the mute button, which sit level with it now the car is lower.
                        .frame(maxWidth: panel > 0 ? g.size.width - panel - 80 : g.size.width - 240)
                        .position(x: panel + (g.size.width - panel) / 2, y: NavGlide.carY(g.size.height) + 50)
                }
            }
            .ignoresSafeArea()
        )
        .task {
            // Screenshot run: the end trip question.
            if DemoScript.step == "endtrip" {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                withAnimation { confirmEnd = true }
            }
        }
        .sheet(isPresented: $showAlong) {
            AlongRouteSheet(nav: nav).kiteType()
        }
        .sheet(isPresented: $showSteps) {
            NavigationView {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        // The things that used to be buttons on the map, out of the way until wanted.
                        HStack(spacing: 10) {
                            tripAction("magnifyingglass", "Find on route") {
                                showSteps = false
                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { showAlong = true }
                            }
                            ShareLink(item: etaText) { tripActionLabel("square.and.arrow.up", "Share ETA") }
                                .buttonStyle(.plain)
                            tripAction("arrow.up.left.and.arrow.down.right", "Overview") {
                                showSteps = false
                                model.overviewNav()
                            }
                        }
                        StepsList(route: nav.route, from: nav.progress?.nextIndex ?? 0)
                    }
                    .padding(.horizontal, 16)
                }
                .navigationTitle("Your trip")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showSteps = false } } }
            }
            .kiteType()
        }
    }

    private func tripAction(_ icon: String, _ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { tripActionLabel(icon, title) }.buttonStyle(.plain)
    }

    private func tripActionLabel(_ icon: String, _ title: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon).font(.system(size: 22, weight: .bold))
            Text(title).font(.system(size: 15, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
        }
        .foregroundColor(.primary)
        .frame(maxWidth: .infinity, minHeight: 78)
        .background(RoundedRectangle(cornerRadius: 18).fill(Vela.chip(scheme)))
    }
}

/**
 * The next turn, as big as it can be: the real roundabout drawn with every exit and yours lit,
 * the distance, the road, and below it which lane to be in.
 */
struct InstructionBanner: View {
    @ObservedObject var nav: Navigator

    var body: some View {
        let m = nav.nextManeuver
        let leftTraffic = Geo.drivesOnLeft(m?.location ?? LatLng(0, 0))
        let refs = RoadRefs.find(m?.instruction ?? "")
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 16) {
                if m?.type == .roundabout {
                    RoundaboutDiagram(shape: m?.roundabout, exit: m?.exit ?? 1, clockwise: leftTraffic)
                        .frame(width: 100, height: 100)
                } else {
                    Image(systemName: maneuverSymbol(m?.type ?? .straight))
                        .font(.system(size: 62, weight: .heavy))
                        .frame(width: 100, height: 100)
                }
                VStack(alignment: .leading, spacing: 4) {
                    if let p = nav.progress {
                        Text(Fmt.distance(p.distanceToNext))
                            .font(.system(size: 46, weight: .heavy))
                            .monospacedDigit()
                            .lineLimit(1).minimumScaleFactor(0.6)
                    }
                    if let parts = Self.split(Self.headline(m)) {
                        // "At the end of the road, turn left" small, the road you turn onto big, like Waze.
                        Text(parts.0)
                            .font(.system(size: 17, weight: .semibold)).opacity(0.9)
                            .lineLimit(1).minimumScaleFactor(0.75)
                        Text(parts.1)
                            .font(.system(size: 25, weight: .heavy))
                            .lineLimit(2).minimumScaleFactor(0.7)
                    } else {
                        Text(Self.headline(m))
                            .font(.system(size: 23, weight: .bold))
                            .lineLimit(2)
                            .minimumScaleFactor(0.75)
                    }
                    if !refs.isEmpty {
                        HStack(spacing: 6) { ForEach(refs, id: \.self) { RoadShield(ref: $0) } }
                            .padding(.top, 2)
                    } else if m?.type == .roundabout, let road = m?.road {
                        Text("onto \(road)").font(.system(size: 18, weight: .semibold)).opacity(0.9).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            if let m, let p = nav.progress, p.distanceToNext < 1_500, let lanes = LaneGuide.lanes(for: m, leftTraffic: leftTraffic) {
                HStack(spacing: 14) {
                    LaneStrip(lanes: lanes.lanes, turn: m.type, exitAngle: m.roundabout.map { $0.exits[$0.taken] })
                    Text(lanes.words).font(.system(size: 19, weight: .bold)).lineLimit(2).minimumScaleFactor(0.8)
                    Spacer(minLength: 0)
                    thenView
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Vela.navBand)
            } else if nav.thenManeuver != nil {
                HStack { Spacer(); thenView }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Vela.navBand)
            }
        }
        .foregroundColor(.white)
        .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Vela.navGreen))
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .shadow(color: .black.opacity(0.4), radius: 12, y: 5)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var thenView: some View {
        if let then = nav.thenManeuver {
            HStack(spacing: 6) {
                Text("Then").font(.system(size: 16, weight: .bold)).opacity(0.85)
                Image(systemName: maneuverSymbol(then.type)).font(.system(size: 20, weight: .heavy))
                if then.type == .roundabout, let n = then.exit {
                    // Which exit, so the next roundabout is known before the banner changes.
                    Text("\(n)").font(.system(size: 14, weight: .heavy)).foregroundColor(.white)
                        .frame(minWidth: 22, minHeight: 22).background(Circle().fill(Color(hex: 0x0F766E)))
                }
            }
        }
    }

    /**
     * A long instruction split into what to do and where: "At the end of the road, turn left onto
     * Burgess Road" is "At the end of the road, turn left" and "Burgess Road". Short ones stay whole.
     */
    static func split(_ text: String) -> (String, String)? {
        guard text.count > 26 else { return nil }
        for word in [" onto ", " towards ", " toward "] {
            guard let r = text.range(of: word, options: .backwards) else { continue }
            let place = text[r.upperBound...].trimmingCharacters(in: .whitespaces)
            // "Take the exit toward" keeps its last word, so it still reads as a sentence.
            let action = word == " onto " ? String(text[..<r.lowerBound]) : String(text[..<r.upperBound]).trimmingCharacters(in: .whitespaces)
            guard !place.isEmpty, action.count >= 4 else { continue }
            return (action, place)
        }
        return nil
    }

    /** "6th exit" for a roundabout (the picture says the rest), else the instruction. */
    static func headline(_ m: Maneuver?) -> String {
        guard let m else { return "Follow the route" }
        if m.type == .roundabout, let n = m.exit { return "\(ordinal(n)) exit" }
        return SpokenText.forScreen(m.instruction)
    }

    static func ordinal(_ n: Int) -> String {
        switch n % 100 {
        case 11, 12, 13: return "\(n)th"
        default: break
        }
        switch n % 10 {
        case 1: return "\(n)st"
        case 2: return "\(n)nd"
        case 3: return "\(n)rd"
        default: return "\(n)th"
        }
    }
}

/** Which lanes to show under the turn: the mapped ones, or at a roundabout with none mapped, the Highway Code's. */
enum LaneGuide {
    static func lanes(for m: Maneuver, leftTraffic: Bool) -> (lanes: [Lane], words: String)? {
        if m.lanes.count > 1 { return named(m.lanes) }
        // Google's own advice ("Use the right lane") where the open map has no lanes: words only.
        if let hint = m.laneHint, !hint.localizedCaseInsensitiveContains("any lane"), m.type != .roundabout {
            let words = hint.replacingOccurrences(of: "use the ", with: "", options: .caseInsensitive)
            return ([], words.prefix(1).uppercased() + words.dropFirst())
        }
        // The road's painted arrows, Google's advice, or the Highway Code's lanes, as many as the road in has.
        guard m.type == .roundabout, !m.leavesRoundabout, let lanes = m.roundabout?.ruleLanes(leftTraffic: leftTraffic, hint: m.laneHint) else { return nil }
        return named(lanes)
    }

    /** The lanes with words for the good ones, by the edge they touch: two of three on the left are "Left 2 lanes", not "Middle". */
    static func named(_ lanes: [Lane]) -> (lanes: [Lane], words: String)? {
        let valid = lanes.enumerated().filter(\.element.valid).map(\.offset)
        guard let first = valid.first, let last = valid.last, valid.count < lanes.count else { return nil }
        let n = lanes.count, count = valid.count, many = count > 1 ? "\(count) lanes" : "lane"
        let words: String
        if first == 0 { words = "Left \(many)" }
        else if last == n - 1 { words = "Right \(many)" }
        else if count == 1 && n > 3 { words = "Lane \(first + 1) from left" }
        else { words = count > 1 ? "Middle lanes" : "Middle lane" }
        return (lanes, words)
    }
}

/** Speed and limit as one sign, the way Waze does it: the limit on top, your speed under it, all red when over. */
struct SpeedBadge: View {
    let mps: Double
    var limitKmh: Double?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let metric = Fmt.metric
        let v = Int((mps * (metric ? 3.6 : 2.23694)).rounded())
        // Red once more than a little over the limit.
        let over = limitKmh.map { mps * 3.6 > $0 + 5 } ?? false
        let speed = VStack(spacing: -2) {
            Text("\(v)").font(.system(size: 36, weight: .heavy)).monospacedDigit()
                .foregroundColor(over ? .white : .primary)
            Text(metric ? "km/h" : "mph").font(.system(size: 14, weight: .semibold))
                .foregroundColor(over ? .white.opacity(0.85) : Vela.muted(scheme))
        }
        Group {
            if let limitKmh {
                VStack(spacing: 6) {
                    SpeedLimitSign(kmh: limitKmh, size: 76)
                    speed
                }
                .padding(.top, 8).padding(.bottom, 12)
                .frame(width: 92)
                .background(Capsule().fill(over ? Vela.closed : Color.clear))
                .glass(Capsule())
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(v) \(metric ? "kilometres" : "miles") an hour" + (over ? ", over the limit" : ""))
            } else {
                speed
                    .frame(width: 84, height: 84)
                    .glass(Circle())
            }
        }
        .animation(.easeOut(duration: 0.25), value: over)
    }
}

/** The posted limit: a US-style white sign in miles, a round red-ringed one in kilometers. */
struct SpeedLimitSign: View {
    let kmh: Double
    var size: CGFloat = 56

    var body: some View {
        if Fmt.metric || Fmt.yards {
            // Round red ring: kilometers on the Continent, miles in Britain.
            Text("\(Fmt.metric ? Int(kmh.rounded()) : Int((kmh / 1.609344 / 5).rounded()) * 5)")
                .font(.system(size: size * 0.4, weight: .heavy))
                .foregroundColor(.black)
                .frame(width: size, height: size)
                .background(Circle().fill(.white))
                .overlay(Circle().inset(by: size * 0.055).stroke(Color(hex: 0xD93025), lineWidth: size * 0.11))
                .shadow(color: .black.opacity(0.3), radius: 5, y: 2)
        } else {
            // OSM limits in the US are mapped in mph; round back to the posted number.
            let mph = Int((kmh / 1.609344 / 5).rounded()) * 5
            VStack(spacing: 0) {
                Text("SPEED\nLIMIT").font(.system(size: size * 0.14, weight: .bold)).multilineTextAlignment(.center)
                Text("\(mph)").font(.system(size: size * 0.4, weight: .heavy))
            }
            .foregroundColor(.black)
            .frame(width: size * 0.86, height: size * 1.07)
            .background(RoundedRectangle(cornerRadius: 6).fill(.white))
            .overlay(RoundedRectangle(cornerRadius: 6).inset(by: 2).stroke(.black, lineWidth: 2))
            .shadow(color: .black.opacity(0.3), radius: 5, y: 2)
        }
    }
}

/** The bottom card while driving: end, time left (big), and the trip menu. */
/** Asks before ending a trip, in place of the trip bar: two big buttons, easy to hit in a phone holder. */
struct EndTripCard: View {
    let destination: String
    let onKeep: () -> Void
    let onEnd: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(spacing: 14) {
            VStack(spacing: 2) {
                Text("End this trip?").font(.system(size: 24, weight: .heavy))
                Text("to \(destination)").font(.system(size: 16, weight: .medium))
                    .foregroundColor(Vela.muted(scheme)).lineLimit(1)
            }
            HStack(spacing: 12) {
                Button(action: onKeep) {
                    Text("Keep going").font(.system(size: 20, weight: .bold))
                        .frame(maxWidth: .infinity).frame(height: 60)
                        .foregroundColor(Vela.onAccent(scheme))
                        .background(Capsule().fill(Vela.accent(scheme)))
                }
                Button(action: onEnd) {
                    Text("End trip").font(.system(size: 20, weight: .bold))
                        .frame(maxWidth: .infinity).frame(height: 60)
                        .foregroundColor(.white)
                        .background(Capsule().fill(Vela.closed))
                }
            }
            .buttonStyle(.plain)
        }
        .foregroundColor(.primary)
        .padding(18)
        .glass(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }
}

struct TripBar: View {
    @ObservedObject var nav: Navigator
    let onEnd: () -> Void
    let onSteps: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let remaining = nav.progress?.remainingSeconds ?? nav.route.durationSeconds
        let meters = nav.progress?.remainingMeters ?? nav.route.distanceMeters
        HStack(spacing: 12) {
            Button(action: onEnd) {
                Image(systemName: "xmark")
                    .font(.system(size: 24, weight: .heavy))
                    .foregroundColor(scheme == .dark ? Color(hex: 0xFCA5A5) : Vela.closed)
                    .frame(width: 64, height: 64)
                    .background(Circle().fill(scheme == .dark ? Color(hex: 0x3B1820) : Color(hex: 0xFEE2E2)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("End trip")
            // Three big numbers side by side, like Waze: when you get there, minutes left, miles left.
            // The arrival time turns orange (or red) when traffic is making you late.
            let arrive = TripBar.arrivalParts(remaining)
            let left = TripBar.timeParts(remaining)
            let dist = TripBar.distanceParts(meters)
            // The arrival time gets a wider share, since "10:42 pm" is longer than "8" or "2.1".
            GeometryReader { g in
                HStack(spacing: 0) {
                    stat(Text(arrive.time) + Text(arrive.suffix).font(.system(size: 16, weight: .bold)),
                         "arrive", TripBody.delayColor(nav.route, scheme))
                        .frame(width: g.size.width * 0.42)
                    stat(Text(left.value), left.unit, .primary).frame(width: g.size.width * 0.29)
                    stat(Text(dist.value), dist.unit, .primary).frame(width: g.size.width * 0.29)
                }
                .frame(maxHeight: .infinity)
            }
            .frame(height: 60)
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Arrive at \(Fmt.arrival(remaining)), \(Fmt.duration(remaining)), \(Fmt.distance(meters)) to go")
            Button(action: onSteps) {
                Image(systemName: "list.bullet")
                    .font(.system(size: 24, weight: .bold))
                    .frame(width: 64, height: 64)
                    .background(Circle().fill(Vela.chip(scheme)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Trip and steps")
        }
        .foregroundColor(.primary)
        .padding(.horizontal, 16)
        .frame(height: 100)
        .glass(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    private func stat(_ big: Text, _ label: String, _ color: Color) -> some View {
        VStack(spacing: 0) {
            // Same height for all three, so the labels underneath line up even when one number shrinks to fit.
            big.font(.system(size: 30, weight: .heavy)).foregroundColor(color)
                .lineLimit(1).minimumScaleFactor(0.5)
                .frame(height: 38, alignment: .bottom)
            Text(label).font(.system(size: 14, weight: .medium)).foregroundColor(Vela.muted(scheme))
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }

    /** "9:18" and " pm" (nothing on a 24 hour clock), so the time stays big and the pm small. */
    static func arrivalParts(_ s: Double) -> (time: String, suffix: String) {
        let f = DateFormatter()
        f.timeStyle = .short
        let full = f.string(from: Date().addingTimeInterval(s))
        for sym in [f.amSymbol, f.pmSymbol].compactMap({ $0 }) where !sym.isEmpty && full.contains(sym) {
            let time = full.replacingOccurrences(of: sym, with: "").trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "\u{202F}")))
            return (time, " " + sym.lowercased())
        }
        return (full, "")
    }

    /** "8" min, or "1:20" hr once it's an hour or more. */
    static func timeParts(_ s: Double) -> (value: String, unit: String) {
        let mins = max(1, Int((s / 60).rounded()))
        if mins < 60 { return ("\(mins)", "min") }
        return (String(format: "%d:%02d", mins / 60, mins % 60), "hr")
    }

    /** "2.1" and "mi" from "2.1 mi". */
    static func distanceParts(_ m: Double) -> (value: String, unit: String) {
        let s = Fmt.distance(m)
        guard let i = s.lastIndex(of: " ") else { return (s, "") }
        return (String(s[..<i]), String(s[s.index(after: i)...]))
    }
}

struct ArrivedCard: View {
    let name: String
    var onPark: (() -> Void)?
    var note: String?
    let done: () -> Void
    @State private var parked = false

    var body: some View {
        // The whole card on one row when it fits; on a narrow screen the flag goes first, then the
        // buttons move under the words, so "You've arrived" and the name are never cut short.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 14) { flag; words(fit: true); Spacer(minLength: 0); buttons }
            HStack(spacing: 14) { words(fit: true); Spacer(minLength: 0); buttons }
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 14) { flag; words(fit: false) }
                HStack(spacing: 14) { Spacer(minLength: 0); buttons }
            }
        }
        .foregroundColor(.white)
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Vela.navGreen))
        .shadow(color: .black.opacity(0.4), radius: 12, y: 5)
    }

    private var flag: some View {
        Image(systemName: "flag.checkered").font(.system(size: 38, weight: .bold))
    }

    /** [fit]: at full width or not at all, so the row is only used when nothing is cut short. */
    private func words(fit: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("You've arrived").font(.system(size: 26, weight: .heavy)).lineLimit(1).minimumScaleFactor(fit ? 1 : 0.7)
                .fixedSize(horizontal: fit, vertical: false)
            Text(name).font(.system(size: 18, weight: .semibold)).lineLimit(fit ? 1 : 3)
                .fixedSize(horizontal: fit, vertical: !fit)
            if let note { Text(note).font(.subheadline).opacity(0.85).lineLimit(2) }
        }
    }

    private var buttons: some View {
        HStack(spacing: 14) {
            if let onPark, !parked {
                Button { onPark(); parked = true } label: {
                    Image(systemName: "parkingsign").font(.system(size: 22, weight: .bold))
                        .frame(width: 56, height: 56)
                        .background(Circle().fill(Color.white.opacity(0.22)))
                }
                .accessibilityLabel("Save parking")
            }
            Button(action: done) {
                Text("Done").font(.system(size: 20, weight: .bold)).lineLimit(1).fixedSize()
                    .padding(.horizontal, 18).frame(height: 56)
                    .background(Capsule().fill(Color.white.opacity(0.22)))
            }
        }
    }
}

/**
 * The camera warning, loud on purpose: bright orange, a big camera, the distance in huge
 * numbers and the limit it checks. It pulses as it appears, and turns red while you're over.
 */
struct CameraCard: View {
    let alert: CameraAlert
    let speedKmh: Double
    /** The limit by the speed badge: when the camera checks the same one, it isn't shown twice. */
    var roadLimitKmh: Double? = nil
    @State private var pulse = false

    var body: some View {
        let over = alert.limitKmh.map { speedKmh > $0 + 3 } ?? false
        let left = min(max(alert.meters / alert.startMeters, 0), 1)
        let fill: Color = over ? Vela.closed : (alert.kind == .speed ? Vela.camera : Color(hex: 0x8E44AD))
        // Compact, Waze-style: about half the old card's height and only as wide as it needs,
        // so the road ahead stays in view; the bright color and pulses keep it hard to miss.
        VStack(spacing: 7) {
            HStack(spacing: 10) {
                CameraBadge(kind: alert.kind, size: 42)
                VStack(alignment: .leading, spacing: -2) {
                    Text(Fmt.distance(alert.meters)).font(.system(size: 30, weight: .heavy)).monospacedDigit()
                        .lineLimit(1).minimumScaleFactor(0.7)
                    Text(over ? "Slow down" : alert.kind == .speed ? "Speed camera" : "Plate camera")
                        .font(.system(size: 15, weight: .bold))
                        .lineLimit(1).minimumScaleFactor(0.8)
                }
                if let limit = alert.limitKmh, roadLimitKmh.map({ abs($0 - limit) > 1 }) ?? true {
                    SpeedLimitSign(kmh: limit, size: 50).padding(.leading, 4)
                }
            }
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.3))
                    Capsule().fill(Color.white).frame(width: g.size.width * left)
                }
            }
            .frame(height: 5)
        }
        .foregroundColor(.white)
        .padding(.horizontal, 12).padding(.top, 9).padding(.bottom, 10)
        .fixedSize()
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(fill))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Color.white.opacity(pulse ? 0.9 : 0), lineWidth: 3))
        .shadow(color: fill.opacity(0.55), radius: pulse ? 18 : 8, y: 3)
        .scaleEffect(pulse ? 1.04 : 1)
        .animation(.easeOut(duration: 0.3), value: over)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .task(id: alert.startMeters) {
            // Two quick pulses when it first shows, so it catches the eye.
            for _ in 0..<2 {
                withAnimation(.easeOut(duration: 0.25)) { pulse = true }
                try? await Task.sleep(nanoseconds: 300_000_000)
                withAnimation(.easeIn(duration: 0.3)) { pulse = false }
                try? await Task.sleep(nanoseconds: 300_000_000)
            }
        }
    }
}

/** A Waze report on the road ahead, as loud as the camera card, in the report's own color. */
struct WazeCard: View {
    let ahead: WazeAhead

    var body: some View {
        let a = ahead.alert
        let left = min(max(ahead.meters / ahead.startMeters, 0), 1)
        let detail = [a.reportedText, a.thumbsUp > 0 ? "\(a.thumbsUp) 👍" : nil, a.street].compactMap { $0 }.joined(separator: " · ")
        let fill = Color(uiColor: MapIcons.wazeColor(a.kind))
        VStack(spacing: 10) {
            HStack(spacing: 14) {
                WazeBadge(kind: a.kind, size: 58)
                VStack(alignment: .leading, spacing: 1) {
                    Text(Fmt.distance(ahead.meters)).font(.system(size: 38, weight: .heavy)).monospacedDigit()
                        .lineLimit(1).minimumScaleFactor(0.7)
                    Text(a.title).font(.system(size: 21, weight: .bold)).lineLimit(1).minimumScaleFactor(0.7)
                    Text(detail).font(.system(size: 15, weight: .medium)).opacity(0.9).lineLimit(1)
                }
                Spacer(minLength: 4)
                Text("Waze").font(.system(size: 13, weight: .heavy)).opacity(0.8)
            }
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.3))
                    Capsule().fill(Color.white).frame(width: g.size.width * left)
                }
            }
            .frame(height: 8)
        }
        .foregroundColor(.white)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(fill))
        .shadow(color: fill.opacity(0.5), radius: 12, y: 4)
        .accessibilityElement(children: .combine)
    }
}

extension WazeAlert {
    /** "Reported 12 min ago". */
    var reportedText: String {
        let mins = max(Int(Date().timeIntervalSince(reportedAt) / 60), 0)
        let when = mins < 1 ? "just now" : mins < 60 ? "\(mins) min ago" : mins < 1440 ? "\(mins / 60) h ago" : "\(mins / 1440) d ago"
        return "Reported \(when)"
    }
}

/** A Waze report tapped on the map: what, where, how fresh, how many drivers agree. */
struct WazeInfoCard: View {
    let alert: WazeAlert
    let close: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            WazeBadge(kind: alert.kind, size: 40)
            VStack(alignment: .leading, spacing: 3) {
                Text(alert.title).font(.headline)
                Text([alert.street, alert.reportedText].compactMap { $0 }.joined(separator: " · "))
                    .font(.subheadline).foregroundColor(.secondary)
                HStack(spacing: 10) {
                    if alert.thumbsUp > 0 {
                        Label("\(alert.thumbsUp) confirmed", systemImage: "hand.thumbsup.fill")
                    }
                    // Waze's 0-10 trust in the report, as five dots.
                    HStack(spacing: 3) {
                        ForEach(0..<5, id: \.self) { i in
                            Circle().fill(i < (alert.reliability + 1) / 2 ? Color(uiColor: MapIcons.wazeColor(alert.kind)) : Color.secondary.opacity(0.25))
                                .frame(width: 6, height: 6)
                        }
                    }
                    .accessibilityLabel("Reliability \(alert.reliability) out of 10")
                    Text("via Waze")
                }
                .font(.caption).foregroundColor(.secondary)
            }
            Spacer(minLength: 0)
            Button(action: close) {
                Image(systemName: "xmark").font(.footnote.weight(.semibold)).foregroundColor(.secondary)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close")
        }
        .padding(14)
        .glass(RoundedRectangle(cornerRadius: 18))
        .padding(.horizontal, 12)
    }
}

/**
 * Before starting a drive: what Waze drivers have reported along this route right now, like
 * "2 police · 1 crash". Quiet when Waze is off or didn't answer.
 */
struct RouteWazeSummary: View {
    let route: Route
    @ObservedObject private var settings = AppSettings.shared
    @State private var counts: [(WazeAlert.Kind, Int)]?
    /** For the build's screenshots, since Waze turns away the build's computers. */
    static var demo: [(WazeAlert.Kind, Int)]?

    var body: some View {
        // A VStack, not a Group: an empty Group has nothing to hang .task on, so it would never ask.
        VStack(alignment: .leading, spacing: 0) {
            if let counts {
                if counts.isEmpty {
                    Label("No road closures on this route", systemImage: "checkmark.shield.fill")
                        .font(.system(size: 16, weight: .semibold)).foregroundColor(Vela.open)
                } else {
                    WrapLayout(spacing: 8, lineSpacing: 8) {
                        ForEach(counts, id: \.0) { item in
                            let tint = Color(uiColor: MapIcons.wazeColor(item.0))
                            HStack(spacing: 6) {
                                WazeBadge(kind: item.0, size: 24)
                                Text("\(item.1) \(Self.label(item.0, item.1))").font(.system(size: 16, weight: .bold))
                            }
                            .padding(.leading, 6).padding(.trailing, 12)
                            .frame(height: 38)
                            .background(Capsule().fill(tint.opacity(0.18)))
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .task(id: route.id) {
            if let demo = Self.demo { counts = demo; return }
            guard settings.wazeOn, route.mode == .drive else { counts = nil; return }
            counts = nil
            guard let found = await Waze.alongRoute(route.polyline) else { return }
            let on = Waze.project(found, on: route.polyline).map(\.alert).filter { settings.shows($0) }
            let grouped = Dictionary(grouping: on, by: \.kind)
            counts = WazeAlert.Kind.allCases.compactMap { k in grouped[k].map { (k, $0.count) } }
        }
    }

    static func label(_ k: WazeAlert.Kind, _ n: Int) -> String {
        switch k {
        case .police: return "police"
        case .accident: return n == 1 ? "crash" : "crashes"
        case .hazard: return n == 1 ? "hazard" : "hazards"
        case .weather: return "weather"
        case .roadworks: return "roadworks"
        case .closure: return n == 1 ? "closure" : "closures"
        case .jam: return n == 1 ? "jam" : "jams"
        }
    }
}

/** The heat map's key, top left of the map while it's on: yellow for a few police reports, red for many. */
struct HeatLegend: View {
    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "flame.fill").font(.system(size: 11, weight: .bold)).foregroundColor(Color(hex: 0xDC2626))
            Text("Police").font(.caption2.weight(.semibold))
            LinearGradient(colors: [Color(hex: 0xFFD600), Color(hex: 0xF97316), Color(hex: 0xDC2626)],
                           startPoint: .leading, endPoint: .trailing)
                .frame(width: 54, height: 6).clipShape(Capsule())
            Text("often").font(.caption2).foregroundColor(.secondary)
        }
        .padding(.horizontal, 10).frame(height: 28)
        .glass(Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Police heat map on: red where police are reported most often")
    }
}

/** "Police risk: High" over the speed badge, from past and live Waze police reports ahead. */
struct PoliceRiskChip: View {
    let score: Int

    static func color(_ level: PoliceRisk.Level) -> Color {
        switch level {
        case .high: return Color(hex: 0xDC2626)
        case .medium: return Color(hex: 0xF59E0B)
        case .low: return Color(hex: 0x16A34A)
        }
    }

    var body: some View {
        let level = PoliceRisk.level(score)
        let color = Self.color(level)
        HStack(spacing: 8) {
            Image(systemName: "shield.lefthalf.filled").font(.system(size: 17, weight: .bold))
            Text("Police risk: \(level.rawValue.capitalized)").font(.system(size: 17, weight: .bold))
        }
        .foregroundColor(level == .medium ? Color(hex: 0x1F1300) : .white)
        .padding(.horizontal, 14).frame(height: 42)
        .background(Capsule().fill(color))
        .shadow(color: .black.opacity(0.35), radius: 8, y: 3)
        .accessibilityLabel("Police risk \(level.rawValue), \(score) out of 100")
    }
}

/** The warnings that can take the big card while driving. */
enum NavAlert: Equatable { case camera, waze, average, aircraft }

/** "Also ahead": the warnings waiting their turn, small, in one pill. Tap to peek at the next one. */
struct AlsoAheadPill: View {
    @ObservedObject var nav: Navigator
    let alerts: [NavAlert]
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            // Two at most, with "+1" for any more. On a small phone the words "Also ahead" go first,
            // then the second warning, so the pill never runs off the screen.
            ViewThatFits(in: .horizontal) {
                row(label: true, shown: 2)
                row(label: false, shown: 2)
                row(label: false, shown: 1)
            }
            .foregroundColor(.white)
            .padding(.horizontal, 16).frame(height: 44)
            .background(Capsule().fill(Color(hex: 0x0F172A).opacity(0.92)))
            .overlay(Capsule().strokeBorder(Color.white.opacity(0.14), lineWidth: 1))
            .shadow(color: .black.opacity(0.3), radius: 6, y: 2)
        }
        .buttonStyle(.plain)
        .accessibilityHint("Shows the next warning")
    }

    private func row(label: Bool, shown: Int) -> some View {
        HStack(spacing: 12) {
            if label { Text("Also ahead").font(.system(size: 15, weight: .semibold)).foregroundColor(.white.opacity(0.75)) }
            ForEach(alerts.prefix(shown), id: \.self) { item($0) }
            if alerts.count > shown { Text("+\(alerts.count - shown)").font(.system(size: 16, weight: .heavy)) }
        }
        .lineLimit(1)
        .fixedSize()
    }

    @ViewBuilder
    private func item(_ a: NavAlert) -> some View {
        HStack(spacing: 6) {
            switch a {
            case .camera:
                if let c = nav.cameraAhead {
                    CameraBadge(kind: c.kind, size: 24)
                    Text(Fmt.distance(c.meters))
                }
            case .waze:
                if let w = nav.wazeAhead {
                    WazeBadge(kind: w.alert.kind, size: 24)
                    Text(Fmt.distance(w.meters))
                }
            case .average:
                if let c = nav.averageCheck {
                    Image(systemName: "gauge.with.dots.needle.33percent").font(.system(size: 17, weight: .bold))
                    Text("Avg \(Fmt.speed(c.averageKmh)) \(Fmt.speedUnit)")
                }
            case .aircraft:
                if let air = nav.aircraftNear {
                    Image(systemName: air.aircraft.helicopter ? "fanblades.fill" : "airplane").font(.system(size: 17, weight: .bold))
                    Text(Fmt.distance(air.meters))
                }
            }
        }
        .font(.system(size: 16, weight: .bold)).monospacedDigit()
        .lineLimit(1)
    }
}

/** The aircraft card made small, for when the screen is already full of warnings. */
struct AircraftChip: View {
    let aircraft: PoliceAircraft
    let meters: Double

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: aircraft.helicopter ? "fanblades.fill" : "airplane").font(.system(size: 17, weight: .bold))
            Text("\(aircraft.title) · \(Fmt.distance(meters))").font(.system(size: 16, weight: .bold)).lineLimit(1)
        }
        .foregroundColor(.white)
        .padding(.horizontal, 14).frame(height: 40)
        .background(Capsule().fill(Color(hex: 0x1E88E5)))
        .shadow(color: .black.opacity(0.25), radius: 3, y: 1)
        .accessibilityElement(children: .combine)
    }
}

/** A police plane or helicopter nearby: what, how far, how high. */
struct AircraftCard: View {
    let aircraft: PoliceAircraft
    let meters: Double
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let detail = [aircraft.altitudeFeet.map { !Fmt.metric ? "\(Int($0)) ft up" : "\(Int($0 * 0.3048)) m up" },
                      aircraft.callsign ?? aircraft.registration].compactMap { $0 }.joined(separator: " · ")
        HStack(spacing: 12) {
            Image(systemName: aircraft.helicopter ? "fanblades.fill" : "airplane")
                .font(.system(size: 20, weight: .bold)).foregroundColor(.white)
                .frame(width: 40, height: 40)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x1E88E5)))
            VStack(alignment: .leading, spacing: 1) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(Fmt.distance(meters)).font(.system(size: 22, weight: .bold)).monospacedDigit()
                    Text(aircraft.title).font(.headline)
                }
                if !detail.isEmpty { Text(detail).font(.subheadline).foregroundColor(.secondary).lineLimit(1) }
            }
            Spacer(minLength: 4)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18).fill(Vela.sheet(scheme))
            .shadow(color: .black.opacity(0.25), radius: 4, y: 2))
        .accessibilityElement(children: .combine)
    }
}

struct WazeBadge: View {
    let kind: WazeAlert.Kind
    var size: CGFloat = 28

    var body: some View {
        Image(uiImage: MapIcons.waze(kind)).resizable().frame(width: size, height: size)
    }
}

/** Inside an average speed check: your average so far against the limit, and how much is left. */
struct AverageCheckCard: View {
    let check: AverageCheck
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let over = check.limitKmh.map { check.averageKmh > $0 + 2 } ?? false
        HStack(spacing: 12) {
            Image(systemName: "gauge.with.dots.needle.33percent")
                .font(.system(size: 26, weight: .semibold))
                .foregroundColor(over ? .white : Color(hex: 0xF9AB00))
            VStack(alignment: .leading, spacing: 1) {
                Text("Average speed check").font(.subheadline.weight(.semibold))
                Text("\(Fmt.distance(max(check.metersLeft, 0))) left").font(.caption)
                    .foregroundColor(over ? .white.opacity(0.9) : .secondary)
            }
            Spacer(minLength: 4)
            VStack(spacing: -2) {
                Text("\(Fmt.speed(check.averageKmh))").font(.system(size: 24, weight: .bold)).monospacedDigit()
                Text("avg \(Fmt.speedUnit)").font(.system(size: 10, weight: .medium))
                    .foregroundColor(over ? .white.opacity(0.9) : .secondary)
            }
            if let limit = check.limitKmh { SpeedLimitSign(kmh: limit) }
        }
        .foregroundColor(over ? .white : .primary)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18).fill(over ? Vela.closed : Vela.sheet(scheme))
            .shadow(color: .black.opacity(0.25), radius: 4, y: 2))
        .accessibilityElement(children: .combine)
    }
}

struct CameraBadge: View {
    let kind: RoadCamera.Kind
    var size: CGFloat = 28

    var body: some View {
        Image(uiImage: kind == .speed ? MapIcons.speedCamera() : MapIcons.plateCamera())
            .resizable()
            .frame(width: size, height: size)
    }
}

/** Find a stop ahead without leaving navigation: gas, food, coffee… near the road still to drive. */
struct AlongRouteSheet: View {
    @ObservedObject var nav: Navigator
    @ObservedObject private var settings = AppSettings.shared
    @Environment(\.dismiss) private var dismiss
    @State private var found: [(place: Place, ahead: Double)] = []
    @State private var searching = false
    @State private var picked: String?

    private let kinds: [(String, String, String)] = [
        (Fmt.yards ? "Petrol" : "Gas", "fuelpump.fill", Fmt.yards ? "petrol station" : "gas station"), ("EV charging", "bolt.car.fill", "ev charging station"),
        ("Coffee", "cup.and.saucer.fill", "coffee"), ("Food", "fork.knife", "restaurants"),
        (Fmt.yards ? "Supermarket" : "Groceries", "cart.fill", Fmt.yards ? "supermarket" : "grocery store"),
        (Fmt.yards ? "Toilets" : "Restrooms", "toilet.fill", Fmt.yards ? "public toilets" : "public restroom"),
        ("Parking", "parkingsign", "parking"), ("Pharmacy", "cross.case.fill", "pharmacy"),
    ]

    var body: some View {
        NavigationView {
            List {
                Section {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 8)], spacing: 8) {
                        ForEach(kinds, id: \.0) { k in
                            Button { run(k.2, label: k.0) } label: {
                                Label(k.0, systemImage: k.1)
                                    .font(.subheadline.weight(.semibold))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 12).frame(height: 40)
                                    .background(RoundedRectangle(cornerRadius: 10).fill(picked == k.0 ? Vela.routeBlue.opacity(0.22) : Color.secondary.opacity(0.12)))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .listRowInsets(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))
                }
                if searching {
                    HStack { ProgressView(); Text("Looking along your route…").foregroundColor(.secondary) }
                } else if picked != nil && found.isEmpty {
                    Text("Nothing found close to the road ahead.").foregroundColor(.secondary)
                }
                if !found.isEmpty {
                    Section("Ahead on your route") {
                        ForEach(found, id: \.place.id) { f in
                            Button {
                                nav.addStop(f.place)
                                dismiss()
                            } label: {
                                HStack(spacing: 12) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(f.place.name).foregroundColor(.primary).lineLimit(1)
                                        HStack(spacing: 6) {
                                            if let r = f.place.rating { Text(String(format: "%.1f ★", r)) }
                                            if let st = f.place.statusText { Text(st).lineLimit(1) }
                                        }
                                        .font(.caption).foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    VStack(alignment: .trailing, spacing: 2) {
                                        Text("in \(Fmt.distance(f.ahead))").font(.subheadline.weight(.medium)).foregroundColor(.primary)
                                        Text("Add stop").font(.caption.weight(.semibold)).foregroundColor(Vela.routeBlue)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Search along route")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }

    private func run(_ query: String, label: String) {
        picked = label
        searching = true
        found = []
        let line = nav.route.polyline
        let cum = Geo.cumulative(line)
        let here = nav.progress?.alongMeters ?? 0
        let total = cum.last ?? 0
        // The next stretch of road, up to 40 km: search around its middle, wide enough to cover it.
        let end = min(total, here + 40_000)
        let mid = Geo.point(along: line, meters: (here + end) / 2, cumulative: cum)
        let span = max(end - here, 3_000)
        let useGoogle = !settings.noGoogle
        Task {
            let r = await PlaceSearch.search(query, center: mid, spanMeters: span, useGoogle: useGoogle)
            var out: [(Place, Double)] = []
            for p in r.places {
                let at = Geo.project(p.location, on: line, cumulative: cum)
                // Close to the road (a short detour) and still ahead of you.
                guard at.offset < 800, at.along > here + 100 else { continue }
                out.append((p, at.along - here))
            }
            found = out.sorted { $0.1 < $1.1 }.prefix(20).map { (place: $0.0, ahead: $0.1) }
            searching = false
        }
    }
}

/** Road numbers in an instruction ("I-80", "US 50", "CA 113", "A9"), for shields in the banner. */
enum RoadRefs {
    static func find(_ text: String) -> [String] {
        let pattern = #"\b(I|US|CA|SR|State Route|Route|Hwy|Highway|A|B|M|E|N|D|B|SS)[- ]?(\d{1,4}[A-Z]?)\b"#
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        let ns = text as NSString
        var out: [String] = []
        for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            let net = ns.substring(with: m.range(at: 1)), num = ns.substring(with: m.range(at: 2))
            let ref: String
            switch net {
            case "I": ref = "I-\(num)"
            case "US": ref = "US \(num)"
            case "CA", "SR", "State Route", "Route", "Hwy", "Highway": ref = "SR \(num)"
            default: ref = net + num
            }
            if !out.contains(ref) { out.append(ref) }
        }
        return Array(out.prefix(3))
    }
}

/** A road number drawn like its sign: Interstate blue-and-red, US white, others a plain plate. */
struct RoadShield: View {
    let ref: String

    var body: some View {
        if ref.hasPrefix("I-") {
            let n = String(ref.dropFirst(2))
            ZStack(alignment: .top) {
                Color(hex: 0x003F87)
                Color(hex: 0xC8102E).frame(height: 9)
                Text(n).font(.system(size: 14, weight: .heavy)).foregroundColor(.white).padding(.top, 10)
            }
            .frame(width: n.count > 2 ? 40 : 32, height: 32)
            .mask(InterstateShape())
            .overlay(InterstateShape().stroke(.white, lineWidth: 1.5))
        } else if ref.hasPrefix("US ") {
            Text(String(ref.dropFirst(3)))
                .font(.system(size: 14, weight: .heavy)).foregroundColor(.black)
                .frame(minWidth: 30).frame(height: 30).padding(.horizontal, 2)
                .background(InterstateShape().fill(.white))
                .overlay(InterstateShape().inset(by: 2).stroke(.black, lineWidth: 1.5))
        } else if let uk = Self.british(ref) {
            // British plates: blue for motorways, green with yellow numbers for primary A roads.
            Text(ref)
                .font(.system(size: 14, weight: .heavy)).foregroundColor(uk == .motorway ? .white : Color(hex: 0xFFD200))
                .padding(.horizontal, 7).frame(height: 26)
                .background(RoundedRectangle(cornerRadius: 4).fill(uk == .motorway ? Color(hex: 0x0079C1) : Color(hex: 0x00703C)))
                .overlay(RoundedRectangle(cornerRadius: 4).inset(by: 1.5).stroke(.white, lineWidth: 1))
        } else {
            Text(ref.hasPrefix("SR ") ? String(ref.dropFirst(3)) : ref)
                .font(.system(size: 14, weight: .heavy)).foregroundColor(.black)
                .padding(.horizontal, 7).frame(height: 26)
                .background(RoundedRectangle(cornerRadius: 5).fill(.white))
                .overlay(RoundedRectangle(cornerRadius: 5).inset(by: 1.5).stroke(.black, lineWidth: 1.5))
        }
    }
}

extension RoadShield {
    enum British { case motorway, primary }

    /** "M4" or "A1(M)" are motorways, "A470" a primary road; only where units are British. */
    static func british(_ ref: String) -> British? {
        guard Fmt.yards else { return nil }
        if ref.range(of: #"^(M\d+|A\d+\(M\))$"#, options: .regularExpression) != nil { return .motorway }
        if ref.range(of: #"^A\d+$"#, options: .regularExpression) != nil { return .primary }
        return nil
    }
}

/** A shield outline: flat top with dips, sides curving into a point. */
struct InterstateShape: InsettableShape {
    var inset: CGFloat = 0

    func inset(by amount: CGFloat) -> InterstateShape { var s = self; s.inset += amount; return s }

    func path(in rect: CGRect) -> Path {
        let r = rect.insetBy(dx: inset, dy: inset)
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.minY + r.height * 0.08))
        p.addQuadCurve(to: CGPoint(x: r.midX, y: r.minY + r.height * 0.06), control: CGPoint(x: r.minX + r.width * 0.25, y: r.minY - r.height * 0.04))
        p.addQuadCurve(to: CGPoint(x: r.maxX, y: r.minY + r.height * 0.08), control: CGPoint(x: r.maxX - r.width * 0.25, y: r.minY - r.height * 0.04))
        p.addCurve(to: CGPoint(x: r.midX, y: r.maxY), control1: CGPoint(x: r.maxX + r.width * 0.05, y: r.minY + r.height * 0.6),
                   control2: CGPoint(x: r.midX + r.width * 0.3, y: r.maxY - r.height * 0.05))
        p.addCurve(to: CGPoint(x: r.minX, y: r.minY + r.height * 0.08), control1: CGPoint(x: r.midX - r.width * 0.3, y: r.maxY - r.height * 0.05),
                   control2: CGPoint(x: r.minX - r.width * 0.05, y: r.minY + r.height * 0.6))
        p.closeSubpath()
        return p
    }
}

/** Which lanes to be in, the way Waze and Google draw it: the right ones lit up, the rest dim. */
struct LaneStrip: View {
    let lanes: [Lane]
    let turn: ManeuverType
    /** At a roundabout: where the exit is (degrees clockwise from straight ahead). */
    var exitAngle: Double?

    var body: some View {
        HStack(spacing: 5) {
            ForEach(Array(lanes.prefix(6).enumerated()), id: \.offset) { _, lane in
                Image(systemName: Self.symbol(pick(lane)))
                    .font(.system(size: 26, weight: .heavy))
                    .foregroundColor(lane.valid ? Vela.navBand : .white.opacity(0.4))
                    .frame(width: 48, height: 48)
                    .background(RoundedRectangle(cornerRadius: 12).fill(lane.valid ? Color.white : Color.white.opacity(0.1)))
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Use the highlighted lanes")
    }

    /** A lane that goes several ways shows the way this turn goes when it's a good lane. */
    private func pick(_ lane: Lane) -> String {
        guard lane.valid, lane.indications.count > 1 else { return lane.indications.first ?? "straight" }
        let want: [String]
        var t = turn
        if t == .roundabout, let a = exitAngle {
            t = a > 200 && a < 340 ? .turnLeft : (a > 20 && a < 160 ? .turnRight : .straight)
        }
        switch t {
        case .turnLeft, .sharpLeft, .rampLeft: want = ["left", "sharp left", "slight left"]
        case .slightLeft, .forkLeft, .keepLeft: want = ["slight left", "left"]
        case .turnRight, .sharpRight, .rampRight: want = ["right", "sharp right", "slight right"]
        case .slightRight, .forkRight, .keepRight: want = ["slight right", "right"]
        case .uturn: want = ["uturn"]
        default: want = ["straight"]
        }
        return want.first { lane.indications.contains($0) } ?? lane.indications[0]
    }

    static func symbol(_ indication: String) -> String {
        switch indication {
        case "left": return "arrow.turn.up.left"
        case "sharp left": return "arrow.turn.left.down"
        case "slight left": return "arrow.up.left"
        case "right": return "arrow.turn.up.right"
        case "sharp right": return "arrow.turn.right.down"
        case "slight right": return "arrow.up.right"
        case "uturn": return "arrow.uturn.down"
        default: return "arrow.up"
        }
    }
}

/**
 * The roundabout as it really is: the way in at the bottom, every road around the circle, and
 * only your way round and out lit up. Without the real layout, the exits are spread evenly.
 */
struct RoundaboutDiagram: View {
    let shape: RoundaboutShape?
    let exit: Int
    /** Traffic goes round clockwise (keep-left countries). */
    let clockwise: Bool

    private var layout: RoundaboutShape {
        if let shape, shape.exits.indices.contains(shape.taken) { return shape }
        // Evenly spread up to yours, a little short of the full circle.
        let n = max(exit, 1)
        let step = min(90, 300 / Double(n + 1))
        let sign: Double = clockwise ? 1 : -1
        var exits: [Double] = []
        for k in 1...n {
            let a: Double = 180 + sign * Double(k) * step
            exits.append((a + 360).truncatingRemainder(dividingBy: 360))
        }
        return RoundaboutShape(exits: exits, taken: n - 1, clockwise: clockwise, complete: false)
    }

    var body: some View {
        Canvas { ctx, size in
            let l = layout
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let ring = size.width * 0.2
            let edge = size.width * 0.47
            func pt(_ deg: Double, _ r: CGFloat) -> CGPoint {
                let a: Double = deg * .pi / 180
                let dx = CGFloat(sin(a)) * r
                let dy = CGFloat(cos(a)) * r
                return CGPoint(x: c.x + dx, y: c.y - dy)
            }
            let faint = GraphicsContext.Shading.color(.white.opacity(0.32))
            let road = StrokeStyle(lineWidth: size.width * 0.075, lineCap: .round)
            // Every road and the circle, dim.
            for a in l.exits + l.entriesOnly + [180] {
                var p = Path()
                p.move(to: pt(a, ring))
                p.addLine(to: pt(a, l.entriesOnly.contains(a) ? edge * 0.85 : edge))
                ctx.stroke(p, with: faint, style: road)
            }
            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - ring, y: c.y - ring, width: ring * 2, height: ring * 2)), with: faint, style: road)
            // Yours: in from the bottom, round the way traffic goes, and out.
            let out = l.exits[l.taken]
            let sweep = l.turn(l.taken)
            var path = Path()
            path.move(to: pt(180, edge))
            path.addLine(to: pt(180, ring))
            let steps = max(Int(sweep / 6), 2)
            let sign: Double = l.clockwise ? 1 : -1
            for i in 1...steps {
                let f = Double(i) / Double(steps)
                let a: Double = 180 + sign * sweep * f
                path.addLine(to: pt(a, ring))
            }
            let tip = pt(out, edge - size.width * 0.07)
            path.addLine(to: tip)
            ctx.stroke(path, with: .color(.white), style: StrokeStyle(lineWidth: size.width * 0.1, lineCap: .round, lineJoin: .round))
            // The arrowhead on the way out.
            let a = out * .pi / 180
            let dir = CGPoint(x: CGFloat(sin(a)), y: CGFloat(-cos(a)))
            let side = CGPoint(x: -dir.y, y: dir.x)
            let w = size.width * 0.12, len = size.width * 0.14
            let base = CGPoint(x: tip.x - dir.x * 2, y: tip.y - dir.y * 2)
            let nose = CGPoint(x: base.x + dir.x * len, y: base.y + dir.y * len)
            let left = CGPoint(x: base.x + side.x * w, y: base.y + side.y * w)
            let right = CGPoint(x: base.x - side.x * w, y: base.y - side.y * w)
            var head = Path()
            head.move(to: nose)
            head.addLine(to: left)
            head.addLine(to: right)
            head.closeSubpath()
            ctx.fill(head, with: .color(.white))
            // The exit number in the middle, as well as "6th exit" beside the picture.
            let digits = Text("\(exit)")
                .font(.system(size: size.width * (exit >= 10 ? 0.17 : 0.22), weight: .heavy))
                .foregroundColor(.white)
            ctx.draw(digits, at: c)
        }
        .accessibilityLabel("Roundabout, take exit \(exit)")
    }
}
