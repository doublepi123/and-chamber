package io.andchamber.app.data

import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionAuthenticator
import io.andchamber.app.runtime.WebSessionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Writes a cookie header into the WebView's cookie jar. Abstracted so tests need no Android. */
interface WebCookieSink {
    fun setCookie(
        url: String,
        header: String,
    )

    fun flush()
}

/**
 * The local runtime's readiness for a login. [launchId] identifies the launch that is Running now;
 * the password may be sent only while that same launch is still the Running one, checked again
 * right before the POST, so a launch that died between the check and the request sends nothing.
 */
fun interface LocalReadiness {
    /** The current launch id when Running, or null when not Running. */
    fun runningLaunchId(): Long?
}

/**
 * Prepares a session for the WebView: health-checks the server, logs in with the known UI password
 * (POST /auth/session) and copies every Set-Cookie into the WebView cookie jar.
 *
 * Rules:
 *  - The login client must not follow redirects. A 3xx on the login path is NeedsLogin, never a
 *    silent hop to another origin with the password attached.
 *  - A password is never sent in cleartext to a non-loopback host unless the remote entry has
 *    allowInsecureHttp. Such a request is refused without any network call.
 *  - The local server is asked for login only while the controller reports Running.
 *
 * Cookies: OpenChamber names its session cookie `oc_ui_session_<port>` when the Host header has a
 * port. Browsers do not isolate cookies by port, so the names are what keeps two servers on one host
 * apart; this code passes each Set-Cookie header through unchanged.
 */
class DefaultWebSessionAuthenticator(
    client: OkHttpClient,
    private val cookies: WebCookieSink,
    private val localPassword: () -> String?,
    private val localReady: LocalReadiness = LocalReadiness { 1L },
    private val remoteStoredPassword: (ServerTarget.Remote) -> String? = { it.uiPassword },
) : WebSessionAuthenticator {
    /** Health and session probes may follow redirects; they never carry a password. */
    private val probeClient = client
    /** Login must never follow a redirect: a 3xx is reported, not re-sent with the password. */
    private val loginClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun prepare(target: ServerTarget): WebSessionResult =
        withContext(Dispatchers.IO) {
            val base = target.baseUrl.trimEnd('/')
            val url = base.toHttpUrlOrNull() ?: return@withContext WebSessionResult.Unreachable("Invalid server address: ${target.baseUrl}")
            if (!isHealthy(base)) {
                return@withContext WebSessionResult.Unreachable("No OpenChamber server answered at $base")
            }

            // The local password is released only for a Running launch, and that launch is checked again
            // right before the POST: a launch that changed or died in between sends nothing.
            val launchId = if (target is ServerTarget.Local) localReady.runningLaunchId() else null
            if (target is ServerTarget.Local && launchId == null) {
                return@withContext WebSessionResult.Unreachable("The local OpenChamber is not running")
            }
            val password =
                when (target) {
                    is ServerTarget.Local -> localPassword()
                    is ServerTarget.Remote -> remoteStoredPassword(target)
                }

            if (password.isNullOrEmpty()) {
                // No password to use: only a session that is already signed in counts as Ready.
                return@withContext if (isAuthenticated(base)) WebSessionResult.Ready(base) else WebSessionResult.NeedsLogin(base)
            }
            if (target is ServerTarget.Local && localReady.runningLaunchId() != launchId) {
                return@withContext WebSessionResult.Unreachable("The local OpenChamber restarted; try again")
            }

            val allowed = target !is ServerTarget.Remote || target.allowInsecureHttp || url.isTransportSafe()
            if (!allowed) {
                return@withContext WebSessionResult.Unreachable(
                    "Refusing to send the password over plain http to ${url.host}. Enable insecure http for this server to allow it.",
                )
            }

            login(base, password)
        }

    private fun isHealthy(base: String): Boolean =
        runCatching {
            val request = Request.Builder().url("$base/health").get().build()
            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body?.string().orEmpty()
                json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content == "ok"
            }
        }.getOrDefault(false)

    private fun isAuthenticated(base: String): Boolean =
        runCatching {
            val request = Request.Builder().url("$base/auth/session").get().build()
            probeClient.newCall(request).execute().use { response ->
                val ok = response.code == 200
                if (ok) storeCookies(base, response.headers("Set-Cookie"))
                ok
            }
        }.getOrDefault(false)

    private fun login(
        base: String,
        password: String,
    ): WebSessionResult {
        val body =
            buildJsonObject {
                put("password", password)
                put("trustDevice", true)
            }.toString()
        val request =
            Request.Builder()
                .url("$base/auth/session")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
        return runCatching {
            loginClient.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> {
                        storeCookies(base, response.headers("Set-Cookie"))
                        WebSessionResult.Ready(base)
                    }
                    401 -> WebSessionResult.NeedsLogin(base)
                    // A redirect means the server wants us elsewhere; never follow it with the password.
                    in 300..399 -> WebSessionResult.NeedsLogin(base)
                    else -> WebSessionResult.Unreachable("Login failed with HTTP ${response.code}")
                }
            }
        }.getOrElse { WebSessionResult.Unreachable(it.message ?: "Login request failed") }
    }

    private fun storeCookies(
        base: String,
        headers: List<String>,
    ) {
        if (headers.isEmpty()) return
        headers.forEach { header -> cookies.setCookie(base, header) }
        cookies.flush()
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** https, or http to a loopback host (the password never leaves the device). */
internal fun HttpUrl.isTransportSafe(): Boolean =
    isHttps || host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
