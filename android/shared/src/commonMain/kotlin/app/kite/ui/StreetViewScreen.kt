package app.kite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.kite.*
import app.kite.core.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.ceil

/** Google Street View, full screen: drag to look around, pinch to zoom. Google Maps can take you walking down the street from here. */
@Composable
fun StreetViewScreen(req: StreetViewRequest, close: () -> Unit) {
    // Drawn over the whole app (not a separate window), so it fills the screen edge to edge, under the status bar too.
    androidx.compose.ui.backhandler.BackHandler(onBack = close)
    val state = remember(req.pano.id) { PanoState(req.pano, req.heading) }
    LaunchedEffect(state) { state.load() }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { }) {
        PanoramaSurface(state, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ -> state.gesture(pan.x, pan.y, zoom, size.width.toFloat()) }
        })
        // The close button and the place's name over the panorama.
        Row(Modifier.statusBarsPadding().padding(horizontal = 12.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).tap(close), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(req.name, fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Street View", fontSize = KType.caption, color = Color.White.copy(alpha = 0.8f))
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp).height(42.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f)).border(0.75.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .tap { Phone.open("https://www.google.com/maps/@?api=1&map_action=pano&pano=${req.pano.id}&heading=${req.heading.toInt()}") }
            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text("Walk around in Google Maps", fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

/**
 * Where you're looking in a panorama, and its picture: the tiles stitched into one image, a
 * low-detail one first, then a sharp one. Each phone draws it its own fast way ([PanoramaSurface]):
 * every pixel on screen looks up the right spot of the picture (no sphere needed).
 */
class PanoState(val pano: StreetView.Panorama, startHeading: Double) {
    var heading by mutableStateOf(startHeading)
    var pitch by mutableStateOf(0.0)
    var fov by mutableStateOf(80.0)
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    suspend fun load() {
        for (zoom in listOf(2, 3)) {
            // The sharper one only replaces the first if every tile came: a dropped signal
            // mustn't turn a working view half black.
            val img = stitch(zoom, complete = zoom > 2) ?: continue
            image = img
        }
    }

    /** One screen width turns by about the field of view; a drag down looks up, like the iPhone app. */
    fun gesture(dx: Float, dy: Float, zoom: Float, width: Float) {
        val perPx = fov / width.coerceAtLeast(1f)
        heading -= dx * perPx
        pitch = (pitch + dy * perPx).coerceIn(-80.0, 80.0)
        if (zoom != 1f) fov = (fov / zoom).coerceIn(30.0, 100.0)
    }

    /** All of the panorama's tiles at [zoom], drawn into one picture. */
    private suspend fun stitch(zoom: Int, complete: Boolean = false): ImageBitmap? = coroutineScope {
        val (w, h) = pano.size(zoom)
        val cols = ceil(w / 512.0).toInt(); val rows = ceil(h / 512.0).toInt()
        val tiles = (0 until rows).flatMap { y -> (0 until cols).map { x -> Triple(x, y, async(Dispatchers.Default) {
            Http.getOrNull(StreetView.tileUrl(pano, x, y, zoom))?.takeIf { it.ok }?.body?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
        }) } }.mapNotNull { (x, y, d) -> d.await()?.let { Triple(x, y, it) } }
        if (tiles.isEmpty() || (complete && tiles.size < rows * cols)) return@coroutineScope null
        withContext(Dispatchers.Default) {
            val out = ImageBitmap(w, h)
            val cv = Canvas(out)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.Black })
            val paint = Paint()
            for ((x, y, b) in tiles) cv.drawImageRect(b, IntOffset.Zero, IntSize(b.width, b.height),
                IntOffset(x * 512, y * 512), IntSize(512, 512), paint)
            out
        }
    }
}

/** The panorama as seen from [state]'s heading, pitch and field of view, filling the space given. */
@Composable
expect fun PanoramaSurface(state: PanoState, modifier: Modifier)
