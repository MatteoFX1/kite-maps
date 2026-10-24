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

    /** "On my way to X, arriving around 5:42 PM (25 min)" with a map link. */
    private var etaText: String {
        let left = nav.progress?.remainingSeconds ?? nav.route.durationSeconds
        let link = nav.destination.shareURL.map { "\n\($0.absoluteString)" } ?? ""
        return "On my way to \(nav.destination.name), arriving around \(Fmt.arrival(left)) (\(Fmt.duration(left)) away).\(link)"
    }

    var body: some View {
        ZStack {
            VStack(spacing: 8) {
                if nav.arrived {
                    ArrivedCard(name: nav.destination.name, onPark: nav.route.mode == .drive ? { model.saveParking() } : nil) { model.endNavigation() }
                } else {
                    InstructionBanner(nav: nav)
                }
                if let cam = nav.cameraAhead, !nav.arrived {
                    CameraCard(alert: cam, speedKmh: nav.speedMps * 3.6)
                }
                if let check = nav.averageCheck, !nav.arrived {
                    AverageCheckCard(check: check)
                }
                if nav.rerouting {
                    Label("Rerouting…", systemImage: "arrow.triangle.2.circlepath")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 14).frame(height: 34)
                        .background(Capsule().fill(Vela.sheet(scheme)).shadow(radius: 3))
                }
                Spacer()
            }
            .padding(.horizontal, 10)
            .padding(.top, 4)

            VStack(spacing: 12) {
                Spacer()
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 8) {
                            SpeedBadge(mps: nav.speedMps, limitKmh: nav.speedLimitKmh)
                            if let limit = nav.speedLimitKmh { SpeedLimitSign(kmh: limit) }
                        }
                        if !nav.following {
                            Button { model.recenterNav() } label: {
                                Label("Re-center", systemImage: "location.north.line.fill")
                                    .font(.subheadline.weight(.semibold))
                                    .padding(.horizontal, 16).frame(height: 42)
                                    .foregroundColor(Vela.onAccent(scheme))
                                    .background(Capsule().fill(Vela.accent(scheme)))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    Spacer()
                    VStack(spacing: 12) {
                        MapFab(icon: "magnifyingglass", size: 48) { showAlong = true }
                            .accessibilityLabel("Search along the route")
                        ShareLink(item: etaText) {
                            Image(systemName: "square.and.arrow.up")
                                .font(.system(size: 19, weight: .semibold))
                                .foregroundColor(.primary)
                                .frame(width: 48, height: 48)
                                .background(Circle().fill(Vela.sheet(scheme)))
                                .shadow(color: .black.opacity(0.25), radius: 4, y: 2)
                        }
                        .accessibilityLabel("Share arrival time")
                        MapFab(icon: "arrow.up.left.and.arrow.down.right", size: 48) { model.overviewNav() }
                        MapFab(icon: nav.muted ? "speaker.slash.fill" : "speaker.wave.2.fill", size: 48) { nav.muted.toggle() }
                        MapFab(icon: "square.3.layers.3d", size: 48) { model.showLayers = true }
                    }
                }
                .padding(.horizontal, 14)
                TripBar(nav: nav, onEnd: { if nav.arrived { model.endNavigation() } else { confirmEnd = true } }, onSteps: { showSteps = true })
                    .confirmationDialog("End this trip?", isPresented: $confirmEnd, titleVisibility: .visible) {
                        Button("End trip", role: .destructive) { model.endNavigation() }
                        Button("Keep going", role: .cancel) {}
                    }
            }
        }
        .sheet(isPresented: $showAlong) {
            AlongRouteSheet(nav: nav)
        }
        .sheet(isPresented: $showSteps) {
            NavigationView {
                ScrollView { StepsList(route: nav.route).padding(.horizontal, 16) }
                    .navigationTitle("Steps")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showSteps = false } } }
            }
        }
    }
}

struct InstructionBanner: View {
    @ObservedObject var nav: Navigator

