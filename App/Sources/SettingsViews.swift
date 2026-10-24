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
            }
            Spacer()
        }
        .padding(20)
        .presentationDetents([.height(470)])
    }

    private func tile(_ title: String, icon: String = "", badge: RoadCamera.Kind? = nil, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Group {
                    if let badge { CameraBadge(kind: badge, size: 34) } else { Image(systemName: icon).font(.system(size: 26)) }
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
                    Toggle("Avoid highways", isOn: $settings.avoidHighways)
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
                    Toggle("License plate cameras", isOn: $settings.plateCameras)
                } header: {
                    Text("Cameras")
                } footer: {
                    Text("Fixed speed cameras and license plate readers (DeFlock) mapped in OpenStreetMap. Saying a camera is ahead is off by default, because some countries restrict it.")
                }
                Section {
                    Toggle("Use Vela without Google", isOn: $settings.noGoogle)
                } header: {
                    Text("Data source & privacy")
                } footer: {
                    Text("On: search comes from OpenStreetMap (Photon) and routes from the FOSSGIS servers, and nothing is sent to Google. Off: Google search, place details and live traffic, with no account and no key.")
                }
                Section("About") {
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                    Link("Vela for iPhone on GitHub", destination: URL(string: "https://github.com/matteofx1/vela-ios")!)
                    Link("Vela for Android", destination: URL(string: "https://github.com/PimpinPumpkin/Vela")!)
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
