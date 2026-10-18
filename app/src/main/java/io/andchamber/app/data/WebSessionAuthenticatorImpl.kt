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
 * Prepares a session for the WebView: checks the server is reachable, logs in with the known UI
 * password (POST /auth/session), and copies every Set-Cookie into the WebView cookie jar.
 *
 * Cookie names are port-suffixed by OpenChamber (oc_ui_session_<port>), so the header is passed to
 * the WebView unchanged; the WebView will send it back to the same host:port.
 */
class DefaultWebSessionAuthenticator(
    private val client: OkHttpClient,
    private val cookies: WebCookieSink,
    private val localPassword: () -> String?,
    private val remoteStoredPassword: (ServerTarget.Remote) -> String? = { it.uiPassword },
) : WebSessionAuthenticator {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun prepare(target: ServerTarget): WebSessionResult =
        withContext(Dispatchers.IO) {
            val base = target.baseUrl.trimEnd('/')
            if (base.toHttpUrlOrNull() == null) {
                return@withContext WebSessionResult.Unreachable("Invalid server address: ${target.baseUrl}")
            }
            if (!isHealthy(base)) {
                return@withContext WebSessionResult.Unreachable("No OpenChamber server answered at $base")
            }

            val password =
                when (target) {
                    is ServerTarget.Local -> localPassword()
                    is ServerTarget.Remote -> remoteStoredPassword(target)
                }

            if (password.isNullOrEmpty()) {
                // Without a stored password, a server that is already signed in (e.g. after a
                // previous login on this device) still counts as Ready.
                return@withContext if (isAuthenticated(base)) {
                    WebSessionResult.Ready(base)
                } else {
                    WebSessionResult.NeedsLogin(base)
                }
            }

            login(base, password)
        }

    private fun isHealthy(base: String): Boolean =
        runCatching {
            val request = Request.Builder().url("$base/health").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body?.string().orEmpty()
                json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content == "ok"
            }
        }.getOrDefault(false)

    private fun isAuthenticated(base: String): Boolean =
        runCatching {
            val request = Request.Builder().url("$base/auth/session").get().build()
            client.newCall(request).execute().use { response ->
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
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> {
                        storeCookies(base, response.headers("Set-Cookie"))
                        WebSessionResult.Ready(base)
                    }
                    401 -> WebSessionResult.NeedsLogin(base)
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
