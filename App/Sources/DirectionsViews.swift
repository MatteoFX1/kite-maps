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
                ForEach(Array(state.stops.enumerated()), id: \.offset) { i, stop in
                    Divider().padding(.leading, 30)
                    HStack(spacing: 12) {
                        Text("\(i + 1)").font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                            .frame(width: 18, height: 18).background(Circle().fill(Color.secondary))
                        Text(stop.name).font(.body.weight(.medium)).lineLimit(1)
                        Spacer()
                        Button { model.removeStop(i) } label: { Image(systemName: "xmark").font(.system(size: 13, weight: .semibold)) }
                            .buttonStyle(.plain).foregroundColor(.secondary)
                            .accessibilityLabel("Remove stop")
                    }
                    .frame(height: 42)
                }
                Divider().padding(.leading, 30)
                endpoint(icon: "mappin.circle.fill", color: Vela.closed, text: state.reversed ? "Your location" : state.destination.name)
                if !state.transit && state.stops.count < 8 {
                    Divider().padding(.leading, 30)
                    Button { model.addingStop = true } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "plus").font(.system(size: 14, weight: .bold)).frame(width: 18)
                            Text("Add stop").font(.subheadline.weight(.medium))
                            Spacer()
                        }
                        .foregroundColor(Vela.routeBlue)
                        .frame(height: 36)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
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
                    Text(d.transit ? "Transit" : d.mode.label).font(.title2.weight(.semibold))
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
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) { tabs }
                .onAppear { if state.transit { proxy.scrollTo("transit", anchor: .trailing) } }
                .onChange(of: state.transit) { on in
                    withAnimation { proxy.scrollTo(on ? "transit" : "drive", anchor: on ? .trailing : .leading) }
                }
        }
    }

    private var tabs: some View {
        HStack(spacing: 4) {
            ForEach(TravelMode.allCases) { m in
                let on = !state.transit && m == state.mode
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
                    .padding(.horizontal, 11)
                    .frame(height: 34)
                    .foregroundColor(on ? Vela.onTonal(scheme) : .primary)
                    .background(Capsule().fill(on ? Vela.tonal(scheme) : Color.clear))
                }
                .buttonStyle(.plain)
                .id(m == .drive ? "drive" : "\(m.rawValue)")
            }
            Button { model.showTransit() } label: {
                HStack(spacing: 6) {
                    Image(systemName: "tram.fill").font(.system(size: 13))
                    if let t = state.trips {
                        Text(t.map(\.durationSeconds).min().map(Fmt.duration) ?? "–").font(.subheadline.weight(.medium))
                    } else {
                        ProgressView().scaleEffect(0.7)
                    }
                }
                .padding(.horizontal, 12)
                .frame(height: 34)
                .foregroundColor(state.transit ? Vela.onTonal(scheme) : .primary)
                .background(Capsule().fill(state.transit ? Vela.tonal(scheme) : Color.clear))
            }
            .buttonStyle(.plain)
            .id("transit")
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
                    if d.transit {
                        TransitBody(model: model, state: d)
                    } else if let r = d.route {
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

/** The Transit tab: each trip as a row of line chips with its times, the picked one's legs below. */
struct TransitBody: View {
    @ObservedObject var model: AppModel
    let state: DirectionsState
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if let trips = state.trips, !trips.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(trips.enumerated()), id: \.element.id) { i, t in
                    Button { model.selectTrip(i) } label: { TripRow(trip: t, picked: t.id == state.pickedTrip?.id) }
                        .buttonStyle(.plain)
                    Divider()
                }
            }
            if let t = state.pickedTrip {
                Text("Steps").font(.headline).padding(.top, 6)
                TransitSteps(trip: t)
            }
            Text("Timetables from Transitous").font(.caption).foregroundColor(.secondary).padding(.top, 4)
        } else if state.trips == nil {
            HStack { ProgressView(); Text("Finding transit…").foregroundColor(.secondary) }
                .padding(.vertical, 20)
        } else {
            Text(state.tripsError ?? "No transit trips found.").foregroundColor(.secondary).padding(.vertical, 20)
        }
    }
}

private let clock: DateFormatter = {
    let f = DateFormatter()
    f.timeStyle = .short
    return f
}()

struct TripRow: View {
    let trip: TransitTrip
    let picked: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                if let a = trip.depart, let b = trip.arrive {
                    Text("\(clock.string(from: a)) – \(clock.string(from: b))").font(.subheadline.weight(.semibold))
                }
                Spacer()
                Text(Fmt.duration(trip.durationSeconds)).font(.headline)
            }
            HStack(spacing: 4) {
                ForEach(Array(trip.legs.enumerated()), id: \.offset) { i, leg in
                    if i > 0 { Image(systemName: "chevron.right").font(.system(size: 9, weight: .bold)).foregroundColor(.secondary) }
                    if leg.isWalk {
                        HStack(spacing: 1) {
                            Image(systemName: "figure.walk").font(.system(size: 13))
                            if leg.durationSeconds >= 60 { Text("\(Int((leg.durationSeconds / 60).rounded()))").font(.caption2) }
                        }
                        .foregroundColor(.secondary)
                    } else {
                        LineChip(leg: leg)
                    }
                }
            }
            .lineLimit(1)
            HStack(spacing: 6) {
                if let first = trip.rides.first, let dep = first.depart {
                    Text("\(clock.string(from: dep)) from \(first.fromName)").lineLimit(1)
                }
                if let late = trip.rides.first?.delayMinutes {
                    Text(late > 0 ? "\(late) min late" : late < 0 ? "\(-late) min early" : "On time")
                        .foregroundColor(late > 1 ? Vela.closed : Vela.open)
                }
            }
            .font(.caption).foregroundColor(.secondary)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 8)
        .background(RoundedRectangle(cornerRadius: 12).fill(picked ? Color.secondary.opacity(0.14) : Color.clear))
        .contentShape(Rectangle())
    }
}

