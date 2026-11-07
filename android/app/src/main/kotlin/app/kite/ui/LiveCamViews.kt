package app.kite.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kite.*
import app.kite.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A live camera's photo, loaded when shown; [reload] asks again. The same as the iPhone's loader. */
class LiveCamPhotoState {
    var bitmap by mutableStateOf<Bitmap?>(null)
    var taken by mutableStateOf<Long?>(null)
    var failed by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    private var camId: String? = null

    suspend fun load(cam: LiveCam, force: Boolean = false) {
        if (!force && camId == cam.id && bitmap != null) return
        if (camId != cam.id) { bitmap = null; taken = null; failed = false }
        camId = cam.id
        refreshing = true
        val current = LiveCamStore.current(cam)
        val got = LiveCamStore.photo(current)
        if (camId != cam.id) return
        refreshing = false
        if (got != null) { bitmap = got.first; taken = got.second; failed = false } else if (bitmap == null) failed = true
    }
}

private val roadBlue = Color(0xFF0284C7)
private val webViolet = Color(0xFF7C3AED)

/** How old a photo is, as a little pill: green when fresh, amber after 20 minutes, grey when it's likely stuck. */
@Composable
fun PhotoAgePill(taken: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val age = taken?.let { maxOf(0L, now - it) / 1000.0 }
    val label = when {
        age == null -> "Latest photo"
        age < 90 -> "Just now"
        age < 3600 -> "${(age / 60).toInt()} min ago"
        age < 86_400 -> "${(age / 3600).toInt()} h ago"
        else -> "${(age / 86_400).toInt()} days ago"
    }
    val dot = when {
        age == null || age < 1200 -> Color(0xFF22C55E)
        age < 7200 -> Color(0xFFF59E0B)
        else -> Color(0xFF9CA3AF)
    }
    Row(Modifier.height(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.62f)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** The photo itself in a 16:9 frame, with a spinner while it loads and a calm note when it can't. */
@Composable
fun LiveCamPhoto(state: LiveCamPhotoState, modifier: Modifier = Modifier, corner: Int = 14, showsAge: Boolean = true, retry: (() -> Unit)? = null) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(corner.dp)).background(Color(0xFF1E293B)), contentAlignment = Alignment.Center) {
        val bmp = state.bitmap
        when {
            bmp != null -> {
                Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (showsAge) Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { PhotoAgePill(state.taken) }
            }
            state.failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.VideocamOff, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(26.dp))
                Text("No photo right now", color = Color.White.copy(alpha = 0.85f), fontSize = KType.footnote, fontWeight = FontWeight.SemiBold)
                if (retry != null) Text("Try again", color = Color(0xFF5EEAD4), fontSize = KType.footnote, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).tap(retry).padding(horizontal = 8.dp, vertical = 4.dp))
            }
            else -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
        }
    }
}

/**
 * The card for a camera tapped on the map, or picked from a route's row: the photo, big, with
 * how old it is, the camera's name and road, who it's from, and a full-screen view.
 */
