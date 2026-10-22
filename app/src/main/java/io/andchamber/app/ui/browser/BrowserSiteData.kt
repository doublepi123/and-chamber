package io.andchamber.app.ui.browser

import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import io.andchamber.app.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** What the person sees after "Clear site data". */
internal sealed class SiteDataClearResult {
    /** Cookies and site storage are gone. [reloaded] tells whether the current page loaded again. */
    data class Cleared(val reloaded: Boolean) : SiteDataClearResult()

    /**
     * Cookies and site storage are gone, but the shown page's cache and history could not be cleared.
     * The page is not reloaded, because a reload would rebuild state that was never removed.
     */
    data object PartiallyCleared : SiteDataClearResult()

    /** The browser profile was never created on this device, so there was nothing to clear. */
    data object NothingStored : SiteDataClearResult()

    /** This WebView has no separate profiles, or a step failed. Nothing is reloaded. */
    data object Failed : SiteDataClearResult()
}

/** What clearing the shown page's cache and history did. */
internal enum class PageCacheClear {
    /** No page is shown, so there was no page cache or history to clear. */
    NoPage,

    /** Cache and history are cleared. */
    Cleared,

    /** A page is shown but clearing its cache or history failed. */
    Failed,
}

/** One named WebView profile's site data. Production wraps [androidx.webkit.Profile]. */
internal interface BrowserProfileData {
    val name: String

    /** Removes every cookie in this profile. False when it did not finish in time or failed. */
    suspend fun removeAllCookies(): Boolean

    fun flushCookies()

    /** localStorage, IndexedDB and the other JavaScript storage APIs of this profile. */
    fun deleteAllStorage()
}

/** Everything the clearer touches, injected so the order can be checked without a WebView. */
internal interface BrowserSiteDataOps {
    /** Turns agent control off before anything is cleared. */
    fun revokeAgent(reason: String)

    fun profilesSupported(): Boolean

    /** The existing profile, or null when it was never created. Must not create or delete one. */
    fun existingProfile(name: String): BrowserProfileData?

    /** Clears the current page's HTTP cache and back/forward list. */
    fun clearCurrentPage(): PageCacheClear

    /** Loads the current page again as a person's own navigation, never as an agent load. */
    fun reloadByHand(): Boolean
}

/**
 * Clears the floating browser's site data. All its pages share the one profile
 * [BrowserDriverLogic.PROFILE_NAME]; this never names another profile, so the OpenChamber console's
 * own profiles are left alone. The profile is not deleted or recreated, because live pages use it.
 *
 * Order: revoke the agent grant, then cookies (and flush), then site storage, then the current
 * page's cache and history, then a manual reload.
 */
internal class BrowserSiteDataClearer(private val ops: BrowserSiteDataOps) {
    suspend fun clear(): SiteDataClearResult {
        ops.revokeAgent(REVOKE_REASON)
        if (!ops.profilesSupported()) return SiteDataClearResult.Failed
        val profile = try {
            ops.existingProfile(BrowserDriverLogic.PROFILE_NAME)
        } catch (_: RuntimeException) {
            return SiteDataClearResult.Failed
        } ?: return SiteDataClearResult.NothingStored
        // A different profile here would mean clearing someone else's data: refuse.
        if (profile.name != BrowserDriverLogic.PROFILE_NAME) return SiteDataClearResult.Failed
        try {
            if (!profile.removeAllCookies()) return SiteDataClearResult.Failed
            profile.flushCookies()
            profile.deleteAllStorage()
        } catch (_: RuntimeException) {
            return SiteDataClearResult.Failed
        }
        val page = try {
            ops.clearCurrentPage()
        } catch (_: RuntimeException) {
            PageCacheClear.Failed
        }
        if (page == PageCacheClear.Failed) return SiteDataClearResult.PartiallyCleared
        val reloaded = page == PageCacheClear.Cleared && try {
            ops.reloadByHand()
        } catch (_: RuntimeException) {
            false
        }
        return SiteDataClearResult.Cleared(reloaded)
    }

    companion object {
        const val REVOKE_REASON = "site-data-cleared"
    }
}

internal fun browserSiteDataResultResource(result: SiteDataClearResult): Int = when (result) {
    is SiteDataClearResult.Cleared ->
        if (result.reloaded) R.string.browser_site_data_cleared else R.string.browser_site_data_cleared_no_reload
    SiteDataClearResult.PartiallyCleared -> R.string.browser_site_data_partial
    SiteDataClearResult.NothingStored -> R.string.browser_site_data_cleared
    SiteDataClearResult.Failed -> R.string.browser_site_data_failed
}

/** Production ops. Main thread only (WebView and ProfileStore APIs). */
internal class PresenterSiteDataOps(private val presenter: NativeBrowserPresenter) : BrowserSiteDataOps {
    override fun revokeAgent(reason: String) = presenter.revokeAll(reason)

    // Same check BrowserSession uses before it creates any page.
    override fun profilesSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    override fun existingProfile(name: String): BrowserProfileData? =
        ProfileStore.getInstance().getProfile(name)?.let { WebkitProfileData(it) }

    override fun clearCurrentPage(): PageCacheClear {
        val view = presenter.currentView() as? WebView ?: return PageCacheClear.NoPage
        try {
            // The disk cache belongs to the view's profile, so this does not reach the console.
            view.clearCache(true)
            view.clearHistory()
        } catch (_: RuntimeException) {
            return PageCacheClear.Failed
        }
        return PageCacheClear.Cleared
    }

    override fun reloadByHand(): Boolean {
        val view = presenter.currentView() as? WebView ?: return false
        // Takeover puts the page in manual mode, so the reload is a person's navigation.
        presenter.takeover()
        if (presenter.currentView() !== view) return false
        view.reload()
        return true
    }
}

private class WebkitProfileData(private val profile: Profile) : BrowserProfileData {
    override val name: String get() = profile.name

    override suspend fun removeAllCookies(): Boolean = withTimeoutOrNull(COOKIE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            profile.cookieManager.removeAllCookies { if (continuation.isActive) continuation.resume(true) }
        }
    } ?: false

    override fun flushCookies() = profile.cookieManager.flush()

    override fun deleteAllStorage() = profile.webStorage.deleteAllData()

    private companion object {
        const val COOKIE_TIMEOUT_MS = 5_000L
    }
}
