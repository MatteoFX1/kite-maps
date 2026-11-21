@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

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
import androidx.compose.ui.platform.LocalHapticFeedback
import app.kite.core.Place
import app.kite.Phone
import app.kite.core.plain
import app.kite.core.urlEnc

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
            APPLE -> "https://maps.apple.com/?ll=$ll&q=" + urlEnc(p.name)
        }
    }
}

/** Press and hold to get a "Copy" bubble for [text]; nothing has to be selected. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Copyable(text: String?, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    if (text.isNullOrEmpty()) { content(); return }
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
                onClick = { Phone.copy(text); copied = true; showing = false })
        }
    }
}
