import XCTest
@testable import VelaCore

final class RoundaboutTests: XCTestCase {
    /** M27 junction 9 (Park Gate / Whiteley) from the north-west, as the open router answered on 2026-10-25: 5th exit. */
    let parkGate: [String: Any] = [
        "maneuver": ["type": "rotary", "exit": 5, "location": [-1.253292, 50.873558]],
        "intersections": [
            ["location": [-1.253292, 50.873558], "bearings": [120, 165, 330], "entry": [false, false, true], "in": 1, "out": 2],
            ["location": [-1.253542, 50.873819], "bearings": [0, 150, 315], "entry": [true, false, true], "in": 1, "out": 0],
            ["location": [-1.252877, 50.875028], "bearings": [75, 225, 255], "entry": [true, false, false], "in": 1, "out": 0],
            ["location": [-1.252625, 50.87509], "bearings": [75, 255, 345], "entry": [true, false, true], "in": 1, "out": 0],
            ["location": [-1.252442, 50.87513], "bearings": [60, 90, 255], "entry": [true, true, false], "in": 2, "out": 1],
            ["location": [-1.251731, 50.875004], "bearings": [135, 315, 345], "entry": [true, false, false], "in": 1, "out": 0],
            ["location": [-1.251667, 50.874959], "bearings": [75, 150, 315], "entry": [true, true, false], "in": 2, "out": 1],
        ] as [[String: Any]],
    ]
    let parkGateExit: [String: Any] = [
        "maneuver": ["type": "exit rotary", "location": [-1.251482, 50.874705]],
        "intersections": [
            ["location": [-1.251482, 50.874705], "bearings": [150, 165, 345], "entry": [true, true, false], "in": 2, "out": 0],
        ] as [[String: Any]],
    ]

    func testSketchCountsTheExitsPassed() throws {
        let s = try XCTUnwrap(OSRM.roundaboutSketch(parkGate, next: parkGateExit, clockwise: true))
        // Four exits passed on the way round, then yours: the 5th.
        XCTAssertEqual(s.exits.count, 5)
        XCTAssertEqual(s.taken, 4)
        XCTAssertFalse(s.complete)
        XCTAssertEqual(s.entriesOnly.count, 2)
        // Going round clockwise, each exit is further round than the one before.
        let turns = s.exits.indices.map(s.turn)
        XCTAssertEqual(turns, turns.sorted())
        XCTAssertTrue(turns.allSatisfy { $0 > 15 && $0 < 375 })
    }

    func testCircleFit() throws {
        let c = LatLng(51.0, -1.0)
        let pts = stride(from: 0.0, to: 300, by: 50).map { a -> LatLng in
            let r = 60.0
            return LatLng(c.lat + r * cos(a * .pi / 180) / 110_540, c.lng + r * sin(a * .pi / 180) / (111_320 * cos(51 * .pi / 180)))
        }
        let fit = try XCTUnwrap(OSRM.fitCircle(pts))
        XCTAssertLessThan(fit.distance(to: c), 2)
    }

    func testHighwayCodeLane() {
        // Keep left: first exit or straight on in the left lane, past 12 o'clock in the right.
        let left = RoundaboutShape(exits: [270, 0, 90], taken: 0, clockwise: true, complete: true)
        XCTAssertEqual(left.ruleLane, 0)
        var right = left
        right.taken = 2
        XCTAssertEqual(right.ruleLane, 1)
        var ahead = left
        ahead.taken = 1
        XCTAssertEqual(ahead.ruleLane, 0)
    }

    /** A plain four-way roundabout built by hand: ring, four two-way roads, the route going right (3rd exit in Britain). */
    func testShapeFromOpenStreetMap() throws {
        let c = LatLng(51.5, -0.1)
        let k = 111_320 * cos(51.5 * .pi / 180)
        func at(_ deg: Double, _ r: Double) -> LatLng {
            LatLng(c.lat + r * cos(deg * .pi / 180) / 110_540, c.lng + r * sin(deg * .pi / 180) / k)
        }
        // Ring nodes every 30 degrees, ids 1...12, closed.
        let ringIds: [Int64] = Array(1...12) + [1]
        let ringPts = (0..<12).map { at(Double($0) * 30, 30) } + [at(0, 30)]
        var ways = [Roundabouts.Way(id: 100, nodes: ringIds, points: ringPts, tags: ["junction": "roundabout", "highway": "primary"])]
        // Roads at north (node 1), east (4), south (7), west (10).
        for (i, deg) in [0.0, 90, 180, 270].enumerated() {
            let ring = Int64([1, 4, 7, 10][i])
            ways.append(Roundabouts.Way(id: Int64(200 + i), nodes: [ring, Int64(50 + i)], points: [at(deg, 30), at(deg, 120)],
                                        tags: ["highway": "primary"]))
        }
        // The route: up from the south, round clockwise (west, north) and out east.
        var line = [at(180, 200), at(180, 30)]
        for d in stride(from: 210.0, through: 450, by: 15) { line.append(at(d, 30)) }
        line.append(at(90, 200))
        let cum = Geo.cumulative(line)
        let along = Geo.project(at(180, 30), on: line, cumulative: cum).along
        let s = try XCTUnwrap(Roundabouts.shape(ways, entry: at(180, 30), line: line, cumulative: cum, along: along,
                                                clockwise: true, exitNumber: 3))
        // West, north, east, then back south: four exits, the third taken.
        XCTAssertEqual(s.exits.count, 4)
        XCTAssertEqual(s.taken, 2)
        XCTAssertTrue(s.complete)
        XCTAssertEqual(s.exits[0], 270, accuracy: 3)
        XCTAssertLessThan(Roundabouts.angleGap(s.exits[1], 0), 3)
        XCTAssertEqual(s.exits[2], 90, accuracy: 3)
        XCTAssertEqual(s.ruleLane, 1)
    }

    /** A dual carriageway meeting the circle with a way in and a way out is one road. */
    func testDualCarriagewayMerges() {
        let arms = [
            Roundabouts.Arm(angle: 80, canExit: true, canEnter: false, name: "A27"),
            Roundabouts.Arm(angle: 100, canExit: false, canEnter: true, name: "A27"),
            Roundabouts.Arm(angle: 200, canExit: true, canEnter: true, name: nil),
        ]
        let merged = Roundabouts.merge(arms)
        XCTAssertEqual(merged.count, 2)
        XCTAssertEqual(merged[0].angle, 90, accuracy: 0.5)
        XCTAssertTrue(merged[0].canExit && merged[0].canEnter)
    }
}
