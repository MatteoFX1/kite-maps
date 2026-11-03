package app.kite.core

import kotlin.test.*

class LiveCamsTests {
    @Test fun openHighways() {
        val json = """
        [{"active":true,"direction":"Clockwise","id":207,"image_url":"https://public.highwaystrafficcameras.co.uk/cctvpublicaccess/images/00207.jpg","latitude":51.3,"longitude":-0.4,"name":"M25 J10","road":"M25","source":"national_highways","vehicles":4},
         {"active":true,"direction":null,"id":9001,"image_url":"/api/cameras/9001/image","latitude":55.86,"longitude":-4.25,"name":"M8 J15 TOWNHEAD","road":"M8","source":"traffic_scotland"},
         {"active":false,"id":3,"image_url":"https://x/y.jpg","latitude":51,"longitude":0,"name":"Old","source":"tfl"},
         {"active":true,"id":4,"image_url":"","latitude":51,"longitude":0,"name":"No photo","source":"tfl"},
         {"active":true,"id":5,"image_url":"https://x/z.jpg","latitude":null,"longitude":null,"name":"Nowhere","source":"essex"},
         {"active":true,"id":207,"image_url":"https://dup.jpg","latitude":51.3,"longitude":-0.4,"name":"Dup","source":"national_highways"}]
        """.trimIndent()
        val cams = assertNotNull(LiveCams.parseOpenHighways(json.toByteArray()))
        assertEquals(2, cams.size)
        assertEquals("oh:207", cams[0].id)
        assertEquals("Clockwise", cams[0].detail)
        assertEquals("https://openhighways.uk/api/cameras/9001/image", cams[1].imageUrl)
        assertEquals("M8 J15 Townhead", cams[1].name)
        assertEquals("Traffic Scotland", cams[1].source.credit)
        assertNull(LiveCams.parseOpenHighways("{}".toByteArray()))
        // Saved and read back the same.
        assertEquals(cams[1], LiveCam.fromMap(Json.parse(Json.write(cams[1].toMap())).asMap()!!))
    }

    @Test fun tfl() {
        val json = """
        [{"id":"JamCams_00002.00865","commonName":"A406 Billet Upass E","placeType":"JamCam","lat":51.60067,"lon":-0.01594,
          "additionalProperties":[{"key":"available","value":"true"},{"key":"imageUrl","value":"https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00002.00865.jpg"},
          {"key":"videoUrl","value":"https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00002.00865.mp4"},{"key":"view","value":"West"}]},
         {"id":"JamCams_1","commonName":"Off","lat":51.5,"lon":0.1,"additionalProperties":[{"key":"available","value":"false"},{"key":"imageUrl","value":"https://a/b.jpg"}]}]
        """.trimIndent()
        val cams = assertNotNull(LiveCams.parseTfL(json.toByteArray()))
        assertEquals(1, cams.size)
        assertEquals("West", cams[0].direction)
        assertTrue(cams[0].moreUrl!!.endsWith(".mp4"))
    }

    @Test fun windy() {
        val json = """
        {"total":2,"webcams":[{"webcamId":1179853135,"title":"Keswick: Derwentwater","status":"active","lastUpdatedOn":"2026-11-03T13:40:12.000Z",
          "images":{"current":{"icon":"https://i/icon.jpg","thumbnail":"https://i/thumb.jpg","preview":"https://i/preview.jpg"}},
          "location":{"city":"Keswick","region":"England","country":"United Kingdom","latitude":54.6,"longitude":-3.13},
          "urls":{"detail":"https://www.windy.com/webcams/1179853135"}},
         {"webcamId":2,"title":"Broken","status":"inactive","images":{"current":{"preview":"https://i/p.jpg"}},"location":{"latitude":1,"longitude":1}}]}
        """.trimIndent()
        val cams = assertNotNull(LiveCams.parseWindy(json.toByteArray()))
        assertEquals(1, cams.size)
        assertEquals("windy:1179853135", cams[0].id)
        assertEquals("https://i/preview.jpg", cams[0].imageUrl)
        assertEquals("Webcam · Keswick", cams[0].detail)
        assertNotNull(cams[0].updatedAtMillis)
        assertTrue(LiveCams.windyOneUrl(cams[0].id)!!.contains("/webcams/api/v3/webcams/1179853135?"))
        assertTrue(LiveCams.windyNearbyUrl(LatLng(54.6, -3.1), 900).contains(",250&"))
    }

    @Test fun alongRouteKeepsOrderAndSpacing() {
        val line = listOf(LatLng(51.0, -1.0), LatLng(51.1, -1.0))
        fun cam(id: String, lat: Double, lng: Double, s: LiveCam.Source = LiveCam.Source.NATIONAL_HIGHWAYS) =
            LiveCam(id, s, id, location = LatLng(lat, lng), imageUrl = "https://x/$id.jpg")
        val cams = listOf(cam("far", 51.05, -0.9), cam("c", 51.08, -1.0), cam("a", 51.01, -1.0), cam("a2", 51.0105, -1.0005),
            cam("b", 51.04, -1.0), cam("w", 51.06, -1.0, LiveCam.Source.WINDY))
        assertEquals(listOf("a", "b", "c"), LiveCams.along(cams, line).map { it.cam.id })
    }

    @Test fun tidyKeepsRoadNumbers() {
        assertEquals("A406 Billet Upass E", LiveCams.tidy("A406 BILLET UPASS E"))
        assertEquals("Romford Rd / Tennyson Rd", LiveCams.tidy("Romford Rd / Tennyson Rd"))
    }
}
