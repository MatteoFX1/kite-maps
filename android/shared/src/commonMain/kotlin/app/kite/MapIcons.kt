package app.kite

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color as CColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import app.kite.core.Place
import app.kite.core.WazeAlert
import app.kite.map.Color
import app.kite.map.RectF
import app.kite.map.Style

/** Drawing onto an icon, with Android Canvas's names so the icon code reads as before. */
class IconCanvas internal constructor(private val c: androidx.compose.ui.graphics.Canvas) {
    fun save() = c.save()
    fun restore() = c.restore()
    fun translate(x: Float, y: Float) = c.translate(x, y)
    fun scale(x: Float, y: Float) = c.scale(x, y)
    fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint) = c.drawRoundRect(r.left, r.top, r.right, r.bottom, rx, ry, p)
    fun drawCircle(x: Float, y: Float, radius: Float, p: Paint) = c.drawCircle(Offset(x, y), radius, p)
    fun drawOval(r: RectF, p: Paint) = c.drawOval(r.left, r.top, r.right, r.bottom, p)
    fun drawRect(r: RectF, p: Paint) = c.drawRect(r.left, r.top, r.right, r.bottom, p)
    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) = c.drawLine(Offset(x0, y0), Offset(x1, y1), p)
    fun drawPath(path: Path, p: Paint) = c.drawPath(path, p)
}

fun Path.arcTo(r: RectF, start: Float, sweep: Float, forceMoveTo: Boolean) = arcTo(r.rect(), start, sweep, forceMoveTo)

/** The images Kite's map layers draw: category pins, the red picked pin, the puck, route bubbles. */
object MapIcons {
    data class Kind(val name: String, val icon: ImageVector, val color: Int, val words: List<String>)

    private fun c(hex: Long, a: Float = 1f) = Color.argb((a * 255).toInt(), ((hex shr 16) and 0xFF).toInt(), ((hex shr 8) and 0xFF).toInt(), (hex and 0xFF).toInt())

    val kinds = listOf(
        Kind("cat-coffee", Icons.Filled.LocalCafe, c(0xE8710A), listOf("coffee", "cafe", "café", "bakery", "tea", "donut")),
        Kind("cat-food", Icons.Filled.Restaurant, c(0xE8453C), listOf("restaurant", "food", "pizza", "sushi", "burger", "grill", "bar", "pub", "diner", "taco", "bistro", "steak", "ramen", "noodle", "kitchen", "eatery", "sandwich", "chicken")),
        Kind("cat-gas", Icons.Filled.LocalGasStation, c(0x1A73E8), listOf("gas", "fuel", "petrol", "charging")),
        Kind("cat-shop", Icons.Filled.ShoppingCart, c(0x1A73E8), listOf("grocery", "supermarket", "store", "shop", "market", "mall", "pharmacy", "drugstore")),
        Kind("cat-hotel", Icons.Filled.Hotel, c(0xD01884), listOf("hotel", "motel", "inn", "lodging", "hostel", "resort")),
        Kind("cat-park", Icons.Filled.Park, c(0x188038), listOf("park", "garden", "trail", "playground", "beach")),
        Kind("cat-health", Icons.Filled.MedicalServices, c(0xC5221F), listOf("hospital", "clinic", "doctor", "dentist", "medical", "urgent")),
        Kind("cat-transit", Icons.Filled.Tram, c(0x12B5A5), listOf("station", "bus", "subway", "metro", "train", "airport")),
        Kind("cat-money", Icons.Filled.Payments, c(0x5F6368), listOf("atm", "bank")),
        Kind("cat-parking", Icons.Filled.LocalParking, c(0x1A73E8), listOf("parking", "garage")),
    )

    fun iconName(p: Place): String {
        val cat = (p.category ?: "").lowercase()
        return kinds.firstOrNull { k -> k.words.any { cat.contains(it) } }?.name ?: "cat-default"
    }

    /** The screen's density (pixels per dp), set by the map when it starts. */
    var density = 2.75f

