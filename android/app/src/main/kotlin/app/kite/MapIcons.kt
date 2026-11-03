package app.kite

import android.graphics.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import app.kite.core.Place
import app.kite.core.WazeAlert
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory

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

    private val density get() = KiteApp.app.resources.displayMetrics.density

    /** A bitmap [w] x [h] dp, drawn in dp units, at the screen's density. */
    private fun image(w: Float, h: Float, draw: (Canvas) -> Unit): Bitmap {
        val d = density
        val b = Bitmap.createBitmap((w * d).toInt().coerceAtLeast(1), (h * d).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        b.density = KiteApp.app.resources.displayMetrics.densityDpi
        val cv = Canvas(b)
        cv.scale(d, d)
        draw(cv)
        return b
    }

    private fun paint(color: Int, style: Paint.Style = Paint.Style.FILL, width: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; this.style = style; strokeWidth = width; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    /** Draws a Material icon (24-unit art) filling [r]. */
    fun drawIcon(cv: Canvas, icon: ImageVector, r: RectF, color: Int) {
        val p = paint(color)
        cv.save()
        cv.translate(r.left, r.top)
        cv.scale(r.width() / icon.viewportWidth, r.height() / icon.viewportHeight)
        fun walk(g: VectorGroup) {
            for (n in g) when (n) {
                is VectorPath -> {
                    val path = PathParser().addPathNodes(n.pathData).toPath().asAndroidPath()
                    path.fillType = if (n.pathFillType == androidx.compose.ui.graphics.PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
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
        style.addImage("bubble-sel", bubble(c(0x0F766E), Color.WHITE))
        style.addImage("bubble-alt", bubble(Color.WHITE, c(0xBDC1C6)))
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

    fun turnHead() = image(44f, 40f) { cv ->
        val p = Path().apply { moveTo(22f, 6f); lineTo(38f, 32f); lineTo(6f, 32f); close() }
        // A soft shadow drawn by hand (a paint's own shadow comes out wrong on some phones' maps).
        cv.save(); cv.translate(0f, 2f)
        cv.drawPath(p, paint(c(0x000000, 0.18f), Paint.Style.STROKE, 11f))
        cv.restore()
        cv.drawPath(p, paint(c(0x0F766E), Paint.Style.STROKE, 8f))
        cv.drawPath(p, paint(Color.WHITE))
        cv.drawPath(p, paint(Color.WHITE, Paint.Style.STROKE, 2.5f))
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
        val arm = paint(Color.WHITE, Paint.Style.STROKE, 2.4f).apply { strokeCap = Paint.Cap.BUTT }
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
    private fun badge(color: Int, glyph: (Canvas, Int) -> Unit) = image(34.5f, 34.5f) { cv ->
        cv.scale(0.75f, 0.75f)
        cv.drawRoundRect(RectF(2f, 2f, 44f, 44f), 11f, 11f, paint(Color.WHITE))
        cv.drawRoundRect(RectF(4.5f, 4.5f, 41.5f, 41.5f), 9f, 9f, paint(color))
        glyph(cv, color)
    }

    private fun dot(icon: ImageVector, color: Int) = image(28f, 28f) { cv ->
        val r = RectF(1.5f, 1.5f, 26.5f, 26.5f)
        cv.drawOval(r, paint(color))
        cv.drawOval(r, paint(Color.WHITE, Paint.Style.STROKE, 2f))
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
        cv.drawPath(p, paint(c(0xA50E0E), Paint.Style.STROKE, 1.5f))
        cv.drawOval(RectF(12f, 12f, 22f, 22f), paint(c(0x7A0B0B)))
    }

    private fun originDot() = image(22f, 22f) { cv ->
        cv.drawOval(RectF(1f, 1f, 21f, 21f), paint(Color.WHITE))
        cv.drawOval(RectF(4f, 4f, 18f, 18f), paint(c(0x14B8A6), Paint.Style.STROKE, 4f))
    }

    /** The car while navigating, Waze-style in Kite's teal. */
    fun puck(dark: Boolean) = image(76f, 76f) { cv ->
        val disc = RectF(6f, 6f, 70f, 70f)
        cv.drawOval(disc, paint(if (dark) c(0x0F172A, 0.6f) else c(0xFFFFFF, 0.75f)))
        cv.drawOval(disc, paint(c(0x2DD4BF, 0.75f), Paint.Style.STROKE, 2.5f))
        val outline = Path().apply { moveTo(38f, 13f); lineTo(58f, 60f); lineTo(38f, 50f); lineTo(18f, 60f); close() }
        cv.save(); cv.translate(0f, 2f)
        cv.drawPath(outline, paint(c(0x000000, 0.2f), Paint.Style.STROKE, 8f))
        cv.restore()
        cv.drawPath(outline, paint(Color.WHITE, Paint.Style.STROKE, 5f))
        cv.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(38f, 13f, 52f, 60f, c(0x99F6E4), c(0x0D9488), Shader.TileMode.CLAMP)
        })
        val shade = Path().apply { moveTo(38f, 13f); lineTo(58f, 60f); lineTo(38f, 50f); close() }
        cv.drawPath(shade, paint(c(0x064E3B, 0.3f)))
    }

    /** A chevron along the route line, pointing the way you go. */
    fun routeChevron() = image(14f, 16f) { cv ->
        val p = Path().apply { moveTo(4f, 3f); lineTo(10f, 8f); lineTo(4f, 13f) }
        cv.drawPath(p, paint(c(0x0B3B36), Paint.Style.STROKE, 3f))
    }

    /** A pill for the route time bubbles; MapLibre stretches its middle to fit the text. */
    private fun bubble(fill: Int, stroke: Int) = image(30f, 26f) { cv ->
        val r = RectF(1f, 1f, 29f, 25f)
        cv.drawRoundRect(r, 12f, 12f, paint(fill))
        cv.drawRoundRect(r, 12f, 12f, paint(stroke, Paint.Style.STROKE, 1.5f))
    }

    /** The puck again, for after a light/dark switch. */
    fun swapPuck(style: Style, dark: Boolean) { style.addImage("puck", puck(dark)) }

    @Suppress("unused") private val keep = PropertyFactory::class
}
