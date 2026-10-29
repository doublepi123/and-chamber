package io.andchamber.app.phone.android

import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.standing.PhoneOpenAppResult
import io.andchamber.app.phone.standing.PhoneStandingView
import io.andchamber.app.phone.standing.PhoneTaskContinuationStatus
import io.andchamber.app.phone.standing.PhoneTaskStartResult
import io.andchamber.app.phone.standing.PhoneTaskStarter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Lazy native adapter for the tool lane. The integration is resolved only when a call arrives, so
 * DI can build `PhoneToolBinding` before `PhoneIntegration` without a cycle.
 *
 * Every suspending tool call is serialized on the main dispatcher because all integration state is
 * main-thread owned. `standing()` is the one non-suspend read: the integration publishes that
 * projection through a volatile snapshot. The default `Unavailable` in the interface still fails
 * closed when this adapter is not installed.
 */
internal class PhoneIntegrationTaskStarter(
    private val owner: () -> PhoneIntegration,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : PhoneTaskStarter {
    override val available: Boolean = true

    override fun standing(): PhoneStandingView? = owner().standingViewSnapshot()

    /** Same lazy provider pattern: the native integration publishes a volatile redacted snapshot. */
    override fun continuation(): PhoneTaskContinuationStatus? = owner().continuationViewSnapshot()

    override suspend fun request(
        sessionId: String,
        summary: String,
        packageName: String,
    ): PhoneTaskStartResult =
        withContext(dispatcher) {
            owner().startStandingTask(sessionId, summary, packageName)
        }

    override suspend fun openApp(
        sessionId: String,
        packageName: String,
    ): PhoneOpenAppResult =
        withContext(dispatcher) {
            owner().openStandingApp(sessionId, packageName)
        }

    override suspend fun abortStarted(
        epoch: Long,
        key: PhoneTaskKey,
    ): Boolean =
        // Rollback must still run when the tool caller was cancelled after a failed publish.
        withContext(dispatcher + NonCancellable) {
            owner().abortStandingTask(epoch, key)
        }

    /** Re-arm at most one bounded silent reconnect; the core already revoked this connection. */
    override suspend fun onReadTimeout() =
        withContext(dispatcher) {
            owner().onReadTimeoutDisconnected()
        }
}
