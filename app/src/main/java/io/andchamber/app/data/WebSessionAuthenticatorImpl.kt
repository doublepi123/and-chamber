package io.andchamber.app.data

import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionAuthenticator
import io.andchamber.app.runtime.WebSessionCookies
import io.andchamber.app.runtime.WebSessionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import io.andchamber.app.runtime.WebSessionIdentity
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * The local server's trust for a login: the launch id, or null when that launch is not trustworthy
 * right now (child not confirmed listening, child dead, or stopped). See OpenChamberServer.readyLaunchId.
 * A provider that throws counts as null.
 */
fun interface LocalReadiness {
    fun runningLaunchId(): Long?
}

/**
 * Prepares a session for the WebView: health-checks the server, logs in with the password when one is
 * known (POST /auth/session), and stores the resulting Set-Cookie headers through the caller's
 * [WebSessionCookies] (the capability of that target's own WebView profile).
 *
 * Rules:
 *  - The cookies must belong to this target and its canonical origin. Otherwise: no HTTP request, no
 *    password read, Unreachable.
 *  - No request uses a cookie jar, and no request follows redirects. A 3xx on the login path is
 *    NeedsLogin, its cookies are never stored, and nothing is re-sent with the password.
 *  - A password is never sent in cleartext to a non-loopback host unless the remote entry has
 *    allowInsecureHttp.
 *  - Local targets are trusted only while [LocalReadiness] reports the same non-null launch id before
 *    and right before the POST and again before cookies are stored. Default readiness denies.
 *  - Auth for one identity is serialized. Cookies are stored only while the coroutine is still active,
 *    and a started store is completed (NonCancellable), so a cancelled older attempt cannot write over
 *    a newer login.
 *  - CancellationException is never swallowed.
 */
