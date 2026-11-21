@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import kotlinx.coroutines.IO
import kotlinx.datetime.toLocalDateTime

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kite.*
import app.kite.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/** Route preview: the from/to card on top, routes on the map, the trip sheet below. */
@Composable
fun DirectionsOverlay(model: AppModel) {
    val density = LocalDensity.current
    var cardHeight by remember { mutableStateOf(130.dp) }
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = model.directions
        val full = maxHeight
        Box(Modifier.align(Alignment.BottomCenter)) {
            BottomSheet(model.detent, { model.detent = it }, full, tripPeek(d),
                topClearance = statusTop + 6.dp + cardHeight + 16.dp,
                header = { TripHeader(model) }, content = { TripBody(model) })
        }
        if (d != null) {
            Box(Modifier.statusBarsPadding().padding(top = 6.dp).onSizeChanged { cardHeight = with(density) { it.height.toDp() } }) {
                EndpointsCard(model, d)
            }
            Box(Modifier.statusBarsPadding().padding(top = 6.dp + cardHeight + 14.dp).padding(horizontal = 16.dp).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter) {
                AvoidRoadCard(model, d)
            }
        }
    }
    androidx.compose.ui.backhandler.BackHandler { model.closeDirections() }
}

@Composable
fun EndpointsCard(model: AppModel, state: DirectionsState) {
    val dark = LocalDark.current
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape).tap { model.closeDirections() }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = primaryText, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f)) {
            if (state.reversed) Endpoint(Icons.Outlined.Circle, Vela.tealLight, state.destination.name) else OriginRow(model, state)
            state.stops.forEachIndexed { i, stop ->
                HorizontalDivider(Modifier.padding(start = 30.dp))
                Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(secondaryText), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Text(stop.name, fontSize = KType.body, fontWeight = FontWeight.Medium, color = primaryText, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Close, "Remove stop", tint = secondaryText, modifier = Modifier.size(20.dp).clip(CircleShape).tap { model.removeStop(i) })
                }
            }
            HorizontalDivider(Modifier.padding(start = 30.dp))
            if (state.reversed) OriginRow(model, state) else Endpoint(Icons.Filled.Place, Vela.closed, state.destination.name)
            if (!state.transit && state.stops.size < 8) {
                HorizontalDivider(Modifier.padding(start = 30.dp))
                Row(Modifier.fillMaxWidth().height(44.dp).tap { model.addingStop = true }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Add, null, tint = Vela.accentText(dark), modifier = Modifier.size(18.dp))
                    Text("Add stop", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Vela.accentText(dark))
                }
            }
        }
        Box(Modifier.size(48.dp).clip(CircleShape).background(Vela.chip(dark)).tap { model.reverseDirections() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.SwapVert, "Swap start and end", tint = primaryText, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * "Your location", or the place picked to start from instead: tap to pick one (GitHub #55),
 * ✕ to go back to where you are.
 */
@Composable
private fun OriginRow(model: AppModel, state: DirectionsState) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (state.origin == null) Icons.Filled.MyLocation else Icons.Outlined.Circle, null,
            tint = if (state.reversed) Vela.closed else Vela.tealLight, modifier = Modifier.size(18.dp))
        Text(state.origin?.name ?: "Your location", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = primaryText, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).tap { model.pickingOrigin = true })
        if (state.origin != null) {
            Icon(Icons.Filled.Close, "Start from your location", tint = secondaryText,
                modifier = Modifier.size(20.dp).clip(CircleShape).tap { model.setOrigin(null) })
        }
    }
}

/**
 * Tap the route to avoid a road (GitHub idea, 0.7.17): "Avoid Bassett Avenue?", then while it looks
 * for a way round, then "Avoiding Bassett Avenue" with Undo. Small, under the from/to box.
 */
