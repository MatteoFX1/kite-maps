import XCTest
@testable import VelaCore

final class TripNotesTests: XCTestCase {
    func testClosingTimes() {
        XCTAssertEqual(ClosingCheck.closing("Open · Closes 10 pm")?.minutes, 22 * 60)
        XCTAssertEqual(ClosingCheck.closing("Open ⋅ Closes 5:30 pm")?.minutes, 17 * 60 + 30)
        XCTAssertEqual(ClosingCheck.closing("Open ⋅ Closes 5:30 pm")?.text, "5:30 pm")
        XCTAssertEqual(ClosingCheck.closing("Closes soon ⋅ 5 PM")?.minutes, 17 * 60)
        XCTAssertEqual(ClosingCheck.closing("Open · Closes 17:00")?.minutes, 17 * 60)
        XCTAssertEqual(ClosingCheck.closing("Open · Closes 12 am")?.minutes, 0)
        XCTAssertEqual(ClosingCheck.closing("Open · Closes midnight")?.minutes, 24 * 60)
        XCTAssertNil(ClosingCheck.closing("Open 24 hours"))
        XCTAssertNil(ClosingCheck.closing("Closed · Opens 9 am"))
        XCTAssertNil(ClosingCheck.closing(nil))
    }

    func testClosesBeforeArrival() {
        // 16:30 now, 40 minutes away, shuts at 5: there ten minutes too late.
        XCTAssertEqual(ClosingCheck.warning(status: "Open · Closes 5 pm", nowMinutes: 16 * 60 + 30, travelSeconds: 40 * 60),
                       ClosingCheck.Warning(closes: "5 pm", before: true))
        // 20 minutes away: there ten minutes before it shuts.
        XCTAssertEqual(ClosingCheck.warning(status: "Open · Closes 5 pm", nowMinutes: 16 * 60 + 30, travelSeconds: 20 * 60),
                       ClosingCheck.Warning(closes: "5 pm", before: false))
        // Plenty of time.
        XCTAssertNil(ClosingCheck.warning(status: "Open · Closes 10 pm", nowMinutes: 16 * 60 + 30, travelSeconds: 40 * 60))
        // Shuts at 1 in the morning, asked at 10 in the evening, 20 minutes away: fine.
        XCTAssertNil(ClosingCheck.warning(status: "Open · Closes 1 am", nowMinutes: 22 * 60, travelSeconds: 20 * 60))
        // The same, four hours away: shut.
        XCTAssertEqual(ClosingCheck.warning(status: "Open · Closes 1 am", nowMinutes: 22 * 60, travelSeconds: 4 * 3600)?.before, true)
    }

    func testRoadCharges() {
        // Over the Thames at Dartford, south to north.
        let dartford = [LatLng(51.4500, 0.2500), LatLng(51.4647, 0.2586), LatLng(51.4800, 0.2700)]
        XCTAssertEqual(RoadCharges.on(dartford).map(\.name), ["Dartford Crossing"])
        // Along the south bank a mile from the crossing: nothing.
        XCTAssertEqual(RoadCharges.on([LatLng(51.4480, 0.2200), LatLng(51.4480, 0.2400)]), [])
        // Southampton to Winchester: nothing.
        XCTAssertEqual(RoadCharges.on([LatLng(50.9097, -1.4044), LatLng(51.0632, -1.3080)]), [])
        // To Trafalgar Square: inside the Congestion Charge zone.
        XCTAssertEqual(RoadCharges.on([LatLng(51.5400, -0.1500), LatLng(51.5080, -0.1281)]).map(\.name), ["London Congestion Charge zone"])
        // Round the ring road (Euston Road), which is free.
        XCTAssertEqual(RoadCharges.on([LatLng(51.5238, -0.1440), LatLng(51.5304, -0.1238)]), [])
        // Blackwall and Silvertown are close together but not the same tunnel.
        XCTAssertEqual(RoadCharges.on([LatLng(51.5100, -0.0040), LatLng(51.5045, -0.0019), LatLng(51.4990, 0.0010)]).map(\.name), ["Blackwall Tunnel"])
    }
}
