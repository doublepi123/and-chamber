package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.core.PhoneStandingBlockReason
import io.andchamber.app.phone.core.PhoneTimeLimits
import io.andchamber.app.phone.standing.PhoneStandingApp
import io.andchamber.app.phone.standing.PhoneStandingRefusal
import io.andchamber.app.ui.phone.PhonePending
import io.andchamber.app.ui.phone.PhoneRuntimeStatus
import io.andchamber.app.ui.phone.PhoneToolStatus

/**
 * The person-confirmed standing configuration. This is configuration, never a lease: saving it does
 * not grant, connect, launch or start anything. It may be saved before the runtime loaded the tool,
 * and it survives a process restart as a bounded window (wall-clock end), never as authority.
 *
 * Either [windowEndsAtWallMs] or [perTaskMs] may be [PhoneTimeLimits.PERMANENT]. That is still only a
 * configured scope: after a reboot or process death there is no grant, no task binding and no
 * automatic resume; the first valid chat request still binds and every stop/block check still runs.
 */
data class PhoneStandingConfig(
    val apps: List<PhoneStandingApp>,
    val actions: Set<PhoneActionKind>,
    /** Absolute wall-clock end, or [PhoneTimeLimits.PERMANENT]. Persisted as configuration only. */
    val windowEndsAtWallMs: Long,
    /** Per-task limit, or [PhoneTimeLimits.PERMANENT] when the person chose no per-task limit. */
    val perTaskMs: Long,
    val revoked: Boolean = false,
)

/**
 * Runtime projection of a saved configuration: [endsAtElapsedMs] is derived from the monotonic
 * clock used by the controller, so a wall-clock change cannot extend a per-task lease. A permanent
 * window uses [PhoneTimeLimits.PERMANENT] for both ends; it never becomes a persistent lease.
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
    !revoked && !PhoneTimeLimits.isExpired(nowWallMs, windowEndsAtWallMs)

/**
 * Restores the monotonic projection from the persisted wall-clock end.
 *
 * A permanent scope stores [PhoneTimeLimits.PERMANENT] for both ends; restoring it must not compute
 * `clock() + (MAX - wallClock)`, which would overflow. A finite end is bounded by the configured
 * 24-hour maximum even if the wall clock moved backwards, and an unrepresentable remainder fails
 * closed as expired instead of becoming a permanent scope.
 */
internal fun restoredStandingState(
    config: PhoneStandingConfig,
    nowWallMs: Long,
    nowElapsedMs: Long,
): PhoneStandingState {
    if (PhoneTimeLimits.isPermanent(config.windowEndsAtWallMs)) {
        // Explicit deadline sentinel: never compute MAX - wallClock + elapsedRealtime.
        return PhoneStandingState(
            config = config,
            endsAtElapsedMs = PhoneTimeLimits.PERMANENT,
            expired = false,
        )
    }
    val remaining = PhoneTimeLimits.remainingMs(nowWallMs, config.windowEndsAtWallMs)
        ?: return PhoneStandingState(
            config = config,
            endsAtElapsedMs = PhoneTimeLimits.PERMANENT,
            expired = false,
        )
    if (remaining <= 0L) {
        return PhoneStandingState(config = config, endsAtElapsedMs = nowElapsedMs, expired = true)
    }
    // A finite saved window can never be longer than the configured maximum. A much larger remainder
    // is a corrupt/backwards-clock value; fail closed instead of projecting an unbounded active scope.
    if (remaining > STANDING_MAX_WINDOW_MS) {
        return PhoneStandingState(config = config, endsAtElapsedMs = nowElapsedMs, expired = true)
    }
    val endsAt =
        runCatching { PhoneTimeLimits.expiresAt(nowElapsedMs, remaining) }
            .getOrElse { return PhoneStandingState(config, nowElapsedMs, expired = true) }
    return PhoneStandingState(config = config, endsAtElapsedMs = endsAt, expired = false)
}

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

/**
 * Exact bounds used by the native save path; the UI has its own earlier copy of the same limits.
 *
 * Either duration may be the explicit [PhoneTimeLimits.PERMANENT] sentinel. A permanent scope is
 * configuration, not a lease: it still needs the runtime, native task binding and every stop/block
 * check. When both values are finite, a task can never outlive its standing window.
 */
internal fun validStandingBounds(
    windowMs: Long,
    perTaskMs: Long,
): Boolean {
    val windowValid =
        PhoneTimeLimits.isPermanent(windowMs) ||
            windowMs in STANDING_MIN_WINDOW_MS..STANDING_MAX_WINDOW_MS
    val perTaskValid =
        PhoneTimeLimits.isPermanent(perTaskMs) ||
            perTaskMs in PhoneLimits.MIN_LEASE_MS..PhoneLimits.MAX_LEASE_MS
    if (!windowValid || !perTaskValid) return false
    if (PhoneTimeLimits.isPermanent(windowMs) || PhoneTimeLimits.isPermanent(perTaskMs)) return true
    return perTaskMs <= windowMs
}

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

/**
 * Why a person-confirmed standing resume is refused. Each value is one independently failing guard,
 * so a caller can log or test it without ever calling the controller.
 */
internal enum class StandingResumeRefusal {
    /** No standing block at all: opening the screen, reconnecting or recomposition never resumes. */
    NOT_PAUSED,

    /** The phone-control experiment / controller is switched off (or unavailable on this device). */
    DISABLED,

