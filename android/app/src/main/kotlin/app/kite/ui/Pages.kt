@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kite.*
import app.kite.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun hex(v: Long) = Color(0xFF000000 or v)

/** The Saved page of the dock: Home and Work as big tiles, then every saved place with its own Go. */
@Composable
fun SavedPage(model: AppModel) {
    val dark = LocalDark.current
    val s = AppSettings
    var renaming by remember { mutableStateOf<Place?>(null) }
    var newName by remember { mutableStateOf("") }
    fun go(p: Place) { model.tab = AppTab.MAP; model.openDirections(p) }
    fun show(p: Place) { model.tab = AppTab.MAP; model.select(p) }
    fun distance(p: Place) = model.userLatLng?.let { Fmt.distance(it.distanceTo(p.location)) }

    Box(Modifier.fillMaxSize().background(Vela.ground(dark))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Saved", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = primaryText, modifier = Modifier.padding(horizontal = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MineTile(model, Icons.Filled.Home, Vela.routeBlue, "Home", s.home, PickKind.HOME, ::go, ::distance)
                MineTile(model, Icons.Filled.Work, hex(0x8B5CF6), "Work", s.work, PickKind.WORK, ::go, ::distance)
            }
            s.parking?.let { car ->
                Box(Modifier.clip(RoundedCornerShape(24.dp)).background(Vela.surface(dark))) {
                    SavedRow(car, Icons.Filled.LocalParking, Vela.routeBlue, distance(car), null, ::show, ::go)
                }
            }
            if (s.saved.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.BookmarkBorder, null, tint = Vela.muted(dark), modifier = Modifier.size(44.dp))
                    Text("No saved places yet", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = primaryText)
                    Text("Open any place and tap Save to keep it here.", fontSize = KType.body, color = Vela.muted(dark), textAlign = TextAlign.Center)
                }
            } else {
                Column(Modifier.clip(RoundedCornerShape(24.dp)).background(Vela.surface(dark))) {
                    s.saved.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 78.dp))
                        SavedRow(p, Icons.Filled.Bookmark, hex(0xF59E0B), distance(p), { action ->
                            if (action == 0) { newName = p.name; renaming = p } else s.saved = s.saved.filter { it.id != p.id }
                        }, ::show, ::go)
                    }
                }
                Text("Hold a place to rename or remove it.", fontSize = KType.subheadline, color = Vela.muted(dark), modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        KiteDock(model, Modifier.align(Alignment.BottomCenter))
    }
    renaming?.let { p ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename") },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton({ s.renameSaved(p.id, newName); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } })
    }
    BackHandler { model.tab = AppTab.MAP }
}

@Composable
private fun RowScope.MineTile(model: AppModel, icon: ImageVector, color: Color, title: String, place: Place?, kind: PickKind,
                              go: (Place) -> Unit, distance: (Place) -> String?) {
    val dark = LocalDark.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 118.dp).clip(RoundedCornerShape(24.dp)).background(Vela.surface(dark))
            .combinedClickable(onClick = { if (place != null) go(place) else model.picking = kind }, onLongClick = { if (place != null) menu = true })
            .padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (place == null) Color.Gray else color), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = primaryText, modifier = Modifier.weight(1f))
                Text(place?.let(distance) ?: (if (place == null) "Set" else ""), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Vela.muted(dark))
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Change ${title.lowercase()}") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { menu = false; model.picking = kind })
        }
    }
}

/** [edit]: 0 rename, 1 remove; null when the row can't be changed. */
@Composable
private fun SavedRow(p: Place, icon: ImageVector, color: Color, distance: String?, edit: ((Int) -> Unit)?, show: (Place) -> Unit, go: (Place) -> Unit) {
    val dark = LocalDark.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(start = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.weight(1f).combinedClickable(onClick = { show(p) }, onLongClick = { if (edit != null) menu = true }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    (distance ?: p.address ?: p.category)?.let { Text(it, fontSize = 16.sp, color = Vela.muted(dark), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Box(Modifier.size(60.dp).clip(CircleShape).background(Vela.accent(dark)).tap { go(p) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Navigation, "Go to ${p.name}", tint = Vela.onAccent(dark), modifier = Modifier.size(28.dp))
            }
        }
        if (edit != null) DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; edit(0) })
            DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; edit(1) })
        }
    }
}

// Settings

