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

final class PoliceRiskTests: XCTestCase {
    func testScore() {
        let road = [LatLng(51.5, -0.2), LatLng(51.5, -0.16)]
        let now = Date()
        XCTAssertEqual(PoliceRisk.score(ahead: road, past: [], live: 0, now: now), 0)
        XCTAssertEqual(PoliceRisk.level(PoliceRisk.score(ahead: road, past: [], live: 1, now: now)), .high)
        let near = (0..<2).map { _ in PoliceRisk.Report(LatLng(51.5002, -0.18), now.addingTimeInterval(-86_400 * 7)) }
        let far = (0..<20).map { _ in PoliceRisk.Report(LatLng(51.6, -0.18), now) }
        XCTAssertEqual(PoliceRisk.level(PoliceRisk.score(ahead: road, past: near, live: 0, now: now)), .medium)
        XCTAssertEqual(PoliceRisk.score(ahead: road, past: far, live: 0, now: now), 0)
    }
}

final class AircraftTests: XCTestCase {
    func testParse() {
        let json = #"{"ac":[{"hex":"406f5c","flight":"NPAS15  ","r":"G-POLA","t":"EC35","lat":51.5,"lon":-0.1,"alt_baro":1500,"gs":90,"track":120},{"hex":"aaa","flight":"BAW123","ownOp":"British Airways","lat":51.4,"lon":-0.4,"alt_baro":9000},{"hex":"bbb","ownOp":"County of Los Angeles Sheriff","category":"A7","lat":34,"lon":-118,"alt_baro":800},{"hex":"ccc","flight":"UKP02","lat":51,"lon":0,"alt_baro":"ground"}]}"#
        let list = Aircraft.parse(Data(json.utf8))!
        XCTAssertEqual(list.map(\.id), ["406f5c", "bbb"])
        XCTAssertTrue(list[0].helicopter)
        XCTAssertEqual(list[0].callsign, "NPAS15")
        XCTAssertEqual(list[0].altitudeFeet, 1500)
        XCTAssertTrue(list[1].helicopter)
    }
}