@Composable
fun LiveCamCard(model: AppModel, cam: LiveCam) {
    val dark = LocalDark.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { LiveCamPhotoState() }
    var fullScreen by remember { mutableStateOf(false) }
    LaunchedEffect(cam.id) {
        state.load(cam)
        // A fresh photo every minute while the card is open; most cameras update every 1 to 5 minutes.
        while (true) { delay(60_000); state.load(cam, force = true) }
    }
    val list = model.liveCamRoute
    val index = list.indexOfFirst { it.id == cam.id }.takeIf { it >= 0 }
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 58.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).padding(horizontal = 12.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (cam.source.isRoad) roadBlue else webViolet), contentAlignment = Alignment.Center) {
                    Icon(if (cam.source.isRoad) Icons.Filled.Videocam else Icons.Filled.Landscape, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(cam.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (cam.detail.isNotEmpty()) Text(cam.detail, fontSize = KType.footnote, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.Close, "Close", tint = secondaryText,
                    modifier = Modifier.size(30.dp).clip(CircleShape).background(Vela.chip(dark)).tap { model.liveCam = null }.padding(6.dp))
            }
            Box {
                LiveCamPhoto(state, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).tap { if (state.bitmap != null) fullScreen = true },
                    retry = { scope.launch { state.load(cam, force = true) } })
                if (state.bitmap != null) Icon(Icons.Filled.OpenInFull, "Full screen", tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(7.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Photo: ${cam.source.credit}", fontSize = KType.caption2, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                cam.moreUrl?.let { more ->
                    Row(Modifier.height(34.dp).clip(CircleShape).background(Vela.chip(dark)).tap { context.open(more) }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Filled.PlayArrow, null, tint = primaryText, modifier = Modifier.size(16.dp))
                        Text(if (cam.source == LiveCam.Source.WINDY) "Timelapse" else "Video", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = primaryText)
                    }
                }
                val spin = rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f,
                    infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "spin")
                Icon(Icons.Filled.Refresh, "Refresh photo", tint = primaryText,
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(Vela.chip(dark))
                        .tap { scope.launch { state.load(cam, force = true) } }.padding(8.dp).rotate(if (state.refreshing) spin.value else 0f))
            }
            if (index != null && list.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous camera", index > 0) { model.liveCam = list[index - 1] }
                    Text("Camera ${index + 1} of ${list.size} on your route", fontSize = KType.footnote, fontWeight = FontWeight.SemiBold,
                        color = secondaryText, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    StepButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next camera", index < list.size - 1) { model.liveCam = list[index + 1] }
                }
            }
        }
    }
    if (fullScreen) LiveCamFullScreen(cam, state) { fullScreen = false }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, action: () -> Unit) {
    Box(Modifier.size(width = 40.dp, height = 34.dp).clip(CircleShape).background(Vela.chip(LocalDark.current))
        .then(if (enabled) Modifier.tap(action) else Modifier).graphicsLayer { alpha = if (enabled) 1f else 0.35f },
        contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = primaryText, modifier = Modifier.size(22.dp))
    }
}

/** The photo filling the screen: pinch to zoom, double tap to zoom in, back or the cross to close. */
@Composable
fun LiveCamFullScreen(cam: LiveCam, state: LiveCamPhotoState, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            state.bitmap?.let { bmp ->
                Image(bmp.asImageBitmap(), cam.name, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f })
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(cam.name, color = Color.White, fontSize = KType.headline, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhotoAgePill(state.taken)
                        Text(cam.source.credit, color = Color.White.copy(alpha = 0.7f), fontSize = KType.caption2)
                    }
                }
                Icon(Icons.Filled.Close, "Close", tint = Color.White,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).tap(close).padding(9.dp))
            }
        }
    }
}

/**
 * Before pressing Go: the road cameras and webcams along the route, in the order you'll pass them,
 * so you can see the traffic, rain or snow ahead. Hidden when there are none.
 */
@Composable
fun RouteLiveCams(model: AppModel, route: Route) {
    val s = AppSettings
    if (!s.liveCams || !s.liveCamsOnRoute) return
    val webcams = s.showsWebcams
    LaunchedEffect(route.id, webcams) {
        LiveCamStore.ensureLoaded()
        if (webcams) LiveCamStore.loadWindyRoute(route.id, route.polyline)
    }
    val version = LiveCamStore.version
    val list = remember(route.id, version, webcams) {
        val web = if (webcams) LiveCamStore.webcams else emptyList()
        LiveCams.along(LiveCamStore.road + web, route.polyline, webcamOffset = if (web.isEmpty()) 0.0 else LiveCams.WEBCAM_ROUTE_OFFSET)
    }
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Videocam, null, tint = roadBlue, modifier = Modifier.size(20.dp))
            Text("Cameras on your route", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = primaryText)
            Text("${list.size}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = secondaryText)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (item in list) {
                RouteCamThumb(item) {
                    model.liveCamRoute = list.map { it.cam }
                    model.liveCam = item.cam
                }
            }
        }
    }
}

@Composable
private fun RouteCamThumb(item: LiveCams.OnRoute, open: () -> Unit) {
    val state = remember(item.cam.id) { LiveCamPhotoState() }
    LaunchedEffect(item.cam.id) { state.load(item.cam) }
    Column(Modifier.width(168.dp).clip(RoundedCornerShape(12.dp)).tap(open), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            LiveCamPhoto(state, Modifier.fillMaxWidth(), corner = 12, showsAge = false)
            Text(if (item.along < 400) "Start" else "In ${Fmt.distance(item.along)}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 7.dp, vertical = 3.dp))
        }
        Text(item.cam.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
