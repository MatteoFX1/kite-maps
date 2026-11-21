package app.kite.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import app.kite.*

private fun hex(v: Long) = Color(0xFF000000 or v)

/**
 * The driving view with no route: the warning card up top (a speed camera or a Waze report), the
 * speed sign bottom left, and one slim bar with ✕. The road's name is written on the road itself.
 */
@Composable
fun CruiseOverlay(model: AppModel, cruise: Cruise) {
    val dark = LocalDark.current
    Column(Modifier.fillMaxSize().systemBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 12.dp).padding(top = 4.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val cam = cruise.cameraAhead
            val w = cruise.wazeAhead
            // One warning at a time, nearest first, like on a route; a camera you're speeding at always wins.
            val cameraFirst = cam != null && (w == null || cam.meters <= w.meters ||
                (cam.limitKmh ?: cruise.limitKmh)?.let { cruise.speedMps * 3.6 > it + 3 } == true)
            if (cam != null && cameraFirst) CameraCard(cam, cruise.speedMps * 3.6, cruise.limitKmh)
            else if (w != null) Box(Modifier.tap { cruise.dismissWaze() }) { WazeCard(w) }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
            SpeedBadge(cruise.speedMps, cruise.limitKmh)
            Spacer(Modifier.weight(1f))
        }
        if (!model.browseFollowing) {
            Row(Modifier.align(Alignment.CenterHorizontally).height(52.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Vela.accent(dark))
                .tap { model.recenterCruise() }.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Navigation, null, tint = Vela.onAccent(dark))
                Text("Re-centre", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Vela.onAccent(dark))
            }
        }
        CruiseBar(cruise.ref) { model.endCruise(closed = true) }
    }
    androidx.compose.ui.backhandler.BackHandler { model.endCruise(closed = true) }
}

/** ✕, then "Driving" and the road's number: no arrival time, since there's no route. */
@Composable
fun CruiseBar(ref: String?, onClose: () -> Unit) {
    val dark = LocalDark.current
    Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp).fillMaxWidth().height(64.dp).glass(CircleShape).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).tap(onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, "Back to the map", tint = if (dark) hex(0xFCA5A5) else Vela.closed, modifier = Modifier.size(28.dp))
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            Icon(Icons.Filled.DirectionsCar, null, tint = Vela.accentText(dark), modifier = Modifier.size(22.dp))
            Text("Driving", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ref != null) {
                val motorway = ref.startsWith("M")
                Text(ref, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = if (motorway) hex(0xFFFFFF) else hex(0xFFD200),
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (motorway) hex(0x0067B1) else hex(0x00703C))
                        .padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        // Balances the ✕ so "Driving" sits in the middle.
        Spacer(Modifier.size(56.dp))
    }
}
