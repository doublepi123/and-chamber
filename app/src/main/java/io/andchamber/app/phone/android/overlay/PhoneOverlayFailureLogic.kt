package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.overlay.progress.PhoneTaskState
import io.andchamber.app.ui.phone.PhoneHaltReason
import io.andchamber.app.ui.phone.PhoneScreenState
import io.andchamber.app.ui.phone.PhoneStopChannel
import io.andchamber.app.ui.phone.canContinueInChat
import io.andchamber.app.ui.phone.overlay.OverlaySurfaceMode

/**
 * A visibility/stop-entry fault, kept separate from a person's take-over or End.
 *
 * These failures must never be reported as `PhoneHaltReason.TakenOver`: the agent was stopped
 * because its stop entry could not be shown, not because the person took control.
 */
internal enum class PhoneOverlayFailure {
    /** A driver read could not restore the window inside its bounded budget. */
    READ_RESTORE_TIMEOUT,

    /** The draw-over permission was revoked while the overlay was selected. */
    PERMISSION_REVOKED,

    /** The foreground display service (and its notification/close entry) went away. */
    DISPLAY_SERVICE_LOST,

    /** The display notification itself could not be shown. */
    NOTIFICATION_LOST,

    /** `addView`, a layout update or a bounded re-show failed. */
    ATTACH_FAILED,

    /** The driver reported the window lost after its own freeze/re-check. */
    VISIBILITY_UNCONFIRMED,
}

/** The native halt reason a failure really is. Never [PhoneHaltReason.TakenOver]. */
internal fun phoneHaltReasonFor(failure: PhoneOverlayFailure): PhoneHaltReason =
    when (failure) {
        PhoneOverlayFailure.PERMISSION_REVOKED,
        PhoneOverlayFailure.DISPLAY_SERVICE_LOST,
        PhoneOverlayFailure.NOTIFICATION_LOST,
        -> PhoneHaltReason.StopChannelLost

        PhoneOverlayFailure.READ_RESTORE_TIMEOUT,
        PhoneOverlayFailure.ATTACH_FAILED,
        PhoneOverlayFailure.VISIBILITY_UNCONFIRMED,
        -> PhoneHaltReason.ScreenUncertain
    }

/** The designer model's reason for the same fixed fact. */
internal fun PhoneOverlayStopReason.toUiStopReason(): io.andchamber.app.ui.phone.overlay.OverlayStopReason =
    when (this) {
        PhoneOverlayStopReason.Expired -> io.andchamber.app.ui.phone.overlay.OverlayStopReason.Expired
        PhoneOverlayStopReason.NativeOpened -> io.andchamber.app.ui.phone.overlay.OverlayStopReason.NativeOpened
        PhoneOverlayStopReason.TargetLeft -> io.andchamber.app.ui.phone.overlay.OverlayStopReason.TargetLeft
        PhoneOverlayStopReason.ScreenLocked -> io.andchamber.app.ui.phone.overlay.OverlayStopReason.ScreenLocked
        PhoneOverlayStopReason.ConnectionLost -> io.andchamber.app.ui.phone.overlay.OverlayStopReason.ConnectionLost
        PhoneOverlayStopReason.RuntimeChanged -> io.andchamber.app.ui.phone.overlay.OverlayStopReason.RuntimeChanged
        PhoneOverlayStopReason.OverlayUnreachable -> io.andchamber.app.ui.phone.overlay.OverlayStopReason.OverlayUnreachable
        PhoneOverlayStopReason.AgentAskedForYou -> io.andchamber.app.ui.phone.overlay.OverlayStopReason.AgentAskedForYou
    }

/**
 * The reason shown beside a stopped task. A retained need_user fact wins over the generic halt
 * reason; an unreachable overlay is always explained as such instead of silently disappearing.
 */
internal fun overlayStopReasonFor(
    halt: PhoneHaltReason?,
    taskState: PhoneTaskState?,
): PhoneOverlayStopReason? {
    // The explicit native stop facts are newer than a progress snapshot. A confirmed NeedUser halt
    // still explains "agent asked for you" while progress is only STOPPING or temporarily missing;
    // an explicit takeover is never relabelled by a stale WAITING_USER projection.
    if (halt == PhoneHaltReason.NeedUser) return PhoneOverlayStopReason.AgentAskedForYou
    if (halt == PhoneHaltReason.TakenOver) return null
    if (taskState == PhoneTaskState.WAITING_USER) return PhoneOverlayStopReason.AgentAskedForYou
    return when (halt) {
        PhoneHaltReason.Expired -> PhoneOverlayStopReason.Expired
        PhoneHaltReason.HostReturned -> PhoneOverlayStopReason.NativeOpened
        PhoneHaltReason.TargetLeft -> PhoneOverlayStopReason.TargetLeft
        PhoneHaltReason.ScreenLocked -> PhoneOverlayStopReason.ScreenLocked
        PhoneHaltReason.ConnectionLost -> PhoneOverlayStopReason.ConnectionLost
        PhoneHaltReason.RuntimeChanged -> PhoneOverlayStopReason.RuntimeChanged
        PhoneHaltReason.StopChannelLost,
        PhoneHaltReason.ScreenUncertain,
        -> PhoneOverlayStopReason.OverlayUnreachable

        PhoneHaltReason.Paused,
        PhoneHaltReason.TakenOver,
        PhoneHaltReason.NeedUser,
        PhoneHaltReason.OutcomeUnknown,
        null,
        -> null
    }
}

