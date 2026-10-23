import SwiftUI
import VelaCore

/** The place sheet: photos, rating, open status, actions, hours and the About details. */
struct PlaceDetailView: View {
    let place: Place
    @Binding var expanded: Bool
    let onClose: () -> Void
    @Environment(\.openURL) private var openURL
    @State private var showHours = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header
                actions
                if !place.photoUrls.isEmpty { photos }
                if let summary = place.editorialSummary {
                    Text(summary).font(.body)
                }
                info
                if !place.hours.isEmpty { hours }
                if let review = place.featuredReview {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Review").font(.headline)
                        Text("“\(review)”").font(.body).italic()
                    }
                }
                ForEach(place.about, id: \.self) { section in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(section.title).font(.headline)
                        ForEach(section.items, id: \.self) { Text("• \($0)").font(.subheadline) }
                    }
                }
                Text(place.source == "google" ? "Place details from Google Maps" : "Place details from OpenStreetMap")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40)
        }
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text(place.name).font(.title2.bold())
                RatingLine(place: place)
                if place.permanentlyClosed {
                    Text("Permanently closed").font(.subheadline).foregroundStyle(.red)
                } else if let status = place.statusText {
                    Text(status).font(.subheadline).foregroundStyle(statusColor(place))
                }
            }
            Spacer()
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.headline)
                    .frame(width: 32, height: 32)
                    .background(Color.secondary.opacity(0.2), in: Circle())
            }
            .buttonStyle(.plain)
        }
    }

    private var actions: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ActionButton(title: "Directions", icon: "arrow.triangle.turn.up.right.diamond.fill", prominent: true) {
                    // Vela's own routing comes in stage 2; until then Apple Maps drives.
                    var c = URLComponents(string: "https://maps.apple.com/")!
                    c.queryItems = [
                        URLQueryItem(name: "daddr", value: "\(place.location.lat),\(place.location.lng)"),
                        URLQueryItem(name: "q", value: place.name),
                    ]
                    if let url = c.url { openURL(url) }
                }
                if let phone = place.phone, let url = URL(string: "tel:" + phone.filter { "+0123456789".contains($0) }) {
                    ActionButton(title: "Call", icon: "phone.fill") { openURL(url) }
                }
                if let site = place.website, let url = URL(string: site.hasPrefix("http") ? site : "https://" + site) {
                    ActionButton(title: "Website", icon: "globe") { openURL(url) }
                }
                if let url = place.shareURL {
                    ShareLink(item: url) {
                        Label("Share", systemImage: "square.and.arrow.up")
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 14)
                            .frame(height: 38)
                            .background(Color.secondary.opacity(0.18), in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var photos: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(place.photoUrls, id: \.self) { u in
                    AsyncImage(url: URL(string: u)) { img in
                        img.resizable().scaledToFill()
                    } placeholder: {
                        Color.secondary.opacity(0.15)
                    }
                    .frame(width: 200, height: 140)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
            }
        }
    }

    private var info: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let address = place.address {
                InfoRow(icon: "mappin.and.ellipse", text: address)
            }
            if let phone = place.phone {
                InfoRow(icon: "phone", text: phone)
            }
            if let site = place.website {
                InfoRow(icon: "globe", text: site.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: ""))
            }
        }
    }

    private var hours: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                withAnimation { showHours.toggle() }
            } label: {
                HStack {
                    Image(systemName: "clock").frame(width: 24)
                    Text("Hours").font(.headline)
                    Spacer()
                    Image(systemName: showHours ? "chevron.up" : "chevron.down")
                }
            }
            .buttonStyle(.plain)
            if showHours {
                ForEach(place.hours, id: \.self) { Text($0).font(.subheadline).padding(.leading, 32) }
            }
        }
    }
}

struct InfoRow: View {
    let icon: String
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: icon).frame(width: 24).foregroundStyle(.secondary)
            Text(text).font(.subheadline).textSelection(.enabled)
        }
    }
}

struct ActionButton: View {
    let title: String
    let icon: String
    var prominent = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 14)
                .frame(height: 38)
                .foregroundStyle(prominent ? Color.white : Color.primary)
                .background(prominent ? Color.accentColor : Color.secondary.opacity(0.18), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}
