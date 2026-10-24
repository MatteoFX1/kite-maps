import SwiftUI
import VelaCore

/** Turn-by-turn: the green instruction banner, map buttons, and the ETA bar. */
struct NavigationOverlay: View {
    @ObservedObject var model: AppModel
    @ObservedObject var nav: Navigator
    @State private var showSteps = false
    @Environment(\.colorScheme) private var scheme

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
                        MapFab(icon: "arrow.up.left.and.arrow.down.right", size: 48) { model.overviewNav() }
                        MapFab(icon: nav.muted ? "speaker.slash.fill" : "speaker.wave.2.fill", size: 48) { nav.muted.toggle() }
                        MapFab(icon: "square.3.layers.3d", size: 48) { model.showLayers = true }
                    }
                }
                .padding(.horizontal, 14)
                TripBar(nav: nav, onEnd: { model.endNavigation() }, onSteps: { showSteps = true })
            }
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
