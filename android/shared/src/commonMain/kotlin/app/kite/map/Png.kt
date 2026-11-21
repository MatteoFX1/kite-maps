package app.kite.map

import app.kite.core.Bytes
import app.kite.core.crc32

/** A plain PNG writer for pictures Kite makes itself (the coloured heat map tiles). */
object Png {
    /** [argb] pixels (not premultiplied), [w] by [h], as PNG file bytes. Stored without squeezing: quick, and the map reads it from memory. */
    fun encode(argb: IntArray, w: Int, h: Int): ByteArray {
        val raw = ByteArray((w * 4 + 1) * h)
        var i = 0
        for (y in 0 until h) {
            raw[i++] = 0 // no filter
            for (x in 0 until w) {
                val p = argb[y * w + x]
                raw[i++] = (p shr 16).toByte(); raw[i++] = (p shr 8).toByte(); raw[i++] = p.toByte(); raw[i++] = (p ushr 24).toByte()
            }
        }
        val out = Bytes(raw.size + raw.size / 65535 * 5 + 128)
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        chunk(out, "IHDR", Bytes().apply { int(w); int(h); write(8); write(6); write(0); write(0); write(0) }.toByteArray())
        chunk(out, "IDAT", zlibStored(raw))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun Bytes.int(v: Int) { write((v ushr 24) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 8) and 0xFF); write(v and 0xFF) }

    private fun chunk(out: Bytes, type: String, data: ByteArray) {
        out.int(data.size)
        val td = type.encodeToByteArray() + data
        out.write(td)
        out.int(crc32(td).toInt())
    }

    private fun zlibStored(data: ByteArray): ByteArray {
        val out = Bytes(data.size + data.size / 65535 * 5 + 16)
        out.write(0x78); out.write(0x01)
        var at = 0
        do {
            val n = minOf(65535, data.size - at)
            out.write(if (at + n >= data.size) 1 else 0)
            out.write(n and 0xFF); out.write(n shr 8)
            out.write(n.inv() and 0xFF); out.write((n.inv() shr 8) and 0xFF)
            out.write(data, at, n)
            at += n
        } while (at < data.size)
        var a = 1L; var b = 0L
        for (x in data) { a = (a + (x.toInt() and 0xFF)) % 65521; b = (b + a) % 65521 }
        out.int(((b shl 16) or a).toInt())
        return out.toByteArray()
    }
}
