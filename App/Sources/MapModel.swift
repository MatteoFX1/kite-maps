import Foundation
import UIKit
import MapLibre
import VelaCore

/** The app's state: what was searched, what came back, which place is open. */
@MainActor
final class MapModel: ObservableObject {
    @Published var query = ""
    @Published var results: [Place] = []
    @Published var selected: Place?
    @Published var searching = false
    @Published var notice: String?
    @Published var panelExpanded = false

    /** Set by the map view once it exists; used to read the view and move the camera. */
    weak var mapView: MLNMapView?
    private var searchTask: Task<Void, Never>?

    /** Where to search: the middle of the map, and how wide the map is in meters. */
    private var viewport: (center: LatLng, span: Double?) {
        guard let map = mapView else { return (LatLng(40.7484, -73.9857), nil) }
        let c = map.centerCoordinate
        let b = map.visibleCoordinateBounds
        let span = LatLng(b.sw.latitude, b.sw.longitude).distance(to: LatLng(b.ne.latitude, b.ne.longitude))
        return (LatLng(c.latitude, c.longitude), span.isFinite && span > 0 ? span : nil)
    }

    func search() {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        searchTask?.cancel()
        selected = nil
        searching = true
        notice = nil
        let vp = viewport
        searchTask = Task {
            let r = await PlaceSearch.search(q, center: vp.center, spanMeters: vp.span)
            guard !Task.isCancelled else { return }
            searching = false
            results = r.places
            panelExpanded = false
            if r.places.isEmpty {
                notice = "Nothing found for \"\(q)\"."
            } else if r.googleError != nil {
                notice = "Google search didn't answer, so these results come from OpenStreetMap."
            }
            if r.places.count == 1 { select(r.places[0]) } else { frameResults() }
        }
    }

    func clear() {
        searchTask?.cancel()
        query = ""
        results = []
        selected = nil
        searching = false
        notice = nil
    }

    func select(_ place: Place) {
        selected = place
        panelExpanded = false
        guard let map = mapView else { return }
        let target = CLLocationCoordinate2D(latitude: place.location.lat, longitude: place.location.lng)
        map.setCenter(target, zoomLevel: max(map.zoomLevel, 15), animated: true)
    }

    func closePlace() {
        selected = nil
    }

    func centerOnUser() {
        guard let map = mapView else { return }
        if let loc = map.userLocation?.location {
            map.setCenter(loc.coordinate, zoomLevel: max(map.zoomLevel, 14), animated: true)
        } else {
            map.userTrackingMode = .follow
        }
    }

    private func frameResults() {
        guard let map = mapView, !results.isEmpty else { return }
        let lats = results.map(\.location.lat), lngs = results.map(\.location.lng)
        let bounds = MLNCoordinateBounds(
            sw: CLLocationCoordinate2D(latitude: lats.min()!, longitude: lngs.min()!),
            ne: CLLocationCoordinate2D(latitude: lats.max()!, longitude: lngs.max()!))
        let pad = UIEdgeInsets(top: 140, left: 50, bottom: map.bounds.height * 0.4, right: 50)
        let camera = map.cameraThatFitsCoordinateBounds(bounds, edgePadding: pad)
        if camera.altitude < 600 { camera.altitude = 600 } // one result alone: don't zoom to the doorstep
        map.setCamera(camera, animated: true)
    }
}
