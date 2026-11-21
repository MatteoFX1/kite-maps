@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kite.*
import app.kite.core.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun PlaceHeader(model: AppModel, place: Place) {
    val dark = LocalDark.current
    val ctx = Phone
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                Copyable(place.name) {
                    Text(place.name, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = primaryText, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, lineHeight = 30.sp)
                }
            }
            CircleIconButton(if (AppSettings.isSaved(place)) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder) { AppSettings.toggleSaved(place) }
            CircleIconButton(Icons.Filled.Share) { ctx.share(place.shareUrl) }
            CircleIconButton(Icons.Filled.Close) { model.closePlace() }
        }
        // Tapping the stars opens the reviews (GitHub #70).
        Box(Modifier.clickable(enabled = place.rating != null) { model.showReviews = place.id; model.detent = SheetDetent.FULL }) {
            PlaceMetaLine(place, here = model.userLatLng)
        }
        StatusText(place)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f).heightIn(min = 56.dp).clip(CircleShape).background(Vela.accent(dark))
                .clickable { model.openDirections(place) }, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Directions, null, tint = Vela.onAccent(dark), modifier = Modifier.size(24.dp))
                Text("Directions", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Vela.onAccent(dark))
            }
            Row(Modifier.heightIn(min = 56.dp).clip(CircleShape).background(Vela.tonal(dark))
                .clickable { model.openDirections(place); model.autoStart = true }.padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Navigation, null, tint = Vela.onTonal(dark), modifier = Modifier.size(22.dp))
                Text("Go", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Vela.onTonal(dark))
            }
        }
    }
}

@Composable
fun PlaceBody(model: AppModel, place: Place) {
    val ctx = Phone
    var tab by remember(place.id) { mutableIntStateOf(0) }
    var driveSeconds by remember(place.id) { mutableStateOf<Double?>(null) }
    LaunchedEffect(place.id) {
        val from = model.userLatLng ?: return@LaunchedEffect
        val d = from.distanceTo(place.location)
        if (d <= 300 || d >= 300_000) return@LaunchedEffect
        val s = model.settings
        var r: List<Route>? = null
        if (!s.noGoogle) r = runCatching { GoogleClient.shared.directions(from, place.location, TravelMode.DRIVE, s.avoidHighways, s.avoidTolls) }.getOrNull()
        if (r.isNullOrEmpty()) r = runCatching { OSRM.route(from, place.location, TravelMode.DRIVE) }.getOrNull()
        driveSeconds = r?.minOfOrNull { it.durationSeconds }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PlaceActions(model, place, driveSeconds)
        PhotoStrip(place.photoUrls, 140.dp, opensViewer = true, streetView = place, model = model)
        // Google's places have reviews to read; open map data has none.
        val hasReviews = place.source == "google" && place.rating != null && !AppSettings.noGoogle
        LaunchedEffect(model.showReviews) {
            if (model.showReviews == place.id && hasReviews) { tab = 2; model.showReviews = null }
        }
        val tabs = listOfNotNull(0 to "Overview", if (hasReviews) 2 to "Reviews" else null, if (place.about.isNotEmpty()) 1 to "About" else null)
        if (tabs.size > 1) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { i, (id, label) ->
                    SegmentedButton(tab == id, { tab = id }, SegmentedButtonDefaults.itemShape(i, tabs.size)) { Text(label) }
                }
            }
        }
        when (tab) {
            1 -> PlaceAbout(place)
            2 -> ReviewsList(place)
            else -> PlaceOverview(place)
        }
        Text(if (place.source == "google") "Place details from Google Maps" else "Map data © OpenStreetMap contributors",
            fontSize = KType.caption, color = secondaryText)
    }
}

