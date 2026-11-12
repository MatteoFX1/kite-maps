import XCTest
@testable import VelaCore

/** Steps the open router leaves out or says twice, filled in and tidied (StepFill.swift). */
final class StepFillTests: XCTestCase {
    /** A straight line about 5.5 km due north, so every step sits on it. */
    let line = [LatLng(50.0, -1.0), LatLng(50.05, -1.0)]

    func step(_ t: ManeuverType, _ words: String, _ at: Double, road: String? = nil, exit: Int? = nil) -> Maneuver {
        var m = Maneuver(type: t, instruction: words, location: Geo.point(along: line, meters: at), alongMeters: at, distanceMeters: 0, road: road)
        m.exit = exit
        return m
    }

    func ends(_ mid: [Maneuver]) -> [Maneuver] {
        var all = [step(.depart, "Head out", 0)] + mid + [step(.arrive, "You have arrived", Geo.length(line))]
        StepMerge.respace(&all)
        return all
    }

    func testGoogleTurnTheRouterLeftOutIsAdded() {
        // Spaghetti Junction: the router says nothing where the A38(M) splits; Google says keep right.
        var steps = ends([step(.merge, "Merge onto A38(M)", 1000, road: "A38(M)"), step(.straight, "Continue straight onto Gravelly Hill", 3000, road: "Gravelly Hill")])
        let google = [step(.straight, "Head north", 0), step(.merge, "Merge onto A38(M)", 990), step(.turnRight, "Keep right to stay on A38(M)", 2200),
                      step(.merge, "Merge onto Gravelly Hill", 2990)]
        StepMerge.addGoogleMissing(&steps, from: google, line: line)
        XCTAssertEqual(steps.map(\.instruction), ["Head out", "Merge onto A38(M)", "Keep right to stay on A38(M)", "Continue straight onto Gravelly Hill", "You have arrived"])
        XCTAssertEqual(steps[2].alongMeters, 2200, accuracy: 15)
        XCTAssertEqual(steps[2].road, "A38(M)")
        XCTAssertEqual(steps[1].distanceMeters, steps[2].alongMeters - steps[1].alongMeters, accuracy: 0.1)
    }

    func testDriftedGoogleStepIsNotAddedTwice() {
        // Google's own "Exit at junction 9" counted back 120 m off the router's "Take exit 9".
        var steps = ends([step(.rampLeft, "Take exit 9 toward Redbourn", 2000), step(.roundabout, "At the roundabout, take the 3rd exit", 2600, exit: 3)])
        let google = [step(.rampLeft, "Exit at junction 9", 2120), step(.roundabout, "At the roundabout, take the 3rd exit", 2610, exit: 3)]
        StepMerge.addGoogleMissing(&steps, from: google, line: line)
        XCTAssertEqual(steps.count, 4)
    }

    func testSecondOfTwoRoundaboutsIsAdded() {
        // Six Ways, Erdington: two roundabouts 130 m apart, the router only knows the first.
        var first = step(.roundabout, "At the roundabout, take the 2nd exit onto Summer Road", 3600, road: "Summer Road", exit: 2)
        first.roundabout = nil
        var off = step(.roundabout, "Exit the roundabout onto Summer Road", 3620, road: "Summer Road", exit: 2)
        off.leavesRoundabout = true
        var steps = ends([first, off])
        let google = [step(.roundabout, "At the roundabout, take the 2nd exit", 3602, exit: 2), step(.roundabout, "At the roundabout, take the 1st exit", 3733, exit: 1)]
        StepMerge.addGoogleMissing(&steps, from: google, line: line)
        XCTAssertEqual(steps.filter { $0.type == .roundabout && !$0.leavesRoundabout }.map(\.exit), [2, 1])
    }

    func testMergeIntoALaneThatRunsToTheExitStaysOut() {
        var steps = ends([step(.rampLeft, "Take exit 3 toward Totton", 1500)])
        let google = [step(.merge, "Merge onto A33", 1000)]
        StepMerge.addGoogleMissing(&steps, from: google, line: line)
        XCTAssertFalse(steps.contains { $0.type == .merge })
        var far = ends([step(.rampLeft, "Take exit 15 toward Springburn", 4000)])
        StepMerge.addGoogleMissing(&far, from: [step(.merge, "Merge onto M8", 1000)], line: line)
        XCTAssertTrue(far.contains { $0.instruction == "Merge onto M8" })
    }

    func testGoogleRoundaboutWhereTheRouterSaysTurn() {
        var steps = ends([step(.turnRight, "Turn right onto Otterbourne Hill", 1000, road: "Otterbourne Hill")])
        StepMerge.addGoogleKinds(&steps, from: [step(.roundabout, "At the roundabout, take the 2nd exit", 985, exit: 2)])
        XCTAssertEqual(steps[1].type, .roundabout)
        XCTAssertEqual(steps[1].exit, 2)
        XCTAssertEqual(steps[1].instruction, "At the roundabout, take the 2nd exit onto Otterbourne Hill")
    }