@Composable
fun AvoidRoadCard(model: AppModel, state: DirectionsState) {
    val dark = LocalDark.current
    val ask = model.avoidAsk
    when {
        ask != null -> Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).glass(RoundedCornerShape(27.dp)).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Block, null, tint = Vela.closed, modifier = Modifier.size(22.dp))
            // Two lines rather than a cut-off name ("Avoid Russell Boule…").
            Text("Avoid ${ask.road ?: "this road"}?", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = primaryText, maxLines = 2, lineHeight = 20.sp,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("Cancel", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = secondaryText, modifier = Modifier.tap { model.avoidAsk = null }.padding(6.dp))
            Box(Modifier.height(40.dp).clip(CircleShape).background(Vela.accent(dark)).tap { model.avoidRoad() }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center) {
                Text("Avoid", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Vela.onAccent(dark))
            }
        }
        model.avoidBusy -> Row(Modifier.height(46.dp).glass(CircleShape).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Finding a way round…", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
        }
        state.avoidNames.isNotEmpty() && state.mode == TravelMode.DRIVE && !state.transit -> Row(Modifier.height(44.dp).glass(CircleShape).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Block, null, tint = Vela.closed, modifier = Modifier.size(18.dp))
            Text("Avoiding ${avoidNames(state.avoidNames)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = primaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("Undo", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Vela.accentText(dark), modifier = Modifier.tap { model.undoAvoid() })
        }
    }
}

fun avoidNames(list: List<String>): String {
    val seen = list.distinct()
    return if (seen.size <= 2) seen.joinToString(" and ") else "${seen[0]} and ${seen.size - 1} more"
}

@Composable
private fun Endpoint(icon: ImageVector, color: Color, text: String) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun delayColor(r: Route, dark: Boolean): Color = when (r.delayLevel) {
    2 -> if (dark) Vela.closedDark else Vela.closed
    1 -> Color(0xFFE37400)
    else -> Vela.accentText(dark)
}

/** Route cards show when there's a choice to make: up to three, the picked one lit. */
fun routeCards(d: DirectionsState): List<Route> = if (!d.transit && d.route != null && d.current.size > 1) d.current.take(3) else emptyList()

/** The place shuts before, or just as, this trip gets there. */
fun closingWarning(d: DirectionsState): ClosingCheck.Warning? {
    val r = d.route ?: return null
    if (d.transit || d.previewOnly) return null
    val now = kotlin.time.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
    return ClosingCheck.warning(d.destination.statusText, now.hour * 60 + now.minute, r.durationSeconds)
}

/** How tall the sheet sits at rest, so everything above the fold (cards, Go, modes) is in view. */
fun tripPeek(d: DirectionsState?): androidx.compose.ui.unit.Dp {
    if (d == null) return 300.dp
    if (d.transit) return 200.dp
    val n = routeCards(d).size
    val base = if (n == 0) 300 else 186 + n * 70
    return (base + if (closingWarning(d) != null) 36 else 0).dp
}

@Composable
private fun RouteOptionsButton(model: AppModel) {
    val dark = LocalDark.current
    val s = AppSettings
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Vela.chip(dark)).tap { menu = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Tune, "Route options", tint = primaryText, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Avoid tolls") }, trailingIcon = { Checkbox(s.avoidTolls, null) },
                onClick = { s.avoidTolls = !s.avoidTolls; model.reloadRoutes() })
            DropdownMenuItem(text = { Text(if (Fmt.yards) "Avoid motorways" else "Avoid highways") }, trailingIcon = { Checkbox(s.avoidHighways, null) },
                onClick = { s.avoidHighways = !s.avoidHighways; model.reloadRoutes() })
        }
    }
}

@Composable
private fun GoButton(model: AppModel) {
    val dark = LocalDark.current
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(CircleShape).background(Vela.accent(dark)).tap { model.startNavigation() },
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Navigation, null, tint = Vela.onAccent(dark), modifier = Modifier.size(28.dp))
        Text("Go", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Vela.onAccent(dark))
    }
}

