import Foundation
import VelaCore

/**
 * Every Google feature Kite uses, checked live around Southampton and London: search, autocomplete,
 * place details, directions (traffic, alternates, avoid motorways and tolls, a stop), the traffic
 * tiles and Street View.
 */
func googleCheck() async {
    let soton = LatLng(50.9097, -1.4044)
    func line(_ s: String) { print(s); fflush(stdout) }

    // Search: a chain, a category, a street address and a postcode.
    for (q, near) in [("Tesco", soton), ("petrol station", soton), ("10 Downing Street", LatLng(51.5034, -0.1276)), ("SO17 1BJ", soton)] {
        do {
            let t0 = Date()
            let places = try await GoogleClient.shared.search(q, center: near, spanMeters: 8_000)
            line("Search \"\(q)\": \(places.count) in \(String(format: "%.1f", Date().timeIntervalSince(t0))) s")
            for p in places.prefix(3) {
                line("  · \(p.name) | \(p.category ?? "-") | \(p.address ?? "-") | \(Int(p.distanceMeters ?? -1)) m | ★\(p.rating.map { String($0) } ?? "-") (\(p.reviewCount ?? 0)) | \(p.statusText ?? "-") | hours \(p.hours.count) | photos \(p.photoUrls.count) | phone \(p.phone ?? "-") | web \(p.website.map { String($0.prefix(40)) } ?? "-") | placeId \(p.placeId != nil) | share \(p.shareURL?.absoluteString.prefix(60) ?? "-")")
            }
        } catch {
            line("Search \"\(q)\": FAILED \(error)")
        }
    }

    // Autocomplete while typing.
    for q in ["tesc", "southampton gen", "so17"] {
        do {
            let s = try await GoogleClient.shared.suggest(q, near: soton)
            line("Suggest \"\(q)\": \(s.places.count) places, \(s.queries.count) searches; " + s.places.prefix(3).map { "\($0.name) (\($0.address ?? "-"), \(Int($0.distanceMeters ?? -1)) m)" }.joined(separator: "; "))
        } catch {
            line("Suggest \"\(q)\": FAILED \(error)")
        }
    }

    // Directions.
    func describe(_ r: Route) -> String {
        let roads = Set(r.maneuvers.compactMap(\.road) + r.maneuvers.map(\.instruction).flatMap { s in
            s.split(whereSeparator: { !$0.isLetter && !$0.isNumber }).map(String.init).filter { $0.range(of: #"^[AM][0-9]+M?$"#, options: .regularExpression) != nil }
        }).sorted().prefix(14)
        return "\(Int(r.distanceMeters)) m, typical \(Int(r.typicalSeconds / 60)) min, traffic \(r.trafficSeconds.map { "\(Int($0 / 60)) min" } ?? "-"), via \(r.summary ?? "-"), \(r.polyline.count) pts, \(r.maneuvers.count) steps, complete \(r.stepsComplete), spans \(r.trafficSpans.count) (levels \(Set(r.trafficSpans.map(\.level)).sorted())), roads \(Array(roads))"
    }
    let drives: [(String, LatLng, LatLng, Bool, Bool)] = [
        ("Southampton to Winchester", soton, LatLng(51.0632, -1.3080), false, false),
        ("Southampton to Winchester, no motorways", soton, LatLng(51.0632, -1.3080), true, false),
        ("Bluewater to Lakeside (Dartford toll)", LatLng(51.4390, 0.2710), LatLng(51.4870, 0.2830), false, false),
        ("Bluewater to Lakeside, no tolls", LatLng(51.4390, 0.2710), LatLng(51.4870, 0.2830), false, true),
    ]
    for (name, a, b, hw, tolls) in drives {
        do {
            let rs = try await GoogleClient.shared.directions(from: a, to: b, mode: .drive, avoidHighways: hw, avoidTolls: tolls)
            line("Directions \(name): \(rs.count) routes")
            for r in rs { line("  · " + describe(r)) }
        } catch {
            line("Directions \(name): FAILED \(error)")
        }
    }
    do {
        let rs = try await GoogleClient.shared.directions(from: soton, to: LatLng(51.0632, -1.3080), mode: .drive, via: [LatLng(50.9670, -1.3530)])
        line("Directions with a stop at Eastleigh: " + (rs.first.map { describe($0) + ", passes \(Int($0.polyline.map { $0.distance(to: LatLng(50.9670, -1.3530)) }.min()!)) m from it" } ?? "none"))
    } catch {
        line("Directions with a stop: FAILED \(error)")
    }
    for mode in [TravelMode.walk, .bike] {
        let rs = try? await GoogleClient.shared.directions(from: soton, to: LatLng(50.9346, -1.3966), mode: mode)
        line("Directions \(mode): " + (rs?.first.map(describe) ?? "none"))
    }
    let kite = await Directions.routes(from: LatLng(50.8705, -1.2690), to: LatLng(50.9346, -1.3966), mode: .drive)
    line("Kite's own pick, Park Gate to the Uni: " + kite.routes.map { "\($0.source): " + describe($0) }.joined(separator: " || "))

    // Traffic tiles: the overlay the map draws (central London, zoom 13).
    let tile = URL(string: "https://www.google.com/maps/vt/pb=!1m4!1m3!1i13!2i4093!3i2724!2m9!1e2!2straffic!3i999999"
        + "!4m2!1sincidents!2s1!4m2!1sincidents_text!2s1!3m8!2sen!3sus!5e1105!12m4!1e68!2m2!1sset!2sRoadmap!4e0!5m1!1e0")!
    if let (d, resp) = try? await URLSession.shared.data(from: tile) {
        let h = resp as? HTTPURLResponse
        line("Traffic tile: HTTP \(h?.statusCode ?? 0), \(h?.value(forHTTPHeaderField: "Content-Type") ?? "-"), \(d.count) bytes, cache \(h?.value(forHTTPHeaderField: "Cache-Control") ?? "-")")
    } else {
        line("Traffic tile: FAILED")
    }

    // Street View near Southampton's Bargate.
    if let pano = await StreetView.find(near: LatLng(50.9045, -1.4040)) {
        let thumb = StreetView.thumbnailURL(pano, heading: 0)
        let code = ((try? await URLSession.shared.data(from: thumb))?.1 as? HTTPURLResponse)?.statusCode ?? 0
        line("Street View Southampton: \(pano.id) at \(pano.location), \(pano.width)x\(pano.height), thumbnail HTTP \(code)")
    } else {
        line("Street View Southampton: none")
    }
}
