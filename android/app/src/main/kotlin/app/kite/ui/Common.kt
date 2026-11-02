@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kite.*
import app.kite.core.*
import kotlin.math.abs

/** A tap target with no ripple flash, for rows that change color themselves. */
fun Modifier.tap(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/**
 * Kite's draggable bottom sheet: peek, half and full heights, like the iPhone app's. Drag it from
 * anywhere: below full height the whole sheet moves; at full height its list scrolls, and
 * pulling down from the top of the list brings the sheet down.
 */
@Composable
fun BottomSheet(
    detent: SheetDetent,
    onDetent: (SheetDetent) -> Unit,
    containerHeight: Dp,
    peekHeight: Dp = 230.dp,
    /** Room kept clear above the sheet at full height (the directions from/to card). */
    topClearance: Dp = 0.dp,
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalDark.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    fun height(d: SheetDetent): Float = with(density) {
        when (d) {
            SheetDetent.PEEK -> peekHeight.toPx()
            SheetDetent.HALF -> containerHeight.toPx() * 0.46f
            SheetDetent.FULL -> if (topClearance > 0.dp) containerHeight.toPx() - topClearance.toPx() else containerHeight.toPx() * 0.94f
        }
    }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val target = height(detent) - drag
    val animated by animateFloatAsState(target, if (dragging) spring(stiffness = Spring.StiffnessHigh * 4) else
        spring(dampingRatio = 0.85f, stiffness = 380f), label = "sheet")
    val top = with(density) { containerHeight.toPx() * 0.96f }
    val current = animated.coerceIn(with(density) { peekHeight.toPx() } * 0.8f, top)

    fun settle(velocity: Float) {
        val end = height(detent) - drag - velocity * 0.18f
        val best = SheetDetent.entries.minBy { abs(height(it) - end) }
        if (best != detent) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        dragging = false
        drag = 0f
        onDetent(best)
    }

    val connection = remember(detent) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Below full height a drag on the list moves the sheet, not the list.
                if (detent != SheetDetent.FULL || drag != 0f) {
                    dragging = true
                    drag += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // At full height, a pull down past the top of the list brings the sheet down.
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    dragging = true
                    drag += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragging) { settle(available.y); return available }
                return Velocity.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (dragging) settle(available.y)
                return Velocity.Zero
            }
        }
    }

    val floating = detent != SheetDetent.FULL
    val shape = RoundedCornerShape(if (floating) 34.dp else 28.dp)
    Column(
        Modifier
            .padding(horizontal = if (floating) 8.dp else 0.dp)
            .padding(bottom = if (floating) 8.dp else 0.dp)
            .fillMaxWidth()
            .height(with(density) { current.toDp() })
            .shadow(14.dp, shape)
            .clip(shape)
            .background(Vela.sheet(dark).copy(alpha = 0.97f))
            .border(0.75.dp, Color.White.copy(alpha = if (dark) 0.12f else 0.5f), shape)
            .nestedScroll(connection)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .draggable(rememberDraggableState { dragging = true; drag += it }, Orientation.Vertical,
                    onDragStarted = { dragging = true }, onDragStopped = { v -> settle(v) })
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                    if (detent == SheetDetent.PEEK) onDetent(SheetDetent.HALF)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.padding(top = 8.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(secondaryText.copy(alpha = 0.45f)))
            Column(Modifier.fillMaxWidth()) { header() }
        }
        Column(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

/** The bar along the bottom: Map, Saved and Settings, each a big target. */
@Composable
fun KiteDock(model: AppModel, modifier: Modifier = Modifier) {
    val dark = LocalDark.current
    Row(
        modifier.navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 10.dp)
            .fillMaxWidth().height(80.dp).glass(RoundedCornerShape(28.dp)),
    ) {
        @Composable
        fun RowScope.item(tab: AppTab, icon: ImageVector, title: String) {
            val on = model.tab == tab
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable {
                    if (tab != model.tab && model.tab == AppTab.SETTINGS) model.reloadRoutes()
                    model.tab = tab
                },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                val c = if (on) Vela.accentText(dark) else Vela.muted(dark)
                Icon(icon, null, tint = c, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(4.dp))
                Text(title, color = c, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
            }
        }
        item(AppTab.MAP, Icons.Filled.Map, "Map")
        item(AppTab.SAVED, Icons.Filled.Bookmark, "Saved")
        item(AppTab.SETTINGS, Icons.Filled.Settings, "Settings")
    }
}

