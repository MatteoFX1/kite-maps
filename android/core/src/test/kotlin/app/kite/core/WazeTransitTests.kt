package app.kite.core

import kotlin.test.*

class TransitTests {
    @Test fun parsePlan() {
        val json = """
        {"itineraries":[
          {"duration":1800,"startTime":"2026-10-23T17:00:00Z","endTime":"2026-10-23T17:30:00Z","transfers":0,"legs":[
            {"mode":"WALK","duration":300,"distance":350,"startTime":"2026-10-23T17:00:00Z","endTime":"2026-10-23T17:05:00Z",
             "from":{"name":"START","lat":38.5,"lon":-120.2},"to":{"name":"Market St","lat":38.51,"lon":-120.21}},
            {"mode":"SUBWAY","duration":1200,"routeShortName":"N","routeColor":"FCCC0A","routeTextColor":"000000",
             "headsign":"Ocean Beach","realTime":true,"startTime":"2026-10-23T17:07:00Z","scheduledStartTime":"2026-10-23T17:05:00Z",
             "endTime":"2026-10-23T17:27:00Z","intermediateStops":[{},{},{}],
             "from":{"name":"Market St","lat":38.51,"lon":-120.21},"to":{"name":"Judah St","lat":40.7,"lon":-120.95},
             "legGeometry":{"points":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","precision":5}},
            {"mode":"WALK","duration":180,"from":{"name":"Judah St","lat":40.7,"lon":-120.95},"to":{"name":"END","lat":40.71,"lon":-120.96}}
          ]},
          {"duration":900,"legs":[{"mode":"WALK","duration":900}]}
        ]}
        """
        val trips = Transitous.parse(json.toByteArray())
        assertEquals(1, trips.size, "a walk-only answer is not a transit trip")
        val t = trips[0]
        assertEquals(1800.0, t.durationSeconds)
        assertEquals(3, t.legs.size)
        val ride = t.legs[1]
        assertFalse(ride.isWalk)
        assertEquals("N", ride.line)
        assertEquals(0xFCCC0AL, ride.color)
        assertEquals(4, ride.stops)
        assertEquals(2, ride.delayMinutes)
        assertEquals(TransitLeg.Vehicle.METRO, ride.vehicle)
        assertEquals(3, ride.path.size)
        assertEquals(38.5, ride.path[0].lat, 1e-5)
        assertEquals(-126.453, ride.path[2].lng, 1e-5)
        assertTrue(t.legs[0].isWalk)
        assertEquals(2, t.legs[0].path.size)
        assertEquals(480.0, t.walkSeconds)
    }

    @Test fun stopTimes() {
        val json = """
        {"stopTimes":[
          {"place":{"departure":"2026-10-23T17:10:00Z","scheduledDeparture":"2026-10-23T17:08:00Z"},"mode":"BUS","realTime":true,
           "headsign":"Downtown","routeShortName":"42","routeColor":"#0055AA"},
          {"place":{"departure":"2026-10-23T17:12:00Z","cancelled":true},"mode":"BUS","routeShortName":"43"},
          {"place":{"departure":"2026-10-23T17:20:00Z"},"mode":"TRAM","displayName":"T"},
          {"place":{"departure":"2026-10-23T17:25:00Z"},"mode":"RAIL","routeShortName":"CC","headsign":"Davis","tripTo":{"name":"San Jose"}},
          {"place":{"departure":"2026-10-23T17:30:00Z"},"mode":"RAIL","routeShortName":"CC","headsign":"Davis","tripTo":{"name":"Davis"}}
        ]}
        """
        val d = Transitous.parseStopTimes(json.toByteArray(), "Davis")
        assertEquals(3, d.size)
        assertEquals("San Jose", d[2].headsign)
        assertEquals("42", d[0].line)
        assertEquals(0x0055AAL, d[0].color)
        assertEquals(2, d[0].delayMinutes)
        assertEquals("T", d[1].line)
        assertNull(d[1].delayMinutes)
        assertEquals(TransitLeg.Vehicle.TRAM, d[1].vehicle)
    }

    @Test fun lines() {
        val json = """
        {"routes":[{"mode":"BUS","transitRoutes":[{"color":"FF0000"}]},{"mode":"SUBWAY","transitRoutes":[{"color":"FCCC0A","shortName":"N"}]}],
         "polylines":[{"polyline":{"points":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","precision":5},"routeIndexes":[0]},
                      {"polyline":{"points":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","precision":5},"routeIndexes":[0,1]}]}
        """
        val lines = Transitous.parseLines(json.toByteArray())
        assertEquals(1, lines.size)
        assertEquals(0xFCCC0AL, lines[0].color)
        assertTrue(lines[0].metro)
        assertEquals(listOf("N"), lines[0].names)
    }
}

class WazeTests {
    private val P = WazeAlert.Kind.POLICE

