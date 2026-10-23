import XCTest
@testable import VelaCore

final class TransitTests: XCTestCase {
    func testParsePlan() throws {
        let json = """
        {"itineraries":[
          {"duration":1800,"startTime":"2026-10-23T17:00:00Z","endTime":"2026-10-23T17:30:00Z","transfers":0,"legs":[
            {"mode":"WALK","duration":300,"distance":350,"startTime":"2026-10-23T17:00:00Z","endTime":"2026-10-23T17:05:00Z",
             "from":{"name":"START","lat":38.5,"lon":-120.2},"to":{"name":"Market St","lat":38.51,"lon":-120.21}},
            {"mode":"SUBWAY","duration":1200,"routeShortName":"N","routeColor":"FCCC0A","routeTextColor":"000000",
             "headsign":"Ocean Beach","realTime":true,"startTime":"2026-10-23T17:07:00Z","scheduledStartTime":"2026-10-23T17:05:00Z",
             "endTime":"2026-10-23T17:27:00Z","intermediateStops":[{},{},{}],
             "from":{"name":"Market St","lat":38.51,"lon":-120.21},"to":{"name":"Judah St","lat":40.7,"lon":-120.95},
             "legGeometry":{"points":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","precision":5}},
            {"mode":"WALK","duration":180,"from":{"name":"Judah St","lat":40.7,"lon":-120.95},"to":{"name":"END","lat":40.71,"lon":-120.96}}
          ]},
          {"duration":900,"legs":[{"mode":"WALK","duration":900}]}
        ]}
        """
        let trips = Transitous.parse(Data(json.utf8))
        XCTAssertEqual(trips.count, 1, "a walk-only answer is not a transit trip")
        let t = try XCTUnwrap(trips.first)
        XCTAssertEqual(t.durationSeconds, 1800)
        XCTAssertEqual(t.legs.count, 3)
        let ride = t.legs[1]
        XCTAssertFalse(ride.isWalk)
        XCTAssertEqual(ride.line, "N")
        XCTAssertEqual(ride.color, 0xFCCC0A)
        XCTAssertEqual(ride.stops, 4)
        XCTAssertEqual(ride.delayMinutes, 2)
        XCTAssertEqual(ride.vehicle, .metro)
        XCTAssertEqual(ride.path.count, 3)
        XCTAssertEqual(ride.path[0].lat, 38.5, accuracy: 1e-5)
        XCTAssertEqual(ride.path[2].lng, -126.453, accuracy: 1e-5)
        XCTAssertTrue(t.legs[0].isWalk)
        XCTAssertEqual(t.legs[0].path.count, 2, "a leg with no shape is drawn stop to stop")
        XCTAssertEqual(t.walkSeconds, 480)
    }
}
