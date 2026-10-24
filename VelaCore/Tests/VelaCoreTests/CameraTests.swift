import XCTest
@testable import VelaCore

final class CameraTests: XCTestCase {
    func testParse() {
        let json = #"{"elements":[{"type":"node","id":1,"lat":48.1,"lon":11.5,"tags":{"highway":"speed_camera"}},{"type":"node","id":2,"lat":38.5,"lon":-121.7,"tags":{"surveillance:type":"ALPR","manufacturer":"Flock Safety"}},{"type":"node","id":3}]}"#
        let cams = Overpass.parse(Data(json.utf8))
        XCTAssertEqual(cams?.count, 2)
        XCTAssertEqual(cams?[0].kind, .speed)
        XCTAssertEqual(cams?[1].kind, .plate)
        XCTAssertEqual(cams?[1].operatorName, "Flock Safety")
    }

    func testQuery() {
        let q = Overpass.query(filter: "(1,2,3,4)", speed: true, plate: false)
        XCTAssertEqual(q, "[out:json][timeout:25];(node[\"highway\"=\"speed_camera\"](1,2,3,4););out body 4000;")
    }

    func testDue() {
        // At 20 m/s the lead is 240 m: the camera 200 m ahead is due, the one 400 m ahead is not yet.
        XCTAssertEqual(CameraAlerts.due(cameraMeters: [500, 700], traveled: 300, speedMps: 20, spoken: []), 0)
        XCTAssertNil(CameraAlerts.due(cameraMeters: [500, 700], traveled: 300, speedMps: 20, spoken: [0]))
        XCTAssertNil(CameraAlerts.due(cameraMeters: [500], traveled: 300, speedMps: 1, spoken: []))
        XCTAssertNil(CameraAlerts.due(cameraMeters: [200], traveled: 300, speedMps: 20, spoken: []))
    }

    func testProject() {
        let line = [LatLng(0, 0), LatLng(0.01, 0)]
        let on = RoadCamera(id: 1, kind: .speed, location: LatLng(0.005, 0.0001))
        let off = RoadCamera(id: 2, kind: .speed, location: LatLng(0.005, 0.01))
        let p = CameraAlerts.project([off, on], on: line)
        XCTAssertEqual(p.count, 1)
        XCTAssertEqual(p[0].camera.id, 1)
        XCTAssertEqual(p[0].along, Geo.length(line) / 2, accuracy: 2)
    }

    func testMaxspeed() {
        XCTAssertEqual(Overpass.maxspeedKmh("50"), 50)
        XCTAssertEqual(Overpass.maxspeedKmh("30 mph") ?? 0, 48.28, accuracy: 0.01)
        XCTAssertNil(Overpass.maxspeedKmh("DE:urban"))
        XCTAssertNil(Overpass.maxspeedKmh("none"))
    }

    func testSpeedLimitsAlongRoute() {
        // Route north ~1,113 m; a 50 road covers the first half, a 30 road the rest, and a
        // crossing 80 road touches it at one point only.
        let route = [LatLng(0, 0), LatLng(0.01, 0)]
        let ways = [
            Overpass.LimitWay(kmh: 50, line: [LatLng(0, 0), LatLng(0.005, 0)]),
            Overpass.LimitWay(kmh: 30, line: [LatLng(0.005, 0), LatLng(0.01, 0)]),
            Overpass.LimitWay(kmh: 80, line: [LatLng(0.003, -0.01), LatLng(0.003, 0), LatLng(0.003, 0.01)]),
        ]
        let limits = Overpass.speedLimits(ways, on: route)
        XCTAssertEqual(limits.first?.kmh, 50)
        XCTAssertFalse(limits.contains { $0.kmh == 80 })
        var r = Route(mode: .drive, polyline: route, distanceMeters: 1113, typicalSeconds: 60, trafficSeconds: nil,
                      summary: nil, maneuvers: [], source: "osm")
        r.speedLimits = limits
        XCTAssertEqual(r.speedLimit(at: 200), 50)
        XCTAssertEqual(r.speedLimit(at: 900), 30)
    }

    func testBritishLimits() {
        XCTAssertEqual(Overpass.maxspeedKmh("national") ?? 0, 96.56, accuracy: 0.1)
        XCTAssertEqual(Overpass.maxspeedKmh("GB:nsl_dual") ?? 0, 112.65, accuracy: 0.1)
        XCTAssertEqual(Overpass.maxspeedKmh("20 mph") ?? 0, 32.19, accuracy: 0.1)
        XCTAssertTrue(Geo.drivesOnLeft(LatLng(51.5074, -0.1278)), "London")
        XCTAssertTrue(Geo.drivesOnLeft(LatLng(55.95, -3.19)), "Edinburgh")
        XCTAssertTrue(Geo.drivesOnLeft(LatLng(51.48, -3.18)), "Cardiff")
        XCTAssertFalse(Geo.drivesOnLeft(LatLng(48.85, 2.35)), "Paris")
        XCTAssertFalse(Geo.drivesOnLeft(LatLng(38.54, -121.74)), "Davis")
    }

    func testAverageZones() {
        // A straight road north; a relation whose start and end nodes sit 0.9 km apart on it.
        let line = [LatLng(51.0, -1.0), LatLng(51.02, -1.0)]
        let json = """
        {"elements":[{"type":"relation","tags":{"enforcement":"average_speed","maxspeed":"50 mph"},
          "members":[{"type":"node","lat":51.002,"lon":-1.0},{"type":"node","lat":51.0101,"lon":-1.0},
                     {"type":"node","lat":51.05,"lon":-1.2}]}]}
        """
        let z = Overpass.averageZones(Data(json.utf8), on: line)
        XCTAssertEqual(z.count, 1)
        XCTAssertEqual(z.first?.startMeters ?? 0, 222, accuracy: 5)
        XCTAssertEqual(z.first?.endMeters ?? 0, 1123, accuracy: 5)
        XCTAssertEqual(z.first?.kmh ?? 0, 80.47, accuracy: 0.1)
    }
}

final class PostcodeTests: XCTestCase {
    func testPostcodes() {
        XCTAssertEqual(Postcodes.normalize("sw1a1aa"), "SW1A 1AA")
        XCTAssertEqual(Postcodes.normalize(" CF10 1NS "), "CF10 1NS")
        XCTAssertEqual(Postcodes.normalize("EH1 1YZ"), "EH1 1YZ")
        XCTAssertNil(Postcodes.normalize("pizza"))
        XCTAssertNil(Postcodes.normalize("12345"))
        let json = #"{"status":200,"result":{"postcode":"CF10 1NS","latitude":51.48,"longitude":-3.18,"admin_district":"Cardiff","admin_ward":"Cathays","country":"Wales"}}"#
        let p = Postcodes.parse(Data(json.utf8), code: "CF10 1NS")
        XCTAssertEqual(p?.name, "CF10 1NS")
        XCTAssertEqual(p?.address, "Cathays, Cardiff, Wales")
    }
}
