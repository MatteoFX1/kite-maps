@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kite.*
import app.kite.core.*

/** The map page: a big "Where to?" bar, Home and Work one tap away, and the results or place sheet. */
@Composable
fun BrowseOverlay(model: AppModel) {
    val dark = LocalDark.current
    val ctx = Phone
    val s = AppSettings
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val hasSheet = model.selected != null || model.results.isNotEmpty()
    val idle = !hasSheet && !focused

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val full = maxHeight
        val sheetH = if (!hasSheet) 0.dp else when (model.detent) {
            SheetDetent.PEEK -> 230.dp
            SheetDetent.HALF -> full * 0.46f
            SheetDetent.FULL -> full * 0.94f
        }
        val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }

        // Map buttons: layers, parking and the 3D/2D/north-up button on the right, under the top cards.
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = if (idle) (if (model.resumeOffer != null) 266.dp else 166.dp) else 76.dp)) {
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MapFab(Icons.Outlined.Layers) { model.showLayers = true }
                    if (s.parkingButton || s.parking != null) {
                        MapFab(Icons.Filled.LocalParking, tint = if (s.parking != null) Vela.routeBlue else null) {
                            val p = s.parking
                            if (p != null) model.select(p) else model.saveParking()
                        }
                    }
                    // 3D, 2D or north up, always there. On north up with the map turned by hand,
                    // the needle shows which way north is and a tap faces north again.
                    MapLookButton(model.browseLook, model.mapHeading, 48.dp) { model.cycleBrowseLook() }
                }
            }
            Spacer(Modifier.weight(1f))
            if (sheetH < full * 0.6f) {
                Row(Modifier.fillMaxWidth().padding(bottom = if (hasSheet) (sheetH - navBottom).coerceAtLeast(0.dp) + 14.dp else 86.dp),
                    verticalAlignment = Alignment.Bottom) {
                    if (idle) WazeMapChips(model)
                    Spacer(Modifier.weight(1f))
                    LocateButton(model)
                }
            }
        }

        AnimatedVisibility(hasSheet, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            val place = model.selected
            if (place != null) {
                key(place.id) {
                    BottomSheet(model.detent, { model.detent = it }, full, 230.dp,
                        header = { PlaceHeader(model, place) }, content = { PlaceBody(model, place) })
                }
            } else {
                BottomSheet(model.detent, { model.detent = it }, full, 230.dp,
                    header = { ResultsHeader(model) }, content = { ResultsList(model) })
            }
        }

        AnimatedVisibility(idle, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            KiteDock(model)
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchBar(model, focused, { focused = it }, unfocus = { focus.clearFocus(); focused = false })
            if (focused) SuggestionsPanel(model) { focus.clearFocus(); focused = false }
            val n = model.notice
            if (n != null && !focused) NoticeBanner(n) { model.notice = null }
            if (!focused) StatusCards()
            // Kite closed in the middle of a drive: one big button to carry on.
            val trip = model.resumeOffer
            if (idle && trip != null) ResumeCard(trip, onGo = { model.resumeTrip() }, onClose = { model.dismissResume() })
        }
    }
    // The back button closes things one at a time, like the iPhone's back arrows.
    // Screenshot run only: the search box open, to check nothing sits on its panel.
    if (DemoScript.step == "searchfocus") LaunchedEffect(Unit) { kotlinx.coroutines.delay(8000); focused = true }
    androidx.compose.ui.backhandler.BackHandler(enabled = focused || hasSheet) {
        if (focused) { focus.clearFocus(); focused = false } else model.clearSearch()
    }
}

