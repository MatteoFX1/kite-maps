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
let merged = await Directions.routes(from: davis, to: smf, mode: .drive)
print("Merged steps on Google's line: \(merged.routes.first?.maneuvers.count ?? 0)")
for m in merged.routes.first?.maneuvers.prefix(6) ?? [] {
    print("  · \(m.type.rawValue) @\(Int(m.alongMeters)) m: \(m.instruction)")
}

// Autocomplete
do {
    let sug = try await GoogleClient.shared.suggest("pier 50", near: LatLng(38.5449, -121.7405))
    print("Google suggest: \(sug.places.count) places, \(sug.queries.count) queries; first: \(sug.places.first?.name ?? "-") | \(sug.places.first?.address ?? "-")")
} catch {
    print("Google suggest failed: \(error)")
}

// Cameras around Munich (speed) and Davis (plate readers).
let munich = await Overpass.cameras(south: 48.10, west: 11.50, north: 48.18, east: 11.62, speed: true, plate: false)
print("Overpass speed cameras near Munich: \(munich.map { "\($0.count)" } ?? "failed")")
let plates = await Overpass.cameras(south: 38.50, west: -121.80, north: 38.60, east: -121.68, speed: false, plate: true)
print("Overpass plate cameras near Davis: \(plates.map { "\($0.count)" } ?? "failed")")
if munich == nil || plates == nil {
    let q = Overpass.query(filter: "(48.10,11.50,48.18,11.62)", speed: true, plate: false)
    for ep in Overpass.endpoints {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let url = URL(string: ep + "?data=" + (q.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""))!
        var req = URLRequest(url: url)
        req.timeoutInterval = 40
        let start = Date()
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            print("  \(ep): HTTP \((resp as? HTTPURLResponse)?.statusCode ?? 0) in \(Int(Date().timeIntervalSince(start))) s, \(data.count) bytes: \(String(decoding: data.prefix(160), as: UTF8.self))")
        } catch {
            print("  \(ep): \(error.localizedDescription) after \(Int(Date().timeIntervalSince(start))) s")
        }
    }
}
let via = try? await OSRM.routeVia(merged.routes.first?.polyline ?? [], mode: .drive)
print("OSRM via Google's line: \(via.map { "\(Int($0.distanceMeters)) m, \($0.maneuvers.count) steps" } ?? "failed")")
if let via, let line = merged.routes.first?.polyline {
    let cum = Geo.cumulative(line)
    for m in via.maneuvers {
        let p = Geo.project(m.location, on: line, cumulative: cum)
        print("  · \(m.type.rawValue) \(m.instruction): along \(Int(p.along)) m, off \(Int(p.offset)) m")
    }
    print("  adopted: \(StepMerge.adopt([via], onto: line)?.count ?? 0) steps")
}
// Cameras and speed limits along the Davis to airport route.
if let line = merged.routes.first?.polyline, let extra = await Overpass.alongRoute(line, speed: true, plate: true) {
    let limits = Overpass.speedLimits(extra.ways, on: line)
    print("Along the route: \(extra.cameras.count) cameras, \(extra.ways.count) roads with limits, \(limits.count) changes: \(limits.prefix(10).map { "\(Int($0.startMeters)) m \($0.kmh.map { String(Int(($0 / 1.609344).rounded())) + " mph" } ?? "?")" })")
} else {
    print("Along the route: Overpass failed")
}
exit(exitCode)

// Transit: San Francisco Ferry Building to Ocean Beach.
do {
    let trips = try await Transitous.plan(from: LatLng(37.7955, -122.3937), to: LatLng(37.7599, -122.5100))
    print("Transitous trips: \(trips.count)")
    for t in trips.prefix(3) {
        print("  · \(Int(t.durationSeconds / 60)) min, \(t.transfers) transfers: " + t.legs.map {
            $0.isWalk ? "walk \(Int($0.durationSeconds / 60))m" : "\($0.mode) \($0.line) → \($0.headsign ?? "?") (\($0.stops) stops, \($0.path.count) pts)"
        }.joined(separator: ", "))
    }
} catch {
    print("Transitous failed: \(error.localizedDescription)")
}
