package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneBlockReason
import io.andchamber.app.phone.core.PhoneConnectionState
import io.andchamber.app.phone.core.PhoneStandingBlockReason
import io.andchamber.app.phone.core.PhoneState
import io.andchamber.app.phone.core.PhoneTimeLimits
import io.andchamber.app.ui.phone.PhoneActionScope
import io.andchamber.app.ui.phone.PhoneAgentRequest
import io.andchamber.app.ui.phone.PhoneAutoConnect
import io.andchamber.app.ui.phone.PhoneBusy
import io.andchamber.app.ui.phone.PhoneControl
import io.andchamber.app.ui.phone.PhoneDeviceProblem
import io.andchamber.app.ui.phone.PhoneDeviceState
import io.andchamber.app.ui.phone.PhoneDiagnostics
import io.andchamber.app.ui.phone.PhoneDiscovery
import io.andchamber.app.ui.phone.PhoneEndpointHints
import io.andchamber.app.ui.phone.PhoneHaltReason
import io.andchamber.app.ui.phone.PhoneLastAction
import io.andchamber.app.ui.phone.PhoneLease
import io.andchamber.app.ui.phone.PhoneLink
import io.andchamber.app.ui.phone.PhonePairingNotice
import io.andchamber.app.ui.phone.PhonePending
import io.andchamber.app.ui.phone.PhoneResultItem
import io.andchamber.app.ui.phone.PhoneRuntimeStatus
import io.andchamber.app.ui.phone.PhoneScreenState
import io.andchamber.app.ui.phone.PhoneStanding
import io.andchamber.app.ui.phone.PhoneStandingPause
import io.andchamber.app.ui.phone.PhoneStandingPauseReason
import io.andchamber.app.ui.phone.PhoneStandingStatus
import io.andchamber.app.ui.phone.PhoneStopChannel
import io.andchamber.app.ui.phone.PhoneTarget
import io.andchamber.app.ui.phone.PhoneTargetVerdict
import io.andchamber.app.ui.phone.PhoneTask
import io.andchamber.app.ui.phone.PhoneTaskResult
import io.andchamber.app.ui.phone.PhoneContinuation
import io.andchamber.app.ui.phone.PhoneUiLimits
import io.andchamber.app.ui.phone.phoneHaltFacts

/**
 * Everything the native layer knows that the core state does not carry: the device link details,
 * the person's stop entry, the target list, the agent's proposal, and the minimal task/halt memory
 * the coordinator keeps after the controller revoked its grant or the process restarted.
 *
 * Nothing here is a grant. The controller decides; this is display input only.
 */
internal data class PhoneScreenFacts(
    val hostPackage: String,
    val available: Boolean,
    val enabled: Boolean,
    val paired: Boolean? = null,
    val hints: PhoneEndpointHints = PhoneEndpointHints(),
    val problem: PhoneDeviceProblem? = null,
    val pairingNotice: PhonePairingNotice = PhonePairingNotice.Idle,
    val stopChannel: PhoneStopChannel = PhoneStopChannel.Unknown,
    val targets: List<PhoneTarget> = emptyList(),
    val request: PhoneAgentRequest? = null,
    /** Revision of the proposal that produced [request]; a grant consumes exactly this one. */
    val requestRevision: Long? = null,
    val pending: PhonePending? = null,
    val busy: PhoneBusy? = null,
    /** The lease the coordinator remembers from the last native grant. */
    /** The single missing step before the agent tool can work, if any (technical token for the UI). */
    val agentToolBlocker: PhoneAgentToolBlocker? = null,
    val lease: PhoneLeaseFacts? = null,
    /** A halt the coordinator must keep after the controller dropped the grant. */
    val halt: PhoneHaltSnapshot? = null,
    val task: PhoneTaskRecord? = null,
    val diagnostics: PhoneDiagnostics = PhoneDiagnostics(),
    /** Enable the configure-once surface; false keeps the legacy per-task flow (tests/older UI). */
    val standingEnabled: Boolean = false,
    /** The saved configuration, if any. It is not authority. */
    val standingState: PhoneStandingState? = null,
    /**
     * Display-only: the last accepted observation package of the live all-apps task. It survives an
     * observation invalidation after a sent action, so a followed handoff keeps naming the app now
     * in front. Null in explicit mode or before the first observation. Never authority.
     */
    val followedPackage: String? = null,
    /** Truthful runtime/tool projection, separate from the local endpoint being reachable. */
    val runtimeStatus: PhoneRuntimeStatus = PhoneRuntimeStatus(),
    /** Native continuation facts after a stop. */
    val continuation: PhoneContinuation = PhoneContinuation(),
    /** Bounded local discovery, separate from what pairing/connection actually proved. */
    val discoveryPairing: PhoneDiscoveryStatus = PhoneDiscoveryStatus.Idle,
    val discoveryConnect: PhoneDiscoveryStatus = PhoneDiscoveryStatus.Idle,
    /** Native-initiated connect after a found endpoint. Manual connects stay [PhoneAutoConnect.Idle]. */
    val autoConnect: PhoneAutoConnect = PhoneAutoConnect.Idle,
)

