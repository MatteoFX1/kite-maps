import SwiftUI
import VelaCore

struct ResultsHeader: View {
    @ObservedObject var model: AppModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.resultsTitle.capitalizedFirst).font(.title3.weight(.semibold))
                    Text("\(model.visibleResults.count) results").font(.subheadline).foregroundColor(.secondary)
                }
                Spacer()
                CircleIconButton(icon: model.detent == .full ? "chevron.down" : "chevron.up") {
                    withAnimation { model.detent = model.detent == .full ? .half : .full }
                }
                CircleIconButton(icon: "xmark") { withAnimation { model.clearSearch() } }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    FilterChip(title: "Open now", on: model.openNowOnly) { model.openNowOnly.toggle(); model.mapTick += 1 }
                    Menu {
                        Picker("Sort", selection: $model.sort) {
                            ForEach(SortMode.allCases) { Text($0.rawValue).tag($0) }
                        }
                    } label: {
                        FilterChipLabel(title: model.sort == .relevance ? "Sort" : model.sort.rawValue,
                                        on: model.sort != .relevance, menu: true)
                    }
                    FilterChip(title: "Rating 4.0+", on: model.rating4Only) { model.rating4Only.toggle(); model.mapTick += 1 }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 10)
    }
}

struct FilterChip: View {
    let title: String
    let on: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) { FilterChipLabel(title: title, on: on, menu: false) }.buttonStyle(.plain)
    }
}

struct FilterChipLabel: View {
    let title: String
    let on: Bool
    let menu: Bool
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: 4) {
            if on && !menu { Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)) }
            Text(title).font(.subheadline)
            if menu { Image(systemName: "chevron.down").font(.system(size: 10, weight: .semibold)) }
        }
        .padding(.horizontal, 14)
        .frame(height: 34)
        .foregroundColor(on ? Vela.onTonal(scheme) : .primary)
        .background(Capsule().fill(on ? Vela.tonal(scheme) : Color.clear))
        .overlay(Capsule().stroke(Color.secondary.opacity(on ? 0 : 0.5), lineWidth: 1))
    }
}

struct ResultsList: View {
    @ObservedObject var model: AppModel

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                Divider()
                ForEach(model.visibleResults) { p in
                    ResultRow(model: model, place: p)
                    Divider()
                }
            }
            .padding(.bottom, 60)
        }
    }
}

struct ResultRow: View {
    @ObservedObject var model: AppModel
    let place: Place
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(place.name).font(.headline).foregroundColor(.primary)
            PlaceMetaLine(place: place)
            StatusText(place: place).font(.subheadline)
            if place.rating == nil, let a = place.address {
                Text(a).font(.subheadline).foregroundColor(.secondary).lineLimit(1)
            }
            if !place.photoUrls.isEmpty {
                PhotoStrip(urls: place.photoUrls, height: 110).padding(.top, 6)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    PillButton(title: "Directions", icon: "arrow.triangle.turn.up.right.diamond.fill", filled: true) {
                        model.selected = place
                        model.openDirections(to: place)
                    }
                    if let tel = telURL(place.phone) {
                        PillButton(title: "Call", icon: "phone.fill") { openURL(tel) }
                    }
                    if let web = webURL(place.website) {
                        PillButton(title: "Website", icon: "globe") { openURL(web) }
                    }
                    if let share = place.shareURL {
                        ShareLink(item: share) {
                            Image(systemName: "square.and.arrow.up")
                                .font(.system(size: 14, weight: .semibold))
                                .frame(width: 40, height: 40)
                                .background(Circle().fill(Color.secondary.opacity(0.18)))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.top, 6)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
        .onTapGesture { model.select(place) }
    }
}

struct PhotoStrip: View {
    let urls: [String]
    var height: CGFloat = 130

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(urls, id: \.self) { u in
                    AsyncImage(url: URL(string: u)) { phase in
                        if let img = phase.image {
                            img.resizable().scaledToFill()
                        } else {
                            Color.secondary.opacity(0.15)
                        }
                    }
                    .frame(width: height * 1.4, height: height)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }
        }
    }
}

func telURL(_ phone: String?) -> URL? {
    guard let phone else { return nil }
    return URL(string: "tel:" + phone.filter { "+0123456789".contains($0) })
}

func webURL(_ site: String?) -> URL? {
    guard let site else { return nil }
    return URL(string: site.hasPrefix("http") ? site : "https://" + site)
}
