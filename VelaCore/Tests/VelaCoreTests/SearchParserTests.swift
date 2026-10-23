import XCTest
@testable import VelaCore

final class SearchParserTests: XCTestCase {
    /** A list reply cut down to the fields the parser reads, at Vela's calibrated positions. */
    private func entry(name: String, lat: Double, lng: Double, status: String?) -> [Any] {
        var node = [Any](repeating: NSNull(), count: 210)
        node[11] = name
        node[9] = [NSNull(), NSNull(), lat, lng]
        node[39] = "\(name), 1 Main St, Springfield"
        node[13] = ["Pizza restaurant"]
        var four = [Any](repeating: NSNull(), count: 9)
        four[7] = 4.5
        four[8] = 120
        node[4] = four
        node[10] = "0x1:0x\(name.count)"
        if let status {
            var meta = [Any](repeating: NSNull(), count: 9)
            meta[4] = [status]
            let day: [Any] = ["Monday", 1, [2026, 10, 19], [["11 AM–10 PM"]]]
            node[203] = [[day], meta]
        }
        return [NSNull(), node]
    }

    func testParsesListReply() {
        var root = [Any](repeating: NSNull(), count: 65)
        root[64] = [
            entry(name: "Far Pizza", lat: 40.80, lng: -73.98, status: "Closed · Opens 11 AM"),
            entry(name: "Near Pizza", lat: 40.7485, lng: -73.9857, status: "Open · Closes 10 PM"),
        ]
        let places = SearchParser.parse(root: root, near: LatLng(40.7484, -73.9857))
        XCTAssertEqual(places.map(\.name), ["Near Pizza", "Far Pizza"])
        let near = places[0]
        XCTAssertEqual(near.address, "1 Main St, Springfield")
        XCTAssertEqual(near.category, "Pizza restaurant")
        XCTAssertEqual(near.rating, 4.5)
        XCTAssertEqual(near.reviewCount, 120)
        XCTAssertEqual(near.openNow, true)
        XCTAssertEqual(near.hours, ["Monday: 11 AM–10 PM"])
        XCTAssertEqual(places[1].openNow, false)
    }

    func testOpensIsClosed() {
        XCTAssertEqual(SearchParser.openNow("Opens 5 AM"), false)
        XCTAssertEqual(SearchParser.openNow("Open 24 hours"), true)
        XCTAssertEqual(SearchParser.openNow("Closes soon · 9 PM"), true)
        XCTAssertNil(SearchParser.openNow(nil))
    }

    func testStripNamePrefix() {
        XCTAssertEqual(SearchParser.stripNamePrefix("Safeway, 1451 W Covell Blvd", name: "Safeway"), "1451 W Covell Blvd")
        XCTAssertEqual(SearchParser.stripNamePrefix("Safeway Plaza, 1 Rd", name: "Safeway"), "Safeway Plaza, 1 Rd")
    }

    func testXssiGuard() {
        let v = GoogleJSON.parse(")]}'\n[1,[\"a\"]]")
        XCTAssertEqual(str(at(v, 1, 0)), "a")
    }

    func testPbTemplate() {
        let pb = SearchPb.build(query: "pizza!", center: LatLng(1.5, 2.5), spanMeters: 3000)
        XCTAssertTrue(pb.hasPrefix("!1spizza!4m8!1m3!1d3000.0!2d2.5!3d1.5!3m2"))
        XCTAssertFalse(pb.contains(" "))
        XCTAssertFalse(pb.contains("\n"))
        XCTAssertTrue(pb.hasSuffix("!69i782!77b1"))
    }
}
