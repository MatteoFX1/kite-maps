import SwiftUI
import VelaCore

struct ContentView: View {
    @StateObject private var model = MapModel()
    @FocusState private var searchFocused: Bool

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                MapView(model: model)
                    .ignoresSafeArea()

                VStack(spacing: 8) {
                    searchBar
                    if let notice = model.notice {
                        Text(notice)
                            .font(.footnote)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                            .onTapGesture { model.notice = nil }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)

                VStack(spacing: 0) {
                    Spacer(minLength: 0)
                    HStack {
                        Spacer()
                        Button { model.centerOnUser() } label: {
                            Image(systemName: "location.fill")
                                .font(.title3)
                                .frame(width: 48, height: 48)
                                .background(.regularMaterial, in: Circle())
                        }
                    }
                    .padding(.trailing, 12)
                    .padding(.bottom, 8)
                    bottomPanel
                        .frame(height: hasPanel ? geo.size.height * (model.panelExpanded ? 0.85 : 0.42) : 0)
                }
            }
        }
    }

    private var hasPanel: Bool { model.selected != nil || !model.results.isEmpty }

    private var searchBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("Search here", text: $model.query)
                .focused($searchFocused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .onSubmit { model.search() }
            if model.searching {
                ProgressView()
            } else if !model.query.isEmpty || !model.results.isEmpty {
                Button {
                    model.clear()
                    searchFocused = false
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 50)
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.15), radius: 6, y: 2)
    }

    @ViewBuilder
    private var bottomPanel: some View {
        if let place = model.selected {
            panel {
                PlaceDetailView(place: place, expanded: $model.panelExpanded, onClose: { model.closePlace() })
            }
        } else if !model.results.isEmpty {
            panel {
                ResultsList(places: model.results, expanded: $model.panelExpanded, onPick: { p in
                    searchFocused = false
                    model.select(p)
                })
            }
        }
    }

    private func panel<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(Color.secondary.opacity(0.5))
                .frame(width: 40, height: 5)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
                .onTapGesture { withAnimation { model.panelExpanded.toggle() } }
                .gesture(DragGesture(minimumDistance: 10).onEnded { g in
                    withAnimation { model.panelExpanded = g.translation.height < 0 }
                })
            content()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: .black.opacity(0.2), radius: 10, y: -2)
        .ignoresSafeArea(edges: .bottom)
    }
}

struct ResultsList: View {
    let places: [Place]
    @Binding var expanded: Bool
    let onPick: (Place) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(places) { p in
                    Button { onPick(p) } label: {
                        ResultRow(place: p)
                    }
                    .buttonStyle(.plain)
                    Divider().padding(.leading, 16)
                }
            }
            .padding(.bottom, 30)
        }
    }
}

struct ResultRow: View {
    let place: Place

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(place.name).font(.headline)
            RatingLine(place: place)
            if let status = place.statusText {
                Text(status).font(.subheadline).foregroundStyle(statusColor(place))
            }
            if let address = place.address {
                Text(address).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

/** "4.5 ★ (120) · Pizza restaurant · $10–20 · 1.2 km" */
struct RatingLine: View {
    let place: Place

    var body: some View {
        let parts: [String] = [
            place.rating.map { r in "\(String(format: "%.1f", r)) ★" + (place.reviewCount.map { " (\($0))" } ?? "") },
            place.category,
            place.priceText,
            place.distanceMeters.map(formatDistance),
        ].compactMap { $0 }
        if !parts.isEmpty {
            Text(parts.joined(separator: " · "))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

func statusColor(_ p: Place) -> Color {
    if p.permanentlyClosed { return .red }
    switch p.openNow {
    case true?: return .green
    case false?: return .red
    default: return .secondary
    }
}

func formatDistance(_ m: Double) -> String {
    let f = MeasurementFormatter()
    f.unitOptions = .naturalScale
    f.numberFormatter.maximumFractionDigits = m < 1000 ? 0 : 1
    return f.string(from: Measurement(value: m, unit: UnitLength.meters))
}
