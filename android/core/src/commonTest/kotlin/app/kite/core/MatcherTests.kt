package app.kite.core

import kotlin.test.*

class MatcherTests {
    @Test fun tolls() {
        val json = """{"edges":[{"names":["A5"],"length":1.2,"toll":false},{"names":["M6 Toll"],"length":0.4,"toll":true},{"names":["M6 Toll"],"length":3.0,"toll":true},{"names":["A38"],"length":2.0},{"length":0.02,"toll":true},{"names":["A38"],"length":1.0}]}"""
        // The M6 Toll once; 20 m of toll mark at a junction is nothing.
        assertEquals(listOf("M6 Toll"), Valhalla.parseTolls(json.toByteArray()))
        assertEquals(listOf("A toll road"), Valhalla.parseTolls("""{"edges":[{"length":0.3,"toll":true}]}""".toByteArray()))
        assertEquals(emptyList(), Valhalla.parseTolls("""{"edges":[{"names":["A33"],"length":2.0}]}""".toByteArray()))
    }

    @Test fun here() {
        val json = """{"edges":[{"names":["High Street"],"speed_limit":48},{"names":["A33","Bassett Avenue"],"speed_limit":30}]}"""
        val h = Valhalla.parseHere(json.toByteArray(), LatLng(50.93, -1.40))!!
        assertEquals("Bassett Avenue", h.road)
        assertEquals("A33", h.ref)
        // "30" with no unit in Britain is 30 mph.
        assertEquals(30 * 1.609344, h.kmh!!, 0.01)
        assertEquals("A3(M)", Valhalla.parseHere("""{"edges":[{"names":["A3(M)"]}]}""".toByteArray(), LatLng(50.9, -1.0))!!.ref)
        assertNull(Valhalla.parseHere("""{"edges":[]}""".toByteArray(), LatLng(50.9, -1.0)))
    }

    @Test fun cruiseCameras() {
        val here = LatLng(50.9000, -1.4000)
        // Driving north at 30 mph (13.4 m/s): warn from 250 m.
        val ahead = RoadCamera(1, RoadCamera.Kind.SPEED, LatLng(50.9020, -1.4000))       // 222 m straight on
        val far = RoadCamera(2, RoadCamera.Kind.SPEED, LatLng(50.9060, -1.4000))         // 667 m: not yet
        val side = RoadCamera(3, RoadCamera.Kind.SPEED, LatLng(50.9015, -1.3985))        // a road off to the right
        val behind = RoadCamera(4, RoadCamera.Kind.SPEED, LatLng(50.8985, -1.4000))
        val otherWay = RoadCamera(5, RoadCamera.Kind.SPEED, LatLng(50.9010, -1.4000), directions = listOf(180.0))
        val plate = RoadCamera(6, RoadCamera.Kind.PLATE, LatLng(50.9005, -1.4000))
        val found = CruiseCameras.ahead(listOf(far, side, behind, otherWay, plate, ahead), here, 0.0, 13.4)
        assertEquals(1L, found?.first?.id)
        assertEquals(222.0, found!!.second, 3.0)
        // On a motorway (31 m/s) the far one is in reach too, but the nearer one still comes first.
        assertEquals(1L, CruiseCameras.ahead(listOf(far, ahead), here, 0.0, 31.0)?.first?.id)
        assertNull(CruiseCameras.ahead(listOf(far, side, behind, otherWay, plate), here, 0.0, 13.4))
    }

    @Test fun lineAvoiding() {
        // Two legs (through a stop): joined without the stop twice.
        val json = """{"trip":{"legs":[{"shape":"_aua`B~jmtA_pR~oR"},{"shape":"_rhb`B~{`uA_pR~oR"}]}}"""
        val line = Valhalla.parseLine(json.toByteArray())!!
        assertEquals(3, line.size)
        assertEquals(50.92, line[2].lat, 1e-6)
        assertEquals(-1.42, line[2].lng, 1e-6)
        assertNull(Valhalla.parseLine("""{"error":"No path could be found"}""".toByteArray()))
    }

    @Test fun destination() {
        val p = Geo.destination(LatLng(50.9, -1.4), 90.0, 100.0)
        assertEquals(100.0, LatLng(50.9, -1.4).distanceTo(p), 1.0)
        assertEquals(90.0, Geo.bearing(LatLng(50.9, -1.4), p), 0.5)
    }

    @Test fun googleReviews() {
        assertEquals("6699", GoogleReviews.ids("0x48738c3e0a3b4c4f:0x1a2b")?.second)
        assertEquals("48738c3e0a3b4c4f".toULong(16).toString(), GoogleReviews.ids("0x48738c3e0a3b4c4f:0x1a2b")?.first)
        assertNull(GoogleReviews.ids("ChIJ123"))
        val body = ")]}'\n" + """[null,null,[[[null,"Sam P"],"2 months ago",null,"Great coffee, friendly staff.",5],[[null,"Jo"],"a week ago",null,null,4],[[null,"Al"],"a year ago",null,"Slow.",2]]]"""
        val r = GoogleReviews.parse(body)!!
        assertEquals(2, r.size)
        assertEquals("Sam P", r[0].author)
        assertEquals(5, r[0].rating)
        assertEquals("2 months ago", r[0].whenText)
        assertEquals("Slow.", r[1].text)
        assertNull(GoogleReviews.parse("<html>"))
    }

