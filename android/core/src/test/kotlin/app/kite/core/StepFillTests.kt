package app.kite.core

import kotlin.test.*

/** Steps the open router leaves out or says twice, filled in and tidied (StepFill.kt; the iPhone's StepFillTests). */
class StepFillTests {
    /** A straight line about 5.5 km due north, so every step sits on it. */
    private val line = listOf(LatLng(50.0, -1.0), LatLng(50.05, -1.0))

    private fun step(t: ManeuverType, words: String, at: Double, road: String? = null, exit: Int? = null) =
        Maneuver(t, words, Geo.point(line, at), at, 0.0, road = road, exit = exit)

    private fun ends(mid: List<Maneuver>): MutableList<Maneuver> {
        val all = (listOf(step(ManeuverType.DEPART, "Head out", 0.0)) + mid + listOf(step(ManeuverType.ARRIVE, "You have arrived", Geo.length(line)))).toMutableList()
        respace(all)
        return all
    }

    @Test fun googleTurnTheRouterLeftOutIsAdded() {
        val steps = ends(listOf(step(ManeuverType.MERGE, "Merge onto A38(M)", 1000.0, road = "A38(M)"),
            step(ManeuverType.STRAIGHT, "Continue straight onto Gravelly Hill", 3000.0, road = "Gravelly Hill")))
        val google = listOf(step(ManeuverType.STRAIGHT, "Head north", 0.0), step(ManeuverType.MERGE, "Merge onto A38(M)", 990.0),
            step(ManeuverType.TURN_RIGHT, "Keep right to stay on A38(M)", 2200.0), step(ManeuverType.MERGE, "Merge onto Gravelly Hill", 2990.0))
        StepMerge.addGoogleMissing(steps, google, line)
        assertEquals(listOf("Head out", "Merge onto A38(M)", "Keep right to stay on A38(M)", "Continue straight onto Gravelly Hill", "You have arrived"),
            steps.map { it.instruction })
        assertEquals(2200.0, steps[2].alongMeters, 15.0)
        assertEquals("A38(M)", steps[2].road)
        assertEquals(steps[2].alongMeters - steps[1].alongMeters, steps[1].distanceMeters, 0.1)
    }

    @Test fun driftedGoogleStepIsNotAddedTwice() {
        val steps = ends(listOf(step(ManeuverType.RAMP_LEFT, "Take exit 9 toward Redbourn", 2000.0),
            step(ManeuverType.ROUNDABOUT, "At the roundabout, take the 3rd exit", 2600.0, exit = 3)))
        val google = listOf(step(ManeuverType.RAMP_LEFT, "Exit at junction 9", 2120.0), step(ManeuverType.ROUNDABOUT, "At the roundabout, take the 3rd exit", 2610.0, exit = 3))
        StepMerge.addGoogleMissing(steps, google, line)
        assertEquals(4, steps.size)
    }

    @Test fun secondOfTwoRoundaboutsIsAdded() {
        val first = step(ManeuverType.ROUNDABOUT, "At the roundabout, take the 2nd exit onto Summer Road", 3600.0, road = "Summer Road", exit = 2)
        val off = step(ManeuverType.ROUNDABOUT, "Exit the roundabout onto Summer Road", 3620.0, road = "Summer Road", exit = 2).apply { leavesRoundabout = true }
        val steps = ends(listOf(first, off))
        val google = listOf(step(ManeuverType.ROUNDABOUT, "At the roundabout, take the 2nd exit", 3602.0, exit = 2),
            step(ManeuverType.ROUNDABOUT, "At the roundabout, take the 1st exit", 3733.0, exit = 1))
        StepMerge.addGoogleMissing(steps, google, line)
        assertEquals(listOf(2, 1), steps.filter { it.type == ManeuverType.ROUNDABOUT && !it.leavesRoundabout }.map { it.exit })
    }

    @Test fun mergeIntoALaneThatRunsToTheExitStaysOut() {
        val steps = ends(listOf(step(ManeuverType.RAMP_LEFT, "Take exit 3 toward Totton", 1500.0)))
        StepMerge.addGoogleMissing(steps, listOf(step(ManeuverType.MERGE, "Merge onto A33", 1000.0)), line)
        assertFalse(steps.any { it.type == ManeuverType.MERGE })
        val far = ends(listOf(step(ManeuverType.RAMP_LEFT, "Take exit 15 toward Springburn", 4000.0)))
        StepMerge.addGoogleMissing(far, listOf(step(ManeuverType.MERGE, "Merge onto M8", 1000.0)), line)
        assertTrue(far.any { it.instruction == "Merge onto M8" })
    }

