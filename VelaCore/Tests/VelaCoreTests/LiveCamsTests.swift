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

    func testTidyKeepsRoadNumbers() {
        XCTAssertEqual(LiveCams.tidy("A406 BILLET UPASS E"), "A406 Billet Upass E")
        XCTAssertEqual(LiveCams.tidy("Romford Rd / Tennyson Rd"), "Romford Rd / Tennyson Rd")
    }
}
