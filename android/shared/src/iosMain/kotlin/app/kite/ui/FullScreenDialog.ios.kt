package app.kite.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A Dialog that covers the whole screen (on iPhone it already can). */
@Composable
actual fun FullScreenDialog(onDismiss: () -> Unit, dismissOnBack: Boolean, content: @Composable () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = dismissOnBack)) { content() }
}
