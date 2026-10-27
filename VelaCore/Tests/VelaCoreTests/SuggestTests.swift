import XCTest
@testable import VelaCore

final class SuggestTests: XCTestCase {
    func testParse() throws {
        // A place row (block with a position at [11]) and a plain query row.
        let place: [Any] = [["Pier 50 Sushi"], ["Pier 50 Sushi", [[0, 4]]], ["226 F St, Davis, CA"],
                            NSNull(), NSNull(), NSNull(), NSNull(), NSNull(), NSNull(), NSNull(), NSNull(),
                            [NSNull(), NSNull(), 38.5446, -121.7409]]
        let query: [Any] = [["pizza near me"], ["pizza near me"]]
        let inner: [Any] = [["pi", [[NSNull(), place], [query]]]]
        let innerText = String(decoding: try JSONSerialization.data(withJSONObject: inner), as: UTF8.self)
        let env = String(decoding: try JSONSerialization.data(withJSONObject: ["c": 0, "d": ")]}'\n" + innerText]), as: UTF8.self)
        let body = env + "/*\"\"*/{\"c\":0,\"d\":\"\",\"e\":\"x\"}"
        let r = SuggestParser.parse(body, near: LatLng(38.5449, -121.7405))
        XCTAssertEqual(r.places.count, 1)
        XCTAssertEqual(r.places.first?.name, "Pier 50 Sushi")
        XCTAssertEqual(r.places.first?.address, "226 F St, Davis, CA")
        XCTAssertEqual(r.queries, ["pizza near me"])
    }

    func testCloseNameMatchComesFirst() {
        func place(_ n: String) -> Place { Place(id: n, name: n, location: LatLng(50.88, -1.25), source: "google") }
        let google = ["The Body Shop", "Whiteley Village", "Whiteley Shopping Centre", "Boots"].map(place)
        XCTAssertEqual(SuggestParser.closeMatchesFirst(google, query: "Whiteley Shopping Centre").map(\.name),
                       ["Whiteley Shopping Centre", "Whiteley Village", "The Body Shop", "Boots"])
        // Still typing: the last word counts as a start.
        XCTAssertEqual(SuggestParser.closeMatchesFirst(google, query: "whiteley shop").first?.name, "Whiteley Shopping Centre")
        // Nothing close: Google's order stays.
        XCTAssertEqual(SuggestParser.closeMatchesFirst(google, query: "pharmacy").map(\.name), google.map(\.name))
        // Several close ones keep Google's order between them.
        let tescos = ["Tesco Express", "Tesco Extra", "Sainsbury's"].map(place)
        XCTAssertEqual(SuggestParser.closeMatchesFirst(tescos, query: "tesco").map(\.name), ["Tesco Express", "Tesco Extra", "Sainsbury's"])
        XCTAssertEqual(SuggestParser.closeMatchesFirst(tescos, query: "sainsburys").first?.name, "Sainsbury's")
    }
}
