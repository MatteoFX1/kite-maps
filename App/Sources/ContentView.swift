import CoreLocation
import MapLibre
import SwiftUI
import VelaCore

struct ContentView: View {
    @StateObject private var model = AppModel()
    @ObservedObject private var settings = AppSettings.shared
    @Environment(\.colorScheme) private var systemScheme

    private var dark: Bool { settings.colorScheme.map { $0 == .dark } ?? (systemScheme == .dark) }

    var body: some View {
        ZStack {
            VelaMapView(model: model, settings: settings, dark: dark)
                .ignoresSafeArea()
            if let nav = model.nav {
                NavigationOverlay(model: model, nav: nav)
            } else if model.directions != nil {
                DirectionsOverlay(model: model)
            } else {
                BrowseOverlay(model: model)
            }
        }
        .preferredColorScheme(settings.colorScheme)
        .task { await DemoScript.run(model) }
        .sheet(isPresented: $model.showLayers) {
            LayersSheet(settings: settings)
        }
        .sheet(isPresented: $model.showSettings) {
            SettingsView(settings: settings, onChange: { model.reloadRoutes() })
        }
        .sheet(isPresented: $model.showSaved) {
            SavedView(settings: settings) { p in
                model.showSaved = false
                model.select(p)
            }
        }
    }
}

/** The normal map screen: search bar, category chips, map buttons and the results or place sheet. */
struct BrowseOverlay: View {
    @ObservedObject var model: AppModel
    @FocusState private var searchFocused: Bool
    @Environment(\.colorScheme) private var scheme

    private var hasSheet: Bool { model.selected != nil || !model.results.isEmpty }

    var body: some View {
        GeometryReader { geo in
            let full = geo.size.height + geo.safeAreaInsets.bottom
            let sheetH = hasSheet ? BottomSheet<EmptyView, EmptyView>.height(model.detent, container: full, peek: 150) : 0
            ZStack(alignment: .top) {
                // Map buttons on the right: layers up top, location just above the sheet.
                VStack {
                    HStack {
                        Spacer()
                        MapFab(icon: "square.3.layers.3d", size: 44) { model.showLayers = true }
                    }
                    .padding(.top, model.results.isEmpty && model.selected == nil ? 118 : 70)
                    Spacer()
                    if sheetH < full * 0.6 {
                        HStack {
                            Spacer()
                            MapFab(icon: "location.fill", tint: Vela.routeBlue) { model.centerOnUser() }
                        }
                        .padding(.bottom, max(sheetH - geo.safeAreaInsets.bottom, 0) + 14)
                    }
                }
                .padding(.horizontal, 14)

                if hasSheet {
                    VStack {
                        Spacer()
                        sheet(full: full)
                    }
                    .ignoresSafeArea(edges: .bottom)
                    .transition(.move(edge: .bottom))
                }

                VStack(spacing: 10) {
                    SearchBar(model: model, focused: $searchFocused)
                    if searchFocused {
                        SuggestionsPanel(model: model) { searchFocused = false }
                    } else if model.results.isEmpty && model.selected == nil {
                        CategoryChips { c in
                            searchFocused = false
                            model.search(c.query, title: c.label)
                        }
                    }
                    if let n = model.notice, !searchFocused {
                        NoticeBanner(text: n) { model.notice = nil }
                    }
                }
                .padding(.top, 6)
            }
            .animation(.spring(response: 0.3, dampingFraction: 0.85), value: hasSheet)
        }
    }

    @ViewBuilder
    private func sheet(full: CGFloat) -> some View {
        if let place = model.selected {
            BottomSheet(detent: $model.detent, containerHeight: full, peekHeight: 150) {
                PlaceHeader(model: model, place: place)
            } content: {
                PlaceBody(model: model, place: place)
            }
            .id(place.id)
        } else {
            BottomSheet(detent: $model.detent, containerHeight: full, peekHeight: 150) {
                ResultsHeader(model: model)
            } content: {
                ResultsList(model: model)
            }
        }
    }
}

struct SearchBar: View {
    @ObservedObject var model: AppModel
    var focused: FocusState<Bool>.Binding
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: 12) {
            if focused.wrappedValue {
                Button { focused.wrappedValue = false } label: {
                    Image(systemName: "arrow.left").font(.system(size: 18, weight: .medium))
                }
                .buttonStyle(.plain)
            } else {
                Image(systemName: "magnifyingglass").font(.system(size: 18, weight: .medium))
            }
            TextField("Search", text: $model.query)
                .focused(focused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit { model.search() }
                .onChange(of: model.query) { q in
                    if focused.wrappedValue { model.typed(q) }
                }
            if model.searching {
                ProgressView()
            } else if !model.query.isEmpty || !model.results.isEmpty || model.selected != nil {
                Button {
                    model.clearSearch()
                    focused.wrappedValue = false
                } label: {
                    Image(systemName: "xmark").font(.system(size: 17, weight: .medium))
                }
                .buttonStyle(.plain)
            } else {
                Button { model.showSaved = true } label: {
                    Image(systemName: "bookmark").font(.system(size: 18))
                }
                .buttonStyle(.plain)
            }
            Button { model.showSettings = true } label: {
                Image(systemName: "gearshape").font(.system(size: 18))
            }
            .buttonStyle(.plain)
        }
        .foregroundColor(.primary)
        .padding(.horizontal, 18)
        .frame(height: 52)
        .background(Capsule().fill(Vela.sheet(scheme)).shadow(color: .black.opacity(0.22), radius: 6, y: 2))
        .padding(.horizontal, 12)
    }
}

