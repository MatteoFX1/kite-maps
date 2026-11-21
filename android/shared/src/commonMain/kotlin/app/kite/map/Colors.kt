package app.kite.map

/** Android-style ARGB colour ints, as the map code has always used them. */
object Color {
    const val WHITE = -0x1
    const val BLACK = -0x1000000
    const val GRAY = -0x777778
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    fun rgb(r: Int, g: Int, b: Int) = argb(255, r, g, b)
}

/** A rectangle by its edges, like Android's RectF. */
class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width() = right - left
    fun height() = bottom - top
    fun rect() = androidx.compose.ui.geometry.Rect(left, top, right, bottom)
}