    @Test fun parse() {
        val json = """{"alerts":[{"type":"POLICE","subtype":"POLICE_HIDING","uuid":"a1","location":{"x":-0.12,"y":51.5},"street":"A40","pubMillis":1700000000000,"nThumbsUp":3,"reliability":8,"magvar":90},{"type":"WEATHERHAZARD","subtype":"HAZARD_ON_ROAD_POT_HOLE","uuid":"a2","location":{"x":-0.1,"y":51.4}},{"type":"WEATHERHAZARD","subtype":"HAZARD_WEATHER_FOG","uuid":"a3","location":{"x":-0.1,"y":51.4}},{"type":"HAZARD","subtype":"HAZARD_ON_ROAD_CONSTRUCTION","uuid":"a4","location":{"x":-0.1,"y":51.4}},{"type":"CHIT_CHAT","uuid":"a5","location":{"x":0,"y":0}},{"type":"ROAD_CLOSED","uuid":"a6"}],"jams":[]}"""
        val list = Waze.parse(json.toByteArray())!!
        assertEquals(listOf(P, WazeAlert.Kind.HAZARD, WazeAlert.Kind.WEATHER, WazeAlert.Kind.ROADWORKS), list.map { it.kind })
        assertEquals("Hidden police", list[0].title)
        assertEquals(3, list[0].thumbsUp)
        assertEquals(90.0, list[0].heading)
        assertEquals(LatLng(51.5, -0.12), list[0].location)
        assertEquals(1_700_000_000_000L, list[0].reportedAtMillis)
        assertEquals("Pothole reported ahead.", list[1].spoken)
        assertNull(Waze.parse("nope".toByteArray()))
    }

    @Test fun liveAlertParse() {
        val pos = ProtoWriter(); pos.int32(101, -1_339_275); pos.int32(102, 50_905_133)
        val info = ProtoWriter(); info.varint(1, 2); info.varint(2, 203); info.message(3, pos.data); info.varint(6, 270)
        val address = ProtoWriter(); address.string(2, "A3024 Bursledon Rd")
        val report = ProtoWriter(); report.varint(4, 1_793_107_490); report.message(8, address.data); report.varint(9, 12)
        val alert = ProtoWriter(); alert.varint(1, 42); alert.message(2, info.data); alert.message(3, report.data); alert.string(6, "uuid-1")
        val action = ProtoWriter(); action.message(1, alert.data)
        val element = ProtoWriter(); element.message(2708, action.data); element.string(2001, "RmAlert,uuid-0")
        val batch = ProtoWriter(); batch.message(1001, element.data)

        val parsed = WazeLive.parseBatch(batch.data)
        assertEquals(1, parsed.size)
        assertEquals("RmAlert,uuid-0", parsed[0].oldCommand)
        val a = parsed[0].alert!!
        assertEquals("uuid-1", a.id)
        assertEquals(P, a.kind)
        assertEquals("Mobile speed camera", a.title)
        assertEquals(50.905133, a.location.lat, 1e-6)
        assertEquals(-1.339275, a.location.lng, 1e-6)
        assertEquals(270.0, a.heading)
        assertEquals("A3024 Bursledon Rd", a.street)
        assertEquals(12, a.thumbsUp)
        assertEquals(1_793_107_490_000L, a.reportedAtMillis)
    }

    @Test fun mapDisplayedLine() {
        val line = WazeLive.mapDisplayed(Box(50.0, -1.5, 51.0, -1.0))
        assertTrue(line.startsWith("MapDisplayed,-1.500000,51.000000,-1.000000,51.000000,-1.000000,50.000000,-1.500000,50.000000,-1.250000,50.500000,67186,-1.500000,51.000000"), line)
        assertEquals("row", WazeLive.region(LatLng(51.5, -0.12)))
        assertEquals("na", WazeLive.region(LatLng(38.5, -121.7)))
    }

    @Test fun env() {
        assertEquals("row", Waze.env(LatLng(51.5, -0.12)))
        assertEquals("na", Waze.env(LatLng(38.5, -121.7)))
        assertEquals("il", Waze.env(LatLng(32.08, 34.78)))
    }

