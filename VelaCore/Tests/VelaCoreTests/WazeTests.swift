import XCTest
@testable import VelaCore

final class WazeTests: XCTestCase {
    func testParse() {
        let json = #"{"alerts":[{"type":"POLICE","subtype":"POLICE_HIDING","uuid":"a1","location":{"x":-0.12,"y":51.5},"street":"A40","pubMillis":1700000000000,"nThumbsUp":3,"reliability":8,"magvar":90},{"type":"WEATHERHAZARD","subtype":"HAZARD_ON_ROAD_POT_HOLE","uuid":"a2","location":{"x":-0.1,"y":51.4}},{"type":"WEATHERHAZARD","subtype":"HAZARD_WEATHER_FOG","uuid":"a3","location":{"x":-0.1,"y":51.4}},{"type":"HAZARD","subtype":"HAZARD_ON_ROAD_CONSTRUCTION","uuid":"a4","location":{"x":-0.1,"y":51.4}},{"type":"CHIT_CHAT","uuid":"a5","location":{"x":0,"y":0}},{"type":"ROAD_CLOSED","uuid":"a6"}],"jams":[]}"#
        let list = Waze.parse(Data(json.utf8))!
        XCTAssertEqual(list.map(\.kind), [.police, .hazard, .weather, .roadworks])
        XCTAssertEqual(list[0].title, "Hidden police")
        XCTAssertEqual(list[0].thumbsUp, 3)
        XCTAssertEqual(list[0].heading, 90)
        XCTAssertEqual(list[0].location, LatLng(51.5, -0.12))
        XCTAssertEqual(list[0].reportedAt, Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(list[1].spoken, "Pothole reported ahead.")
        XCTAssertNil(Waze.parse(Data("nope".utf8)))
    }

    func testEnv() {
        XCTAssertEqual(Waze.env(for: LatLng(51.5, -0.12)), "row")
        XCTAssertEqual(Waze.env(for: LatLng(38.5, -121.7)), "na")
        XCTAssertEqual(Waze.env(for: LatLng(32.08, 34.78)), "il")
    }

    func testProjectKeepsOnlyOurDirection() {
        // A road heading due east.
        let line = [LatLng(0, 0), LatLng(0, 0.02)]
        let same = WazeAlert(id: "same", kind: .police, location: LatLng(0.0001, 0.01), heading: 85)
        let other = WazeAlert(id: "other", kind: .police, location: LatLng(-0.0001, 0.012), heading: 268)
        let unknown = WazeAlert(id: "unknown", kind: .accident, location: LatLng(0, 0.015))
        let far = WazeAlert(id: "far", kind: .police, location: LatLng(0.01, 0.01))
        let hits = Waze.project([same, other, unknown, far], on: line)
        XCTAssertEqual(hits.map(\.alert.id), ["same", "unknown"])
        XCTAssertEqual(hits[0].along, 1113, accuracy: 15)
    }
}