@Composable
private fun ClosingLine(d: DirectionsState) {
    val w = closingWarning(d) ?: return
    val dark = LocalDark.current
    val red = if (dark) Vela.closedDark else Vela.closed
    Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Schedule, null, tint = red, modifier = Modifier.size(20.dp))
        Text(if (w.before) "Closes at ${w.closes}, before you arrive" else "Closes at ${w.closes}, just after you arrive",
            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = red, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One route to choose: the road it uses, how its traffic is, and its time, big. */
@Composable
private fun RouteCard(r: Route, on: Boolean, pick: () -> Unit) {
    val dark = LocalDark.current
    val fg = if (on) Vela.onAccent(dark) else primaryText
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(20.dp)).background(if (on) Vela.accent(dark) else Vela.chip(dark))
        .tap(pick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.summary?.let { "via $it" } ?: Fmt.distance(r.distanceMeters), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = fg,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.mode == TravelMode.DRIVE && r.trafficSeconds != null) {
                Text(if (r.delayLevel == 0) "Traffic is light" else "${Fmt.duration(r.durationSeconds - r.typicalSeconds)} slower than usual",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) fg else delayColor(r, dark), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(if (r.summary == null) "Arrive ${Fmt.arrival(r.durationSeconds)}" else Fmt.distance(r.distanceMeters), fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, color = if (on) fg.copy(alpha = 0.85f) else Vela.muted(dark), maxLines = 1)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Fmt.duration(r.durationSeconds), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = fg, maxLines = 1, lineHeight = 26.sp)
            Text("arrive ${Fmt.arrival(r.durationSeconds)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg.copy(alpha = 0.8f), maxLines = 1)
        }
    }
}

/** The part of the trip sheet always in view: how long, how you go, and a giant Go. */
@Composable
fun TripHeader(model: AppModel) {
    val d = model.directions ?: return
    val dark = LocalDark.current
    val ctx = Phone
    val cards = routeCards(d)
    if (cards.isNotEmpty()) {
        // A choice of routes: the choices first, then Go, then the ways to travel.
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cards.forEachIndexed { i, r -> RouteCard(r, r.id == d.route?.id) { model.selectRoute(i) } }
            Box(Modifier.padding(top = 2.dp)) { ClosingLine(d) }
            if (!d.previewOnly) Box(Modifier.padding(top = 2.dp)) { GoButton(model) } else PreviewLine(d)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { ModeTabs(model, d) }
                RouteOptionsButton(model)
            }
        }
        return
    }
    Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) {
                val r = d.route
                when {
                    d.transit -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(d.pickedTrip?.let { Fmt.duration(it.durationSeconds) } ?: "Transit", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
                            color = Vela.accentText(dark))
                        Text("By public transport", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Vela.muted(dark))
                    }
                    r != null -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(Fmt.duration(r.durationSeconds), fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = delayColor(r, dark),
                            maxLines = 1, lineHeight = 48.sp)
                        Text("${Fmt.distance(r.distanceMeters)} · arrive ${Fmt.arrival(r.durationSeconds)}", fontSize = 17.sp,
                            fontWeight = FontWeight.Medium, color = Vela.muted(dark), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    d.mode in d.loading -> Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp, color = Vela.accent(dark))
                        Text("Finding routes…", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    }
                    else -> Column(Modifier.heightIn(min = 52.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(d.errors[d.mode] ?: "No route found.", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Vela.muted(dark))
                        if (model.location.denied) PillButton("Open Settings", Icons.Filled.MyLocation, filled = true) { LocationService.openSettings() }
                        else if (d.errors[d.mode] != null) PillButton("Try again", Icons.Filled.Refresh) { model.reloadRoutes() }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (!d.transit) RouteOptionsButton(model)
        }
        ModeTabs(model, d)
        ClosingLine(d)
        if (!d.transit && !d.previewOnly && d.route != null) GoButton(model) else if (d.route != null) PreviewLine(d)
    }
}

/** Instead of Go when the trip doesn't start where you are. */
@Composable
private fun PreviewLine(d: DirectionsState) {
    if (d.origin == null || d.transit) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Visibility, null, tint = secondaryText, modifier = Modifier.size(18.dp))
        Text("Just a preview: to drive it, start from your location", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = secondaryText)
    }
}

fun modeIcon(m: TravelMode): ImageVector = when (m) {
    TravelMode.DRIVE -> Icons.Filled.DirectionsCar
    TravelMode.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
    TravelMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
}