internal data class PhoneLeaseFacts(
    val target: PhoneTarget,
    val summary: String,
    val actions: Set<PhoneActionScope>,
)

internal data class PhoneHaltSnapshot(
    val lease: PhoneLeaseFacts,
    val reason: PhoneHaltReason,
    /** True once the executor can no longer take a queued action. */
    val confirmed: Boolean,
    /** Latched: a dispatched action stays "may still apply" until a person marks it checked. */
    val lastAction: PhoneLastAction,
    val acknowledged: Boolean = false,
)

/**
 * Maps the core state and the native facts into the screen's own model.
 *
 * Rules that matter:
 * - A grant is the only source of an Agent lease; a paused grant is a halt, not an agent.
 * - A halt reason is kept after `revoke()` cleared the grant, so the screen never loses why it
 *   stopped nor pretends the last action cannot have taken effect.
 * - `inFlightMutation`/`pausing` make the halt unconfirmed; an unknown outcome makes the last action
 *   "may still apply" until a person marks it checked.
 * - A persisted task record becomes `Ended` with no verified steps, which the screen renders as
 *   "needs checking"; a tool acknowledgement is never treated as a business result.
 */
internal fun phoneScreenState(
    core: PhoneState,
    facts: PhoneScreenFacts,
    nowElapsedMs: Long = 0L,
): PhoneScreenState {
    val halt = facts.halt
    val record = facts.task
    // A remembered halt keeps its own lease, so the reason survives the controller clearing its grant.
    val lease = halt?.lease?.toLease(core, facts) ?: facts.lease?.toLease(core, facts)
    val outcomeUnknown = core.blocked == PhoneBlockReason.UNKNOWN_OUTCOME

    val standing = standingProjection(core, facts, nowElapsedMs)

    val control: PhoneControl =
        when {
            halt != null && lease != null ->
                PhoneControl.Halted(
                    lease = lease,
                    reason = halt.reason,
                    confirmed = halt.confirmed,
                    lastAction = halt.lastAction,
                )
            core.grant != null && !core.paused && lease != null ->
                PhoneControl.Agent(lease = lease, actionInFlight = core.inFlightMutation)
            core.grant != null && core.paused && lease != null -> {
                // Fallback for a pause the coordinator did not record itself.
                val derived =
                    phoneHaltFacts(
                        pausing = core.pausing,
                        inFlightMutation = core.inFlightMutation,
                        outcomeUnknown = outcomeUnknown,
                    )
                PhoneControl.Halted(
                    lease = lease,
                    reason = PhoneHaltReason.Paused,
                    confirmed = derived.confirmed,
                    lastAction = derived.lastAction,
                )
            }
            else -> PhoneControl.None
        }

    val task: PhoneTask =
        when {
            control is PhoneControl.Agent -> PhoneTask.Active(control.lease.target, control.lease.summary)
            // A live halted/retained task is still the task the person is recovering, even when a
            // persisted needs-check record exists from an earlier stop. The record is evidence, not
            // a replacement task and never proves the work ended.
            control is PhoneControl.Halted -> PhoneTask.Active(control.lease.target, control.lease.summary)
            record != null ->
                PhoneTask.Ended(
                    PhoneTaskResult(
                        target = targetFor(facts, record.targetPackage),
                        summary = record.summary,
                        // No step was re-read in the target app, so nothing may be called verified.
                        items = emptyList<PhoneResultItem>(),
                        // Only a clean agent finish; any halt reason keeps the full check card.
                        agentReported = record.agentReported && record.haltReason == null,
                    ),
                )
            else -> PhoneTask.None
        }

    val link =
        when {
            core.connection == PhoneConnectionState.CONNECTED -> PhoneLink.Connected
            facts.problem == PhoneDeviceProblem.ConnectionLost -> PhoneLink.Lost
            facts.pending == PhonePending.Connecting -> PhoneLink.Connecting
            else -> PhoneLink.Disconnected
        }

    return PhoneScreenState(
        available = facts.available,
        enabled = facts.enabled && core.enabled,
        hostPackage = facts.hostPackage,
        device =
            PhoneDeviceState(
                link = link,
                paired = facts.paired,
                hints = facts.hints,
                problem = facts.problem,
                pairingNotice = facts.pairingNotice,
                pairingDiscovery = facts.discoveryPairing.toScreenDiscovery(),
                connectDiscovery = facts.discoveryConnect.toScreenDiscovery(),
                autoConnect = facts.autoConnect,
            ),
        stopChannel = facts.stopChannel,
        control = control,
        task = task,
        targets = facts.targets,
        request = facts.request,
        pending = facts.pending,
        busy = facts.busy,
        diagnostics =
            facts.diagnostics.copy(
                // The missing step outranks a transient technical error while it is present.
                lastError = facts.agentToolBlocker?.token ?: facts.diagnostics.lastError,
                runtime = core.runtimeId ?: facts.diagnostics.runtime,
                connection = connectionText(core, facts),
                observedAtMs = core.observation?.observedAtMs ?: facts.diagnostics.observedAtMs,
                observedNodes = core.observation?.nodeCount ?: facts.diagnostics.observedNodes,
            ),
        standing = standing,
    )
}