/** A line's name on its own color, the way stations sign it. */
struct LineChip: View {
    let leg: TransitLeg

    var body: some View {
        let bg = leg.color.map { Color(hex: $0) } ?? Vela.routeBlue
        let fg = leg.textColor.map { Color(hex: $0) } ?? .white
        HStack(spacing: 3) {
            Image(systemName: vehicleSymbol(leg.vehicle)).font(.system(size: 10, weight: .bold))
            Text(leg.line.isEmpty ? leg.mode.capitalized : leg.line).font(.caption.weight(.bold))
        }
        .padding(.horizontal, 6)
        .frame(height: 22)
        .foregroundColor(fg)
        .background(RoundedRectangle(cornerRadius: 5).fill(bg))
    }
}

func vehicleSymbol(_ v: TransitLeg.Vehicle) -> String {
    switch v {
    case .walk: return "figure.walk"
    case .bus: return "bus.fill"
    case .metro: return "tram.fill.tunnel"
    case .tram: return "tram.fill"
    case .train: return "train.side.front.car"
    case .ferry: return "ferry.fill"
    case .other: return "tram.fill"
    }
}

struct TransitSteps: View {
    let trip: TransitTrip

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(trip.legs.enumerated()), id: \.offset) { _, leg in
                HStack(alignment: .top, spacing: 14) {
                    Image(systemName: vehicleSymbol(leg.vehicle))
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(leg.isWalk ? .secondary : (leg.color.map { Color(hex: $0) } ?? Vela.routeBlue))
                        .frame(width: 26)
                    VStack(alignment: .leading, spacing: 3) {
                        if leg.isWalk {
                            Text(leg.toName.isEmpty || leg.toName == "END" ? "Walk to the destination" : "Walk to \(leg.toName)").font(.subheadline)
                            Text("\(Fmt.duration(leg.durationSeconds)) · \(Fmt.distance(leg.distanceMeters))").font(.caption).foregroundColor(.secondary)
                        } else {
                            HStack(spacing: 6) {
                                LineChip(leg: leg)
                                if let h = leg.headsign { Text("to \(h)").font(.subheadline).lineLimit(1) }
                            }
                            Text(times(leg.depart, leg.fromName)).font(.subheadline)
                            Text("Ride \(leg.stops) stop\(leg.stops == 1 ? "" : "s") · \(Fmt.duration(leg.durationSeconds))")
                                .font(.caption).foregroundColor(.secondary)
                            Text(times(leg.arrive, "Get off at \(leg.toName)")).font(.caption).foregroundColor(.secondary)
                        }
                    }
                    Spacer()
                }
                .padding(.vertical, 9)
                Divider().padding(.leading, 40)
            }
        }
    }

    private func times(_ d: Date?, _ text: String) -> String {
        d.map { "\(clock.string(from: $0))  \(text)" } ?? text
    }
}

/** Search for a place to stop at on the way. Home, work and saved places are one tap. */
struct StopPicker: View {
    @ObservedObject var model: AppModel
    @ObservedObject private var settings = AppSettings.shared
    @State private var query = ""
    @State private var found: [Place] = []
    @State private var searching = false
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    var body: some View {
        NavigationView {
            List {
                if !found.isEmpty {
                    Section("Results") { ForEach(found) { row($0, icon: "mappin.circle.fill") } }
                } else if searching {
                    HStack { ProgressView(); Text("Searching…").foregroundColor(.secondary) }
                }
                let mine = [settings.home.map { ($0, "house.fill") }, settings.work.map { ($0, "briefcase.fill") }].compactMap { $0 }
                    + settings.saved.prefix(12).map { ($0, "bookmark.fill") }
                if !mine.isEmpty && found.isEmpty {
                    Section("Your places") { ForEach(mine, id: \.0.id) { row($0.0, icon: $0.1) } }
                }
                if found.isEmpty && !searching {
                    Section("Along the way") {
                        ForEach(["Gas stations", "Coffee", "Restaurants", "EV charging"], id: \.self) { c in
                            Button { query = c; run() } label: { Label(c, systemImage: "magnifyingglass") }
                        }
                    }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search for a stop")
            .onSubmit(of: .search) { run() }
            .navigationTitle("Add stop")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }

    private func row(_ p: Place, icon: String) -> some View {
        Button {
            model.addStop(p)
            dismiss()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon).foregroundColor(.secondary).frame(width: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.name).foregroundColor(.primary).lineLimit(1)
                    if let a = p.address { Text(a).font(.caption).foregroundColor(.secondary).lineLimit(1) }
                }
                Spacer()
                if let d = model.userLatLng?.distance(to: p.location) {
                    Text(Fmt.distance(d)).font(.caption).foregroundColor(.secondary)
                }
            }
        }
    }

    private func run() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        searching = true
        found = []
        // Look between you and where you're going, so stops fall along the way.
        let a = model.userLatLng ?? model.viewport.center
        let b = model.directions?.destination.location ?? a
        let mid = LatLng((a.lat + b.lat) / 2, (a.lng + b.lng) / 2)
        let span = max(a.distance(to: b) * 1.2, 5_000)
        let useGoogle = !settings.noGoogle
        Task {
            let r = await PlaceSearch.search(q, center: mid, spanMeters: span, useGoogle: useGoogle)
            found = Array(r.places.prefix(30))
            searching = false
        }
    }
}