@Composable
fun ModeTabs(model: AppModel, state: DirectionsState) {
    // Four equal tiles, always all in view, so the right one is easy to hit.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TravelMode.entries.forEach { m ->
            ModeTile(modeIcon(m), !state.transit && m == state.mode, m in state.loading,
                state.routes[m]?.firstOrNull()?.let { Fmt.shortDuration(it.durationSeconds) }) { model.setMode(m) }
        }
        ModeTile(Icons.Filled.Tram, state.transit, state.trips == null,
            // The same trip the header shows (the picked one, else the first), so the two never disagree.
            (state.pickedTrip ?: state.trips?.firstOrNull())?.let { Fmt.shortDuration(it.durationSeconds) }) { model.showTransit() }
    }
}

@Composable
private fun RowScope.ModeTile(icon: ImageVector, on: Boolean, loading: Boolean, time: String?, onClick: () -> Unit) {
    val dark = LocalDark.current
    val fg = if (on) Vela.onAccent(dark) else primaryText
    Column(Modifier.weight(1f).heightIn(min = 62.dp).clip(RoundedCornerShape(18.dp)).background(if (on) Vela.accent(dark) else Vela.chip(dark))
        .tap(onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp))
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
        else Text(time ?: "–", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
    }
}

@Composable
fun TripBody(model: AppModel) {
    val d = model.directions ?: return
    val dark = LocalDark.current
    val ctx = Phone
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val r = d.route
        when {
            d.transit -> TransitBody(model, d)
            r != null -> {
                if (r.mode == TravelMode.DRIVE) RouteCharges(r)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val best = d.current.minOfOrNull { it.durationSeconds } ?: r.durationSeconds
                    val lead = if (r.durationSeconds <= best + 30) (if (r.trafficSeconds != null) "Fastest route now" else "Best route") else "Slower route"
                    Text(listOfNotNull(lead, r.summary?.let { "via $it" }).joinToString(" · "), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    if (r.mode == TravelMode.DRIVE && r.trafficSeconds != null) {
                        Text(if (r.delayLevel == 0) "Traffic is light" else "${Fmt.duration(r.durationSeconds - r.typicalSeconds)} slower than usual",
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = delayColor(r, dark))
                    }
                    if (r.mode == TravelMode.DRIVE) Box(Modifier.padding(top = 2.dp)) { RouteTolls(r) }
                    if (r.mode == TravelMode.DRIVE) Box(Modifier.padding(top = 2.dp)) { ArrivalRows(model, d) }
                    if (r.mode == TravelMode.DRIVE && d.avoidNames.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.TouchApp, null, tint = secondaryText, modifier = Modifier.size(16.dp))
                            Text("Tap a road on the route to avoid it", fontSize = 15.sp, color = secondaryText)
                        }
                    }
                    if (r.mode == TravelMode.DRIVE) Box(Modifier.padding(top = 4.dp)) {
                        RouteWazeSummary(r, onShow = { model.showSpot(it.location) }, onFound = { model.routeReports = it })
                    }
                    if (r.mode == TravelMode.DRIVE) Box(Modifier.padding(top = 6.dp)) { RouteLiveCams(model, r) }
                }
                // More than three routes is rare; the ones past the cards stay reachable here.
                if (d.current.size > 3) {
                    Column {
                        d.current.forEachIndexed { i, alt ->
                            if (i >= 3 && alt.id != r.id) {
                                Row(Modifier.fillMaxWidth().tap { model.selectRoute(i) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.AutoMirrored.Filled.CallSplit, null, tint = secondaryText, modifier = Modifier.width(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("${Fmt.duration(alt.durationSeconds)} · ${Fmt.distance(alt.distanceMeters)}", fontSize = KType.subheadline,
                                            fontWeight = FontWeight.Medium, color = primaryText)
                                        alt.summary?.let { Text("via $it", fontSize = KType.caption, color = secondaryText) }
                                    }
                                    val diff = alt.durationSeconds - r.durationSeconds
                                    // Under half a minute apart would round to "+1 min": call it the same.
                                    Text(if (abs(diff) < 30) "Same time" else if (diff > 0) "+${Fmt.duration(diff)}" else "−${Fmt.duration(-diff)}",
                                        fontSize = KType.caption, color = secondaryText)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("Steps", fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText)
                StepsList(r)
                Row(Modifier.height(52.dp).clip(CircleShape).background(Vela.tonal(dark)).tap { ctx.share(d.destination.shareUrl) }.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Share, null, tint = Vela.onTonal(dark), modifier = Modifier.size(20.dp))
                    Text("Share place", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Vela.onTonal(dark))
                }
            }
            d.mode in d.loading -> Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Finding routes…", color = secondaryText)
            }
            else -> Text(d.errors[d.mode] ?: "No route found.", color = secondaryText, modifier = Modifier.padding(vertical = 20.dp))
        }
    }
}

/** Pay-later charges on this route (Dartford, the Mersey bridges, London's zone): there's no barrier to remind you. */
@Composable
fun RouteCharges(route: Route) {
    val dark = LocalDark.current
    val clean = AppSettings.cleanCar
    var charges by remember(route.id, clean) { mutableStateOf(RouteChargesDemo.charges) }
    LaunchedEffect(route.id, clean) {
        val found = withContext(Dispatchers.Default) { RoadCharges.on(route.polyline) + CleanAirZones.on(route.polyline, clean) }
        if (found.isNotEmpty() || RouteChargesDemo.charges.isEmpty()) charges = found
    }
    charges.forEach { c ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Vela.chip(dark)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (c.zone) Icons.Filled.Eco else Icons.Filled.CurrencyPound, null,
                tint = if (c.zone) Color(0xFF15803D) else Color(0xFFE37400), modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (c.zone) "Clean air zone on this route: ${c.name}" else "Charge on this route: ${c.name}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = primaryText)
                Text(c.note, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Vela.muted(dark), lineHeight = 19.sp)
            }
        }
    }
}

