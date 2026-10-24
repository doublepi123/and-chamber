package io.andchamber.app.data

import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Why a named WebView profile's data was not cleared. The server entry must be kept in every case. */
enum class WebProfileRemovalFailure {
    /** MULTI_PROFILE is missing, so no named profile can be addressed. This is not "no data". */
    UnsupportedWebView,

    /** The target's canonical origin cannot be derived, so no profile can be named. Not "no data". */
    InvalidServerAddress,

    /** Another removal of the same identity is already running. */
    AlreadyRemoving,

    /** The cookie removal did not finish (callback missing, failure, or timeout). */
    CookiesNotCleared,

    /** Cookies were removed but the profile's WebStorage could not be cleared. */
    StorageNotCleared,

    /** Something unexpected went wrong while looking the profile up or before clearing. */
    Unexpected,
}

sealed interface WebProfileRemovalResult {
    /** The named profile's cookies and WebStorage are cleared. */
    data object Removed : WebProfileRemovalResult

    /** No profile with that name was ever created on this device, so there was nothing to clear. */
    data object NothingStored : WebProfileRemovalResult

    data class Failed(val failure: WebProfileRemovalFailure) : WebProfileRemovalResult
}

/**
 * One existing named profile. Production wraps [androidx.webkit.Profile]; tests use fakes.
 *
 * [removeAllCookies] returning true means the platform's completion callback arrived; its own
 * `Boolean` argument only says whether a cookie was removed, so it must not be read as success.
 * False means the removal did not finish (for example its timeout elapsed).
 */
interface RemovableWebProfile {
    val name: String

    suspend fun removeAllCookies(): Boolean

    fun flushCookies()

    /** localStorage, IndexedDB and the other JavaScript storage APIs of this profile. */
    fun deleteAllStorage()
}

/** Looks up existing profiles by name. Never creates a profile and never falls back to Default. */
interface RemovableWebProfileStore {
    fun profilesSupported(): Boolean

    /** The existing profile, or null when it was never created on this device. */
    fun existingProfile(name: String): RemovableWebProfile?
}

/**
 * The write/open barrier of one profile. Removal retires the gate first: later opens and cookie
 * batches are refused, and [retire] then waits for a batch that had already started (including the
 * NonCancellable part of [io.andchamber.app.runtime.WebSessionCookies.storeCookies]) to finish.
 * Destroying a WebView or discarding the pool does not replace this wait.
 */
class WebProfileWriteGate {
    private val writeLock = Mutex()

    @Volatile
    var isRetired: Boolean = false
        private set

    /** Runs one cookie batch. Refuses to start after the gate was retired; a started batch finishes. */
    suspend fun <T> write(block: suspend () -> T): T =
        writeLock.withLock {
            check(!isRetired) { "This WebView profile is being removed" }
            block()
        }

    /** Freezes the gate, then waits for a batch that had already started. */
    suspend fun retire() {
        isRetired = true
        writeLock.withLock { }
    }

    /** Reopens the gate after a removal that did not commit. Never call this after a clear succeeded. */
    fun reopen() {
        isRetired = false
    }
}

/**
 * Clears one target's own named profile: cookies first, then WebStorage. The profile is looked up
 * by [profileNameFor] of the identity, so only that profile is touched; Default and the independent
 * browser profile are never named.
 *
 * The gate is retired before anything is cleared and stays retired after success, so a stale
 * WebView cannot recreate the data of a removed server. Any failure, timeout or cancellation reopens
 * it; the caller's server entry must then stay in place. A cancellation rethrows
 * [CancellationException] after reopening, so a destroyed composition cannot lock the singleton.
 */
