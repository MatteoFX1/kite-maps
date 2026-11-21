package app.kite.core

import kotlin.test.*

class TripNotesTests {
    @Test fun closingTimes() {
        assertEquals(22 * 60, ClosingCheck.closing("Open · Closes 10 pm")?.second)
        assertEquals(17 * 60 + 30, ClosingCheck.closing("Open ⋅ Closes 5:30 pm")?.second)
        assertEquals("5:30 pm", ClosingCheck.closing("Open ⋅ Closes 5:30 pm")?.first)
        assertEquals(17 * 60, ClosingCheck.closing("Closes soon ⋅ 5 PM")?.second)
        assertEquals(17 * 60, ClosingCheck.closing("Open · Closes 17:00")?.second)
        assertEquals(0, ClosingCheck.closing("Open · Closes 12 am")?.second)
        assertEquals(24 * 60, ClosingCheck.closing("Open · Closes midnight")?.second)
        assertNull(ClosingCheck.closing("Open 24 hours"))
        assertNull(ClosingCheck.closing("Closed · Opens 9 am"))
        assertNull(ClosingCheck.closing(null))
    }

    @Test fun closesBeforeArrival() {
        assertEquals(ClosingCheck.Warning("5 pm", true), ClosingCheck.warning("Open · Closes 5 pm", 16 * 60 + 30, 40.0 * 60))
        assertEquals(ClosingCheck.Warning("5 pm", false), ClosingCheck.warning("Open · Closes 5 pm", 16 * 60 + 30, 20.0 * 60))
        assertNull(ClosingCheck.warning("Open · Closes 10 pm", 16 * 60 + 30, 40.0 * 60))
        assertNull(ClosingCheck.warning("Open · Closes 1 am", 22 * 60, 20.0 * 60))
        assertEquals(true, ClosingCheck.warning("Open · Closes 1 am", 22 * 60, 4.0 * 3600)?.before)
    }

    @Test fun roadCharges() {
        val dartford = listOf(LatLng(51.4500, 0.2500), LatLng(51.4647, 0.2586), LatLng(51.4800, 0.2700))
        assertEquals(listOf("Dartford Crossing"), RoadCharges.on(dartford).map { it.name })
        assertEquals(emptyList(), RoadCharges.on(listOf(LatLng(51.4480, 0.2200), LatLng(51.4480, 0.2400))))
        assertEquals(emptyList(), RoadCharges.on(listOf(LatLng(50.9097, -1.4044), LatLng(51.0632, -1.3080))))
        assertEquals(listOf("London Congestion Charge zone"), RoadCharges.on(listOf(LatLng(51.5400, -0.1500), LatLng(51.5080, -0.1281))).map { it.name })
        assertEquals(emptyList(), RoadCharges.on(listOf(LatLng(51.5238, -0.1440), LatLng(51.5304, -0.1238))))
        assertEquals(listOf("Blackwall Tunnel"),
            RoadCharges.on(listOf(LatLng(51.5100, -0.0040), LatLng(51.5045, -0.0019), LatLng(51.4990, 0.0010))).map { it.name })
    }
}
