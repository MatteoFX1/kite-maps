import KiteShared
import SwiftUI
import UIKit

/**
 * The iPhone shell. Everything on screen is Kite's shared app (the same code as on Android, in
 * android/shared); this only shows it, tells it when Kite comes and goes, and starts the lock
 * screen's next turn, which only Swift may do.
 */
@main
struct KiteApp: App {
    @Environment(\.scenePhase) private var phase

    init() {
        if #available(iOS 16.2, *) { LiveTurns.shared.bridge = LiveTurn() }
    }

    var body: some Scene {
        WindowGroup {
            SharedApp()
                .ignoresSafeArea()
                .onOpenURL { url in MainViewControllerKt.kiteOpenLink(text: url.absoluteString) }
        }
        .onChange(of: phase) { p in
            switch p {
            case .active: MainViewControllerKt.kiteForeground()
            case .background: MainViewControllerKt.kiteBackground()
            default: break
            }
        }
    }
}

/** The shared app's screen. */
private struct SharedApp: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController() }
    func updateUIViewController(_ vc: UIViewController, context: Context) {}
}
