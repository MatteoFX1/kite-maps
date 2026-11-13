@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
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
            BottomSheet(model.detent, { model.detent = it }, full, if (d?.transit == true) 200.dp else 300.dp,
                topClearance = statusTop + 6.dp + cardHeight + 16.dp,
                header = { TripHeader(model) }, content = { TripBody(model) })
        }
        if (d != null) {
            Box(Modifier.statusBarsPadding().padding(top = 6.dp).onSizeChanged { cardHeight = with(density) { it.height.toDp() } }) {
                EndpointsCard(model, d)
            }
        }
    }
    androidx.activity.compose.BackHandler { model.closeDirections() }
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
            Endpoint(Icons.Outlined.Circle, Vela.tealLight, if (state.reversed) state.destination.name else "Your location")
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
            Endpoint(Icons.Filled.Place, Vela.closed, if (state.reversed) "Your location" else state.destination.name)
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

/** The part of the trip sheet always in view: how long, how you go, and a giant Go. */
@Composable
fun TripHeader(model: AppModel) {
    val d = model.directions ?: return
    val dark = LocalDark.current
    val ctx = LocalContext.current
    val s = AppSettings
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
                        if (model.location.denied) PillButton("Open Settings", Icons.Filled.MyLocation, filled = true) { LocationService.openSettings(ctx) }
                        else if (d.errors[d.mode] != null) PillButton("Try again", Icons.Filled.Refresh) { model.reloadRoutes() }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (!d.transit) {
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
        }
        ModeTabs(model, d)
        if (!d.transit && !d.reversed && d.route != null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(CircleShape).background(Vela.accent(dark)).tap { model.startNavigation() },
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Navigation, null, tint = Vela.onAccent(dark), modifier = Modifier.size(28.dp))
                Text("Go", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Vela.onAccent(dark))
            }
        }
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
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val r = d.route
        when {
            d.transit -> TransitBody(model, d)
            r != null -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val best = d.current.minOfOrNull { it.durationSeconds } ?: r.durationSeconds
                    val lead = if (r.durationSeconds <= best + 30) (if (r.trafficSeconds != null) "Fastest route now" else "Best route") else "Slower route"
                    Text(listOfNotNull(lead, r.summary?.let { "via $it" }).joinToString(" · "), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    if (r.mode == TravelMode.DRIVE && r.trafficSeconds != null) {
                        Text(if (r.delayLevel == 0) "Traffic is light" else "${Fmt.duration(r.durationSeconds - r.typicalSeconds)} slower than usual",
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = delayColor(r, dark))
                    }
                    if (r.mode == TravelMode.DRIVE) Box(Modifier.padding(top = 4.dp)) { RouteWazeSummary(r) }
                    if (r.mode == TravelMode.DRIVE) Box(Modifier.padding(top = 6.dp)) { RouteLiveCams(model, r) }
                }
                Row(Modifier.height(52.dp).clip(CircleShape).background(Vela.tonal(dark)).tap { ctx.share(d.destination.shareUrl) }.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Share, null, tint = Vela.onTonal(dark), modifier = Modifier.size(20.dp))
                    Text("Share place", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Vela.onTonal(dark))
                }
                if (d.current.size > 1) {
                    Column {
                        d.current.forEachIndexed { i, alt ->
                            if (alt.id != r.id) {
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
            }
            d.mode in d.loading -> Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Finding routes…", color = secondaryText)
            }
            else -> Text(d.errors[d.mode] ?: "No route found.", color = secondaryText, modifier = Modifier.padding(vertical = 20.dp))
        }
    }
}

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

private fun clock(t: Long) = Fmt.time(Date(t))

/** The Transit tab: each trip as a row of line chips with its times, the picked one's legs below. */
@Composable
fun TransitBody(model: AppModel, state: DirectionsState) {
    val ctx = LocalContext.current
    var kind by remember { mutableIntStateOf(if (state.transitTime == null) 0 else if (state.arriveBy) 2 else 1) }
    var whenAt by remember { mutableLongStateOf(state.transitTime ?: System.currentTimeMillis()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Leave now", "Depart at", "Arrive by").forEachIndexed { i, label ->
                SegmentedButton(kind == i, { kind = i; model.setTransitTime(i, whenAt) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(label, maxLines = 1) }
            }
        }
        if (kind != 0) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Time", fontSize = KType.subheadline, color = primaryText, modifier = Modifier.weight(1f))
                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date(whenAt)),
                    fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = primaryText,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Vela.chip(LocalDark.current)).tap {
                        val c = Calendar.getInstance().apply { timeInMillis = whenAt }
                        DatePickerDialog(ctx, { _, y, mo, day ->
                            c.set(y, mo, day)
                            TimePickerDialog(ctx, { _, h, min ->
                                c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min)
                                whenAt = max(c.timeInMillis, System.currentTimeMillis() - 3_600_000)
                                model.setTransitTime(kind, whenAt)
                            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(ctx)).show()
                        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                    }.padding(horizontal = 10.dp, vertical = 6.dp))
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
                        if (leg.durationSeconds >= 60) Text("${Math.round(leg.durationSeconds / 60)}", fontSize = KType.caption2, color = secondaryText)
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

/** Search for a place to stop at on the way. Home, work and saved places are one tap. */
@Composable
fun StopPicker(model: AppModel) {
    val s = AppSettings
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun close() { model.addingStop = false }
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
    PickerPage("Add stop", ::close) {
        PickerSearchField(query, { query = it }, "Search for a stop", ::run)
        LazyColumn(Modifier.fillMaxSize()) {
            fun pick(p: Place) { model.addStop(p); close() }
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
            if (found.isEmpty() && !searching) {
                item { SectionTitle("Along the way") }
                items(listOf(if (Fmt.yards) "Petrol stations" else "Gas stations", "Coffee", "Restaurants", "EV charging")) { c ->
                    IconRow(Icons.Filled.Search, c) { query = c; run() }
                }
            }
        }
    }
}
