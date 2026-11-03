package app.kite

import android.annotation.SuppressLint
import android.app.Activity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.kite.core.Waze
import kotlinx.coroutines.*
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * When Waze turns Kite's plain requests away, ask from inside the real live map page instead.
 * Since 2026 Waze guards its feed with Google reCAPTCHA: the live map page runs a check in the
 * background and gets a pass (the "recaptcha-ca-t" cookie), and only requests carrying it get
 * reports. So a web view, tucked out of sight behind the map, opens waze.com/live-map, waits for
 * the pass, and fetches the feed from there, copying any extra headers the page itself sends.
 * The same as the iPhone app's WazeWeb.
 */
object WazeWeb {
    private var web: WebView? = null
    private var ready = false
    private var waiting = mutableListOf<CancellableContinuation<Unit>>()
    private var activity: WeakReference<Activity>? = null
    private val answers = ConcurrentHashMap<Int, CancellableContinuation<String?>>()
    private val ids = AtomicInteger()
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What happened last time, for Settings › Road alerts. */
    @Volatile var note = "page: not tried"; private set

    fun install(a: Activity) {
        // A new screen: the old hidden page went with the old one.
        if (activity?.get() !== a) { reset(); finish(ok = false) }
        activity = WeakReference(a)
        Waze.fallback = { url -> fetch(url) }
    }

    /** The screen is going away: drop the hidden page so it doesn't keep the old screen in memory. */
    fun uninstall(a: Activity) {
        if (activity?.get() !== a) return
        reset(); finish(ok = false)
        activity = null
    }

    /** Watches the page's own requests for the feed, and notes reCAPTCHA's key and action. */
    private val watcher = """
(function () {
  var k = window.__kite = { seen: 0, status: 0, headers: null };
  var isFeed = function (u) { return String(u || '').indexOf('/api/georss') >= 0; };
  // What the page itself asked for and got, by type, to compare with what Kite gets.
  var note = function (u, text) {
    k.url = String(u || '').split('?')[1] || '';
    try {
      var t = {}, list = (JSON.parse(text).alerts || []);
      list.forEach(function (a) { t[a.type] = (t[a.type] || 0) + 1; });
      k.types = t;
    } catch (e) {}
  };
  var of = window.fetch;
  window.fetch = function (input, init) {
    var u = typeof input === 'string' ? input : (input && input.url);
    var p = of.apply(this, arguments);
    if (isFeed(u) && !(init && init.__kite)) {
      try {
        var h = {}, src = (init && init.headers) || (input && input.headers);
        if (src) { if (src.forEach) { src.forEach(function (v, n) { h[n] = v; }); } else { for (var n in src) { h[n] = src[n]; } } }
        k.headers = h; k.seen++;
      } catch (e) {}
      p.then(function (r) { k.status = r.status; r.clone().text().then(function (t) { note(u, t); }, function () {}); }, function () {});
    }
    return p;
  };
  var X = XMLHttpRequest.prototype, oo = X.open, os = X.setRequestHeader, sd = X.send;
  X.open = function (m, u) { this.__feed = isFeed(u); this.__u = u; this.__h = {}; return oo.apply(this, arguments); };
  X.setRequestHeader = function (n, v) { if (this.__feed) { this.__h[n] = v; } return os.apply(this, arguments); };
  X.send = function () {
    if (this.__feed) {
      var x = this; k.headers = this.__h; k.seen++;
      this.addEventListener('loadend', function () { k.status = x.status; try { note(x.__u, x.responseText); } catch (e) {} });
    }
    return sd.apply(this, arguments);
  };
  // The page asks reCAPTCHA for a one-off token before each request; note the site key and
  // action it uses, so Kite can ask for fresh tokens of its own.
  var wrap = setInterval(function () {
    var e = window.grecaptcha && window.grecaptcha.enterprise;
    if (!e || !e.execute || e.__kite) { return; }
    var ex = e.execute;
    e.execute = function (key, opts) {
      k.key = key; k.action = (opts && opts.action) || k.action;
      return ex.apply(this, arguments);
    };
    e.__kite = true;
    clearInterval(wrap);
  }, 30);
})();

"""

    private class Bridge {
        @JavascriptInterface fun done(id: Int, text: String?) { answers.remove(id)?.let { if (it.isActive) it.resume(text) } }
    }

