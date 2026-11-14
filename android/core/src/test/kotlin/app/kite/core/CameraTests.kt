package app.kite.core

import kotlin.math.round
import kotlin.test.*

private fun b(s: String) = s.toByteArray()
private fun ll(a: Double, b: Double) = LatLng(a, b)

class CameraTests {
    @Test fun parse() {
        val json = """{"elements":[{"type":"node","id":1,"lat":48.1,"lon":11.5,"tags":{"highway":"speed_camera"}},{"type":"node","id":2,"lat":38.5,"lon":-121.7,"tags":{"surveillance:type":"ALPR","manufacturer":"Flock Safety"}},{"type":"node","id":3}]}"""
        val cams = Overpass.parse(b(json))!!
        assertEquals(2, cams.size)
        assertEquals(RoadCamera.Kind.SPEED, cams[0].kind)
        assertEquals(RoadCamera.Kind.PLATE, cams[1].kind)
        assertEquals("Flock Safety", cams[1].operatorName)
    }

    @Test fun trafficLights() {
        val json = """{"elements":[{"type":"node","id":1,"lat":0.0050,"lon":0.0001,"tags":{"highway":"traffic_signals"}},{"type":"node","id":2,"lat":0.0052,"lon":0,"tags":{"highway":"traffic_signals"}},{"type":"node","id":3,"lat":0.0095,"lon":0,"tags":{"highway":"traffic_signals"}},{"type":"node","id":4,"lat":0.003,"lon":0.002,"tags":{"highway":"traffic_signals"}},{"type":"node","id":5,"lat":0.004,"lon":0,"tags":{"highway":"speed_camera"}}]}"""
        assertEquals(listOf(5L), Overpass.parse(b(json))!!.map { it.id })
        val lights = Overpass.parseSignalNodes(b(json)).map { it.at }
        assertEquals(4, lights.size)
        val on = Overpass.signalsOnRoute(lights, listOf(ll(0.0, 0.0), ll(0.01, 0.0)))
        assertEquals(listOf(ll(0.0050, 0.0001), ll(0.0095, 0.0)), on)
    }

    @Test fun query() {
        assertEquals("[out:json][timeout:25];(node[\"highway\"=\"speed_camera\"](1,2,3,4););out body 4000;",
            Overpass.query("(1,2,3,4)", speed = true, plate = false))
    }

    @Test fun due() {
        assertEquals(0, CameraAlerts.due(listOf(500.0, 700.0), 300.0, 20.0, emptySet()))
        assertNull(CameraAlerts.due(listOf(500.0, 700.0), 300.0, 20.0, setOf(0)))
        assertNull(CameraAlerts.due(listOf(500.0), 300.0, 1.0, emptySet()))
        assertNull(CameraAlerts.due(listOf(200.0), 300.0, 20.0, emptySet()))
    }

    @Test fun project() {
        val line = listOf(ll(0.0, 0.0), ll(0.01, 0.0))
        val on = RoadCamera(1, RoadCamera.Kind.SPEED, ll(0.005, 0.0001))
        val off = RoadCamera(2, RoadCamera.Kind.SPEED, ll(0.005, 0.01))
        val p = CameraAlerts.project(listOf(off, on), line)
        assertEquals(1, p.size)
        assertEquals(1L, p[0].camera.id)
        assertEquals(Geo.length(line) / 2, p[0].along, 2.0)
    }

    @Test fun maxspeed() {
        assertEquals(50.0, Overpass.maxspeedKmh("50"))
        assertEquals(48.28, Overpass.maxspeedKmh("30 mph")!!, 0.01)
        assertNull(Overpass.maxspeedKmh("DE:urban"))
        assertNull(Overpass.maxspeedKmh("none"))
    }

