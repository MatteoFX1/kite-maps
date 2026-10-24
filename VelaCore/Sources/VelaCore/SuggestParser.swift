import Foundation

/**
 * Google Maps' search-as-you-type answer (`/s?tbm=map&gs_ri=maps&suggest=p`), ported from Vela.
 * The body is `{"c":0,"d":")]}'\n<json>"}` plus a tail; <json> is `[[query, [rows...]], ...]`.
 * Each row holds a block whose first child is an array starting with a string: the title at
 * [0][0], the name at [1][0], the address at [2][0] and the position at [11][2..3]. A row with
 * no position is a plain query ("pizza near me").
 */
public enum SuggestParser {
    public struct Result: Sendable {
        public var places: [Place]
        public var queries: [String]
        public init(places: [Place] = [], queries: [String] = []) {
            self.places = places
            self.queries = queries
        }
    }

    static func parse(_ body: String, near: LatLng?) -> Result {
        guard let payload = unwrap(body), let root = GoogleJSON.parse(payload),
              let rows = arr(at(root, 0, 1)) else { return Result() }
        var out = Result()
        for row in rows {
            guard let block = (row as? [Any])?.first(where: { el in
                guard let a = el as? [Any], let first = a.first as? [Any] else { return false }
                return first.first is String
            }) else { continue }
            guard let title = str(at(block, 0, 0))?.nilIfBlank else { continue }
            let primary = str(at(block, 1, 0))?.nilIfBlank ?? title
            let secondary = str(at(block, 2, 0))?.nilIfBlank
            let lat = dbl(at(block, 11, 2)) ?? dbl(at(block, 13, 0, 3, 2))
            let lng = dbl(at(block, 11, 3)) ?? dbl(at(block, 13, 0, 3, 3))
            guard let lat, let lng else {
                out.queries.append(title)
                continue
            }
            let fid = str(at(block, 13, 0, 0)).flatMap { $0.range(of: #"^0x[0-9a-f]+:0x[0-9a-f]+$"#, options: .regularExpression) != nil ? $0 : nil }
            var p = Place(id: fid ?? String(format: "suggest:%.5f,%.5f", lat, lng), name: primary,
                          location: LatLng(lat, lng), source: "google")
            p.address = secondary == primary ? nil : secondary
            p.featureId = fid
            if let near { p.distanceMeters = near.distance(to: p.location) }
            out.places.append(p)
        }
        return out
    }

    /** The `d` string of the first `{...}` object in the body. */
    static func unwrap(_ body: String) -> String? {
        guard let start = body.firstIndex(of: "{") else { return nil }
        var depth = 0
        var inString = false
        var escape = false
        var end: String.Index?
        var i = start
        while i < body.endIndex {
            let c = body[i]
            if inString {
                if escape { escape = false } else if c == "\\" { escape = true } else if c == "\"" { inString = false }
            } else if c == "\"" {
                inString = true
            } else if c == "{" {
                depth += 1
            } else if c == "}" {
                depth -= 1
                if depth == 0 { end = i; break }
            }
            i = body.index(after: i)
        }
        guard let end, let data = String(body[start...end]).data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return obj["d"] as? String
    }
}