    func testRealTurnWhereTheRouterSaysStraight() {
        var steps = ends([step(.straight, "Continue straight onto Gloucester Road", 1000, road: "Gloucester Road")])
        StepMerge.addGoogleKinds(&steps, from: [step(.turnLeft, "Turn left onto Gloucester Rd", 1010)])
        XCTAssertEqual(steps[1].type, .turnLeft)
        XCTAssertEqual(steps[1].instruction, "Turn left onto Gloucester Road")
    }

    func testJunctionNumberFromGoogle() {
        var steps = ends([step(.rampLeft, "Take the exit toward The Midlands and London", 1000), step(.rampLeft, "Take the exit", 3000)])
        let google = [step(.rampLeft, "Exit at junction 4", 1030), step(.rampLeft, "Take the exit towards Emsworth", 3110)]
        StepMerge.addGoogleSigns(&steps, from: google)
        XCTAssertEqual(steps[1].instruction, "Take exit 4 toward The Midlands and London")
        XCTAssertEqual(steps[2].instruction, "Take the exit towards Emsworth")
    }

    func testTurnThenContinueIsOneTurn() {
        var steps = ends([step(.turnRight, "Turn right", 2108), step(.straight, "Continue straight onto Wide Ln", 2119, road: "Wide Ln")])
        StepMerge.tidy(&steps)
        XCTAssertEqual(steps.filter { !$0.silent }.map(\.instruction), ["Head out", "Turn right onto Wide Ln", "You have arrived"])
    }

    func testSameTurnTwiceIsOnce() {
        var a = step(.slightRight, "Turn slight right onto Coldhams Lane", 1098, road: "Coldhams Lane")
        a.lanes = [Lane(indications: ["straight"], valid: false), Lane(indications: ["right"], valid: true)]
        var steps = ends([a, step(.turnRight, "Turn right onto Coldhams Lane", 1106, road: "Coldhams Lane")])
        StepMerge.tidy(&steps)
        let spoken = steps.filter { !$0.silent }
        XCTAssertEqual(spoken.map(\.instruction), ["Head out", "Turn right onto Coldhams Lane", "You have arrived"])
        XCTAssertEqual(spoken[1].lanes.count, 2)
    }

    func testStayOnTheSameRoad() {
        var steps = ends([step(.turnLeft, "Turn left onto Transport Avenue", 500, road: "Transport Avenue"),
                          step(.turnLeft, "Turn left onto Transport Avenue", 900, road: "Transport Avenue"),
                          step(.straight, "Continue onto Transport Avenue", 1400, road: "Transport Avenue")])
        StepMerge.tidy(&steps)
        XCTAssertEqual(steps.filter { !$0.silent }.map(\.instruction),
                       ["Head out", "Turn left onto Transport Avenue", "Turn left to stay on Transport Avenue", "You have arrived"])
    }

    func testStartRoadIsNotTheRoadYoureOn() {
        var depart = step(.depart, "Exit the car park towards Elmbank Cres", 0, road: "Elmbank Cres")
        depart.distanceMeters = 46
        var steps = [depart, step(.turnLeft, "Turn left onto Elmbank Cres", 46, road: "Elmbank Cres"), step(.arrive, "You have arrived", 500)]
        StepMerge.tidy(&steps)
        XCTAssertEqual(steps[1].instruction, "Turn left onto Elmbank Cres")
    }

    func testPiecewiseUsesGoogleWhereTheRouterStrays() {
        // The router follows the line except for a stretch in the middle where it goes its own way.
        let total = Geo.length(line)
        let a = Geo.point(along: line, meters: 2000), b = Geo.point(along: line, meters: 3000)
        let off = LatLng((a.lat + b.lat) / 2, a.lng + 0.01)
        var own = Route(mode: .drive, polyline: [line[0], a, off, b, line[1]], distanceMeters: total, typicalSeconds: 300, trafficSeconds: nil,
                        summary: nil, maneuvers: [], source: "osm")
        own.maneuvers = [step(.depart, "Drive along High Street", 0, road: "High Street"), step(.turnLeft, "Turn left onto Mill Lane", 1000, road: "Mill Lane"),
                         step(.turnRight, "Turn right onto Nowhere Road", 2000), step(.turnLeft, "Turn left onto Station Road", 4500, road: "Station Road"),
                         step(.arrive, "You have arrived", total)]
        let google = [step(.straight, "Head north on High St", 0), step(.turnLeft, "Turn left onto Mill Ln", 1005),
                      step(.roundabout, "At the roundabout, take the 1st exit", 2500, exit: 1), step(.turnLeft, "Turn left onto Station Rd", 4490),
                      step(.arrive, "You have arrived", total)]
        let out = StepMerge.adoptPiecewise([own], google: google, onto: line)
        XCTAssertEqual(out?.map(\.instruction), ["Drive along High Street", "Turn left onto Mill Lane", "At the roundabout, take the 1st exit",
                                                 "Turn left onto Station Road", "You have arrived"])
    }

    func testGoogleKeepIsAKeepArrow() {
        let m = DirectionsParser.parseStep("<step maneuver='TURN' meters='500'>Keep <turn side='RIGHT'>right</turn> to stay on <road>A38(M)</road></step>")
        XCTAssertEqual(m.type, .keepRight)
    }
}