    var body: some View {
        let m = nav.nextManeuver
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 14) {
                if m?.type == .roundabout, let exit = m?.exit {
                    RoundaboutGlyph(exit: exit, rightHand: !Geo.drivesOnLeft(m?.location ?? LatLng(0, 0)))
                } else {
                    Image(systemName: maneuverSymbol(m?.type ?? .straight))
                        .font(.system(size: 40, weight: .bold))
                        .frame(width: 56)
                }
                VStack(alignment: .leading, spacing: 4) {
                    if let p = nav.progress {
                        Text(Fmt.distance(p.distanceToNext)).font(.system(size: 30, weight: .bold))
                    }
                    let refs = RoadRefs.find(m?.instruction ?? "")
                    if !refs.isEmpty {
                        HStack(spacing: 6) { ForEach(refs, id: \.self) { RoadShield(ref: $0) } }
                    }
                    Text(m?.instruction ?? "Follow the route")
                        .font(.system(size: 19, weight: .medium))
                        .lineLimit(3)
                        .minimumScaleFactor(0.8)
                    if let lane = m?.laneHint {
                        Text(lane).font(.footnote).opacity(0.85)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            if let m, m.lanes.count > 1, let p = nav.progress, p.distanceToNext < 1_500 {
                LaneStrip(lanes: m.lanes, turn: m.type)
                    .padding(.bottom, 12)
            }
            if let then = nav.thenManeuver {
                HStack(spacing: 8) {
                    Text("Then").font(.subheadline.weight(.semibold))
                    Image(systemName: maneuverSymbol(then.type)).font(.system(size: 16, weight: .bold))
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color.black.opacity(0.18))
            }
        }
        .foregroundColor(.white)
        .background(RoundedRectangle(cornerRadius: 18).fill(Vela.navGreen))
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .shadow(color: .black.opacity(0.3), radius: 6, y: 2)
    }
}

struct SpeedBadge: View {
    let mps: Double
    var limitKmh: Double?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let metric = Fmt.metric
        let v = Int((mps * (metric ? 3.6 : 2.23694)).rounded())
        // Red once more than a little over the limit.
        let over = limitKmh.map { mps * 3.6 > $0 + 5 } ?? false
        VStack(spacing: -2) {
            Text("\(v)").font(.system(size: 24, weight: .bold)).foregroundColor(over ? Vela.closed : .primary)
            Text(metric ? "km/h" : "mph").font(.system(size: 10, weight: .medium)).foregroundColor(.secondary)
        }
        .frame(width: 64, height: 64)
        .background(Circle().fill(Vela.sheet(scheme)).shadow(color: .black.opacity(0.25), radius: 4, y: 2))
    }
}

/** The posted limit: a US-style white sign in miles, a round red-ringed one in kilometers. */
struct SpeedLimitSign: View {
    let kmh: Double

    var body: some View {
        if Fmt.metric || Fmt.yards {
            // Round red ring: kilometers on the Continent, miles in Britain.
            Text("\(Fmt.metric ? Int(kmh.rounded()) : Int((kmh / 1.609344 / 5).rounded()) * 5)")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.black)
                .frame(width: 56, height: 56)
                .background(Circle().fill(.white))
                .overlay(Circle().inset(by: 3).stroke(Color(hex: 0xD93025), lineWidth: 6))
                .shadow(color: .black.opacity(0.25), radius: 4, y: 2)
        } else {
            // OSM limits in the US are mapped in mph; round back to the posted number.
            let mph = Int((kmh / 1.609344 / 5).rounded()) * 5
            VStack(spacing: 0) {
                Text("SPEED\nLIMIT").font(.system(size: 8, weight: .bold)).multilineTextAlignment(.center)
                Text("\(mph)").font(.system(size: 22, weight: .bold))
            }
            .foregroundColor(.black)
            .frame(width: 48, height: 60)
            .background(RoundedRectangle(cornerRadius: 6).fill(.white))
            .overlay(RoundedRectangle(cornerRadius: 6).inset(by: 2).stroke(.black, lineWidth: 2))
            .shadow(color: .black.opacity(0.25), radius: 4, y: 2)
        }
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
        HStack {
            Button(action: onEnd) {
                Image(systemName: "xmark")
                    .font(.system(size: 18, weight: .bold))
                    .frame(width: 50, height: 50)
                    .overlay(Circle().stroke(Color.secondary.opacity(0.5), lineWidth: 1.5))
            }
            .buttonStyle(.plain)
            Spacer()
            VStack(spacing: 2) {
                Text(Fmt.duration(remaining)).font(.system(size: 26, weight: .bold))
                    .foregroundColor(scheme == .dark ? Vela.openDark : Vela.open)
                Text("\(Fmt.distance(meters)) · \(Fmt.arrival(remaining))").font(.subheadline).foregroundColor(.secondary)
            }
            Spacer()
            Button(action: onSteps) {
                Image(systemName: "list.bullet")
                    .font(.system(size: 18, weight: .bold))
                    .frame(width: 50, height: 50)
                    .overlay(Circle().stroke(Color.secondary.opacity(0.5), lineWidth: 1.5))
            }
            .buttonStyle(.plain)
        }
        .foregroundColor(.primary)
        .padding(.horizontal, 18)
        .padding(.top, 14)
        .padding(.bottom, 8)
        .background(
            UnevenTopRounded(radius: 22)
                .fill(Vela.sheet(scheme))
                .shadow(color: .black.opacity(0.25), radius: 8, y: -2)
                .ignoresSafeArea(edges: .bottom)
        )
    }
}

