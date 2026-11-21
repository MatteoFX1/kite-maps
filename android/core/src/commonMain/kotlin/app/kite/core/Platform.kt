package app.kite.core

import kotlinx.coroutines.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.uuid.Uuid

// The few things each phone does its own way (files, locks, unzipping), plus small helpers that
// replace Java-only tools so the engine runs the same on Android and iPhone.

/** Milliseconds since 1970, like System.currentTimeMillis(). */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** A random id like "3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90". */
fun newId(): String = Uuid.random().toString()

/** A lock that the same thread may take twice. */
expect class KLock() {
    fun lock()
    fun unlock()
}

@OptIn(ExperimentalContracts::class)
inline fun <T> KLock.locked(block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    lock()
    try { return block() } finally { unlock() }
}

private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Runs [block] off the main thread after [delayMillis], like a short-lived background thread. */
fun background(delayMillis: Long = 0, block: () -> Unit) {
    backgroundScope.launch { if (delayMillis > 0) delay(delayMillis); block() }
}

/** Small files on the phone: read whole, write whole (safely, via a temporary file). */
expect object Disk {
    fun read(path: String): ByteArray?
    fun write(path: String, bytes: ByteArray): Boolean
    fun delete(path: String)
}

/** Unpacks zlib-packed bytes (with the 2-byte zlib header) into exactly [size] bytes, or null. */
expect fun zlibInflate(data: ByteArray, offset: Int, length: Int, size: Int): ByteArray?

/** Removes accents: "Café" -> "Cafe". */
expect fun foldAccents(s: String): String

/** The phone's language, like "en". */
expect fun systemLanguage(): String

/** A growing byte buffer, like ByteArrayOutputStream. */
class Bytes(capacity: Int = 64) {
    private var buf = ByteArray(maxOf(capacity, 16))
    var size = 0
        private set

    fun write(b: Int) {
        if (size == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[size++] = b.toByte()
    }

    fun write(bytes: ByteArray, from: Int = 0, len: Int = bytes.size - from) {
        if (size + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + len))
        bytes.copyInto(buf, size, from, from + len)
        size += len
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

private val crcTable = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
    c
}

/** The CRC-32 of [bytes], as java.util.zip.CRC32 gives it. */
fun crc32(bytes: ByteArray): Long {
    var c = -1
    for (b in bytes) c = crcTable[(c xor b.toInt()) and 0xff] xor (c ushr 8)
    return (c.inv().toLong()) and 0xffffffffL
}

/**
 * A small printf with the US number style: %d, %s, %b, %c, %f with precision (%.5f), %x/%X with
 * zero padding (%02X), left alignment (%-12s) and %%. Enough for the URLs and labels Kite builds.
 * Never throws: anything it doesn't know is written as plain text, so a log line can't stop Kite.
 */
fun fmt(pattern: String, vararg args: Any?): String {
    val sb = StringBuilder()
    var i = 0
    var arg = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c != '%') { sb.append(c); i++; continue }
        i++
        if (i < pattern.length && pattern[i] == '%') { sb.append('%'); i++; continue }
        var zero = false
        var plus = false
        var left = false
        while (i < pattern.length && (pattern[i] == '0' || pattern[i] == '+' || pattern[i] == ',' || pattern[i] == '-')) {
            if (pattern[i] == '0') zero = true
            if (pattern[i] == '+') plus = true
            if (pattern[i] == '-') left = true
            i++
        }
        var width = 0
        while (i < pattern.length && pattern[i].isDigit()) { width = width * 10 + (pattern[i] - '0'); i++ }
        var prec = -1
        if (i < pattern.length && pattern[i] == '.') {
            i++; prec = 0
            while (i < pattern.length && pattern[i].isDigit()) { prec = prec * 10 + (pattern[i] - '0'); i++ }
        }
        if (i >= pattern.length) { sb.append('%'); break }
        val conv = pattern[i++]
        val a = args.getOrNull(arg++)
        val n = a as? Number
        var s = when {
            conv == 'd' && n != null -> n.toLong().toString()
            conv == 'f' && n != null -> fixed(n.toDouble(), if (prec < 0) 6 else prec)
            conv == 'x' && n != null -> n.toLong().let { if (it < 0) (it and 0xffffffffL) else it }.toString(16)
            conv == 'X' && n != null -> n.toLong().let { if (it < 0) (it and 0xffffffffL) else it }.toString(16).uppercase()
            conv == 'b' -> (if (a is Boolean) a else a != null).toString()
            else -> a.toString().let { if (conv == 's' && prec >= 0 && it.length > prec) it.substring(0, prec) else it }
        }
        if (plus && conv in "df" && !s.startsWith("-")) s = "+$s"
        if (s.length < width) {
            s = if (left) s + " ".repeat(width - s.length) else if (zero && conv != 's') {
                val neg = s.startsWith("-") || s.startsWith("+")
                if (neg) s[0] + "0".repeat(width - s.length) + s.substring(1) else "0".repeat(width - s.length) + s
            } else " ".repeat(width - s.length) + s
        }
        sb.append(s)
    }
    return sb.toString()
}

