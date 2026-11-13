@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kite.*
import app.kite.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

private fun hex(v: Long) = Color(0xFF000000 or v)

/** Turn-by-turn: the green instruction banner, map buttons, and the ETA bar. */
@Composable
fun NavigationOverlay(model: AppModel, nav: Navigator) {
    val dark = LocalDark.current
    val ctx = LocalContext.current
    var showSteps by remember { mutableStateOf(false) }
    var showAlong by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    // Screenshot run: the end trip question.
    if (DemoScript.step == "endtrip") LaunchedEffect(Unit) { kotlinx.coroutines.delay(4000); confirmEnd = true }

    // Every warning that's up, most important first. Only the first gets the big card, like Waze;
    // the rest wait in one small pill. Whatever you'll reach first along the route wins, except a
    // camera while you're over its limit, which always wins (that's the one that costs a fine).
    // An average check and a police aircraft aren't a spot on the road ahead, so they only get the
    // big card when nothing else is up.
    // Traffic jams don't get a card up top: they show as a small line over the trip bar instead.
    val alertsUp = buildList {
        if (nav.arrived) return@buildList
        val ahead = listOfNotNull(nav.cameraAhead?.let { NavAlert.CAMERA to it.meters }, nav.wazeAhead?.takeIf { it.alert.kind != WazeAlert.Kind.JAM }?.let { NavAlert.WAZE to it.meters })
        addAll(ahead.sortedBy { it.second }.map { it.first })
        val cam = nav.cameraAhead
        val limit = cam?.limitKmh ?: nav.speedLimitKmh
        if (cam != null && limit != null && nav.speedMps * 3.6 > limit + 3 && indexOf(NavAlert.CAMERA) > 0) {
            remove(NavAlert.CAMERA); add(0, NavAlert.CAMERA)
        }
        if (nav.averageCheck != null) add(NavAlert.AVERAGE)
        if (nav.aircraftNear != null) add(NavAlert.AIRCRAFT)
    }
    // The warning shown for a few seconds after tapping the "also ahead" pill.
    var peek by remember { mutableStateOf<NavAlert?>(null) }
    LaunchedEffect(peek) { if (peek != null) { delay(4000); peek = null } }
    val mainAlert = peek?.takeIf { it in alertsUp } ?: alertsUp.firstOrNull()
    val others = alertsUp.filter { it != mainAlert }
    // The voice choice just picked, shown by the speaker button for a moment.
    var voiceNote by remember { mutableStateOf<VoiceMode?>(null) }
    LaunchedEffect(voiceNote) { if (voiceNote != null) { delay(2500); voiceNote = null } }
    val risk = nav.policeRisk
    val showRiskChip = risk != null && !nav.arrived && PoliceRisk.level(risk) != PoliceRisk.Level.LOW
    val chipShowing = showRiskChip

    fun etaText(): String {
        val left = nav.progress?.remainingSeconds ?: nav.route.durationSeconds
        return "On my way to ${nav.destination.name}, arriving around ${Fmt.arrival(left)} (${Fmt.duration(left)} away).\n${nav.destination.shareUrl}"
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        val w = maxWidth
        // The car, while the camera follows it: always at the same spot, pointing up the screen and
        // lying flat on the tilted map.
        if (model.glide.carOnScreen) {
            val puck = remember(dark) { MapIcons.puck(dark).asImageBitmap() }
            Box(Modifier.fillMaxWidth().offset(y = NavGlide.carY(h.value).dp - 38.dp), contentAlignment = Alignment.TopCenter) {
                Image(puck, null, Modifier.size(76.dp).graphicsLayer { scaleY = 0.64f })
            }
        }
        // The road you're on, just under the arrow.
        val road = nav.currentRoad
        if (nav.following && !nav.arrived && !chipShowing && road != null) {
            // On a short phone (360 x 800) that spot is behind the trip bar: keep it just above the bar instead.
            val barTop = h - WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() - 104.dp
            val pillTop = minOf(NavGlide.carY(h.value).dp + 50.dp - 18.dp, barTop - 36.dp - 8.dp)
            Box(Modifier.fillMaxWidth().offset(y = pillTop), contentAlignment = Alignment.TopCenter) {
                Text(road, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = w - 240.dp).height(36.dp).clip(CircleShape)
                        .background(hex(0x0F172A).copy(alpha = 0.92f)).border(1.5.dp, hex(0x2DD4BF).copy(alpha = 0.8f), CircleShape)
                        .padding(horizontal = 14.dp).wrapContentHeight(Alignment.CenterVertically))
            }
        }

        // One column, so however many warnings are up, they push the map buttons down rather than cover them.
        Column(Modifier.fillMaxSize().systemBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.padding(horizontal = 12.dp).padding(top = 4.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (nav.arrived) {
                    ArrivedCard(nav.destination.name, if (nav.route.mode == TravelMode.DRIVE) ({ model.saveParking() }) else null,
                        if (nav.wazeWarned > 0) "Waze drivers warned you about ${nav.wazeWarned} report${if (nav.wazeWarned == 1) "" else "s"} on the way" else null) {
                        model.endNavigation()
                    }
                } else InstructionBanner(nav)
                // One warning at a time; the others wait in the pill under it.
                when (mainAlert) {
                    NavAlert.CAMERA -> nav.cameraAhead?.let { CameraCard(it, nav.speedMps * 3.6, nav.speedLimitKmh) }
                    NavAlert.WAZE -> nav.wazeAhead?.let { w -> Box(Modifier.tap { nav.dismissWaze() }) { WazeCard(w) } }
                    NavAlert.AVERAGE -> nav.averageCheck?.let { AverageCheckCard(it) }
                    NavAlert.AIRCRAFT -> nav.aircraftNear?.let { AircraftCard(it.aircraft, it.meters) }
                    null -> {}
                }
                if (others.isNotEmpty()) AlsoAheadPill(nav, others) { peek = others.first() }
                // On a short phone with a warning card and lane arrows up there's no room for a third
                // row: the card matters more, and the GPS and rerouting notes come back once it's gone.
                if (mainAlert != null && h < 700.dp) {
                } else if (nav.gpsLost && !nav.arrived) {
                    // In a tunnel the speed and the next turn stop moving: say why, so it doesn't look live.
                    Row(Modifier.align(Alignment.CenterHorizontally).height(44.dp).glass(CircleShape).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.LocationOff, null, tint = primaryText)
                        Text("GPS signal lost", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = primaryText)
                    }
                } else if (nav.rerouting) {
                    Row(Modifier.align(Alignment.CenterHorizontally).height(44.dp).glass(CircleShape).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Sync, null, tint = primaryText)
                        Text("Rerouting…", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = primaryText)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // A Waze police card already says it louder; the chip comes back when it's gone.
                    // On a short phone a warning card plus lanes leaves no room: the chip gives way to the card.
                    if (showRiskChip && risk != null && !(mainAlert != null && h < 700.dp)) PoliceRiskChip(risk)
                    SpeedBadge(nav.speedMps, nav.speedLimitKmh)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    voiceNote?.let { note ->
                        // Says which of the three the speaker is on now, for a moment.
                        Column(Modifier.shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(hex(0x0F172A).copy(alpha = 0.95f))
                            .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(note.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Vela.accentText(true))
                            Text(note.detail, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                    }
                    val icon = when (nav.voiceMode) {
                        VoiceMode.ALL -> Icons.AutoMirrored.Filled.VolumeUp
                        VoiceMode.ALERTS -> Icons.Filled.Notifications
                        VoiceMode.OFF -> Icons.AutoMirrored.Filled.VolumeOff
                    }
                    MapFab(icon, tint = if (nav.voiceMode == VoiceMode.ALERTS) Vela.accentText(dark) else null, size = 64.dp,
                        modifier = Modifier.semantics { contentDescription = "Voice: ${nav.voiceMode.title}" }) {
                        nav.voiceMode = nav.voiceMode.next
                        voiceNote = nav.voiceMode
                    }
                }
            }
            if (!nav.following) {
                Row(Modifier.align(Alignment.CenterHorizontally).height(60.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Vela.accent(dark))
                    .tap { model.recenterNav() }.padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Navigation, null, tint = Vela.onAccent(dark))
                    Text("Re-centre", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Vela.onAccent(dark))
                }
            }
            // A traffic jam ahead: small and calm over the trip bar, so the top stays for turns and cameras.
            val jam = nav.wazeAhead?.takeIf { it.alert.kind == WazeAlert.Kind.JAM && !nav.arrived && !confirmEnd }
            if (jam != null) TrafficLine(jam, Modifier.align(Alignment.CenterHorizontally).tap { nav.dismissWaze() })
            // Arriving answers the question: the trip bar's Done takes over.
            AnimatedContent(confirmEnd && !nav.arrived, label = "tripbar") { asking ->
                if (asking) EndTripCard(nav.destination.name, onKeep = { confirmEnd = false }, onEnd = { model.endNavigation() })
                else TripBar(nav, onEnd = { if (nav.arrived) model.endNavigation() else confirmEnd = true }, onSteps = { showSteps = true })
            }
        }
    }
    androidx.activity.compose.BackHandler { if (confirmEnd) confirmEnd = false else if (!nav.arrived) confirmEnd = true else model.endNavigation() }

    if (showAlong) AlongRouteSheet(nav) { showAlong = false }
    if (showSteps) {
        TripSheet("Your trip", "Done", { showSteps = false }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // The things that used to be buttons on the map, out of the way until wanted.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TripAction(Icons.Filled.Search, "Find on route") { showSteps = false; showAlong = true }
                    TripAction(Icons.Filled.Share, "Share ETA") { ctx.share(etaText()) }
                    TripAction(Icons.Filled.ZoomOutMap, "Overview") { showSteps = false; model.overviewNav() }
                }
                StepsList(nav.route, nav.progress?.nextIndex ?: 0)
            }
        }
    }
}

