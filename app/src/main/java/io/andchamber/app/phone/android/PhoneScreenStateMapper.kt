package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneBlockReason
import io.andchamber.app.phone.core.PhoneConnectionState
import io.andchamber.app.phone.core.PhoneState
import io.andchamber.app.ui.phone.PhoneActionScope
import io.andchamber.app.ui.phone.PhoneAgentRequest
import io.andchamber.app.ui.phone.PhoneBusy
import io.andchamber.app.ui.phone.PhoneControl
import io.andchamber.app.ui.phone.PhoneDeviceProblem
import io.andchamber.app.ui.phone.PhoneDeviceState
import io.andchamber.app.ui.phone.PhoneDiagnostics
import io.andchamber.app.ui.phone.PhoneEndpointHints
import io.andchamber.app.ui.phone.PhoneHaltReason
import io.andchamber.app.ui.phone.PhoneLastAction
import io.andchamber.app.ui.phone.PhoneLease
import io.andchamber.app.ui.phone.PhoneLink
import io.andchamber.app.ui.phone.PhonePairingNotice
import io.andchamber.app.ui.phone.PhonePending
import io.andchamber.app.ui.phone.PhoneResultItem
import io.andchamber.app.ui.phone.PhoneScreenState
import io.andchamber.app.ui.phone.PhoneStopChannel
import io.andchamber.app.ui.phone.PhoneTarget
import io.andchamber.app.ui.phone.PhoneTask
import io.andchamber.app.ui.phone.PhoneTaskResult
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
): PhoneScreenState {
    val halt = facts.halt
    val record = facts.task
    // A remembered halt keeps its own lease, so the reason survives the controller clearing its grant.
    val lease = halt?.lease?.toLease(core) ?: facts.lease?.toLease(core)
    val outcomeUnknown = core.blocked == PhoneBlockReason.UNKNOWN_OUTCOME

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
            record != null ->
                PhoneTask.Ended(
                    PhoneTaskResult(
                        target = targetFor(facts, record.targetPackage),
                        summary = record.summary,
                        // No step was re-read in the target app, so nothing may be called verified.
                        items = emptyList<PhoneResultItem>(),
                    ),
                )
            control is PhoneControl.Halted ->
                PhoneTask.Active(control.lease.target, control.lease.summary)
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
    )
}

private fun PhoneLeaseFacts.toLease(core: PhoneState): PhoneLease =
    PhoneLease(
        target = target,
        summary = summary,
        actions = actions,
        expiresAtMs = core.grant?.expiresAtMs ?: 0L,
    )

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