/** [v] with [digits] digits after the point, rounded half up like Java's %f. */
fun fixed(v: Double, digits: Int): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    var scale = 1.0
    repeat(digits) { scale *= 10 }
    val neg = v < 0
    val n = floor(abs(v) * scale + 0.5)
    val whole = floor(n / scale)
    val frac = (n - whole * scale).toLong()
    val wholeStr = whole.toLong().toString()
    val sign = if (neg && n != 0.0) "-" else ""
    if (digits == 0) return sign + wholeStr
    val fracStr = frac.toString().padStart(digits, '0')
    return "$sign$wholeStr.$fracStr"
}

/** Form encoding like java.net.URLEncoder (space -> '+'). */
fun formEnc(s: String): String {
    val sb = StringBuilder()
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        val ch = c.toChar()
        when {
            c < 0x80 && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in ".-*_") -> sb.append(ch)
            ch == ' ' -> sb.append('+')
            else -> { sb.append('%'); sb.append(HEX[c shr 4]); sb.append(HEX[c and 15]) }
        }
    }
    return sb.toString()
}

/** Form decoding like java.net.URLDecoder ('+' -> space). Throws on a broken escape. */
fun formDec(s: String): String {
    if (!s.contains('%') && !s.contains('+')) return s
    val out = Bytes(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when (c) {
            '+' -> { out.write(' '.code); i++ }
            '%' -> {
                require(i + 3 <= s.length) { "bad escape" }
                val v = s.substring(i + 1, i + 3).toInt(16)
                out.write(v); i += 3
            }
            else -> {
                val start = i
                while (i < s.length && s[i] != '%' && s[i] != '+') i++
                out.write(s.substring(start, i).encodeToByteArray())
            }
        }
    }
    return out.toByteArray().decodeToString()
}

private const val HEX = "0123456789ABCDEF"

/** Resolves a link [ref] (maybe relative) against [base], like java.net.URI.resolve. */
fun resolveUrl(base: String, ref: String): String {
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(ref)) return ref
    val scheme = base.substringBefore("://", "")
    require(scheme.isNotEmpty()) { "not absolute" }
    val afterScheme = base.substringAfter("://")
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    if (ref.startsWith("//")) return "$scheme:$ref"
    val rest = afterScheme.substring(authority.length)
    val basePath = rest.substringBefore('?').substringBefore('#').ifEmpty { "/" }
    if (ref.isEmpty()) return base.substringBefore('#')
    if (ref.startsWith("#")) return base.substringBefore('#') + ref
    if (ref.startsWith("?")) return "$scheme://$authority$basePath$ref"
    val path = if (ref.startsWith("/")) ref else basePath.substringBeforeLast('/') + "/" + ref
    val pathOnly = path.substringBefore('?').substringBefore('#')
    val tail = path.substring(pathOnly.length)
    val segs = mutableListOf<String>()
    for (p in pathOnly.split('/').drop(1)) when (p) {
        "." -> {}
        ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.size - 1)
        else -> segs.add(p)
    }
    val endsDir = pathOnly.endsWith("/.") || pathOnly.endsWith("/..")
    return "$scheme://$authority/" + segs.joinToString("/") + (if (endsDir) "/" else "") + tail
}

/** The class name of [e], for diagnostics. */
fun errName(e: Throwable): String = e::class.simpleName ?: "Error"