/** A full-screen sheet with a title and one button on the right (Done or Close). */
@Composable
fun TripSheet(title: String, button: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    FullScreenDialog(close) {
        val dark = LocalDark.current
        Column(Modifier.fillMaxSize().background(Vela.ground(dark)).systemBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                Text(title, fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText, modifier = Modifier.align(Alignment.Center))
                Text(button, fontSize = KType.body, fontWeight = FontWeight.SemiBold, color = Vela.accentText(dark), modifier = Modifier.align(Alignment.CenterEnd)
                    .padding(end = 8.dp).clip(RoundedCornerShape(8.dp)).tap(close).padding(horizontal = 10.dp, vertical = 8.dp))
            }
            content()
        }
    }
}

@Composable
private fun RowScope.TripAction(icon: ImageVector, title: String, onClick: () -> Unit) {
    val dark = LocalDark.current
    Column(Modifier.weight(1f).heightIn(min = 78.dp).clip(RoundedCornerShape(18.dp)).background(Vela.chip(dark)).tap(onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        Icon(icon, null, tint = primaryText, modifier = Modifier.size(26.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = primaryText, maxLines = 1)
    }
}

object Banner {
    /** "At the end of the road, turn left onto Burgess Road" is "At the end of the road, turn left" and "Burgess Road". */
    fun split(text: String): Pair<String, String>? {
        if (text.length <= 26) return null
        for (word in listOf(" onto ", " towards ", " toward ")) {
            val i = text.lastIndexOf(word)
            if (i < 0) continue
            val place = text.substring(i + word.length).trim()
            // "Take the exit toward" keeps its last word, so it still reads as a sentence.
            val action = if (word == " onto ") text.substring(0, i) else text.substring(0, i + word.length).trim()
            if (place.isEmpty() || action.length < 4) continue
            return action to place
        }
        return null
    }

    /** "6th exit" for a roundabout (the picture says the rest), else the instruction. */
    fun headline(m: Maneuver?): String {
        m ?: return "Follow the route"
        val n = m.exit
        if (m.type == ManeuverType.ROUNDABOUT && n != null) return "${ordinal(n)} exit"
        return SpokenText.forScreen(m.instruction)
    }

    fun ordinal(n: Int): String {
        if (n % 100 in 11..13) return "${n}th"
        return when (n % 10) { 1 -> "${n}st"; 2 -> "${n}nd"; 3 -> "${n}rd"; else -> "${n}th" }
    }
}

/**
 * The next turn, as big as it can be: the real roundabout drawn with every exit and yours lit,
 * the distance, the road, and below it which lane to be in.
 */
@Composable
fun InstructionBanner(nav: Navigator) {
    val m = nav.nextManeuver
    val leftTraffic = Geo.drivesOnLeft(m?.location ?: LatLng(0.0, 0.0))
    val refs = RoadRefs.find(m?.instruction ?: "")
    val p = nav.progress
    val shape = RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth().shadow(12.dp, shape).clip(shape).background(Vela.navGreen)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                if (m?.type == ManeuverType.ROUNDABOUT) RoundaboutDiagram(m.roundabout, m.exit ?: 1, leftTraffic, Modifier.fillMaxSize())
                else Icon(maneuverIcon(m?.type ?: ManeuverType.STRAIGHT), null, tint = Color.White, modifier = Modifier.size(80.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (p != null) Text(Fmt.distance(p.distanceToNext), fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, lineHeight = 48.sp)
                val head = Banner.headline(m)
                val parts = Banner.split(head)
                if (parts != null) {
                    // "At the end of the road, turn left" small, the road you turn onto big, like Waze.
                    Text(parts.first, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(parts.second, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 28.sp)
                } else {
                    Text(head, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 27.sp)
                }
                if (refs.isNotEmpty()) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { refs.forEach { RoadShield(it) } }
                else if (m?.type == ManeuverType.ROUNDABOUT) m.road?.let {
                    Text("onto $it", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val lanes = if (m != null && p != null && p.distanceToNext < 1_500) LaneGuide.lanes(m, leftTraffic) else null
        if (lanes != null && m != null) {
            Row(Modifier.fillMaxWidth().background(Vela.navBand).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LaneStrip(lanes.first, m.type, m.roundabout?.let { it.exits.getOrNull(it.taken) })
                Text(lanes.second, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, modifier = Modifier.weight(1f))
                ThenView(nav)
            }
        } else if (nav.thenManeuver != null) {
            Row(Modifier.fillMaxWidth().background(Vela.navBand).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) { ThenView(nav) }
        }
    }
}

@Composable
private fun ThenView(nav: Navigator) {
    val then = nav.thenManeuver ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Then", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
        Icon(maneuverIcon(then.type), null, tint = Color.White, modifier = Modifier.size(24.dp))
        val n = then.exit
        if (then.type == ManeuverType.ROUNDABOUT && n != null) {
            // Which exit, so the next roundabout is known before the banner changes.
            Box(Modifier.defaultMinSize(22.dp, 22.dp).clip(CircleShape).background(hex(0x0F766E)), contentAlignment = Alignment.Center) {
                Text("$n", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
        }
    }
}

/** Speed and limit as one sign, the way Waze does it: the limit on top, your speed under it, all red when over. */
@Composable
fun SpeedBadge(mps: Double, limitKmh: Double?) {
    val dark = LocalDark.current
    val metric = Fmt.metric
    val v = (mps * (if (metric) 3.6 else 2.23694)).roundToInt()
    // Red once more than a little over the limit.
    val over = limitKmh?.let { mps * 3.6 > it + 5 } ?: false
    val speed: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$v", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = if (over) Color.White else primaryText, lineHeight = 38.sp)
            Text(if (metric) "km/h" else "mph", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (over) Color.White.copy(alpha = 0.85f) else Vela.muted(dark), lineHeight = 14.sp)
        }
    }
    if (limitKmh != null) {
        Column(Modifier.width(92.dp).glass(CircleShape).clip(CircleShape).background(if (over) Vela.closed else Color.Transparent)
            .padding(top = 8.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SpeedLimitSign(limitKmh, 76.dp)
            speed()
        }
    } else {
        Box(Modifier.size(84.dp).glass(CircleShape), contentAlignment = Alignment.Center) { speed() }
    }
}

/** The posted limit: a US-style white sign in miles, a round red-ringed one in kilometers or in Britain. */
@Composable
fun SpeedLimitSign(kmh: Double, size: Dp = 56.dp) {
    if (Fmt.metric || Fmt.yards) {
        val n = if (Fmt.metric) kmh.roundToInt() else Fmt.postedMph(kmh)
        Box(Modifier.size(size).shadow(5.dp, CircleShape).clip(CircleShape).background(Color.White)
            .border(size * 0.11f, hex(0xD93025), CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
        }
    } else {
        // OSM limits in the US are mapped in mph; round back to the posted number.
        val mph = Fmt.postedMph(kmh)
        Column(Modifier.size(size * 0.86f, size * 1.07f).shadow(5.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
            .background(Color.White).padding(2.dp).border(2.dp, Color.Black, RoundedCornerShape(5.dp)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("SPEED\nLIMIT", fontSize = (size.value * 0.14f).sp, fontWeight = FontWeight.Bold, color = Color.Black, textAlign = TextAlign.Center,
                lineHeight = (size.value * 0.15f).sp)
            Text("$mph", fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.ExtraBold, color = Color.Black, lineHeight = (size.value * 0.42f).sp)
        }
    }
}

/** Asks before ending a trip, in place of the trip bar: two big buttons, easy to hit in a phone holder. */
@Composable
fun EndTripCard(destination: String, onKeep: () -> Unit, onEnd: () -> Unit) {
    val dark = LocalDark.current
    Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp).fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("End this trip?", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = primaryText)
            Text("to $destination", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Vela.muted(dark), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).height(60.dp).clip(CircleShape).background(Vela.accent(dark)).tap(onKeep), contentAlignment = Alignment.Center) {
                Text("Keep going", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Vela.onAccent(dark))
            }
            Box(Modifier.weight(1f).height(60.dp).clip(CircleShape).background(Vela.closed).tap(onEnd), contentAlignment = Alignment.Center) {
                Text("End trip", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/** The bottom card while driving: end, time left (big), and the trip menu. */
@Composable
fun TripBar(nav: Navigator, onEnd: () -> Unit, onSteps: () -> Unit) {
    val dark = LocalDark.current
    val remaining = nav.progress?.remainingSeconds ?: nav.route.durationSeconds
    val meters = nav.progress?.remainingMeters ?: nav.route.distanceMeters
    Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp).fillMaxWidth().height(100.dp).glass(RoundedCornerShape(28.dp)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(if (dark) hex(0x3B1820) else hex(0xFEE2E2)).tap(onEnd), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, "End trip", tint = if (dark) hex(0xFCA5A5) else Vela.closed, modifier = Modifier.size(30.dp))
        }
        // Three big numbers side by side, like Waze: when you get there, minutes left, miles left.
        // The arrival time turns orange (or red) when traffic is making you late.
        val arrive = arrivalParts(remaining)
        val left = timeParts(remaining)
        val dist = distanceParts(meters)
        val spoken = "Arrive at ${Fmt.arrival(remaining)}, ${Fmt.duration(remaining)}, ${Fmt.distance(meters)} to go"
        // The biggest size where all three fit with a gap between them, all three the same size
        // (so they share a line), dropping the small "am"/"pm" only when even that won't fit.
        // There: no "1 min · 20 yd" left over from the last moment of the drive.
        if (nav.arrived) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text("Arrived", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Vela.accentText(dark))
        } else BoxWithConstraints(Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken }, contentAlignment = Alignment.Center) {
            val measurer = rememberTextMeasurer()
            val gap = 14.dp
            val avail = with(LocalDensity.current) { maxWidth.toPx() - 2 * gap.toPx() }
            fun arriveText(size: Int, ampm: Boolean) = buildAnnotatedString {
                append(arrive.first)
                if (ampm && arrive.second.isNotEmpty()) withStyle(SpanStyle(fontSize = (size * 0.53f).sp, fontWeight = FontWeight.Bold)) { append(arrive.second) }
            }
            val candidates = listOf(30 to true, 27 to true, 24 to true, 22 to true, 21 to true, 21 to false, 18 to false)
            val (size, ampm) = candidates.firstOrNull { (s, a) ->
                val style = TextStyle(fontSize = s.sp, fontWeight = FontWeight.ExtraBold)
                listOf(arriveText(s, a), AnnotatedString(left.first), AnnotatedString(dist.first))
                    .sumOf { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } <= avail
            } ?: candidates.last()
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                TripStat(arriveText(size, ampm), "arrive", delayColor(nav.route, dark), size)
                TripStat(AnnotatedString(left.first), left.second, primaryText, size)
                TripStat(AnnotatedString(dist.first), dist.second, primaryText, size)
            }
        }
        Box(Modifier.size(64.dp).clip(CircleShape).background(Vela.chip(dark)).tap(onSteps), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.List, "Trip and steps", tint = primaryText, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun TripStat(big: AnnotatedString, label: String, color: Color, size: Int) {
    val dark = LocalDark.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(big, fontSize = size.sp, fontWeight = FontWeight.ExtraBold, color = color, maxLines = 1, softWrap = false, lineHeight = (size + 2).sp)
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Vela.muted(dark), maxLines = 1)
    }
}

/** "9:18" and " pm" (nothing on a 24 hour clock), so the time stays big and the pm small. */
internal fun arrivalParts(s: Double): Pair<String, String> {
    val full = Fmt.arrival(s)
    val syms = java.text.DateFormatSymbols.getInstance().amPmStrings
    for (sym in syms) if (sym.isNotEmpty() && full.contains(sym)) {
        return full.replace(sym, "").trim { it.isWhitespace() || it == '\u202F' || it == '\u00A0' } to " " + sym.lowercase()
    }
    return full to ""
}

/** "8" min, or "1:20" hr once it's an hour or more. */
internal fun timeParts(s: Double): Pair<String, String> {
    val mins = maxOf(1, Math.round(s / 60).toInt())
    return if (mins < 60) "$mins" to "min" else String.format(java.util.Locale.US, "%d:%02d", mins / 60, mins % 60) to "hr"
}

/** "2.1" and "mi" from "2.1 mi". */
internal fun distanceParts(m: Double): Pair<String, String> {
    val s = Fmt.distance(m)
    val i = s.lastIndexOf(' ')
    return if (i < 0) s to "" else s.substring(0, i) to s.substring(i + 1)
}

@Composable
fun ArrivedCard(name: String, onPark: (() -> Unit)?, note: String?, done: () -> Unit) {
    var parked by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth().shadow(12.dp, shape).clip(shape).background(Vela.navGreen).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.SportsScore, null, tint = Color.White, modifier = Modifier.size(42.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("You've arrived", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 3)
                if (note != null) Text(note, fontSize = KType.subheadline, color = Color.White.copy(alpha = 0.85f), maxLines = 2)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End)) {
            if (onPark != null && !parked) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)).tap { onPark(); parked = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.LocalParking, "Save parking", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
            Box(Modifier.height(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)).tap(done).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text("Done", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/**
 * The camera warning, loud on purpose: bright orange, a big camera, the distance in huge
 * numbers and the limit it checks. It pulses as it appears, and turns red while you're over.
 */
@Composable
fun CameraCard(alert: CameraAlert, speedKmh: Double, roadLimitKmh: Double? = null) {
    val over = alert.limitKmh?.let { speedKmh > it + 3 } ?: false
    val left = (alert.meters / alert.startMeters).coerceIn(0.0, 1.0).toFloat()
    val fill = if (over) Vela.closed else if (alert.kind == RoadCamera.Kind.SPEED) Vela.camera else hex(0x8E44AD)
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(alert.startMeters) {
        // Two quick pulses when it first shows, so it catches the eye.
        repeat(2) { pulse = true; delay(300); pulse = false; delay(300) }
    }
    val scale by animateFloatAsState(if (pulse) 1.04f else 1f, tween(250), label = "pulse")
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.scale(scale).width(IntrinsicSize.Max).shadow(if (pulse) 18.dp else 8.dp, shape, spotColor = fill).clip(shape).background(fill)
            .border(3.dp, Color.White.copy(alpha = if (pulse) 0.9f else 0f), shape).padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CameraBadge(alert.kind, 42.dp)
                Column {
                    Text(Fmt.distance(alert.meters), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, lineHeight = 32.sp)
                    Text(if (over) "Slow down" else if (alert.kind == RoadCamera.Kind.SPEED) "Speed camera" else "Plate camera",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                }
                // The limit by the speed badge already says it: only shown here when the camera checks a different one.
                alert.limitKmh?.takeIf { l -> roadLimitKmh?.let { abs(it - l) > 1 } ?: true }?.let { Box(Modifier.padding(start = 4.dp)) { SpeedLimitSign(it, 50.dp) } }
            }
            ProgressStrip(left, 5.dp)
        }
    }
}

@Composable
private fun ProgressStrip(left: Float, height: Dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxWidth(left).fillMaxHeight().clip(CircleShape).background(Color.White))
    }
}

/** "Reported 12 min ago". */
val WazeAlert.reportedText: String
    get() {
        val mins = max(((System.currentTimeMillis() - reportedAtMillis) / 60_000).toInt(), 0)
        val w = when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 1440 -> "${mins / 60} h ago"
            else -> "${mins / 1440} d ago"
        }
        return "Reported $w"
    }

/** A traffic jam ahead, kept small and calm just over the trip bar: "Standstill traffic in 140 yd". */
@Composable
fun TrafficLine(ahead: WazeAhead, modifier: Modifier = Modifier) {
    val dark = LocalDark.current
    val ink = if (dark) hex(0xFF8A80) else Vela.closed
    Row(modifier.height(40.dp).shadow(5.dp, CircleShape).clip(CircleShape)
        .background(if (dark) hex(0x0F172A).copy(alpha = 0.92f) else Color.White.copy(alpha = 0.95f)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WazeBadge(ahead.alert.kind, 22.dp)
        Text("${ahead.alert.title} in ${Fmt.distance(ahead.meters)}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A Waze report on the road ahead, as loud as the camera card, in the report's own color. */
@Composable
fun WazeCard(ahead: WazeAhead) {
    val a = ahead.alert
    val left = (ahead.meters / ahead.startMeters).coerceIn(0.0, 1.0).toFloat()
    val detail = listOfNotNull(a.reportedText, if (a.thumbsUp > 0) "${a.thumbsUp} 👍" else null, a.street).joinToString(" · ")
    val fill = hex(MapIcons.wazeColor(a.kind))
    val shape = RoundedCornerShape(26.dp)
    Column(Modifier.fillMaxWidth().shadow(12.dp, shape, spotColor = fill).clip(shape).background(fill).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            WazeBadge(a.kind, 58.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(Fmt.distance(ahead.meters), fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, lineHeight = 40.sp)
                Text(a.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("Waze", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.White.copy(alpha = 0.8f))
        }
        ProgressStrip(left, 8.dp)
    }
}

/** A Waze report tapped on the map: what, where, how fresh, how many drivers agree. Goes by itself after a while. */
@Composable
fun WazeInfoToast(model: AppModel, info: WazeAlert) {
    LaunchedEffect(info.id) {
        if (DemoScript.active) return@LaunchedEffect
        delay(9_000)
        if (model.wazeInfo?.id == info.id) model.wazeInfo = null
    }
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp)) { WazeInfoCard(info) { model.wazeInfo = null } }
}

@Composable
fun WazeInfoCard(alert: WazeAlert, close: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        WazeBadge(alert.kind, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(alert.title, fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText)
            Text(listOfNotNull(alert.street, alert.reportedText).joinToString(" · "), fontSize = KType.subheadline, color = secondaryText)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (alert.thumbsUp > 0) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ThumbUp, null, tint = secondaryText, modifier = Modifier.size(12.dp))
                    Text("${alert.thumbsUp} confirmed", fontSize = KType.caption, color = secondaryText)
                }
                // Waze's 0-10 trust in the report, as five dots.
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(5) { i ->
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (i < (alert.reliability + 1) / 2) hex(MapIcons.wazeColor(alert.kind)) else secondaryText.copy(alpha = 0.25f)))
                    }
                }
                Text("via Waze", fontSize = KType.caption, color = secondaryText)
            }
        }
        Icon(Icons.Filled.Close, "Close", tint = secondaryText, modifier = Modifier.size(28.dp).clip(CircleShape).tap(close).padding(5.dp))
    }
}