    /** A bitmap [w] x [h] dp, drawn in dp units, at the screen's density. */
    private fun image(w: Float, h: Float, draw: (IconCanvas) -> Unit): ImageBitmap {
        val d = density
        val b = ImageBitmap((w * d).toInt().coerceAtLeast(1), (h * d).toInt().coerceAtLeast(1))
        val cv = IconCanvas(androidx.compose.ui.graphics.Canvas(b))
        cv.scale(d, d)
        draw(cv)
        return b
    }

    private enum class Fill { FILL, STROKE }

    private fun paint(color: Int, style: Fill = Fill.FILL, width: Float = 0f) = Paint().apply {
        isAntiAlias = true
        this.color = CColor(color); this.style = if (style == Fill.STROKE) PaintingStyle.Stroke else PaintingStyle.Fill
        strokeWidth = width; strokeJoin = StrokeJoin.Round; strokeCap = StrokeCap.Round
    }

    /** Draws a Material icon (24-unit art) filling [r]. */
    fun drawIcon(cv: IconCanvas, icon: ImageVector, r: RectF, color: Int) {
        val p = paint(color)
        cv.save()
        cv.translate(r.left, r.top)
        cv.scale(r.width() / icon.viewportWidth, r.height() / icon.viewportHeight)
        fun walk(g: VectorGroup) {
            for (n in g) when (n) {
                is VectorPath -> {
                    val path = PathParser().addPathNodes(n.pathData).toPath()
                    path.fillType = n.pathFillType
                    cv.drawPath(path, p)
                }
                is VectorGroup -> walk(n)
            }
        }
        walk(icon.root)
        cv.restore()
    }

    fun register(style: Style, dark: Boolean) {
        for (k in kinds) style.addImage(k.name, dot(k.icon, k.color))
        style.addImage("cat-default", dot(Icons.Filled.Place, c(0xE8453C)))
        style.addImage("pin-selected", selectedPin())
        style.addImage("pin-origin", originDot())
        style.addImage("puck", puck(dark))
        style.addImage("me-dot", meDot())
        style.addImage("me-heading", meHeading())
        style.addImage("bubble-sel", bubble(c(0x0F766E), Color.WHITE))
        style.addImage("bubble-alt", bubble(Color.WHITE, c(0xBDC1C6)))
        style.addImage("road-pill", bubble(c(0x2DD4BF), c(0x0F766E)))
        style.addImage("cam-speed", speedCamera())
        style.addImage("cam-plate", plateCamera())
        style.addImage("cam-surveillance", badge(c(0x22863A)) { cv, _ -> drawIcon(cv, Icons.Filled.Videocam, RectF(12f, 12f, 34f, 34f), Color.WHITE) })
        style.addImage("traffic-light", trafficLight())
        // Live camera photos: sky blue for road cameras, violet for Windy's scenic webcams.
        style.addImage("livecam-road", dot(Icons.Filled.Videocam, c(0x0284C7)))
        style.addImage("livecam-web", dot(Icons.Filled.Landscape, c(0x7C3AED)))
        for (k in WazeAlert.Kind.entries) style.addImage("waze-${k.key}", waze(k))
        style.addImage("turn-head", turnHead())
        style.addImage("route-chevron", routeChevron())
        style.addImage("pin-stop", dot(Icons.Filled.RadioButtonChecked, c(0x5F6368)))
        style.addImage("mine-home", dot(Icons.Filled.Home, c(0x1A73E8)))
        style.addImage("mine-work", dot(Icons.Filled.Work, c(0x8D6E63)))
        style.addImage("mine-parking", dot(Icons.Filled.LocalParking, c(0x1967D2)))
        style.addImage("mine-saved", dot(Icons.Filled.Bookmark, c(0x0F9D58)))
    }

    /** The head of the turn arrow drawn on the road: big and white with a teal edge, pointing up. */
    fun turnHead() = image(64f, 60f) { cv ->
        val p = Path().apply { moveTo(32f, 7f); lineTo(57f, 46f); lineTo(7f, 46f); close() }
        // A soft shadow drawn by hand (a paint's own shadow comes out wrong on some phones' maps).
        cv.save(); cv.translate(0f, 2f)
        cv.drawPath(p, paint(c(0x000000, 0.18f), Fill.STROKE, 14f))
        cv.restore()
        cv.drawPath(p, paint(c(0x0F766E), Fill.STROKE, 8f))
        cv.drawPath(p, paint(Color.WHITE))
    }

