@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import app.kite.core.Place
import app.kite.core.plain

/** Opens a link in whatever app handles it (browser, dialer, another maps app). */
fun Context.open(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun Context.share(text: String) {
    runCatching {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun Context.copy(text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kite", text))
}

fun telUrl(phone: String?): String? = phone?.let { "tel:" + it.filter { c -> c in "+0123456789" } }
fun webUrl(site: String?): String? = site?.let { if (it.startsWith("http")) it else "https://$it" }

/** Other map apps a place can be handed to, by their web links. */
enum class OtherApp(val label: String) {
    GOOGLE("Google Maps"), WAZE("Waze"), APPLE("Apple Maps");

    fun url(p: Place): String {
        val ll = "${p.location.lat.plain()},${p.location.lng.plain()}"
        return when (this) {
            GOOGLE -> p.shareUrl
            WAZE -> "https://waze.com/ul?ll=$ll&navigate=yes"
            APPLE -> "https://maps.apple.com/?ll=$ll&q=" + java.net.URLEncoder.encode(p.name, "UTF-8").replace("+", "%20")
        }
    }
}

/** Press and hold to get a "Copy" bubble for [text]; nothing has to be selected. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Copyable(text: String?, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    if (text.isNullOrEmpty()) { content(); return }
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    Box(Modifier.combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClick = { onClick?.invoke() },
        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); copied = false; showing = true })) {
        content()
        DropdownMenu(showing, { showing = false }) {
            DropdownMenuItem(text = { Text(if (copied) "Copied" else "Copy") },
                leadingIcon = { Icon(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy, null) },
                onClick = { ctx.copy(text); copied = true; showing = false })
        }
    }
}