/**
 * With "Avoid tolls" on: a quiet "No tolls" when the route is clear, or a plain warning naming the toll
 * road when there was no way round it. Checked against the road map's own toll marks; nothing at all
 * when the map server doesn't answer.
 */
@Composable
fun RouteTolls(route: Route) {
    val avoid = AppSettings.avoidTolls
    var tolls by remember(route.id, avoid) { mutableStateOf(RouteTollsDemo.tolls) }
    LaunchedEffect(route.id, avoid) {
        if (RouteTollsDemo.tolls != null || !avoid || route.mode != TravelMode.DRIVE) return@LaunchedEffect
        tolls = withContext(Dispatchers.IO) { Valhalla.tolls(route.polyline) }
    }
    val t = tolls ?: return
    if (!avoid) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        if (t.isEmpty()) {
            Icon(Icons.Filled.CheckCircle, null, tint = Vela.open, modifier = Modifier.size(18.dp))
            Text("No tolls on this route", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Vela.open)
        } else {
            Icon(Icons.Filled.Warning, null, tint = Color(0xFFE37400), modifier = Modifier.size(18.dp))
            Text("No toll-free way found. This route uses ${tollList(t)}.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE37400))
        }
    }
}

/** "the M6 Toll", "the M6 Toll and the Dartford Crossing". */
fun tollList(names: List<String>): String {
    val n = names.take(3).map { if (it == "A toll road") "a toll road" else "the $it" }
    return if (n.size <= 1) n.firstOrNull() ?: "a toll road" else n.dropLast(1).joinToString(", ") + " and " + n.last()
}

object RouteTollsDemo { var tolls: List<String>? = null }

/** Made-up charges for the build's screenshots. */
object RouteChargesDemo { var charges: List<RoadCharge> = emptyList() }

/** The steps worth saying, each with the length of the quiet name changes after it added on. */
@Composable
fun StepsList(route: Route, from: Int = 0) {
    val shown = remember(route.id, from) {
        val out = mutableListOf<Maneuver>()
        for (m in route.maneuvers.drop(from.coerceIn(0, route.maneuvers.size))) {
            if (m.silent && out.isNotEmpty()) out[out.size - 1] = out.last().copy(distanceMeters = out.last().distanceMeters + m.distanceMeters)
            else if (!m.silent) out += m
        }
        out
    }
    Column {
        shown.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(maneuverIcon(m.type), null, tint = primaryText, modifier = Modifier.size(30.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(SpokenText.forScreen(m.instruction), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = primaryText)
                    m.laneHint?.let { Text(it, fontSize = KType.subheadline, color = secondaryText) }
                    if (m.distanceMeters > 0) Text(Fmt.distance(m.distanceMeters), fontSize = KType.subheadline, color = secondaryText)
                }
            }
            HorizontalDivider(Modifier.padding(start = 44.dp))
        }
    }
}

