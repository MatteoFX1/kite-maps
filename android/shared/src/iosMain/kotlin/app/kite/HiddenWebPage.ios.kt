package app.kite

import kotlinx.cinterop.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGRectMake
import platform.Foundation.*
import platform.UIKit.*
import platform.WebKit.*
import platform.darwin.NSObject
import kotlin.coroutines.resume

actual class HiddenWebPage actual constructor(url: String, startScript: String, onLoaded: () -> Unit, onFailed: () -> Unit) {
    private var web: WKWebView? = null
    private var loaded = false

    private val nav = object : NSObject(), WKNavigationDelegateProtocol {
        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            if (loaded) return
            loaded = true
            onLoaded()
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) { onFailed() }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) { onFailed() }
    }

    init {
        val config = WKWebViewConfiguration()
        config.userContentController.addUserScript(
            WKUserScript(startScript, WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, true))
        val w = WKWebView(CGRectMake(0.0, 0.0, 400.0, 800.0), config)
        w.navigationDelegate = nav
        // Behind everything, so the page runs like a real one (reCAPTCHA's check needs its scripts
        // to keep running) without ever being seen or touched.
        w.alpha = 0.01
        w.userInteractionEnabled = false
        val window = UIApplication.sharedApplication.keyWindow
            ?: (UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow)
        if (window == null) onFailed() else {
            window.insertSubview(w, 0)
            web = w
            NSURL.URLWithString(url)?.let { w.loadRequest(NSURLRequest.requestWithURL(it)) }
        }
    }

    actual suspend fun run(body: String, args: Map<String, String>, timeoutMs: Long): String? {
        val w = web ?: return null
        val wrapped = "try { var r = await (async function () { $body })(); return r == null ? null : String(r); } catch (e) { return 'ERR ' + e; }"
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                w.callAsyncJavaScript(wrapped, args.mapKeys { it.key as Any? }, null, WKContentWorld.pageWorld) { result, _ ->
                    if (cont.isActive) cont.resume(result as? String)
                }
            }
        }
    }

    actual suspend fun hasCookie(site: String, prefix: String): Boolean {
        val host = NSURL.URLWithString(site)?.host ?: return false
        return suspendCancellableCoroutine { cont ->
            WKWebsiteDataStore.defaultDataStore().httpCookieStore.getAllCookies { list ->
                val found = list?.any { c -> (c as? NSHTTPCookie)?.let { it.name.startsWith(prefix) && host.endsWith(it.domain.removePrefix(".")) } == true } == true
                if (cont.isActive) cont.resume(found)
            }
        }
    }

    actual fun close() {
        web?.stopLoading()
        web?.removeFromSuperview()
        web = null
    }
}
