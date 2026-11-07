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

    @Test fun alongRouteAddsWebcamsWhenAsked() {
        val line = listOf(LatLng(51.0, -1.0), LatLng(51.1, -1.0))
        fun cam(id: String, lat: Double, lng: Double, s: LiveCam.Source = LiveCam.Source.NATIONAL_HIGHWAYS) =
            LiveCam(id, s, id, location = LatLng(lat, lng), imageUrl = "https://x/$id.jpg")
        // "town" is about 1.4 km off the road, "hill" about 7 km off.
        val cams = listOf(cam("a", 51.01, -1.0), cam("town", 51.05, -0.98, LiveCam.Source.WINDY),
            cam("hill", 51.08, -0.9, LiveCam.Source.WINDY), cam("c", 51.09, -1.0))
        assertEquals(listOf("a", "c"), LiveCams.along(cams, line).map { it.cam.id })
        assertEquals(listOf("a", "town", "c"), LiveCams.along(cams, line, webcamOffset = LiveCams.WEBCAM_ROUTE_OFFSET).map { it.cam.id })
    }

    @Test fun windyRouteAsksCoverTheRoute() {
        // About 11 km: one ask in the middle.
        val short = LiveCams.windyRouteAsks(listOf(LatLng(51.0, -1.0), LatLng(51.1, -1.0)))
        assertEquals(1, short.size)
        assertTrue(short[0].radiusKm >= 6)
        // About 555 km: capped at 8 asks, end to end, each circle wide enough for its stretch.
        val line = listOf(LatLng(50.0, -1.0), LatLng(55.0, -1.0))
        val long = LiveCams.windyRouteAsks(line)
        assertEquals(8, long.size)
        assertEquals(0.0, long.first().from, 0.001)
        assertEquals(Geo.length(line), long.last().to, 1.0)
        for (a in long) {
            assertTrue(a.radiusKm * 1000.0 >= a.center.distanceTo(Geo.point(line, a.from)))
            assertTrue(a.radiusKm <= 250)
        }
        assertTrue(LiveCams.windyRouteAsks(listOf(LatLng(51.0, -1.0))).isEmpty())
    }

    @Test fun windySplitHalvesAFullAsk() {
        val line = listOf(LatLng(50.0, -1.0), LatLng(51.0, -1.0))
        val cum = Geo.cumulative(line)
        val ask = LiveCams.windyRouteAsks(line)[0]
        val halves = LiveCams.windySplit(ask, line, cum)!!
        assertEquals(2, halves.size)
        assertEquals(halves[0].to, halves[1].from, 0.001)
        assertTrue(halves[0].radiusKm < ask.radiusKm)
        // Small enough already: not split again.
        assertNull(LiveCams.windySplit(LiveCams.windyRouteAsk(line, cum, 0.0, 8000.0), line, cum))
    }

    @Test fun windyPlanCarriesOnAfterAnInterruptedLook() {
        val line = listOf(LatLng(50.0, -1.0), LatLng(50.36, -1.0))
        val cum = Geo.cumulative(line)
        val parent = LiveCams.windyRouteAsks(line)[0]
        val asked = ArrayList<LiveCams.WindyAsked>()
        assertEquals(LiveCams.WindyPlan.Fetch, LiveCams.windyPlan(parent, asked, line, cum))
        // Its answer was full; then the look was cut short before the halves were asked.
        asked += LiveCams.WindyAsked(parent.center, parent.radiusKm.toDouble(), parent.radiusKm * 0.8)
        // Shown again: the full answer sends its halves on instead of counting as done.
        val plan = LiveCams.windyPlan(parent, asked, line, cum)
        assertTrue(plan is LiveCams.WindyPlan.Closer)
        val halves = (plan as LiveCams.WindyPlan.Closer).asks
        assertEquals(2, halves.size)
        assertEquals(LiveCams.WindyPlan.Fetch, LiveCams.windyPlan(halves[0], asked, line, cum))
        // Once a half has its own answer with room to spare, it's done.
        asked += LiveCams.WindyAsked(halves[0].center, halves[0].radiusKm.toDouble(), 0.0)
        assertEquals(LiveCams.WindyPlan.Skip, LiveCams.windyPlan(halves[0], asked, line, cum))
        assertEquals(LiveCams.WindyPlan.Fetch, LiveCams.windyPlan(halves[1], asked, line, cum))
    }

    @Test fun windyPlanAsksASmallStretchAWiderFullAnswerCovered() {
        // An 8 km route inside a full 15 km map answer: too small to split, so it's asked once itself.
        val line = listOf(LatLng(51.0, -1.0), LatLng(51.072, -1.0))
        val cum = Geo.cumulative(line)
        val ask = LiveCams.windyRouteAsks(line)[0]
        val asked = arrayListOf(LiveCams.WindyAsked(ask.center, 15.0, 8.0))
        assertNull(LiveCams.windySplit(ask, line, cum))
        assertEquals(LiveCams.WindyPlan.Fetch, LiveCams.windyPlan(ask, asked, line, cum))
        // Then not again, even though its own answer was full too.
        asked += LiveCams.WindyAsked(ask.center, ask.radiusKm.toDouble(), ask.radiusKm * 0.8)
        assertEquals(LiveCams.WindyPlan.Skip, LiveCams.windyPlan(ask, asked, line, cum))
        // A reroute 50 m over makes nearly the same circle: not asked again.
        val shifted = line.map { LatLng(it.lat, it.lng + 0.0007) }
        val again = LiveCams.windyRouteAsks(shifted)[0]
        assertTrue(again.center.distanceTo(ask.center) > 30)
        assertEquals(LiveCams.WindyPlan.Skip, LiveCams.windyPlan(again, asked, shifted, Geo.cumulative(shifted)))
        // Nor when the earlier answer had room to spare.
        assertEquals(LiveCams.WindyPlan.Skip, LiveCams.windyPlan(again, listOf(LiveCams.WindyAsked(ask.center, ask.radiusKm.toDouble(), 0.0)),
            shifted, Geo.cumulative(shifted)))
    }

    @Test fun alongRouteFindsWebcamsFarNorth() {
        // Shetland, 60.5°N: a webcam 2.4 km east of a road running north.
        val line = listOf(LatLng(60.4, -1.2), LatLng(60.6, -1.2))
        val east = -1.2 + 2400 / (111_320 * kotlin.math.cos(60.5 * Math.PI / 180))
        val cams = listOf(LiveCam("w", LiveCam.Source.WINDY, "w", location = LatLng(60.5, east), imageUrl = "https://x/w.jpg"))
        assertEquals(listOf("w"), LiveCams.along(cams, line, webcamOffset = LiveCams.WEBCAM_ROUTE_OFFSET).map { it.cam.id })
    }

    @Test fun tidyKeepsRoadNumbers() {
        assertEquals("A406 Billet Upass E", LiveCams.tidy("A406 BILLET UPASS E"))
        assertEquals("Romford Rd / Tennyson Rd", LiveCams.tidy("Romford Rd / Tennyson Rd"))
    }
}
