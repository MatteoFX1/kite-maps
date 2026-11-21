package app.kite.core

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlin.concurrent.Volatile

/** The headers of an answer; names are looked up ignoring case. */
class RespHeaders(private val map: Map<String, List<String>>) {
    operator fun get(name: String): String? =
        map.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/** One answer: the HTTP code, the body and the headers. */
class HttpResp(val code: Int, val body: ByteArray, val headers: RespHeaders) {
    val text: String get() = body.decodeToString()
    val ok: Boolean get() = code in 200..299
}

/** The network engine each phone uses (OkHttp on Android, Apple's URLSession on iPhone). */
internal expect fun httpEngine(): HttpClientEngineFactory<*>

/** A cookie to start a session with. */
data class SeedCookie(val domain: String, val path: String, val name: String, val value: String, val secure: Boolean, val expiresAtMillis: Long)

/** The app's network: one client per purpose, all coroutine-friendly. */
object Http {
    /** Plain client with no cookies, like an ephemeral URLSession. */
    val plain: HttpClient by lazy { make(30, 15, null, true) }

    fun client(timeoutSeconds: Long, cookies: MemoryCookies? = null, followRedirects: Boolean = true): HttpClient =
        make(timeoutSeconds, minOf(timeoutSeconds, 15), cookies, followRedirects, timeoutSeconds * 2)

    private fun make(read: Long, connect: Long, cookies: MemoryCookies?, follow: Boolean, total: Long? = null): HttpClient =
        HttpClient(httpEngine()) {
            expectSuccess = false
            followRedirects = follow
            install(HttpTimeout) {
                connectTimeoutMillis = connect * 1000
                socketTimeoutMillis = read * 1000
                if (total != null) requestTimeoutMillis = total * 1000
            }
            if (cookies != null) install(HttpCookies) { storage = cookies }
        }

    /** Bytes sent and received since launch (for Settings' data use line). */
    @Volatile var bytes: Long = 0

    private val skip = setOf("content-type", "content-length")

    private suspend fun run(client: HttpClient, url: String, headers: Map<String, String>, post: Pair<ByteArray, String>?): HttpResp {
        val r = client.request(url) {
            method = if (post == null) HttpMethod.Get else HttpMethod.Post
            headers.forEach { (k, v) -> if (k.lowercase() !in skip) header(k, v) }
            if (post != null) setBody(ByteArrayContent(post.first, ContentType.parse(post.second)))
        }
        val body = r.bodyAsBytes()
        bytes += body.size
        return HttpResp(r.status.value, body, RespHeaders(r.headers.entries().associate { it.key to it.value }))
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), client: HttpClient = plain): HttpResp =
        run(client, url, headers, null)

    suspend fun post(
        url: String, body: ByteArray, contentType: String,
        headers: Map<String, String> = emptyMap(), client: HttpClient = plain,
    ): HttpResp {
        bytes += body.size
        return run(client, url, headers, body to contentType)
    }

    /** The answer, or null on any network error. */
    suspend fun getOrNull(url: String, headers: Map<String, String> = emptyMap(), client: HttpClient = plain): HttpResp? =
        try { get(url, headers, client) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /** A cookie jar that keeps cookies in memory, like an ephemeral URLSession's storage. */
    class MemoryCookies(seed: List<SeedCookie> = emptyList()) : CookiesStorage {
        private class Kept(val domain: String, val hostOnly: Boolean, val path: String, val name: String, val cookie: Cookie, val expires: Long?)
        private val lock = KLock()
        private val store = mutableMapOf<String, Kept>()

        init {
            seed.forEach {
                store[it.domain + it.path + it.name] = Kept(it.domain, false, it.path, it.name,
                    Cookie(it.name, it.value, domain = it.domain, path = it.path, secure = it.secure), it.expiresAtMillis)
            }
        }

        override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
            val domain = (cookie.domain ?: requestUrl.host).removePrefix(".").lowercase()
            val path = cookie.path ?: requestUrl.encodedPath.substringBeforeLast('/', "").ifEmpty { "/" }
            val expires = cookie.maxAge?.let { nowMillis() + it * 1000L } ?: cookie.expires?.timestamp
            lock.locked {
                store[domain + path + cookie.name] = Kept(domain, cookie.domain == null, path, cookie.name, cookie, expires)
            }
        }

        override suspend fun get(requestUrl: Url): List<Cookie> {
            val now = nowMillis()
            val host = requestUrl.host.lowercase()
            val path = requestUrl.encodedPath.ifEmpty { "/" }
            return lock.locked {
                store.values.removeAll { it.expires != null && it.expires < now }
                store.values.filter { k ->
                    val hostOk = if (k.hostOnly) host == k.domain else host == k.domain || host.endsWith("." + k.domain)
                    val pathOk = path == k.path || path.startsWith(if (k.path.endsWith("/")) k.path else k.path + "/")
                    hostOk && pathOk && (!k.cookie.secure || requestUrl.protocol.isSecure())
                }.map { it.cookie }
            }
        }

        override fun close() {}
    }
}

/** [op]'s answer, or null if it takes longer than [seconds]. */
suspend fun <T> deadline(seconds: Double, op: suspend () -> T): T? =
    withTimeoutOrNull((seconds * 1000).toLong()) { op() }

/** URL-encodes a query value the way the Swift code's percent-encoding does. */
fun urlEnc(s: String): String = formEnc(s).replace("+", "%20")