    @Test fun googleRoundaboutWhereTheRouterSaysTurn() {
        val steps = ends(listOf(step(ManeuverType.TURN_RIGHT, "Turn right onto Otterbourne Hill", 1000.0, road = "Otterbourne Hill")))
        StepMerge.addGoogleKinds(steps, listOf(step(ManeuverType.ROUNDABOUT, "At the roundabout, take the 2nd exit", 985.0, exit = 2)))
        assertEquals(ManeuverType.ROUNDABOUT, steps[1].type)
        assertEquals(2, steps[1].exit)
        assertEquals("At the roundabout, take the 2nd exit onto Otterbourne Hill", steps[1].instruction)
    }

    @Test fun realTurnWhereTheRouterSaysStraight() {
        val steps = ends(listOf(step(ManeuverType.STRAIGHT, "Continue straight onto Gloucester Road", 1000.0, road = "Gloucester Road")))
        StepMerge.addGoogleKinds(steps, listOf(step(ManeuverType.TURN_LEFT, "Turn left onto Gloucester Rd", 1010.0)))
        assertEquals(ManeuverType.TURN_LEFT, steps[1].type)
        assertEquals("Turn left onto Gloucester Road", steps[1].instruction)
    }

    @Test fun junctionNumberFromGoogle() {
        val steps = ends(listOf(step(ManeuverType.RAMP_LEFT, "Take the exit toward The Midlands and London", 1000.0), step(ManeuverType.RAMP_LEFT, "Take the exit", 3000.0)))
        StepMerge.addGoogleSigns(steps, listOf(step(ManeuverType.RAMP_LEFT, "Exit at junction 4", 1030.0), step(ManeuverType.RAMP_LEFT, "Take the exit towards Emsworth", 3110.0)))
        assertEquals("Take exit 4 toward The Midlands and London", steps[1].instruction)
        assertEquals("Take the exit towards Emsworth", steps[2].instruction)
    }

    @Test fun oneSlipRoadToldTwiceIsOnce() {
        // Winchester J10: Google's exit, then the open router's 160 m on.
        val a = ends(listOf(step(ManeuverType.RAMP_LEFT, "Exit at junction 10", 20970.0), step(ManeuverType.RAMP_LEFT, "Take the exit", 21130.0)))
        StepMerge.tidy(a)
        assertEquals(listOf("Head out", "Exit at junction 10", "You have arrived"), a.filter { !it.silent }.map { it.instruction })
        // Gravelly Hill J6: the second brings the lane.
        val b = ends(listOf(step(ManeuverType.RAMP_LEFT, "Take exit 6 onto M6", 6165.0),
            step(ManeuverType.RAMP_LEFT, "Exit at junction 6", 6295.0).apply { laneHint = "Use the left lane" }))
        StepMerge.tidy(b)
        val spoken = b.filter { !it.silent }
        assertEquals(listOf("Head out", "Take exit 6 onto M6", "You have arrived"), spoken.map { it.instruction })
        assertEquals("Use the left lane", spoken[1].laneHint)
        // A bare first one takes the second's words.
        val c = ends(listOf(step(ManeuverType.RAMP_LEFT, "Take the exit", 1000.0), step(ManeuverType.RAMP_LEFT, "Take the exit toward Alton", 1120.0)))
        StepMerge.tidy(c)
        assertEquals(listOf("Head out", "Take the exit toward Alton", "You have arrived"), c.filter { !it.silent }.map { it.instruction })
        // Two real exits further apart both stay.
        val d = ends(listOf(step(ManeuverType.RAMP_LEFT, "Take exit 4", 1000.0), step(ManeuverType.RAMP_LEFT, "Take the exit toward Alton", 1400.0)))
        StepMerge.tidy(d)
        assertEquals(4, d.count { !it.silent })
    }

    @Test fun turnThenContinueIsOneTurn() {
        val steps = ends(listOf(step(ManeuverType.TURN_RIGHT, "Turn right", 2108.0), step(ManeuverType.STRAIGHT, "Continue straight onto Wide Ln", 2119.0, road = "Wide Ln")))
        StepMerge.tidy(steps)
        assertEquals(listOf("Head out", "Turn right onto Wide Ln", "You have arrived"), steps.filter { !it.silent }.map { it.instruction })
    }

    @Test fun sameTurnTwiceIsOnce() {
        val a = step(ManeuverType.SLIGHT_RIGHT, "Turn slight right onto Coldhams Lane", 1098.0, road = "Coldhams Lane").apply {
            lanes = listOf(Lane(listOf("straight"), false), Lane(listOf("right"), true))
        }
        val steps = ends(listOf(a, step(ManeuverType.TURN_RIGHT, "Turn right onto Coldhams Lane", 1106.0, road = "Coldhams Lane")))
        StepMerge.tidy(steps)
        val spoken = steps.filter { !it.silent }
        assertEquals(listOf("Head out", "Turn right onto Coldhams Lane", "You have arrived"), spoken.map { it.instruction })
        assertEquals(2, spoken[1].lanes.size)
    }

