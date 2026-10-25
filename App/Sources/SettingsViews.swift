import SwiftUI
import VelaCore

struct LayersSheet: View {
    @ObservedObject var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("Layers").font(.title3.weight(.semibold))
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
                tile("Waze reports", waze: .police, on: settings.wazeOn && settings.wazeOnMap) {
                    if settings.waze && settings.wazeOnMap { settings.wazeOnMap = false } else { settings.waze = true; settings.wazeOnMap = true }
                }
                tile("Police heat map", icon: "flame.fill", on: settings.wazeOn && settings.wazeHeatmap) {
                    if settings.waze && settings.wazeHeatmap { settings.wazeHeatmap = false } else { settings.waze = true; settings.wazeHeatmap = true }
                }
            }
            Spacer()
        }
        .padding(20)
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
    let onChange: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Form {
                Section("Appearance") {
                    Picker("Theme", selection: $settings.theme) {
                        ForEach(ThemeMode.allCases) { Text($0.label).tag($0) }
                    }
                    Picker("Distance units", selection: $settings.units) {
                        ForEach(UnitsMode.allCases) { Text($0.label).tag($0) }
                    }
                }
                Section("Navigation") {
                    Toggle("Voice guidance", isOn: $settings.voice)
                    Toggle("Avoid tolls", isOn: $settings.avoidTolls)
                    Toggle(Fmt.yards ? "Avoid motorways" : "Avoid highways", isOn: $settings.avoidHighways)
                    Toggle("Say when a camera is ahead", isOn: $settings.cameraWarnings)
                    Toggle("Say when I'm over the speed limit", isOn: $settings.speedingAlert)
                    Picker("Default travel mode", selection: $settings.defaultMode) {
                        ForEach(TravelMode.allCases) { Text($0.label).tag($0) }
                    }
                }
                Section("My places") {
                    Toggle("Parking button on the map", isOn: $settings.parkingButton)
                    if let h = settings.home {
                        LabeledContent("Home", value: h.name)
                        Button("Remove home", role: .destructive) { settings.home = nil }
                    }
                    if let w = settings.work {
                        LabeledContent("Work", value: w.name)
                        Button("Remove work", role: .destructive) { settings.work = nil }
                    }
                }
                Section {
                    Toggle("Speed cameras", isOn: $settings.speedCameras)
                    Toggle(Fmt.yards ? "Number plate cameras" : "License plate cameras", isOn: $settings.plateCameras)
                } header: {
                    Text("Cameras")
                } footer: {
                    Text("Fixed speed cameras and license plate readers (DeFlock) mapped in OpenStreetMap. Saying a camera is ahead is off by default, because some countries restrict it.")
                }
                Section {
                    NavigationLink {
                        WazeSettingsView(settings: settings)
                    } label: {
                        LabeledContent("Waze integration", value: settings.wazeOn ? "On" : "Off")
                    }
                } footer: {
                    Text("Live police, crash and hazard reports from Waze drivers.")
                }
                Section {
                    Toggle("Use Kite without Google", isOn: $settings.noGoogle)
                } header: {
                    Text("Data source & privacy")
                } footer: {
                    Text("On: search comes from OpenStreetMap (Photon) and routes from the FOSSGIS servers, and nothing is sent to Google. Off: Google search, place details and live traffic, with no account and no key.")
                }
                Section("About") {
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                    Link("Kite Maps on GitHub", destination: URL(string: "https://github.com/matteofx1/kite-maps")!)
                    Link("Based on Vela Maps for Android", destination: URL(string: "https://github.com/PimpinPumpkin/Vela")!)
                    Text("Free software under the GPL-3.0. Map © OpenFreeMap, OpenMapTiles, OpenStreetMap contributors. Routing © FOSSGIS OSRM. Imagery © Esri.")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        onChange()
                        dismiss()
                    }
                }
            }
        }
    }
}

/** Settings › Waze integration: which live reports to show and warn about, and the police heat map. */
struct WazeSettingsView: View {
    @ObservedObject var settings: AppSettings

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
                Section("Show and warn about") {
                    ForEach(kinds, id: \.0) { item in
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
                Section {
                    Toggle("Police heat map", isOn: $settings.wazeHeatmap)
                    Toggle("Police risk score while driving", isOn: $settings.policeRisk)
                } footer: {
                    Text("A glow where police get reported most, and a low, medium or high risk for the road ahead (counting reports made at this time of day most). Waze only shares what's live now, so Kite remembers the reports it sees on this phone, for 90 days and never anywhere else, and both fill in the more you use it.")
                }
            }
            Section {
                Toggle("Police planes and helicopters", isOn: $settings.policeAircraft)
            } header: {
                Text("Aircraft")
            } footer: {
                Text("While navigating, warns when a police aircraft is within about 10 km, from open flight tracking (adsb.lol), not Waze. Some police aircraft don't broadcast their position, so they won't show.")
            }
        }
        .navigationTitle("Waze integration")
        .navigationBarTitleDisplayMode(.inline)
    }
}
