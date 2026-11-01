package app.kite.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kite.*
import app.kite.core.*

@Composable
fun ResultsHeader(model: AppModel) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(model.resultsTitle.replaceFirstChar { it.uppercase() }, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                    color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${model.visibleResults.size} results", fontSize = KType.subheadline, color = secondaryText)
            }
            CircleIconButton(if (model.detent == SheetDetent.FULL) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp) {
                model.detent = if (model.detent == SheetDetent.FULL) SheetDetent.HALF else SheetDetent.FULL
            }
            CircleIconButton(Icons.Filled.Close) { model.clearSearch() }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChipLabel("Open now", model.openNowOnly, menu = false) { model.openNowOnly = !model.openNowOnly; model.mapTick++ }
            var open by remember { mutableStateOf(false) }
            Box {
                FilterChipLabel(if (model.sort == SortMode.RELEVANCE) "Sort" else model.sort.label, model.sort != SortMode.RELEVANCE, menu = true) { open = true }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    SortMode.entries.forEach { m ->
                        DropdownMenuItem(text = { Text(m.label) }, onClick = { model.sort = m; model.mapTick++; open = false },
                            trailingIcon = { if (model.sort == m) Icon(Icons.Filled.Check, null) })
                    }
                }
            }
            FilterChipLabel("Rating 4.0+", model.rating4Only, menu = false) { model.rating4Only = !model.rating4Only; model.mapTick++ }
        }
    }
}

@Composable
fun FilterChipLabel(title: String, on: Boolean, menu: Boolean, onClick: () -> Unit) {
    val dark = LocalDark.current
    Row(Modifier.height(44.dp).clip(CircleShape)
        .background(if (on) Vela.tonal(dark) else Color.Transparent)
        .border(BorderStroke(1.dp, if (on) Color.Transparent else secondaryText.copy(alpha = 0.5f)), CircleShape)
        .tap(onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val fg = if (on) Vela.onTonal(dark) else primaryText
        if (on && !menu) Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = fg)
        Text(title, fontSize = KType.body, fontWeight = FontWeight.SemiBold, color = fg)
        if (menu) Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp), tint = fg)
    }
}

@Composable
fun ResultsList(model: AppModel, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
        item { HorizontalDivider() }
        if (model.visibleResults.isEmpty() && model.results.isNotEmpty()) {
            item {
                Text("Nothing here matches these filters. Turn one off to see more.", fontSize = KType.body, color = secondaryText,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 20.dp))
            }
        }
        items(model.visibleResults, key = { it.id }) { p ->
            ResultRow(model, p)
            HorizontalDivider()
        }
    }
}

@Composable
fun ResultRow(model: AppModel, place: Place) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().tap { model.select(place) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(place.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = primaryText)
        PlaceMetaLine(place, here = model.userLatLng)
        StatusText(place)
        if (place.rating == null) place.address?.let {
            Text(it, fontSize = KType.subheadline, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (place.photoUrls.isNotEmpty()) Box(Modifier.padding(top = 6.dp)) { PhotoStrip(place.photoUrls, height = 110.dp) }
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PillButton("Directions", Icons.Filled.Directions, filled = true) { model.selected = place; model.openDirections(place) }
            telUrl(place.phone)?.let { t -> PillButton("Call", Icons.Filled.Phone) { ctx.open(t) } }
            webUrl(place.website)?.let { w -> PillButton("Website", Icons.Filled.Language) { ctx.open(w) } }
            Box(Modifier.size(40.dp).clip(CircleShape).background(secondaryText.copy(alpha = 0.18f)).tap { ctx.share(place.shareUrl) },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Share, "Share", Modifier.size(18.dp), tint = primaryText)
            }
        }
    }
}
