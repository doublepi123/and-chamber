package io.andchamber.app.data

import io.andchamber.app.notify.ApiRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 * in-memory jar, and re-logs in when the server rejects the session. Never follows redirects with the
 * password, and only ever targets loopback.
 */
class LocalApiClient(
    private val baseUrl: () -> String?,
    private val password: () -> String?,
    timeoutMillis: Long = 8_000L,
) {
    private val jar = MemoryCookieJar()
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(jar)
            .followRedirects(false)
            .followSslRedirects(false)
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
            val base = baseUrl()?.toHttpUrl() ?: return@withContext ApiResult(0, "Server is not running")
            if (!isLoopback(base)) return@withContext ApiResult(0, "Refusing a non-loopback address")
            if (!login(base)) return@withContext ApiResult(401, "Could not sign in to OpenChamber")
            val first = execute(base, request, directory)
            if (first.status != 401) return@withContext first
            // The session expired: forget it, sign in again, and retry once.
            jar.clear()
            if (!login(base)) return@withContext first
            execute(base, request, directory)
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

    private fun login(base: HttpUrl): Boolean {
        if (jar.hasSession(base)) return true
        val pw = password()?.takeIf { it.isNotEmpty() } ?: return false
        val body = buildJsonObject {
            put("password", pw)
            put("trustDevice", true)
        }.toString()
        val request =
            Request.Builder()
                .url(base.resolve("/auth/session")!!)
                .post(body.toRequestBody(JSON))
                .build()
        return runCatching {
            client.newCall(request).execute().use { it.code in 200..299 }
        }.getOrDefault(false)
    }

    private fun execute(
        base: HttpUrl,
        request: ApiRequest,
        directory: String?,
    ): ApiResult {
        val payload = request.body?.toString()
        val requestBody = if (request.method == "GET") null else (payload ?: "").toRequestBody(JSON)
        val builder =
            Request.Builder()
                .url(base.resolve(request.path)!!)
                .method(request.method, requestBody)
        // OpenChamber decodes this with decodeURIComponent, so encode like encodeURIComponent: space is %20.
        if (!directory.isNullOrBlank()) builder.header("x-opencode-directory", encodeUriComponent(directory))
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                ApiResult(response.code, response.body?.string().orEmpty())
            }
        }.getOrElse { ApiResult(0, it.message ?: "Request failed") }
    }

    private fun isLoopback(url: HttpUrl): Boolean = url.host == "127.0.0.1" || url.host == "localhost" || url.host == "::1"

    /** JavaScript encodeURIComponent: like URLEncoder, but a space is %20 and + stays a literal %2B. */
    private fun encodeUriComponent(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
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
