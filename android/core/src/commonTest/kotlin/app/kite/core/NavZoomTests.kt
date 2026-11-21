package app.kite.core

import kotlin.test.*

class NavZoomTests {
    private val mph30 = 13.4
    private val mph70 = 31.3

    @Test fun normalBetweenTurns() {
        val v = NavZoom.view(mph30, 500.0, ManeuverType.TURN_LEFT, true)
        assertEquals(702.0, v.across, 1.0)
        assertEquals(50.0, v.pitch)
    }
    @Test fun pullsBackOnLongStretch() {
        val v = NavZoom.view(mph70, 5000.0, ManeuverType.RAMP_LEFT, true)
        assertEquals(1549.0, v.across, 1.0)
        assertEquals(55.0, v.pitch)
    }
    @Test fun zoomsInBeforeTurn() {
        val far = NavZoom.view(mph30, 300.0, ManeuverType.TURN_LEFT, true)
        val mid = NavZoom.view(mph30, 180.0, ManeuverType.TURN_LEFT, true)
        val close = NavZoom.view(mph30, 50.0, ManeuverType.TURN_LEFT, true)
        assertTrue(far.across > mid.across && mid.across > close.across)
        assertEquals(330.0, close.across, 0.1)
        assertEquals(35.0, close.pitch)
    }
    @Test fun motorwayExitStaysFurtherOut() {
        val v = NavZoom.view(mph70, 50.0, ManeuverType.RAMP_LEFT, true)
        assertEquals(mph70 * 14, v.across, 0.1)
    }
    @Test fun noCloseInForStraightOrArrive() {
        assertEquals(50.0, NavZoom.view(mph30, 100.0, ManeuverType.STRAIGHT, true).pitch)
        assertEquals(50.0, NavZoom.view(mph30, 100.0, ManeuverType.ARRIVE, true).pitch)
    }
    @Test fun roundaboutStaysClose() {
        assertEquals(330.0, NavZoom.view(mph30, 50.0, ManeuverType.ROUNDABOUT, true).across, 0.1)
        assertTrue(NavZoom.view(mph30, 340.0, ManeuverType.ROUNDABOUT, true).across <= 380.0)
    }
    @Test fun walking() {
        assertEquals(NavZoom.View(300.0, 50.0), NavZoom.view(1.4, 50.0, ManeuverType.TURN_LEFT, false))
    }

    @Test fun zoomSettingAndLook() {
        val v = NavZoom.View(700.0, 50.0)
        assertEquals(490.0, v.applying(MapZoom.CLOSER, MapLook.TILTED).across, 0.1)
        assertEquals(980.0, v.applying(MapZoom.WIDER, MapLook.TILTED).across, 0.1)
        assertEquals(v, v.applying(MapZoom.NORMAL, MapLook.TILTED))
        assertEquals(0.0, v.applying(MapZoom.NORMAL, MapLook.FLAT).pitch, 0.0)
        assertEquals(0.0, v.applying(MapZoom.NORMAL, MapLook.NORTH).pitch, 0.0)
        assertEquals(MapLook.FLAT, MapLook.TILTED.next)
        assertEquals(MapLook.NORTH, MapLook.FLAT.next)
        assertEquals(MapLook.TILTED, MapLook.NORTH.next)
        assertEquals(MapZoom.NORMAL, MapZoom.of(null))
        assertEquals(MapLook.NORTH, MapLook.of("north"))
    }

    @Test fun nextLimitDrop() {
        val r = Route(TravelMode.DRIVE, emptyList(), 5000.0, 300.0, null, null, mutableListOf(), source = "osm",
            speedLimits = listOf(SpeedLimit(0.0, 96.6), SpeedLimit(1000.0, 48.3), SpeedLimit(2000.0, null),
                SpeedLimit(3000.0, 32.2), SpeedLimit(4000.0, 64.4)))
        assertEquals(48.3, r.nextLimitDrop(700.0, 400.0)?.kmh)
        assertNull(r.nextLimitDrop(100.0, 400.0))
        assertNull(r.nextLimitDrop(1800.0, 400.0))
        assertNull(r.nextLimitDrop(2800.0, 400.0))
        assertNull(r.nextLimitDrop(3800.0, 400.0))
        assertEquals(469.5, NavZoom.limitHeadsUp(31.3), 0.1)
        assertEquals(200.0, NavZoom.limitHeadsUp(5.0), 0.0)
    }
}
