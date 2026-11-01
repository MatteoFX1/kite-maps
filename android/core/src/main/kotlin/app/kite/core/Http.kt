package app.kite.core

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** One answer: the HTTP code, the body and the headers. */
class HttpResp(val code: Int, val body: ByteArray, val headers: Headers) {
    val text: String get() = String(body, Charsets.UTF_8)
    val ok: Boolean get() = code in 200..299
}

/** The app's network: one OkHttp client per purpose, all coroutine-friendly. */
object Http {
    /** Plain client with no cookies, like an ephemeral URLSession. */
    val plain: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun client(timeoutSeconds: Long, cookies: CookieJar? = null, followRedirects: Boolean = true): OkHttpClient =
        plain.newBuilder()
            .connectTimeout(minOf(timeoutSeconds, 15), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .apply { if (cookies != null) cookieJar(cookies) }
            .build()

    /** Bytes sent and received since launch (for Settings' data use line). */
    @Volatile var bytes: Long = 0

    suspend fun send(client: OkHttpClient, request: Request): HttpResp = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val body = it.body.bytes()
                        bytes += body.size
                        cont.resumeWith(Result.success(HttpResp(it.code, body, it.headers)))
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        })
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), client: OkHttpClient = plain): HttpResp {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        return send(client, b.build())
    }

    suspend fun post(
        url: String, body: ByteArray, contentType: String,
        headers: Map<String, String> = emptyMap(), client: OkHttpClient = plain,
    ): HttpResp {
        val b = Request.Builder().url(url).post(body.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        bytes += body.size
        return send(client, b.build())
    }

    /** The answer, or null on any network error. */
    suspend fun getOrNull(url: String, headers: Map<String, String> = emptyMap(), client: OkHttpClient = plain): HttpResp? =
        try { get(url, headers, client) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /** A cookie jar that keeps cookies in memory, like an ephemeral URLSession's storage. */
    class MemoryCookies(seed: List<Cookie> = emptyList()) : CookieJar {
        private val store = mutableMapOf<String, Cookie>()

        init { seed.forEach { store[it.domain + it.path + it.name] = it } }

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { store[it.domain + it.path + it.name] = it }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            store.values.removeAll { it.expiresAt < now }
            return store.values.filter { it.matches(url) }
        }
    }
}

/** [op]'s answer, or null if it takes longer than [seconds]. */
suspend fun <T> deadline(seconds: Double, op: suspend () -> T): T? =
    withTimeoutOrNull((seconds * 1000).toLong()) { op() }

/** URL-encodes a query value the way the Swift code's percent-encoding does. */
fun urlEnc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