struct CategoryChips: View {
    let onPick: (Category) -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Category.all) { c in
                    Button { onPick(c) } label: {
                        HStack(spacing: 6) {
                            Image(systemName: c.icon).font(.system(size: 13))
                            Text(c.label).font(.subheadline.weight(.medium))
                        }
                        .padding(.horizontal, 14)
                        .frame(height: 36)
                        .background(Capsule().fill(Vela.sheet(scheme)).shadow(color: .black.opacity(0.18), radius: 3, y: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
        }
    }
}

struct SuggestionsPanel: View {
    @ObservedObject var model: AppModel
    @ObservedObject var settings = AppSettings.shared
    let done: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !model.query.isEmpty && !(model.suggestions.places.isEmpty && model.suggestions.queries.isEmpty) {
                    ForEach(model.suggestions.places.prefix(6)) { p in
                        Button { done(); model.openSuggestion(p) } label: {
                            HStack(spacing: 16) {
                                Image(systemName: "mappin.circle.fill").font(.system(size: 20)).frame(width: 22).foregroundColor(.secondary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(p.name).foregroundColor(.primary).lineLimit(1)
                                    if let a = p.address { Text(a).font(.caption).foregroundColor(.secondary).lineLimit(1) }
                                }
                                Spacer()
                                if let d = p.distanceMeters { Text(Fmt.distance(d)).font(.caption).foregroundColor(.secondary) }
                            }
                            .padding(.horizontal, 18)
                            .frame(minHeight: 54)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    ForEach(model.suggestions.queries.prefix(5), id: \.self) { q in
                        row(icon: "magnifyingglass", text: q) { done(); model.search(q) }
                    }
                } else {
                    ForEach(Category.all) { c in
                        row(icon: c.icon, text: c.label) { done(); model.search(c.query, title: c.label) }
                    }
                    if !settings.recents.isEmpty {
                        Text("Recent").font(.subheadline.weight(.semibold)).foregroundColor(.secondary)
                            .padding(.horizontal, 18).padding(.top, 14).padding(.bottom, 4)
                        ForEach(settings.recents, id: \.self) { q in
                            row(icon: "clock.arrow.circlepath", text: q) { done(); model.search(q) }
                        }
                    }
                }
            }
            .padding(.vertical, 8)
        }
        .background(RoundedRectangle(cornerRadius: 20).fill(Vela.sheet(scheme)).shadow(color: .black.opacity(0.2), radius: 8))
        .padding(.horizontal, 12)
        .frame(maxHeight: 460)
    }

    private func row(icon: String, text: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon).frame(width: 22).foregroundColor(.secondary)
                Text(text).foregroundColor(.primary)
                Spacer()
            }
            .padding(.horizontal, 18)
            .frame(height: 46)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct NoticeBanner: View {
    let text: String
    let dismiss: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "info.circle").foregroundColor(.secondary)
            Text(text).font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
            Button(action: dismiss) { Image(systemName: "xmark").font(.footnote) }.buttonStyle(.plain)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14).fill(Vela.sheet(scheme)).shadow(color: .black.opacity(0.18), radius: 4))
        .padding(.horizontal, 12)
    }
}

/** Drives the app into one screen for the build's screenshots: `-demo search|place|directions|nav`. */
enum DemoScript {
    @MainActor
    static func run(_ model: AppModel) async {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-demo"), i + 1 < args.count else { return }
        let step = args[i + 1]
        func wait(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1e9)) }
        await wait(6)
        if step == "browse" { return }
        if step == "cameras" {
            // Central Munich, where many fixed speed cameras are mapped.
            model.map?.setCenter(CLLocationCoordinate2D(latitude: 48.137, longitude: 11.575), zoomLevel: 12.5, animated: false)
            return
        }
        if step == "layers" { model.showLayers = true; return }
        if step == "transit" {
            // Davis to Sacramento Valley Station: buses and Amtrak.
            let station = Place(id: "demo-sac", name: "Sacramento Valley Station", location: LatLng(38.5843, -121.5006), source: "demo")
            model.select(station)
            await wait(2)
            model.openDirections(to: station)
            model.showTransit()
            return
        }
        model.search("restaurants", title: "Restaurants")
        await wait(8)
        guard step != "search", let first = model.visibleResults.first else { return }
        model.select(first)
        await wait(5)
        guard step != "place" else { return }
        model.openDirections(to: first)
        await wait(8)
        guard step == "nav" else { return }
        model.startNavigation()
    }
}
