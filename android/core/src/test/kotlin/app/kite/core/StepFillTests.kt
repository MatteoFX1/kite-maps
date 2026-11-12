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
}
