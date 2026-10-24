import MapKit
import SwiftUI
import VelaCore
import WebKit

/**
 * The first tile of a place's photos, like Google Maps: Google Street View looking at the place,
 * or Apple's Look Around where Google has none. Tapping it opens the panorama full screen.
 */
struct StreetViewTile: View {
    let place: Place
    var height: CGFloat = 140

    private enum Found {
        case google(StreetView.Panorama, heading: Double)
        case apple(MKLookAroundScene, UIImage?)
    }

    @State private var found: Found?
    @State private var loaded = false
    @State private var open = false

    var body: some View {
        Group {
            if let found {
                Button { open = true } label: { tile(found) }
                    .buttonStyle(.plain)
                    .transition(.opacity)
            } else if !loaded {
                RoundedRectangle(cornerRadius: 10).fill(Color.secondary.opacity(0.15))
                    .frame(width: height * 1.4, height: height)
            }
        }
        .task(id: place.id) { await load() }
        .fullScreenCover(isPresented: $open) {
            switch found {
            case let .google(pano, heading)?: GoogleStreetViewScreen(name: place.name, pano: pano, heading: heading)
            case let .apple(scene, _)?: LookAroundScreen(name: place.name, scene: scene)
            case nil: EmptyView()
            }
        }
    }

    @ViewBuilder
    private func tile(_ f: Found) -> some View {
        ZStack(alignment: .bottomLeading) {
            switch f {
            case let .google(pano, heading):
                AsyncImage(url: StreetView.thumbnailURL(pano, heading: heading)) { phase in
                    if let img = phase.image { img.resizable().scaledToFill() } else { Color.secondary.opacity(0.15) }
                }
            case let .apple(_, image):
                if let image { Image(uiImage: image).resizable().scaledToFill() } else { Color.secondary.opacity(0.15) }
            }
            LinearGradient(colors: [.clear, .black.opacity(0.55)], startPoint: .center, endPoint: .bottom)
            Label(isGoogle(f) ? "Street View" : "Look Around", systemImage: isGoogle(f) ? "rotate.3d" : "binoculars.fill")
                .font(.caption.weight(.semibold))
                .foregroundColor(.white)
                .padding(8)
        }
        .frame(width: height * 1.4, height: height)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .contentShape(Rectangle())
    }

    private func isGoogle(_ f: Found) -> Bool {
        if case .google = f { return true }
        return false
    }

    private func load() async {
        found = nil
        loaded = false
        defer { loaded = true }
        // Google first (the screenshot run can skip it to show Apple's), then Apple.
        if DemoScript.step != "lookaround", !AppSettings.shared.noGoogle,
           let pano = await StreetView.find(near: place.location) {
            withAnimation { found = .google(pano, heading: Geo.bearing(pano.location, place.location)) }
            if DemoScript.step == "streetview" { open = true }
            return
        }
        let coord = CLLocationCoordinate2D(latitude: place.location.lat, longitude: place.location.lng)
        guard let scene = try? await MKLookAroundSceneRequest(coordinate: coord).scene else { return }
        let options = MKLookAroundSnapshotter.Options()
        options.size = CGSize(width: height * 1.4 * 2, height: height * 2)
        let image = try? await MKLookAroundSnapshotter(scene: scene, options: options).snapshot.image
        withAnimation { found = .apple(scene, image) }
    }
}

/** Google's Street View viewer, full screen: drag to look around, tap the arrows on the road to move. */
struct GoogleStreetViewScreen: View {
    let name: String
    let pano: StreetView.Panorama
    let heading: Double
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            WebPage(url: StreetView.viewerURL(pano, heading: heading)).ignoresSafeArea()
            PanoramaBar(name: name, source: "Street View") { dismiss() }
        }
    }
}

/** Apple's Look Around, full screen. */
struct LookAroundScreen: View {
    let name: String
    let scene: MKLookAroundScene
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            LookAroundView(scene: scene).ignoresSafeArea()
            PanoramaBar(name: name, source: "Look Around") { dismiss() }
        }
    }
}

/** The close button and the place's name over a panorama. */
struct PanoramaBar: View {
    let name: String
    let source: String
    let close: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Button(action: close) {
                Image(systemName: "arrow.left").font(.system(size: 17, weight: .bold)).foregroundColor(.white)
                    .frame(width: 40, height: 40).background(Circle().fill(Color.black.opacity(0.55)))
            }
            .accessibilityLabel("Close")
            VStack(alignment: .leading, spacing: 0) {
                Text(name).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(source).font(.caption).opacity(0.8)
            }
            .foregroundColor(.white)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(Capsule().fill(Color.black.opacity(0.55)))
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }
}

struct WebPage: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        let web = WKWebView(frame: .zero, configuration: cfg)
        web.isOpaque = false
        web.backgroundColor = .black
        web.scrollView.isScrollEnabled = false
        web.scrollView.bounces = false
        web.load(URLRequest(url: url))
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {}
}

struct LookAroundView: UIViewControllerRepresentable {
    let scene: MKLookAroundScene

    func makeUIViewController(context: Context) -> MKLookAroundViewController {
        let vc = MKLookAroundViewController(scene: scene)
        vc.showsRoadLabels = true
        return vc
    }

    func updateUIViewController(_ vc: MKLookAroundViewController, context: Context) {}
}
