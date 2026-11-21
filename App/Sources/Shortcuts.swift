import AppIntents
import KiteShared

/** "Hey Siri, take me home with Kite", also in Spotlight and the Shortcuts app. Kite does the rest. */
struct DirectionsHomeIntent: AppIntent {
    static var title: LocalizedStringResource = "Directions home"
    static var description = IntentDescription("Opens Kite with directions to your home.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        MainViewControllerKt.kitePerform(kind: "home")
        return .result()
    }
}

struct DirectionsWorkIntent: AppIntent {
    static var title: LocalizedStringResource = "Directions to work"
    static var description = IntentDescription("Opens Kite with directions to work.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        MainViewControllerKt.kitePerform(kind: "work")
        return .result()
    }
}

struct FindMyCarIntent: AppIntent {
    static var title: LocalizedStringResource = "Find my car"
    static var description = IntentDescription("Opens Kite with the way back to where you parked.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        MainViewControllerKt.kitePerform(kind: "car")
        return .result()
    }
}

struct KiteShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: DirectionsHomeIntent(), phrases: [
            "Take me home with \(.applicationName)",
            "\(.applicationName) directions home",
        ])
        AppShortcut(intent: DirectionsWorkIntent(), phrases: [
            "Take me to work with \(.applicationName)",
            "\(.applicationName) directions to work",
        ])
        AppShortcut(intent: FindMyCarIntent(), phrases: [
            "Find my car with \(.applicationName)",
            "Where did I park in \(.applicationName)",
        ])
    }
}
