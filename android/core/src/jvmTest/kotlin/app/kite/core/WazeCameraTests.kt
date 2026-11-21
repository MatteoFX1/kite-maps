package app.kite.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import kotlin.test.*

class WazeCameraTests {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun u32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    /** A piece of Waze's map as its server sends it, holding [cameras] as table 25. */
    private fun piece(tile: Int, cameras: ByteArray): ByteArray {
        val tables = MutableList(46) { ByteArray(0) }
        tables[1] = "A27 - Bassett Green Rd".toByteArray() + 0
        tables[25] = cameras
        tables[26] = u32(tile) + u32(0) + u32(1_793_783_731)
        val bits = 2; val mask = (1 shl bits) - 1
        val ends = ByteArrayOutputStream(); val body = ByteArrayOutputStream()
        for (t in tables) {
            if (t.isNotEmpty()) {
                while (body.size() and mask != 0) body.write(0)
                body.write(t)
            }
            ends.write(u32(body.size()))
        }
        val plain = u32(tables.size) + u32(bits) + ends.toByteArray() + body.toByteArray()
        val deflater = Deflater()
        deflater.setInput(plain)
        deflater.finish()
        val buf = ByteArray(plain.size + 64)
        val packed = buf.copyOf(deflater.deflate(buf))
        deflater.end()
        return bytes("2e2e2e2e000000011329e29e00000000000010b2") + "WZDF".toByteArray() + bytes("0100000000000300") +
            u32(packed.size) + u32(plain.size) + packed
    }

    /** The M27 at junction 5 as Waze sent it on 2026-11-15: a camera each way, both 112 km/h (70 mph). */
    private val j5 = "2ef0eaff5d6d090346000270629e00007ff0eaffa36c0903fa00027099190000"

    @Test fun theSpeedCamerasInAPieceOfWazesMap() {
        // After the two real ones: a red light camera (kind 3), and a speed camera placed far outside the piece.
        val extra = "30f0eaff606d0903000003000100000000000000000000004600027002000000"
        val cams = WazeCameras.parse(piece(321512094, bytes(j5 + extra)))!!
        assertEquals(2, cams.size)
        assertEquals(50.949469, cams[0].location.lat, 0.000001)
        assertEquals(-1.380306, cams[0].location.lng, 0.000001)
        assertEquals(50.949283, cams[1].location.lat, 0.000001)
        assertEquals(listOf(RoadCamera.Kind.SPEED, RoadCamera.Kind.SPEED), cams.map { it.kind })
        assertEquals(listOf<Double?>(112.0, 112.0), cams.map { it.maxspeedKmh })
        // Numbered below zero, so they never clash with OpenStreetMap's, and each its own.
        assertTrue(cams.all { WazeCameras.isWaze(it) })
        assertEquals(2, cams.map { it.id }.toSet().size)
        // No direction is taken from Waze: its meaning isn't certain, so the camera counts both ways.
        assertNull(cams[0].directions)
    }

    @Test fun aPieceWithoutCamerasAndSomethingThatIsntAPiece() {
        assertEquals(0, WazeCameras.parse(piece(321512093, ByteArray(0)))!!.size)
        assertNull(WazeCameras.parse("<html>busy</html>".toByteArray()))
        assertNull(WazeCameras.parse(ByteArray(0)))
        // Cut short on the way: not half a list.
        val whole = piece(321512094, bytes(j5))
        assertNull(WazeCameras.parse(whole.copyOf(whole.size - 9)))
    }

    @Test fun pieceNumbers() {
        assertEquals(321512094, WazeCameras.tileId(LatLng(50.9494996, -1.3803431)))
        assertEquals(321512093, WazeCameras.tileId(LatLng(50.9300, -1.3855)))
        // A route's pieces start where it starts, and take in the ones it only brushes past.
        val ids = WazeCameras.tileIds(listOf(LatLng(50.9405, -1.3880), LatLng(50.9405, -1.3803)))
        assertEquals(WazeCameras.tileId(LatLng(50.9405, -1.3880)), ids.first())
        assertTrue(321512094 in ids)
        assertTrue(321512093 in ids)
        assertEquals(ids.size, ids.toSet().size)
        // A map view: a town's worth is fine, a county's isn't asked for.
        assertEquals(4, WazeCameras.tileIds(50.911, -1.389, 50.925, -1.371).size)
        assertTrue(WazeCameras.tileIds(50.7, -1.6, 51.1, -1.0).isEmpty())
    }