    @Test fun speedLimitsAlongRoute() {
        val route = listOf(ll(0.0, 0.0), ll(0.01, 0.0))
        val ways = listOf(
            Overpass.LimitWay(kmh = 50.0, line = listOf(ll(0.0, 0.0), ll(0.005, 0.0))),
            Overpass.LimitWay(kmh = 30.0, line = listOf(ll(0.005, 0.0), ll(0.01, 0.0))),
            Overpass.LimitWay(kmh = 80.0, line = listOf(ll(0.003, -0.01), ll(0.003, 0.0), ll(0.003, 0.01))),
        )
        val limits = Overpass.speedLimits(ways, route)
        assertEquals(50.0, limits.first().kmh)
        assertFalse(limits.any { it.kmh == 80.0 })
        val r = Route(mode = TravelMode.DRIVE, polyline = route, distanceMeters = 1113.0, typicalSeconds = 60.0,
            trafficSeconds = null, summary = null, maneuvers = mutableListOf(), source = "osm", speedLimits = limits)
        assertEquals(50.0, r.speedLimit(200.0))
        assertEquals(30.0, r.speedLimit(900.0))
    }

    @Test fun britishLimits() {
        assertEquals(96.56, Overpass.maxspeedKmh("national")!!, 0.1)
        assertEquals(112.65, Overpass.maxspeedKmh("GB:nsl_dual")!!, 0.1)
        assertEquals(32.19, Overpass.maxspeedKmh("20 mph")!!, 0.1)
        assertTrue(Geo.drivesOnLeft(ll(51.5074, -0.1278)), "London")
        assertTrue(Geo.drivesOnLeft(ll(55.95, -3.19)), "Edinburgh")
        assertTrue(Geo.drivesOnLeft(ll(51.48, -3.18)), "Cardiff")
        assertFalse(Geo.drivesOnLeft(ll(48.85, 2.35)), "Paris")
        assertFalse(Geo.drivesOnLeft(ll(38.54, -121.74)), "Davis")
    }

    @Test fun averageZones() {
        val line = listOf(ll(51.0, -1.0), ll(51.02, -1.0))
        val json = """
        {"elements":[{"type":"relation","tags":{"enforcement":"average_speed","maxspeed":"50 mph"},
          "members":[{"type":"node","lat":51.002,"lon":-1.0},{"type":"node","lat":51.0101,"lon":-1.0},
                     {"type":"node","lat":51.05,"lon":-1.2}]}]}
        """
        val z = Overpass.averageZones(b(json), line)
        assertEquals(1, z.size)
        assertEquals(222.0, z[0].startMeters, 5.0)
        assertEquals(1123.0, z[0].endMeters, 5.0)
        assertEquals(80.47, z[0].kmh!!, 0.1)
    }

    @Test fun zonesFromCameras() {
        fun cam(id: Long, avg: Boolean) = RoadCamera(id, RoadCamera.Kind.SPEED, ll(0.0, 0.0), maxspeedKmh = 96.56, average = avg)
        val z = CameraAlerts.averageZones(listOf(PlacedCamera(cam(1, true), 1000.0), PlacedCamera(cam(2, false), 2000.0),
            PlacedCamera(cam(3, true), 6000.0), PlacedCamera(cam(4, true), 40000.0)))
        assertEquals(1, z.size, "the far one is a lone camera, not a check")
        assertEquals(1000.0, z[0].startMeters)
        assertEquals(6000.0, z[0].endMeters)
        val m = CameraAlerts.merge(z, listOf(AverageSpeedZone(5000.0, 9000.0, null)))
        assertEquals(1, m.size)
        assertEquals(9000.0, m[0].endMeters)
    }

    @Test fun longRoutesAreCut() {
        val line = (0..100).map { ll(it * 0.01, 0.0) }
        val p = Overpass.pieces(line, 40_000.0)
        assertEquals(3, p.size)
        assertEquals(p[0].last(), p[1].first())
        assertEquals(line.last(), p.last().last())
    }