private enum class SettingsScreen(val title: String) {
    MENU("Settings"), DRIVING("Voice & driving"), CAMERAS("Cameras & speed"), WAZE("Road alerts"), MAP("Map & display"),
    PLACES("My places"), PRIVACY("Privacy"), ABOUT("About"), DIAGNOSTICS("Diagnostics");

    /** Where the back button goes. */
    val parent: SettingsScreen get() = if (this == DIAGNOSTICS) ABOUT else MENU
}

/** The Settings page of the dock: a short menu like Waze's, each row opening a page with its switches. */
@Composable
fun SettingsPage(model: AppModel) {
    val dark = LocalDark.current
    var screen by rememberSaveable { mutableStateOf(when (DemoScript.step) {
        "wazesettings" -> SettingsScreen.WAZE
        "diagnostics" -> SettingsScreen.DIAGNOSTICS
        else -> SettingsScreen.MENU
    }) }
    Box(Modifier.fillMaxSize().background(Vela.ground(dark))) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (screen == SettingsScreen.MENU) {
                Text("Settings", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = primaryText, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
            } else {
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    Row(Modifier.align(Alignment.CenterStart).padding(start = 4.dp).clip(RoundedCornerShape(8.dp)).tap { screen = screen.parent }
                        .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Vela.accentText(dark))
                        Text(screen.parent.title, fontSize = KType.body, color = Vela.accentText(dark), modifier = Modifier.padding(start = 4.dp))
                    }
                    Text(screen.title, fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText, modifier = Modifier.align(Alignment.Center))
                }
            }
            AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "settings") { sc ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (sc) {
                        SettingsScreen.MENU -> SettingsMenu { screen = it }
                        SettingsScreen.DRIVING -> DrivingSettings()
                        SettingsScreen.CAMERAS -> CameraSettings()
                        SettingsScreen.WAZE -> WazeSettings()
                        SettingsScreen.MAP -> MapSettings()
                        SettingsScreen.PLACES -> PlacesSettings(model)
                        SettingsScreen.PRIVACY -> PrivacySettings()
                        SettingsScreen.ABOUT -> AboutSettings { screen = SettingsScreen.DIAGNOSTICS }
                        SettingsScreen.DIAGNOSTICS -> DiagnosticsSettings(model)
                    }
                }
            }
        }
        KiteDock(model, Modifier.align(Alignment.BottomCenter))
    }
    BackHandler { if (screen != SettingsScreen.MENU) screen = screen.parent else { model.reloadRoutes(); model.tab = AppTab.MAP } }
}

/** A rounded group of rows, like the iPhone's Settings. */
@Composable
fun SettingsGroup(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val dark = LocalDark.current
    Column(Modifier.padding(top = 10.dp)) {
        if (header != null) Text(header.uppercase(), fontSize = KType.caption, fontWeight = FontWeight.SemiBold, color = secondaryText,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Vela.surface(dark))) { content() }
        if (footer != null) Text(footer, fontSize = KType.caption, color = secondaryText, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
    }
}

/** A settings row's name with a small coloured icon in front. */
@Composable
fun SettingLabel(title: String, icon: ImageVector?, color: Long, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Box(Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(hex(color)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(title, fontSize = KType.body, color = primaryText)
    }
}

@Composable
fun ToggleRow(title: String, icon: ImageVector?, color: Long, on: Boolean, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null, set: (Boolean) -> Unit) {
    val dark = LocalDark.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { if (enabled) set(!on) }.padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(10.dp)); Text(title, fontSize = KType.body, color = primaryText, modifier = Modifier.weight(1f)) }
        else SettingLabel(title, icon, color, Modifier.weight(1f))
        Switch(on, { if (enabled) set(it) }, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Vela.accent(dark), checkedThumbColor = Color.White))
    }
}

@Composable
fun <T> PickRow(title: String, icon: ImageVector?, color: Long, value: T, options: List<T>, label: (T) -> String, set: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { open = true }.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingLabel(title, icon, color, Modifier.weight(1f))
            Text(label(value), fontSize = KType.body, color = secondaryText)
            Icon(Icons.Filled.UnfoldMore, null, tint = secondaryText, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }, Modifier.align(Alignment.CenterEnd)) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(label(o)) }, trailingIcon = { if (o == value) Icon(Icons.Filled.Check, null) }, onClick = { set(o); open = false })
            }
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(start = 55.dp))

