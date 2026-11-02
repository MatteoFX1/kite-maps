package app.kite.core

import kotlin.math.*
import kotlin.test.*

/** A point [r] metres from [c] at [deg] degrees clockwise from north. */
fun around(c: LatLng, deg: Double, r: Double): LatLng {
    val k = 111_320 * cos(c.lat * PI / 180)
    return LatLng(c.lat + r * cos(deg * PI / 180) / 110_540, c.lng + r * sin(deg * PI / 180) / k)
}

fun steps(from: Double, through: Double, by: Double): List<Double> {
    val out = mutableListOf<Double>(); var d = from
    while (d <= through + 1e-9) { out.add(d); d += by }
    return out
}

class RoundaboutTests {
    private val parkGate = Json.obj("""
        {"maneuver":{"type":"rotary","exit":5,"location":[-1.253292,50.873558]},
         "intersections":[
          {"location":[-1.253292,50.873558],"bearings":[120,165,330],"entry":[false,false,true],"in":1,"out":2},
          {"location":[-1.253542,50.873819],"bearings":[0,150,315],"entry":[true,false,true],"in":1,"out":0},
          {"location":[-1.252877,50.875028],"bearings":[75,225,255],"entry":[true,false,false],"in":1,"out":0},
          {"location":[-1.252625,50.87509],"bearings":[75,255,345],"entry":[true,false,true],"in":1,"out":0},
          {"location":[-1.252442,50.87513],"bearings":[60,90,255],"entry":[true,true,false],"in":2,"out":1},
          {"location":[-1.251731,50.875004],"bearings":[135,315,345],"entry":[true,false,false],"in":1,"out":0},
          {"location":[-1.251667,50.874959],"bearings":[75,150,315],"entry":[true,true,false],"in":2,"out":1}]}
    """)!!
    private val parkGateExit = Json.obj("""
        {"maneuver":{"type":"exit rotary","location":[-1.251482,50.874705]},
         "intersections":[{"location":[-1.251482,50.874705],"bearings":[150,165,345],"entry":[true,true,false],"in":2,"out":0}]}
    """)!!

    private fun route(line: List<LatLng>, steps: List<Maneuver>, meters: Double = 600.0, source: String = "osm") =
        Route(mode = TravelMode.DRIVE, polyline = line, distanceMeters = meters, typicalSeconds = 60.0, trafficSeconds = null,
            summary = null, maneuvers = steps.toMutableList(), source = source)

    @Test fun sketchCountsTheExitsPassed() {
        val s = OSRM.roundaboutSketch(parkGate, parkGateExit, true)!!
        assertEquals(5, s.exits.size)
        assertEquals(4, s.taken)
        assertFalse(s.complete)
        assertEquals(2, s.entriesOnly.size)
        val turns = s.exits.indices.map { s.turn(it) }
        assertEquals(turns.sorted(), turns)
        assertTrue(turns.all { it > 15 && it < 375 })
    }

    @Test fun circleFit() {
        val c = LatLng(51.0, -1.0)
        val pts = (0 until 6).map { i ->
            val a = i * 50.0; val r = 60.0
            LatLng(c.lat + r * cos(a * PI / 180) / 110_540, c.lng + r * sin(a * PI / 180) / (111_320 * cos(51 * PI / 180)))
        }
        assertTrue(OSRM.fitCircle(pts)!!.distanceTo(c) < 2)
    }

    @Test fun highwayCodeLane() {
        val left = RoundaboutShape(exits = listOf(270.0, 0.0, 90.0), taken = 0, clockwise = true, complete = true)
        assertEquals(0, left.ruleLane)
        assertEquals(1, left.copy(taken = 2).ruleLane)
        assertEquals(0, left.copy(taken = 1).ruleLane)
    }

