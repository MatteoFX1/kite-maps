import Foundation
import VelaCore
import WebKit

/**
 * When Waze turns Kite's plain requests away, ask from inside the real live map page instead: a
 * hidden web view opens waze.com/live-map once, then fetches the feed from there, like the page does.
 */
@MainActor
final class WazeWeb: NSObject, WKNavigationDelegate {
    static let shared = WazeWeb()

    private var web: WKWebView?
    private var ready = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    static func install() {
        Waze.fallback = { url in await WazeWeb.shared.fetch(url) }
    }

    func fetch(_ url: URL) async -> Data? {
        await openPage()
        guard let web, ready else { return nil }
        let js = """
        const r = await fetch(u, { credentials: 'include', headers: { 'Accept': 'application/json' } });
        if (!r.ok) { return null; }
        return await r.text();
        """
        guard let text = try? await web.callAsyncJavaScript(js, arguments: ["u": url.absoluteString], contentWorld: .page) as? String else { return nil }
        return Data(text.utf8)
    }

    private func openPage() async {
        if ready { return }
        if web == nil {
            let w = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
            w.navigationDelegate = self
            w.customUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
            web = w
            w.load(URLRequest(url: Waze.pageURL))
            // Give up waiting after 20 seconds, so a stuck page never holds the map back.
            Task {
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                if !self.ready, self.web === w { self.finish(ok: false) }
            }
        }
        await withCheckedContinuation { waiting.append($0) }
    }

    private func finish(ok: Bool) {
        if ok { ready = true }
        let w = waiting
        waiting = []
        for c in w { c.resume() }
        if !ok && !ready { web = nil }
    }

    // WebKit calls these on the main thread; a late call from a page already given up on is ignored.
    nonisolated func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        MainActor.assumeIsolated { if webView === web { finish(ok: true) } }
    }

    nonisolated func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        MainActor.assumeIsolated { if webView === web { finish(ok: false) } }
    }

    nonisolated func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        MainActor.assumeIsolated { if webView === web { finish(ok: false) } }
    }
}