    fun speedCamera() = badge(c(0xEF6C00)) { cv, amber ->
        cv.drawRoundRect(RectF(15f, 12f, 31f, 32f), 3f, 3f, paint(Color.WHITE))
        cv.drawCircle(23f, 20f, 4.6f, paint(amber))
        cv.drawCircle(23f, 20f, 2f, paint(Color.WHITE))
        cv.drawRect(RectF(18f, 26.5f, 28f, 29.5f), paint(amber))
    }

    fun trafficLight() = image(14f, 30f) { cv ->
        cv.drawRoundRect(RectF(0f, 0f, 14f, 30f), 5f, 5f, paint(Color.WHITE))
        cv.drawRoundRect(RectF(1.5f, 1.5f, 12.5f, 28.5f), 4f, 4f, paint(c(0x263238)))
        listOf(0xFF3B30L, 0xFFB300L, 0x34C759L).forEachIndexed { i, col -> cv.drawOval(RectF(3.5f, 3.5f + i * 8, 10.5f, 10.5f + i * 8), paint(c(col))) }
    }

    fun plateCamera() = badge(c(0x7B1FA2)) { cv, purple ->
        val w = paint(Color.WHITE)
        cv.drawRoundRect(RectF(12f, 18f, 30f, 27f), 2f, 2f, w)
        cv.drawCircle(31f, 22.5f, 4.2f, w)
        cv.drawCircle(31f, 22.5f, 2f, paint(purple))
        val arm = paint(Color.WHITE, Fill.STROKE, 2.4f).apply { strokeCap = StrokeCap.Butt }
        cv.drawLine(17f, 18f, 17f, 12f, arm)
        cv.drawLine(12f, 12f, 24f, 12f, arm)
    }

    fun wazeIcon(k: WazeAlert.Kind): ImageVector = when (k) {
        WazeAlert.Kind.POLICE -> Icons.Filled.Shield
        WazeAlert.Kind.ACCIDENT -> Icons.Filled.CarCrash
        WazeAlert.Kind.HAZARD -> Icons.Filled.Warning
        WazeAlert.Kind.WEATHER -> Icons.Filled.Cloud
        WazeAlert.Kind.ROADWORKS -> Icons.Filled.Construction
        WazeAlert.Kind.CLOSURE -> Icons.Filled.Block
        WazeAlert.Kind.JAM -> Icons.Filled.Traffic
    }

    fun wazeColor(k: WazeAlert.Kind): Long = when (k) {
        WazeAlert.Kind.POLICE -> 0x1E88E5
        WazeAlert.Kind.ACCIDENT, WazeAlert.Kind.JAM -> 0xD93025
        WazeAlert.Kind.HAZARD -> 0xF29900
        WazeAlert.Kind.WEATHER -> 0x00897B
        WazeAlert.Kind.ROADWORKS -> 0xE8710A
        WazeAlert.Kind.CLOSURE -> 0xB71C1C
    }

    fun waze(k: WazeAlert.Kind) = badge(c(wazeColor(k))) { cv, _ -> drawIcon(cv, wazeIcon(k), RectF(12f, 12f, 34f, 34f), Color.WHITE) }

    /** A 46-unit badge drawn at 34.5 dp: white rim, colored field, then the glyph. */
    private fun badge(color: Int, glyph: (IconCanvas, Int) -> Unit) = image(34.5f, 34.5f) { cv ->
        cv.scale(0.75f, 0.75f)
        cv.drawRoundRect(RectF(2f, 2f, 44f, 44f), 11f, 11f, paint(Color.WHITE))
        cv.drawRoundRect(RectF(4.5f, 4.5f, 41.5f, 41.5f), 9f, 9f, paint(color))
        glyph(cv, color)
    }

    private fun dot(icon: ImageVector, color: Int) = image(28f, 28f) { cv ->
        val r = RectF(1.5f, 1.5f, 26.5f, 26.5f)
        cv.drawOval(r, paint(color))
        cv.drawOval(r, paint(Color.WHITE, Fill.STROKE, 2f))
        drawIcon(cv, icon, RectF(7.5f, 7.5f, 20.5f, 20.5f), Color.WHITE)
    }

