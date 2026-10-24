package io.andchamber.app.data

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
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
 * Can also clear one target's own named profile when that server is removed. The identity stays
 * frozen after a successful removal, so a stale page cannot recreate its data.
 *
 * Call from the main thread (WebView API).
 */
class WebSessionProfiles(
    removalStore: RemovableWebProfileStore = PlatformRemovableWebProfileStore(),
) {
    private val bindings = HashMap<WebSessionIdentity, WebProfileBinding>()
    private val removal = WebProfileRemovalCoordinator(removalStore)

    @Synchronized
    fun acquire(target: ServerTarget): WebProfileBinding {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            throw WebIsolationException(WebIsolationFailure.UnsupportedWebView)
        }
        val origin = canonicalWebOrigin(target.baseUrl) ?: throw WebIsolationException(WebIsolationFailure.InvalidServerAddress)
        val identity = WebSessionIdentity(target.id, origin)
        // A removal freezes the identity before it clears anything; no new page may join it.
        if (removal.isUnavailable(identity)) throw WebIsolationException(WebIsolationFailure.ProfileBindingFailed)
        return bindings.getOrPut(identity) { WebProfileBinding(identity, profileNameFor(identity), removal.gateFor(identity)) }
    }

    /**
     * Clears the named profile of [target] at its current canonical origin: retires the identity,
     * waits for cookie batches that had already started, runs [beforeClear] (the caller discards
     * its pooled WebView there), then removes that profile's cookies and WebStorage.
     *
     * Only [WebProfileRemovalResult.Removed] and [WebProfileRemovalResult.NothingStored] mean the
     * caller may delete the saved server. Any [WebProfileRemovalResult.Failed] leaves it in place and
     * reopens the identity for another attempt. Cancellation reopens the identity too.
     */
    suspend fun removeProfile(
        target: ServerTarget,
        beforeClear: suspend () -> Unit = {},
    ): WebProfileRemovalResult {
        val identity = profileRemovalIdentity(target)
            ?: return WebProfileRemovalResult.Failed(WebProfileRemovalFailure.InvalidServerAddress)
        // Drop the current capability first; the gate is what stops writes from old holders.
        synchronized(this) { bindings.remove(identity) }
        return removal.remove(identity, beforeClear)
    }
}

/** One profile, bound to exactly one identity. Call from the main thread. */
class WebProfileBinding internal constructor(
    val identity: WebSessionIdentity,
    val profileName: String,
    private val gate: WebProfileWriteGate,
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
        // The identity may have been frozen for removal after this binding was handed out.
        if (gate.isRetired) {
            view.destroy()
            throw WebIsolationException(WebIsolationFailure.ProfileBindingFailed)
        }
        try {
            WebViewCompat.setProfile(view, profileName)
            val profile = WebViewCompat.getProfile(view)
            check(profile.name == profileName) { "WebView bound to an unexpected profile" }
            val manager = profile.cookieManager
            manager.setAcceptCookie(true)
            manager.setAcceptThirdPartyCookies(view, false)
            return PlatformWebSessionCookies(identity, manager, gate)
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
    private val gate: WebProfileWriteGate,
) : WebSessionCookies {
    override suspend fun cookieHeader(url: String): String? {
        if (gate.isRetired) return null
        if (canonicalWebOrigin(url) != identity.origin) return null
        return withContext(Dispatchers.IO) { manager.getCookie(url) }
    }

    override suspend fun storeCookies(
        url: String,
        headers: List<String>,
    ) {
        require(canonicalWebOrigin(url) == identity.origin) { "Cookies are only stored for the bound origin" }
        if (headers.isEmpty()) return
        // The gate refuses a batch once removal froze this identity; a batch that already started
        // finishes before the gate lets the removal proceed. Once a batch has started it must finish:
        // a cancelled caller must not leave a half-written login.
        gate.write {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main.immediate) {
                    for (header in headers) setOne(url, header)
                    @Suppress("DEPRECATION")
                    manager.flush()
                }
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

/** Production profile lookup. Main thread only, like all ProfileStore/WebView APIs. */
private class PlatformRemovableWebProfileStore : RemovableWebProfileStore {
    override fun profilesSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    override fun existingProfile(name: String): RemovableWebProfile? {
        val store = ProfileStore.getInstance()
        // getProfile() throws for a name that was never created. The name list is the reliable
        // "does not exist" signal; a profile that is listed but cannot be read stays a failure.
        if (name !in store.allProfileNames) return null
        val profile = store.getProfile(name)
            ?: throw IllegalStateException("WebView profile '$name' is listed but cannot be read")
        return PlatformRemovableWebProfile(profile)
    }
}

/**
 * One profile's data. `removeAllCookies` resumes on the platform's completion callback regardless of
 * its Boolean (which only says whether a cookie was there); a callback that never arrives makes the
 * coordinator's timeout fail the removal, so the server entry is kept.
 */
private class PlatformRemovableWebProfile(private val profile: Profile) : RemovableWebProfile {
    override val name: String get() = profile.name

    override suspend fun removeAllCookies(): Boolean =
        suspendCancellableCoroutine { continuation ->
            profile.cookieManager.removeAllCookies {
                if (continuation.isActive) continuation.resume(true)
            }
        }

    override fun flushCookies() = profile.cookieManager.flush()

    override fun deleteAllStorage() = profile.webStorage.deleteAllData()
}
