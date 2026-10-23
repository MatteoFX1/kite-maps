import SwiftUI
import VelaCore

/** Route preview: the from/to card on top, routes on the map, the trip sheet below. */
struct DirectionsOverlay: View {
    @ObservedObject var model: AppModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        GeometryReader { geo in
            let full = geo.size.height + geo.safeAreaInsets.bottom
            ZStack(alignment: .top) {
                VStack {
                    Spacer()
                    BottomSheet(detent: $model.detent, containerHeight: full, peekHeight: 190) {
                        TripHeader(model: model)
                    } content: {
                        TripBody(model: model)
                    }
                }
                .ignoresSafeArea(edges: .bottom)
                if let d = model.directions {
                    EndpointsCard(model: model, state: d)
                        .padding(.top, 6)
                }
            }
        }
    }
}

struct EndpointsCard: View {
    @ObservedObject var model: AppModel
    let state: DirectionsState
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: 10) {
            Button { withAnimation { model.closeDirections() } } label: {
                Image(systemName: "arrow.left").font(.system(size: 18, weight: .medium))
            }
            .buttonStyle(.plain)
            VStack(spacing: 0) {
                endpoint(icon: "circle", color: Vela.routeBlue, text: state.reversed ? state.destination.name : "Your location")
                Divider().padding(.leading, 30)
                endpoint(icon: "mappin.circle.fill", color: Vela.closed, text: state.reversed ? "Your location" : state.destination.name)
            }
            Button { model.reverseDirections() } label: {
                Image(systemName: "arrow.up.arrow.down").font(.system(size: 16, weight: .medium))
            }
            .buttonStyle(.plain)
        }
        .foregroundColor(.primary)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 18).fill(Vela.sheet(scheme)).shadow(color: .black.opacity(0.22), radius: 6, y: 2))
        .padding(.horizontal, 12)
    }

    private func endpoint(icon: String, color: Color, text: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 14, weight: .bold)).foregroundColor(color).frame(width: 18)
            Text(text).font(.body.weight(.medium)).lineLimit(1)
            Spacer()
        }
        .frame(height: 42)
    }
}

struct TripHeader: View {
    @ObservedObject var model: AppModel
    @ObservedObject private var settings = AppSettings.shared

    var body: some View {
        if let d = model.directions {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text(d.mode.label).font(.title2.weight(.semibold))
                    Spacer()
                    Menu {
                        Toggle("Avoid tolls", isOn: $settings.avoidTolls)
                        Toggle("Avoid highways", isOn: $settings.avoidHighways)
                    } label: {
                        Image(systemName: "slider.horizontal.3")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(.primary)
                            .frame(width: 34, height: 34)
                            .background(Circle().fill(Color.secondary.opacity(0.18)))
                    }
                    .onChange(of: settings.avoidTolls) { _ in model.reloadRoutes() }
                    .onChange(of: settings.avoidHighways) { _ in model.reloadRoutes() }
                    CircleIconButton(icon: "xmark") { withAnimation { model.closeDirections() } }
                }
                ModeTabs(model: model, state: d)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
        }
    }
}

struct ModeTabs: View {
    @ObservedObject var model: AppModel
    let state: DirectionsState
    @Environment(\.colorScheme) private var scheme

    private func icon(_ m: TravelMode) -> String {
        switch m {
        case .drive: return "car.fill"
        case .bike: return "bicycle"
        case .walk: return "figure.walk"
        }
    }