    @Test fun aCameraBothMapsHoldShowsOnce() {
        val osm = RoadCamera(7, RoadCamera.Kind.SPEED, LatLng(50.9494996, -1.3803431), maxspeedKmh = 112.0, average = true)
        val plate = RoadCamera(8, RoadCamera.Kind.PLATE, LatLng(50.9300, -1.3855))
        val same = RoadCamera(-1, RoadCamera.Kind.SPEED, LatLng(50.949469, -1.380306), maxspeedKmh = 112.0)
        val new = RoadCamera(-2, RoadCamera.Kind.SPEED, LatLng(50.92021, -1.39097), maxspeedKmh = 64.0)
        val twin = RoadCamera(-3, RoadCamera.Kind.SPEED, LatLng(50.92021, -1.39096), maxspeedKmh = 64.0)
        // A number plate camera at the same spot is a different thing: Waze's speed camera stays.
        val byPlate = RoadCamera(-4, RoadCamera.Kind.SPEED, LatLng(50.9300, -1.3855), maxspeedKmh = 48.0)
        val all = WazeCameras.merge(listOf(osm, plate), listOf(same, new, twin, byPlate))
        assertEquals(listOf(7L, 8L, -2L, -4L), all.map { it.id })
        // Mixing twice adds nothing twice.
        assertEquals(listOf(7L, 8L, -2L, -4L), WazeCameras.merge(all, listOf(same, new, twin, byPlate)).map { it.id })
        assertEquals(listOf(7L), WazeCameras.merge(listOf(osm), emptyList()).map { it.id })
    }

    @Test fun camerasAreKeptOnThePhoneForAWeek() {
        val store = WazeCameraStore(null)
        val now = 1_794_000_000_000L; val day = 86_400_000L
        assertEquals(listOf(1, 2, 3), store.stale(listOf(1, 2, 3), now))
        val cam = RoadCamera(-2, RoadCamera.Kind.SPEED, LatLng(50.92021, -1.39097), maxspeedKmh = 64.0)
        val far = RoadCamera(-5, RoadCamera.Kind.SPEED, LatLng(50.9495, -1.3803), maxspeedKmh = 112.0)
        store.put(mapOf(1 to listOf(cam), 2 to listOf(far)), listOf(3), now)
        assertEquals(1, store.version)
        assertEquals(emptyList(), store.stale(listOf(1, 2, 3), now))
        // A piece that came back blank is asked for again the next day; a read one after a week.
        assertEquals(listOf(3), store.stale(listOf(1, 2, 3), now + 2 * day))
        assertEquals(listOf(1, 2, 3), store.stale(listOf(1, 2, 3), now + 8 * day))
        assertEquals(setOf(-2L, -5L), store.known().map { it.id }.toSet())
        // Thomas Lewis Way, heading into Southampton: only the camera on it.
        val line = listOf(LatLng(50.9300, -1.3850), LatLng(50.9202, -1.3910), LatLng(50.91894, -1.39534))
        assertEquals(listOf(-2L), store.known(line).map { it.id })
    }

    @Test fun theStoreComesBackFromItsFile() {
        val file = File.createTempFile("waze-cameras", ".json")
        try {
            val store = WazeCameraStore(file.path)
            store.put(mapOf(321494092 to listOf(RoadCamera(-2, RoadCamera.Kind.SPEED, LatLng(50.92021, -1.39097), maxspeedKmh = 64.0))), listOf(5), 1_794_000_000_000L)
            Thread.sleep(3500)
            val again = WazeCameraStore(file.path)
            assertEquals(listOf(RoadCamera(-2, RoadCamera.Kind.SPEED, LatLng(50.92021, -1.39097), maxspeedKmh = 64.0)), again.known())
            assertEquals(listOf(9), again.stale(listOf(321494092, 5, 9), 1_794_000_000_000L))
        } finally {
            file.delete()
        }
    }

    @Test fun wazeCamerasJoinWhatTheRoadCacheKnows() {
        val line = listOf(LatLng(50.9300, -1.3850), LatLng(50.9202, -1.3910), LatLng(50.91894, -1.39534))
        val real = WazeCameras.shared
        WazeCameras.shared = WazeCameraStore(null)
        try {
            WazeCameras.shared.put(mapOf(999_999_001 to listOf(RoadCamera(-77, RoadCamera.Kind.SPEED, LatLng(50.92021, -1.39097), maxspeedKmh = 64.0))))
            val cache = RoadCache(null)
            assertEquals(emptyList(), cache.along(line).cameras.map { it.id })
            WazeCameras.enabled = true
            assertEquals(listOf(-77L), cache.along(line).cameras.map { it.id })
        } finally {
            WazeCameras.enabled = false
            WazeCameras.shared = real
        }
    }
}