    @Test fun shapeFromOpenStreetMap() {
        val c = LatLng(51.5, -0.1)
        fun at(d: Double, r: Double) = around(c, d, r)
        val ringIds = (1L..12L).toList() + 1L
        val ringPts = (0 until 12).map { at(it * 30.0, 30.0) } + at(0.0, 30.0)
        val ways = mutableListOf(Roundabouts.Way(100, ringIds, ringPts, mapOf("junction" to "roundabout", "highway" to "primary")))
        listOf(0.0, 90.0, 180.0, 270.0).forEachIndexed { i, deg ->
            val ring = listOf(1L, 4L, 7L, 10L)[i]
            ways.add(Roundabouts.Way(200L + i, listOf(ring, 50L + i), listOf(at(deg, 30.0), at(deg, 120.0)), mapOf("highway" to "primary")))
        }
        val line = mutableListOf(at(180.0, 200.0), at(180.0, 30.0))
        for (d in steps(210.0, 450.0, 15.0)) line.add(at(d, 30.0))
        line.add(at(90.0, 200.0))
        val cum = Geo.cumulative(line)
        val along = Geo.project(at(180.0, 30.0), line, cum).along
        val s = Roundabouts.shape(ways, at(180.0, 30.0), line, cum, along, true, 3)!!
        assertEquals(4, s.exits.size)
        assertEquals(2, s.taken)
        assertTrue(s.complete)
        assertEquals(270.0, s.exits[0], 3.0)
        assertTrue(Roundabouts.angleGap(s.exits[1], 0.0) < 3)
        assertEquals(90.0, s.exits[2], 3.0)
        assertEquals(1, s.ruleLane)
    }

    @Test fun dualCarriagewayMerges() {
        val merged = Roundabouts.merge(listOf(
            Roundabouts.Arm(80.0, true, false, "A27"), Roundabouts.Arm(100.0, false, true, "A27"), Roundabouts.Arm(200.0, true, true, null)))
        assertEquals(2, merged.size)
        assertEquals(90.0, merged[0].angle, 0.5)
        assertTrue(merged[0].canExit && merged[0].canEnter)
    }

    @Test fun firstExitStraightOnPointsUp() {
        val c = LatLng(50.97, -1.37)
        fun at(d: Double, r: Double) = around(c, d, r)
        val line = mutableListOf(at(180.0, 300.0), at(180.0, 12.0))
        for (d in steps(200.0, 340.0, 20.0)) line.add(at(d, 12.0))
        line += listOf(at(0.0, 12.0), at(0.0, 300.0))
        val along = Geo.cumulative(line)[1]
        fun m() = Maneuver(ManeuverType.ROUNDABOUT, "At the roundabout, take the 1st exit", at(180.0, 12.0), along, 300.0, exit = 1)
        val s = Roundabouts.settle(route(line, listOf(m()))).maneuvers[0].roundabout!!
        assertEquals(1, s.exits.size)
        assertTrue(Roundabouts.angleGap(s.exits[s.taken], 0.0) < 15)
        assertEquals(0, s.ruleLane)

        val west = listOf(at(180.0, 300.0), at(180.0, 12.0), at(220.0, 12.0), at(250.0, 12.0), at(270.0, 12.0), at(270.0, 300.0))
        val s2 = Roundabouts.settle(route(west, listOf(m()))).maneuvers[0].roundabout!!
        assertTrue(Roundabouts.angleGap(s2.exits[s2.taken], 270.0) < 15)

        val sketched = route(line, listOf(m().also { it.roundabout = RoundaboutShape(listOf(265.0, 5.0), 1, clockwise = true, complete = false) }))
        val kept = Roundabouts.settle(sketched).maneuvers[0].roundabout!!
        assertEquals(2, kept.exits.size)
        assertTrue(Roundabouts.angleGap(kept.exits[0], 265.0) < 10)
        assertTrue(Roundabouts.angleGap(kept.exits[1], 0.0) < 15)
    }

    @Test fun bigRoundaboutExitIsReadOffTheRoadOut() {
        val c = LatLng(50.9455, -1.4763)
        val k = cos(c.lat * PI / 180) * 111_320
        fun at(d: Double, r: Double) = around(c, d, r)
        val line = mutableListOf(at(180.0, 500.0), at(180.0, 120.0))
        for (d in steps(185.0, 420.0, 5.0)) line.add(at(d, 120.0))
        val off = at(60.0, 120.0)
        line.add(LatLng(off.lat, off.lng + 400 / k))
        val cum = Geo.cumulative(line)
        val entry = cum[1]; val leave = cum[line.size - 2]
        assertTrue(Roundabouts.angleGap(Roundabouts.exitAngle(line, cum, entry, leave)!!, 90.0) < 10)
        assertTrue(Roundabouts.angleGap(Roundabouts.exitAngle(line, cum, entry)!!, 90.0) < 10)
    }

