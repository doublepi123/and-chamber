package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneErrorCode
import io.andchamber.app.phone.core.PhoneStandingBlockReason
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.standing.PhoneStandingRefusal
import io.andchamber.app.phone.standing.PhoneTaskContinuationFailure
import io.andchamber.app.phone.standing.PhoneTaskContinuationState
import io.andchamber.app.phone.standing.PhoneTaskStartFailureStage
import io.andchamber.app.ui.phone.PhoneContinueDelivery
import io.andchamber.app.ui.phone.PhoneContinueFailure
import io.andchamber.app.ui.phone.PhoneHaltReason

/** A person confirmation may start only from Idle or a previous NotResumed technical failure. */
internal fun continuationMayStart(
    delivery: PhoneContinueDelivery,
    inFlight: Boolean,
): Boolean =
    !inFlight &&
        (delivery == PhoneContinueDelivery.Idle || delivery == PhoneContinueDelivery.NotResumed)

/**
 * True only when the controller already stopped this exact continuation observe because it saw a
 * read-only connection failure, so rolling back must not revoke anything again. The grant is gone,
 * the runtime is the same, and the retained task/key/progress epoch still belong to this attempt.
 * Any newer owner, halt or progress epoch makes it false.
 */
internal fun continuationCoreStoppedExact(
    typedError: PhoneErrorCode?,
    liveEpoch: Long?,
    runtimeId: String?,
    attemptEpoch: Long,
    key: PhoneTaskKey?,
    retainedEpoch: Long?,
    identityMatchesKey: Boolean,
    haltStillSame: Boolean,
    progressKeyMatches: Boolean,
    progressEpoch: Long?,
): Boolean =
    (typedError == PhoneErrorCode.READ_TIMEOUT || typedError == PhoneErrorCode.NOT_CONNECTED) &&
        liveEpoch == null &&
        key != null &&
        runtimeId == key.runtimeId &&
        retainedEpoch == attemptEpoch &&
        identityMatchesKey &&
        haltStillSame &&
        progressKeyMatches &&
        progressEpoch == attemptEpoch

/** A failure before access came back is always NotResumed; it is never the one-shot wake Failed. */
internal fun continuationPreWakeDelivery(): PhoneContinueDelivery = PhoneContinueDelivery.NotResumed

/** Once the wake was attempted, only Delivered/Failed are possible; it is never NotResumed. */
internal fun continuationWakeDelivery(delivered: Boolean): PhoneContinueDelivery =
    if (delivered) PhoneContinueDelivery.Delivered else PhoneContinueDelivery.Failed

/** The original stop reason the chat must keep waiting on. Never widens authority. */
internal fun continuationStandingBlockReason(reason: PhoneHaltReason): PhoneStandingBlockReason =
    when (reason) {
        PhoneHaltReason.NeedUser -> PhoneStandingBlockReason.NEED_USER
        PhoneHaltReason.TakenOver -> PhoneStandingBlockReason.TAKEOVER
        PhoneHaltReason.OutcomeUnknown,
        PhoneHaltReason.ScreenUncertain,
        -> PhoneStandingBlockReason.UNKNOWN_OUTCOME
        PhoneHaltReason.ConnectionLost,
        PhoneHaltReason.StopChannelLost,
        -> PhoneStandingBlockReason.CONNECTION_LOST
        PhoneHaltReason.ScreenLocked -> PhoneStandingBlockReason.DEVICE_LOCKED
        PhoneHaltReason.RuntimeChanged -> PhoneStandingBlockReason.RUNTIME_CHANGED
        PhoneHaltReason.Paused,
        PhoneHaltReason.Expired,
        PhoneHaltReason.HostReturned,
        PhoneHaltReason.TargetLeft,
        -> PhoneStandingBlockReason.UNKNOWN_OUTCOME
    }

/**
 * Fixed mapping from the native continuation failure facts to the screen's bounded failure reason.
 * It never carries page, package or model text. This is display input only; it does not grant,
 * resume, retry or widen any block.
 */
