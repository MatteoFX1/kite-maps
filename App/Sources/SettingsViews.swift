import SwiftUI
import VelaCore

struct LayersSheet: View {
    @ObservedObject var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("Layers").font(.system(size: 26, weight: .heavy))
                Spacer()
                CircleIconButton(icon: "xmark") { dismiss() }
            }
            Text("Map type").font(.subheadline.weight(.semibold)).foregroundColor(.secondary)
            HStack(spacing: 14) {
                tile("Default", icon: "map", on: !settings.satellite) { settings.satellite = false }
                tile("Satellite", icon: "globe.americas.fill", on: settings.satellite) { settings.satellite = true }
            }
            Text("Map details").font(.subheadline.weight(.semibold)).foregroundColor(.secondary)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), alignment: .leading, spacing: 14) {
                tile("Traffic", icon: "car.2.fill", on: settings.traffic) { settings.traffic.toggle() }
                tile("Transit", icon: "tram.fill", on: settings.transitLines) { settings.transitLines.toggle() }
                tile("Terrain", icon: "mountain.2.fill", on: settings.terrain) { settings.terrain.toggle() }
                tile("Speed cameras", badge: .speed, on: settings.speedCameras) { settings.speedCameras.toggle() }
                tile("Plate cameras", badge: .plate, on: settings.plateCameras) { settings.plateCameras.toggle() }
                tile("Road alerts", waze: Waze.shares.contains(.police) ? .police : .closure, on: settings.wazeOn && settings.wazeOnMap) {
                    if settings.waze && settings.wazeOnMap { settings.wazeOnMap = false } else { settings.waze = true; settings.wazeOnMap = true }
                }
                if Waze.shares.contains(.police) {
                    tile("Police heat map", icon: "flame.fill", on: settings.wazeOn && settings.wazeHeatmap) {
                        if settings.waze && settings.wazeHeatmap { settings.wazeHeatmap = false } else { settings.waze = true; settings.wazeHeatmap = true }
                    }
                }
            }
            Spacer()
        }
        .padding(20)
        .background(Vela.sheet(scheme).ignoresSafeArea())
        .presentationDetents([.height(560)])
    }

    private func tile(_ title: String, icon: String = "", badge: RoadCamera.Kind? = nil, waze: WazeAlert.Kind? = nil,
                      on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Group {
                    if let badge { CameraBadge(kind: badge, size: 34) }
                    else if let waze { WazeBadge(kind: waze, size: 34) }
                    else { Image(systemName: icon).font(.system(size: 26)) }
                }
                    .frame(width: 74, height: 64)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Vela.chip(scheme)))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(on ? Vela.accent(scheme) : .clear, lineWidth: 3))
                Text(title).font(.caption.weight(on ? .semibold : .regular))
                    .foregroundColor(on ? Vela.accent(scheme) : .primary)
            }
        }
        .buttonStyle(.plain)
    }
}