    var body: some View {
        HStack(spacing: 6) {
            ForEach(TravelMode.allCases) { m in
                let on = m == state.mode
                Button { model.setMode(m) } label: {
                    HStack(spacing: 6) {
                        Image(systemName: icon(m)).font(.system(size: 13))
                        if state.loading.contains(m) {
                            ProgressView().scaleEffect(0.7)
                        } else if let r = state.routes[m]?.first {
                            Text(Fmt.duration(r.durationSeconds)).font(.subheadline.weight(.medium))
                        } else {
                            Text("–").font(.subheadline)
                        }
                    }
                    .padding(.horizontal, 12)
                    .frame(height: 34)
                    .foregroundColor(on ? Vela.onTonal(scheme) : .primary)
                    .background(Capsule().fill(on ? Vela.tonal(scheme) : Color.clear))
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
    }
}

struct TripBody: View {
    @ObservedObject var model: AppModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if let d = model.directions {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let r = d.route {
                        summary(r, d)
                        buttons(d)
                        if d.current.count > 1 { alternates(d, picked: r) }
                        Divider()
                        Text("Steps").font(.headline)
                        StepsList(route: r)
                    } else if d.loading.contains(d.mode) {
                        HStack { ProgressView(); Text("Finding routes…").foregroundColor(.secondary) }
                            .padding(.vertical, 20)
                    } else {
                        Text(d.errors[d.mode] ?? "No route found.").foregroundColor(.secondary).padding(.vertical, 20)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 80)
            }
        }
    }

    private func delayColor(_ r: Route) -> Color {
        switch r.delayLevel {
        case 2: return scheme == .dark ? Vela.closedDark : Vela.closed
        case 1: return Color(hex: 0xE37400)
        default: return scheme == .dark ? Vela.openDark : Vela.open
        }
    }

    private func summary(_ r: Route, _ d: DirectionsState) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(Fmt.duration(r.durationSeconds)).font(.title2.weight(.semibold)).foregroundColor(delayColor(r))
                Text("(\(Fmt.distance(r.distanceMeters)))").font(.title3).foregroundColor(.secondary)
            }
            let best = d.current.map(\.durationSeconds).min() ?? r.durationSeconds
            let lead = r.durationSeconds <= best + 30 ? (r.trafficSeconds != nil ? "Fastest route now" : "Best route") : "Slower route"
            Text([lead, r.summary.map { "via \($0)" }].compactMap { $0 }.joined(separator: " · "))
                .font(.subheadline).foregroundColor(.secondary)
            if r.mode == .drive, r.trafficSeconds != nil {
                Text(r.delayLevel == 0 ? "Traffic is light" : "\(Fmt.duration(r.durationSeconds - r.typicalSeconds)) slower than usual")
                    .font(.subheadline).foregroundColor(delayColor(r))
            }
            Text("Arrive around \(Fmt.arrival(r.durationSeconds))").font(.subheadline).foregroundColor(.secondary)
        }
    }

    private func buttons(_ d: DirectionsState) -> some View {
        HStack(spacing: 8) {
            if !d.reversed {
                PillButton(title: "Start", icon: "location.north.fill", filled: true) { model.startNavigation() }
            }
            if let url = d.destination.shareURL {
                ShareLink(item: url) {
                    HStack(spacing: 6) {
                        Image(systemName: "square.and.arrow.up").font(.system(size: 14, weight: .semibold))
                        Text("Share").font(.subheadline.weight(.semibold))
                    }
                    .padding(.horizontal, 16)
                    .frame(height: 40)
                    .foregroundColor(Vela.onTonal(scheme))
                    .background(Capsule().fill(Vela.tonal(scheme)))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func alternates(_ d: DirectionsState, picked: Route) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(d.current.enumerated()), id: \.element.id) { i, r in
                if r.id != picked.id {
                    Button { model.selectRoute(i) } label: {
                        HStack {
                            Image(systemName: "arrow.triangle.branch").foregroundColor(.secondary).frame(width: 22)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(Fmt.duration(r.durationSeconds)) · \(Fmt.distance(r.distanceMeters))").font(.subheadline.weight(.medium))
                                if let s = r.summary { Text("via \(s)").font(.caption).foregroundColor(.secondary) }
                            }
                            Spacer()
                            let diff = r.durationSeconds - picked.durationSeconds
                            Text(diff >= 0 ? "+\(Fmt.duration(diff))" : "−\(Fmt.duration(-diff))")
                                .font(.caption).foregroundColor(.secondary)
                        }
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

struct StepsList: View {
    let route: Route

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(route.maneuvers.enumerated()), id: \.offset) { _, m in
                HStack(alignment: .top, spacing: 14) {
                    Image(systemName: maneuverSymbol(m.type))
                        .font(.system(size: 18, weight: .semibold))
                        .frame(width: 26)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(m.instruction).font(.subheadline)
                        if let lane = m.laneHint { Text(lane).font(.caption).foregroundColor(.secondary) }
                        if m.distanceMeters > 0 {
                            Text(Fmt.distance(m.distanceMeters)).font(.caption).foregroundColor(.secondary)
                        }
                    }
                    Spacer()
                }
                .padding(.vertical, 9)
                Divider().padding(.leading, 40)
            }
        }
    }
}