@Composable
private fun PlaceActions(model: AppModel, place: Place, driveSeconds: Double?) {
    val ctx = Phone
    val dark = LocalDark.current
    var more by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        driveSeconds?.let {
            Row(Modifier.height(52.dp).border(1.dp, secondaryText.copy(alpha = 0.35f), CircleShape).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.DirectionsCar, null, tint = primaryText, modifier = Modifier.size(20.dp))
                Text("${Fmt.duration(it)} drive", fontWeight = FontWeight.Bold, color = primaryText)
            }
        }
        telUrl(place.phone)?.let { PillButton("Call", Icons.Filled.Phone) { ctx.open(it) } }
        webUrl(place.website)?.let { PillButton("Website", Icons.Filled.Language) { ctx.open(it) } }
        if (place.id == model.settings.parking?.id) PillButton("Clear spot", Icons.Outlined.Cancel) { model.clearParking() }
        Box {
            Row(Modifier.height(40.dp).clip(CircleShape).background(Vela.tonal(dark)).clickable { more = true }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.MoreHoriz, null, tint = Vela.onTonal(dark), modifier = Modifier.size(18.dp))
                Text("More", fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = Vela.onTonal(dark))
            }
            DropdownMenu(more, { more = false }) {
                fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, f: () -> Unit) = @Composable {
                    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = { more = false; f() })
                }
                if (model.settings.home?.id == place.id) item("Remove home", Icons.Filled.Home) { model.setHome(null) }()
                else item("Set as home", Icons.Outlined.Home) { model.setHome(place) }()
                if (model.settings.work?.id == place.id) item("Remove work", Icons.Filled.Work) { model.setWork(null) }()
                else item("Set as work", Icons.Outlined.WorkOutline) { model.setWork(place) }()
                item("Copy coordinates", Icons.Filled.ContentCopy) {
                    ctx.copy(fmt("%.6f, %.6f", place.location.lat, place.location.lng))
                }()
                place.address?.let { a -> item("Copy address", Icons.Filled.ContentPaste) { ctx.copy("${place.name}, $a") }() }
                HorizontalDivider()
                Text("Open in", fontSize = KType.caption, color = secondaryText, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                for (app in OtherApp.entries) item(app.label, Icons.Filled.Map) { ctx.open(app.url(place)) }()
            }
        }
    }
}

/** A bus stop or a station, which gets a live departure board. */
private fun isStop(p: Place): Boolean {
    val text = ((p.category ?: "") + " " + p.name).lowercase()
    return listOf("station", "bus stop", "stop ", "transit", "subway", "metro", "tram", "light rail", "terminal", "depot").any { text.contains(it) }
}

@Composable
private fun PlaceOverview(place: Place) {
    val ctx = Phone
    var hoursOpen by remember(place.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        place.editorialSummary?.let { s -> Copyable(s) { Text(s, color = primaryText) } }
        place.address?.let { a -> Copyable(a) { InfoRow(Icons.Outlined.Place, a) } }
        if (isStop(place)) DepartureBoard(place)
        if (place.hours.isNotEmpty()) Copyable(place.hours.joinToString("\n"), onClick = { hoursOpen = !hoursOpen }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Schedule, null, tint = secondaryText, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        StatusText(place)
                        if (!hoursOpen) place.hours.firstOrNull()?.let { Text(it, fontSize = KType.subheadline, color = secondaryText) }
                    }
                    Icon(if (hoursOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = secondaryText)
                }
                if (hoursOpen) for (h in place.hours) {
                    val parts = h.split(":", limit = 2)
                    Row(Modifier.padding(start = 36.dp)) {
                        Text(parts.first(), fontSize = KType.subheadline, color = primaryText, modifier = Modifier.width(110.dp))
                        Text(if (parts.size > 1) parts[1].trim() else "", fontSize = KType.subheadline, color = primaryText)
                    }
                }
            }
        }
        place.phone?.let { phone -> Copyable(phone, onClick = { telUrl(phone)?.let { ctx.open(it) } }) { InfoRow(Icons.Outlined.Phone, phone) } }
        place.website?.let { site ->
            Copyable(site, onClick = { webUrl(site)?.let { ctx.open(it) } }) {
                InfoRow(Icons.Filled.Language, site.removePrefix("https://").removePrefix("http://"))
            }
        }
        val coords = fmt("%.6f, %.6f", place.location.lat, place.location.lng)
        Copyable(coords) { InfoRow(Icons.Filled.GpsFixed, coords) }
        place.featuredReview?.let { r ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Review", fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.AccountCircle, null, tint = secondaryText, modifier = Modifier.size(28.dp))
                    Text("“$r”", fontSize = KType.subheadline, color = primaryText)
                }
            }
        }
    }
}