@Composable
fun HeatLegend() {
    Row(Modifier.height(28.dp).glass(CircleShape).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.LocalFireDepartment, null, tint = Color(0xFFDC2626), modifier = Modifier.size(13.dp))
        Text("Police", fontSize = KType.caption2, fontWeight = FontWeight.SemiBold, color = primaryText)
        Box(Modifier.size(54.dp, 6.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Color(0xFFFFD600), Color(0xFFF97316), Color(0xFFDC2626)))))
        Text("often", fontSize = KType.caption2, color = secondaryText)
    }
}

object PoliceRiskColors {
    fun color(l: PoliceRisk.Level) = when (l) {
        PoliceRisk.Level.HIGH -> Color(0xFFDC2626)
        PoliceRisk.Level.MEDIUM -> Color(0xFFF59E0B)
        PoliceRisk.Level.LOW -> Color(0xFF16A34A)
    }
}

fun PoliceRisk.Level.word() = name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
fun PoliceRiskChip(score: Int, label: String = "Police risk") {
    val level = PoliceRisk.level(score)
    val fg = if (level == PoliceRisk.Level.MEDIUM) Color(0xFF1F1300) else Color.White
    Row(Modifier.height(42.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(PoliceRiskColors.color(level)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Shield, null, tint = fg, modifier = Modifier.size(19.dp))
        Text("$label: ${level.word()}", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Bottom left of the map, with Waze on: the heat map's key, the police risk here and how many live reports are in view. */
@Composable
fun WazeMapChips(model: AppModel) {
    val s = AppSettings
    if (!s.wazeOn) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.wazeHeatmap && s.wazePolice) HeatLegend()
        val risk = model.areaRisk
        if (s.policeRisk && risk != null) PoliceRiskChip(risk, "Local report activity")
        val n = model.wazeLiveCount
        if (n != null && (s.wazeOnMap || s.wazeHeatmap)) {
            Row(Modifier.height(34.dp).glass(CircleShape).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                Text("Live from Waze · $n report${if (n == 1) "" else "s"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
            }
        }
    }
}

@Composable
fun NoticeBanner(text: String, dismiss: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().glass(RoundedCornerShape(14.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Info, null, tint = secondaryText, modifier = Modifier.size(18.dp))
        Text(text, fontSize = KType.footnote, color = primaryText, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.Close, "Dismiss", tint = primaryText, modifier = Modifier.size(18.dp).clickable(onClick = dismiss))
    }
}

/** Why the map can't do its job right now: location turned off, or no internet. */
@Composable
fun StatusCards() {
    val ctx = LocalContext.current
    val loc = LocationService
    val fix = loc.last
    when {
        loc.denied || DemoScript.step == "locationoff" -> StatusCard(Icons.Filled.LocationOff, Vela.closed, "Location is off",
            "Kite needs it to show where you are and to guide you.", "Turn on") { LocationService.openSettings(ctx) }
        loc.authorized && loc.reducedAccuracy -> StatusCard(Icons.Filled.MyLocation, Color(0xFFF59E0B), "Precise location is off",
            "Allow precise location for Kite in Settings to improve your position.", "Settings") { LocationService.openSettings(ctx) }
        loc.authorized && fix != null && fix.accuracy > 200 -> StatusCard(Icons.Filled.MyLocation, Color(0xFFF59E0B), "Location is uncertain",
            "GPS accuracy is about ${fix.accuracy.toInt()} m. Move outdoors for a better fix.", null) {}
        !Connectivity.online -> StatusCard(Icons.Filled.WifiOff, Color(0xFFF59E0B), "No internet connection",
            "Search, routes and live traffic need one. The map you've already seen still works.", null) {}
    }
}

@Composable
private fun StatusCard(icon: ImageVector, color: Color, title: String, detail: String, button: String?, action: () -> Unit) {
    val dark = LocalDark.current
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = primaryText)
            Text(detail, fontSize = 15.sp, color = Vela.muted(dark))
        }
        if (button != null) {
            Box(Modifier.height(44.dp).clip(CircleShape).background(Vela.accent(dark)).clickable(onClick = action).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center) {
                Text(button, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Vela.onAccent(dark))
            }
        }
    }
}

