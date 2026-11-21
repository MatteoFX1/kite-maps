package app.kite.core

import kotlin.test.*

class DiagnosticZipTests {
    @Test fun storedZip() {
        val zip = DiagnosticZip.archive(listOf(DiagnosticZip.Entry("a.txt", "123456789".toByteArray())))
        fun bytes(vararg v: Int) = v.map { it.toByte() }
        assertEquals(bytes(0x50, 0x4b, 0x03, 0x04), zip.take(4))
        assertEquals(bytes(0x26, 0x39, 0xf4, 0xcb), zip.slice(14 until 18))
        assertTrue(zip.toList().windowed(4).any { it == bytes(0x50, 0x4b, 0x01, 0x02) })
        assertEquals(bytes(0x50, 0x4b, 0x05, 0x06), zip.takeLast(22).take(4))
    }
    @Test fun rejectsPaths() {
        assertFails { DiagnosticZip.archive(listOf(DiagnosticZip.Entry("../x", ByteArray(0)))) }
    }
    @Test fun systemUnzipReadsIt() {
        val f = java.io.File.createTempFile("kite", ".zip")
        try {
            f.writeBytes(DiagnosticZip.archive(listOf(DiagnosticZip.Entry("diagnostics.txt", "test".toByteArray()),
                DiagnosticZip.Entry("heatmap.webp", byteArrayOf(0, 1, -1)))))
            java.util.zip.ZipFile(f).use { z ->
                assertEquals(2, z.size())
                assertEquals("test", z.getInputStream(z.getEntry("diagnostics.txt")).readBytes().decodeToString())
            }
        } finally { f.delete() }
    }
}

class MapLinksTests {
    private fun target(s: String) = MapLinks.link(s)?.let { MapLinks.parse(it) }
    private fun spot(t: MapLinks.Target?, lat: Double, lng: Double, name: String? = null) {
        val s = t as? MapLinks.Target.Spot ?: fail("not a spot: $t")
        assertEquals(lat, s.at.lat, 0.001); assertEquals(lng, s.at.lng, 0.001)
        if (name != null) assertEquals(name, s.name)
    }

    @Test fun googlePlace() {
        spot(target("https://www.google.com/maps/place/Big+Ben/@51.5007292,-0.1272003,17z/data=!3m1!4b1!4m6!3m5!1s0x487604c38c8cd1d9:0xb78f2474b9a45aa9!8m2!3d51.5007292!4d-0.1246254!16zL20vMGNrcA"),
            51.5007, -0.1246, "Big Ben")
        spot(target("https://www.google.co.uk/maps/place/Cardiff+Castle/@51.48,-3.18,15z"), 51.48, -3.18, "Cardiff Castle")
    }
    @Test fun googleQueryAndCoords() {
        spot(target("https://maps.google.com/?q=55.9486,-3.1999"), 55.9486, -3.1999)
        spot(target("https://www.google.com/maps/search/?api=1&query=51.5,-0.12"), 51.5, -0.12)
        assertEquals(MapLinks.Target.Search("Tesco Extra", null), target("https://www.google.com/maps/search/?api=1&query=Tesco+Extra"))
        spot(target("https://www.google.com/maps/@51.4545,-2.5879,14z"), 51.4545, -2.5879)
    }
    @Test fun shareTextWithShortLink() {
        assertEquals("maps.app.goo.gl", MapLinks.link("Big Ben\nhttps://maps.app.goo.gl/abcDEF123")?.host)
        assertTrue(MapLinks.looksLikeLink("maps.app.goo.gl/abc"))
        assertFalse(MapLinks.looksLikeLink("pizza near me"))
    }
    @Test fun consentWrapper() {
        val inner = "https://www.google.com/maps/place/X/@51.5,-0.1,17z/data=!3d51.501!4d-0.142"
        val enc = inner.map { if (it.isLetterOrDigit()) "$it" else "%%%02X".format(it.code) }.joinToString("")
        spot(MapLinks.parse(Link("https://consent.google.com/m?continue=$enc")), 51.501, -0.142)
    }
    @Test fun waze() {
        spot(target("https://waze.com/ul?ll=51.50072%2C-0.12462&navigate=yes"), 51.5007, -0.1246)
        spot(target("https://www.waze.com/live-map/directions?to=ll.53.4808%2C-2.2426"), 53.4808, -2.2426)
        spot(target("https://waze.com/ul/hgcpuvpk4"), 51.5002, -0.1262)
    }
    @Test fun appleGeoPlain() {
        spot(target("https://maps.apple.com/?ll=51.5,-0.12&q=Pin"), 51.5, -0.12, "Pin")
        spot(MapLinks.parse(Link("geo:51.5,-0.12?q=51.5,-0.12(Home)")), 51.5, -0.12, "Home")
        assertEquals(51.50072, MapLinks.coordinates("51.50072, -0.12462")!!.lat, 1e-6)
        assertNull(MapLinks.coordinates("10 20"))
    }
    @Test fun geohash() {
        val p = MapLinks.geohash("gcpvj0d6")!!
        assertEquals(51.5071, p.lat, 0.001); assertEquals(-0.1286, p.lng, 0.001)
    }
}

