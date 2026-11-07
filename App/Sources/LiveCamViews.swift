import SwiftUI
import VelaCore

/** Loads a live camera's photo, and again every minute while it's on screen. */
@MainActor
final class LiveCamPhotoLoader: ObservableObject {
    enum State { case loading, ready(UIImage, Date?), failed }
    @Published var state: State = .loading
    @Published var refreshing = false
    private(set) var cam: LiveCam?

    func load(_ cam: LiveCam, force: Bool = false) async {
        if !force, self.cam?.id == cam.id, case .ready = state { return }
        if self.cam?.id != cam.id { state = .loading }
        self.cam = cam
        refreshing = true
        let current = await LiveCamStore.shared.current(cam)
        let got = await LiveCamStore.photo(current)
        guard self.cam?.id == cam.id else { return }
        refreshing = false
        if let got { state = .ready(got.0, got.1) } else if case .ready = state {} else { state = .failed }
    }

    var image: UIImage? { if case let .ready(i, _) = state { return i } else { return nil } }
    var taken: Date? { if case let .ready(_, d) = state { return d } else { return nil } }
}

/** How old a photo is, as a little pill: green when fresh, amber after 20 minutes, grey when it's likely stuck. */
struct PhotoAgePill: View {
    let taken: Date?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { ctx in
            let age = taken.map { max(0, ctx.date.timeIntervalSince($0)) }
            HStack(spacing: 5) {
                Circle().fill(color(age)).frame(width: 7, height: 7)
                Text(label(age)).font(.system(size: 12, weight: .bold)).monospacedDigit()
            }
            .foregroundColor(.white)
            .padding(.horizontal, 8).frame(height: 24)
            .background(Capsule().fill(Color.black.opacity(0.62)))
        }
    }

    private func label(_ age: TimeInterval?) -> String {
        guard let age else { return "Latest photo" }
        if age < 90 { return "Just now" }
        if age < 3600 { return "\(Int(age / 60)) min ago" }
        if age < 86_400 { return "\(Int(age / 3600)) h ago" }
        return "\(Int(age / 86_400)) days ago"
    }

    private func color(_ age: TimeInterval?) -> Color {
        guard let age else { return Color(hex: 0x22C55E) }
        if age < 1200 { return Color(hex: 0x22C55E) }
        if age < 7200 { return Color(hex: 0xF59E0B) }
        return Color(hex: 0x9CA3AF)
    }
}

/** The photo itself in a 16:9 frame, with a spinner while it loads and a calm note when it can't. */
struct LiveCamPhotoView: View {
    @ObservedObject var loader: LiveCamPhotoLoader
    var corner: CGFloat = 14
    var showsAge = true
    var retry: (() -> Void)?

    var body: some View {
        ZStack {
            Rectangle().fill(Color(hex: 0x1E293B))
            switch loader.state {
            case .loading:
                ProgressView().tint(.white)
            case .failed:
                VStack(spacing: 8) {
                    Image(systemName: "video.slash.fill").font(.system(size: 24)).foregroundColor(.white.opacity(0.7))
                    Text("No photo right now").font(.subheadline.weight(.semibold)).foregroundColor(.white.opacity(0.85))
                    if let retry {
                        Button("Try again", action: retry).font(.subheadline.weight(.bold)).foregroundColor(Color(hex: 0x5EEAD4))
                    }
                }
            case let .ready(img, taken):
                Image(uiImage: img).resizable().aspectRatio(contentMode: .fill)
                if showsAge {
                    VStack { HStack { PhotoAgePill(taken: taken); Spacer() }; Spacer() }.padding(8)
                }
            }
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
    }
}

/**
 * The card for a camera tapped on the map, or picked from a route's row: the photo, big, with
 * how old it is, the camera's name and road, who it's from, and a full-screen view.
 */
struct LiveCamCard: View {
    @ObservedObject var model: AppModel
    let cam: LiveCam
    @StateObject private var loader = LiveCamPhotoLoader()
    @State private var fullScreen = false
    @Environment(\.colorScheme) private var scheme