    @Test fun stayOnTheSameRoad() {
        val steps = ends(listOf(step(ManeuverType.TURN_LEFT, "Turn left onto Transport Avenue", 500.0, road = "Transport Avenue"),
            step(ManeuverType.TURN_LEFT, "Turn left onto Transport Avenue", 900.0, road = "Transport Avenue"),
            step(ManeuverType.STRAIGHT, "Continue onto Transport Avenue", 1400.0, road = "Transport Avenue")))
        StepMerge.tidy(steps)
        assertEquals(listOf("Head out", "Turn left onto Transport Avenue", "Turn left to stay on Transport Avenue", "You have arrived"),
            steps.filter { !it.silent }.map { it.instruction })
    }

    @Test fun startRoadIsNotTheRoadYoureOn() {
        val steps = mutableListOf(step(ManeuverType.DEPART, "Exit the car park towards Elmbank Cres", 0.0, road = "Elmbank Cres"),
            step(ManeuverType.TURN_LEFT, "Turn left onto Elmbank Cres", 46.0, road = "Elmbank Cres"), step(ManeuverType.ARRIVE, "You have arrived", 500.0))
        StepMerge.tidy(steps)
        assertEquals("Turn left onto Elmbank Cres", steps[1].instruction)
    }

    @Test fun piecewiseUsesGoogleWhereTheRouterStrays() {
        val total = Geo.length(line)
        val a = Geo.point(line, 2000.0)
        val b = Geo.point(line, 3000.0)
        val off = LatLng((a.lat + b.lat) / 2, a.lng + 0.01)
        val own = Route(mode = TravelMode.DRIVE, polyline = listOf(line[0], a, off, b, line[1]), distanceMeters = total, typicalSeconds = 300.0,
            trafficSeconds = null, summary = null, maneuvers = mutableListOf(
                step(ManeuverType.DEPART, "Drive along High Street", 0.0, road = "High Street"),
                step(ManeuverType.TURN_LEFT, "Turn left onto Mill Lane", 1000.0, road = "Mill Lane"),
                step(ManeuverType.TURN_RIGHT, "Turn right onto Nowhere Road", 2000.0),
                step(ManeuverType.TURN_LEFT, "Turn left onto Station Road", 4500.0, road = "Station Road"),
                step(ManeuverType.ARRIVE, "You have arrived", total)), source = "osm")
        val google = listOf(step(ManeuverType.STRAIGHT, "Head north on High St", 0.0), step(ManeuverType.TURN_LEFT, "Turn left onto Mill Ln", 1005.0),
            step(ManeuverType.ROUNDABOUT, "At the roundabout, take the 1st exit", 2500.0, exit = 1),
            step(ManeuverType.TURN_LEFT, "Turn left onto Station Rd", 4490.0), step(ManeuverType.ARRIVE, "You have arrived", total))
        val out = StepMerge.adoptPiecewise(listOf(own), google, line)
        assertEquals(listOf("Drive along High Street", "Turn left onto Mill Lane", "At the roundabout, take the 1st exit", "Turn left onto Station Road", "You have arrived"),
            out?.map { it.instruction })
    }

    /** A wiggly line about [km] long heading north-east from Southampton, a point every [step] metres or so. */
    private fun longLine(km: Double, step: Double = 25.0): List<LatLng> {
        val n = (km * 1000 / step).toInt()
        return (0..n).map { i ->
            val d = i * step
            LatLng(50.9 + d / 111_000.0 * 0.8, -1.4 + d / 70_000.0 * 0.4 + 0.0004 * kotlin.math.sin(i / 7.0))
        }
    }

    @Test fun nearLineMatchesTheFullSearch() {
        val l = longLine(30.0)
        val near = NearLine(l)
        val cum = Geo.cumulative(l)
        val rnd = java.util.Random(7)
        repeat(2000) {
            val base = Geo.point(l, rnd.nextDouble() * cum.last(), cum)
            val p = LatLng(base.lat + (rnd.nextDouble() - 0.5) * 0.0008, base.lng + (rnd.nextDouble() - 0.5) * 0.0012)
            val full = Geo.project(p, l, cum)
            val quick = near.project(p)
            if (full.offset <= 45) {
                assertEquals(full.offset, quick.offset, 0.01)
                assertEquals(full.along, quick.along, 0.5)
            } else assertTrue(quick.offset >= full.offset - 0.01)
        }
    }