/**
 * Before starting a drive: what Waze drivers have reported along this route right now, like
 * "2 police · 1 crash". Quiet when Waze is off or didn't answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteWazeSummary(route: Route) {
    val s = AppSettings
    var counts by remember(route.id) { mutableStateOf<List<Pair<WazeAlert.Kind, Int>>?>(null) }
    LaunchedEffect(route.id) {
        // For the build's screenshots, since Waze turns away the build's computers.
        if (DemoScript.step == "wazeroute") { counts = listOf(WazeAlert.Kind.POLICE to 2, WazeAlert.Kind.ACCIDENT to 1, WazeAlert.Kind.HAZARD to 3); return@LaunchedEffect }
        counts = null
        if (!s.wazeOn || route.mode != TravelMode.DRIVE) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) { Waze.alongRoute(route.polyline) } ?: return@LaunchedEffect
        val on = Waze.project(found, route.polyline).map { it.alert }.filter { s.shows(it) }
        val grouped = on.groupBy { it.kind }
        counts = WazeAlert.Kind.entries.mapNotNull { k -> grouped[k]?.let { k to it.size } }
    }
    val c = counts ?: return
    if (c.isEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.VerifiedUser, null, tint = Vela.open, modifier = Modifier.size(18.dp))
            Text("No road closures on this route", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Vela.open)
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            c.forEach { (k, n) ->
                Row(Modifier.height(38.dp).clip(CircleShape).background(hex(MapIcons.wazeColor(k)).copy(alpha = 0.18f)).padding(start = 6.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WazeBadge(k, 24.dp)
                    Text("$n ${wazeLabel(k, n)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = primaryText)
                }
            }
        }
    }
}

fun wazeLabel(k: WazeAlert.Kind, n: Int) = when (k) {
    WazeAlert.Kind.POLICE -> "police"
    WazeAlert.Kind.ACCIDENT -> if (n == 1) "crash" else "crashes"
    WazeAlert.Kind.HAZARD -> if (n == 1) "hazard" else "hazards"
    WazeAlert.Kind.WEATHER -> "weather"
    WazeAlert.Kind.ROADWORKS -> "roadworks"
    WazeAlert.Kind.CLOSURE -> if (n == 1) "closure" else "closures"
    WazeAlert.Kind.JAM -> if (n == 1) "jam" else "jams"
}

private val aircraftBlue = hex(0x1E88E5)

/** The warnings that can take the big card while driving. */
enum class NavAlert { CAMERA, WAZE, AVERAGE, AIRCRAFT }

