package app.kite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.FileProvider
import java.io.File
import java.util.Date

actual object Phone {
    private val ctx: Context get() = AndroidHost.activity ?: AndroidHost.app

    actual fun open(url: String) {
        val c = ctx
        runCatching {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Kite opens map links itself: a link meant for Google Maps or the browser mustn't come
            // straight back to Kite ("Walk around in Google Maps" did).
            val handlers = c.packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }
            if (c.packageName in handlers) {
                val others = handlers.filter { it != c.packageName }.distinct()
                when {
                    "com.google.android.apps.maps" in others -> i.setPackage("com.google.android.apps.maps")
                    others.size == 1 -> i.setPackage(others[0])
                    others.isEmpty() -> return@runCatching
                    else -> {
                        val mine = c.packageManager.getLaunchIntentForPackage(c.packageName)?.component
                        c.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, listOfNotNull(mine).toTypedArray<ComponentName>()))
                        return@runCatching
                    }
                }
            }
            c.startActivity(i)
        }
    }

    actual fun share(text: String) {
        runCatching {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    actual fun shareFile(path: String, mime: String, title: String) {
        runCatching {
            val c = ctx
            val uri = FileProvider.getUriForFile(c, "app.kite.files", File(path))
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            c.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val clip get() = AndroidHost.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    actual fun copy(text: String) { clip.setPrimaryClip(ClipData.newPlainText("Kite", text)) }

    actual fun hasCopiedText(): Boolean =
        clip.primaryClipDescription?.let { it.hasMimeType("text/plain") || it.hasMimeType("text/uri-list") } == true

    actual fun paste(): String? =
        clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()

    actual fun openLocationSettings() {
        runCatching {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    actual val appVersion: String
        get() = runCatching {
            val p = AndroidHost.app.packageManager.getPackageInfo(AndroidHost.app.packageName, 0)
            "${p.versionName} (${if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()})"
        }.getOrDefault("?")

    actual val appId: String get() = AndroidHost.app.packageName

    actual val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
}

actual object LocalFormat {
    actual fun time(epochMillis: Long): String = android.text.format.DateFormat.getTimeFormat(AndroidHost.app).format(Date(epochMillis))
    actual fun dateTime(epochMillis: Long): String =
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date(epochMillis))
    actual fun count(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)
    actual val amPm: List<String> get() = java.text.DateFormatSymbols.getInstance().amPmStrings.toList()
    actual val is24Hour: Boolean get() = android.text.format.DateFormat.is24HourFormat(AndroidHost.app)
}

/**
 * Voice search through the phone's speech app (its own "Speak now" window). The Android shell
 * registers [launcher] at start and passes the answer to [deliver].
 */
actual object Dictation {
    var launcher: ((Intent) -> Unit)? = null
    private var pending: ((String?) -> Unit)? = null

    // Phones without Google (like GrapheneOS) may have no speech service at all.
    actual fun available(): Boolean = launcher != null && SpeechRecognizer.isRecognitionAvailable(AndroidHost.app)

    actual fun listen(onResult: (String?) -> Unit) {
        val l = launcher ?: return onResult(null)
        pending = onResult
        try {
            l(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Where to?"))
        } catch (_: Exception) {
            pending = null
            onResult(null)
        }
    }

    fun deliver(data: Intent?) {
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        pending?.invoke(text)
        pending = null
    }

    actual fun stop() { pending = null }
}