@Composable
private fun SettingsMenu(open: (SettingsScreen) -> Unit) {
    val s = AppSettings
    SettingsGroup {
        @Composable
        fun item(sc: SettingsScreen, detail: String, value: String?, icon: ImageVector, color: Long, last: Boolean = false) {
            Row(Modifier.fillMaxWidth().tap { open(sc) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(hex(color)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(sc.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    Text(detail, fontSize = KType.subheadline, color = secondaryText)
                }
                if (value != null) Text(value, color = secondaryText)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText)
            }
            if (!last) HorizontalDivider(Modifier.padding(start = 64.dp))
        }
        item(SettingsScreen.DRIVING, "Voice, travel mode, avoid tolls and ${if (Fmt.yards) "motorways" else "highways"}", null, Icons.AutoMirrored.Filled.VolumeUp, 0x0F766E)
        item(SettingsScreen.CAMERAS, "Camera warnings, speeding alert", null, Icons.Filled.PhotoCamera, 0xDC2626)
        item(SettingsScreen.WAZE, if (WazeAlert.Kind.POLICE in Waze.shares) "Police, crashes, closures" else "Closures, police aircraft",
            if (s.wazeOn) "On" else "Off", Icons.Filled.Warning, 0x7C3AED)
        item(SettingsScreen.MAP, "Theme, units, parking button", null, Icons.Filled.Contrast, 0x334155)
        item(SettingsScreen.PLACES, "Home and work", null, Icons.Filled.Home, 0x16A34A)
        item(SettingsScreen.PRIVACY, "Use Kite without Google", if (s.noGoogle) "On" else null, Icons.Filled.Lock, 0x475569)
        item(SettingsScreen.ABOUT, "Version ${BuildConfig.VERSION_NAME}", null, Icons.Filled.Info, 0x64748B, last = true)
    }
}

@Composable
private fun DrivingSettings() {
    val s = AppSettings
    SettingsGroup {
        ToggleRow("Voice guidance", Icons.AutoMirrored.Filled.VolumeUp, 0x0F766E, s.voice) { s.voice = it }
        RowDivider()
        PickRow("Default travel mode", Icons.Filled.DirectionsCar, 0x2563EB, s.defaultMode, TravelMode.entries.toList(), { it.label }) { s.defaultMode = it }
    }
    SettingsGroup("Routes") {
        ToggleRow("Avoid tolls", Icons.Filled.Toll, 0xF59E0B, s.avoidTolls) { s.avoidTolls = it }
        RowDivider()
        ToggleRow(if (Fmt.yards) "Avoid motorways" else "Avoid highways", Icons.Filled.AddRoad, 0x1D4ED8, s.avoidHighways) { s.avoidHighways = it }
    }
}

@Composable
private fun CameraSettings() {
    val s = AppSettings
    SettingsGroup("Warnings") {
        ToggleRow("Say when a speed camera is ahead", Icons.Filled.RecordVoiceOver, 0xDC2626, s.cameraWarnings) { s.cameraWarnings = it }
        RowDivider()
        ToggleRow("Say when I'm over the speed limit", Icons.Filled.Speed, 0xEA580C, s.speedingAlert) { s.speedingAlert = it }
    }
    SettingsGroup("Show on the map and route", "Camera locations come from OpenStreetMap. General surveillance cameras are not necessarily plate readers and are shown only on the map. Some countries, like France and Germany, don't allow warnings of speed cameras, so check before you turn the voice on there.") {
        ToggleRow("Speed cameras", Icons.Filled.PhotoCamera, 0xDC2626, s.speedCameras) { s.speedCameras = it }
        RowDivider()
        ToggleRow("Surveillance cameras", Icons.Filled.Videocam, 0x16A34A, s.surveillanceCameras) { s.surveillanceCameras = it }
        RowDivider()
        ToggleRow(if (Fmt.yards) "Number plate cameras" else "License plate cameras", Icons.Filled.Pin, 0x6B7280, s.plateCameras) { s.plateCameras = it }
    }
}

@Composable
private fun MapSettings() {
    val s = AppSettings
    SettingsGroup {
        PickRow("Theme", Icons.Filled.Contrast, 0x334155, s.theme, ThemeMode.entries.toList(), { it.label }) { s.theme = it }
        RowDivider()
        PickRow("Distance units", Icons.Filled.Straighten, 0x0891B2, s.units, UnitsMode.entries.toList(), { it.label }) { s.units = it }
        RowDivider()
        ToggleRow("Parking button on the map", Icons.Filled.LocalParking, 0x2563EB, s.parkingButton) { s.parkingButton = it }
    }
}

@Composable
private fun PlacesSettings(model: AppModel) {
    val s = AppSettings
    SettingsGroup {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { model.picking = PickKind.HOME }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingLabel("Home", Icons.Filled.Home, 0x16A34A, Modifier.weight(1f))
            Text(s.home?.name ?: "Set", color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { model.picking = PickKind.WORK }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingLabel("Work", Icons.Filled.Work, 0x9333EA, Modifier.weight(1f))
            Text(s.work?.name ?: "Set", color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
    }
}

@Composable
private fun PrivacySettings() {
    val s = AppSettings
    SettingsGroup(footer = "Off (the usual): search, place details, routes and live traffic come from Google, asked anonymously with no account. On: everything comes from free open-data services instead and nothing goes to Google, but search and routes are less complete.") {
        ToggleRow("Use Kite without Google", Icons.Filled.Lock, 0x475569, s.noGoogle) { s.noGoogle = it }
    }
}

@Composable
private fun AboutSettings(openDiagnostics: () -> Unit) {
    val ctx = LocalContext.current
    val dark = LocalDark.current
    @Composable
    fun link(text: String, url: String) {
        Text(text, fontSize = KType.body, color = Vela.accentText(dark), modifier = Modifier.fillMaxWidth().tap { ctx.open(url) }.padding(horizontal = 14.dp, vertical = 14.dp))
    }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text("Version", fontSize = KType.body, color = primaryText, modifier = Modifier.weight(1f))
            Text(BuildConfig.VERSION_NAME, color = secondaryText)
        }
        HorizontalDivider()
        link("Kite Maps on GitHub", "https://github.com/matteofx1/kite-maps")
        HorizontalDivider()
        link("Based on Vela Maps for Android", "https://github.com/PimpinPumpkin/Vela")
        HorizontalDivider()
        link("Live Waze reports the way SABRE Plus gets them (MIT)", "https://github.com/nicglazkov/highway-radar-sabre-plus")
        HorizontalDivider()
        Text("Free software under the GPL-3.0. Map © OpenFreeMap, OpenMapTiles, OpenStreetMap contributors. Routing © FOSSGIS OSRM. Imagery © Esri.",
            fontSize = KType.caption, color = secondaryText, modifier = Modifier.padding(14.dp))
    }
    SettingsGroup(footer = "Makes a small file to send with a bug report.") {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap(openDiagnostics).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SettingLabel("Share diagnostics", Icons.Filled.BugReport, 0x64748B, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText)
        }
    }
    SettingsGroup("Thanks to") {
        link("Surveillance cameras, saved-place matching, GPS warnings and diagnostics from Ausbloke's Kite fork", "https://github.com/NotDiscordOfficial/kite-maps")
    }
}