/**
 * The standing card is enabled by [PhoneScreenFacts.standingEnabled]; with no saved configuration it
 * still projects `NotSet` so the person can configure it once. A wall-clock/elapsed expiration or a
 * revoke is reflected truthfully and never as an active authority.
 *
 * The native block is projected from the controller's own facts, never inferred from a missing task:
 * a scope with no block stays without [PhoneStanding.pause]. A `standingBlockReason` without
 * `standingBlocked` still counts as a block (fail closed), and a null reason while blocked is shown as
 * [PhoneStandingPauseReason.OutcomeUnknown] instead of guessing. An ordinary unknown-outcome block
 * also suspends chat use, even before the native coordinator recorded a standing reason.
 */
internal fun standingProjection(
    core: PhoneState,
    facts: PhoneScreenFacts,
    nowElapsedMs: Long,
): PhoneStanding? {
    if (!facts.standingEnabled) return null
    val state = facts.standingState
    val status =
        when {
            state == null -> PhoneStandingStatus.NotSet
            state.config.revoked -> PhoneStandingStatus.Revoked
            state.expired || state.endsAtElapsedMs <= nowElapsedMs -> PhoneStandingStatus.Expired
            else -> PhoneStandingStatus.Active(state.endsAtElapsedMs)
        }
    val apps =
        state?.config?.apps.orEmpty().map { app ->
            PhoneTarget(
                label = app.label,
                packageName = app.packageName,
                verdict = PhoneTargetVerdict.Allowed,
            )
        }
    val perTaskPermanent = state?.config?.perTaskMs?.let(PhoneTimeLimits::isPermanent) == true
    return PhoneStanding(
        status = status,
        apps = apps,
        actions = state?.config?.let { actionScopes(it.actions) }.orEmpty(),
        perTaskMinutes =
            if (perTaskPermanent) {
                PhoneUiLimits.DEFAULT_DURATION_MIN
            } else {
                state?.config?.perTaskMs
                    ?.let { (it / 60_000L).toInt().coerceAtLeast(1) }
                    ?: PhoneUiLimits.DEFAULT_DURATION_MIN
            },
        perTaskPermanent = perTaskPermanent,
        runtime = facts.runtimeStatus,
        continuation = facts.continuation,
        pause = standingPause(core, facts.halt),
        allApps = state?.config?.allApps == true,
    )
}

/**
 * The native pause behind [PhoneStanding.pause]. Only explicit stopping facts create one: an ordinary
 * paused lease (or an idle scope) is not a standing pause by itself.
 *
 * `outcomeUnchecked` is fail closed: a core unknown-outcome block, an `UNKNOWN_OUTCOME` standing
 * reason, a block whose reason was lost, and an unacknowledged "may still apply" halt all prevent a
 * resume. The person must check the device (and use the recorded task's own path) first.
 */
internal fun standingPause(
    core: PhoneState,
    halt: PhoneHaltSnapshot?,
): PhoneStandingPause? {
    val blocked = core.standingBlocked || core.standingBlockReason != null
    val reason =
        when {
            blocked -> core.standingBlockReason?.toScreenPauseReason() ?: PhoneStandingPauseReason.OutcomeUnknown
            core.blocked == PhoneBlockReason.UNKNOWN_OUTCOME -> PhoneStandingPauseReason.OutcomeUnknown
            else -> return null
        }
    val outcomeUnchecked =
        core.blocked != null ||
            core.standingBlockReason == PhoneStandingBlockReason.UNKNOWN_OUTCOME ||
            (core.standingBlocked && core.standingBlockReason == null) ||
            (halt != null && halt.lastAction == PhoneLastAction.MayStillApply && !halt.acknowledged)
    return PhoneStandingPause(reason = reason, outcomeUnchecked = outcomeUnchecked)
}

