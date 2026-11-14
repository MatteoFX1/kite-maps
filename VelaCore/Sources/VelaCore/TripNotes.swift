import Foundation

/**
 * A place that shuts before (or just as) you'd get there. Kite already knows both halves, the
 * place's "Open · Closes 5 pm" line and the arrival time, and this puts them together.
 */
public enum ClosingCheck {
    public struct Warning: Equatable, Sendable {
        /** The closing time as the place's own line writes it: "5 pm", "17:30". */
        public var closes: String
        /** True when it's already shut on arriving; false when it shuts within a quarter of an hour after. */
        public var before: Bool

        public init(closes: String, before: Bool) {
            self.closes = closes
            self.before = before
        }
    }

    /** The closing time in a status line like "Open · Closes 10 pm" or "Closes soon · 5:30 pm": its words, and minutes after midnight. */
    public static func closing(_ status: String?) -> (text: String, minutes: Int)? {
        guard let status, let re = try? NSRegularExpression(
            pattern: #"closes(?:\s+soon)?[^0-9a-z]*(?:at\s+)?((\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?|midnight|noon)"#,
            options: [.caseInsensitive]) else { return nil }
        let ns = status as NSString
        guard let m = re.firstMatch(in: status, range: NSRange(location: 0, length: ns.length)) else { return nil }
        let text = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
        let low = text.lowercased()
        if low == "midnight" { return (text, 24 * 60) }
        if low == "noon" { return (text, 12 * 60) }
        guard m.range(at: 2).location != NSNotFound, var h = Int(ns.substring(with: m.range(at: 2))) else { return nil }
        let min = m.range(at: 3).location != NSNotFound ? Int(ns.substring(with: m.range(at: 3))) ?? 0 : 0
        if m.range(at: 4).location != NSNotFound {
            let pm = ns.substring(with: m.range(at: 4)).lowercased().hasPrefix("p")
            guard h >= 1, h <= 12 else { return nil }
            h = h % 12 + (pm ? 12 : 0)
        }
        guard h <= 24, min < 60 else { return nil }
        return (text, h * 60 + min)
    }

    /**
     * [nowMinutes] is the time now in minutes after midnight, [travelSeconds] how long the trip takes.
     * Nil when the place has no closing time, or you'd be there with more than 15 minutes to spare.
     */
    public static func warning(status: String?, nowMinutes: Int, travelSeconds: Double) -> Warning? {
        guard let c = closing(status) else { return nil }
        var close = c.minutes
        // "Closes 1 am" read at 10 in the evening is tomorrow's 1 am.
        if close <= nowMinutes { close += 24 * 60 }
        let arrive = Double(nowMinutes) + travelSeconds / 60
        if arrive >= Double(close) { return Warning(closes: c.text, before: true) }
        if arrive > Double(close) - 15 { return Warning(closes: c.text, before: false) }
        return nil
    }
}

/** A charge on the way that has no barrier to remind you: you pay online afterwards, or get a fine. */
public struct RoadCharge: Hashable, Sendable {
    public var name: String
    /** How and when to pay, in a few words. */
    public var note: String

    public init(name: String, note: String) {
        self.name = name
        self.note = note
    }
}

/**
 * Britain's pay-later road charges. A short fixed list, not looked up live: each is a spot in the
 * middle of a river crossing (a route within a few hundred metres of it can only be on the
 * crossing), or for London's Congestion Charge the area inside the Inner Ring Road.
 * No prices: they change every year, and the point is only that you know to pay.
 */
public enum RoadCharges {
    struct Crossing {
        let name: String
        let note: String
        let at: [LatLng]
        let radius: Double
    }

    static let crossings: [Crossing] = [
        Crossing(name: "Dartford Crossing", note: "No barriers. Charged 6 am to 10 pm. Pay the Dart Charge online by midnight the day after.",
                 at: [LatLng(51.4647, 0.2586)], radius: 450),
        Crossing(name: "Mersey Gateway", note: "No barriers. Pay online by midnight the day after.",
                 at: [LatLng(53.3528, -2.7131)], radius: 400),
        Crossing(name: "Silver Jubilee Bridge", note: "No barriers. Pay online by midnight the day after.",
                 at: [LatLng(53.3467, -2.7378)], radius: 250),
        Crossing(name: "Tyne Tunnels", note: "No barriers. Pay online by midnight the day after.",
                 at: [LatLng(54.9877, -1.4859)], radius: 300),
        Crossing(name: "Blackwall Tunnel", note: "No barriers. Charged 6 am to 10 pm. Pay online afterwards.",
                 at: [LatLng(51.5044, -0.0031)], radius: 250),
        Crossing(name: "Silvertown Tunnel", note: "No barriers. Charged 6 am to 10 pm. Pay online afterwards.",
                 at: [LatLng(51.5047, 0.0081)], radius: 250),
    ]

    /** The Inner Ring Road, clockwise from Edgware Road. The ring itself is free; inside it is charged. */
    static let congestionZone: [LatLng] = [
        LatLng(51.5203, -0.1700), LatLng(51.5238, -0.1440), LatLng(51.5304, -0.1238), LatLng(51.5322, -0.1058),
        LatLng(51.5257, -0.0876), LatLng(51.5240, -0.0775), LatLng(51.5100, -0.0730), LatLng(51.5075, -0.0745),
        LatLng(51.4950, -0.0850), LatLng(51.4946, -0.1000), LatLng(51.4862, -0.1230), LatLng(51.4950, -0.1440),
        LatLng(51.5027, -0.1527), LatLng(51.5131, -0.1589), LatLng(51.5203, -0.1700),
    ]
    static let congestion = RoadCharge(name: "London Congestion Charge zone",
                                       note: "Charged in the daytime. Pay online.")

    /** The charges a route runs into, in the order above. Empty for nearly every route. */
    public static func on(_ line: [LatLng]) -> [RoadCharge] {
        guard line.count >= 2 else { return [] }
        var minLat = 90.0, maxLat = -90.0, minLng = 180.0, maxLng = -180.0
        for p in line {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLng = min(minLng, p.lng); maxLng = max(maxLng, p.lng)
        }
        func near(_ p: LatLng) -> Bool {
            p.lat > minLat - 0.01 && p.lat < maxLat + 0.01 && p.lng > minLng - 0.02 && p.lng < maxLng + 0.02
        }
        var found: [RoadCharge] = []
        var cum: [Double]?
        for c in crossings where c.at.contains(where: near) {
            if cum == nil { cum = Geo.cumulative(line) }
            if c.at.contains(where: { near($0) && Geo.project($0, on: line, cumulative: cum).offset < c.radius }) {
                found.append(RoadCharge(name: c.name, note: c.note))
            }
        }
        if line.contains(where: { insideCongestionZone($0) }) { found.append(congestion) }
        return found
    }

    /** Well inside the zone (300 m from its edge), so driving the ring road round it never counts. */
    static func insideCongestionZone(_ p: LatLng) -> Bool {
        guard p.lat > 51.486, p.lat < 51.533, p.lng > -0.171, p.lng < -0.072 else { return false }
        let ring = congestionZone
        var inside = false
        var j = ring.count - 1
        for i in 0..<ring.count {
            let a = ring[i], b = ring[j]
            if (a.lat > p.lat) != (b.lat > p.lat),
               p.lng < (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng { inside.toggle() }
            j = i
        }
        return inside && Geo.project(p, on: ring).offset > 300
    }
}