internal fun continuationFailureFor(
    stage: PhoneTaskStartFailureStage?,
    error: PhoneErrorCode?,
    refusal: PhoneStandingRefusal? = null,
    cancelled: Boolean = false,
): PhoneContinueFailure {
    if (cancelled || error == PhoneErrorCode.CANCELLED) return PhoneContinueFailure.Cancelled
    if (refusal != null) {
        return when (refusal) {
            PhoneStandingRefusal.NOT_CONFIGURED,
            PhoneStandingRefusal.INVALID,
            PhoneStandingRefusal.EXPIRED,
            -> PhoneContinueFailure.ScopeUnavailable
            PhoneStandingRefusal.TARGET_NOT_ALLOWED -> PhoneContinueFailure.TargetUnavailable
            // Disabled, not connected, runtime changed, standing blocked, paused, unknown outcome,
            // active task, session mismatch: not a scope the person can resume right now. This is
            // still a fixed reason; the original halt/unknown guard remains in force.
            else -> PhoneContinueFailure.NotReady
        }
    }
    return when (stage) {
        PhoneTaskStartFailureStage.STOP_ENTRY,
        PhoneTaskStartFailureStage.OVERLAY,
        -> PhoneContinueFailure.StopChannelUnavailable
        PhoneTaskStartFailureStage.TARGET_LAUNCH -> PhoneContinueFailure.TargetUnavailable
        PhoneTaskStartFailureStage.AUTHORITY -> PhoneContinueFailure.Cancelled
        PhoneTaskStartFailureStage.TASK_BINDING,
        PhoneTaskStartFailureStage.TASK_RECORD,
        -> PhoneContinueFailure.Other
        PhoneTaskStartFailureStage.OBSERVATION, null -> observationFailureFor(error)
    }
}

private fun observationFailureFor(error: PhoneErrorCode?): PhoneContinueFailure =
    when (error) {
        PhoneErrorCode.HIERARCHY_NOT_IDLE -> PhoneContinueFailure.ScreenNotIdle
        PhoneErrorCode.READ_TIMEOUT,
        PhoneErrorCode.DEADLINE,
        -> PhoneContinueFailure.ReadTimeout
        PhoneErrorCode.WINDOW_CHANGED,
        PhoneErrorCode.GEOMETRY_CHANGED,
        PhoneErrorCode.TARGET_MISMATCH,
        -> PhoneContinueFailure.WindowUnstable
        PhoneErrorCode.NOT_CONNECTED -> PhoneContinueFailure.ConnectionLost
        PhoneErrorCode.CANCELLED -> PhoneContinueFailure.Cancelled
        PhoneErrorCode.PAUSED,
        PhoneErrorCode.EPOCH_CHANGED,
        PhoneErrorCode.NOT_GRANTED,
        PhoneErrorCode.LEASE_EXPIRED,
        -> PhoneContinueFailure.NotReady
        else -> PhoneContinueFailure.Other
    }

/** Mechanical bridge to the redacted read-only status contract; no UI/visual decision here. */
internal fun PhoneContinueDelivery.toTaskContinuationState(): PhoneTaskContinuationState =
    when (this) {
        PhoneContinueDelivery.Idle -> PhoneTaskContinuationState.IDLE
        PhoneContinueDelivery.Sending -> PhoneTaskContinuationState.SENDING
        PhoneContinueDelivery.Delivered -> PhoneTaskContinuationState.DELIVERED
        PhoneContinueDelivery.Failed -> PhoneTaskContinuationState.FAILED
        PhoneContinueDelivery.NotResumed -> PhoneTaskContinuationState.NOT_RESUMED
    }

internal fun PhoneContinueFailure.toTaskContinuationFailure(): PhoneTaskContinuationFailure =
    when (this) {
        PhoneContinueFailure.NotReady -> PhoneTaskContinuationFailure.NOT_READY
        PhoneContinueFailure.ScopeUnavailable -> PhoneTaskContinuationFailure.SCOPE_UNAVAILABLE
        PhoneContinueFailure.StopChannelUnavailable -> PhoneTaskContinuationFailure.STOP_CHANNEL_UNAVAILABLE
        PhoneContinueFailure.TargetUnavailable -> PhoneTaskContinuationFailure.TARGET_UNAVAILABLE
        PhoneContinueFailure.ScreenNotIdle -> PhoneTaskContinuationFailure.SCREEN_NOT_IDLE
        PhoneContinueFailure.ReadTimeout -> PhoneTaskContinuationFailure.READ_TIMEOUT
        PhoneContinueFailure.WindowUnstable -> PhoneTaskContinuationFailure.WINDOW_UNSTABLE
        PhoneContinueFailure.ConnectionLost -> PhoneTaskContinuationFailure.CONNECTION_LOST
        PhoneContinueFailure.Cancelled -> PhoneTaskContinuationFailure.CANCELLED
        PhoneContinueFailure.Other -> PhoneTaskContinuationFailure.OTHER
    }
