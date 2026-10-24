import SwiftUI

/** First launch: what Vela is, and the one choice that matters (Google's data, or open data only). */
struct WelcomeView: View {
    @ObservedObject var settings: AppSettings
    @Environment(\.colorScheme) private var scheme
    @State private var openOnly = false

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Spacer(minLength: 20)
            Image(systemName: "map.fill")
                .font(.system(size: 34, weight: .semibold))
                .foregroundColor(.white)
                .frame(width: 72, height: 72)
                .background(RoundedRectangle(cornerRadius: 20).fill(Vela.accent(scheme)))
            VStack(alignment: .leading, spacing: 8) {
                Text("Welcome to Vela").font(.largeTitle.weight(.bold))
                Text("Maps, search and turn-by-turn directions with no account and no tracking.")
                    .font(.title3).foregroundColor(.secondary)
            }
            VStack(spacing: 12) {
                option(title: "Best results", detail: "Search, place details, live traffic and routes from Google, asked anonymously. The map itself is open data.",
                       icon: "sparkles", picked: !openOnly) { openOnly = false }
                option(title: "Open data only", detail: "Nothing is sent to Google. Search from OpenStreetMap and routes from open servers.",
                       icon: "lock.shield", picked: openOnly) { openOnly = true }
            }
            Text("You can change this any time in Settings.").font(.footnote).foregroundColor(.secondary)
            Spacer()
            Button {
                settings.noGoogle = openOnly
                settings.welcomed = true
            } label: {
                Text("Get started").font(.headline)
                    .frame(maxWidth: .infinity).frame(height: 54)
                    .foregroundColor(Vela.onAccent(scheme))
                    .background(Capsule().fill(Vela.accent(scheme)))
            }
            .buttonStyle(.plain)
        }
        .padding(24)
        .background(Vela.sheet(scheme).ignoresSafeArea())
    }

    private func option(title: String, detail: String, icon: String, picked: Bool, tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: icon).font(.system(size: 20, weight: .semibold)).frame(width: 28)
                    .foregroundColor(picked ? Vela.accent(scheme) : .secondary)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.headline)
                    Text(detail).font(.subheadline).foregroundColor(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Image(systemName: picked ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22)).foregroundColor(picked ? Vela.accent(scheme) : .secondary)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 16).stroke(picked ? Vela.accent(scheme) : Color.secondary.opacity(0.3), lineWidth: picked ? 2 : 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundColor(.primary)
    }
}
