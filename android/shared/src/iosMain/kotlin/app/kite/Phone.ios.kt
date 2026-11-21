package app.kite

import app.kite.core.nowMillis
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.*
import platform.Speech.*
import platform.UIKit.*
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC

/** The screen on top, to show the share sheet from. */
internal fun topController(): UIViewController? {
    val scenes = UIApplication.sharedApplication.connectedScenes
    val window = scenes.mapNotNull { (it as? UIWindowScene)?.windows }.flatten()
        .mapNotNull { it as? UIWindow }.firstOrNull { it.isKeyWindow() }
        ?: (UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow)
    var c = window?.rootViewController
    while (c?.presentedViewController != null) c = c.presentedViewController
    return c
}

actual object Phone {
    actual fun open(url: String) {
        val u = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(u, emptyMap<Any?, Any?>(), null)
    }

    private fun present(items: List<Any>) {
        val top = topController() ?: return
        val sheet = UIActivityViewController(items, null)
        sheet.popoverPresentationController?.sourceView = top.view
        top.presentViewController(sheet, true, null)
    }

    actual fun share(text: String) {
        // A link shares as a link (so it opens in Maps or the browser), anything else as text.
        val url = if (text.startsWith("http")) NSURL.URLWithString(text) else null
        present(listOf(url ?: text))
    }

    actual fun shareFile(path: String, mime: String, title: String) {
        present(listOf(NSURL.fileURLWithPath(path)))
    }

    actual fun copy(text: String) { UIPasteboard.generalPasteboard.string = text }

    // hasStrings / hasURLs don't read what was copied, so iPhone shows no "pasted from" notice.
    actual fun hasCopiedText(): Boolean = UIPasteboard.generalPasteboard.let { it.hasStrings || it.hasURLs }

    actual fun paste(): String? = UIPasteboard.generalPasteboard.let { it.string ?: it.URL?.absoluteString }

    /** iPhone has no way to open its Location Services page from an app, only Kite's own page. */
    actual fun openLocationSettings() {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null) }
    }

    actual val appVersion: String
        get() {
            val info = NSBundle.mainBundle.infoDictionary
            return "${info?.get("CFBundleShortVersionString") ?: "?"} (${info?.get("CFBundleVersion") ?: "?"})"
        }

    actual val appId: String get() = NSBundle.mainBundle.bundleIdentifier ?: "?"

    actual val deviceName: String
        get() = "${UIDevice.currentDevice.model}, ${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion} (${machine()})"
}

/** The model code, like "iPhone18,1" (or the simulated one on the Mac). */
private fun machine(): String =
    NSProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] as? String ?: "device"

actual object LocalFormat {
    private fun formatter(date: NSDateFormatterStyle, time: NSDateFormatterStyle) = NSDateFormatter().apply {
        dateStyle = date; timeStyle = time; locale = NSLocale.currentLocale
    }
    private val timeOnly by lazy { formatter(NSDateFormatterNoStyle, NSDateFormatterShortStyle) }
    private val dateAndTime by lazy { formatter(NSDateFormatterMediumStyle, NSDateFormatterShortStyle) }
    private fun date(ms: Long) = NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)

    actual fun time(epochMillis: Long): String = timeOnly.stringFromDate(date(epochMillis))
    actual fun dateTime(epochMillis: Long): String = dateAndTime.stringFromDate(date(epochMillis))
    actual fun count(n: Int): String =
        NSNumberFormatter.localizedStringFromNumber(NSNumber(int = n), NSNumberFormatterDecimalStyle)
    actual val amPm: List<String> get() = listOf(timeOnly.AMSymbol, timeOnly.PMSymbol)
    actual val is24Hour: Boolean
        get() = NSDateFormatter.dateFormatFromTemplate("j", 0u, NSLocale.currentLocale)?.contains("a") != true
}

/**
 * Voice search with iPhone's own speech recognition. Listens until there's a short pause after
 * some words (or 8 seconds), then gives back what it heard.
 */
@OptIn(ExperimentalForeignApi::class)
actual object Dictation {
    private var engine: AVAudioEngine? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var done: ((String?) -> Unit)? = null
    private var heard: String? = null
    private var lastChange = 0L

    actual fun available(): Boolean = SFSpeechRecognizer()?.isAvailable() == true

    actual fun listen(onResult: (String?) -> Unit) {
        stop()
        done = onResult
        SFSpeechRecognizer.requestAuthorization { status ->
            onMain {
                if (status != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) finish()
                else AVAudioSession.sharedInstance().requestRecordPermission { ok -> onMain { if (ok) begin() else finish() } }
            }
        }
    }

    private fun begin() {
        val recognizer = SFSpeechRecognizer() ?: return finish()
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryRecord, AVAudioSessionModeMeasurement, AVAudioSessionCategoryOptionDuckOthers, null)
        session.setActive(true, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
        val req = SFSpeechAudioBufferRecognitionRequest().apply { shouldReportPartialResults = true }
        val eng = AVAudioEngine()
        val input = eng.inputNode
        input.installTapOnBus(0u, 1024u, input.outputFormatForBus(0u)) { buffer, _ -> if (buffer != null) req.appendAudioPCMBuffer(buffer) }
        eng.prepare()
        if (!eng.startAndReturnError(null)) return finish()
        engine = eng; request = req
        heard = null; lastChange = nowMillis()
        val started = nowMillis()
        task = recognizer.recognitionTaskWithRequest(req) { result, error ->
            onMain {
                result?.bestTranscription?.formattedString?.let { if (it != heard) { heard = it; lastChange = nowMillis() } }
                if (error != null || result?.isFinal() == true) finish()
            }
        }
        fun check() {
            if (done == null) return
            val now = nowMillis()
            if ((heard != null && now - lastChange > 1500) || now - started > 8000) finish() else after(250) { check() }
        }
        after(250) { check() }
    }

    private fun finish() {
        val cb = done
        val text = heard?.takeIf { it.isNotBlank() }
        stop()
        cb?.invoke(text)
    }

    actual fun stop() {
        done = null
        engine?.let { it.inputNode.removeTapOnBus(0u); it.stop() }
        request?.endAudio()
        task?.cancel()
        engine = null; request = null; task = null
        AVAudioSession.sharedInstance().setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
    }

    private fun onMain(block: () -> Unit) = NSOperationQueue.mainQueue.addOperationWithBlock(block)
    private fun after(ms: Long, block: () -> Unit) =
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, ms * NSEC_PER_MSEC.toLong()), dispatch_get_main_queue(), block)
}