/** "Also ahead": the warnings waiting their turn, small, in one pill. Tap to peek at the next one. */
@Composable
fun AlsoAheadPill(nav: Navigator, alerts: List<NavAlert>, onTap: () -> Unit) {
    Row(Modifier.height(44.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(hex(0x0F172A).copy(alpha = 0.92f))
        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape).tap(onTap).padding(horizontal = 16.dp)
        .semantics { contentDescription = "Also ahead. Tap to show the next warning" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Two at most, with "+1" for any more. On a small phone the words "Also ahead" go first,
        // then the second warning, so the pill never runs off the screen.
        val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
        val shown = if (width < 360) 1 else 2
        if (width >= 400) Text("Also ahead", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.75f))
        for (a in alerts.take(shown)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val label = when (a) {
                NavAlert.CAMERA -> nav.cameraAhead?.let { CameraBadge(it.kind, 24.dp); Fmt.distance(it.meters) }
                NavAlert.WAZE -> nav.wazeAhead?.let { WazeBadge(it.alert.kind, 24.dp); Fmt.distance(it.meters) }
                NavAlert.AVERAGE -> nav.averageCheck?.let {
                    Icon(Icons.Filled.Speed, null, tint = Color.White, modifier = Modifier.size(20.dp)); "Avg ${Fmt.speed(it.averageKmh)} ${Fmt.speedUnit}"
                }
                NavAlert.AIRCRAFT -> nav.aircraftNear?.let {
                    Icon(if (it.aircraft.helicopter) Icons.Filled.Toys else Icons.Filled.Flight, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Fmt.distance(it.meters)
                }
            }
            if (label != null) Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        }
        if (alerts.size > shown) Text("+${alerts.size - shown}", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
    }
}

/** The aircraft card made small, for when the screen is already full of warnings. */
@Composable
fun AircraftChip(aircraft: PoliceAircraft, meters: Double) {
    Row(Modifier.height(40.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(aircraftBlue).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (aircraft.helicopter) Icons.Filled.Toys else Icons.Filled.Flight, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text("${aircraft.title} · ${Fmt.distance(meters)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}

/** A police plane or helicopter nearby: what, how far, how high. */
@Composable
fun AircraftCard(aircraft: PoliceAircraft, meters: Double) {
    val dark = LocalDark.current
    val detail = listOfNotNull(aircraft.altitudeFeet?.let { if (!Fmt.metric) "${it.toInt()} ft up" else "${(it * 0.3048).toInt()} m up" },
        aircraft.callsign ?: aircraft.registration).joinToString(" · ")
    Row(Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Vela.sheet(dark)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(aircraftBlue), contentAlignment = Alignment.Center) {
            Icon(if (aircraft.helicopter) Icons.Filled.Toys else Icons.Filled.Flight, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Fmt.distance(meters), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = primaryText)
                Text(aircraft.title, fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText)
            }
            if (detail.isNotEmpty()) Text(detail, fontSize = KType.subheadline, color = secondaryText, maxLines = 1)
        }
    }
}

@Composable
fun WazeBadge(kind: WazeAlert.Kind, size: Dp = 28.dp) {
    val bmp = remember(kind) { MapIcons.waze(kind).asImageBitmap() }
    Image(bmp, null, Modifier.size(size))
}

@Composable
fun CameraBadge(kind: RoadCamera.Kind, size: Dp = 28.dp) {
    val bmp = remember(kind) { (if (kind == RoadCamera.Kind.SPEED) MapIcons.speedCamera() else MapIcons.plateCamera()).asImageBitmap() }
    Image(bmp, null, Modifier.size(size))
}

/** Inside an average speed check: your average so far against the limit, and how much is left. */
@Composable
fun AverageCheckCard(check: AverageCheck) {
    val dark = LocalDark.current
    val over = check.limitKmh?.let { check.averageKmh > it + 2 } ?: false
    val fg = if (over) Color.White else primaryText
    val sub = if (over) Color.White.copy(alpha = 0.9f) else secondaryText
    Row(Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(if (over) Vela.closed else Vela.sheet(dark)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.Speed, null, tint = if (over) Color.White else hex(0xF9AB00), modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f)) {
            Text("Average speed check", fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = fg)
            Text("${Fmt.distance(max(check.metersLeft, 0.0))} left", fontSize = KType.caption, color = sub)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${Fmt.speed(check.averageKmh)}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = fg, lineHeight = 26.sp)
            Text("avg ${Fmt.speedUnit}", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = sub, lineHeight = 11.sp)
        }
        check.limitKmh?.let { SpeedLimitSign(it) }
    }
}

/** Find a stop ahead without leaving navigation: fuel, food, coffee… near the road still to drive. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlongRouteSheet(nav: Navigator, close: () -> Unit) {
    val s = AppSettings
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<Pair<Place, Double>>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    val y = Fmt.yards
    val kinds = listOf(
        Triple(if (y) "Petrol" else "Gas", Icons.Filled.LocalGasStation, if (y) "petrol station" else "gas station"),
        Triple("EV charging", Icons.Filled.EvStation, "ev charging station"),
        Triple("Coffee", Icons.Filled.LocalCafe, "coffee"), Triple("Food", Icons.Filled.Restaurant, "restaurants"),
        Triple(if (y) "Supermarket" else "Groceries", Icons.Filled.ShoppingCart, if (y) "supermarket" else "grocery store"),
        Triple(if (y) "Toilets" else "Restrooms", Icons.Filled.Wc, if (y) "public toilets" else "public restroom"),
        Triple("Parking", Icons.Filled.LocalParking, "parking"), Triple("Pharmacy", Icons.Filled.LocalPharmacy, "pharmacy"),
    )
    fun run(query: String, label: String) {
        picked = label
        searching = true
        found = emptyList()
        val line = nav.route.polyline
        val cum = Geo.cumulative(line)
        val here = nav.progress?.alongMeters ?: 0.0
        val total = cum.lastOrNull() ?: 0.0
        // The next stretch of road, up to 40 km: search around its middle, wide enough to cover it.
        val end = min(total, here + 40_000)
        val mid = Geo.point(line, (here + end) / 2, cum)
        val span = max(end - here, 3_000.0)
        scope.launch {
            val r = withContext(Dispatchers.IO) { PlaceSearch.search(query, mid, span, !s.noGoogle) }
            found = r.places.mapNotNull { p ->
                val at = Geo.project(p.location, line, cum)
                // Close to the road (a short detour) and still ahead of you.
                if (at.offset < 800 && at.along > here + 100) p to (at.along - here) else null
            }.sortedBy { it.second }.take(20)
            searching = false
        }
    }
    TripSheet("Search along route", "Close", close) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                    kinds.forEach { (label, icon, q) ->
                        Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (picked == label) Vela.routeBlue.copy(alpha = 0.22f) else secondaryText.copy(alpha = 0.12f))
                            .tap { run(q, label) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(icon, null, tint = primaryText, modifier = Modifier.size(20.dp))
                            Text(label, fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = primaryText, maxLines = 1)
                        }
                    }
                }
            }
            if (searching) item {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Looking along your route…", color = secondaryText)
                }
            } else if (picked != null && found.isEmpty()) item {
                Text("Nothing found close to the road ahead.", color = secondaryText, modifier = Modifier.padding(18.dp))
            }
            if (found.isNotEmpty()) {
                item { SectionTitle("Ahead on your route") }
                items(found, key = { it.first.id }) { (p, ahead) ->
                    Row(Modifier.fillMaxWidth().tap { nav.addStop(p); close() }.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(p.rating?.let { String.format("%.1f ★", it) }, p.statusText).joinToString("  "), fontSize = KType.caption,
                                color = secondaryText, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("in ${Fmt.distance(ahead)}", fontSize = KType.subheadline, fontWeight = FontWeight.Medium, color = primaryText)
                            Text("Add stop", fontSize = KType.caption, fontWeight = FontWeight.SemiBold, color = Vela.routeBlue)
                        }
                    }
                }
            }
        }
    }
}

/** Road numbers in an instruction ("I-80", "US 50", "A9", "M4"), for shields in the banner. */
object RoadRefs {
    private val re = Regex("""\b(I|US|CA|SR|State Route|Route|Hwy|Highway|A|B|M|E|N|D|SS)[- ]?(\d{1,4}[A-Z]?)\b""")
    fun find(text: String): List<String> {
        val out = mutableListOf<String>()
        for (m in re.findAll(text)) {
            val net = m.groupValues[1]; val num = m.groupValues[2]
            val ref = when (net) {
                "I" -> "I-$num"
                "US" -> "US $num"
                "CA", "SR", "State Route", "Route", "Hwy", "Highway" -> "SR $num"
                else -> net + num
            }
            if (ref !in out) out += ref
        }
        return out.take(3)
    }
}

/** A road number drawn like its sign: British blue or green plates, Interstate blue-and-red, US white, others plain. */
@Composable
fun RoadShield(ref: String) {
    val british = if (!Fmt.yards) null else when {
        Regex("^(M\\d+|A\\d+\\(M\\))$").matches(ref) -> true
        Regex("^A\\d+$").matches(ref) -> false
        else -> null
    }
    when {
        ref.startsWith("I-") -> {
            val n = ref.drop(2)
            Box(Modifier.size(if (n.length > 2) 40.dp else 32.dp, 32.dp).clip(ShieldShape).background(hex(0x003F87)).border(1.5.dp, Color.White, ShieldShape)) {
                Box(Modifier.fillMaxWidth().height(9.dp).background(hex(0xC8102E)))
                Text(n, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 9.dp))
            }
        }
        ref.startsWith("US ") -> Box(Modifier.defaultMinSize(minWidth = 30.dp).height(30.dp).clip(ShieldShape).background(Color.White)
            .border(1.5.dp, Color.Black, ShieldShape).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text(ref.drop(3), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
        }
        british != null -> Box(Modifier.height(26.dp).clip(RoundedCornerShape(4.dp)).background(if (british) hex(0x0079C1) else hex(0x00703C))
            .padding(1.5.dp).border(1.dp, Color.White, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            // British plates: blue for motorways, green with yellow numbers for primary A roads.
            Text(ref, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = if (british) Color.White else hex(0xFFD200))
        }
        else -> Box(Modifier.height(26.dp).clip(RoundedCornerShape(5.dp)).background(Color.White).padding(1.5.dp)
            .border(1.5.dp, Color.Black, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            Text(if (ref.startsWith("SR ")) ref.drop(3) else ref, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
        }
    }
}

/** A shield outline: flat top with dips, sides curving into a point. */
private val ShieldShape = GenericShape { size, _ ->
    val w = size.width; val h = size.height
    moveTo(0f, h * 0.08f)
    quadraticTo(w * 0.25f, -h * 0.04f, w / 2, h * 0.06f)
    quadraticTo(w * 0.75f, -h * 0.04f, w, h * 0.08f)
    cubicTo(w * 1.05f, h * 0.6f, w * 0.8f, h * 0.95f, w / 2, h)
    cubicTo(w * 0.2f, h * 0.95f, -w * 0.05f, h * 0.6f, 0f, h * 0.08f)
    close()
}

/** Which lanes to be in, the way Waze and Google draw it: the right ones lit up, the rest dim. */
@Composable
fun LaneStrip(lanes: List<Lane>, turn: ManeuverType, exitAngle: Double?) {
    // A lane that goes several ways shows the way this turn goes when it's a good lane.
    fun pick(lane: Lane): String {
        if (!lane.valid || lane.indications.size <= 1) return lane.indications.firstOrNull() ?: "straight"
        var t = turn
        if (t == ManeuverType.ROUNDABOUT && exitAngle != null) {
            t = if (exitAngle > 200 && exitAngle < 340) ManeuverType.TURN_LEFT else if (exitAngle > 20 && exitAngle < 160) ManeuverType.TURN_RIGHT else ManeuverType.STRAIGHT
        }
        val want = when (t) {
            ManeuverType.TURN_LEFT, ManeuverType.SHARP_LEFT, ManeuverType.RAMP_LEFT -> listOf("left", "sharp left", "slight left")
            ManeuverType.SLIGHT_LEFT, ManeuverType.FORK_LEFT, ManeuverType.KEEP_LEFT -> listOf("slight left", "left")
            ManeuverType.TURN_RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.RAMP_RIGHT -> listOf("right", "sharp right", "slight right")
            ManeuverType.SLIGHT_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.KEEP_RIGHT -> listOf("slight right", "right")
            ManeuverType.UTURN -> listOf("uturn")
            else -> listOf("straight")
        }
        return want.firstOrNull { it in lane.indications } ?: lane.indications[0]
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        lanes.take(6).forEach { lane ->
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(if (lane.valid) Color.White else Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center) {
                Icon(laneIcon(pick(lane)), null, tint = if (lane.valid) Vela.navBand else Color.White.copy(alpha = 0.4f), modifier = Modifier.size(32.dp))
            }
        }
    }
}

fun laneIcon(indication: String): ImageVector = when (indication) {
    "left" -> Icons.Filled.TurnLeft
    "sharp left" -> Icons.Filled.TurnSharpLeft
    "slight left" -> Icons.Filled.TurnSlightLeft
    "right" -> Icons.Filled.TurnRight
    "sharp right" -> Icons.Filled.TurnSharpRight
    "slight right" -> Icons.Filled.TurnSlightRight
    "uturn" -> Icons.Filled.UTurnLeft
    else -> Icons.Filled.ArrowUpward
}

/**
 * The roundabout as it really is: the way in at the bottom, every road around the circle, and
 * only your way round and out lit up. Without the real layout, the exits are spread evenly.
 */
@Composable
fun RoundaboutDiagram(shape: RoundaboutShape?, exit: Int, clockwise: Boolean, modifier: Modifier = Modifier) {
    val l = remember(shape, exit, clockwise) {
        if (shape != null && shape.taken in shape.exits.indices) shape else {
            // Evenly spread up to yours, a little short of the full circle.
            val n = max(exit, 1)
            val step = min(90.0, 300.0 / (n + 1))
            val sign = if (clockwise) 1 else -1
            RoundaboutShape(exits = (1..n).map { k -> ((180 + sign * k * step) + 360) % 360 }, taken = n - 1, clockwise = clockwise, complete = false)
        }
    }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val font = Rounded
    Canvas(modifier) {
        val w = size.width
        val c = Offset(w / 2, size.height / 2)
        val ring = w * 0.2f
        val edge = w * 0.47f
        fun pt(deg: Double, r: Float): Offset {
            val a = Math.toRadians(deg)
            return Offset(c.x + (sin(a) * r).toFloat(), c.y - (cos(a) * r).toFloat())
        }
        val faint = Color.White.copy(alpha = 0.32f)
        val roadW = w * 0.075f
        // Every road and the circle, dim.
        for (a in l.exits + l.entriesOnly + listOf(180.0)) {
            drawLine(faint, pt(a, ring), pt(a, if (a in l.entriesOnly) edge * 0.85f else edge), roadW, StrokeCap.Round)
        }
        drawCircle(faint, ring, c, style = Stroke(roadW))
        // Yours: in from the bottom, round the way traffic goes, and out.
        val out = l.exits[l.taken]
        val sweep = l.turn(l.taken)
        val path = Path().apply {
            val s0 = pt(180.0, edge); moveTo(s0.x, s0.y)
            val s1 = pt(180.0, ring); lineTo(s1.x, s1.y)
            val steps = max((sweep / 6).toInt(), 2)
            val sign = if (l.clockwise) 1 else -1
            for (i in 1..steps) { val p = pt(180 + sign * sweep * i / steps, ring); lineTo(p.x, p.y) }
            val tip = pt(out, edge - w * 0.07f); lineTo(tip.x, tip.y)
        }
        drawPath(path, Color.White, style = Stroke(w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // The arrowhead on the way out.
        val tip = pt(out, edge - w * 0.07f)
        val a = Math.toRadians(out)
        val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
        val side = Offset(-dir.y, dir.x)
        val hw = w * 0.12f; val len = w * 0.14f
        val base = tip - dir * 2f
        val head = Path().apply {
            val nose = base + dir * len; moveTo(nose.x, nose.y)
            val left = base + side * hw; lineTo(left.x, left.y)
            val right = base - side * hw; lineTo(right.x, right.y)
            close()
        }
        drawPath(head, Color.White)
        // The exit number in the middle, as well as "6th exit" beside the picture.
        val t = measurer.measure("$exit", androidx.compose.ui.text.TextStyle(color = Color.White, fontWeight = FontWeight.ExtraBold, fontFamily = font,
            fontSize = (w * (if (exit >= 10) 0.17f else 0.22f) / density).sp))
        drawText(t, topLeft = Offset(c.x - t.size.width / 2, c.y - t.size.height / 2))
    }
}
