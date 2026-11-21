@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kite.*
import app.kite.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * "Share diagnostics": checks that the services Kite leans on answer, and packs the results
 * (plus, if you choose, a picture of the map) into a ZIP you can send with a bug report.
 * Adapted from Ausbloke's Kite fork (github.com/NotDiscordOfficial/kite-maps), GPL-3.0.
 * Tests use a fixed spot in central London, never your position.
 */
@Composable
fun DiagnosticsSettings(model: AppModel) {
    val dark = LocalDark.current
    val scope = rememberCoroutineScope()
    var includeMap by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var report by remember { mutableStateOf<String?>(null) }

    SettingsGroup(footer = "Checks that Waze and OpenStreetMap answer, and puts that with your Kite version, phone model and settings into a small file " +
        "to send with a bug report. Nothing is sent until you share it. The map picture can show where you are, so leave it off unless it helps.") {
        ToggleRow("Include a picture of the map", null, 0L, includeMap) { includeMap = it }
        HorizontalDivider()
        Text(if (running) "Testing…" else "Run tests", fontSize = KType.body, color = if (running) secondaryText else Vela.accentText(dark),
            modifier = Modifier.fillMaxWidth().tap {
                if (running) return@tap
                running = true; report = null; status = "Testing…"
                scope.launch {
                    report = runDiagnostics(model, includeMap) { status = it }
                    status = if (report != null) "Ready to share." else "Couldn't make the file."
                    running = false
                }
            }.padding(horizontal = 14.dp, vertical = 14.dp))
        HorizontalDivider()
        Text(status, fontSize = KType.footnote, color = secondaryText, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        report?.let { file ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().tap { Phone.shareFile(file, "application/zip", "Share diagnostics") }.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Share, null, tint = Vela.accentText(dark), modifier = Modifier.size(20.dp))
                Text("Share diagnostics", fontSize = KType.body, color = Vela.accentText(dark))
            }
        }
    }
}

private suspend fun runDiagnostics(model: AppModel, includeMap: Boolean, progress: (String) -> Unit): String? {
    val s = AppSettings
    val map = if (includeMap) model.mapPicture() else null
    val lines = mutableListOf(
        "Kite diagnostics",
        "Created (UTC): ${Clock.System.now()}",
        "Kite: ${Phone.appVersion}",
        "App ID: ${Phone.appId}",
        "Phone: ${Phone.deviceName}",
        "Location allowed: ${LocationService.authorized}, precise: ${!model.location.reducedAccuracy}",
        "GPS accuracy now: ${model.location.last?.let { "${it.accuracy.toInt()} m" } ?: "no fix"}",
        "Road alerts: ${s.wazeOn}, on map: ${s.wazeOnMap}, heat map: ${s.wazeHeatmap}, no Google: ${s.noGoogle}",
        "Speed cameras: ${s.speedCameras}, plate cameras: ${s.plateCameras}, surveillance: ${s.surveillanceCameras}",
        "Live cameras: ${s.liveCams} (road ${s.liveRoadCams}, webcams ${s.liveWebcams}, route ${s.liveCamsOnRoute}), list: ${LiveCamStore.status ?: "not loaded"}",
        "Navigating: ${model.nav != null}",
        "Map picture included: ${map != null} (it can show where you are).",
        "No GPS position, searches, saved places or accounts are in this file.",
        "",
        "Service tests (fixed box in central London):",
    )
    suspend fun <T> timed(name: String, work: suspend () -> T?, describe: (T) -> String) {
        progress("Testing $name…")
        val t0 = elapsedRealtime()
        val result = runCatching { work() }.getOrNull()
        val ms = elapsedRealtime() - t0
        lines += "$name: " + (result?.let(describe) ?: "FAILED") + ", $ms ms"
    }
    timed("Waze live map", { Waze.alerts(51.49, -0.15, 51.53, -0.08) }) { "${it.size} reports" }
    timed("Speed cameras (OpenStreetMap)", { Overpass.cameras(51.49, -0.15, 51.53, -0.08, speed = true, plate = true) }) { "${it.size} cameras" }

    return withContext(Dispatchers.IO) {
        val files = listOf("diagnostics.txt" to lines.joinToString("\n").encodeToByteArray()) + listOfNotNull(map?.let { "map.png" to it })
        val path = cacheDir() + "/shared/Kite-diagnostics.zip"
        path.takeIf { Disk.write(it, storedZip(files)) }
    }
}

/** A ZIP with the files kept as they are (not squeezed): the report is tiny and the PNG is already packed. */
internal fun storedZip(files: List<Pair<String, ByteArray>>): ByteArray {
    val out = Bytes(files.sumOf { it.second.size } + 256)
    val central = Bytes()
    fun Bytes.u16(v: Int) { write(v and 0xff); write((v shr 8) and 0xff) }
    fun Bytes.u32(v: Long) { u16((v and 0xffff).toInt()); u16(((v shr 16) and 0xffff).toInt()) }
    for ((name, data) in files) {
        val offset = out.size.toLong()
        val crc = crc32(data)
        val nameBytes = name.encodeToByteArray()
        out.u32(0x04034b50); out.u16(20); out.u16(0x0800); out.u16(0); out.u16(0); out.u16(0x21)
        out.u32(crc); out.u32(data.size.toLong()); out.u32(data.size.toLong()); out.u16(nameBytes.size); out.u16(0)
        out.write(nameBytes); out.write(data)
        central.u32(0x02014b50); central.u16(20); central.u16(20); central.u16(0x0800); central.u16(0); central.u16(0); central.u16(0x21)
        central.u32(crc); central.u32(data.size.toLong()); central.u32(data.size.toLong()); central.u16(nameBytes.size)
        central.u16(0); central.u16(0); central.u16(0); central.u16(0); central.u32(0); central.u32(offset)
        central.write(nameBytes)
    }
    val start = out.size.toLong()
    val dir = central.toByteArray()
    out.write(dir)
    out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(files.size); out.u16(files.size)
    out.u32(dir.size.toLong()); out.u32(start); out.u16(0)
    return out.toByteArray()
}
