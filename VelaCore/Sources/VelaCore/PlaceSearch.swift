import Foundation

/** What the app calls: Google first (the full place cards), OpenStreetMap when Google fails. */
public enum PlaceSearch {
    public struct Result: Sendable {
        public var places: [Place]
        /** Set when Google failed and the open-data search answered instead. */
        public var googleError: String?
        public init(places: [Place], googleError: String? = nil) {
            self.places = places
            self.googleError = googleError
        }
    }

    public static func search(_ query: String, center: LatLng, spanMeters: Double?, useGoogle: Bool = true) async -> Result {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return Result(places: []) }
        var googleError: String?
        if useGoogle { do {
            var places = try await GoogleClient.shared.search(q, center: center, spanMeters: spanMeters)
            if !places.isEmpty {
                // Google sometimes puts a far-off namesake first ("Covent Garden Market" in Ontario,
                // "Museo Británico" for the British Museum): one nearby goes ahead of it.
                places = nearFirst(places, center: center)
                if let top = places.first, top.location.distance(to: center) > farMeters {
                    // Nothing near from Google at all: the open map may know the one down the road.
                    let osm = (try? await Photon.search(q, near: center)) ?? []
                    let near = osm.filter { $0.location.distance(to: center) < nearMeters }
                    if !near.isEmpty { return Result(places: near + places) }
                }
                return Result(places: places)
            }
        } catch {
            googleError = error.localizedDescription
        } }
        if let pc = await Postcodes.lookup(q) { return Result(places: [pc], googleError: googleError) }
        let osm = (try? await Photon.search(q, near: center)) ?? []
        return Result(places: osm, googleError: googleError)
    }

    static let nearMeters = 100_000.0, farMeters = 300_000.0

    /** Results within 100 km keep Google's order but go ahead of any more than 300 km away. */
    public static func nearFirst(_ places: [Place], center: LatLng) -> [Place] {
        guard let top = places.first, top.location.distance(to: center) > farMeters,
              places.contains(where: { $0.location.distance(to: center) < nearMeters }) else { return places }
        let near = places.filter { $0.location.distance(to: center) <= farMeters }
        return near + places.filter { $0.location.distance(to: center) > farMeters }
    }
}