@Composable
private fun PlaceAbout(place: Place) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (section in place.about) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(section.title, fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText)
                for (item in section.items) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, null, tint = secondaryText, modifier = Modifier.size(14.dp))
                    Text(item, fontSize = KType.subheadline, color = primaryText)
                }
            }
        }
    }
}

@Composable
fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = secondaryText, modifier = Modifier.size(22.dp))
        Text(text, fontSize = KType.subheadline, color = primaryText, modifier = Modifier.weight(1f))
    }
}

/** Next departures at a stop from Transitous, refreshed every 30 seconds while it's open. */
@Composable
fun DepartureBoard(place: Place) {
    var board by remember(place.id) { mutableStateOf<Transitous.Board?>(null) }
    var loaded by remember(place.id) { mutableStateOf(false) }
    LaunchedEffect(place.id) {
        while (true) {
            val b = runCatching { Transitous.departures(place.location) }.getOrNull()
            if (b != null || !loaded) board = b
            loaded = true
            delay(30_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Departures", fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText)
        val b = board
        when {
            b != null -> {
                for (d in b.departures.take(8)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineChip(TransitLeg(isWalk = false, mode = d.mode, line = d.line, color = d.color, textColor = d.textColor))
                    Text(d.headsign, fontSize = KType.subheadline, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(countdown(d.time), fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold,
                            color = if (d.delayMinutes != null) Vela.open else primaryText)
                        d.delayMinutes?.takeIf { it != 0 }?.let { late ->
                            Text(if (late > 0) "$late min late" else "${-late} min early", fontSize = KType.caption2,
                                color = if (late > 0) Vela.closed else Vela.open)
                        }
                    }
                }
                Text("Live times in green · from Transitous", fontSize = KType.caption, color = secondaryText)
            }
            loaded -> Text("No departures found here.", fontSize = KType.subheadline, color = secondaryText)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Loading departures…", color = secondaryText)
            }
        }
    }
}

private fun countdown(t: Long): String {
    val mins = ((t - nowMillis()) / 60_000.0).roundToInt()
    if (mins <= 0) return "Now"
    if (mins < 60) return "$mins min"
    return Fmt.time(t)
}

@Composable
fun PhotoStrip(all: List<String>, height: Dp = 130.dp, opensViewer: Boolean = false, streetView: Place? = null, model: AppModel? = null) {
    // "Use Kite without Google": a place saved earlier still has Google's photo links; don't load them.
    val urls = if (AppSettings.noGoogle) all.filterNot(::isGooglePhoto) else all
    var viewing by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (streetView != null && model != null) StreetViewTile(model, streetView, height)
        urls.forEachIndexed { i, u ->
            AsyncImage(u, null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(height * 1.4f, height).clip(RoundedCornerShape(10.dp)).background(secondaryText.copy(alpha = 0.15f))
                    .then(if (opensViewer) Modifier.clickable { viewing = i } else Modifier))
        }
    }
    viewing?.let { start -> PhotoViewer(urls, start) { viewing = null } }
}

fun isGooglePhoto(u: String): Boolean {
    val host = u.substringAfter("://", "").substringBefore("/").substringBefore("?").substringAfter("@").substringBefore(":").lowercase().ifEmpty { return false }
    return "google" in host || "gstatic" in host || "ggpht" in host
}

/** Google photo links carry their size ("=w408-h306-k-no"): ask for a bigger one. */
fun largePhoto(u: String): String = u.replace(Regex("=w\\d+-h\\d+"), "=w1600-h1200")

/** Photos full screen, swiped through. */
@Composable
fun PhotoViewer(urls: List<String>, start: Int, close: () -> Unit) {
    FullScreenDialog(close) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val pager = rememberPagerState(start) { urls.size }
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                AsyncImage(largePhoto(urls[i]), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).size(40.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f)).clickable(onClick = close), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
        }
    }
}