class PlaceMatchingTests {
    @Test fun nearbyCopiesMatch() {
        val osm = Place("osm", "Perth Airport", LatLng(-31.93908, 115.968171), "osm")
        val google = Place("google", "perth airport", LatLng(-31.93920, 115.9683), "google")
        assertTrue(osm.matchesNearby(google))
    }
    @Test fun differentPlacesStayDistinct() {
        val first = Place("a", "Perth Airport", LatLng(-31.939, 115.968), "osm")
        assertFalse(first.matchesNearby(Place("b", "Airport Cafe", LatLng(-31.939, 115.968), "google")))
        assertFalse(first.matchesNearby(Place("c", "Perth Airport", LatLng(-31.95, 115.97), "google")))
    }
    @Test fun sameIdAfterRename() {
        assertTrue(Place("a", "Old", LatLng(0.0, 0.0), "osm").matchesNearby(Place("a", "New", LatLng(1.0, 1.0), "osm")))
    }
}

class SpeedingTests {
    @Test fun speaksOnceAfterHold() {
        val a = SpeedingAlerts()
        assertFalse(a.update(70.0, 50.0, 0.0))
        assertFalse(a.update(70.0, 50.0, 2.0))
        assertTrue(a.update(70.0, 50.0, 4.5))
        assertFalse(a.update(70.0, 50.0, 30.0))
        assertFalse(a.update(52.0, 50.0, 31.0))
        assertFalse(a.update(45.0, 50.0, 40.0))
        assertFalse(a.update(70.0, 50.0, 50.0))
        assertTrue(a.update(70.0, 50.0, 55.0))
        assertFalse(a.update(70.0, null, 200.0))
    }
}

class SuggestTests {
    @Test fun parse() {
        val place = listOf(listOf("Pier 50 Sushi"), listOf("Pier 50 Sushi", listOf(listOf(0L, 4L))), listOf("226 F St, Davis, CA"),
            null, null, null, null, null, null, null, null, listOf(null, null, 38.5446, -121.7409))
        val query = listOf(listOf("pizza near me"), listOf("pizza near me"))
        val inner = listOf(listOf("pi", listOf(listOf(null, place), listOf(query))))
        val env = Json.write(mapOf("c" to 0L, "d" to ")]}'\n" + Json.write(inner)))
        val body = env + "/*\"\"*/{\"c\":0,\"d\":\"\",\"e\":\"x\"}"
        val r = SuggestParser.parse(body, LatLng(38.5449, -121.7405))
        assertEquals(1, r.places.size)
        assertEquals("Pier 50 Sushi", r.places[0].name)
        assertEquals("226 F St, Davis, CA", r.places[0].address)
        assertEquals(listOf("pizza near me"), r.queries)
    }
    @Test fun closeNameMatchComesFirst() {
        fun place(n: String) = Place(n, n, LatLng(50.88, -1.25), "google")
        val google = listOf("The Body Shop", "Whiteley Village", "Whiteley Shopping Centre", "Boots").map(::place)
        assertEquals(listOf("Whiteley Shopping Centre", "Whiteley Village", "The Body Shop", "Boots"),
            SuggestParser.closeMatchesFirst(google, "Whiteley Shopping Centre").map { it.name })
        assertEquals("Whiteley Shopping Centre", SuggestParser.closeMatchesFirst(google, "whiteley shop").first().name)
        assertEquals(google.map { it.name }, SuggestParser.closeMatchesFirst(google, "pharmacy").map { it.name })
        val tescos = listOf("Tesco Express", "Tesco Extra", "Sainsbury's").map(::place)
        assertEquals(listOf("Tesco Express", "Tesco Extra", "Sainsbury's"), SuggestParser.closeMatchesFirst(tescos, "tesco").map { it.name })
        assertEquals("Sainsbury's", SuggestParser.closeMatchesFirst(tescos, "sainsburys").first().name)
    }
}

