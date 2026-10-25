import SwiftUI

@main
struct VelaApp: App {
    init() { WazeWeb.install() }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