class DefaultWebSessionAuthenticator(
    client: OkHttpClient,
    private val localPassword: () -> String?,
    private val localReady: LocalReadiness = LocalReadiness { null },
    private val remoteStoredPassword: (ServerTarget.Remote) -> String? = { it.uiPassword },
) : WebSessionAuthenticator {
    /** Shared by health, session probe and login: no cookie jar and no redirects, ever. */
    private val authClient: OkHttpClient =
        client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val locks = ConcurrentHashMap<WebSessionIdentity, Mutex>()

    override suspend fun prepare(
        target: ServerTarget,
        cookies: WebSessionCookies,
    ): WebSessionResult {
        val identity = cookies.identity
        val origin = canonicalWebOrigin(target.baseUrl)
        // Identity is checked before anything else: a mismatch makes no request and reads no secret.
        if (identity.targetId != target.id || origin == null || origin != identity.origin) {
            return WebSessionResult.Unreachable("The web profile does not belong to this server")
        }
        val base = target.baseUrl.trimEnd('/')
        val url = base.toHttpUrlOrNull() ?: return WebSessionResult.Unreachable("Invalid server address: ${target.baseUrl}")
        val lock = locks.computeIfAbsent(identity) { Mutex() }
        return try {
            lock.withLock { prepareLocked(target, base, url, cookies) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WebSessionResult.Unreachable("Could not prepare the session")
        }
    }

    private suspend fun prepareLocked(
        target: ServerTarget,
        base: String,
        url: HttpUrl,
        cookies: WebSessionCookies,
    ): WebSessionResult {
        val health = health(base, cookies, target is ServerTarget.Remote)
        if (health.gated && target is ServerTarget.Remote) return WebSessionResult.NeedsGatewayLogin(base)
        if (!health.healthy) return WebSessionResult.Unreachable("No OpenChamber server answered at $base")

        // The local password is released only while the same launch is trusted, checked before and
        // right before the POST. A child that died (or was replaced) in between sends nothing.
        val launchId = if (target is ServerTarget.Local) readyLaunchId() else null
        if (target is ServerTarget.Local && launchId == null) {
            return WebSessionResult.Unreachable("The local OpenChamber is not running")
        }
        val password =
            when (target) {
                is ServerTarget.Local -> localPassword()
                is ServerTarget.Remote -> remoteStoredPassword(target)
            }

        if (password.isNullOrEmpty()) {
            // No password: only a session the server already accepts (with this profile's cookies) counts.
            return when (val probe = probeSession(base, cookies, target is ServerTarget.Remote)) {
                ProbeResult.Gateway -> if (target is ServerTarget.Remote) WebSessionResult.NeedsGatewayLogin(base) else WebSessionResult.NeedsLogin(base)
                ProbeResult.Accepted -> WebSessionResult.Ready(base)
                ProbeResult.Rejected -> WebSessionResult.NeedsLogin(base)
            }
        }
        if (target is ServerTarget.Local && readyLaunchId() != launchId) {
            return WebSessionResult.Unreachable("The local OpenChamber restarted; try again")
        }

        val allowed = target !is ServerTarget.Remote || target.allowInsecureHttp || url.isTransportSafe()
        if (!allowed) {
            return WebSessionResult.Unreachable(
                "Refusing to send the password over plain http to ${url.host}. Enable insecure http for this server to allow it.",
            )
        }
        return login(base, password, cookies, target, launchId)
    }

    private suspend fun health(base: String, cookies: WebSessionCookies, remote: Boolean): HealthResult {
        val header = if (remote) cookies.cookieHeader(base) else null
        return fetch(default = HealthResult(false, false)) {
            val builder = Request.Builder().url("$base/health").get()
            if (header != null) builder.header("Cookie", header)
            val request = builder.build()
            authClient.newCall(request).execute().use { response ->
                if (isCloudflareAccessGate(response.code, response.header("Location"), response.headers.toMultimap())) return@use HealthResult(false, true)
                if (!response.isSuccessful) return@use HealthResult(false, false)
                val body = response.body?.string().orEmpty()
                HealthResult(json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content == "ok", false)
            }
        }
    }

    /** GET /auth/session with this profile's cookies. True when the server accepts them (its cookies are stored). */
    private suspend fun probeSession(
        base: String,
        cookies: WebSessionCookies,
        remote: Boolean,
    ): ProbeResult {
        val header = cookies.cookieHeader(base)
        val reply =
            fetch<SetCookieReply?>(default = null) {
                val builder = Request.Builder().url("$base/auth/session").get()
                if (header != null) builder.header("Cookie", header)
                authClient.newCall(builder.build()).execute().use { response ->
                    SetCookieReply(response.code, response.headers("Set-Cookie"), isCloudflareAccessGate(response.code, response.header("Location"), response.headers.toMultimap()))
                }
            } ?: return ProbeResult.Rejected
        if (reply.gated) return ProbeResult.Gateway
        if (reply.code != 200) return ProbeResult.Rejected
        commit(base, reply.setCookies, cookies)
        return ProbeResult.Accepted
    }

    private suspend fun login(
        base: String,
        password: String,
        cookies: WebSessionCookies,
        target: ServerTarget,
        launchId: Long?,
    ): WebSessionResult {
        val body =
            buildJsonObject {
                put("password", password)
                put("trustDevice", true)
            }.toString()
        val request =
            Request.Builder()
                .url("$base/auth/session")
                .apply { if (target is ServerTarget.Remote) cookies.cookieHeader(base)?.let { header("Cookie", it) } }
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
        val reply =
            fetch<SetCookieReply?>(default = null) {
                authClient.newCall(request).execute().use { response ->
                    SetCookieReply(response.code, response.headers("Set-Cookie"), isCloudflareAccessGate(response.code, response.header("Location"), response.headers.toMultimap()))
                }
            } ?: return WebSessionResult.Unreachable("Login request failed")
        if (reply.gated && target is ServerTarget.Remote) return WebSessionResult.NeedsGatewayLogin(base)
        return when (reply.code) {
            in 200..299 -> {
                // Launch may have changed while the POST was in flight: its cookies must not be stored.
                if (target is ServerTarget.Local && readyLaunchId() != launchId) {
                    WebSessionResult.Unreachable("The local OpenChamber restarted; try again")
                } else {
                    commit(base, reply.setCookies, cookies)
                    WebSessionResult.Ready(base)
                }
            }
            // 401 is a wrong password; a redirect means the server wants us elsewhere. Neither stores cookies.
            401 -> WebSessionResult.NeedsLogin(base)
            in 300..399 -> WebSessionResult.NeedsLogin(base)
            else -> WebSessionResult.Unreachable("Login failed with HTTP ${reply.code}")
        }
    }

    /**
     * Stores the cookies of one accepted response. Checks that the coroutine is still active first, then
     * completes the whole batch (NonCancellable) so a started write is never left half done.
     */
    private suspend fun commit(
        base: String,
        headers: List<String>,
        cookies: WebSessionCookies,
    ) {
        currentCoroutineContext().ensureActive()
        if (headers.isEmpty()) return
        withContext(NonCancellable) { cookies.storeCookies(base, headers) }
    }

    private fun readyLaunchId(): Long? = runCatching { localReady.runningLaunchId() }.getOrNull()

    /** Blocking network work off the main thread. Ordinary failures become [default]; cancellation propagates. */
    private suspend fun <T> fetch(
        default: T,
        block: () -> T,
    ): T =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            default
        }

    private class SetCookieReply(
        val code: Int,
        val setCookies: List<String>,
        val gated: Boolean = false,
    )

    private data class HealthResult(val healthy: Boolean, val gated: Boolean)
    private enum class ProbeResult { Accepted, Rejected, Gateway }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** Pure detector for Cloudflare Access redirects and authentication challenges. */
internal fun isCloudflareAccessGate(code: Int, location: String?, headers: Map<String, List<String>>): Boolean {
    if (code in 300..399 && location != null) {
        val resolved = "https://placeholder.invalid".toHttpUrlOrNull()!!.resolve(location)
        if (resolved?.host?.endsWith(".cloudflareaccess.com", ignoreCase = true) == true || resolved?.encodedPath?.startsWith("/cdn-cgi/access/") == true) return true
    }
    if (code == 401 || code == 403) return headers.any { (name, values) ->
        name.startsWith("cf-access-", ignoreCase = true) || (name.equals("www-authenticate", true) && values.any { it.contains("Cloudflare-Access", true) })
    }
    return false
}

/** https, or http to a loopback host (the password never leaves the device). */
internal fun HttpUrl.isTransportSafe(): Boolean =
    isHttps || host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