struct ArrivedCard: View {
    let name: String
    var onPark: (() -> Void)?
    let done: () -> Void
    @State private var parked = false

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "flag.checkered").font(.system(size: 30, weight: .bold))
            VStack(alignment: .leading, spacing: 2) {
                Text("You have arrived").font(.title3.weight(.bold))
                Text(name).font(.subheadline).lineLimit(1)
            }
            Spacer()
            if let onPark, !parked {
                Button { onPark(); parked = true } label: { Image(systemName: "parkingsign").font(.headline) }
                    .frame(width: 40, height: 40)
                    .background(Circle().fill(Color.white.opacity(0.25)))
                    .accessibilityLabel("Save parking")
            }
            Button("Done", action: done)
                .font(.headline)
                .padding(.horizontal, 16).frame(height: 40)
                .background(Capsule().fill(Color.white.opacity(0.25)))
        }
        .foregroundColor(.white)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18).fill(Vela.navGreen))
    }
}

/**
 * The Waze-style camera warning: the camera, how far, a bar that empties as it gets close, and
 * the limit it checks. Turns red while the user is over that limit.
 */
struct CameraCard: View {
    let alert: CameraAlert
    let speedKmh: Double
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let over = alert.limitKmh.map { speedKmh > $0 + 3 } ?? false
        let left = min(max(alert.meters / alert.startMeters, 0), 1)
        let tint: Color = alert.kind == .speed ? Color(hex: 0xF9AB00) : Color(hex: 0x8E44AD)
        VStack(spacing: 8) {
            HStack(spacing: 12) {
                CameraBadge(kind: alert.kind, size: 40)
                VStack(alignment: .leading, spacing: 1) {
                    Text(Fmt.distance(alert.meters)).font(.system(size: 24, weight: .bold)).monospacedDigit()
                    Text(alert.kind == .speed ? "Speed camera" : (Fmt.yards ? "Number plate camera" : "License plate camera"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(over ? .white.opacity(0.9) : .secondary)
                }
                Spacer(minLength: 4)
                if let limit = alert.limitKmh { SpeedLimitSign(kmh: limit) }
            }
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.secondary.opacity(0.25))
                    Capsule().fill(over ? Color.white : tint).frame(width: g.size.width * left)
                }
            }
            .frame(height: 6)
        }
        .foregroundColor(over ? .white : .primary)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18).fill(over ? Vela.closed : Vela.sheet(scheme))
            .shadow(color: .black.opacity(0.25), radius: 4, y: 2))
        .animation(.easeOut(duration: 0.3), value: over)
        .accessibilityElement(children: .combine)
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

/** Which lanes to be in, the way Waze and Google draw it: the right ones bright, the rest dim. */
struct LaneStrip: View {
    let lanes: [Lane]
    let turn: ManeuverType

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(lanes.enumerated()), id: \.offset) { i, lane in
                if i > 0 { Rectangle().fill(Color.white.opacity(0.35)).frame(width: 1, height: 26) }
                Image(systemName: Self.symbol(pick(lane)))
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(lane.valid ? .white : .white.opacity(0.32))
                    .frame(width: 38, height: 36)
            }
        }
        .padding(.horizontal, 6)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.black.opacity(0.22)))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Use the highlighted lanes")
    }

    /** A lane that goes several ways shows the way this turn goes when it's a good lane. */
    private func pick(_ lane: Lane) -> String {
        guard lane.valid, lane.indications.count > 1 else { return lane.indications.first ?? "straight" }
        let want: [String]
        switch turn {
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

/** A roundabout with the exit to take drawn as the way out, and its number. */
struct RoundaboutGlyph: View {
    let exit: Int
    /** Drives on the right: traffic goes round counterclockwise. */
    var rightHand = true

    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.45), lineWidth: 5).frame(width: 26, height: 26)
            // Entry from the bottom, around to the exit, one exit about every 90 degrees.
            let turn = Double(min(max(exit, 1), 6)) * 90
            Circle()
                .trim(from: 0, to: min(turn, 330) / 360)
                .stroke(Color.white, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                .frame(width: 26, height: 26)
                .rotationEffect(.degrees(90))
                .scaleEffect(x: rightHand ? -1 : 1, y: 1)
            Rectangle().fill(Color.white).frame(width: 5, height: 12).offset(y: 19)
            Text("\(exit)").font(.system(size: 13, weight: .heavy)).foregroundColor(.white)
        }
        .frame(width: 56, height: 56)
    }
}