private fun clock(t: Long) = Fmt.time(t)

/** The Transit tab: each trip as a row of line chips with its times, the picked one's legs below. */
@Composable
fun TransitBody(model: AppModel, state: DirectionsState) {
    var kind by remember { mutableIntStateOf(if (state.transitTime == null) 0 else if (state.arriveBy) 2 else 1) }
    var whenAt by remember { mutableLongStateOf(state.transitTime ?: nowMillis()) }
    var picking by remember { mutableStateOf(false) }
    if (picking) DateTimePicker(whenAt, { picking = false }) { t ->
        picking = false
        whenAt = max(t, nowMillis() - 3_600_000)
        model.setTransitTime(kind, whenAt)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Leave now", "Depart at", "Arrive by").forEachIndexed { i, label ->
                SegmentedButton(kind == i, { kind = i; model.setTransitTime(i, whenAt) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(label, maxLines = 1) }
            }
        }
        if (kind != 0) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Time", fontSize = KType.subheadline, color = primaryText, modifier = Modifier.weight(1f))
                Text(LocalFormat.dateTime(whenAt),
                    fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = primaryText,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Vela.chip(LocalDark.current)).tap { picking = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
    val trips = state.trips
    when {
        trips != null && trips.isNotEmpty() -> {
            Column {
                trips.forEachIndexed { i, t ->
                    Box(Modifier.tap { model.selectTrip(i) }) { TripRow(t, t.id == state.pickedTrip?.id) }
                    HorizontalDivider()
                }
            }
            state.pickedTrip?.let { t ->
                Text("Steps", fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText, modifier = Modifier.padding(top = 6.dp))
                TransitSteps(t)
            }
            Text("Timetables from Transitous", fontSize = KType.caption, color = secondaryText, modifier = Modifier.padding(top = 4.dp))
        }
        trips == null -> Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Finding transit…", color = secondaryText)
        }
        else -> Text(state.tripsError ?: "No transit trips found.", color = secondaryText, modifier = Modifier.padding(vertical = 20.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripRow(trip: TransitTrip, picked: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (picked) secondaryText.copy(alpha = 0.14f) else Color.Transparent)
        .padding(vertical = 10.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val a = trip.depart; val b = trip.arrive
            if (a != null && b != null) Text("${clock(a)} – ${clock(b)}", fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = primaryText)
            Spacer(Modifier.weight(1f))
            Text(Fmt.duration(trip.durationSeconds), fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText)
        }
        // Wraps onto more lines for trips with many changes, like Google Maps does.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            trip.legs.forEachIndexed { i, leg ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText, modifier = Modifier.size(12.dp))
                    if (leg.isWalk) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = secondaryText, modifier = Modifier.size(16.dp))
                        if (leg.durationSeconds >= 60) Text("${kotlin.math.round(leg.durationSeconds / 60).toLong()}", fontSize = KType.caption2, color = secondaryText)
                    } else LineChip(leg)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val first = trip.rides.firstOrNull()
            first?.depart?.let { Text("${clock(it)} from ${first.fromName}", fontSize = KType.caption, color = secondaryText, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
            first?.delayMinutes?.let { late ->
                Text(if (late > 0) "$late min late" else if (late < 0) "${-late} min early" else "On time", fontSize = KType.caption,
                    color = if (late > 1) Vela.closed else Vela.open)
            }
        }
    }
}

@Composable
fun TransitSteps(trip: TransitTrip) {
    fun times(t: Long?, text: String) = t?.let { "${clock(it)}  $text" } ?: text
    Column {
        trip.legs.forEach { leg ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(vehicleIcon(leg.vehicle), null, modifier = Modifier.size(26.dp),
                    tint = if (leg.isWalk) secondaryText else leg.color?.let { Color(0xFF000000 or it) } ?: Vela.routeBlue)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (leg.isWalk) {
                        Text(if (leg.toName.isEmpty() || leg.toName == "END") "Walk to the destination" else "Walk to ${leg.toName}", fontSize = KType.subheadline, color = primaryText)
                        Text("${Fmt.duration(leg.durationSeconds)} · ${Fmt.distance(leg.distanceMeters)}", fontSize = KType.caption, color = secondaryText)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            LineChip(leg)
                            leg.headsign?.let { Text("to $it", fontSize = KType.subheadline, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        Text(times(leg.depart, leg.fromName), fontSize = KType.subheadline, color = primaryText)
                        Text("Ride ${leg.stops} stop${if (leg.stops == 1) "" else "s"} · ${Fmt.duration(leg.durationSeconds)}", fontSize = KType.caption, color = secondaryText)
                        Text(times(leg.arrive, "Get off at ${leg.toName}"), fontSize = KType.caption, color = secondaryText)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 40.dp))
        }
    }
}

/** A full-screen page with a title bar and Cancel, used for the pickers. */
@Composable
fun PickerPage(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    FullScreenDialog(close) {
        val dark = LocalDark.current
        Column(Modifier.fillMaxSize().background(Vela.ground(dark)).systemBarsPadding().imePadding()) {
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                Text("Cancel", fontSize = KType.body, color = Vela.accentText(dark), modifier = Modifier.align(Alignment.CenterStart)
                    .padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).tap(close).padding(horizontal = 10.dp, vertical = 8.dp))
                Text(title, fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText, modifier = Modifier.align(Alignment.Center))
            }
            content()
        }
    }
}

