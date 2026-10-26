import Foundation
import VelaCore

/**
 * Waze's website stopped sharing drivers' police, crash and hazard reports. Google Maps shows some
 * of them along routes: look for them in Google's raw route answers, around Southampton (where the
 * Waze app showed police at Bishopstoke), on the M27, across London and in Los Angeles.
 */
func googleIncidents() async {
    let drives: [(String, LatLng, LatLng)] = [
        ("Southampton to Bishopstoke", LatLng(50.909, -1.404), LatLng(50.966, -1.330)),
        ("M27 Southampton to Portsmouth", LatLng(50.915, -1.380), LatLng(50.805, -1.087)),
        ("Heathrow to the City", LatLng(51.470, -0.454), LatLng(51.513, -0.090)),
        ("Santa Monica to Downtown LA", LatLng(34.019, -118.491), LatLng(34.050, -118.250)),
    ]
    let words = ["speed trap", "police", "crash", "accident", "reported", "waze", "speed camera", "hazard",
                 "roadwork", "construction", "lane clos", "road closed", "incident", "stalled", "object on"]
    for (name, a, b) in drives {
        do {
            let body = try await GoogleClient.shared.directionsBody(from: a, to: b, mode: .drive)
            let low = body.lowercased()
            print("Google incidents · \(name): \(body.count) chars")
            for w in words {
                var hits: [String] = []
                var from = low.startIndex
                while let r = low.range(of: w, range: from..<low.endIndex) {
                    if hits.count < 3 {
                        let s = low.index(r.lowerBound, offsetBy: -80, limitedBy: low.startIndex) ?? low.startIndex
                        let e = low.index(r.upperBound, offsetBy: 120, limitedBy: low.endIndex) ?? low.endIndex
                        hits.append(String(body[s..<e]).replacingOccurrences(of: "\n", with: " "))
                    }
                    from = r.upperBound
                }
                let n = low.components(separatedBy: w).count - 1
                if n > 0 {
                    print("  \"\(w)\" ×\(n)")
                    for h in hits { print("    … \(h) …") }
                }
            }
        } catch {
            print("Google incidents · \(name): failed \(error)")
        }
    }
}