/** The first tile of a place's photos: Google Street View looking at the place. */
@Composable
fun StreetViewTile(model: AppModel, place: Place, height: Dp) {
    var pano by remember(place.id) { mutableStateOf<StreetView.Panorama?>(null) }
    var loaded by remember(place.id) { mutableStateOf(false) }
    LaunchedEffect(place.id) {
        if (!AppSettings.noGoogle) pano = runCatching { StreetView.find(place.location) }.getOrNull()
        loaded = true
        // Screenshot run only: open the viewer straight away.
        val p = pano
        if (p != null && DemoScript.step == "streetview") model.streetView = StreetViewRequest(place.name, p, Geo.bearing(p.location, place.location))
    }
    val p = pano
    if (p != null) {
        val heading = Geo.bearing(p.location, place.location)
        Box(Modifier.size(height * 1.4f, height).clip(RoundedCornerShape(10.dp))
            .clickable { model.streetView = StreetViewRequest(place.name, p, heading) }) {
            AsyncImage(StreetView.thumbnailUrl(p, heading), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(secondaryText.copy(alpha = 0.15f)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Streetview, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("Street View", fontSize = KType.caption, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    } else if (!loaded) {
        Box(Modifier.size(height * 1.4f, height).clip(RoundedCornerShape(10.dp)).background(secondaryText.copy(alpha = 0.15f)))
    }
}

/** A transit line's badge in its own colors. */
@Composable
fun LineChip(leg: TransitLeg) {
    val bg = leg.color?.let { Color(0xFF000000 or it) } ?: Vela.routeBlue
    val fg = leg.textColor?.let { Color(0xFF000000 or it) } ?: Color.White
    Row(Modifier.height(22.dp).clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(vehicleIcon(leg.vehicle), null, tint = fg, modifier = Modifier.size(12.dp))
        Text(leg.line.ifEmpty { leg.mode.lowercase().replaceFirstChar { it.uppercase() } }, fontSize = KType.caption, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
    }
}

fun vehicleIcon(v: TransitLeg.Vehicle) = when (v) {
    TransitLeg.Vehicle.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    TransitLeg.Vehicle.BUS -> Icons.Filled.DirectionsBus
    TransitLeg.Vehicle.METRO -> Icons.Filled.Subway
    TransitLeg.Vehicle.TRAM -> Icons.Filled.Tram
    TransitLeg.Vehicle.TRAIN -> Icons.Filled.Train
    TransitLeg.Vehicle.FERRY -> Icons.Filled.DirectionsBoat
    TransitLeg.Vehicle.OTHER -> Icons.Filled.DirectionsTransit
}

/**
 * A place's Google reviews inside Kite (GitHub #70): stars, who, when and what they said, the ones
 * Google Maps shows first. When Google won't list them, a button opens them on Google.
 */
@Composable
fun ReviewsList(place: Place) {
    val ctx = Phone
    val dark = LocalDark.current
    // Google's list of reviews stopped answering apps (checked 2026-11-21): the one review Google
    // sends with the place, and the full list on Google's own page.
    val reviews: List<GoogleReview>? = null
    val failed = true
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        place.rating?.let { rating ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(fmt("%.1f", rating), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = primaryText)
                Column {
                    Text(starsText(rating), fontSize = 18.sp, color = Color(0xFFF59E0B))
                    place.reviewCount?.let { Text("${LocalFormat.count(it)} reviews on Google", fontSize = KType.subheadline, color = secondaryText) }
                }
            }
        }
        val list = reviews
        if (list != null) {
            list.forEach { r ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.AccountCircle, null, tint = secondaryText, modifier = Modifier.size(30.dp))
                        Column {
                            Text(r.author, fontSize = KType.subheadline, fontWeight = FontWeight.Bold, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(r.rating?.let { starsText(it.toDouble()) }, r.whenText).joinToString("  "), fontSize = KType.caption, color = secondaryText)
                        }
                    }
                    Text(r.text, fontSize = KType.subheadline, color = primaryText)
                }
                HorizontalDivider()
            }
        } else if (!failed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Loading reviews…", color = secondaryText)
            }
        }
        if ((failed || list?.isEmpty() == true) && place.featuredReview != null) {
            Text("“${place.featuredReview}”", fontSize = KType.subheadline, color = primaryText)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape).background(Vela.tonal(dark))
            .clickable { ctx.open(GoogleReviews.webUrl(place)) }, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.RateReview, null, tint = Vela.onTonal(dark), modifier = Modifier.size(20.dp))
            Text(if (list?.isNotEmpty() == true) "More reviews on Google" else "Read the reviews on Google", fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, color = Vela.onTonal(dark))
        }
    }
}

private fun starsText(rating: Double): String = (0 until 5).joinToString("") { if (rating - it >= 0.5) "★" else "☆" }
