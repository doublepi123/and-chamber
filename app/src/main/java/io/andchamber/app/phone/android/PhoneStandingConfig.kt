package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.core.PhoneStandingBlockReason
import io.andchamber.app.phone.standing.PhoneStandingApp
import io.andchamber.app.phone.standing.PhoneStandingRefusal
import io.andchamber.app.ui.phone.PhoneRuntimeStatus
import io.andchamber.app.ui.phone.PhoneToolStatus

/**
 * The person-confirmed standing configuration. This is configuration, never a lease: saving it does
 * not grant, connect, launch or start anything. It may be saved before the runtime loaded the tool,
 * and it survives a process restart as a bounded window (wall-clock end), never as authority.
 */
data class PhoneStandingConfig(
    val apps: List<PhoneStandingApp>,
    val actions: Set<PhoneActionKind>,
    /** Absolute wall-clock end, persisted so a restarted process can age the window honestly. */
    val windowEndsAtWallMs: Long,
    val perTaskMs: Long,
    val revoked: Boolean = false,
)

/**
 * Runtime projection of a saved configuration: [endsAtElapsedMs] is derived from the monotonic
 * clock used by the controller, so a wall-clock change cannot extend a per-task lease.
 */
internal data class PhoneStandingState(
    val config: PhoneStandingConfig,
    val endsAtElapsedMs: Long,
    val expired: Boolean = false,
)

/** In-memory binding to the current runtime and the chat that first used the scope. */
internal data class PhoneStandingBindingState(
    val runtimeId: String? = null,
    val sessionLabel: String? = null,
)

internal const val STANDING_MIN_WINDOW_MS = 60_000L
internal const val STANDING_MAX_WINDOW_MS = 24L * 60L * 60L * 1_000L

internal fun PhoneStandingConfig.isActive(nowWallMs: Long): Boolean =
    !revoked && nowWallMs < windowEndsAtWallMs

/**
 * Pre-checks that need the native task/session memory the core does not carry. The core policy is
 * still applied afterwards under the controller lock. Nothing here grants or mutates.
 *
 * Order is deliberate: a native stop block wins over a retained task (the chat must wait for the
 * person), then an already live/retained task, then a different chat. A second request can never
 * silently replace a task.
 */
internal fun standingRequestRefusal(
    config: PhoneStandingConfig?,
    nowWallMs: Long,
    runtimeId: String?,
    sessionId: String,
    binding: PhoneStandingBindingState,
    hasLiveTask: Boolean,
    hasRetainedTask: Boolean,
    blocked: Boolean,
): PhoneStandingRefusal? =
    when {
        config == null || config.revoked -> PhoneStandingRefusal.NOT_CONFIGURED
        !config.isActive(nowWallMs) -> PhoneStandingRefusal.EXPIRED
        binding.runtimeId != null && runtimeId != binding.runtimeId -> PhoneStandingRefusal.RUNTIME_CHANGED
        binding.sessionLabel != null && sessionId != binding.sessionLabel -> PhoneStandingRefusal.SESSION_MISMATCH
        blocked -> PhoneStandingRefusal.STANDING_BLOCKED
        hasLiveTask || hasRetainedTask -> PhoneStandingRefusal.TASK_ACTIVE
        else -> null
    }

/** Exact bounds used by the native save path; the UI has its own earlier copy of the same limits. */
internal fun validStandingBounds(
    windowMs: Long,
    perTaskMs: Long,
): Boolean =
    windowMs in STANDING_MIN_WINDOW_MS..STANDING_MAX_WINDOW_MS &&
        perTaskMs in PhoneLimits.MIN_LEASE_MS..PhoneLimits.MAX_LEASE_MS &&
        perTaskMs <= windowMs

/**
 * Exact-owner test for a failed start rollback. A live epoch always decides first: if a newer task
 * is live, or the bound task identity already belongs to a newer task, an old rollback is refused
 * even when the old retained epoch/key still looks familiar. With no live epoch, only the epoch this
 * start created, the retained epoch it replaced, or its own identity is a valid target.
 */
