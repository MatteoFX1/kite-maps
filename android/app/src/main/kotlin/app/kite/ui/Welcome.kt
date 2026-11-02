package app.kite.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kite.*
import app.kite.R
import app.kite.core.*

/** A full-screen first-launch page: scrolls with very big text, with the button always in reach at the bottom. */
@Composable
private fun IntroPage(button: String, onButton: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Drawn straight over the app (not in a Dialog): a Dialog's window didn't reach the bottom of
    // the screen on a Pixel, which hid the button. Back does nothing here; the choice comes first.
    androidx.activity.compose.BackHandler {}
    val dark = LocalDark.current
    Column(Modifier.fillMaxSize().background(Vela.ground(dark)).pointerInput(Unit) { }.systemBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Spacer(Modifier.height(20.dp))
            content()
        }
        Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth().height(54.dp).clip(CircleShape).background(Vela.accent(dark)).tap(onButton),
            contentAlignment = Alignment.Center) {
            Text(button, fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = Vela.onAccent(dark))
        }
    }
}

@Composable
private fun IntroOption(title: String, detail: String, icon: ImageVector, picked: Boolean, tap: () -> Unit) {
    val dark = LocalDark.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .border(if (picked) 2.dp else 1.dp, if (picked) Vela.accent(dark) else secondaryText.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
        .tap(tap).padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = if (picked) Vela.accent(dark) else secondaryText, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText)
            Text(detail, fontSize = KType.subheadline, color = secondaryText)
        }
        Icon(if (picked) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = if (picked) Vela.accent(dark) else secondaryText, modifier = Modifier.size(24.dp))
    }
}

/** First launch: what Kite is, and the one choice that matters (Google's data, or open data only). */
@Composable
fun WelcomeScreen() {
    val s = AppSettings
    var openOnly by remember { mutableStateOf(false) }
    IntroPage("Get started", {
        s.noGoogle = openOnly
        // Someone keeping away from Google isn't asked about Waze, which Google owns.
        if (openOnly) s.wazeAsked = true
        s.welcomed = true
    }) {
        Image(painterResource(R.drawable.kite_logo), null, Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Welcome to Kite", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = primaryText)
            Text("Maps, search and turn-by-turn directions with no account and no tracking.", fontSize = 20.sp, color = secondaryText)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IntroOption("Best results", "Search, place details, live traffic and routes from Google, asked anonymously. The map itself is open data.",
                Icons.Filled.AutoAwesome, !openOnly) { openOnly = false }
            IntroOption("Open data only", "Nothing is sent to Google. Search from OpenStreetMap and routes from open servers.",
                Icons.Filled.Security, openOnly) { openOnly = true }
        }
        Text("You can change this any time in Settings. Next, Kite asks to use your location, so it can show where you are and guide you.",
            fontSize = KType.footnote, color = secondaryText)
    }
}

/**
 * Asked once, after the welcome: whether to turn on live Waze reports. A beta, since Waze could
 * stop it working any day.
 */
@Composable
fun WazeIntroScreen() {
    val s = AppSettings
    var on by remember { mutableStateOf(true) }
    val police = WazeAlert.Kind.POLICE in Waze.shares
    IntroPage("Continue", { s.waze = on; s.wazeAsked = true }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Only kinds Waze still shares, so the picture matches what Kite can really show.
            (if (police) listOf(WazeAlert.Kind.POLICE, WazeAlert.Kind.ACCIDENT, WazeAlert.Kind.HAZARD) else listOf(WazeAlert.Kind.CLOSURE)).forEach { WazeBadge(it, 56.dp) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Road alerts", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = primaryText)
                Text("BETA", fontSize = KType.caption, fontWeight = FontWeight.ExtraBold, color = Color.White,
                    modifier = Modifier.clip(CircleShape).background(Color(0xFFF29900)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Text(if (police) "Live police, crash and hazard reports from Waze drivers, on the map and as warnings while you drive, plus a police heat map and risk score."
                else "Live road closures from Waze, on the map and as warnings while you drive.", fontSize = 20.sp, color = secondaryText)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IntroOption("Turn on road alerts", "Kite asks Waze anonymously about the area you're looking at and your route. Reports Kite sees are kept only on this phone.",
                Icons.Filled.NotificationsActive, on) { on = true }
            IntroOption("Not now", "Nothing is sent to Waze.", Icons.Outlined.NotificationsOff, !on) { on = false }
        }
        Text("This is a beta: Waze doesn't officially allow other apps to read its live map, so it may stop working. You can change this any time in Settings › Road alerts.",
            fontSize = KType.footnote, color = secondaryText)
    }
}