    private fun selectedPin() = image(34f, 46f) { cv ->
        val p = Path().apply {
            moveTo(17f, 44f)
            cubicTo(13f, 37f, 6f, 29f, 3.5f, 23f)
            arcTo(RectF(2f, 2f, 32f, 32f), 154.8f, 230.4f, false)
            cubicTo(28f, 29f, 21f, 37f, 17f, 44f)
            close()
        }
        cv.drawPath(p, paint(c(0xEA4335)))
        cv.drawPath(p, paint(c(0xA50E0E), Fill.STROKE, 1.5f))
        cv.drawOval(RectF(12f, 12f, 22f, 22f), paint(c(0x7A0B0B)))
    }

    private fun originDot() = image(22f, 22f) { cv ->
        cv.drawOval(RectF(1f, 1f, 21f, 21f), paint(Color.WHITE))
        cv.drawOval(RectF(4f, 4f, 18f, 18f), paint(c(0x14B8A6), Fill.STROKE, 4f))
    }

    /** The car while navigating, in Kite's teal: just the arrow, with no disc or ring around it (the light look). */
    fun puck(@Suppress("UNUSED_PARAMETER") dark: Boolean) = image(76f, 76f) { cv ->
        val outline = Path().apply { moveTo(38f, 13f); lineTo(58f, 60f); lineTo(38f, 50f); lineTo(18f, 60f); close() }
        cv.save(); cv.translate(0f, 2f)
        cv.drawPath(outline, paint(c(0x000000, 0.2f), Fill.STROKE, 8f))
        cv.restore()
        cv.drawPath(outline, paint(Color.WHITE, Fill.STROKE, 5f))
        cv.drawPath(outline, Paint().apply {
            isAntiAlias = true
            shader = LinearGradientShader(Offset(38f, 13f), Offset(52f, 60f), listOf(CColor(c(0x99F6E4)), CColor(c(0x0D9488))))
        })
        val shade = Path().apply { moveTo(38f, 13f); lineTo(58f, 60f); lineTo(38f, 50f); close() }
        cv.drawPath(shade, paint(c(0x064E3B, 0.3f)))
    }

    /** You, off a route: Kite's teal dot in a white ring, with a soft shadow. */
    fun meDot() = image(26f, 26f) { cv ->
        cv.drawOval(RectF(2f, 3f, 24f, 25f), paint(c(0x000000, 0.18f)))
        cv.drawOval(RectF(2f, 2f, 24f, 24f), paint(Color.WHITE))
        cv.drawOval(RectF(5.5f, 5.5f, 20.5f, 20.5f), paint(c(0x14B8A6)))
    }

    /** The pointer round your dot, the way you're going (drawn pointing up, the map turns it). */
    fun meHeading() = image(48f, 48f) { cv ->
        val p = Path().apply { moveTo(24f, 2f); lineTo(32f, 14f); quadraticTo(24f, 10f, 16f, 14f); close() }
        cv.drawPath(p, paint(c(0x14B8A6)))
    }

    /** A chevron along the route line, pointing the way you go. */
    fun routeChevron() = image(14f, 16f) { cv ->
        val p = Path().apply { moveTo(4f, 3f); lineTo(10f, 8f); lineTo(4f, 13f) }
        cv.drawPath(p, paint(c(0x0B3B36), Fill.STROKE, 3f))
    }

    /** A pill for the route time bubbles; MapLibre stretches its middle to fit the text. */
    private fun bubble(fill: Int, stroke: Int) = image(30f, 26f) { cv ->
        val r = RectF(1f, 1f, 29f, 25f)
        cv.drawRoundRect(r, 12f, 12f, paint(fill))
        cv.drawRoundRect(r, 12f, 12f, paint(stroke, Fill.STROKE, 1.5f))
    }

    /** The puck again, for after a light/dark switch. */
    fun swapPuck(style: Style, dark: Boolean) { style.addImage("puck", puck(dark)) }
}