    @Test fun postcodes() {
        assertEquals("SW1A 1AA", Postcodes.normalize("sw1a1aa"))
        assertEquals("CF10 1NS", Postcodes.normalize(" CF10 1NS "))
        assertEquals("EH1 1YZ", Postcodes.normalize("EH1 1YZ"))
        assertNull(Postcodes.normalize("pizza"))
        assertNull(Postcodes.normalize("12345"))
        val json = """{"status":200,"result":{"postcode":"CF10 1NS","latitude":51.48,"longitude":-3.18,"admin_district":"Cardiff","admin_ward":"Cathays","country":"Wales"}}"""
        val p = Postcodes.parse(b(json), "CF10 1NS")
        assertEquals("CF10 1NS", p?.name)
        assertEquals("Cathays, Cardiff, Wales", p?.address)
    }

    @Test fun streetView() {
        val reply = """[[0],[[1],[2,"AbCdEfGhIjKlMnOpQrStUv"],[2,2,[6656,13312],[[1]]],[[null,null,38.544912,-121.740501],[15.2],[201.5,90.1,0.3]]]]"""
        val p = StreetView.parse(reply)!!
        assertEquals("AbCdEfGhIjKlMnOpQrStUv", p.id)
        assertEquals(38.544912, p.location.lat, 1e-6)
        assertEquals(-121.740501, p.location.lng, 1e-6)
        assertEquals(13312, p.width)
        assertEquals(6656, p.height)
        assertEquals(201.5, p.heading, 0.01)
        assertEquals(3328, p.size(3).first)
        assertNull(StreetView.parse("""[[5,"search returned no images"]]"""))
    }

    @Test fun knownRoadsComeBack() {
        val cache = RoadCache(null)
        val road = Overpass.LimitWay(7, 48.3, listOf(ll(50.900, -1.400), ll(50.905, -1.400)))
        val far = Overpass.LimitWay(8, 112.0, listOf(ll(51.500, -0.100), ll(51.505, -0.100)))
        cache.add(listOf(road, far), listOf(RoadCamera(3, RoadCamera.Kind.SPEED, ll(50.902, -1.4001))),
            listOf(Overpass.SignalNode(9, ll(50.903, -1.400))))
        cache.add(listOf(road), emptyList(), emptyList())
        val line = listOf(ll(50.899, -1.400), ll(50.906, -1.400))
        val known = cache.along(line)
        assertEquals(listOf(7L), known.ways.map { it.id })
        assertEquals(listOf(3L), known.cameras.map { it.id })
        assertEquals(1, known.signals.size)
        assertEquals(listOf(48.3), Overpass.speedLimits(known.ways, line).mapNotNull { it.kmh })
    }

    @Test fun aCameraTheMapDroppedIsForgotten() {
        val cache = RoadCache(null)
        val line = listOf(ll(50.899, -1.400), ll(50.906, -1.400))
        cache.add(emptyList(), listOf(
            RoadCamera(3, RoadCamera.Kind.SPEED, ll(50.902, -1.4001)),
            RoadCamera(4, RoadCamera.Kind.SPEED, ll(50.904, -1.4001)),
            RoadCamera(5, RoadCamera.Kind.PLATE, ll(50.903, -1.4001)),
            // On a road a road beside it, outside what the answer covered: the answer for this stretch says nothing about it.
            RoadCamera(6, RoadCamera.Kind.SPEED, ll(50.903, -1.4018)),
        ), emptyList())
        // A live answer for the stretch that only asked about speed cameras and found number 4.
        cache.forgetCameras(line, setOf(4L), speed = true, plate = false)
        assertEquals(listOf(4L, 5L, 6L), cache.along(line).cameras.map { it.id }.sorted())
    }

    @Test fun camerasMarkedNotWorkingAreLeftOut() {
        val json = """
        {"elements":[
         {"type":"node","id":1,"lat":50.9,"lon":-1.3,"tags":{"highway":"speed_camera","active":"no","note":"Not currently operational"}},
         {"type":"node","id":2,"lat":50.9,"lon":-1.3,"tags":{"highway":"speed_camera","active":"yes"}},
         {"type":"node","id":3,"lat":50.9,"lon":-1.3,"tags":{"highway":"speed_camera","disused":"yes"}},
         {"type":"node","id":4,"lat":50.9,"lon":-1.3,"tags":{"highway":"speed_camera","maxspeed":"70 mph"}},
         {"type":"node","id":5,"lat":50.9,"lon":-1.3,"tags":{"highway":"speed_camera","operational_status":"decommissioned"}}]}
        """
        assertEquals(listOf(2L, 4L), Overpass.parse(b(json))?.map { it.id })
    }

