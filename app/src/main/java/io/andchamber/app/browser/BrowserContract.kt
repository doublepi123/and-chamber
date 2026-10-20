package io.andchamber.app.browser

import kotlinx.coroutines.flow.StateFlow

/**
 * Stable contract between the single visible WebView (UI driver), the in-app controller, and the
 * loopback command server. This package does not own a WebView, a page bridge, or an outbound
 * firewall. Document refs stay in the UI driver; this contract never stores them.
 *
 * Integration wires a credential file under the runtime home
 * (`<runtime>/home/.local/state/andchamber/browser-control.json`). The file is not shared config.
 * Tokens are never placed in a URL, controller state, a log line, or page content.
 */
interface BrowserController {
    val state: StateFlow<BrowserState>

    /**
     * Binds the only driver. A later attach replaces the previous driver and revokes its lease.
     * Must be called from the UI / main dispatcher that will run [BrowserDriver.execute].
     */
    fun attach(driver: BrowserDriver)

    /** Drops the driver and revokes any lease. In-flight work is abandoned as revoked. */
    fun detach()

    /**
     * Reports whether the single WebView is visible to the user. Becoming invisible revokes the
     * lease, abandons in-flight work, closes the listener, and deletes the credential file.
     * Must be called from the UI / main dispatcher.
     */
    fun setVisible(visible: Boolean)

    /**
     * User-initiated enable from the in-app UI. Succeeds only when a driver is attached, the page
     * is visible, the registered listener binds `http://127.0.0.1:<port>/v1/command`, and the
     * credential file is published. A missing listener, a failed bind, or a failed write returns
     * false and leaves no lease. The UI must not start the listener itself.
     */
    suspend fun enableFromUi(): Boolean

    /**
     * The user took the page back. Revokes the lease, abandons in-flight work, closes the listener,
     * and deletes the credential file. Further agent commands are refused until the user enables
     * control again. May suspend. A touch path must call [takeOverFromUi] instead.
     */
    suspend fun takeOver()

    /**
     * Synchronous takeover for the UI thread. Clears the lease, advances generation, cancels the
     * active driver job, and publishes [BrowserPhase.TAKEN_OVER] before returning. Listener and
     * credential cleanup run afterwards and cannot revoke a newer lease. Call only from the main
     * dispatcher. After this returns, the touch should continue to the page.
     */
    fun takeOverFromUi()

    /**
     * Revokes the lease first, then closes the registered listener, then deletes the credential file.
     * The UI must not stop the listener itself.
     */
    suspend fun disable()

    /**
     * Synchronous revoke for one runtime launch. Call from the main dispatcher. Clears the lease
     * only when it was issued for [launchId]. A callback for an older launch does not revoke a
     * lease enabled for a newer launch. Cleanup is asynchronous and generation-owned.
     * [currentRuntimeLaunchId] must stay a pure getter; it must not call this or [disable].
     */
    fun revokeRuntimeLaunch(launchId: Long)
}

/**
 * Loopback listener owned by the controller, not by the UI. [BrowserControllerImpl.attachListener]
 * is the only wiring step. [start] must bind before returning and then call back with the exact
 * credential endpoint `http://127.0.0.1:<port>/v1/command`. [stop] closes that bind.
 */
interface BrowserListener {
    fun start(onBound: (endpoint: String) -> Unit)

    suspend fun stop()
}

/**
 * UI-owned driver. [execute] is invoked on the main dispatcher. It must not run arbitrary script
 * supplied by the caller. A fixed UI-owned template is allowed. It must not put the control token
 * into the page.
 *
 * Document refs belong only to the driver. Drop them whenever [BrowserState.generation] changes.
 * A side-effecting reply of [BrowserErrorCode.OUTCOME_UNKNOWN] means the page may have changed
 * after the controller stopped waiting. The driver must discard that WebView before returning, or
 * refuse every later [execute] until it has been replaced. A read-only [BrowserErrorCode.TIMEOUT]
 * does not require discarding the view. A late callback from an older generation must not touch
 * the replacement view.
 */
fun interface BrowserDriver {
    suspend fun execute(request: BrowserRequest): BrowserReply
}

enum class BrowserMethod {
    NAVIGATE,
    SNAPSHOT,
    CLICK,
    TYPE,
    SCROLL,
    CAPTURE,
}

/**
 * One command. [id] is the caller's idempotency key: a duplicate of an in-flight or completed id
 * is refused and is never retried. [timeoutMs] is the caller's requested budget; the controller
 * clamps it. The deadline includes queueing, not only driver execution.
 */
data class BrowserRequest(
    val id: String,
    val method: BrowserMethod,
    val params: Map<String, String> = emptyMap(),
    val timeoutMs: Long? = null,
)

data class BrowserReply(
    val ok: Boolean,
    val result: Map<String, String> = emptyMap(),
    val error: BrowserError? = null,
)

enum class BrowserErrorCode {
    DISABLED,
    NOT_VISIBLE,
    TAKEN_OVER,
    REVOKED,
    BUSY,
    DUPLICATE,
    TIMEOUT,
    OUTCOME_UNKNOWN,
    INVALID_REQUEST,
    POLICY_DENIED,
    DRIVER,
    INTERNAL,
}

data class BrowserError(
    val code: BrowserErrorCode,
    val message: String,
)

enum class BrowserPhase {
    /** No lease. This is the default. */
    DISABLED,

    /** User enabled control and the page is visible. */
    ENABLED,

    /** User took the page back. Stays until the user enables again or control is disabled. */
    TAKEN_OVER,
}

/**
 * Observable control state. Deliberately has no token, endpoint, or credential material.
 *
 * [generation] advances on enable and on every revoke. The UI drops document refs when it changes.
 * [viewInvalidated] is set by the controller when a side-effecting command ends as
 * [BrowserErrorCode.OUTCOME_UNKNOWN]. The old driver cannot be enabled again until [attach]
 * replaces the view.
 * [leaseExpiresAtEpochMs] is wall-clock display only. Lease admission uses [BrowserClock.elapsedRealtimeMs].
 */
data class BrowserState(
    val phase: BrowserPhase = BrowserPhase.DISABLED,
    val visible: Boolean = false,
    val attached: Boolean = false,
    val busy: Boolean = false,
    val leaseExpiresAtEpochMs: Long? = null,
    val generation: Long = 0,
    val viewInvalidated: Boolean = false,
)

/**
 * Two clocks. Lease expiry and command deadlines use [elapsedRealtimeMs], which does not move
 * backwards when the wall clock changes. [wallEpochMs] is written to the credential file because
 * the guest compares `expiresAt` with its own `Date.now()`. A wall-clock step can make the file
 * expire early or late; the controller still stops accepting commands on the monotonic lease.
 */
data class BrowserClock(
    val elapsedRealtimeMs: () -> Long,
    val wallEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /**
         * Android default: monotonic lease clock plus wall clock for the credential file.
         * Not safe to call in a plain JVM unit test; inject [frozen] or an explicit clock there.
         */
        fun system(): BrowserClock = BrowserClock(
            elapsedRealtimeMs = SystemMonotonicClock::elapsedRealtimeMs,
            wallEpochMs = { System.currentTimeMillis() },
        )

        /** JVM tests and non-Android callers. Both clocks read the same injected value. */
        fun frozen(now: () -> Long): BrowserClock = BrowserClock(
            elapsedRealtimeMs = now,
            wallEpochMs = now,
        )
    }
}