    @Test fun detours() {
        val d = OSRM.parseDetours("""{"code":"Ok","durations":[[100,200,300,500],[0,10,260,999],[10,0,999,400]]}""".toByteArray(), 2)!!
        assertEquals(60.0, d[0]!!, 0.01)
        assertEquals(100.0, d[1]!!, 0.01)
        // A place the router can't reach.
        assertNull(OSRM.parseDetours("""{"code":"Ok","durations":[[null,200,300,500],[0,10,260,999],[10,0,999,400]]}""".toByteArray(), 2)!![0])
        assertNull(OSRM.parseDetours("""{"code":"NoTable"}""".toByteArray(), 2))
    }

    @Test fun arrivalHelp() {
        val here = LatLng(50.9000, -1.4000)
        val parks = ArrivalHelp.parseParking("""{"elements":[{"type":"way","id":1,"center":{"lat":50.9010,"lon":-1.4000},"tags":{"amenity":"parking","name":"West Quay Car Park","fee":"yes","capacity":"1200","parking":"multi-storey"}},{"type":"node","id":2,"lat":50.9001,"lon":-1.4000,"tags":{"amenity":"parking","access":"private"}},{"type":"node","id":3,"lat":50.9002,"lon":-1.4000,"tags":{"amenity":"parking","parking":"street_side"}},{"type":"node","id":4,"lat":50.9003,"lon":-1.4000,"tags":{"amenity":"parking","capacity":"6"}}]}""".toByteArray(), here)!!
        // The private one and the street-side spaces are left out; the big named car park beats six spaces nearer.
        assertEquals(listOf("way1", "node4"), parks.map { it.id })
        assertEquals(true, parks[0].fee)
        assertEquals(1200, parks[0].spaces)
        assertEquals("Multi-storey", parks[0].kind)
        val door = ArrivalHelp.parseEntrance("""{"elements":[{"type":"node","id":9,"lat":50.9005,"lon":-1.4000,"tags":{"entrance":"main"}},{"type":"node","id":10,"lat":50.9001,"lon":-1.4000,"tags":{"entrance":"main","access":"private"}}]}""".toByteArray(), here)!!
        assertEquals(50.9005, door.lat, 1e-6)
        assertNull(ArrivalHelp.parseEntrance("""{"elements":[{"type":"node","id":9,"lat":50.9001,"lon":-1.4000,"tags":{}}]}""".toByteArray(), here))
        assertTrue(ArrivalHelp.isBigPlace("Shopping mall"))
        assertTrue(!ArrivalHelp.isBigPlace("Cafe"))
    }

    @Test fun cleanAirZones() {
        fun names(to: LatLng, clean: Boolean = false) = CleanAirZones.on(listOf(LatLng(51.60, -0.60), to), clean).map { it.name }
        // Into central London from outside: the ULEZ, and nothing once the car meets the rules.
        assertEquals(listOf("London ULEZ"), names(LatLng(51.5074, -0.1278)))
        assertEquals(emptyList<String>(), names(LatLng(51.5074, -0.1278), clean = true))
        assertTrue(CleanAirZones.on(listOf(LatLng(51.5074, -0.1278), LatLng(51.5080, -0.1270)), false).single().zone)
        // Oxford's zone charges every car that isn't electric.
        assertEquals(listOf("Oxford Zero Emission Zone"), CleanAirZones.on(listOf(LatLng(51.70, -1.30), LatLng(51.7527, -1.2590)), true).map { it.name })
        assertEquals(listOf("Glasgow Low Emission Zone"), CleanAirZones.on(listOf(LatLng(55.90, -4.40), LatLng(55.8611, -4.2502)), false).map { it.name })
        assertEquals(listOf("Birmingham Clean Air Zone"), CleanAirZones.on(listOf(LatLng(52.55, -1.80), LatLng(52.4797, -1.9026)), false).map { it.name })
        // Southampton, and a drive past London that never goes in.
        assertEquals(emptyList<String>(), CleanAirZones.on(listOf(LatLng(50.90, -1.40), LatLng(50.93, -1.39)), false).map { it.name })
        assertEquals(emptyList<String>(), CleanAirZones.on(listOf(LatLng(51.60, -0.60), LatLng(51.70, -0.55)), false).map { it.name })
    }

    @Test fun roadAhead() {
        // A road going north then bending east, cut in two pieces, the second one drawn backwards.
        val a = listOf(LatLng(50.9000, -1.4000), LatLng(50.9010, -1.4000))
        val b = listOf(LatLng(50.9010, -1.3970), LatLng(50.9010, -1.4000))
        val p = RoadAhead.path(listOf(b, a), LatLng(50.9001, -1.40005), 0.0)!!
        // Starts 40 m on up the road, and follows the bend (ends east of the corner, still on the road).
        assertEquals(50.9001 + 40 / 111_200.0, p.first().lat, 0.00005)
        assertTrue(p.last().lng > -1.3999)
        assertEquals(50.9010, p.last().lat, 0.00002)
        assertEquals(160.0, Geo.length(p), 3.0)
        // Going the other way (south): only about 10 m of road left behind the start, no room for the name.
        assertNull(RoadAhead.path(listOf(b, a), LatLng(50.9001, -1.40005), 180.0))
        // Off the road.
        assertNull(RoadAhead.path(listOf(a), LatLng(50.9005, -1.3990), 0.0))
    }
}