internal fun standingRollbackTargets(
    requestedEpoch: Long,
    liveEpoch: Long?,
    retainedEpoch: Long?,
    identityMatchesRequestedKey: Boolean,
    identityOwnedByNewerTask: Boolean = false,
): Boolean {
    if (identityOwnedByNewerTask) return false
    if (liveEpoch != null) return liveEpoch == requestedEpoch
    return retainedEpoch == requestedEpoch || identityMatchesRequestedKey
}

/**
 * Exact live-owner test for route-abort/continuation rollback. The live epoch must be exactly the
 * one being rolled back, the task identity must not belong to a newer task, and (when this caller
 * has a key) must be this key. A superseded caller must leave all newer state alone.
 */
internal fun standingExactLiveOwner(
    requestedEpoch: Long,
    liveEpoch: Long?,
    identityMatchesRequestedKey: Boolean,
    identityOwnedByNewerTask: Boolean,
    requireIdentity: Boolean = true,
): Boolean =
    !identityOwnedByNewerTask &&
        liveEpoch == requestedEpoch &&
        (!requireIdentity || identityMatchesRequestedKey)

/**
 * The one narrow auto-unlock allowed after a person-confirmed runtime restart. It only clears a
 * `RUNTIME_CHANGED` standing block for a first, still-unbound configuration with no task owner.
 * Every other reason, a bound session/runtime, a retained/live task, an unknown outcome, a pause,
 * a not-ready handshake or a newer revision keeps the block and requires native re-save/continue.
 *
 * This is a pure admission check; the caller must use the strict `resumeStanding` (which does not
 * clear pause/unknown) only after this returns true.
 */
internal fun confirmedRestartMayClearRuntimeBlock(
    restartConfirmed: Boolean,
    restartRevision: Long?,
    currentRevision: Long,
    configActive: Boolean,
    bindingNeverBound: Boolean,
    standingBlocked: Boolean,
    standingBlockReason: PhoneStandingBlockReason?,
    controllerEnabled: Boolean,
    controllerPaused: Boolean,
    controllerUnknownBlocked: Boolean,
    runtimeId: String?,
    runtimeRunning: Boolean,
    toolReady: Boolean,
    hasLiveGrant: Boolean,
    hasHalt: Boolean,
    hasRetainedTask: Boolean,
    hasLease: Boolean,
    hasTaskOwner: Boolean,
): Boolean =
    restartConfirmed &&
        restartRevision != null &&
        restartRevision == currentRevision &&
        configActive &&
        bindingNeverBound &&
        standingBlocked &&
        standingBlockReason == PhoneStandingBlockReason.RUNTIME_CHANGED &&
        controllerEnabled &&
        !controllerPaused &&
        !controllerUnknownBlocked &&
        !runtimeId.isNullOrBlank() &&
        runtimeRunning &&
        toolReady &&
        !hasLiveGrant &&
        !hasHalt &&
        !hasRetainedTask &&
        !hasLease &&
        !hasTaskOwner

/**
 * Truthful runtime projection. TRANSITIONING is never Ready: a restart in progress may have an
 * endpoint that answers but has not yet proved that this launch loaded the plugin.
 */
internal fun phoneRuntimeStatus(
    presence: PhoneRuntimePresence,
    toolReady: Boolean?,
    restarting: Boolean,
): PhoneRuntimeStatus {
    val tool =
        when {
            // A confirmed restart is in progress: even a still-old endpoint/tool probe is not Ready.
            restarting -> PhoneToolStatus.Restarting
            presence == PhoneRuntimePresence.NOT_RUNNING -> PhoneToolStatus.RuntimeNotRunning
            presence == PhoneRuntimePresence.RUNNING ->
                when (toolReady) {
                    true -> PhoneToolStatus.Ready
                    false -> PhoneToolStatus.NeedsRestart
                    null -> PhoneToolStatus.Unknown
                }
            presence == PhoneRuntimePresence.TRANSITIONING -> PhoneToolStatus.Unknown
            else -> PhoneToolStatus.Unknown
        }
    return PhoneRuntimeStatus(tool = tool)
}
