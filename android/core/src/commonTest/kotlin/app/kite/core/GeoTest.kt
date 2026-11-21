package app.kite.core

import kotlin.test.*

class PlainNumberTests {
    @Test fun smallNumbersByGreenwich() {
        assertEquals("-0.0001", (-0.0001).plain())
        assertEquals("0.00005", 0.00005.plain())
        assertEquals("51.4543", 51.4543.plain())
        assertEquals("-0.9731", (-0.9731).plain())
        assertFalse((-1.2e-4).plain().contains("E"))
    }

    @Test fun osrmUrlHasNoExponent() {
        val url = OSRM.routeURL(LatLng(51.4778, -0.00015), LatLng(51.5, 0.0005), TravelMode.DRIVE)
        assertFalse(url.contains("E-"), url)
    }
}
