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
}
