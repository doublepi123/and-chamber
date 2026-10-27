package io.andchamber.app.phone.standing

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneErrorCode
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey

/** One preauthorized app, for the model's app picker. Never the device's installed-app catalogue. */
data class PhoneStandingApp(
    val label: String,
    val packageName: String,
)

/** Read-only native standing projection: only the person-confirmed scope. */
data class PhoneStandingView(
    val apps: List<PhoneStandingApp>,
    val actions: Set<PhoneActionKind>,
    val windowEndsAtMs: Long,
)

enum class PhoneTaskStartState {
    RUNNING,
    READY,

    /** The native side explicitly needs the person before the task can continue. */
    NEEDS_USER,
}

/** Fixed startup phases only; never carries page text, packages or raw exception messages. */
enum class PhoneTaskStartFailureStage { AUTHORITY, STOP_ENTRY, TASK_BINDING, TASK_RECORD, TARGET_LAUNCH, OVERLAY, OBSERVATION }

/**
 * Typed result of one chat request. The model receives [Started.epoch]/[Started.state] (and a fixed
 * bounded message); [Started.key] and the native record stay native. There is no PENDING state: the
 * native starter has already checked the standing scope, created the per-task grant and launched the
 * allowed app, or it refused.
 */
sealed interface PhoneTaskStartResult {
    data class Started(
        val epoch: Long,
        val key: PhoneTaskKey,
        val state: PhoneTaskStartState,
        val message: String? = null,
    ) : PhoneTaskStartResult

    data class Refused(
        val refusal: PhoneStandingRefusal,
        val stage: PhoneTaskStartFailureStage? = null,
        val error: PhoneErrorCode? = null,
    ) : PhoneTaskStartResult
}

sealed interface PhoneOpenAppResult {
    data object Opened : PhoneOpenAppResult

    data class Refused(val refusal: PhoneStandingRefusal) : PhoneOpenAppResult
}

/**
 * Native port for the chat's direct task request and app picker inside the standing scope.
 *
 * The tool server never grants, launches or reads the installed-app list: it forwards the verified
 * V2 session + bounded summary/package to this port. The native implementation checks the current
 * standing scope and the controller's epoch/owner, then starts the task and launches the allowed
 * app. The default fails closed. This is a native seam, not authority: the controller and the native
 * person confirmation remain authoritative.
 */
interface PhoneTaskStarter {
    /** False when no native standing backend is installed. */
    val available: Boolean get() = false

    /** Only the person-confirmed apps; null when no standing scope is active. */
    fun standing(): PhoneStandingView? = null

    suspend fun request(
        sessionId: String,
        summary: String,
        packageName: String,
    ): PhoneTaskStartResult = PhoneTaskStartResult.Refused(PhoneStandingRefusal.NOT_CONFIGURED)

    suspend fun openApp(
        sessionId: String,
        packageName: String,
    ): PhoneOpenAppResult = PhoneOpenAppResult.Refused(PhoneStandingRefusal.NOT_CONFIGURED)

    /**
     * Native-only rollback for a start this adapter already performed but cannot keep: the tool had
     * to refuse the direct request (a stale epoch or an unpublished route), so the exact lease it
     * just created must not stay live.
     *
     * [epoch] and [key] are exactly the values of the [PhoneTaskStartResult.Started] being undone;
     * the implementation must release only that task, never an older or newer epoch, and must not
     * grant, launch or publish anything. Returning false means the rollback was not confirmed, so
     * the caller still answers failure and never claims success.
     */
    suspend fun abortStarted(
        epoch: Long,
        key: PhoneTaskKey,
    ): Boolean

    /** The default is a closed gate: no configured standing backend, no apps, no starts, no launches. */
    object Unavailable : PhoneTaskStarter {
        override val available: Boolean = false

        override fun standing(): PhoneStandingView? = null

        override suspend fun request(
            sessionId: String,
            summary: String,
            packageName: String,
        ): PhoneTaskStartResult = PhoneTaskStartResult.Refused(PhoneStandingRefusal.NOT_CONFIGURED)

        override suspend fun openApp(
            sessionId: String,
            packageName: String,
        ): PhoneOpenAppResult = PhoneOpenAppResult.Refused(PhoneStandingRefusal.NOT_CONFIGURED)

        override suspend fun abortStarted(
            epoch: Long,
            key: PhoneTaskKey,
        ): Boolean = false
    }
}
