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

    func testStopTimes() {
        let json = """
        {"stopTimes":[
          {"place":{"departure":"2026-10-23T17:10:00Z","scheduledDeparture":"2026-10-23T17:08:00Z"},"mode":"BUS","realTime":true,
           "headsign":"Downtown","routeShortName":"42","routeColor":"#0055AA"},
          {"place":{"departure":"2026-10-23T17:12:00Z","cancelled":true},"mode":"BUS","routeShortName":"43"},
          {"place":{"departure":"2026-10-23T17:20:00Z"},"mode":"TRAM","displayName":"T"},
          {"place":{"departure":"2026-10-23T17:25:00Z"},"mode":"RAIL","routeShortName":"CC","headsign":"Davis","tripTo":{"name":"San Jose"}},
          {"place":{"departure":"2026-10-23T17:30:00Z"},"mode":"RAIL","routeShortName":"CC","headsign":"Davis","tripTo":{"name":"Davis"}}
        ]}
        """
        let d = Transitous.parseStopTimes(Data(json.utf8), stopName: "Davis")
        XCTAssertEqual(d.count, 3, "a canceled call and a run ending here are dropped")
        XCTAssertEqual(d[2].headsign, "San Jose")
        XCTAssertEqual(d[0].line, "42")
        XCTAssertEqual(d[0].color, 0x0055AA)
        XCTAssertEqual(d[0].delayMinutes, 2)
        XCTAssertEqual(d[1].line, "T")
        XCTAssertNil(d[1].delayMinutes)
        XCTAssertEqual(d[1].vehicle, .tram)
    }

    func testLines() {
        let json = """
        {"routes":[{"mode":"BUS","transitRoutes":[{"color":"FF0000"}]},{"mode":"SUBWAY","transitRoutes":[{"color":"FCCC0A","shortName":"N"}]}],
         "polylines":[{"polyline":{"points":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","precision":5},"routeIndexes":[0]},
                      {"polyline":{"points":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","precision":5},"routeIndexes":[0,1]}]}
        """
        let lines = Transitous.parseLines(Data(json.utf8))
        XCTAssertEqual(lines.count, 1, "bus-only stretches are left out")
        XCTAssertEqual(lines[0].color, 0xFCCC0A)
        XCTAssertTrue(lines[0].metro)
        XCTAssertEqual(lines[0].names, ["N"])
    }
}
