package io.andchamber.app.data

import io.andchamber.app.notify.ApiRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Outcome of one local API call. [status] 0 means the request never completed. */
data class ApiResult(
    val status: Int,
    val body: String,
) {
    val ok: Boolean get() = status in 200..299
}

/**
 * Talks to the on-device OpenChamber server for notification actions. Logs in once with the UI
 * password (the same POST /auth/session the WebView uses), keeps the session cookie in a private
 * in-memory jar, and re-logs in when the server rejects the session.
 *
 * Trust: a request is only ever made while [localReady] reports a non-null launch id that is still the
 * same launch, and the base URL still points at the same canonical loopback origin. That pair is the
 * [LocalApiLease] captured when a send begins. Every request carries the lease as a tag and a network
 * interceptor refuses to send it once the lease is stale. A stale lease drops the cookie jar and the
 * result is a failure; the password is never sent to a server that was not the lease's launch.
 *
 * Default readiness denies everything. Callers must pass a real [LocalReadiness].
 *
 * Sends are serialized by a mutex, so a second send cannot interleave with a login or a 401 retry of
 * the first. Redirects are never followed. Failures never throw across this boundary and never log
 * credentials.
 */
class LocalApiClient(
    private val baseUrl: () -> String?,
    private val password: () -> String?,
    private val localReady: LocalReadiness = LocalReadiness { null },
    timeoutMillis: Long = 8_000L,
) {
    private val jar = MemoryCookieJar()
    private val mutex = Mutex()

    /** The lease the cookie jar currently belongs to. Only read or written while [mutex] is held. */
    private var bound: LocalApiLease? = null

    /** Last gate before bytes leave: the lease must still be current and the URL must stay on its origin. */
    private val guard =
        Interceptor { chain ->
            val request = chain.request()
            val lease = request.tag(LocalApiLease::class.java)
            if (lease == null || !isCurrent(lease, request.url)) throw IOException(CHANGED_MESSAGE)
            chain.proceed(request)
        }

    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(jar)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor(guard)
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()

    private val json = Json

    /** Runs [request] against the local server, logging in first when there is no session. */
    suspend fun send(
        request: ApiRequest,
        directory: String? = null,
    ): ApiResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    sendLocked(request, directory)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    abandon()
                }
            }
        }

    /** GET [path] and parse the body as a JSON object. Null when the call fails or is not an object. */
    suspend fun getJson(path: String): JsonObject? {
        val result = send(ApiRequest("GET", path, null))
        if (!result.ok) return null
        return runCatching { json.parseToJsonElement(result.body) as? JsonObject }.getOrNull()
    }

    /** GET /api/sessions/status, the pending forms and permissions. Null when unavailable. */
    suspend fun pending(): JsonObject? {
        val result = send(ApiRequest("GET", "/api/sessions/status", null))
        if (!result.ok) return null
        return runCatching { (json.parseToJsonElement(result.body) as? JsonObject)?.get("pending") as? JsonObject }
            .getOrNull()
    }

    /** Caller holds [mutex]. */
    private fun sendLocked(
        request: ApiRequest,
        directory: String?,
    ): ApiResult {
        val launch = currentLaunch()
        if (launch == null) {
            dropBinding()
            return ApiResult(0, "Server is not running")
        }
        val origin = currentOrigin()
        if (origin == null) {
            dropBinding()
            return ApiResult(0, "Refusing a non-loopback address")
        }
        val lease = LocalApiLease(launch, origin)
        // A different launch (restart) or origin never inherits the previous session cookie.
        if (bound != lease) {
            jar.clear()
            bound = lease
        }
        val target =
            lease.origin.resolveSameOrigin(request.path)
                ?: return ApiResult(0, "Refusing a request outside the local server")

        if (!jar.hasSession(lease.origin) && !login(lease)) {
            return if (isCurrent(lease)) ApiResult(401, "Could not sign in to OpenChamber") else abandon()
        }
        val first = execute(lease, target, request, directory)
        if (!isCurrent(lease)) return abandon()
        if (first.status != 401) return first

        // The session expired: forget it, sign in again, and retry once. Only on the same lease.
        jar.clear()
        if (!login(lease)) return if (isCurrent(lease)) first else abandon()
        val retry = execute(lease, target, request, directory)
        return if (isCurrent(lease)) retry else abandon()
    }

    /** Signs in with the UI password. False when there is no password, the lease is stale, or the server refuses. */
    private fun login(lease: LocalApiLease): Boolean {
        if (!isCurrent(lease)) return false
        val pw = runCatching { password() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        val body =
            buildJsonObject {
                put("password", pw)
                put("trustDevice", true)
            }.toString()
        val url = lease.origin.resolve("/auth/session") ?: return false
        val request =
            Request.Builder()
                .url(url)
                .tag(LocalApiLease::class.java, lease)
                .post(body.toRequestBody(JSON))
                .build()
        val ok =
            runCatching {
                client.newCall(request).execute().use { it.code in 200..299 }
            }.getOrDefault(false)
        // Set-Cookie was stored by the bridge before this point; a change during the call discards it.
        return ok && isCurrent(lease)
    }

    private fun execute(
        lease: LocalApiLease,
        target: HttpUrl,
        request: ApiRequest,
        directory: String?,
    ): ApiResult {
        val payload = request.body?.toString()
        val requestBody = if (request.method == "GET") null else OneShotJsonBody(payload ?: "")
        val builder =
            Request.Builder()
                .url(target)
                .method(request.method, requestBody)
                .tag(LocalApiLease::class.java, lease)
        // OpenChamber decodes this with decodeURIComponent, so encode like encodeURIComponent: space is %20.
        if (!directory.isNullOrBlank()) builder.header("x-opencode-directory", encodeUriComponent(directory))
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                ApiResult(response.code, response.body?.string().orEmpty())
            }
        }.getOrElse { ApiResult(0, it.message ?: "Request failed") }
    }

    /** True while [lease] is still the trusted launch on its origin (and [url], when given, stays on it). */
    private fun isCurrent(
        lease: LocalApiLease,
        url: HttpUrl? = null,
    ): Boolean {
        if (currentLaunch() != lease.launchId) return false
        val origin = currentOrigin() ?: return false
        if (!origin.sameOrigin(lease.origin)) return false
        return url == null || url.sameOrigin(lease.origin)
    }

    /** Provider failures count as "not trusted". */
    private fun currentLaunch(): Long? = runCatching { localReady.runningLaunchId() }.getOrNull()

    private fun currentOrigin(): HttpUrl? = canonicalLoopbackOrigin(runCatching { baseUrl() }.getOrNull())

    private fun dropBinding() {
        jar.clear()
        bound = null
    }

    private fun abandon(): ApiResult {
        dropBinding()
        return ApiResult(0, CHANGED_MESSAGE)
    }

    /** JavaScript encodeURIComponent: like URLEncoder, but a space is %20 and + stays a literal %2B. */
    private fun encodeUriComponent(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val CHANGED_MESSAGE = "The local OpenChamber changed; request dropped"
    }
}

/**
 * Body of a business call (prompt, permission, form). OkHttp's RetryAndFollowUpInterceptor re-sends
 * a request after a 503 with Retry-After: 0 even when retryOnConnectionFailure(false), but only if the
 * body is not one-shot. A replayed prompt could run twice, so this body reports one-shot. The same-lease
 * 401 re-login calls execute() again and builds a new body.
 */
internal class OneShotJsonBody(private val payload: String) : RequestBody() {
    private val bytes: ByteArray = payload.toByteArray(Charsets.UTF_8)

    override fun contentType(): MediaType = JSON_TYPE

    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }

    override fun isOneShot(): Boolean = true

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Holds cookies for the lifetime of the client only. Nothing is written to disk. */
class MemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        this.cookies.removeAll { existing -> cookies.any { it.name == existing.name } }
        this.cookies += cookies
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }

    /** True when a non-expired OpenChamber session cookie covers [url]. */
    @Synchronized
    fun hasSession(url: HttpUrl): Boolean =
        cookies.any { it.name.startsWith("oc_ui_session") && it.matches(url) }

    @Synchronized
    fun clear() {
        cookies.clear()
    }
}
