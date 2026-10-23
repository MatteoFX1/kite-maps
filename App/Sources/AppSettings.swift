import Foundation
import SwiftUI
import VelaCore

enum ThemeMode: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
}

enum UnitsMode: String, CaseIterable, Identifiable {
    case auto, metric, imperial
    var id: String { rawValue }
    var label: String {
        switch self {
        case .auto: return "Automatic"
        case .metric: return "Kilometers"
        case .imperial: return "Miles"
        }
    }
}

/** Everything in Settings, kept between launches. */
final class AppSettings: ObservableObject {
    static let shared = AppSettings()
    private let d = UserDefaults.standard

    @Published var theme: ThemeMode { didSet { d.set(theme.rawValue, forKey: "theme") } }
    @Published var units: UnitsMode { didSet { d.set(units.rawValue, forKey: "units") } }
    @Published var voice: Bool { didSet { d.set(voice, forKey: "voice") } }
    @Published var traffic: Bool { didSet { d.set(traffic, forKey: "traffic") } }
    @Published var satellite: Bool { didSet { d.set(satellite, forKey: "satellite") } }
    @Published var avoidTolls: Bool { didSet { d.set(avoidTolls, forKey: "avoidTolls") } }
    @Published var avoidHighways: Bool { didSet { d.set(avoidHighways, forKey: "avoidHighways") } }
    /** "Use Vela without Google": search and routes from open data only. */
    @Published var noGoogle: Bool { didSet { d.set(noGoogle, forKey: "noGoogle") } }
    @Published var defaultMode: TravelMode { didSet { d.set(defaultMode.rawValue, forKey: "defaultMode") } }
    @Published var saved: [Place] { didSet { save(saved, "saved") } }
    @Published var recents: [String] { didSet { d.set(recents, forKey: "recents") } }

    private init() {
        theme = ThemeMode(rawValue: d.string(forKey: "theme") ?? "") ?? .system
        units = UnitsMode(rawValue: d.string(forKey: "units") ?? "") ?? .auto
        voice = d.object(forKey: "voice") as? Bool ?? true
        traffic = d.object(forKey: "traffic") as? Bool ?? false
        satellite = d.bool(forKey: "satellite")
        avoidTolls = d.bool(forKey: "avoidTolls")
        avoidHighways = d.bool(forKey: "avoidHighways")
        noGoogle = d.bool(forKey: "noGoogle")
        defaultMode = TravelMode(rawValue: d.integer(forKey: "defaultMode")) ?? .drive
        saved = (d.data(forKey: "saved")).flatMap { try? JSONDecoder().decode([Place].self, from: $0) } ?? []
        recents = d.stringArray(forKey: "recents") ?? []
    }

    private func save<T: Encodable>(_ v: T, _ key: String) {
        if let data = try? JSONEncoder().encode(v) { d.set(data, forKey: key) }
    }

    var colorScheme: ColorScheme? {
        switch theme {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    func isSaved(_ p: Place) -> Bool { saved.contains { $0.id == p.id } }

    func toggleSaved(_ p: Place) {
        if let i = saved.firstIndex(where: { $0.id == p.id }) { saved.remove(at: i) } else { saved.insert(p, at: 0) }
    }

    func addRecent(_ q: String) {
        var r = recents.filter { $0.caseInsensitiveCompare(q) != .orderedSame }
        r.insert(q, at: 0)
        recents = Array(r.prefix(12))
    }
}
