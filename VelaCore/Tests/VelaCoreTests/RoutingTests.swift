import XCTest
@testable import VelaCore

final class RoutingTests: XCTestCase {
    func testDecodePolyline5() {
        // Google's documented example.
        let pts = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision: 1e5)
        XCTAssertEqual(pts.count, 3)
        XCTAssertEqual(pts[0].lat, 38.5, accuracy: 1e-6)
        XCTAssertEqual(pts[0].lng, -120.2, accuracy: 1e-6)
        XCTAssertEqual(pts[2].lat, 43.252, accuracy: 1e-6)
        XCTAssertEqual(pts[2].lng, -126.453, accuracy: 1e-6)
    }

    func testGoogleStep() {
        let m = DirectionsParser.parseStep("<step maneuver='TURN' meters='120'>Turn <turn side='LEFT'>left</turn> onto <roadlist><road>Elm St</road></roadlist></step>")
        XCTAssertEqual(m.type, .turnLeft)
        XCTAssertEqual(m.distanceMeters, 120)
        XCTAssertEqual(m.road, "Elm St")
        XCTAssertEqual(m.instruction, "Turn left onto Elm St")
    }

    func testGeometryDeltas() {
        let g = DirectionsParser.decodeGeometry([[385_000_000, 10_000], [-1_217_000_000, -20_000]])
        XCTAssertEqual(g?.count, 2)
        XCTAssertEqual(g?[1].lat ?? 0, 38.501, accuracy: 1e-9)
        XCTAssertEqual(g?[1].lng ?? 0, -121.702, accuracy: 1e-9)
    }

    func testPbHasNoPlaceholdersLeft() {
        let pb = DirectionsPb.build(origin: LatLng(38.5, -121.7), destination: LatLng(38.7, -121.6), mode: .walk, avoidTolls: true)
        XCTAssertFalse(pb.contains("{"))
        XCTAssertFalse(pb.contains(" "))
        XCTAssertTrue(pb.contains("!1e2!2e3"))
        XCTAssertTrue(pb.hasPrefix("!1m4!3m2!3d38.5!4d-121.7!6e2!1m4!3m2!3d38.7!4d-121.6!6e2!3m12!1m3!1d"))
        XCTAssertTrue(pb.hasSuffix("!47m2!8b1!10e2"))
        let drive = DirectionsPb.build(origin: LatLng(1, 2), destination: LatLng(3, 4), mode: .drive, avoidTolls: true)
        XCTAssertTrue(drive.contains("!6m57!1m5!18b1!30b1!31m1!1b1!34e1!2m5!2b1!5m1"))
    }

    func testTracker() {
        // A straight 1.1 km line north with a turn in the middle and the arrival at the end.
        let line = [LatLng(0, 0), LatLng(0.005, 0), LatLng(0.01, 0)]
        let total = Geo.length(line)
        let route = Route(mode: .drive, polyline: line, distanceMeters: total, typicalSeconds: 100, trafficSeconds: nil,
                          summary: nil, maneuvers: [
                            Maneuver(type: .depart, instruction: "Go", location: line[0], alongMeters: 0, distanceMeters: total / 2),
                            Maneuver(type: .turnLeft, instruction: "Turn", location: line[1], alongMeters: total / 2, distanceMeters: total / 2),
                            Maneuver(type: .arrive, instruction: "Arrive", location: line[2], alongMeters: total, distanceMeters: 0),
                          ], source: "osm")
        let t = RouteTracker(route: route)
        let p = t.update(LatLng(0.0025, 0.0001))
        XCTAssertEqual(p.nextIndex, 1)
        XCTAssertEqual(p.distanceToNext, total / 4, accuracy: 5)
        XCTAssertEqual(p.remainingSeconds, 75, accuracy: 1)
        XCTAssertFalse(p.offRoute)
        // Three fixes far away: off route.
        _ = t.update(LatLng(0.006, 0.01))
        _ = t.update(LatLng(0.006, 0.01))
        XCTAssertTrue(t.update(LatLng(0.006, 0.01)).offRoute)
        XCTAssertTrue(t.update(LatLng(0.00999, 0)).arrived)
    }

    func testStepsPlacedFromTheEnd() {
        // About 1,113 m due north; Google gave only the last turn (300 m), not the "Head north" step.
        let line = [LatLng(0, 0), LatLng(0.01, 0)]
        let turn = DirectionsParser.parseStep("<step maneuver='TURN' meters='300'>Turn <turn side='LEFT'>left</turn></step>")
        let out = DirectionsParser.place([turn], on: line)
        XCTAssertEqual(out.map(\.type), [.depart, .turnLeft, .arrive])
        XCTAssertEqual(out[0].instruction, "Head north")
        XCTAssertEqual(out[1].alongMeters, Geo.length(line) - 300, accuracy: 0.5)
        XCTAssertEqual(out[2].alongMeters, Geo.length(line), accuracy: 0.5)
    }

    func testStepMergeOntoGoogleLine() {
        let google = [LatLng(0, 0), LatLng(0.01, 0)]
        func m(_ t: ManeuverType, _ lat: Double, _ text: String) -> Maneuver {
            Maneuver(type: t, instruction: text, location: LatLng(lat, 0.00004), alongMeters: 0, distanceMeters: 0, road: nil, laneHint: nil)
        }
        let osrm = Route(mode: .drive, polyline: google, distanceMeters: 1113, typicalSeconds: 100, trafficSeconds: nil, summary: nil,
                         maneuvers: [m(.depart, 0, "Drive north on A St"), m(.turnRight, 0.0045, "Turn right onto B St"), m(.arrive, 0.01, "You have arrived")],
                         source: "osm")
        let steps = StepMerge.adopt([osrm], onto: google)
        XCTAssertEqual(steps?.map(\.type), [.depart, .turnRight, .arrive])
        XCTAssertEqual(steps?[1].alongMeters ?? 0, Geo.length(google) * 0.45, accuracy: 2)
        XCTAssertEqual(steps?[0].distanceMeters ?? 0, Geo.length(google) * 0.45, accuracy: 2)
        // A route on other roads doesn't fit.
        let far = [LatLng(0, 0.01), LatLng(0.01, 0.01)]
        XCTAssertNil(StepMerge.adopt([osrm], onto: far))
    }
}
