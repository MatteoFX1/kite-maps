import XCTest
@testable import VelaCore

final class MapLinksTests: XCTestCase {
    private func target(_ s: String) -> MapLinks.Target? {
        MapLinks.link(in: s).flatMap(MapLinks.parse)
    }

    private func assertSpot(_ t: MapLinks.Target?, _ lat: Double, _ lng: Double, name: String? = nil,
                            file: StaticString = #filePath, line: UInt = #line) {
        guard case let .spot(p, n)? = t else { return XCTFail("not a spot: \(String(describing: t))", file: file, line: line) }
        XCTAssertEqual(p.lat, lat, accuracy: 0.001, file: file, line: line)
        XCTAssertEqual(p.lng, lng, accuracy: 0.001, file: file, line: line)
        if let name { XCTAssertEqual(n, name, file: file, line: line) }
    }

    func testGooglePlace() {
        assertSpot(target("https://www.google.com/maps/place/Big+Ben/@51.5007292,-0.1272003,17z/data=!3m1!4b1!4m6!3m5!1s0x487604c38c8cd1d9:0xb78f2474b9a45aa9!8m2!3d51.5007292!4d-0.1246254!16zL20vMGNrcA"),
                   51.5007, -0.1246, name: "Big Ben")
        assertSpot(target("https://www.google.co.uk/maps/place/Cardiff+Castle/@51.48,-3.18,15z"), 51.48, -3.18, name: "Cardiff Castle")
    }

    func testGoogleQueryAndCoords() {
        assertSpot(target("https://maps.google.com/?q=55.9486,-3.1999"), 55.9486, -3.1999)
        assertSpot(target("https://www.google.com/maps/search/?api=1&query=51.5,-0.12"), 51.5, -0.12)
        XCTAssertEqual(target("https://www.google.com/maps/search/?api=1&query=Tesco+Extra"), .search("Tesco Extra", near: nil))
        assertSpot(target("https://www.google.com/maps/@51.4545,-2.5879,14z"), 51.4545, -2.5879)
    }

    func testShareTextWithShortLink() {
        let url = MapLinks.link(in: "Big Ben\nhttps://maps.app.goo.gl/abcDEF123")
        XCTAssertEqual(url?.host, "maps.app.goo.gl")
        XCTAssertTrue(MapLinks.looksLikeLink("maps.app.goo.gl/abc"))
        XCTAssertFalse(MapLinks.looksLikeLink("pizza near me"))
    }

    func testConsentWrapper() {
        let inner = "https://www.google.com/maps/place/X/@51.5,-0.1,17z/data=!3d51.501!4d-0.142"
        let wrapped = "https://consent.google.com/m?continue=" + inner.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
        assertSpot(MapLinks.parse(URL(string: wrapped)!), 51.501, -0.142)
    }

    func testWaze() {
        assertSpot(target("https://waze.com/ul?ll=51.50072%2C-0.12462&navigate=yes"), 51.5007, -0.1246)
        assertSpot(target("https://www.waze.com/live-map/directions?to=ll.53.4808%2C-2.2426"), 53.4808, -2.2426)
        // waze.com/ul/h + geohash: gcpuvpk4 is by Westminster Bridge.
        assertSpot(target("https://waze.com/ul/hgcpuvpk4"), 51.5002, -0.1262)
    }

    func testAppleAndGeoAndPlain() {
        assertSpot(target("https://maps.apple.com/?ll=51.5,-0.12&q=Pin"), 51.5, -0.12, name: "Pin")
        assertSpot(MapLinks.parse(URL(string: "geo:51.5,-0.12?q=51.5,-0.12(Home)")!), 51.5, -0.12, name: "Home")
        XCTAssertEqual(MapLinks.coordinates("51.50072, -0.12462")?.lat ?? 0, 51.50072, accuracy: 1e-6)
        XCTAssertNil(MapLinks.coordinates("10 20"))
    }

    func testDirectionsAndSearchLinksGoToTheRightPlace() {
        // A Google directions link has a pin for each end: the destination is the last one.
        assertSpot(target("https://www.google.com/maps/dir/Home/Big+Ben/@51.5,-0.12,13z/data=!4m8!4m7!1m2!1m1!1s0x1!2m2!1d-0.1!2d51.4!3m2!3d51.45!4d-0.2!3m2!3d51.5007!4d-0.1246"),
                   51.5007, -0.1246)
        // Apple: a search near somewhere is a search, and directions go to daddr.
        XCTAssertEqual(target("https://maps.apple.com/?sll=51.5,-0.12&q=petrol"), .search("petrol", near: LatLng(51.5, -0.12)))
        assertSpot(target("https://maps.apple.com/?ll=51.5,-0.12&daddr=51.48,-3.18"), 51.48, -3.18)
        // Whole numbers with a comma are coordinates too.
        XCTAssertEqual(MapLinks.coordinates("52, -1.5")?.lng ?? 0, -1.5, accuracy: 1e-6)
    }

    func testGeohash() {
        let p = MapLinks.geohash("gcpvj0d6")!
        XCTAssertEqual(p.lat, 51.5071, accuracy: 0.001)
        XCTAssertEqual(p.lng, -0.1286, accuracy: 0.001)
    }
}