    @Test fun nothingKnownFarAway() {
        val cache = RoadCache(null)
        cache.add(listOf(Overpass.LimitWay(1, 50.0, listOf(ll(0.0, 0.0), ll(0.005, 0.0)))), emptyList(), emptyList())
        assertTrue(cache.along(listOf(ll(10.0, 10.0), ll(10.01, 10.0))).ways.isEmpty())
    }

    @Test fun valhallaLimits() {
        val json = """
        {"shape":"_ibE_ibE_ibE?_ibE?_ibE?","edges":[
         {"speed_limit":48,"way_id":11,"length":0.1,"begin_shape_index":0,"end_shape_index":1},
         {"speed_limit":48,"way_id":12,"length":0.1,"begin_shape_index":1,"end_shape_index":2},
         {"speed_limit":97,"way_id":13,"length":0.3,"begin_shape_index":2,"end_shape_index":3},
         {"way_id":14,"length":0.1,"begin_shape_index":3,"end_shape_index":4}]}
        """
        val p = Valhalla.parse(b(json))!!
        assertEquals(listOf(48.0, 97.0, null), p.first.map { it.kmh })
        assertEquals(listOf(0.0, 200.0, 500.0), p.first.map { round(it.startMeters) })
        assertEquals(600.0, p.third, 0.01)
        assertEquals(listOf(11L, 12L, 13L), p.second.map { it.id })
    }

    @Test fun britishUnitlessLimitIsMph() {
        val eastleigh = ll(50.9613, -1.3705); val paris = ll(48.85, 2.35); val dublin = ll(53.35, -6.26)
        assertEquals(48.28, Overpass.britishLimit(30.0, eastleigh)!!, 0.01)
        assertEquals(30.0, Overpass.britishLimit(30.0, paris))
        assertEquals(30.0, Overpass.britishLimit(30.0, dublin))
        assertEquals(48.28, Overpass.britishLimit(48.28032, eastleigh)!!, 0.01)
        assertEquals(80.0, Overpass.britishLimit(80.0, eastleigh))
        assertNull(Overpass.britishLimit(null, eastleigh))
    }

    @Test fun limitCarriesAlongTheSameRoad() {
        val json = """
        {"shape":"_ibE_ibE_ibE?_ibE?_ibE?","edges":[
         {"speed_limit":64,"way_id":1,"names":["Normandy Way"],"length":0.1,"begin_shape_index":0,"end_shape_index":1},
         {"way_id":2,"names":["Normandy Way"],"length":1.5,"begin_shape_index":1,"end_shape_index":2},
         {"way_id":3,"names":["Cracknore Hard Lane"],"length":0.4,"begin_shape_index":2,"end_shape_index":3},
         {"way_id":4,"names":["Normandy Way"],"length":0.1,"begin_shape_index":3,"end_shape_index":4}]}
        """
        val p = Valhalla.parse(b(json))!!
        assertEquals(listOf(64.0, null), p.first.map { it.kmh })
        assertEquals(listOf(0.0, 1600.0), p.first.map { round(it.startMeters) })
        assertEquals(listOf(64.0, null, null), p.second.map { it.kmh })
    }

    @Test fun directionTag() {
        assertEquals(listOf(90.0), RoadCamera.bearings("90"))
        assertEquals(listOf(45.0), RoadCamera.bearings("NE"))
        assertEquals(listOf(247.5), RoadCamera.bearings("wsw"))
        assertEquals(listOf(45.0, 225.0), RoadCamera.bearings("45;225"))
        assertEquals(listOf(0.0), RoadCamera.bearings("315-45"))
        assertEquals(listOf(270.0), RoadCamera.bearings("-90"))
        assertNull(RoadCamera.bearings("both"))
        assertNull(RoadCamera.bearings("forward"))
        assertNull(RoadCamera.bearings(null))
        val json = """{"elements":[{"type":"node","id":1,"lat":51,"lon":-1,"tags":{"highway":"speed_camera","direction":"180"}},{"type":"node","id":2,"lat":51,"lon":-1,"tags":{"surveillance:type":"ALPR","direction":"180"}}]}"""
        val cams = Overpass.parse(b(json))!!
        assertEquals(listOf(180.0), cams[0].directions)
        assertNull(cams[1].directions)
    }

