import Foundation

/**
 * Keyless Google Maps web search, the way Vela's Android extractor does it: warm a session
 * once (cookies), then call `/search?tbm=map` with Vela's `pb` and a desktop Chrome header set.
 * No key and no account. EU consent cookies are pre-seeded so no consent wall appears.
 */
public actor GoogleClient {
    public static let shared = GoogleClient()

    static let userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"
    static let secChUa = #""Not:A-Brand";v="8", "Chromium";v="156", "Google Chrome";v="156""#
    static let searchEndpoint = "https://www.google.com/search?tbm=map&authuser=0&hl=en&gl=us"
    static let directionsEndpoint = "https://www.google.com/maps/preview/directions?authuser=0&hl=en&gl=us"
    static let sessionWarmUrl = "https://www.google.com/maps?hl=en&gl=us"

    private let session: URLSession
    private var warmed = false

    public init() {
        let cfg = URLSessionConfiguration.ephemeral
        let cookies = cfg.httpCookieStorage ?? HTTPCookieStorage.shared
        for name in [("SOCS", "CAESHAgBEhIaAB"), ("CONSENT", "YES+")] {
            if let c = HTTPCookie(properties: [
                .domain: ".google.com", .path: "/", .name: name.0, .value: name.1, .secure: "TRUE",
                .expires: Date().addingTimeInterval(365 * 86_400),
            ]) { cookies.setCookie(c) }
        }
        cfg.httpCookieAcceptPolicy = .always
        cfg.timeoutIntervalForRequest = 15
        session = URLSession(configuration: cfg)
    }

    /** Search Google around [center]. [spanMeters] is how wide the visible map is. A full first
     *  page (a category like "restaurants") fetches pages two and three too, as Vela does. */
    public func search(_ query: String, center: LatLng, spanMeters: Double?, pages: Int = 3) async throws -> [Place] {
        try await warm()
        let first = try await page(query, center: center, spanMeters: spanMeters, offset: 0)
        guard pages > 1, first.count >= 18 else { return first }
        var more: [Place] = []
        await withTaskGroup(of: [Place].self) { group in
            for i in 1..<pages {
                group.addTask { (try? await self.page(query, center: center, spanMeters: spanMeters, offset: 20 * i)) ?? [] }
            }
            for await p in group { more += p }
        }
        var seen = Set(first.map(\.id))
        let extra = more.filter { seen.insert($0.id).inserted }
            .sorted { ($0.distanceMeters ?? 0) < ($1.distanceMeters ?? 0) }
        return first + extra
    }

    private func page(_ query: String, center: LatLng, spanMeters: Double?, offset: Int) async throws -> [Place] {
        let pb = SearchPb.build(query: query, center: center, spanMeters: spanMeters, offset: offset)
        let url = URL(string: Self.searchEndpoint + "&q=" + enc(query) + "&pb=" + enc(pb))!
        let body = try await get(url, referer: "https://www.google.com/", xhr: true)
        guard let root = GoogleJSON.parse(body) else { throw VelaError.unexpectedResponse(String(body.prefix(200))) }
        return SearchParser.parse(root: root, near: center)
    }

    /** Driving, cycling or walking routes with live traffic times, alternates first-to-last. */
    public func directions(from origin: LatLng, to destination: LatLng, mode: TravelMode,
                           avoidHighways: Bool = false, avoidTolls: Bool = false) async throws -> [Route] {
        try await warm()
        let pb = DirectionsPb.build(origin: origin, destination: destination, mode: mode,
                                    avoidHighways: avoidHighways, avoidTolls: avoidTolls)
        let url = URL(string: Self.directionsEndpoint + "&pb=" + enc(pb))!
        let body = try await get(url, referer: "https://www.google.com/", xhr: true)
        guard let root = GoogleJSON.parse(body) else { throw VelaError.unexpectedResponse(String(body.prefix(200))) }
        return DirectionsParser.parse(root: root, mode: mode)
    }

    private func warm() async throws {
        if warmed { return }
        _ = try? await get(URL(string: Self.sessionWarmUrl)!, referer: nil, xhr: false)
        warmed = true
    }

    private func get(_ url: URL, referer: String?, xhr: Bool) async throws -> String {
        var r = URLRequest(url: url)
        r.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        r.setValue(xhr ? "*/*" : "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                   forHTTPHeaderField: "Accept")
        r.setValue(Self.acceptLanguage, forHTTPHeaderField: "Accept-Language")
        r.setValue(Self.secChUa, forHTTPHeaderField: "Sec-CH-UA")
        r.setValue("?0", forHTTPHeaderField: "Sec-CH-UA-Mobile")
        r.setValue("\"Windows\"", forHTTPHeaderField: "Sec-CH-UA-Platform")
        r.setValue(xhr ? "empty" : "document", forHTTPHeaderField: "Sec-Fetch-Dest")
        r.setValue(xhr ? "cors" : "navigate", forHTTPHeaderField: "Sec-Fetch-Mode")
        r.setValue(xhr ? "same-origin" : "none", forHTTPHeaderField: "Sec-Fetch-Site")
        if let referer { r.setValue(referer, forHTTPHeaderField: "Referer") }
        let (data, resp) = try await session.data(for: r)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw VelaError.http(http.statusCode)
        }
        return String(decoding: data, as: UTF8.self)
    }

    // Matches the hl=en in the URL, so open/closed words come back in English.
    private static let acceptLanguage = "en-US,en;q=0.9"

    private func enc(_ s: String) -> String {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&+=?#")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}

public enum VelaError: Error, LocalizedError {
    case http(Int)
    case unexpectedResponse(String)

    public var errorDescription: String? {
        switch self {
        case .http(let code): return "The server answered with error \(code)."
        case .unexpectedResponse: return "The search answer came back in a shape Vela doesn't understand."
        }
    }
}