/** Settings › Road alerts: which live Waze reports to show and warn about, the police heat map and police aircraft. */
@Composable
private fun WazeSettings() {
    val s = AppSettings
    var remembered by remember { mutableIntStateOf(WazeHistory.all.size) }
    var confirmClear by remember { mutableStateOf(false) }
    val kinds = listOf(
        WazeAlert.Kind.POLICE to "Police and mobile speed traps", WazeAlert.Kind.ACCIDENT to "Crashes",
        WazeAlert.Kind.HAZARD to "Hazards (stopped cars, objects, potholes)", WazeAlert.Kind.WEATHER to "Weather (fog, ice, flooding)",
        WazeAlert.Kind.ROADWORKS to "Roadworks", WazeAlert.Kind.CLOSURE to "Road closures", WazeAlert.Kind.JAM to "Traffic jams reported by drivers",
    )
    val ages = listOf(30 to "30 minutes", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours", 0 to "Keep them all")
    fun whenText(at: Long): String { val m = ((System.currentTimeMillis() - at) / 60_000).toInt(); return if (m < 1) "just now" else "$m min ago" }
    val connection: Pair<String, Color> = Waze.lastStatus?.let { st ->
        val w = whenText(st.atMillis)
        when (st.code) {
            200 -> "Closures working: ${st.count} reports, $w" to hex(0x188038)
            0 -> "Closures: no connection ($w)" to hex(0xF59E0B)
            401, 403 -> "Closures: Waze turned Kite away ($w). It may work again later." to Color.Red
            -1 -> "Closures: Waze sent something Kite can't read ($w)" to Color.Red
            else -> "Closures: Waze error ${st.code} ($w)" to hex(0xF59E0B)
        }
    } ?: ("Closures: checked once you open the map at city zoom" to Color.Gray)
    val live: Pair<String, Color> = WazeLive.lastStatus?.let { st ->
        val w = whenText(st.atMillis)
        when (st.code) {
            200 -> "Driver reports working: ${st.summary}, $w" to hex(0x188038)
            0 -> "Driver reports: no connection ($w)" to hex(0xF59E0B)
            401 -> "Driver reports: Waze turned Kite away ($w). It may work again later." to Color.Red
            440 -> "Driver reports: reconnecting to Waze ($w)" to hex(0xF59E0B)
            -1 -> "Driver reports: Waze sent something Kite can't read ($w)" to Color.Red
            else -> "Driver reports: Waze error ${st.code} ($w)" to hex(0xF59E0B)
        }
    } ?: ("Driver reports: checked once you open the map at city zoom" to Color.Gray)

    SettingsGroup(footer = if (s.noGoogle) "Off while \"Use Kite without Google\" is on, since Waze belongs to Google."
        else "Kite asks Waze for drivers' reports the way the Waze app does, for the map on screen and along your route, with an anonymous account Waze makes for it (no name, email or phone). Closures also come from the Waze live map. Waze sees roughly where you are, anonymously. It doesn't allow other apps to do this, so it may stop working one day.") {
        ToggleRow("Use Waze reports", null, 0, s.waze, enabled = !s.noGoogle) { s.waze = it }
    }
    if (s.wazeOn) {
        // What the hidden live map page saw, to send to whoever looks into it.
        SettingsGroup("Connection", footer = Waze.lastStatus?.takeIf { it.code != 200 }?.let { "Details: " + WazeWeb.note }) {
            for ((text, color) in listOf(connection, live)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Text(text, fontSize = KType.subheadline, color = primaryText)
                }
            }
            Waze.lastStatus?.takeIf { it.code == 200 }?.let { st ->
                // What Waze actually sent, so it's easy to see if a kind has gone missing.
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text("Closures, last answer", fontSize = KType.subheadline, color = primaryText, modifier = Modifier.weight(1f))
                    Text(st.summary, fontSize = KType.subheadline, color = secondaryText)
                }
            }
        }
        SettingsGroup("Show and warn about") {
            // Only what Waze still shares: no switches that can't do anything.
            kinds.filter { it.first in Waze.shares }.forEachIndexed { i, (k, label) ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 50.dp))
                ToggleRow(label, null, 0, k in s.wazeKinds, leading = { WazeBadge(k, 26.dp) }) { on ->
                    s.wazeKinds = if (on) s.wazeKinds + k else s.wazeKinds - k
                }
            }
        }
        SettingsGroup("Alerts", "While navigating, a card pops up for reports on your route, only ones made by drivers going your way.") {
            ToggleRow("Show reports on the map", null, 0, s.wazeOnMap) { s.wazeOnMap = it }
            HorizontalDivider()
            ToggleRow("Chime and say them while navigating", null, 0, s.wazeVoice) { s.wazeVoice = it }
            HorizontalDivider()
            PickRow("Ignore reports older than", null, 0, s.wazeMaxAge, ages.map { it.first }, { v -> ages.first { it.first == v }.second }) { s.wazeMaxAge = it }
        }
        if (WazeAlert.Kind.POLICE in Waze.shares) {
            SettingsGroup(footer = "Heat map: where police get reported most, across the whole UK, from Highway Radar's own map (built from its drivers' reports; it may stop working one day). Risk score: low, medium or high for the road ahead (counting reports made at this time of day most), from the Waze reports Kite remembers on this phone, for 90 days and never anywhere else.") {
                ToggleRow("Police heat map", null, 0, s.wazeHeatmap) { s.wazeHeatmap = it }
                HorizontalDivider()
                ToggleRow("Police risk score while driving", null, 0, s.policeRisk) { s.policeRisk = it }
                HorizontalDivider()
                Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
                    Text("Police reports remembered", fontSize = KType.body, color = primaryText, modifier = Modifier.weight(1f))
                    Text("$remembered", color = secondaryText)
                }
                if (remembered > 0) {
                    HorizontalDivider()
                    Text("Forget them all", fontSize = KType.body, color = Vela.closed, modifier = Modifier.fillMaxWidth().tap { confirmClear = true }.padding(14.dp))
                }
            }
        }
    }
    SettingsGroup("Aircraft", "While navigating, warns when a police aircraft is within about 10 km, from open flight tracking (adsb.lol), not Waze. It spots them by their police callsign (like NPAS in the UK), and some don't broadcast at all, so no warning doesn't mean the sky is clear.") {
        ToggleRow("Police planes and helicopters", null, 0, s.policeAircraft) { s.policeAircraft = it }
    }
    if (confirmClear) {
        AlertDialog(onDismissRequest = { confirmClear = false },
            text = { Text("Forget the $remembered police reports Kite remembered? The risk score starts again from nothing.") },
            confirmButton = { TextButton({ WazeHistory.clear(); remembered = 0; confirmClear = false }) { Text("Forget them", color = Vela.closed) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
    }
}

// Layers

@Composable
fun LayersSheet(close: () -> Unit) {
    val s = AppSettings
    val dark = LocalDark.current
    ModalBottomSheet(onDismissRequest = close, containerColor = Vela.sheet(dark), dragHandle = null) {
        Column(Modifier.padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = primaryText, modifier = Modifier.weight(1f))
                CircleIconButton(Icons.Filled.Close, onClick = close)
            }
            Text("Map type", fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = secondaryText)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LayerTile("Default", !s.satellite, { Icon(Icons.Outlined.Map, null, tint = primaryText, modifier = Modifier.size(28.dp)) }) { s.satellite = false }
                LayerTile("Satellite", s.satellite, { Icon(Icons.Filled.Public, null, tint = primaryText, modifier = Modifier.size(28.dp)) }) { s.satellite = true }
            }
            Text("Map details", fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = secondaryText)
            val tiles = mutableListOf<@Composable () -> Unit>()
            @Composable fun ic(v: ImageVector) = Icon(v, null, tint = primaryText, modifier = Modifier.size(28.dp))
            tiles += { LayerTile("Traffic", s.traffic, { ic(Icons.Filled.Traffic) }) { s.traffic = !s.traffic } }
            tiles += { LayerTile("Transit", s.transitLines, { ic(Icons.Filled.Tram) }) { s.transitLines = !s.transitLines } }
            tiles += { LayerTile("Terrain", s.terrain, { ic(Icons.Filled.Terrain) }) { s.terrain = !s.terrain } }
            tiles += { LayerTile("Speed cameras", s.speedCameras, { CameraBadge(RoadCamera.Kind.SPEED, 34.dp) }) { s.speedCameras = !s.speedCameras } }
            tiles += { LayerTile("Plate cameras", s.plateCameras, { CameraBadge(RoadCamera.Kind.PLATE, 34.dp) }) { s.plateCameras = !s.plateCameras } }
            tiles += { LayerTile("Surveillance", s.surveillanceCameras, { ic(Icons.Filled.Videocam) }) { s.surveillanceCameras = !s.surveillanceCameras } }
            tiles += {
                LayerTile("Road alerts", s.wazeOn && s.wazeOnMap, { WazeBadge(if (WazeAlert.Kind.POLICE in Waze.shares) WazeAlert.Kind.POLICE else WazeAlert.Kind.CLOSURE, 34.dp) }) {
                    if (s.waze && s.wazeOnMap) s.wazeOnMap = false else { s.waze = true; s.wazeOnMap = true }
                }
            }
            if (WazeAlert.Kind.POLICE in Waze.shares) tiles += {
                LayerTile("Police heat map", s.wazeOn && s.wazeHeatmap, { ic(Icons.Filled.LocalFireDepartment) }) {
                    if (s.waze && s.wazeHeatmap) s.wazeHeatmap = false else { s.waze = true; s.wazeHeatmap = true }
                }
            }
            tiles.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) { row.forEach { t -> Box(Modifier.weight(1f)) { t() } }; repeat(3 - row.size) { Spacer(Modifier.weight(1f)) } }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun LayerTile(title: String, on: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val dark = LocalDark.current
    Column(Modifier.clip(RoundedCornerShape(14.dp)).tap(onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(74.dp, 64.dp).clip(RoundedCornerShape(14.dp)).background(Vela.chip(dark))
            .border(3.dp, if (on) Vela.accent(dark) else Color.Transparent, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { icon() }
        Text(title, fontSize = KType.caption, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Vela.accent(dark) else primaryText,
            textAlign = TextAlign.Center, maxLines = 2)
    }
}

// Choosing home or work

/** Choosing home or work: type an address or postcode, or use where you are now. */
@Composable
fun PlacePicker(model: AppModel, kind: PickKind) {
    val s = AppSettings
    val title = if (kind == PickKind.HOME) "Home" else "Work"
    val current = if (kind == PickKind.HOME) s.home else s.work
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf<Job?>(null) }
    fun close() { model.picking = null }
    fun pick(p: Place) { if (kind == PickKind.HOME) model.setHome(p) else model.setWork(p); close() }

    /** As you type: quick suggestions. On Search: a full search, postcodes included. */
    fun lookUp(text: String, full: Boolean) {
        task?.cancel()
        val q = text.trim()
        if (q.length < 2) { found = emptyList(); searching = false; return }
        searching = true
        val center = model.userLatLng ?: model.viewport.first
        val useGoogle = !s.noGoogle
        task = scope.launch {
            if (!full) delay(300)
            val places = withContext(Dispatchers.IO) {
                if (full) PlaceSearch.search(q, center, 50_000.0, useGoogle).places
                else {
                    var out = listOfNotNull(runCatching { Postcodes.lookup(q) }.getOrNull())
                    if (out.isEmpty() && useGoogle) out = runCatching { GoogleClient.shared.suggest(q, center, 50_000.0).places }.getOrDefault(emptyList())
                    if (out.isEmpty()) out = runCatching { Photon.search(q, center, 10) }.getOrDefault(emptyList())
                    out
                }
            }
            found = places.take(20)
            searching = false
        }
    }

    @Composable
    fun row(p: Place) {
        Row(Modifier.fillMaxWidth().tap { pick(p) }.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.Place, null, tint = Vela.closed, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = primaryText, maxLines = 2)
                p.address?.takeIf { it != p.name }?.let { Text(it, fontSize = 15.sp, color = secondaryText, maxLines = 2) }
            }
            model.userLatLng?.let { Text(Fmt.distance(it.distanceTo(p.location)), fontSize = 15.sp, color = secondaryText) }
        }
    }

    PickerPage("Set ${title.lowercase()}", ::close) {
        PickerSearchField(query, { query = it; lookUp(it, false) }, "Address or postcode") { lookUp(query, true) }
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isBlank()) {
                item {
                    val can = model.userLatLng != null && !locating
                    Row(Modifier.fillMaxWidth().tap {
                        val me = model.userLatLng ?: return@tap
                        if (locating) return@tap
                        locating = true
                        scope.launch {
                            val near = withContext(Dispatchers.IO) { runCatching { Photon.reverse(me) }.getOrNull() }
                            val p = Place(id = "${kind.name.lowercase()}:${me.lat},${me.lng}", name = near?.name ?: title, location = me, source = "osm", address = near?.address)
                            locating = false
                            pick(p)
                        }
                    }.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Vela.routeBlue.copy(alpha = if (can) 1f else 0.4f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.MyLocation, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Text("Use where I am now", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = primaryText.copy(alpha = if (can) 1f else 0.4f), modifier = Modifier.weight(1f))
                        if (locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Text(if (model.location.denied) "Location is off for Kite, so type the address instead." else "Or type the address or postcode above.",
                        fontSize = KType.caption, color = secondaryText, modifier = Modifier.padding(horizontal = 18.dp))
                }
                if (current != null) {
                    item { SectionTitle("Now set to") }
                    item { row(current) }
                    item {
                        Row(Modifier.fillMaxWidth().tap { if (kind == PickKind.HOME) model.setHome(null) else model.setWork(null); close() }
                            .padding(horizontal = 18.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.Delete, null, tint = Vela.closed)
                            Text("Remove ${title.lowercase()}", color = Vela.closed)
                        }
                    }
                }
            } else if (found.isNotEmpty()) {
                items(found, key = { it.id }) { row(it) }
            } else if (searching) {
                item {
                    Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Searching…", color = secondaryText)
                    }
                }
            } else item {
                Text(Connectivity.explain("Nothing found yet. Keep typing, or try the postcode."), color = secondaryText, modifier = Modifier.padding(18.dp))
            }
        }
    }
}