class WebProfileRemovalCoordinator(
    private val store: RemovableWebProfileStore,
    private val timeoutMillis: Long = COOKIE_CLEAR_TIMEOUT_MS,
) {
    private val gates = ConcurrentHashMap<WebSessionIdentity, WebProfileWriteGate>()
    private val removing = ConcurrentHashMap.newKeySet<WebSessionIdentity>()

    /** The gate that guards writes and opens of [identity], created on first use. */
    fun gateFor(identity: WebSessionIdentity): WebProfileWriteGate =
        gates.computeIfAbsent(identity) { WebProfileWriteGate() }

    /** True while [identity] is being removed, or permanently after a successful removal. */
    fun isUnavailable(identity: WebSessionIdentity): Boolean = gateFor(identity).isRetired

    /**
     * Freezes [identity], waits for started cookie writes, runs [beforeClear] (the caller discards
     * its pooled WebView there), then clears the named profile. The caller removes the saved server
     * only after [WebProfileRemovalResult.Removed] or [WebProfileRemovalResult.NothingStored].
     */
    suspend fun remove(
        identity: WebSessionIdentity,
        beforeClear: suspend () -> Unit = {},
    ): WebProfileRemovalResult {
        if (!removing.add(identity)) return WebProfileRemovalResult.Failed(WebProfileRemovalFailure.AlreadyRemoving)
        val gate = gateFor(identity)
        try {
            if (!store.profilesSupported()) {
                return WebProfileRemovalResult.Failed(WebProfileRemovalFailure.UnsupportedWebView)
            }
            gate.retire()
            val outcome =
                try {
                    clearFrozen(identity, beforeClear)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    WebProfileRemovalResult.Failed(WebProfileRemovalFailure.Unexpected)
                }
            // Nothing was committed (the caller keeps the server entry), so open the gate again.
            if (outcome is WebProfileRemovalResult.Failed) gate.reopen()
            return outcome
        } catch (e: CancellationException) {
            // The removal was not committed. Reopen in a context that cannot be cancelled again, so a
            // destroyed composition cannot leave the app-wide singleton frozen forever.
            withContext(NonCancellable) { gate.reopen() }
            throw e
        } catch (_: Exception) {
            gate.reopen()
            return WebProfileRemovalResult.Failed(WebProfileRemovalFailure.Unexpected)
        } finally {
            removing.remove(identity)
        }
    }

    private suspend fun clearFrozen(
        identity: WebSessionIdentity,
        beforeClear: suspend () -> Unit,
    ): WebProfileRemovalResult {
        beforeClear()

        val profileName = profileNameFor(identity)
        val profile =
            try {
                store.existingProfile(profileName)
            } catch (_: Exception) {
                return WebProfileRemovalResult.Failed(WebProfileRemovalFailure.Unexpected)
            } ?: return WebProfileRemovalResult.NothingStored
        // A different profile here would mean clearing someone else's data: refuse.
        if (profile.name != profileName) return WebProfileRemovalResult.Failed(WebProfileRemovalFailure.Unexpected)

        val cleared =
            try {
                withTimeoutOrNull(timeoutMillis) { profile.removeAllCookies() } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        if (!cleared) return WebProfileRemovalResult.Failed(WebProfileRemovalFailure.CookiesNotCleared)
        try {
            profile.flushCookies()
        } catch (_: Exception) {
            return WebProfileRemovalResult.Failed(WebProfileRemovalFailure.CookiesNotCleared)
        }
        try {
            profile.deleteAllStorage()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return WebProfileRemovalResult.Failed(WebProfileRemovalFailure.StorageNotCleared)
        }
        return WebProfileRemovalResult.Removed
    }

    companion object {
        const val COOKIE_CLEAR_TIMEOUT_MS = 5_000L
    }
}

/**
 * The identity a removal names: the target id plus the canonical origin of the address it has
 * right now. Null when no profile could be named (an address that is not plain http(s), or one with
 * userinfo); that is a failure, never "no data". A server that was re-pointed only clears the
 * current origin; earlier origins cannot be enumerated and are not claimed.
 */
internal fun profileRemovalIdentity(target: ServerTarget): WebSessionIdentity? =
    canonicalWebOrigin(target.baseUrl)?.let { WebSessionIdentity(target.id, it) }
