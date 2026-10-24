import Foundation

/**
 * Google Street View, keyless: the same panorama lookup Google's own map pages use, a thumbnail
 * for the place page, and the embeddable viewer for looking around.
 */
public enum StreetView {
    public struct Panorama: Equatable, Sendable {
        public var id: String
        public var location: LatLng
    }

    /** Google retired the old GeoPhotoService.SingleImageSearch; its pages now ask this RPC. */
    static let searchEndpoint = URL(string: "https://maps.googleapis.com/$rpc/google.internal.maps.mapsjs.v1.MapsJsInternalService/SingleImageSearch")!

    public static func searchRequest(_ p: LatLng, radius: Int) -> URLRequest {
        var r = URLRequest(url: searchEndpoint)
        r.httpMethod = "POST"
        r.setValue("application/json+protobuf", forHTTPHeaderField: "Content-Type")
        let body = "[[\"apiv3\",null,null,null,\"US\",null,null,null,null,null,[[0]]],"
            + "[[null,null,\(p.lat),\(p.lng)],\(radius)],"
            + "[null,[\"en\",\"US\"],null,null,null,null,null,null,[2],null,[[[2,true,2],[3,true,2],[10,true,2]]]],"
            + "[[1,2,3,4,8,6]]]"
        r.httpBody = body.data(using: .utf8)
        return r
    }

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 12
        c.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
            "Referer": "https://www.google.com/",
        ]
        return URLSession(configuration: c)
    }()

    /** The nearest Google panorama within [radius] meters (official Street View only), or nil. */
    public static func find(near p: LatLng, radius: Int = 60) async -> Panorama? {
        guard let (data, _) = try? await session.data(for: searchRequest(p, radius: radius)) else { return nil }
        return parse(String(decoding: data, as: UTF8.self))
    }

    /** Pulls the panorama id (Google's are 22 characters) and its position out of the reply. */
    static func parse(_ text: String) -> Panorama? {
        guard let idMatch = text.range(of: #"\[2,\s*"[A-Za-z0-9_-]{22}"\]"#, options: .regularExpression) else { return nil }
        let id = String(text[idMatch].filter { $0 != " " }.dropFirst(4).dropLast(2))
        guard let r = text.range(of: #"\[\[null,null,-?\d+\.\d+,\s*-?\d+\.\d+\]"#, options: .regularExpression) else { return nil }
        let nums = text[r].dropFirst(12).dropLast().split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard nums.count == 2 else { return nil }
        return Panorama(id: id, location: LatLng(nums[0], nums[1]))
    }

    /** A still from the panorama, looking toward [heading]. */
    public static func thumbnailURL(_ pano: Panorama, heading: Double, width: Int = 640, height: Int = 360) -> URL {
        URL(string: "https://streetviewpixels-pa.googleapis.com/v1/thumbnail?panoid=\(pano.id)&cb_client=maps_sv.tactile.gps"
            + "&w=\(width)&h=\(height)&yaw=\(Int(heading.rounded()))&pitch=0&thumbfov=100")!
    }

    /** Google's embeddable Street View page, starting at [heading]: drag to look, tap the arrows to move. */
    public static func viewerURL(_ pano: Panorama, heading: Double) -> URL {
        URL(string: "https://www.google.com/maps/embed?pb=!4v1!6m8!1m7!1s\(pano.id)!2m2!1d\(pano.location.lat)!2d\(pano.location.lng)"
            + "!3f\(Int(heading.rounded()))!4f0!5f0.78")!
    }
}