/** "Centre on me", with sharing and parking on a long press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocateButton(model: AppModel) {
    val dark = LocalDark.current
    val ctx = Phone
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(52.dp).glass(CircleShape).combinedClickable(onClick = { model.centerOnUser() }, onLongClick = { menu = true }),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MyLocation, "Centre on me", tint = Vela.accentText(dark), modifier = Modifier.size(24.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            model.userLatLng?.let { u ->
                DropdownMenuItem(text = { Text("Share my location") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
                    onClick = { menu = false; ctx.share("https://maps.google.com/?q=${u.lat.plain()},${u.lng.plain()}") })
                DropdownMenuItem(text = { Text("Copy my coordinates") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    onClick = { menu = false; ctx.copy(fmt("%.6f, %.6f", u.lat, u.lng)) })
            }
            DropdownMenuItem(text = { Text("I parked here") }, leadingIcon = { Icon(Icons.Filled.LocalParking, null) },
                onClick = { menu = false; model.saveParking() })
        }
    }
}

/** "Where to?": the one way into search, big enough to hit from a phone holder. */
@Composable
fun SearchBar(model: AppModel, focused: Boolean, onFocus: (Boolean) -> Unit, unfocus: () -> Unit) {
    val dark = LocalDark.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(focused) { if (focused) runCatching { requester.requestFocus() } }
    var listening by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { Dictation.stop() } }
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(54.dp).glass(CircleShape).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (focused || model.results.isNotEmpty() || model.selected != null) {
            Box(Modifier.size(44.dp).clip(CircleShape).tap { if (focused) unfocus() else model.clearSearch() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = primaryText, modifier = Modifier.size(26.dp))
            }
        } else {
            Icon(Icons.Filled.Search, null, tint = Vela.muted(dark), modifier = Modifier.padding(start = 8.dp).size(24.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (model.query.isEmpty()) Text(if (listening) "Listening\u2026" else "Where to?", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Vela.muted(dark))
            BasicTextField(model.query, { q -> model.query = q; if (focused) model.typed(q) },
                Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { if (it.isFocused != focused) onFocus(it.isFocused) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium, color = primaryText, fontFamily = Rounded),
                cursorBrush = SolidColor(Vela.accent(dark)),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { unfocus(); model.search() }))
        }
        when {
            model.searching -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 3.dp, color = Vela.accent(dark))
            model.query.isNotEmpty() || model.results.isNotEmpty() || model.selected != null ->
                Box(Modifier.size(48.dp).clip(CircleShape).tap { model.clearSearch(); unfocus() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Clear search", tint = primaryText, modifier = Modifier.size(22.dp))
                }
            else -> Box(Modifier.size(48.dp).clip(CircleShape).tap {
                unfocus()
                // Phones without Google (like GrapheneOS) may have no speech service at all: say so plainly.
                if (listening) { Dictation.stop(); listening = false }
                else if (!Dictation.available()) {
                    model.notice = if (isIos) "Voice search isn't available right now. Type where you're going instead."
                        else "Voice search needs a speech app on this phone. Type where you're going instead."
                } else {
                    listening = true
                    Dictation.listen { text ->
                        listening = false
                        if (!text.isNullOrBlank()) { model.query = text; model.search(text) }
                    }
                }
            }, contentAlignment = Alignment.Center) {
                Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) "Stop listening" else "Search by voice",
                    tint = if (listening) Vela.accent(dark) else primaryText, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
fun SuggestionsPanel(model: AppModel, done: () -> Unit) {
    val s = AppSettings
    // Only checks whether the clipboard looks like a link (its type), without reading it.
    val copiedLink = remember { Phone.hasCopiedText() }
    Column(Modifier.padding(horizontal = 12.dp).heightIn(max = 460.dp).glass(RoundedCornerShape(20.dp))
        .verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        val sug = model.suggestions
        if (model.query.isNotEmpty() && !(sug.places.isEmpty() && sug.queries.isEmpty())) {
            sug.places.take(6).forEach { p ->
                IconRow(Icons.Filled.Place, p.name, p.address, p.distanceMeters?.let { Fmt.distance(it) }, height = 54.dp) { done(); model.openSuggestion(p) }
            }
            sug.queries.take(5).forEach { q -> IconRow(Icons.Filled.Search, q) { done(); model.search(q) } }
        } else {
            if (copiedLink) {
                Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Filled.Link, null, tint = secondaryText, modifier = Modifier.size(22.dp))
                    Text("Open a copied map link", color = primaryText, modifier = Modifier.weight(1f))
                    Text("Paste", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).background(Vela.routeBlue).tap {
                            val text = Phone.paste()
                            if (!text.isNullOrBlank()) { done(); model.openLink(text) }
                        }.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            Shortcuts(model, done)
            if (s.recentPlaces.isNotEmpty() || s.recents.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 14.dp, bottom = 4.dp)) {
                    Text("Recent", fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = secondaryText, modifier = Modifier.weight(1f))
                    Text("Clear", fontSize = KType.subheadline, color = Vela.routeBlue, modifier = Modifier.tap { s.recents = emptyList(); s.recentPlaces = emptyList() })
                }
                val shown = s.recentPlaces.take(5)
                shown.forEach { p ->
                    var menu by remember(p.id) { mutableStateOf(false) }
                    Box {
                        IconRow(Icons.Filled.Schedule, p.name, p.address ?: p.category,
                            model.userLatLng?.let { Fmt.distance(it.distanceTo(p.location)) },
                            onLongClick = { menu = true }) { done(); model.openSuggestion(p) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Remove from recents") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = { menu = false; s.recentPlaces = s.recentPlaces.filter { it.id != p.id } })
                        }
                    }
                }
                s.recents.filter { q -> shown.none { it.name == q } }.take(6).forEach { q ->
                    var menu by remember(q) { mutableStateOf(false) }
                    Box {
                        IconRow(Icons.Filled.Search, q, onLongClick = { menu = true }) { done(); model.search(q) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Remove from recents") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = { menu = false; s.recents = s.recents.filter { it != q } })
                        }
                    }
                }
            }
            // Recents first, like every maps app: most searches are for somewhere you've been.
            Text("Nearby", fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = secondaryText,
                modifier = Modifier.padding(horizontal = 18.dp).padding(top = 14.dp, bottom = 4.dp))
            Category.all.forEach { c -> IconRow(c.icon, c.label) { done(); model.search(c.query, title = c.label) } }
        }
    }
}
