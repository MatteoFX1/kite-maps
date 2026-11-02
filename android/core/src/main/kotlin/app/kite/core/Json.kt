package app.kite.core

/**
 * A small JSON reader and writer that gives plain Kotlin values, the way the iPhone app's
 * JSONSerialization does: objects are `Map<String, Any?>`, arrays `List<Any?>`, numbers `Long`
 * (whole) or `Double`, plus `String`, `Boolean` and null. Google's answers are deep positional
 * arrays with no field names, so walking them by index is the natural way to read them.
 */
object Json {
    class Bad(msg: String) : Exception(msg)

    /** The value in [text], or null when it isn't JSON. */
    fun parse(text: String): Any? = try {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) null else v
    } catch (e: Exception) {
        null
    }

    fun parse(bytes: ByteArray): Any? = parse(String(bytes, Charsets.UTF_8))

    /** The object in [text], or null. */
    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?>? = parse(text) as? Map<String, Any?>

    fun obj(bytes: ByteArray): Map<String, Any?>? = obj(String(bytes, Charsets.UTF_8))

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, out: StringBuilder) {
        when (v) {
            null -> out.append("null")
            is String -> quote(v, out)
            is Boolean -> out.append(v)
            is Int, is Long, is Short -> out.append(v)
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) out.append("null")
                else if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append(d.toLong()) else out.append(d)
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) out.append(',')
                    first = false
                    quote(k.toString(), out)
                    out.append(':')
                    write(value, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (e in v) {
                    if (!first) out.append(',')
                    first = false
                    write(e, out)
                }
                out.append(']')
            }
            is Array<*> -> write(v.toList(), out)
            is DoubleArray -> write(v.toList(), out)
            else -> quote(v.toString(), out)
        }
    }

    private fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw Bad("end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw Bad("char $c at $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw Bad("literal")
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw Bad("key")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw Bad("colon")
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) throw Bad("end")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw Bad("object")
            }
        }

        fun arr(): List<Any?> {
            val a = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                ws()
                a.add(value())
                ws()
                if (i >= s.length) throw Bad("end")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return a }
                throw Bad("array")
            }
        }

        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) throw Bad("string end")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n')
                            'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw Bad("escape")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            var frac = false
            while (i < s.length) {
                val c = s[i]
                if (c in '0'..'9') i++
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { frac = true; i++ }
                else break
            }
            val t = s.substring(start, i)
            if (!frac) t.toLongOrNull()?.let { return it }
            return t.toDouble()
        }
    }
}

// Reading values the way the Swift code's `as?` casts do.

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asList(): List<Any?>? = this as? List<Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asMaps(): List<Map<String, Any?>>? = (this as? List<Any?>)?.filterIsInstance<Map<String, Any?>>()?.let {
    if (it.size == (this as List<*>).size) it else null
}

/** A number as a Double (JSON whole numbers too); null for anything else. */
fun Any?.num(): Double? = (this as? Number)?.toDouble()

fun Any?.long(): Long? = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is Double -> if (this == Math.rint(this)) this.toLong() else null
    else -> null
}

fun Any?.int(): Int? = long()?.toInt()

/** A string value; OSM `tags` hold only strings. */
fun Any?.text(): String? = this as? String

fun Map<String, Any?>.tags(): Map<String, String> =
    (this["tags"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }?.toMap() ?: emptyMap()

/** Google's positional answers: a missing or moved index gives null instead of a crash. */
fun at(v: Any?, vararg path: Int): Any? {
    var cur = v
    for (i in path) {
        val a = cur as? List<*> ?: return null
        if (i < 0 || i >= a.size) return null
        cur = a[i]
    }
    return cur
}

fun atPath(v: Any?, path: List<Int>): Any? = at(v, *path.toIntArray())

fun arr(v: Any?): List<Any?>? = v.asList()

fun gstr(v: Any?): String? = when (v) {
    is String -> v
    is Long -> v.toString()
    is Double -> if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    else -> null
}

fun gdbl(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

fun gint(v: Any?): Int? = when (v) {
    is Long -> v.toInt()
    is Double -> v.toInt()
    is String -> v.toIntOrNull()
    else -> null
}
