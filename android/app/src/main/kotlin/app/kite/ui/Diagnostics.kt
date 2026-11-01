@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.kite.*
import app.kite.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

/**
 * "Share diagnostics": checks that the services Kite leans on answer, and packs the results
 * (plus, if you choose, a picture of the map) into a ZIP you can send with a bug report.
 * Adapted from Ausbloke's Kite fork (github.com/NotDiscordOfficial/kite-maps), GPL-3.0.
 * Tests use a fixed spot in central London, never your position.
 */
@Composable
fun DiagnosticsSettings(model: AppModel) {
    val ctx = LocalContext.current
    val dark = LocalDark.current
    val scope = rememberCoroutineScope()
    var includeMap by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var report by remember { mutableStateOf<File?>(null) }

    SettingsGroup(footer = "Checks that Waze and OpenStreetMap answer, and puts that with your Kite version, phone model and settings into a small file " +
        "to send with a bug report. Nothing is sent until you share it. The map picture can show where you are, so leave it off unless it helps.") {
        ToggleRow("Include a picture of the map", null, 0L, includeMap) { includeMap = it }
        HorizontalDivider()
        Text(if (running) "Testing…" else "Run tests", fontSize = KType.body, color = if (running) secondaryText else Vela.accentText(dark),
            modifier = Modifier.fillMaxWidth().tap {
                if (running) return@tap
                running = true; report = null; status = "Testing…"
                scope.launch {
                    report = runDiagnostics(ctx, model, includeMap) { status = it }
                    status = if (report != null) "Ready to share." else "Couldn't make the file."
                    running = false
                }
            }.padding(horizontal = 14.dp, vertical = 14.dp))
        HorizontalDivider()
        Text(status, fontSize = KType.footnote, color = secondaryText, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        report?.let { file ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().tap { shareFile(ctx, file) }.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Share, null, tint = Vela.accentText(dark), modifier = Modifier.size(20.dp))
                Text("Share diagnostics", fontSize = KType.body, color = Vela.accentText(dark))
            }
        }
    }
}

private suspend fun runDiagnostics(ctx: Context, model: AppModel, includeMap: Boolean, progress: (String) -> Unit): File? {
    val s = AppSettings
    val map = if (includeMap) mapPicture(model) else null
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
    val lines = mutableListOf(
        "Kite diagnostics",
        "Created (UTC): ${Instant.now()}",
        "Kite: ${version?.versionName ?: "?"} (${version?.longVersionCode ?: "?"})",
        "App ID: ${ctx.packageName}",
        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
        "Location allowed: ${LocationService.authorized}, precise: ${!model.location.reducedAccuracy}",
        "GPS accuracy now: ${model.location.last?.let { "${it.accuracy.toInt()} m" } ?: "no fix"}",
        "Road alerts: ${s.wazeOn}, on map: ${s.wazeOnMap}, heat map: ${s.wazeHeatmap}, no Google: ${s.noGoogle}",
        "Speed cameras: ${s.speedCameras}, plate cameras: ${s.plateCameras}, surveillance: ${s.surveillanceCameras}",
        "Navigating: ${model.nav != null}",
        "Map picture included: ${map != null} (it can show where you are).",
        "No GPS position, searches, saved places or accounts are in this file.",
        "",
        "Service tests (fixed box in central London):",
    )
    suspend fun <T> timed(name: String, work: suspend () -> T?, describe: (T) -> String) {
        progress("Testing $name…")
        val t0 = System.nanoTime()
        val result = runCatching { work() }.getOrNull()
        val ms = (System.nanoTime() - t0) / 1_000_000
        lines += "$name: " + (result?.let(describe) ?: "FAILED") + ", $ms ms"
    }
    timed("Waze live map", { Waze.alerts(51.49, -0.15, 51.53, -0.08) }) { "${it.size} reports" }
    timed("Speed cameras (OpenStreetMap)", { Overpass.cameras(51.49, -0.15, 51.53, -0.08, speed = true, plate = true) }) { "${it.size} cameras" }

    return withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "Kite-diagnostics.zip")
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("diagnostics.txt")); zip.write(lines.joinToString("\n").toByteArray()); zip.closeEntry()
                map?.let { zip.putNextEntry(ZipEntry("map.png")); zip.write(it); zip.closeEntry() }
            }
            file
        }.getOrNull()
    }
}

/** A PNG of the map as it is on screen, or null if it can't be had within a few seconds. */
private suspend fun mapPicture(model: AppModel): ByteArray? {
    val m = model.map ?: return null
    val bmp = withTimeoutOrNull(5000) { suspendCancellableCoroutine<Bitmap?> { cont -> m.snapshot { if (cont.isActive) cont.resume(it) } } } ?: return null
    return withContext(Dispatchers.Default) { ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
}

private fun shareFile(ctx: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, "app.kite.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
