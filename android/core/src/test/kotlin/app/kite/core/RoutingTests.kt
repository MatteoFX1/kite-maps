package app.kite.core

import kotlin.math.*
import kotlin.test.*

class RoutingTests {
    private fun ll(a: Double, b: Double) = LatLng(a, b)
    private fun route(line: List<LatLng>, steps: List<Maneuver>, meters: Double, secs: Double = 100.0) =
        Route(mode = TravelMode.DRIVE, polyline = line, distanceMeters = meters, typicalSeconds = secs, trafficSeconds = null,
            summary = null, maneuvers = steps.toMutableList(), source = "osm")
    private fun man(t: ManeuverType, text: String, at: LatLng, along: Double, dist: Double, road: String? = null, hint: String? = null) =
        Maneuver(t, text, at, along, dist, road = road, laneHint = hint)

    @Test fun decodePolyline5() {
        val pts = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 1e5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-6); assertEquals(-120.2, pts[0].lng, 1e-6)
        assertEquals(43.252, pts[2].lat, 1e-6); assertEquals(-126.453, pts[2].lng, 1e-6)
    }

    @Test fun googleStep() {
        val m = DirectionsParser.parseStep("<step maneuver='TURN' meters='120'>Turn <turn side='LEFT'>left</turn> onto <roadlist><road>Elm St</road></roadlist></step>")
        assertEquals(ManeuverType.TURN_LEFT, m.type)
        assertEquals(120.0, m.distanceMeters)
        assertEquals("Elm St", m.road)
        assertEquals("Turn left onto Elm St", m.instruction)
    }

    @Test fun foldedMotorwayStep() {
        val json = """[[0,"",[12553,"7.8 mi",1],[486,"8 min"],null,null,null,[[[2612.7,-1.2584,50.8754],[0,0,0],[1024,768],13.1]],null,null,null,null,null,null,[[2,["Merge onto "]],[2,["M27",1]]]],null,["//maps.gstatic.com/tactile/directions/text_mode/maneuvers-2x.png","dir-tt dir-tt-merge"],null,null,"x",0]"""
        val entry = Json.parse(json) as List<*>
        val m = DirectionsParser.foldedStep(entry[0] as List<*>, "dir-tt dir-tt-merge")
        assertEquals(ManeuverType.MERGE, m?.type)
        assertEquals(12553.0, m?.distanceMeters)
        assertEquals("M27", m?.road)
        assertEquals("Merge onto M27", m?.instruction)
        val keep = """[0,"",[900,"0.6 mi",1],[30,"30 sec"],null,null,null,null,null,null,null,null,null,null,[[2,["Keep "]],[2,["right",1]],[2,[" to stay on "]],[2,["M3",1]]]]"""
        val k = DirectionsParser.foldedStep(Json.parse(keep) as List<*>, "dir-tt dir-tt-keep-right")
        assertEquals(ManeuverType.KEEP_RIGHT, k?.type)
        assertEquals("M3", k?.road)
        val steps = mutableListOf<Maneuver>()
        DirectionsParser.collectSteps(listOf(listOf(entry)), steps)
        assertEquals(listOf(12553.0), steps.map { it.distanceMeters })
        assertNull(DirectionsParser.foldedStep(Json.parse("""[0,null,[12967,"8.1 mi",1],[511,"9 min"]]""") as List<*>, null))
        assertEquals("Slight left onto the ramp to So'ton",
            DirectionsParser.parseStep("<step maneuver='ON_RAMP' meters='209'>Slight left onto the ramp to So&#39;ton</step>").instruction)
    }

    @Test fun slipRoadWithNoSideIsNotAMerge() {
        val s = DirectionsParser.parseStep("<step maneuver='ON_RAMP' meters='300'>Use the left lane to take the <signlist><sign>M3</sign></signlist> slip road</step>")
        assertNotEquals(ManeuverType.MERGE, s.type)
        val o = ll(50.97, -1.356)
        fun line(bend: Double): List<LatLng> {
            val k = cos(o.lat * PI / 180) * 111_320
            val a = ll(o.lat + 500 / 110_540.0, o.lng)
            return listOf(o, a, ll(a.lat + 300 * cos(bend * PI / 180) / 110_540, a.lng + 300 * sin(bend * PI / 180) / k))
        }
        assertEquals(ManeuverType.RAMP_LEFT, DirectionsParser.place(listOf(s.copy()), line(-35.0))[1].type)
        assertEquals(ManeuverType.RAMP_RIGHT, DirectionsParser.place(listOf(s.copy()), line(35.0))[1].type)
        assertEquals(ManeuverType.RAMP_LEFT, DirectionsParser.place(listOf(s.copy()), line(4.0))[1].type)
        fun entry(icon: String) = listOf(listOf(0L, "<step maneuver='OFF_RAMP' meters='300'>Exit at junction 10</step>", listOf(300L, "0.2 mi", 1L)), null,
            listOf("//maps.gstatic.com/tactile/directions/text_mode/maneuvers-2x.png", icon))
        val steps = mutableListOf<Maneuver>()
        DirectionsParser.collectSteps(listOf(entry("dir-tt dir-tt-ramp-right")), steps)
        assertEquals(listOf(ManeuverType.RAMP_RIGHT), steps.map { it.type })
        assertEquals(ManeuverType.RAMP_RIGHT, DirectionsParser.place(steps, line(-35.0))[1].type)
        assertEquals(ManeuverType.RAMP_LEFT, OSRM.maneuverType("off ramp", "straight", true))
        assertEquals(ManeuverType.RAMP_RIGHT, OSRM.maneuverType("on ramp", null, false))
        assertEquals(ManeuverType.RAMP_RIGHT, OSRM.maneuverType("on ramp", "slight right", true))
    }

    @Test fun geometryDeltas() {
        val g = DirectionsParser.decodeGeometry(listOf(listOf(385_000_000L, 10_000L), listOf(-1_217_000_000L, -20_000L)))!!
        assertEquals(2, g.size)
        assertEquals(38.501, g[1].lat, 1e-9)
        assertEquals(-121.702, g[1].lng, 1e-9)
    }

    @Test fun pbHasNoPlaceholdersLeft() {
        val pb = DirectionsPb.build(ll(38.5, -121.7), ll(38.7, -121.6), TravelMode.WALK, avoidTolls = true)
        assertFalse(pb.contains("{")); assertFalse(pb.contains(" "))
        assertTrue(pb.contains("!1e2!2e3"))
        assertTrue(pb.startsWith("!1m4!3m2!3d38.5!4d-121.7!6e2!1m4!3m2!3d38.7!4d-121.6!6e2!3m12!1m3!1d"), pb)
        assertTrue(pb.endsWith("!47m2!8b1!10e2"))
        val drive = DirectionsPb.build(ll(1.0, 2.0), ll(3.0, 4.0), TravelMode.DRIVE, avoidTolls = true)
        assertTrue(drive.contains("!6m57!1m5!18b1!30b1!31m1!1b1!34e1!2m5!2b1!5m1"))
    }

    @Test fun tracker() {
        val line = listOf(ll(0.0, 0.0), ll(0.005, 0.0), ll(0.01, 0.0))
        val total = Geo.length(line)
        val r = route(line, listOf(
            man(ManeuverType.DEPART, "Go", line[0], 0.0, total / 2),
            man(ManeuverType.TURN_LEFT, "Turn", line[1], total / 2, total / 2),
            man(ManeuverType.ARRIVE, "Arrive", line[2], total, 0.0)), total)
        val t = RouteTracker(r)
        val p = t.update(ll(0.0025, 0.0001))
        assertEquals(1, p.nextIndex)
        assertEquals(total / 4, p.distanceToNext, 5.0)
        assertEquals(75.0, p.remainingSeconds, 1.0)
        assertFalse(p.offRoute)
        t.update(ll(0.006, 0.01)); t.update(ll(0.006, 0.01))
        assertTrue(t.update(ll(0.006, 0.01)).offRoute)
        assertTrue(t.update(ll(0.00999, 0.0)).arrived)
    }

    @Test fun wrongWayIsOffRoute() {
        val line = listOf(ll(0.0, 0.0), ll(0.005, 0.0), ll(0.01, 0.0))
        val total = Geo.length(line)
        val r = route(line, listOf(man(ManeuverType.DEPART, "Go", line[0], 0.0, total), man(ManeuverType.ARRIVE, "Arrive", line[2], total, 0.0)), total)
        val t = RouteTracker(r)
        assertFalse(t.update(ll(0.004, 0.0), heading = 2.0).offRoute)
        var lat = 0.004; var off = false
        repeat(4) { lat -= 0.0001; off = t.update(ll(lat, 0.0), heading = 180.0).offRoute }
        assertTrue(off)
        val t2 = RouteTracker(r)
        repeat(6) { assertFalse(t2.update(ll(0.004, 0.0)).offRoute) }
    }

    @Test fun noMergeWhenTheLaneRunsToTheExit() {
        val p = ll(50.92, -1.47)
        fun steps() = listOf(
            man(ManeuverType.DEPART, "Head out", p, 0.0, 300.0),
            man(ManeuverType.MERGE, "Merge onto Redbridge Road", p, 300.0, 400.0),
            man(ManeuverType.RAMP_LEFT, "Take the exit onto A33", p, 700.0, 500.0),
            man(ManeuverType.ARRIVE, "Arrive", p, 1200.0, 0.0))
        val out = OSRM.keepLaneThroughMerges(steps(), mapOf(1 to "slight right"))
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.RAMP_LEFT, ManeuverType.ARRIVE), out.map { it.type })
        assertEquals(700.0, out[0].distanceMeters)
        assertEquals(listOf(true, false), out[1].lanes.map { it.valid })
        val far = steps(); far[2].type = ManeuverType.RAMP_RIGHT
        assertEquals(4, OSRM.keepLaneThroughMerges(far, mapOf(1 to "slight right")).size)
    }

    @Test fun stepsOnlyFromARouteOnTheSameRoads() {
        val line = listOf(ll(0.0, 0.0), ll(0.02, 0.0))
        val cum = Geo.cumulative(line)
        assertTrue(StepMerge.follows(listOf(ll(0.0, 0.0001), ll(0.02, 0.0001)), line, cum))
        assertFalse(StepMerge.follows(listOf(ll(0.0, 0.0), ll(0.01, 0.0), ll(0.01, 0.001), ll(0.02, 0.001)), line, cum))
        val loop = listOf(ll(0.0, 0.0), ll(0.01, 0.0), ll(0.01, 0.004), ll(0.01, 0.0), ll(0.02, 0.0))
        assertTrue(StepMerge.follows(line, loop, Geo.cumulative(loop)))
        assertFalse(StepMerge.follows(loop, line, cum))
        val url = OSRM.matchURL(line, TravelMode.DRIVE)
        assertTrue(url.contains("/match/v1/driving/"))
        assertEquals(15, url.split("radiuses=")[1].split("&")[0].split(";").size)
        val long = OSRM.matchURL(listOf(ll(0.0, 0.0), ll(1.0, 0.0)), TravelMode.DRIVE)
        assertTrue(long.endsWith("&waypoints=0;99"), long)
    }

    @Test fun googleLaneHintOnTheSameStep() {
        val p = ll(0.0, 0.0)
        val steps = mutableListOf(man(ManeuverType.MERGE, "Merge onto Redbridge Road", p, 1000.0, 300.0))
        StepMerge.addGoogleLaneHints(steps, listOf(man(ManeuverType.MERGE, "Merge onto Redbridge Rd", p, 1060.0, 300.0, hint = "Use the right lane")))
        assertEquals("Use the right lane", steps[0].laneHint)
    }

    @Test fun rerouteKeepsHeading() {
        val url = OSRM.routeURL(ll(50.9, -1.4), ll(50.8, -1.3), TravelMode.DRIVE, heading = 271.6)
        assertTrue(url.endsWith("&bearings=272,60;"), url)
        assertFalse(OSRM.routeURL(ll(50.9, -1.4), ll(50.8, -1.3), TravelMode.DRIVE).contains("bearings"))
        val south = listOf(ll(0.01, 0.0), ll(0.009, 0.0), ll(0.0, 0.0))
        assertTrue(Directions.startsBehind(south, 0.0))
        assertFalse(Directions.startsBehind(south, 175.0))
    }

    @Test fun stepsPlacedFromTheEnd() {
        val line = listOf(ll(0.0, 0.0), ll(0.01, 0.0))
        val turn = DirectionsParser.parseStep("<step maneuver='TURN' meters='300'>Turn <turn side='LEFT'>left</turn></step>")
        val out = DirectionsParser.place(listOf(turn), line)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.TURN_LEFT, ManeuverType.ARRIVE), out.map { it.type })
        assertEquals("Head north", out[0].instruction)
        assertEquals(Geo.length(line) - 300, out[1].alongMeters, 0.5)
        assertEquals(Geo.length(line), out[2].alongMeters, 0.5)
    }

    @Test fun stepMergeOntoGoogleLine() {
        val google = listOf(ll(0.0, 0.0), ll(0.01, 0.0))
        fun m(t: ManeuverType, lat: Double, text: String) = man(t, text, ll(lat, 0.00004), 0.0, 0.0)
        val osrm = route(google, listOf(m(ManeuverType.DEPART, 0.0, "Drive north on A St"), m(ManeuverType.TURN_RIGHT, 0.0045, "Turn right onto B St"),
            m(ManeuverType.ARRIVE, 0.01, "You have arrived")), 1113.0)
        val steps = StepMerge.adopt(listOf(osrm), google)!!
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.TURN_RIGHT, ManeuverType.ARRIVE), steps.map { it.type })
        assertEquals(Geo.length(google) * 0.45, steps[1].alongMeters, 2.0)
        assertEquals(Geo.length(google) * 0.45, steps[0].distanceMeters, 2.0)
        assertNull(StepMerge.adopt(listOf(osrm), listOf(ll(0.0, 0.01), ll(0.01, 0.01))))
    }

    @Test fun speedLimits() {
        val line = listOf(ll(0.0, 0.0), ll(0.001, 0.0), ll(0.002, 0.0), ll(0.003, 0.0))
        val cum = Geo.cumulative(line)
        val legs = Json.parse("""[{"annotation":{"maxspeed":[{"speed":50.0,"unit":"km/h"},{"speed":50.0,"unit":"km/h"},{"unknown":true}]}}]""").asMaps()!!
        val limits = OSRM.speedLimits(legs, cum)
        assertEquals(2, limits.size)
        assertEquals(50.0, limits[0].kmh)
        assertNull(limits[1].kmh)
        assertEquals(cum[2], limits[1].startMeters, 0.1)
        val r = route(line, emptyList(), cum[3], 10.0).also { it.speedLimits = limits }
        assertEquals(50.0, r.speedLimit(10.0))
        assertNull(r.speedLimit(cum[2] + 1))
    }

    @Test fun lanes() {
        val step = Json.obj("""{"intersections":[{"lanes":[{"indications":["left"],"valid":true},{"indications":["straight"],"valid":false},{"indications":["straight","right"],"valid":false}]}]}""")!!
        val lanes = OSRM.lanes(step)
        assertEquals(3, lanes.size)
        assertTrue(lanes[0].valid)
        assertEquals(listOf("straight", "right"), lanes[2].indications)
        val allValid = Json.obj("""{"intersections":[{"lanes":[{"indications":["straight"],"valid":true},{"indications":["straight"],"valid":true}]}]}""")!!
        assertTrue(OSRM.lanes(allValid).isEmpty())
    }

    @Test fun roundaboutApproachLanes() {
        val redbridge = ll(50.922617, -1.469691)
        val lanes = """[{"indications":["left"],"valid":false},{"indications":["straight"],"valid":true},{"indications":["straight"],"valid":true}]"""
        val slip = Json.obj("""{"location":[-1.4697,50.9232],"in":0,"out":2,"entry":[false,true,true],"bearings":[0,150,180],"lanes":$lanes}""")!!
        assertTrue(OSRM.approachLanes(slip, redbridge, "slight left", true).isEmpty())
        val plain = Json.obj("""{"location":[-1.4697,50.9232],"in":0,"out":1,"entry":[false,true],"bearings":[0,180],"lanes":$lanes}""")!!
        assertEquals(listOf(true, false, false), OSRM.approachLanes(plain, redbridge, "slight left", true).map { it.valid })
        val far = plain + ("location" to listOf(-1.4697, 50.9300))
        assertTrue(OSRM.approachLanes(far, redbridge, "slight left", true).isEmpty())
    }

    @Test fun slipRoadWording() {
        val d = TravelMode.DRIVE
        assertEquals("Take exit 5 toward Eastleigh and Southampton",
            OSRM.instruction("off ramp", "slight left", "M27", null, d, OSRM.Signs("A335: Eastleigh, Southampton", "5", "M27")))
        assertEquals("Take exit 10 toward Alton and Fareham",
            OSRM.instruction("off ramp", "slight left", "M27", null, d, OSRM.Signs("A32: (A32) Alton, Fareham", "10", "M27")))
        assertEquals("Take the exit", OSRM.instruction("off ramp", "slight left", "M27", null, d, OSRM.Signs(null, null, "M27")))
        assertEquals("Take the slip road onto M27", OSRM.instruction("on ramp", "slight left", "M27", null, d, OSRM.Signs(null, null, "A27")))
        assertEquals("Turn right to stay on A3222", OSRM.instruction("continue", "right", "A3222", null, d))
        assertEquals("Make a U-turn on Bridge Road", OSRM.instruction("continue", "uturn", "Bridge Road", null, d))
    }

    @Test fun namedRoundaboutWording() {
        val d = TravelMode.DRIVE
        assertEquals("Go straight through Millbrook Roundabout", OSRM.instruction("roundabout", null, "Millbrook Roundabout", null, d))
        assertEquals("At Redbridge Roundabout, take the 2nd exit", OSRM.instruction("roundabout", null, "Redbridge Roundabout", 2, d))
        assertEquals("At the roundabout, take the 3rd exit onto A33", OSRM.instruction("roundabout", null, "A33", 3, d))
    }

    @Test fun steadySuggestions() {
        val old = listOf("roundhay park", "roundhay park photos", "roundhay park leeds")
        val new = listOf("roundhay park photos", "roundhay park", "roundhay park cafe", "roundhay park leeds")
        assertEquals(listOf("roundhay park", "roundhay park photos", "roundhay park leeds", "roundhay park cafe"), SuggestParser.steady(new, old) { it })
        assertEquals(listOf("a", "b"), SuggestParser.steady(listOf("a", "b"), listOf("x", "y", "b")) { it })
    }

    @Test fun roadNamesSaidInFull() {
        val v = SpokenText::forVoice
        assertEquals("Take the exit toward Stoneham Way.", v("Take the exit toward Stoneham Wy."))
        assertEquals("Slight left onto the ramp to Southampton", v("Slight left onto the ramp to So'ton"))
        assertEquals("Turn right onto Burgess Road", v("Turn right onto Burgess Rd"))
        assertEquals("Turn left onto Princes Street.", v("Turn left onto Princes St."))
        assertEquals("Turn left onto Saint Mary's Road", v("Turn left onto St Mary's Rd"))
        assertEquals("At Saint Mary's Roundabout, take the 2nd exit", v("At St Mary's Roundabout, take the 2nd exit"))
        assertEquals("Merge onto A33 north", v("Merge onto A33 N"))
        assertEquals("Merge onto A57 M", v("Merge onto A57(M)"))
        assertEquals("Turn left onto Cowbridge Road East", v("Turn left onto Cowbridge Road E"))
        assertEquals("Continue onto Waterloo Bridge", v("Continue onto Waterloo Brg"))
        assertEquals("Turn left onto West Approach Road", v("Turn left onto W Approach Rd"))
        assertEquals("Turn left onto Copperas Hill", v("Turn left onto Copperas Hl"))
        assertEquals("Slight right to stay on West Approach Rd", SpokenText.forScreen("Slight right to stay on W Approach Rd"))
        assertEquals("Turn left onto Cowbridge Rd East", SpokenText.forScreen("Turn left onto Cowbridge Rd E"))
        assertEquals("Merge onto A33 N", SpokenText.forScreen("Merge onto A33 N"))
        assertEquals("Slight right to stay on West Approach Road", v("Slight right to stay on W Approach Road"))
        assertEquals("Merge onto A33 north Then keep left", v("Merge onto A33 N Then keep left"))
        assertEquals("Turn right onto Holloway Road West.", v("Turn right onto Holloway Rd W."))
        assertEquals("Turn left onto Princes Street. Then turn right", v("Turn left onto Princes St. Then turn right"))
        assertEquals("Starting route. Head toward Highfield Road.", v("Starting route. Head toward Highfield Rd."))
        assertEquals("Continue onto Drove Road toward Romsey", v("Continue onto Drove Road toward Romsey"))
        assertEquals("Turn left onto Avenue Road", v("Turn left onto Avenue Road"))
    }

    @Test fun exitFromWords() {
        assertEquals(3, DirectionsParser.exitNumber("At the roundabout, take the 3rd exit onto Hill Ln"))
        assertEquals(12, DirectionsParser.exitNumber("At the roundabout, take the 12th exit"))
        assertEquals(2, DirectionsParser.exitNumber("At the roundabout, take the second exit"))
        assertNull(DirectionsParser.exitNumber("At the roundabout, continue straight"))
    }

    @Test fun rampIsSlipRoad() {
        assertEquals("Take the slip road to Southampton", British.words("Take the ramp to Southampton"))
        assertEquals("Slight left onto the slip road to So'ton", British.words("Slight left onto the ramp to So'ton"))
        assertEquals("Turn left onto Rampart Road", British.words("Turn left onto Rampart Road"))
        assertEquals("Exit the car park toward Park Walk", British.words("Exit the parking lot toward Park Walk"))
    }

    @Test fun googleExitCountWins() {
        val a = man(ManeuverType.ROUNDABOUT, "Go straight through Millbrook Roundabout", ll(0.0, 0.0), 2662.0, 60.0, "Millbrook Roundabout")
        val off = man(ManeuverType.ROUNDABOUT, "Exit the roundabout onto Tebourba Way", ll(0.0, 0.0), 2726.0, 0.0, "Tebourba Way")
        off.leavesRoundabout = true; off.exit = 1
        val steps = mutableListOf(a, off)
        val g = man(ManeuverType.ROUNDABOUT, "At the roundabout, take the 2nd exit", ll(0.0, 0.0), 2660.0, 0.0).also { it.exit = 2 }
        StepMerge.addGoogleExits(steps, listOf(g))
        assertEquals(2, steps[0].exit)
        assertEquals("At Millbrook Roundabout, take the 2nd exit", steps[0].instruction)
        assertEquals(2, steps[1].exit)
    }

    @Test fun destinationSide() {
        val line = listOf(ll(50.900, -1.400), ll(50.901, -1.400))
        assertEquals("right", Geo.sideAtEnd(ll(50.901, -1.3996), line))
        assertEquals("left", Geo.sideAtEnd(ll(50.901, -1.4004), line))
        assertNull(Geo.sideAtEnd(ll(50.901, -1.400), line))
        assertNull(Geo.sideAtEnd(ll(50.910, -1.3996), line))
    }

    @Test fun aLinkRoadThatKeepsTheMotorwayNumberIsNotOntoTheMotorway() {
        // M3 J6 into Basingstoke: the slip, the roundabout and the link road up to the A339 all carry ref M3.
        val json = "{\"routes\":[{\"geometry\":\"_t`w`B~j|`AwvIwwPwgDwdHolFgEovA~p@\",\"distance\":1903,\"duration\":120,\"legs\":[{\"steps\":[{\"maneuver\":{\"type\":\"depart\",\"modifier\":\"straight\",\"location\":[-1.08,51.25]},\"name\":\"\",\"ref\":\"M3\",\"distance\":700},{\"maneuver\":{\"type\":\"off ramp\",\"modifier\":\"slight left\",\"location\":[-1.0709,51.2555]},\"name\":\"\",\"ref\":\"M3\",\"distance\":454},{\"maneuver\":{\"type\":\"rotary\",\"modifier\":\"straight\",\"exit\":1,\"location\":[-1.0662,51.2582]},\"name\":\"\",\"ref\":\"M3\",\"distance\":11},{\"maneuver\":{\"type\":\"exit rotary\",\"modifier\":\"straight\",\"exit\":1,\"location\":[-1.0662,51.2583]},\"name\":\"\",\"ref\":\"M3\",\"distance\":416},{\"maneuver\":{\"type\":\"merge\",\"modifier\":\"slight right\",\"location\":[-1.0669,51.2634]},\"name\":\"Ringway East\",\"ref\":\"A339\",\"distance\":322},{\"maneuver\":{\"type\":\"arrive\",\"modifier\":\"straight\",\"location\":[-1.0669,51.2634]},\"name\":\"Ringway East\",\"ref\":\"A339\",\"distance\":0}]}]}]}"
        val steps = OSRM.parse(json.toByteArray(), TravelMode.DRIVE).first().maneuvers
        val ring = steps.first { it.instruction.startsWith("At the roundabout") }
        assertEquals("At the roundabout, take the 1st exit", ring.instruction)
        assertNull(ring.road)
        assertFalse(steps.any { it.instruction.contains("Exit the roundabout onto M3") })
        assertTrue(steps.any { it.instruction.contains("Ringway East") })
    }
}