/** Exhaustive mapping: every native reason has exactly one screen reason. */
private fun PhoneStandingBlockReason.toScreenPauseReason(): PhoneStandingPauseReason =
    when (this) {
        PhoneStandingBlockReason.NEED_USER -> PhoneStandingPauseReason.NeedUser
        PhoneStandingBlockReason.TAKEOVER -> PhoneStandingPauseReason.TakenOver
        PhoneStandingBlockReason.UNKNOWN_OUTCOME -> PhoneStandingPauseReason.OutcomeUnknown
        PhoneStandingBlockReason.CONNECTION_LOST -> PhoneStandingPauseReason.ConnectionLost
        PhoneStandingBlockReason.DEVICE_LOCKED -> PhoneStandingPauseReason.ScreenLocked
        PhoneStandingBlockReason.RUNTIME_CHANGED -> PhoneStandingPauseReason.RuntimeChanged
    }

private fun PhoneDiscoveryStatus.toScreenDiscovery(): PhoneDiscovery =
    when (this) {
        PhoneDiscoveryStatus.Idle -> PhoneDiscovery.Idle
        PhoneDiscoveryStatus.Searching -> PhoneDiscovery.Searching
        is PhoneDiscoveryStatus.Ready -> PhoneDiscovery.Ready
        is PhoneDiscoveryStatus.Ambiguous -> PhoneDiscovery.Ambiguous
        PhoneDiscoveryStatus.TimedOut -> PhoneDiscovery.TimedOut
        PhoneDiscoveryStatus.Unavailable -> PhoneDiscovery.Unavailable
    }

private fun PhoneLeaseFacts.toLease(
    core: PhoneState,
    facts: PhoneScreenFacts,
): PhoneLease =
    PhoneLease(
        target = followedTarget(core, facts, target),
        summary = summary,
        actions = actions,
        expiresAtMs = core.grant?.expiresAtMs ?: 0L,
    )

/**
 * Display target for a live all-apps task: the package the latest accepted observation really saw.
 * A handoff to another allowed app (OAuth/payment/browser) therefore names that app in the screen,
 * overlay and task labels. Explicit mode and a retained halt keep the launched target, and this
 * never changes identity, authority or the dispatch binding.
 */
private fun followedTarget(
    core: PhoneState,
    facts: PhoneScreenFacts,
    launched: PhoneTarget,
): PhoneTarget {
    if (core.grant?.allApps != true) return launched
    // The live observation wins; after a sent action invalidates it, the remembered followed package
    // keeps the label on the app now in front.
    val followed = core.observation?.binding?.packageName ?: facts.followedPackage ?: return launched
    if (followed == launched.packageName) return launched
    return facts.targets.firstOrNull { it.packageName == followed }
        ?: PhoneTarget(label = followed, packageName = followed)
}

private fun targetFor(
    facts: PhoneScreenFacts,
    packageName: String,
): PhoneTarget? =
    facts.targets.firstOrNull { it.packageName == packageName }
        ?: runCatching { PhoneTarget(label = packageName, packageName = packageName) }.getOrNull()

private fun connectionText(core: PhoneState, facts: PhoneScreenFacts): String? {
    val problem = facts.problem
    val pending = facts.pending
    return when {
        core.connection == PhoneConnectionState.CONNECTED -> "connected"
        pending == PhonePending.Connecting -> "connecting"
        problem != null -> problem.name
        else -> null
    }
}

/** The allowed-action classes the controller accepts for a set of screen scopes. */
internal fun actionKinds(actions: Set<PhoneActionScope>): Set<PhoneActionKind> =
    actions.mapNotNullTo(LinkedHashSet()) {
        when (it) {
            PhoneActionScope.Tap -> PhoneActionKind.TAP
            PhoneActionScope.Swipe -> PhoneActionKind.SWIPE
            PhoneActionScope.Input -> PhoneActionKind.INPUT
            PhoneActionScope.Back -> PhoneActionKind.BACK
        }
    }

/** The screen scopes for a granted set. Observe/capture are always part of the lease. */
internal fun actionScopes(actions: Set<PhoneActionKind>): Set<PhoneActionScope> =
    actions.mapNotNullTo(LinkedHashSet()) {
        when (it) {
            PhoneActionKind.TAP -> PhoneActionScope.Tap
            PhoneActionKind.SWIPE -> PhoneActionScope.Swipe
            PhoneActionKind.INPUT -> PhoneActionScope.Input
            PhoneActionKind.BACK -> PhoneActionScope.Back
        }
    }