struct SettingsView: View {
    @ObservedObject var settings: AppSettings
    /** A page of the dock rather than a sheet: no Done button. */
    var inTab = false
    /** Opens the search for choosing home or work (the dock's page has it). */
    var pick: ((PickKind) -> Void)? = nil
    let onChange: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        if DemoScript.step == "wazesettings" {
            // Straight into the Waze screen, for the build's screenshot of it.
            NavigationView { WazeSettingsView(settings: settings) }.navigationViewStyle(.stack)
        } else {
            main
        }
    }

    private var main: some View {
        NavigationView {
            Form {
                // Grouped by what each setting is for, with an icon each, like the iPhone's own Settings.
                Section("Driving") {
                    Toggle(isOn: $settings.voice) { SettingLabel("Voice guidance", icon: "speaker.wave.2.fill", color: 0x0F766E) }
                    Picker(selection: $settings.defaultMode) {
                        ForEach(TravelMode.allCases) { Text($0.label).tag($0) }
                    } label: { SettingLabel("Default travel mode", icon: "car.fill", color: 0x2563EB) }
                    Toggle(isOn: $settings.avoidTolls) { SettingLabel("Avoid tolls", icon: "sterlingsign.circle.fill", color: 0xF59E0B) }
                    Toggle(isOn: $settings.avoidHighways) {
                        SettingLabel(Fmt.yards ? "Avoid motorways" : "Avoid highways", icon: "road.lanes", color: 0x1D4ED8)
                    }
                }
                Section {
                    Toggle(isOn: $settings.speedCameras) { SettingLabel("Speed cameras on the map", icon: "camera.fill", color: 0xDC2626) }
                    Toggle(isOn: $settings.cameraWarnings) { SettingLabel("Say when a speed camera is ahead", icon: "speaker.wave.3.fill", color: 0xDC2626) }
                    Toggle(isOn: $settings.speedingAlert) { SettingLabel("Say when I'm over the speed limit", icon: "gauge.with.dots.needle.67percent", color: 0xEA580C) }
                    Toggle(isOn: $settings.plateCameras) {
                        SettingLabel(Fmt.yards ? "Number plate cameras" : "License plate cameras", icon: "number", color: 0x6B7280)
                    }
                } header: {
                    Text("Cameras & speed")
                } footer: {
                    Text("Speed cameras and \(Fmt.yards ? "number" : "license") plate cameras from OpenStreetMap's open map, shown on the map and on your route. Some countries, like France and Germany, don't allow warnings of speed cameras, so check before you turn the voice on there.")
                }
                Section {
                    NavigationLink {
                        WazeSettingsView(settings: settings)
                    } label: {
                        LabeledContent {
                            Text(settings.wazeOn ? "On" : "Off")
                        } label: {
                            SettingLabel("Road alerts", icon: "exclamationmark.triangle.fill", color: 0x7C3AED)
                        }
                    }
                } header: {
                    Text("Live reports")
                } footer: {
                    Text(Waze.shares.contains(.police) ? "Live police and crash reports from Waze drivers, road closures, and police aircraft."
                         : "Live road closures from Waze, and police planes and helicopters.")
                }
                Section("Map") {
                    Picker(selection: $settings.theme) {
                        ForEach(ThemeMode.allCases) { Text($0.label).tag($0) }
                    } label: { SettingLabel("Theme", icon: "circle.lefthalf.filled", color: 0x334155) }
                    Picker(selection: $settings.units) {
                        ForEach(UnitsMode.allCases) { Text($0.label).tag($0) }
                    } label: { SettingLabel("Distance units", icon: "ruler.fill", color: 0x0891B2) }
                    Toggle(isOn: $settings.parkingButton) { SettingLabel("Parking button on the map", icon: "parkingsign", color: 0x2563EB) }
                }
                if pick != nil || settings.home != nil || settings.work != nil {
                    Section("My places") {
                        if let pick {
                            Button { pick(.home) } label: {
                                LabeledContent { Text(settings.home?.name ?? "Set") } label: { SettingLabel("Home", icon: "house.fill", color: 0x16A34A) }
                            }
                            .foregroundColor(.primary)
                            Button { pick(.work) } label: {
                                LabeledContent { Text(settings.work?.name ?? "Set") } label: { SettingLabel("Work", icon: "briefcase.fill", color: 0x9333EA) }
                            }
                            .foregroundColor(.primary)
                        } else {
                            if let h = settings.home {
                                LabeledContent { Text(h.name) } label: { SettingLabel("Home", icon: "house.fill", color: 0x16A34A) }
                                Button("Remove home", role: .destructive) { settings.home = nil }
                            }
                            if let w = settings.work {
                                LabeledContent { Text(w.name) } label: { SettingLabel("Work", icon: "briefcase.fill", color: 0x9333EA) }
                                Button("Remove work", role: .destructive) { settings.work = nil }
                            }
                        }
                    }
                }
                Section {
                    Toggle(isOn: $settings.noGoogle) { SettingLabel("Use Kite without Google", icon: "lock.fill", color: 0x475569) }
                } header: {
                    Text("Privacy")
                } footer: {
                    Text("Off (the usual): search, place details, routes and live traffic come from Google, asked anonymously with no account. On: everything comes from free open-data services instead and nothing goes to Google, but search and routes are less complete.")
                }
                Section("About") {
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                    Link("Kite Maps on GitHub", destination: URL(string: "https://github.com/matteofx1/kite-maps")!)
                    Link("Based on Vela Maps for Android", destination: URL(string: "https://github.com/PimpinPumpkin/Vela")!)
                    Text("Free software under the GPL-3.0. Map © OpenFreeMap, OpenMapTiles, OpenStreetMap contributors. Routing © FOSSGIS OSRM. Imagery © Esri.")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .kiteList()
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(inTab ? .large : .inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    if !inTab {
                        Button("Done") {
                            onChange()
                            dismiss()
                        }
                    }
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}

/** A settings row's name with a small coloured icon in front, like the iPhone's own Settings. */
struct SettingLabel: View {
    let title: String
    let icon: String
    let color: UInt32

    init(_ title: String, icon: String, color: UInt32) {
        self.title = title
        self.icon = icon
        self.color = color
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(.white)
                .frame(width: 29, height: 29)
                .background(RoundedRectangle(cornerRadius: 7, style: .continuous).fill(Color(hex: color)))
            Text(title)
        }
    }
}

/** Settings › Road alerts: which live Waze reports to show and warn about, the police heat map and police aircraft. */
struct WazeSettingsView: View {
    @ObservedObject var settings: AppSettings
    @State private var remembered = 0
    @State private var confirmClear = false

    private let kinds: [(WazeAlert.Kind, String)] = [
        (.police, "Police and mobile speed traps"),
        (.accident, "Crashes"),
        (.hazard, "Hazards (stopped cars, objects, potholes)"),
        (.weather, "Weather (fog, ice, flooding)"),
        (.roadworks, "Roadworks"),
        (.closure, "Road closures"),
        (.jam, "Traffic jams reported by drivers"),
    ]

    private let ages: [(Int, String)] = [(30, "30 minutes"), (60, "1 hour"), (120, "2 hours"), (240, "4 hours"), (0, "Keep them all")]

    /** Whether Waze answered last time, so it's easy to tell when it has stopped working. */
    private var connection: (text: String, color: Color) {
        guard let s = Waze.lastStatus else { return ("Not checked yet: open the map at city zoom", .gray) }
        let mins = Int(Date().timeIntervalSince(s.at) / 60)
        let when = mins < 1 ? "just now" : "\(mins) min ago"
        switch s.code {
        case 200: return ("Working\(s.viaPage ? " (through the live map page)" : ""): \(s.count) reports, \(when)", Color(hex: 0x188038))
        case 0: return ("No connection (\(when))", .orange)
        case 401, 403: return ("Waze turned Kite away (\(s.code), \(when)). It may work again later.", .red)
        case -1: return ("Waze sent something Kite can't read (\(when))", .red)
        default: return ("Waze error \(s.code) (\(when))", .orange)
        }
    }

    /** How OpenWeb Ninja answered last time, in plain words. */
    private func ninjaState(_ s: Waze.Status) -> (text: String, color: Color) {
        let mins = Int(Date().timeIntervalSince(s.at) / 60)
        let when = mins < 1 ? "just now" : "\(mins) min ago"
        switch s.code {
        case 200: return ("Working: \(s.summary), \(when)", Color(hex: 0x188038))
        case 0: return ("No connection (\(when))", .orange)
        case 401, 403: return ("Key not accepted: check it's copied in full", .red)
        case 429: return ("No checks left this month", .red)
        default: return ("OpenWeb Ninja error \(s.code) (\(when))", .orange)
        }
    }

    var body: some View {
        Form {
            Section {
                Toggle("Use Waze reports", isOn: $settings.waze).disabled(settings.noGoogle)
            } footer: {
                Text(settings.noGoogle
                     ? "Off while \"Use Kite without Google\" is on, since Waze belongs to Google."
                     : "Kite reads the public Waze live map, with no account. Waze sees the area you're looking at, anonymously. It doesn't officially allow other apps to do this, so it may stop working one day.")
            }
            if settings.wazeOn {
                Section {
                    HStack(spacing: 8) {
                        Circle().fill(connection.color).frame(width: 10, height: 10)
                        Text(connection.text).font(.subheadline)
                    }
                    if let s = Waze.lastStatus, s.code == 200 {
                        // What Waze actually sent, so it's easy to see if a kind has gone missing.
                        LabeledContent("Last answer", value: s.summary).font(.subheadline)
                    }
                } header: {
                    Text("Connection")
                } footer: {
                    if let s = Waze.lastStatus, s.code != 200 {
                        // What the hidden live map page saw, to send to whoever looks into it.
                        Text("Details: " + WazeWeb.shared.note).font(.caption2.monospaced())
                    }
                }
                Section {
                    TextField("Paste your key here", text: $settings.ninjaKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.subheadline.monospaced())
                    if WazeNinja.on {
                        LabeledContent("Checks used this month", value: "\(WazeNinja.usedThisMonth) of 50")
                        if let s = WazeNinja.lastStatus {
                            HStack(spacing: 8) {
                                Circle().fill(ninjaState(s).color).frame(width: 10, height: 10)
                                Text(ninjaState(s).text).font(.subheadline)
                            }
                        }
                    } else {
                        Link("Get a free key", destination: URL(string: "https://app.openwebninja.com/api/waze")!)
                    }
                } header: {
                    Text("Police and crashes")
                } footer: {
                    Text("Waze's live map only shares road closures now. For police, mobile speed cameras and crashes, Kite can use OpenWeb Ninja's Waze service with your own free key: sign up (no card needed), open the Waze API and copy the key in here. The free plan has 50 checks a month, so Kite only checks while you're navigating, once every 10 minutes: about 8 hour-long drives.")
                }
                Section("Show and warn about") {
                    // Only what Waze still shares (see Waze.shares): no switches that can't do anything.
                    ForEach(kinds.filter { Waze.shares.contains($0.0) }, id: \.0) { item in
                        let k = item.0
                        Toggle(isOn: Binding(
                            get: { settings.wazeKinds.contains(k) },
                            set: { on in if on { settings.wazeKinds.insert(k) } else { settings.wazeKinds.remove(k) } }
                        )) {
                            HStack(spacing: 10) {
                                WazeBadge(kind: k, size: 26)
                                Text(item.1)
                            }
                        }
                    }
                }
                Section {
                    Toggle("Show reports on the map", isOn: $settings.wazeOnMap)
                    Toggle("Chime and say them while navigating", isOn: $settings.wazeVoice)
                    Picker("Ignore reports older than", selection: $settings.wazeMaxAge) {
                        ForEach(ages, id: \.0) { Text($0.1).tag($0.0) }
                    }
                } header: {
                    Text("Alerts")
                } footer: {
                    Text("While navigating, a card pops up for reports on your route, only ones made by drivers going your way.")
                }
                if Waze.shares.contains(.police) {
                    Section {
                        Toggle("Police heat map", isOn: $settings.wazeHeatmap)
                        Toggle("Police risk score while driving", isOn: $settings.policeRisk)
                        LabeledContent("Police reports remembered", value: "\(remembered)")
                        if remembered > 0 {
                            Button("Forget them all", role: .destructive) { confirmClear = true }
                        }
                    } footer: {
                        Text("A glow where police get reported most, and a low, medium or high risk for the road ahead (counting reports made at this time of day most). Waze only shares what's live now, so Kite remembers the reports it sees on this phone, for 90 days and never anywhere else, and both fill in the more you use it.")
                    }
                }
            }
            Section {
                Toggle("Police planes and helicopters", isOn: $settings.policeAircraft)
            } header: {
                Text("Aircraft")
            } footer: {
                Text("While navigating, warns when a police aircraft is within about 10 km, from open flight tracking (adsb.lol), not Waze. It spots them by their police callsign (like NPAS in the UK), and some don't broadcast at all, so no warning doesn't mean the sky is clear.")
            }
        }
        .kiteList()
        .navigationTitle("Road alerts")
        .onAppear { remembered = WazeHistory.shared.sightings.count }
        .confirmationDialog("Forget the \(remembered) police reports Kite remembered? The heat map and risk score start again from nothing.",
                            isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Forget them", role: .destructive) {
                WazeHistory.shared.clear()
                remembered = 0
            }
        }
        .navigationBarTitleDisplayMode(.inline)
    }
}
