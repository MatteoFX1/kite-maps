import SwiftUI
import VelaCore

/** Turn-by-turn: the green instruction banner, map buttons, and the ETA bar. */
struct NavigationOverlay: View {
    @ObservedObject var model: AppModel
    @ObservedObject var nav: Navigator
    @State private var showSteps = false
    @State private var showAlong = false
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
                    CameraCard(kind: cam.kind, meters: cam.meters)
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
                TripBar(nav: nav, onEnd: { model.endNavigation() }, onSteps: { showSteps = true })
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
                Image(systemName: maneuverSymbol(m?.type ?? .straight))
                    .font(.system(size: 40, weight: .bold))
                    .frame(width: 56)
                VStack(alignment: .leading, spacing: 4) {
                    if let p = nav.progress {
                        Text(Fmt.distance(p.distanceToNext)).font(.system(size: 30, weight: .bold))
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
        if Fmt.metric {
            Text("\(Int(kmh.rounded()))")
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

/** "Speed camera ahead · 200 m", amber like Vela's speed camera badge; purple for plate readers. */
struct CameraCard: View {
    let kind: RoadCamera.Kind
    let meters: Double
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: 10) {
            CameraBadge(kind: kind, size: 30)
            Text(kind == .speed ? "Speed camera ahead" : "License plate camera ahead")
                .font(.subheadline.weight(.semibold))
            Spacer(minLength: 4)
            Text(Fmt.distance(meters)).font(.subheadline).foregroundColor(.secondary)
        }
        .padding(.horizontal, 12)
        .frame(height: 48)
        .background(RoundedRectangle(cornerRadius: 14).fill(Vela.sheet(scheme)).shadow(color: .black.opacity(0.25), radius: 4, y: 2))
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
        ("Gas", "fuelpump.fill", "gas station"), ("EV charging", "bolt.car.fill", "ev charging station"),
        ("Coffee", "cup.and.saucer.fill", "coffee"), ("Food", "fork.knife", "restaurants"),
        ("Groceries", "cart.fill", "grocery store"), ("Restrooms", "toilet.fill", "public restroom"),
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