    @Test fun piecewiseOnALongDriveIsQuick() {
        // Southampton to Newcastle is about 550 km: this used to take minutes and hold "Finding routes…".
        val l = longLine(550.0, 30.0)
        val total = Geo.length(l)
        val cum = Geo.cumulative(l)
        // The router strays for a few km in the middle, and has a turn every few km.
        val own = l.mapIndexed { i, p -> if (i in 9000..9100) LatLng(p.lat, p.lng + 0.01) else p }
        val steps = mutableListOf(Maneuver(ManeuverType.DEPART, "Head out", l[0], 0.0, 0.0))
        var at = 3000.0
        while (at < total - 3000) { steps.add(Maneuver(ManeuverType.TURN_LEFT, "Turn left", Geo.point(l, at, cum), at, 0.0)); at += 3000 }
        steps.add(Maneuver(ManeuverType.ARRIVE, "You have arrived", l.last(), total, 0.0))
        val route = Route(mode = TravelMode.DRIVE, polyline = own, distanceMeters = total, typicalSeconds = 20_000.0, trafficSeconds = null,
            summary = null, maneuvers = steps, source = "osm")
        val google = listOf(Maneuver(ManeuverType.STRAIGHT, "Head north", l[0], 0.0, 0.0), Maneuver(ManeuverType.ARRIVE, "You have arrived", l.last(), total, 0.0))
        val t0 = System.nanoTime()
        val out = StepMerge.adoptPiecewise(listOf(route, route, route), google, l)
        val secs = (System.nanoTime() - t0) / 1e9
        assertNotNull(out)
        assertTrue(out.size > 150, "kept the router's turns: ${out.size}")
        assertTrue(secs < 10, "took $secs s")
    }

    @Test fun aRouteThatStopsFarShortIsNotARoute() {
        val southampton = LatLng(50.9097, -1.4044)
        val davis = LatLng(38.5460, -121.7420)
        val toPortugal = listOf(LatLng(50.9100, -1.4040), LatLng(38.7807, -9.4977))
        assertFalse(Directions.reaches(toPortugal, southampton, davis))
        assertTrue(Directions.reaches(listOf(LatLng(50.9100, -1.4040), LatLng(51.5072, -0.1276)), southampton, LatLng(51.508, -0.128)))
    }

    @Test fun googleKeepIsAKeepArrow() {
        val m = DirectionsParser.parseStep("<step maneuver='TURN' meters='500'>Keep <turn side='RIGHT'>right</turn> to stay on <road>A38(M)</road></step>")
        assertEquals(ManeuverType.KEEP_RIGHT, m.type)
    }

    @Test fun uTurnTheLineNeverMakesIsDropped() {
        val steps = ends(listOf(step(ManeuverType.UTURN, "Make a U-turn", 36.0), step(ManeuverType.SHARP_RIGHT, "Turn sharp right onto Mill Lane", 36.0, road = "Mill Lane"),
            step(ManeuverType.TURN_RIGHT, "Turn right onto Church Street", 450.0, road = "Church Street")))
        StepMerge.dropFalseUTurns(steps, line)
        assertEquals(listOf("Head out", "Turn right onto Church Street", "You have arrived"), steps.map { it.instruction })
        val back = listOf(LatLng(50.0, -1.0), LatLng(50.001, -1.0), LatLng(50.0, -1.00005))
        val real = mutableListOf(step(ManeuverType.DEPART, "Head out", 0.0), step(ManeuverType.UTURN, "Make a U-turn", 111.0), step(ManeuverType.ARRIVE, "You have arrived", 222.0))
        StepMerge.dropFalseUTurns(real, back)
        assertEquals(3, real.size)
    }

    @Test fun forkSideAgainstTheRoadTakesGooglesTurn() {
        val bent = listOf(LatLng(50.0, -1.0), LatLng(50.009, -1.0), LatLng(50.009, -1.014))
        val at = Geo.project(LatLng(50.009, -1.0), bent).along
        val fork = Maneuver(ManeuverType.FORK_RIGHT, "Keep right at the fork onto Castle Lane West", LatLng(50.009, -1.0), at, 0.0, road = "Castle Lane West")
        val steps = mutableListOf(fork)
        StepMerge.addGoogleKinds(steps, listOf(Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Castle Ln W", fork.location, at + 10, 0.0)), bent)
        assertEquals(ManeuverType.TURN_LEFT, steps[0].type)
        assertEquals("Turn left onto Castle Lane West", steps[0].instruction)
        val straight = mutableListOf(fork.copy(type = ManeuverType.FORK_RIGHT, alongMeters = 1000.0))
        StepMerge.addGoogleKinds(straight, listOf(Maneuver(ManeuverType.TURN_LEFT, "Turn left", fork.location, 1010.0, 0.0)), line)
        assertEquals(ManeuverType.FORK_RIGHT, straight[0].type)
    }
}
