import Foundation
import VelaCore

// Live check of the Google search and parser, run by the build workflow.
// Usage: swift run vela-probe "pizza" 40.7484 -73.9857
let args = CommandLine.arguments
let query = args.count > 1 ? args[1] : "pizza"
let lat = args.count > 2 ? Double(args[2]) ?? 40.7484 : 40.7484
let lng = args.count > 3 ? Double(args[3]) ?? -73.9857 : -73.9857
let center = LatLng(lat, lng)
var exitCode: Int32 = 0

do {
    let places = try await GoogleClient.shared.search(query, center: center, spanMeters: 5_000)
    print("Google: \(places.count) results for \"\(query)\"")
    for p in places.prefix(5) {
        print("- \(p.name) | \(p.category ?? "-") | \(p.address ?? "-") | \(p.rating.map { String($0) } ?? "-") (\(p.reviewCount ?? 0)) | \(p.statusText ?? "-") | hours \(p.hours.count) | photos \(p.photoUrls.count)")
    }
    if places.isEmpty { exitCode = 2 }
} catch {
    print("Google failed: \(error)")
    exitCode = 1
}

let osm = try await Photon.search("Eiffel Tower", near: center)
print("Photon: \(osm.count) results, first: \(osm.first?.name ?? "-") | \(osm.first?.address ?? "-")")

// Directions: Davis to Sacramento airport, Google first, then the open router.
let davis = LatLng(38.5449, -121.7405), smf = LatLng(38.6951, -121.5908)
do {
    let routes = try await GoogleClient.shared.directions(from: davis, to: smf, mode: .drive)
    print("Google directions: \(routes.count) routes")
    for r in routes {
        print("- \(Int(r.distanceMeters)) m, typical \(Int(r.typicalSeconds / 60)) min, traffic \(r.trafficSeconds.map { "\(Int($0 / 60)) min" } ?? "-"), via \(r.summary ?? "-"), points \(r.polyline.count), steps \(r.maneuvers.count), spans \(r.trafficSpans.count)")
    }
    for m in routes.first?.maneuvers.prefix(4) ?? [] {
        print("  · \(m.type.rawValue) @\(Int(m.alongMeters)) m: \(m.instruction)")
    }
    if routes.isEmpty { exitCode = 3 }
} catch {
    print("Google directions failed: \(error)")
    exitCode = 3
}
let osrm = try await OSRM.route(from: davis, to: smf, mode: .drive)
print("OSRM: \(osrm.count) routes, first \(Int(osrm.first?.distanceMeters ?? 0)) m, \(osrm.first?.maneuvers.count ?? 0) steps: \(osrm.first?.maneuvers.prefix(3).map(\.instruction) ?? [])")
exit(exitCode)