    /** The other cameras on the route, when opened from the route's row. */
    private var list: [LiveCam] { model.liveCamRoute }
    private var index: Int? { list.firstIndex { $0.id == cam.id } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: cam.source.isRoad ? "web.camera.fill" : "sun.horizon.fill")
                    .font(.system(size: 15, weight: .bold)).foregroundColor(.white)
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(Color(hex: cam.source.isRoad ? 0x0284C7 : 0x7C3AED)))
                VStack(alignment: .leading, spacing: 2) {
                    Text(cam.name).font(.system(size: 17, weight: .bold)).lineLimit(2)
                    if !cam.detail.isEmpty {
                        Text(cam.detail).font(.subheadline).foregroundColor(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                Button { close() } label: {
                    Image(systemName: "xmark").font(.footnote.weight(.bold)).foregroundColor(.secondary)
                        .frame(width: 30, height: 30).background(Circle().fill(Vela.chip(scheme)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close")
            }
            Button { if loader.image != nil { fullScreen = true } } label: {
                LiveCamPhotoView(loader: loader) { Task { await loader.load(cam, force: true) } }
                    .overlay(alignment: .bottomTrailing) {
                        if loader.image != nil {
                            Image(systemName: "arrow.up.left.and.arrow.down.right")
                                .font(.system(size: 13, weight: .bold)).foregroundColor(.white)
                                .frame(width: 30, height: 30).background(Circle().fill(Color.black.opacity(0.55)))
                                .padding(8)
                        }
                    }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Show the photo full screen")
            HStack(spacing: 8) {
                Text("Photo: \(cam.source.credit)").font(.caption).foregroundColor(.secondary).lineLimit(1)
                Spacer(minLength: 0)
                if let more = cam.moreURL {
                    Link(destination: more) {
                        Label(cam.source == .windy ? "Timelapse" : "Video", systemImage: "play.fill")
                            .font(.system(size: 14, weight: .bold))
                            .padding(.horizontal, 12).frame(height: 34)
                            .background(Capsule().fill(Vela.chip(scheme)))
                    }
                    .foregroundColor(.primary)
                }
                Button { Task { await loader.load(cam, force: true) } } label: {
                    Image(systemName: "arrow.clockwise").font(.system(size: 15, weight: .bold))
                        .rotationEffect(.degrees(loader.refreshing ? 360 : 0))
                        .animation(loader.refreshing ? .linear(duration: 0.8).repeatForever(autoreverses: false) : .default, value: loader.refreshing)
                        .frame(width: 34, height: 34).background(Circle().fill(Vela.chip(scheme)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Refresh photo")
            }
            if let i = index, list.count > 1 {
                HStack {
                    step(-1, from: i)
                    Spacer()
                    Text("Camera \(i + 1) of \(list.count) on your route").font(.subheadline.weight(.semibold)).foregroundColor(.secondary)
                    Spacer()
                    step(1, from: i)
                }
            }
        }
        .padding(14)
        .glass(RoundedRectangle(cornerRadius: 22))
        .frame(maxWidth: 520)
        .padding(.horizontal, 12)
        .task(id: cam.id) {
            await loader.load(cam)
            // A fresh photo every minute while the card is open; most cameras update every 1 to 5 minutes.
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                if Task.isCancelled { break }
                await loader.load(cam, force: true)
            }
        }
        .fullScreenCover(isPresented: $fullScreen) {
            LiveCamFullScreen(cam: cam, loader: loader) { fullScreen = false }
        }
    }

    private func step(_ d: Int, from i: Int) -> some View {
        let j = i + d
        let ok = list.indices.contains(j)
        return Button {
            guard ok else { return }
            UISelectionFeedbackGenerator().selectionChanged()
            model.liveCam = list[j]
        } label: {
            Image(systemName: d < 0 ? "chevron.left" : "chevron.right").font(.system(size: 16, weight: .bold))
                .frame(width: 40, height: 34).background(Capsule().fill(Vela.chip(scheme)))
        }
        .buttonStyle(.plain)
        .disabled(!ok)
        .opacity(ok ? 1 : 0.35)
        .accessibilityLabel(d < 0 ? "Previous camera" : "Next camera")
    }

    private func close() {
        withAnimation { model.liveCam = nil }
    }
}

/** The photo filling the screen, pinch to zoom, tap or swipe down to close. */
struct LiveCamFullScreen: View {
    let cam: LiveCam
    @ObservedObject var loader: LiveCamPhotoLoader
    let close: () -> Void
    @State private var scale: CGFloat = 1
    @State private var base: CGFloat = 1
    @State private var drag: CGSize = .zero

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let img = loader.image {
                Image(uiImage: img).resizable().aspectRatio(contentMode: .fit)
                    .scaleEffect(scale)
                    .offset(scale > 1 ? drag : CGSize(width: 0, height: max(0, drag.height)))
                    .gesture(MagnificationGesture()
                        .onChanged { scale = min(max(base * $0, 1), 5) }
                        .onEnded { _ in base = scale; if scale <= 1.02 { withAnimation { scale = 1; base = 1; drag = .zero } } })
                    .simultaneousGesture(DragGesture()
                        .onChanged { drag = $0.translation }
                        .onEnded { v in
                            if scale <= 1, v.translation.height > 120 { close() } else if scale <= 1 { withAnimation { drag = .zero } }
                        })
                    .onTapGesture(count: 2) { withAnimation { scale = scale > 1 ? 1 : 2.5; base = scale; drag = .zero } }
            }
            VStack {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(cam.name).font(.headline).foregroundColor(.white)
                        HStack(spacing: 8) {
                            PhotoAgePill(taken: loader.taken)
                            Text(cam.source.credit).font(.caption).foregroundColor(.white.opacity(0.7))
                        }
                    }
                    Spacer()
                    Button(action: close) {
                        Image(systemName: "xmark").font(.system(size: 16, weight: .bold)).foregroundColor(.white)
                            .frame(width: 40, height: 40).background(Circle().fill(Color.white.opacity(0.18)))
                    }
                    .accessibilityLabel("Close")
                }
                .padding(16)
                Spacer()
            }
        }
        .statusBarHidden()
    }
}

/**
 * Before pressing Go: the road cameras and webcams along the route, in the order you'll pass them,
 * so you can see the traffic, rain or snow ahead. Hidden when there are none.
 */
struct RouteLiveCams: View {
    @ObservedObject var model: AppModel
    let route: Route
    @ObservedObject private var settings = AppSettings.shared
    @ObservedObject private var store = LiveCamStore.shared
    @Environment(\.colorScheme) private var scheme

    private var cams: [(cam: LiveCam, along: Double)] {
        guard settings.liveCams, settings.liveCamsOnRoute else { return [] }
        _ = store.version
        let web = settings.showsWebcams ? store.webcams : []
        return LiveCams.along(store.road + web, line: route.polyline, webcamOffset: web.isEmpty ? 0 : LiveCams.webcamRouteOffset)
    }

    var body: some View {
        let list = cams
        VStack(alignment: .leading, spacing: 8) {
            if !list.isEmpty {
                HStack(spacing: 6) {
                    Image(systemName: "web.camera.fill").foregroundColor(Color(hex: 0x0284C7))
                    Text("Cameras on your route").font(.system(size: 16, weight: .bold))
                    Text("\(list.count)").font(.system(size: 14, weight: .bold)).foregroundColor(.secondary)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(list, id: \.cam.id) { item in
                            Button {
                                UISelectionFeedbackGenerator().selectionChanged()
                                model.liveCamRoute = list.map(\.cam)
                                withAnimation { model.liveCam = item.cam }
                            } label: {
                                RouteCamThumb(cam: item.cam, along: item.along)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        // Asked again when the route changes, or a setting it depends on is switched on.
        .task(id: "\(route.id)|\(settings.liveCams)|\(settings.liveCamsOnRoute)|\(settings.showsWebcams)") {
            guard settings.liveCams && settings.liveCamsOnRoute else { return }
            store.ensureLoaded()
            if settings.showsWebcams { store.loadWindyRoute(route.id.uuidString, line: route.polyline) }
        }
    }
}

private struct RouteCamThumb: View {
    let cam: LiveCam
    let along: Double
    @StateObject private var loader = LiveCamPhotoLoader()

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            LiveCamPhotoView(loader: loader, corner: 12, showsAge: false)
                .frame(width: 168)
                .overlay(alignment: .bottomLeading) {
                    Text(along < 400 ? "Start" : "In \(Fmt.distance(along))")
                        .font(.system(size: 12, weight: .bold)).foregroundColor(.white)
                        .padding(.horizontal, 7).frame(height: 22)
                        .background(Capsule().fill(Color.black.opacity(0.6)))
                        .padding(6)
                }
            Text(cam.name).font(.system(size: 13, weight: .semibold)).lineLimit(1).frame(width: 168, alignment: .leading)
        }
        .task(id: cam.id) { await loader.load(cam) }
    }
}
