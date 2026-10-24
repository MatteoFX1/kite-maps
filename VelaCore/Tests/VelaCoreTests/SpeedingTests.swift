import XCTest
@testable import VelaCore

final class SpeedingTests: XCTestCase {
    func testSpeaksOnceAfterHoldAndAgainAfterRearm() {
        var a = SpeedingAlerts()
        XCTAssertFalse(a.update(speedKmh: 70, limitKmh: 50, now: 0), "not before the hold")
        XCTAssertFalse(a.update(speedKmh: 70, limitKmh: 50, now: 2))
        XCTAssertTrue(a.update(speedKmh: 70, limitKmh: 50, now: 4.5))
        XCTAssertFalse(a.update(speedKmh: 70, limitKmh: 50, now: 30), "doesn't nag")
        XCTAssertFalse(a.update(speedKmh: 52, limitKmh: 50, now: 31), "within tolerance")
        XCTAssertFalse(a.update(speedKmh: 45, limitKmh: 50, now: 40))
        XCTAssertFalse(a.update(speedKmh: 70, limitKmh: 50, now: 50))
        XCTAssertTrue(a.update(speedKmh: 70, limitKmh: 50, now: 55), "rearmed after slowing down")
        XCTAssertFalse(a.update(speedKmh: 70, limitKmh: nil, now: 200), "no limit, no alert")
    }
}