    @Test fun onlyCamerasFacingOurWay() {
        val line = listOf(ll(51.0, -1.0), ll(51.02, -1.0))
        val west = -1.000115; val east = -0.999885; val middle = -1.0
        fun cam(id: Long, lat: Double, lng: Double, dir: List<Double>?) = RoadCamera(id, RoadCamera.Kind.SPEED, ll(lat, lng), directions = dir)
        val cams = listOf(
            cam(1, 51.002, west, listOf(0.0)), cam(2, 51.004, east, listOf(180.0)), cam(3, 51.006, east, null),
            cam(4, 51.008, west, listOf(180.0)), cam(5, 51.010, middle, listOf(180.0)), cam(6, 51.012, east, listOf(0.0)),
            cam(7, 51.014, east, listOf(90.0)), cam(8, 51.016, east, listOf(200.0)),
        )
        val north = CameraAlerts.project(cams, line)
        assertEquals(8, north.size)
        assertEquals(listOf(2L, 8L), north.filter { it.camera.quiet }.map { it.camera.id })
        val south = CameraAlerts.project(cams, line.reversed())
        assertEquals(listOf(1L), south.filter { it.camera.quiet }.map { it.camera.id })
    }

    @Test fun roundaboutEntryLanesFromTheMatcher() {
        val json = """{"shape":"_ibE_ibE_ibE?_ibE?_ibE?","edges":[
         {"way_id":1,"lane_count":1,"length":0.1,"begin_shape_index":0,"end_shape_index":1},
         {"way_id":2,"lane_count":3,"length":0.1,"begin_shape_index":1,"end_shape_index":2},
         {"way_id":3,"lane_count":2,"roundabout":true,"length":0.02,"begin_shape_index":2,"end_shape_index":3},
         {"way_id":4,"lane_count":1,"length":0.1,"begin_shape_index":3,"end_shape_index":4}]}"""
        val e = Valhalla.parseEntries(json.toByteArray())
        assertEquals(listOf(3), e.map { it.lanes })
        val at = Geo.decodePolyline("_ibE_ibE_ibE?_ibE?_ibE?")[2]
        val m = Maneuver(ManeuverType.ROUNDABOUT, "At the roundabout, take the 2nd exit", at, 200.0, 0.0, exit = 2,
            roundabout = RoundaboutShape(listOf(270.0, 60.0), 1, clockwise = true, complete = false))
        assertEquals(3, Roundabouts.withEntryLanes(listOf(m), e)?.get(0)?.roundabout?.entryLanes)
        assertNull(Roundabouts.withEntryLanes(listOf(m.copy(roundabout = m.roundabout!!.copy(entryLanes = 2))), e))
    }

    @Test fun mergeJoinsFromTheMatcher() {
        val json = """{"shape":"_ibE_ibE_ibE?_ibE?","edges":[
 {"lane_count":1,"begin_shape_index":0,"end_shape_index":1,"end_node":{"intersecting_edges":[{"driveability":"backward","begin_heading":61}]}},
 {"lane_count":4,"begin_shape_index":1,"end_shape_index":2,"end_node":{"intersecting_edges":[{"driveability":"forward","begin_heading":200}]}},
 {"lane_count":4,"begin_shape_index":2,"end_shape_index":3}]}"""
        val j = Merges.joins(json.toByteArray())
        assertEquals(2, j.size)
        assertEquals(1, j[0].inLanes)
        assertEquals(4, j[0].outLanes)
        assertEquals(listOf(61.0), j[0].others)
        assertEquals(emptyList(), j[1].others)
    }
}