    @Test fun projectKeepsOnlyOurDirection() {
        val line = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.02))
        val same = WazeAlert("same", P, location = LatLng(0.0001, 0.01), heading = 85.0)
        val other = WazeAlert("other", P, location = LatLng(-0.0001, 0.012), heading = 268.0)
        val unknown = WazeAlert("unknown", WazeAlert.Kind.ACCIDENT, location = LatLng(0.0, 0.015))
        val far = WazeAlert("far", P, location = LatLng(0.01, 0.01))
        val hits = Waze.project(listOf(same, other, unknown, far), line)
        assertEquals(listOf("same", "unknown"), hits.map { it.alert.id })
        assertEquals(1113.0, hits[0].along, 15.0)
    }

    @Test fun headingWrapsAroundNorth() {
        val line = listOf(LatLng(0.0, 0.0), LatLng(0.02, 0.0))
        val same = WazeAlert("same", P, location = LatLng(0.01, 0.0001), heading = 355.0)
        val other = WazeAlert("other", P, location = LatLng(0.012, 0.0001), heading = 175.0)
        val closed = WazeAlert("closed", WazeAlert.Kind.CLOSURE, location = LatLng(0.015, 0.0), heading = 180.0)
        assertEquals(listOf("same", "closed"), Waze.project(listOf(same, other, closed), line).map { it.alert.id })
    }

    @Test fun edgeCases() {
        assertEquals(0, Waze.parse("""{"jams":[]}""".toByteArray())?.size)
        assertEquals(0, Waze.parse("""{"alerts":[]}""".toByteArray())?.size)
        assertNull(Waze.parse("[1,2]".toByteArray()))
        assertTrue(Waze.project(listOf(WazeAlert("a", P, location = LatLng(0.0, 0.0))), listOf(LatLng(0.0, 0.0))).isEmpty())
        assertEquals(WazeAlert.Kind.JAM, Waze.kind("JAM", "JAM_HEAVY_TRAFFIC"))
        assertEquals(WazeAlert.Kind.WEATHER, Waze.kind("WEATHERHAZARD", "HAZARD_ON_ROAD_ICE"))
        assertNull(Waze.kind("CHIT_CHAT", ""))
        assertEquals("Hazard", WazeAlert("x", WazeAlert.Kind.HAZARD, "HAZARD_SOMETHING_NEW", LatLng(0.0, 0.0)).title)
        assertEquals("Road closed ahead.", WazeAlert("x", WazeAlert.Kind.CLOSURE, location = LatLng(0.0, 0.0)).spoken)
        assertTrue(Waze.url(51.0, -1.0, 52.0, 0.0).contains("top=52.00000&bottom=51.00000&left=-1.00000&right=0.00000&env=row"))
    }

    @Test fun everyKindHasAName() {
        for (k in WazeAlert.Kind.entries) {
            val a = WazeAlert("x", k, location = LatLng(0.0, 0.0))
            assertFalse(a.title.isEmpty())
            assertTrue(a.spoken.endsWith("ahead."))
        }
    }
}

class PoliceRiskTests {
    @Test fun score() {
        val road = listOf(LatLng(51.5, -0.2), LatLng(51.5, -0.16))
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        assertEquals(0, PoliceRisk.score(road, emptyList(), 0, now))
        assertEquals(PoliceRisk.Level.HIGH, PoliceRisk.level(PoliceRisk.score(road, emptyList(), 1, now)))
        val near = List(2) { PoliceRisk.Report(LatLng(51.5002, -0.18), now - day * 7) }
        val far = List(20) { PoliceRisk.Report(LatLng(51.6, -0.18), now) }
        assertEquals(PoliceRisk.Level.MEDIUM, PoliceRisk.level(PoliceRisk.score(road, near, 0, now)))
        assertEquals(0, PoliceRisk.score(road, far, 0, now))
        val old = near.map { PoliceRisk.Report(it.location, now - day * 360) }
        assertEquals(PoliceRisk.Level.LOW, PoliceRisk.level(PoliceRisk.score(road, old, 0, now)))
        assertEquals(0, PoliceRisk.score(emptyList(), near, 0, now))
        assertTrue(PoliceRisk.score(emptyList(), emptyList(), 1, now) > 60)
    }
}

class AircraftTests {
    @Test fun parse() {
        val json = """{"ac":[{"hex":"406f5c","flight":"NPAS15  ","r":"G-POLA","t":"EC35","lat":51.5,"lon":-0.1,"alt_baro":1500,"gs":90,"track":120},{"hex":"aaa","flight":"BAW123","ownOp":"British Airways","lat":51.4,"lon":-0.4,"alt_baro":9000},{"hex":"bbb","ownOp":"County of Los Angeles Sheriff","category":"A7","lat":34,"lon":-118,"alt_baro":800},{"hex":"ccc","flight":"UKP02","lat":51,"lon":0,"alt_baro":"ground"}]}"""
        val list = Aircraft.parse(json.toByteArray())!!
        assertEquals(listOf("406f5c", "bbb"), list.map { it.id })
        assertTrue(list[0].helicopter)
        assertEquals("NPAS15", list[0].callsign)
        assertEquals(1500.0, list[0].altitudeFeet)
        assertTrue(list[1].helicopter)
        assertEquals("Police helicopter", list[1].title)
    }
    @Test fun policeNames() {
        assertTrue(Aircraft.isPolice("NPAS21", null))
        assertTrue(Aircraft.isPolice(null, "Bundespolizei"))
        assertTrue(Aircraft.isPolice(null, "California Highway Patrol"))
        assertFalse(Aircraft.isPolice("EZY12AB", "easyJet"))
        assertFalse(Aircraft.isPolice(null, null))
        assertNull(Aircraft.parse("oops".toByteArray()))
        assertEquals(0, Aircraft.parse("""{"ac":[]}""".toByteArray())?.size)
    }
}