    @Test fun ruleLanesFollowTheRoadIn() {
        fun v(s: RoundaboutShape, hint: String? = null) = s.ruleLanes(true, hint)!!.map { it.valid }
        var s = RoundaboutShape(listOf(270.0, 0.0, 90.0), 2, clockwise = true, complete = true, entryLanes = 3)
        assertEquals(listOf(false, true, true), v(s))
        s = s.copy(taken = 1); assertEquals(listOf(true, true, false), v(s))
        s = s.copy(taken = 0); assertEquals(listOf(true, false, false), v(s))
        s = s.copy(entryLanes = null); assertEquals(listOf(true, false), v(s))
        s = s.copy(entryLanes = 1); assertNull(s.ruleLanes(true))
        val lap = RoundaboutShape(listOf(270.0, 0.0, 90.0, 170.0), 3, clockwise = true, complete = true, entryLanes = 3)
        assertEquals(listOf(false, false, true), v(lap))
        s = s.copy(entryLanes = 4, entryArrows = listOf("left", "left", "through", "through;right"), taken = 2)
        assertEquals(listOf(false, false, false, true), v(s))
        s = s.copy(taken = 1); assertEquals(listOf(false, false, true, true), v(s))
        s = s.copy(taken = 0); assertEquals(listOf(true, true, false, false), v(s))
        s = s.copy(entryArrows = listOf("left;through", "through", "through", "through"), taken = 2)
        assertEquals(listOf(true, true, true, false), v(s, "Use the left 3 lanes"))
        s = s.copy(entryArrows = null, entryLanes = 2)
        assertEquals(listOf(false, true, true), v(s, "Use the right 2 lanes"))
        assertEquals(listOf(false, true, false), v(s, "Use the lane 2nd from the left"))
        assertEquals(listOf(false, true), v(s, "Use the middle lane"))
        assertEquals(listOf(false, true), v(s, "Use any lane"))
        s = s.copy(entryLanes = 1)
        assertEquals(listOf(true, false), v(s, "Use the left lane"))
    }

    @Test fun mapPictureAimsWhereTheRoadGoes() {
        val map = RoundaboutShape(listOf(195.0, 300.0, 12.0, 100.0), 0, listOf(140.0), clockwise = true, complete = true)
        val s = Roundabouts.aim(map, 290.0)
        assertEquals(0, s.taken)
        assertEquals(290.0, s.exits[0], 0.01)
        fun r(a: Double) = (a - 180 + 360) % 360
        assertEquals(s.exits.map(::r).sorted(), s.exits.map(::r))
        assertEquals(4, s.exits.size)
        assertTrue(r(s.entriesOnly[0]) > r(s.exits[3]))
        val fine = Roundabouts.aim(RoundaboutShape(listOf(270.0, 0.0, 90.0), 1, clockwise = true, complete = true), 5.0)
        assertEquals(272.5, fine.exits[0], 0.5)
        assertEquals(5.0, fine.exits[1], 0.01)
    }

    @Test fun miniRoundaboutGetsAStep() {
        val c = LatLng(50.8957, -1.4469)
        fun at(d: Double, r: Double) = around(c, d, r)
        val ways = listOf(
            Roundabouts.Way(1, listOf(10L, 99L, 11L), listOf(at(180.0, 200.0), c, at(0.0, 200.0)), mapOf("highway" to "tertiary", "name" to "Normandy Way")),
            Roundabouts.Way(2, listOf(99L, 12L), listOf(c, at(90.0, 150.0)), mapOf("highway" to "service", "name" to "Test Lane")),
        )
        val line = listOf(at(180.0, 600.0), c, at(0.0, 600.0))
        val st = listOf(
            Maneuver(ManeuverType.DEPART, "Drive along Normandy Way", line[0], 0.0, 1000.0, road = "Normandy Way"),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Cracknore Hard", line[2], 1000.0, 200.0, road = "Cracknore Hard"),
            Maneuver(ManeuverType.ARRIVE, "You have arrived", line[2], 1200.0, 0.0),
        )
        val r = Roundabouts.withMinis(route(line, st, 1200.0, "google"), listOf(99L to c), ways)!!
        assertEquals(4, r.maneuvers.size)
        val m = r.maneuvers[1]
        assertEquals(ManeuverType.ROUNDABOUT, m.type)
        assertEquals(1, m.exit)
        assertEquals(600.0, m.alongMeters, 6.0)
        assertEquals("At the roundabout, take the 1st exit", m.instruction)
        assertEquals(600.0, r.maneuvers[0].distanceMeters, 6.0)
        assertEquals(400.0, m.distanceMeters, 6.0)
        val s = m.roundabout!!
        assertTrue(s.mini)
        assertEquals(2, s.exits.size)
        assertTrue(Roundabouts.angleGap(s.exits[s.taken], 0.0) < 5)
        assertEquals(90.0, s.exits[1], 5.0)
    }
}
