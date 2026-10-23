import SwiftUI
import MapLibre
import VelaCore

/** The MapLibre map with the OpenFreeMap basemap (the same tiles Vela uses on Android). */
struct MapView: UIViewRepresentable {
    @ObservedObject var model: MapModel
    @Environment(\.colorScheme) private var colorScheme

    static func styleURL(dark: Bool) -> URL {
        URL(string: dark ? "https://tiles.openfreemap.org/styles/dark" : "https://tiles.openfreemap.org/styles/liberty")!
    }

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: Self.styleURL(dark: colorScheme == .dark))
        map.delegate = context.coordinator
        map.showsUserLocation = true
        map.compassViewMargins = CGPoint(x: 12, y: 120)
        map.setCenter(CLLocationCoordinate2D(latitude: 40.7484, longitude: -73.9857), zoomLevel: 11, animated: false)
        context.coordinator.dark = colorScheme == .dark
        model.mapView = map
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let c = context.coordinator
        let dark = colorScheme == .dark
        if c.dark != dark {
            c.dark = dark
            map.styleURL = Self.styleURL(dark: dark)
        }
        c.sync(map: map, places: model.results, selected: model.selected)
    }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        let model: MapModel
        var dark = false
        private var pins: [String: MLNPointAnnotation] = [:]
        private var placeByPin: [ObjectIdentifier: Place] = [:]
        private var movedToUser = false

        init(model: MapModel) { self.model = model }

        /** Put one pin per result on the map, and keep the selected one highlighted. */
        func sync(map: MLNMapView, places: [Place], selected: Place?) {
            var shown = places
            if let selected, !shown.contains(where: { $0.id == selected.id }) { shown.append(selected) }
            let wanted = Set(shown.map(\.id))
            for (id, pin) in pins where !wanted.contains(id) {
                map.removeAnnotation(pin)
                placeByPin[ObjectIdentifier(pin)] = nil
                pins[id] = nil
            }
            for p in shown where pins[p.id] == nil {
                let pin = MLNPointAnnotation()
                pin.coordinate = CLLocationCoordinate2D(latitude: p.location.lat, longitude: p.location.lng)
                pin.title = p.name
                pins[p.id] = pin
                placeByPin[ObjectIdentifier(pin)] = p
                map.addAnnotation(pin)
            }
            if let selected, let pin = pins[selected.id] {
                if !map.selectedAnnotations.contains(where: { $0 === pin }) {
                    map.selectAnnotation(pin, animated: false)
                }
            } else {
                map.selectedAnnotations.forEach { map.deselectAnnotation($0, animated: false) }
            }
        }

        func mapView(_ mapView: MLNMapView, didSelect annotation: MLNAnnotation) {
            guard let pin = annotation as? MLNPointAnnotation,
                  let place = placeByPin[ObjectIdentifier(pin)] else { return }
            Task { @MainActor in
                if self.model.selected?.id != place.id { self.model.select(place) }
            }
        }

        func mapView(_ mapView: MLNMapView, annotationCanShowCallout annotation: MLNAnnotation) -> Bool {
            false
        }

        func mapView(_ mapView: MLNMapView, didUpdate userLocation: MLNUserLocation?) {
            // Jump to the user once, the first time a location arrives.
            guard !movedToUser, let loc = userLocation?.location else { return }
            movedToUser = true
            mapView.setCenter(loc.coordinate, zoomLevel: 14, animated: false)
        }
    }
}