class SurveillanceCameraTests {
    @Test fun genericVsPlateReaders() {
        val json = """{"elements":[{"id":1,"lat":-31.78,"lon":115.97,"tags":{"man_made":"surveillance","operator":"Public Transport Authority","surveillance":"public","camera:direction":"SE"}},{"id":2,"lat":-31.79,"lon":115.98,"tags":{"surveillance:type":"ALPR","manufacturer":"Example"}},{"id":2,"lat":-31.79,"lon":115.98,"tags":{"surveillance:type":"ALPR"}},{"id":3,"lat":-31.8,"lon":115.9,"tags":{"highway":"traffic_signals"}}]}"""
        val items = SurveillanceCameras.parse(json.toByteArray())!!
        assertEquals(2, items.size)
        assertFalse(items[0].isALPR)
        assertEquals("SE", items[0].direction)
        assertTrue(items[1].isALPR)
        assertEquals("Example", items[1].manufacturer)
    }
    @Test fun malformed() { assertNull(SurveillanceCameras.parse("{}".toByteArray())) }
}

class SearchParserTests {
    private fun entry(name: String, lat: Double, lng: Double, status: String?): List<Any?> {
        val node = MutableList<Any?>(210) { null }
        node[11] = name
        node[9] = listOf(null, null, lat, lng)
        node[39] = "$name, 1 Main St, Springfield"
        node[13] = listOf("Pizza restaurant")
        val four = MutableList<Any?>(9) { null }
        four[7] = 4.5; four[8] = 120L
        node[4] = four
        node[10] = "0x1:0x${name.length}"
        if (status != null) {
            val meta = MutableList<Any?>(9) { null }
            meta[4] = listOf(status)
            val day = listOf("Monday", 1L, listOf(2026L, 10L, 19L), listOf(listOf("11 AM–10 PM")))
            node[203] = listOf(listOf(day), meta)
        }
        return listOf(null, node)
    }
    @Test fun parsesListReply() {
        val root = MutableList<Any?>(65) { null }
        root[64] = listOf(entry("Far Pizza", 40.80, -73.98, "Closed · Opens 11 AM"), entry("Near Pizza", 40.7485, -73.9857, "Open · Closes 10 PM"))
        val places = SearchParser.parse(root, LatLng(40.7484, -73.9857))
        assertEquals(listOf("Far Pizza", "Near Pizza"), places.map { it.name })
        val near = places[1]
        assertEquals("1 Main St, Springfield", near.address)
        assertEquals("Pizza restaurant", near.category)
        assertEquals(4.5, near.rating)
        assertEquals(120, near.reviewCount)
        assertEquals(true, near.openNow)
        assertEquals(listOf("Monday: 11 AM–10 PM"), near.hours)
        assertEquals(false, places[0].openNow)
    }
    @Test fun opensIsClosed() {
        assertEquals(false, SearchParser.openNow("Opens 5 AM"))
        assertEquals(true, SearchParser.openNow("Open 24 hours"))
        assertEquals(true, SearchParser.openNow("Closes soon · 9 PM"))
        assertNull(SearchParser.openNow(null))
    }
    @Test fun stripNamePrefix() {
        assertEquals("1451 W Covell Blvd", SearchParser.stripNamePrefix("Safeway, 1451 W Covell Blvd", "Safeway"))
        assertEquals("Safeway Plaza, 1 Rd", SearchParser.stripNamePrefix("Safeway Plaza, 1 Rd", "Safeway"))
    }
    @Test fun xssiGuard() { assertEquals("a", gstr(at(GoogleJSON.parse(")]}'\n[1,[\"a\"]]"), 1, 0))) }
    @Test fun pbTemplate() {
        val pb = SearchPb.build("pizza!", LatLng(1.5, 2.5), 3000.0)
        assertTrue(pb.startsWith("!1spizza!4m8!1m3!1d3000.0!2d2.5!3d1.5!3m2"), pb)
        assertFalse(pb.contains(" ")); assertFalse(pb.contains("\n"))
        assertTrue(pb.endsWith("!69i782!77b1"))
    }
}
