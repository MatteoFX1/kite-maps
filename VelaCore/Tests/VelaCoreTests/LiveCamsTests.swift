import XCTest
@testable import VelaCore

final class LiveCamsTests: XCTestCase {
    func testOpenHighways() throws {
        let json = #"""
        [{"active":true,"direction":"Clockwise","id":207,"image_url":"https://public.highwaystrafficcameras.co.uk/cctvpublicaccess/images/00207.jpg","latitude":51.3,"longitude":-0.4,"name":"M25 J10","road":"M25","source":"national_highways","vehicles":4},
         {"active":true,"direction":null,"id":9001,"image_url":"/api/cameras/9001/image","latitude":55.86,"longitude":-4.25,"name":"M8 J15 TOWNHEAD","road":"M8","source":"traffic_scotland"},
         {"active":false,"id":3,"image_url":"https://x/y.jpg","latitude":51,"longitude":0,"name":"Old","source":"tfl"},
         {"active":true,"id":4,"image_url":"","latitude":51,"longitude":0,"name":"No photo","source":"tfl"},
         {"active":true,"id":5,"image_url":"https://x/z.jpg","latitude":null,"longitude":null,"name":"Nowhere","source":"essex"},
         {"active":true,"id":207,"image_url":"https://dup.jpg","latitude":51.3,"longitude":-0.4,"name":"Dup","source":"national_highways"}]
        """#
        let cams = try XCTUnwrap(LiveCams.parseOpenHighways(Data(json.utf8)))
        XCTAssertEqual(cams.count, 2)
        XCTAssertEqual(cams[0].id, "oh:207")
        XCTAssertEqual(cams[0].source, .nationalHighways)
        XCTAssertEqual(cams[0].detail, "Clockwise")
        XCTAssertEqual(cams[1].imageURL.absoluteString, "https://openhighways.uk/api/cameras/9001/image")
        XCTAssertEqual(cams[1].name, "M8 J15 Townhead")
        XCTAssertEqual(cams[1].source.credit, "Traffic Scotland")
        XCTAssertNil(LiveCams.parseOpenHighways(Data("{}".utf8)))
    }

    func testTfL() throws {
        let json = #"""
        [{"id":"JamCams_00002.00865","commonName":"A406 Billet Upass E","placeType":"JamCam","lat":51.60067,"lon":-0.01594,
          "additionalProperties":[{"key":"available","value":"true"},{"key":"imageUrl","value":"https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00002.00865.jpg"},
          {"key":"videoUrl","value":"https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00002.00865.mp4"},{"key":"view","value":"West"}]},
         {"id":"JamCams_1","commonName":"Off","lat":51.5,"lon":0.1,"additionalProperties":[{"key":"available","value":"false"},{"key":"imageUrl","value":"https://a/b.jpg"}]}]
        """#
        let cams = try XCTUnwrap(LiveCams.parseTfL(Data(json.utf8)))
        XCTAssertEqual(cams.count, 1)
        XCTAssertEqual(cams[0].source, .tfl)
        XCTAssertEqual(cams[0].direction, "West")
        XCTAssertEqual(cams[0].moreURL?.pathExtension, "mp4")
    }

    func testWindy() throws {
        let json = #"""
        {"total":2,"webcams":[{"webcamId":1179853135,"title":"Keswick: Derwentwater","status":"active","lastUpdatedOn":"2026-11-03T13:40:12.000Z",
          "images":{"current":{"icon":"https://i/icon.jpg","thumbnail":"https://i/thumb.jpg","preview":"https://i/preview.jpg"}},
          "location":{"city":"Keswick","region":"England","country":"United Kingdom","latitude":54.6,"longitude":-3.13},
          "urls":{"detail":"https://www.windy.com/webcams/1179853135"}},
         {"webcamId":2,"title":"Broken","status":"inactive","images":{"current":{"preview":"https://i/p.jpg"}},"location":{"latitude":1,"longitude":1}}]}
        """#
        let cams = try XCTUnwrap(LiveCams.parseWindy(Data(json.utf8)))
        XCTAssertEqual(cams.count, 1)
        XCTAssertEqual(cams[0].id, "windy:1179853135")
        XCTAssertEqual(cams[0].imageURL.absoluteString, "https://i/preview.jpg")
        XCTAssertEqual(cams[0].detail, "Webcam · Keswick")
        XCTAssertNotNil(cams[0].updatedAt)
        XCTAssertEqual(LiveCams.windyOneURL(cams[0].id)?.path, "/webcams/api/v3/webcams/1179853135")
        XCTAssertTrue(LiveCams.windyNearbyURL(LatLng(54.6, -3.1), radiusKm: 900).absoluteString.contains(",250&"))
    }

    func testAlongRouteKeepsOrderAndSpacing() {
        // A straight route north, about 11 km.
        let line = [LatLng(51.0, -1.0), LatLng(51.1, -1.0)]
        func cam(_ id: String, _ lat: Double, _ lng: Double, _ s: LiveCam.Source = .nationalHighways) -> LiveCam {
            LiveCam(id: id, source: s, name: id, location: LatLng(lat, lng), imageURL: URL(string: "https://x/\(id).jpg")!)
        }
        let cams = [cam("far", 51.05, -0.9), cam("c", 51.08, -1.0), cam("a", 51.01, -1.0), cam("a2", 51.0105, -1.0005),
                    cam("b", 51.04, -1.0), cam("w", 51.06, -1.0, .windy)]
        let got = LiveCams.along(cams, line: line).map(\.cam.id)
        XCTAssertEqual(got, ["a", "b", "c"])
    }

    func testAlongRouteAddsWebcamsWhenAsked() {
        let line = [LatLng(51.0, -1.0), LatLng(51.1, -1.0)]
        func cam(_ id: String, _ lat: Double, _ lng: Double, _ s: LiveCam.Source = .nationalHighways) -> LiveCam {
            LiveCam(id: id, source: s, name: id, location: LatLng(lat, lng), imageURL: URL(string: "https://x/\(id).jpg")!)
        }
        // "town" is about 1.4 km off the road, "hill" about 7 km off.
        let cams = [cam("a", 51.01, -1.0), cam("town", 51.05, -0.98, .windy), cam("hill", 51.08, -0.9, .windy), cam("c", 51.09, -1.0)]
        XCTAssertEqual(LiveCams.along(cams, line: line).map(\.cam.id), ["a", "c"])
        XCTAssertEqual(LiveCams.along(cams, line: line, webcamOffset: LiveCams.webcamRouteOffset).map(\.cam.id), ["a", "town", "c"])
    }

    func testWindyRouteAsksCoverTheRoute() {
        // About 11 km: one ask in the middle.
        let short = LiveCams.windyRouteAsks([LatLng(51.0, -1.0), LatLng(51.1, -1.0)])
        XCTAssertEqual(short.count, 1)
        XCTAssertGreaterThanOrEqual(Double(short[0].radiusKm), 6)
        // About 555 km: capped at 8 asks, end to end, each circle wide enough for its stretch.
        let line = [LatLng(50.0, -1.0), LatLng(55.0, -1.0)]
        let long = LiveCams.windyRouteAsks(line)
        XCTAssertEqual(long.count, 8)
        XCTAssertEqual(long.first?.from, 0)
        XCTAssertEqual(long.last?.to ?? 0, Geo.length(line), accuracy: 1)
        for a in long {
            XCTAssertGreaterThanOrEqual(Double(a.radiusKm) * 1000, a.center.distance(to: Geo.point(along: line, meters: a.from)))
            XCTAssertLessThanOrEqual(a.radiusKm, 250)
        }
        XCTAssertTrue(LiveCams.windyRouteAsks([LatLng(51, -1)]).isEmpty)
    }

    func testWindySplitHalvesAFullAsk() {
        let line = [LatLng(50.0, -1.0), LatLng(51.0, -1.0)]
        let cum = Geo.cumulative(line)
        let ask = LiveCams.windyRouteAsks(line)[0]
        let halves = LiveCams.windySplit(ask, line: line, cumulative: cum)!
        XCTAssertEqual(halves.count, 2)
        XCTAssertEqual(halves[0].to, halves[1].from)
        XCTAssertLessThan(halves[0].radiusKm, ask.radiusKm)
        // Small enough already: not split again.
        XCTAssertNil(LiveCams.windySplit(LiveCams.windyRouteAsk(line, cumulative: cum, from: 0, to: 8000), line: line, cumulative: cum))
    }

    func testWindyPlanCarriesOnAfterAnInterruptedLook() {
        let line = [LatLng(50.0, -1.0), LatLng(50.36, -1.0)]
        let cum = Geo.cumulative(line)
        let parent = LiveCams.windyRouteAsks(line)[0]
        var asked: [(center: LatLng, km: Double, minReach: Double)] = []
        XCTAssertEqual(LiveCams.windyPlan(parent, asked: asked, line: line, cumulative: cum), .fetch)
        // Its answer was full; then the look was cut short before the halves were asked.
        asked.append((parent.center, Double(parent.radiusKm), Double(parent.radiusKm) * 0.8))
        // Shown again: the full answer sends its halves on instead of counting as done.
        guard case .closer(let halves) = LiveCams.windyPlan(parent, asked: asked, line: line, cumulative: cum) else {
            return XCTFail("a full answer should send its halves on")
        }
        XCTAssertEqual(halves.count, 2)
        XCTAssertEqual(LiveCams.windyPlan(halves[0], asked: asked, line: line, cumulative: cum), .fetch)
        // Once a half has its own answer with room to spare, it's done.
        asked.append((halves[0].center, Double(halves[0].radiusKm), 0))
        XCTAssertEqual(LiveCams.windyPlan(halves[0], asked: asked, line: line, cumulative: cum), .skip)
        XCTAssertEqual(LiveCams.windyPlan(halves[1], asked: asked, line: line, cumulative: cum), .fetch)
    }

    func testAlongRouteFindsWebcamsFarNorth() {
        // Shetland, 60.5°N: a webcam 2.4 km east of a road running north.
        let line = [LatLng(60.4, -1.2), LatLng(60.6, -1.2)]
        let east = -1.2 + 2400 / (111_320 * cos(60.5 * Double.pi / 180))
        let cams = [LiveCam(id: "w", source: .windy, name: "w", location: LatLng(60.5, east), imageURL: URL(string: "https://x/w.jpg")!)]
        XCTAssertEqual(LiveCams.along(cams, line: line, webcamOffset: LiveCams.webcamRouteOffset).map(\.cam.id), ["w"])
    }

    func testTidyKeepsRoadNumbers() {
        XCTAssertEqual(LiveCams.tidy("A406 BILLET UPASS E"), "A406 Billet Upass E")
        XCTAssertEqual(LiveCams.tidy("Romford Rd / Tennyson Rd"), "Romford Rd / Tennyson Rd")
    }
}
