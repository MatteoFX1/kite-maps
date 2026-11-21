package app.kite

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

@SuppressLint("SetJavaScriptEnabled")
actual class HiddenWebPage actual constructor(url: String, startScript: String, onLoaded: () -> Unit, onFailed: () -> Unit) {
    private val answers = ConcurrentHashMap<Int, CancellableContinuation<String?>>()
    private val ids = AtomicInteger()
    private var w: WebView? = null

    private inner class Bridge {
        @JavascriptInterface fun done(id: Int, text: String?) { answers.remove(id)?.let { if (it.isActive) it.resume(text) } }
    }

    init {
        val a = AndroidHost.activity
        if (a == null) onFailed() else {
            val v = WebView(a)
            v.settings.javaScriptEnabled = true
            v.settings.domStorageEnabled = true
            // The phone's own Chrome name, without the parts that say "app web view", so the page and
            // reCAPTCHA see a normal browser on this phone.
            v.settings.userAgentString = v.settings.userAgentString.replace("; wv)", ")").replace(Regex(" Version/[0-9.]+"), "")
            CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
            v.addJavascriptInterface(Bridge(), "KiteBridge")
            val startSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (startSupported) WebViewCompat.addDocumentStartJavaScript(v, startScript, setOf("https://www.waze.com"))
            var loaded = false
            v.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (loaded) return
                    loaded = true
                    // Without a start-of-page script, watch from now on instead.
                    if (!startSupported) view.evaluateJavascript(startScript, null)
                    onLoaded()
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) onFailed()
                }
            }
            // Behind everything, so the page runs like a real one (reCAPTCHA's check needs its scripts
            // to keep running) without ever being seen or touched.
            v.alpha = 0.01f
            v.isFocusable = false
            v.setOnTouchListener { _, _ -> true }
            (a.window.decorView as? ViewGroup)?.addView(v, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            w = v
            v.loadUrl(url)
        }
    }

    actual suspend fun run(body: String, args: Map<String, String>, timeoutMs: Long): String? {
        val v = w ?: return null
        val id = ids.incrementAndGet()
        val vars = args.entries.joinToString("") { (k, value) -> "var $k = ${jsQuote(value)};" }
        val js = "(async function () { $vars try { var r = await (async function () { $body })(); KiteBridge.done($id, r == null ? null : String(r)); }" +
            " catch (e) { KiteBridge.done($id, 'ERR ' + e); } })();"
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                answers[id] = cont
                cont.invokeOnCancellation { answers.remove(id) }
                v.evaluateJavascript(js, null)
            }
        }
    }

    actual suspend fun hasCookie(site: String, prefix: String): Boolean =
        CookieManager.getInstance().getCookie(site)?.split(";")?.any { it.trim().startsWith(prefix) } == true

    actual fun close() {
        w?.let { v -> (v.parent as? ViewGroup)?.removeView(v); v.destroy() }
        w = null
    }
}