/** True only for a native task that is stopped/kept, never for a finished (`ENDED_PENDING_CHECK`) one. */
internal fun overlayTaskIsRetained(state: PhoneTaskState?): Boolean =
    state != null && state != PhoneTaskState.ENDED_PENDING_CHECK

/**
 * The phase to display after a failed stop. A live-looking phase becomes an honest unknown state
 * unless the exact live lease was really revoked (`stopReceipt`); only then may it become
 * `TAKEN_OVER`. A real confirmed stop that is already shown (for example `WAITING_USER`) is
 * preserved and is never rewound to RUNNING. A stale live-looking phase with no lease is unknown,
 * not a confirmed hand-over.
 */
internal fun overlayFailurePhase(
    current: PhoneOverlayPhase,
    retainedTask: Boolean,
    stopReceipt: Boolean,
    hadLiveLease: Boolean,
): PhoneOverlayPhase =
    when {
        !hadLiveLease ->
            if (current in LIVE_PHASES) PhoneOverlayPhase.UNKNOWN else current

        !stopReceipt -> PhoneOverlayPhase.UNKNOWN

        current in LIVE_PHASES ->
            if (retainedTask) PhoneOverlayPhase.UNKNOWN else PhoneOverlayPhase.TAKEN_OVER

        else -> current
    }

private val LIVE_PHASES =
    setOf(
        PhoneOverlayPhase.RUNNING,
        PhoneOverlayPhase.PAUSING,
        PhoneOverlayPhase.PAUSED,
        PhoneOverlayPhase.STOP_PENDING,
    )

/**
 * A failure may try exactly one bounded re-show of the already-stopped state. An attach that just
 * failed is not immediately retried; the tick loop must not hammer `addView`.
 */
internal fun overlayFailureMayShowStopped(failure: PhoneOverlayFailure): Boolean =
    failure != PhoneOverlayFailure.ATTACH_FAILED

/** `lastSurface` may say "moved to the notification" only when that notification really exists. */
internal fun overlayFailureMovesToNotification(
    failure: PhoneOverlayFailure,
    notificationLive: Boolean,
): Boolean =
    notificationLive &&
        failure != PhoneOverlayFailure.NOTIFICATION_LOST &&
        failure != PhoneOverlayFailure.DISPLAY_SERVICE_LOST

/**
 * A display-service instance exists and the native notification stop channel is verified. This says
 * the recovery entry is available; it is deliberately not evidence that the person saw anything.
 */
internal fun verifiedNotificationFallback(
    displayOwner: String?,
    stopChannel: PhoneStopChannel,
): Boolean = displayOwner != null && stopChannel == PhoneStopChannel.Verified

/** A stopped display card that cannot be attached is not retried until a new authority arrives. */
internal fun overlayAttachMayRetry(
    suppressed: Boolean,
    newAuthority: Boolean,
): Boolean = !suppressed || newAuthority

/** Which person entry was requested from the surface. */
internal enum class PhoneOverlayEntry { OpenChat, ContinueInChat, ReturnToNative }

/**
 * "Continue beside the chat" is offered only for a confirmed hand-over phase and only when the
 * native standing/tool/stop facts allow it. It never resumes from the overlay.
 */
internal fun overlayCanContinueInChat(
    screen: PhoneScreenState?,
    phase: PhoneOverlayPhase,
): Boolean =
    screen != null &&
        (phase == PhoneOverlayPhase.TAKEN_OVER ||
            phase == PhoneOverlayPhase.WAITING_USER ||
            phase == PhoneOverlayPhase.PAUSED) &&
        canContinueInChat(screen)

/**
 * Only Return-to-native revokes. Opening the chat (or the native continue confirmation) must not
 * stop, resume or extend a lease; Main's own resume path owns any host-return stop.
 */
internal fun overlayEntryRevokesLease(entry: PhoneOverlayEntry): Boolean =
    entry == PhoneOverlayEntry.ReturnToNative

/**
 * `lastSurface` after an overlay failure. A real display-service notification is the fallback;
 * without one the honest answer is "nothing" rather than claiming a notification nobody can see.
 */
internal fun overlaySurfaceAfterFailure(
    failure: PhoneOverlayFailure,
    notificationLive: Boolean,
): OverlaySurfaceMode? =
    if (overlayFailureMovesToNotification(failure, notificationLive)) {
        OverlaySurfaceMode.MovedToNotification
    } else {
        null
    }