/** Red-and-gray needle pointing north. */
@Composable
fun CompassNeedle(heading: Double, onClick: () -> Unit) {
    val gray = secondaryText
    Box(Modifier.size(56.dp).glass(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(10.dp, 28.dp).rotate(-heading.toFloat())) {
            val w = size.width; val h = size.height
            drawPath(Path().apply { moveTo(w / 2, 0f); lineTo(w, h / 2); lineTo(0f, h / 2); close() }, Color(0xFFEA4335))
            drawPath(Path().apply { moveTo(0f, h / 2); lineTo(w, h / 2); lineTo(w / 2, h); close() }, gray)
        }
    }
}

/** Home or Work: one tap for directions, or to set it. */
@Composable
fun PlaceTile(model: AppModel, icon: ImageVector, color: Color, title: String, place: Place?, modifier: Modifier = Modifier,
              unset: () -> Unit, go: () -> Unit = {}) {
    val dark = LocalDark.current
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 74.dp).glass(RoundedCornerShape(22.dp))
                .combinedClickable(onClick = { if (place != null) { go(); model.openDirections(place) } else unset() },
                    onLongClick = { if (place != null) menu = true })
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(if (place == null) Color.Gray else color), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = primaryText, maxLines = 1)
                val d = place?.let { p -> model.userLatLng?.distanceTo(p.location) }
                Text(d?.let { Fmt.distance(it) } ?: if (place == null) "Tap to set" else "Tap to go",
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Vela.muted(dark), maxLines = 1)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Change ${title.lowercase()}") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { menu = false; model.picking = if (title == "Home") PickKind.HOME else PickKind.WORK })
        }
    }
}

/** Home and Work at the top of the search panel. */
@Composable
fun Shortcuts(model: AppModel, done: () -> Unit) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PlaceTile(model, Icons.Filled.Home, Vela.routeBlue, "Home", AppSettings.home, Modifier.weight(1f),
            unset = { done(); model.picking = PickKind.HOME }, go = done)
        PlaceTile(model, Icons.Filled.Work, Color(0xFF8B5CF6), "Work", AppSettings.work, Modifier.weight(1f),
            unset = { done(); model.picking = PickKind.WORK }, go = done)
    }
}

/** A simple row with an icon and text, for lists in panels. */
@Composable
fun IconRow(icon: ImageVector, text: String, sub: String? = null, trailing: String? = null, height: Dp = 52.dp,
            onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = height).combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = secondaryText, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(text, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, fontSize = KType.caption, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, fontSize = KType.caption, color = secondaryText)
    }
}

/**
 * A Dialog that really covers the whole screen. Without this, on phones with a camera hole (Pixel),
 * Android starts the dialog's window below the hole, so its bottom (and any button there) ends up
 * off the screen.
 */
@Composable
fun FullScreenDialog(onDismiss: () -> Unit, dismissOnBack: Boolean = true, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = dismissOnBack)) {
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect {
            window?.let { w ->
                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                if (android.os.Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        content()
    }
}
