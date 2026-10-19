package io.andchamber.app.data

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionCookies
import io.andchamber.app.runtime.WebSessionIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Why a page cannot be shown in an isolated profile. Every case fails closed. */
enum class WebIsolationFailure {
    UnsupportedWebView,
    InvalidServerAddress,
    ProfileBindingFailed,
}

class WebIsolationException(
    val failure: WebIsolationFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.name, cause)

/**
 * Hands out one profile binding per (target, canonical origin). Nothing falls back to the default
 * profile or to the global CookieManager: if MULTI_PROFILE is missing or a binding fails, acquire or
 * bindFreshWebView throws [WebIsolationException].
 *
 * Call from the main thread (WebView API).
 */
class WebSessionProfiles {
    private val bindings = HashMap<WebSessionIdentity, WebProfileBinding>()

    @Synchronized
    fun acquire(target: ServerTarget): WebProfileBinding {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            throw WebIsolationException(WebIsolationFailure.UnsupportedWebView)
        }
        val origin = canonicalWebOrigin(target.baseUrl) ?: throw WebIsolationException(WebIsolationFailure.InvalidServerAddress)
        val identity = WebSessionIdentity(target.id, origin)
        return bindings.getOrPut(identity) { WebProfileBinding(identity, profileNameFor(identity)) }
    }
}

/** One profile, bound to exactly one identity. Call from the main thread. */
class WebProfileBinding internal constructor(
    val identity: WebSessionIdentity,
    val profileName: String,
) {
    /**
     * Binds a brand-new WebView to this profile. Must run before any other WebView operation on [view]
     * (settings, JS, navigation). On failure the view is destroyed and [WebIsolationException] is thrown;
     * the caller must not use the view and must not fall back to another profile.
     */
    internal fun bindFreshWebView(view: WebView): WebSessionCookies {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            view.destroy()
            throw WebIsolationException(WebIsolationFailure.UnsupportedWebView)
        }
        try {
            WebViewCompat.setProfile(view, profileName)
            val profile = WebViewCompat.getProfile(view)
            check(profile.name == profileName) { "WebView bound to an unexpected profile" }
            val manager = profile.cookieManager
            manager.setAcceptCookie(true)
            manager.setAcceptThirdPartyCookies(view, false)
            return PlatformWebSessionCookies(identity, manager)
        } catch (error: Exception) {
            view.destroy()
            throw WebIsolationException(WebIsolationFailure.ProfileBindingFailed, error)
        }
    }
}

/** Cookie capability backed by one profile's CookieManager. Never touches the global manager. */
private class PlatformWebSessionCookies(
    override val identity: WebSessionIdentity,
    private val manager: CookieManager,
) : WebSessionCookies {
    override suspend fun cookieHeader(url: String): String? {
        if (canonicalWebOrigin(url) != identity.origin) return null
        return withContext(Dispatchers.IO) { manager.getCookie(url) }
    }

    override suspend fun storeCookies(
        url: String,
        headers: List<String>,
    ) {
        require(canonicalWebOrigin(url) == identity.origin) { "Cookies are only stored for the bound origin" }
        if (headers.isEmpty()) return
        // Once a batch has started it must finish: a cancelled caller must not leave a half-written login.
        withContext(NonCancellable) {
            withContext(Dispatchers.Main.immediate) {
                for (header in headers) setOne(url, header)
                @Suppress("DEPRECATION")
                manager.flush()
            }
        }
    }

    private suspend fun setOne(
        url: String,
        header: String,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        manager.setCookie(url, header) { accepted ->
            if (accepted == true) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWithException(IllegalStateException("The WebView rejected a session cookie"))
            }
        }
    }
}
