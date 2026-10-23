import Foundation
import VelaCore

// Live check of the Google search and parser, run by the build workflow.
// Usage: swift run vela-probe "pizza" 40.7484 -73.9857
let args = CommandLine.arguments
let query = args.count > 1 ? args[1] : "pizza"
let lat = args.count > 2 ? Double(args[2]) ?? 40.7484 : 40.7484
let lng = args.count > 3 ? Double(args[3]) ?? -73.9857 : -73.9857
let center = LatLng(lat, lng)

do {
    let places = try await GoogleClient.shared.search(query, center: center, spanMeters: 5_000)
    print("Google: \(places.count) results for \"\(query)\"")
    for p in places.prefix(5) {
        print("- \(p.name) | \(p.category ?? "-") | \(p.address ?? "-") | \(p.rating.map { String($0) } ?? "-") (\(p.reviewCount ?? 0)) | \(p.statusText ?? "-") | hours \(p.hours.count) | photos \(p.photoUrls.count)")
    }
    if places.isEmpty { exit(2) }
} catch {
    print("Google failed: \(error)")
    exit(1)
}

let osm = try await Photon.search("Eiffel Tower", near: center)
print("Photon: \(osm.count) results, first: \(osm.first?.name ?? "-") | \(osm.first?.address ?? "-")")
