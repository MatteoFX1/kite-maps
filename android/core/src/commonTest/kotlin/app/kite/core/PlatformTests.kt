package app.kite.core

import kotlin.test.*

/** The small helpers that replaced Java's must give the same answers on both phones. */
class PlatformTests {
    @Test fun printfLikeJava() {
        assertEquals("(50.93000,-1.38500,51.00000,-1.30000)", fmt("(%.5f,%.5f,%.5f,%.5f)", 50.93, -1.385, 51.0, -1.3))
        assertEquals("-1.385000,50.930000", fmt("%.6f,%.6f", -1.385, 50.93))
        assertEquals("%0A", fmt("%%%02X", 10))
        assertEquals("on true |ab   |", fmt("on %b |%-5s|", true, "ab"))
        assertEquals("\\u001f", fmt("\\u%04x", 31))
        assertEquals("a/12/0.1235", fmt("%s/%d/%.4f", "a", 12, 0.123456))
        assertEquals("0.0", fmt("%.1f", -0.04))
        assertEquals("1.00", fmt("%.2f", 0.999))
    }

    @Test fun plainNumbers() {
        assertEquals("0.0001", 1.0E-4.plain())
        assertEquals("51.5", 51.5.plain())
        assertEquals("2", 2.0.plain())
        assertEquals("100", 100.0.plain())
        assertEquals("-1.385", (-1.385).plain())
        assertEquals("0", 0.0.plain())
        assertEquals("12345678912345", 1.2345678912345E13.plain())
    }

    @Test fun encoding() {
        assertEquals("caf%C3%A9+%26+bar*-._", formEnc("café & bar*-._"))
        assertEquals("café & bar", formDec("caf%C3%A9+%26+bar"))
        assertEquals("Cafe", foldAccents("Café"))
    }

    @Test fun links() {
        assertEquals("https://maps.app.goo.gl/x/y", resolveUrl("https://maps.app.goo.gl/a/b", "/x/y"))
        assertEquals("https://a.com/a/c?q=1", resolveUrl("https://a.com/a/b", "c?q=1"))
        assertEquals("https://b.com/z", resolveUrl("https://a.com/a/b", "https://b.com/z"))
        assertEquals("https://b.com/z", resolveUrl("https://a.com/a/b", "//b.com/z"))
        assertEquals("https://a.com/x", resolveUrl("https://a.com/a/b", "../x"))
    }

    @Test fun crcAndUnzip() {
        assertEquals(0xCBF43926L, crc32("123456789".encodeToByteArray()))
        // zlib("hello hello hello")
        val packed = byteArrayOf(0x78, 0x9c.toByte(), 0xcb.toByte(), 0x48, 0xcd.toByte(), 0xc9.toByte(), 0xc9.toByte(), 0x57, 0xc8.toByte(), 0x40, 0x90.toByte(), 0x00, 0x3a, 0x2e, 0x06, 0x7d)
        assertEquals("hello hello hello", zlibInflate(packed, 0, packed.size, 17)?.decodeToString())
    }
}