    /** No saved scope, or the person revoked it. */
    NOT_CONFIGURED,

    /** A finite window is over (wall clock or monotonic projection), or the state is already expired. */
    EXPIRED,

    /** No current runtime identity, or the runtime/tool handshake is not stable and ready. */
    NOT_READY,

    /** A different runtime owns the saved session; never silently rebind the scope to another runtime. */
    BINDING_CHANGED,

    CONNECTION,
    STOP_CHANNEL,

    /** Another native operation or a wake-up send is in progress. */
    BUSY,

    /** A live/retained task, lease or task identity still owns the task: use its own continue/end path. */
    TASK_OWNED,

    /** An action may still be in flight; nothing may be cleared before it settles. */
    UNSETTLED,

    /** An uncertain outcome has not been checked (including an `UNKNOWN_OUTCOME` standing reason). */
    UNCHECKED,
}

/**
 * Frozen snapshot for [standingResumeRefusal]. Everything is Android-free so the gate can be tested
 * together with the resume callback and with the real core controller.
 */
internal data class StandingResumeFacts(
    val available: Boolean,
    val enabled: Boolean,
    val controllerEnabled: Boolean,
    val config: PhoneStandingConfig?,
    val nowWallMs: Long,
    val nowElapsedMs: Long,
    val endsAtElapsedMs: Long,
    val expired: Boolean,
    val runtimeId: String?,
    val binding: PhoneStandingBindingState,
    val runtimeRunning: Boolean,
    val toolReady: Boolean,
    val connectionConnected: Boolean,
    val stopChannelVerified: Boolean,
    val pending: PhonePending?,
    val continuationInFlight: Boolean,
    val continuationSending: Boolean,
    val hasGrant: Boolean,
    val hasHalt: Boolean,
    val hasLease: Boolean,
    val hasRetainedTask: Boolean,
    val hasTaskOwner: Boolean,
    val inFlightMutation: Boolean,
    val pausing: Boolean,
    val standingBlocked: Boolean,
    val standingBlockReason: PhoneStandingBlockReason?,
    val coreBlocked: Boolean,
    val haltMayStillApply: Boolean,
    val haltAcknowledged: Boolean,
)

/**
 * The full fresh check before a person-confirmed standing resume. It only reads the frozen facts; a
 * refusal leaves the controller block, binding, configuration and task memory untouched.
 *
 * Order is deliberate: the person fixes the outer readiness first, then the scope, then the binding
 * and connection, and only then is anything cleared. An expired/revoked scope is never revived and
 * a different runtime binding is refused instead of being rebound. A task still owned (live,
 * retained, halted or identified) must go through its own continue/end entry, never this one.
 */
internal fun standingResumeRefusal(facts: StandingResumeFacts): StandingResumeRefusal? {
    if (!facts.standingBlocked && facts.standingBlockReason == null && !facts.coreBlocked) {
        return StandingResumeRefusal.NOT_PAUSED
    }
    if (!facts.available || !facts.enabled || !facts.controllerEnabled) return StandingResumeRefusal.DISABLED
    val config = facts.config ?: return StandingResumeRefusal.NOT_CONFIGURED
    if (config.revoked) return StandingResumeRefusal.NOT_CONFIGURED
    if (!config.isActive(facts.nowWallMs) ||
        facts.expired ||
        facts.endsAtElapsedMs <= facts.nowElapsedMs
    ) {
        return StandingResumeRefusal.EXPIRED
    }
    val runtimeId = facts.runtimeId ?: return StandingResumeRefusal.NOT_READY
    // Preserve the saved session's isolation: never resume against a different runtime.
    if (facts.binding.runtimeId != null && facts.binding.runtimeId != runtimeId) {
        return StandingResumeRefusal.BINDING_CHANGED
    }
    if (!facts.runtimeRunning || !facts.toolReady) return StandingResumeRefusal.NOT_READY
    if (!facts.connectionConnected) return StandingResumeRefusal.CONNECTION
    if (!facts.stopChannelVerified) return StandingResumeRefusal.STOP_CHANNEL
    if (facts.pending != null || facts.continuationInFlight || facts.continuationSending) {
        return StandingResumeRefusal.BUSY
    }
    if (facts.hasGrant ||
        facts.hasHalt ||
        facts.hasLease ||
        facts.hasRetainedTask ||
        facts.hasTaskOwner
    ) {
        return StandingResumeRefusal.TASK_OWNED
    }
    if (facts.inFlightMutation || facts.pausing) return StandingResumeRefusal.UNSETTLED
    if (facts.coreBlocked ||
        facts.standingBlockReason == PhoneStandingBlockReason.UNKNOWN_OUTCOME ||
        // A block whose reason was lost is unknown, not silently resumable.
        (facts.standingBlocked && facts.standingBlockReason == null) ||
        (facts.haltMayStillApply && !facts.haltAcknowledged)
    ) {
        return StandingResumeRefusal.UNCHECKED
    }
    return null
}

/**
 * The one gate-then-resume step. [resume] runs at most once and only after every guard passed, so a
 * refusal cannot clear a block, pause, binding or task as a side effect. The caller supplies the
 * existing `resumeStandingForPerson` callback; this helper never grants or replays anything.
 */
internal fun confirmedStandingResume(
    facts: StandingResumeFacts,
    resume: () -> Boolean,
): Boolean = standingResumeRefusal(facts) == null && resume()