/** The search box at the top of a picker. */
@Composable
fun PickerSearchField(query: String, onQuery: (String) -> Unit, prompt: String, onSearch: () -> Unit) {
    val dark = LocalDark.current
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
        .background(Vela.chip(dark)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Search, null, tint = secondaryText, modifier = Modifier.size(22.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(prompt, fontSize = KType.body, color = secondaryText)
            BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(requester), singleLine = true,
                textStyle = TextStyle(fontSize = KType.body, color = primaryText, fontFamily = Rounded), cursorBrush = SolidColor(Vela.accent(dark)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }))
        }
        if (query.isNotEmpty()) Icon(Icons.Filled.Cancel, "Clear", tint = secondaryText, modifier = Modifier.size(20.dp).tap { onQuery("") })
    }
}

/** A section title in a picker list. */
@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = secondaryText,
        modifier = Modifier.padding(horizontal = 18.dp).padding(top = 16.dp, bottom = 4.dp))
}

/**
 * At the other end (GitHub #63): a big place's main entrance, to drive to the door instead of the
 * middle of the building, and the car parks near it. Two slim rows, only when there's something to say.
 */
@Composable
fun ArrivalRows(model: AppModel, state: DirectionsState) {
    val dark = LocalDark.current
    val place = state.original ?: state.destination
    var door by remember(place.id) { mutableStateOf<LatLng?>(null) }
    var parks by remember(place.id) { mutableStateOf<List<CarPark>>(emptyList()) }
    var showParks by remember { mutableStateOf(false) }
    LaunchedEffect(place.id) {
        val found = async(Dispatchers.IO) { ArrivalHelp.parking(place.location) }
        if (ArrivalHelp.isBigPlace(place.category)) door = withContext(Dispatchers.IO) { ArrivalHelp.mainEntrance(place.location) }
        parks = found.await() ?: emptyList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val note = state.endNote
        if (note != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (note == "Main entrance") Icons.Filled.DoorFront else Icons.Filled.LocalParking, null, tint = Vela.routeBlue, modifier = Modifier.size(20.dp))
                Text(if (note == "Main entrance") "Going to the main entrance" else "Parking at $note", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("Undo", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Vela.accentText(dark), modifier = Modifier.tap { model.endAt(null, null) })
            }
        } else {
            door?.let { dr ->
                Row(Modifier.fillMaxWidth().tap { model.endAt(dr, "Main entrance") }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.DoorFront, null, tint = Vela.routeBlue, modifier = Modifier.size(20.dp))
                    Text("Drive to the main entrance", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    Text("${Fmt.distance(dr.distanceTo(place.location))} from the pin", fontSize = 14.sp, color = secondaryText)
                }
            }
            if (parks.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().tap { showParks = true }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.LocalParking, null, tint = Vela.routeBlue, modifier = Modifier.size(20.dp))
                    Text("${parks.size} car park${if (parks.size == 1) "" else "s"} near there", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    if (showParks) {
        ModalBottomSheet(onDismissRequest = { showParks = false }, containerColor = Vela.sheet(dark), dragHandle = null) {
            Column(Modifier.padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Parking near ${place.name}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = primaryText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    CircleIconButton(Icons.Filled.Close) { showParks = false }
                }
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(parks, key = { it.id }) { c ->
                        Row(Modifier.fillMaxWidth().tap { showParks = false; model.endAt(c.location, c.name ?: "a car park") },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Filled.LocalParking, null, tint = Vela.routeBlue, modifier = Modifier.size(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name ?: "Car park", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(c.kind, c.spaces?.let { "$it spaces" }, c.fee?.let { if (it) "Paid" else "Free" }).joinToString(" · "),
                                    fontSize = KType.caption, color = secondaryText)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${Fmt.walkMinutes(c.meters)} walk", fontSize = KType.subheadline, fontWeight = FontWeight.Medium, color = primaryText)
                                Text("Park here", fontSize = KType.caption, fontWeight = FontWeight.SemiBold, color = Vela.routeBlue)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Search for a place to stop at on the way. Home, work and saved places are one tap. */
@Composable
fun StopPicker(model: AppModel, origin: Boolean = false) {
    val s = AppSettings
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun close() { if (origin) model.pickingOrigin = false else model.addingStop = false }
    fun run() {
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        found = emptyList()
        // Look between you and where you're going, so stops fall along the way.
        val a = model.userLatLng ?: model.viewport.first
        val b = model.directions?.destination?.location ?: a
        val mid = LatLng((a.lat + b.lat) / 2, (a.lng + b.lng) / 2)
        val span = max(a.distanceTo(b) * 1.2, 5_000.0)
        scope.launch {
            val r = withContext(Dispatchers.IO) { PlaceSearch.search(q, mid, span, !s.noGoogle) }
            found = r.places.take(30)
            searching = false
        }
    }
    PickerPage(if (origin) "Start from" else "Add stop", ::close) {
        PickerSearchField(query, { query = it }, if (origin) "Search for a place to start from" else "Search for a stop", ::run)
        LazyColumn(Modifier.fillMaxSize()) {
            fun pick(p: Place) { if (origin) model.setOrigin(p) else model.addStop(p); close() }
            if (found.isNotEmpty()) {
                item { SectionTitle("Results") }
                items(found, key = { it.id }) { p ->
                    IconRow(Icons.Filled.Place, p.name, p.address, model.userLatLng?.let { Fmt.distance(it.distanceTo(p.location)) }) { pick(p) }
                }
            } else if (searching) {
                item {
                    Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Searching…", color = secondaryText)
                    }
                }
            }
            val mine = listOfNotNull(s.home?.let { it to Icons.Filled.Home }, s.work?.let { it to Icons.Filled.Work }) +
                s.saved.take(12).map { it to Icons.Filled.Bookmark }
            if (mine.isNotEmpty() && found.isEmpty()) {
                item { SectionTitle("Your places") }
                items(mine, key = { "mine-" + it.first.id }) { (p, icon) ->
                    IconRow(icon, p.name, p.address, model.userLatLng?.let { Fmt.distance(it.distanceTo(p.location)) }) { pick(p) }
                }
            }
            if (found.isEmpty() && !searching && !origin) {
                item { SectionTitle("Along the way") }
                items(listOf(if (Fmt.yards) "Petrol stations" else "Gas stations", "Coffee", "Restaurants", "EV charging")) { c ->
                    IconRow(Icons.Filled.Search, c) { query = c; run() }
                }
            }
        }
    }
}
