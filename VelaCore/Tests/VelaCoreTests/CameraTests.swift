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
}