    /** Runs [body] (the inside of an async function) in the page, and hands back what it returns. */
    private suspend fun run(w: WebView, body: String, args: Map<String, String> = emptyMap(), timeoutMs: Long = 20_000): String? {
        val id = ids.incrementAndGet()
        val vars = args.entries.joinToString("") { (k, v) -> "var $k = ${JSONObject.quote(v)};" }
        val js = "(async function () { $vars try { var r = await (async function () { $body })(); KiteBridge.done($id, r == null ? null : String(r)); }" +
            " catch (e) { KiteBridge.done($id, 'ERR ' + e); } })();"
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                answers[id] = cont
                cont.invokeOnCancellation { answers.remove(id) }
                w.evaluateJavascript(js, null)
            }
        }
    }

    suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.Main) {
        // Switched off while a request was on its way: don't open the page again.
        if (!AppSettings.wazeOn) return@withContext null
        openPage()
        val w = web
        if (w == null || !ready) { note = "page: didn't load"; return@withContext null }
        val js = """
        var k = window.__kite || {};
        var h = { 'Accept': 'application/json, text/plain, */*' };
        var seen = k.headers || {};
        for (var n in seen) { h[n] = seen[n]; }
        var e = window.grecaptcha && window.grecaptcha.enterprise;
        if (!k.key) {
          var s = document.querySelector('script[src*="recaptcha"][src*="render="]');
          var m = s && s.src.match(/render=([^&]+)/);
          if (m && m[1] !== 'explicit') { k.key = m[1]; }
        }
        if (e && k.key) {
          var t = await new Promise(function (done) {
            e.ready(function () { e.execute(k.key, { action: k.action || 'livemap' }).then(done, function () { done(null); }); });
            setTimeout(function () { done(null); }, 8000);
          });
          if (t) {
            for (var n in h) { if (n.toLowerCase() === 'x-recaptcha-token') { delete h[n]; } }
            h['X-Recaptcha-Token'] = t;
          }
        }
        var r = await fetch(u, { credentials: 'include', headers: h, __kite: true });
        if (!r.ok) { return 'HTTP ' + r.status; }
        return await r.text();
        """
        // A second try a few seconds on: the page's pass may only just be on its way.
        for (attempt in 0 until 2) {
            if (attempt == 1) delay(4000)
            val text = run(w, js, mapOf("u" to url))
            when {
                text == null -> note = "page: no answer"
                text.startsWith("ERR ") -> note = "page: ${text.removePrefix("ERR ")}"
                text.startsWith("HTTP ") -> note = "page: fetch $text · ${pageState(w)}"
                else -> { note = "page: ok, ${text.length} chars"; return@withContext text.toByteArray() }
            }
        }
        // Still turned away: open the page afresh next time, for a new pass.
        reset()
        null
    }

    private suspend fun pageState(w: WebView): String {
        val page = run(w, "var k = window.__kite; return k ? 'page asked ' + k.seen + '× (' + k.status + ')' + (k.key ? ' key ' + String(k.key).slice(0, 6) + ' ' + (k.action || '?') : ' no key') : 'no watcher';", timeoutMs = 3000)
        return "pass ${if (hasPass()) "yes" else "no"}, ${page ?: "?"}"
    }

    private suspend fun pageReady(w: WebView): Boolean =
        run(w, "var k = window.__kite; return !!(k && (k.seen > 0 || k.key));", timeoutMs = 2000) == "true"

    private fun hasPass(): Boolean =
        CookieManager.getInstance().getCookie("https://www.waze.com")?.split(";")?.any { it.trim().startsWith("recaptcha-ca") } == true

    /** Road alerts off, or "Use Kite without Google": close the hidden page so its scripts stop too. */
    fun shutDown() {
        reset()
        finish(ok = false)
    }

    private fun reset() {
        web?.let { w -> (w.parent as? ViewGroup)?.removeView(w); w.destroy() }
        web = null
        ready = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun openPage() {
        if (ready) return
        if (web == null) {
            val a = activity?.get() ?: return
            val w = WebView(a)
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            // The phone's own Chrome name, without the parts that say "app web view", so the page and
            // reCAPTCHA see a normal browser on this phone.
            w.settings.userAgentString = w.settings.userAgentString.replace("; wv)", ")").replace(Regex(" Version/[0-9.]+"), "")
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
            w.addJavascriptInterface(Bridge(), "KiteBridge")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(w, watcher, setOf("https://www.waze.com"))
            }
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (view === web && !ready) {
                        // Without a start-of-page script, watch from now on instead.
                        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(watcher, null)
                        pageLoaded(view)
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (view === web && request.isForMainFrame) finish(ok = false)
                }
            }
            // Behind everything, so the page runs like a real one (reCAPTCHA's check needs its scripts
            // to keep running) without ever being seen or touched.
            w.alpha = 0.01f
            w.isFocusable = false
            w.setOnTouchListener { _, _ -> true }
            (a.window.decorView as? ViewGroup)?.addView(w, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            web = w
            w.loadUrl(Waze.pageURL)
            // Give up waiting after 25 seconds, so a stuck page never holds the map back.
            main.launch { delay(25_000); if (!ready && web === w) finish(ok = false) }
        }
        suspendCancellableCoroutine { waiting.add(it) }
    }

    /** Once the page has loaded, wait (up to 12 seconds) for reCAPTCHA's pass before saying it's ready. */
    private fun pageLoaded(w: WebView) {
        main.launch {
            for (i in 0 until 24) {
                if (hasPass() || pageReady(w)) break
                delay(500)
                if (web !== w) return@launch
            }
            if (web === w) finish(ok = true)
        }
    }

    private fun finish(ok: Boolean) {
        if (ready && waiting.isEmpty()) return
        if (ok) ready = true
        val w = waiting
        waiting = mutableListOf()
        w.forEach { if (it.isActive) it.resume(Unit) }
        if (!ok && !ready) reset()
    }
}
