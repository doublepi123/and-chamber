package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneGrant
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneObservationSummary
import io.andchamber.app.phone.core.PhoneState
import io.andchamber.app.phone.core.PhoneTimeLimits
import io.andchamber.app.phone.core.PhoneBlockReason
import io.andchamber.app.phone.core.PhoneConnectionState
import io.andchamber.app.phone.core.PhoneStandingBlockReason
import io.andchamber.app.ui.phone.PhoneActionScope
import io.andchamber.app.ui.phone.PhoneAutoConnect
import io.andchamber.app.ui.phone.PhoneControl
import io.andchamber.app.ui.phone.PhoneDiscovery
import io.andchamber.app.ui.phone.PhoneHaltReason
import io.andchamber.app.ui.phone.PhoneLastAction
import io.andchamber.app.ui.phone.PhoneLink
import io.andchamber.app.ui.phone.PhonePending
import io.andchamber.app.ui.phone.PhoneStandingPauseReason
import io.andchamber.app.ui.phone.PhoneStopChannel
import io.andchamber.app.ui.phone.PhoneTask
import io.andchamber.app.ui.phone.PhoneTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen must never be told more than the native layer knows: a paused grant is a halt, a revoked
 * grant keeps its reason and "may still apply", and a persisted task is only ever "needs checking".
 */
class PhoneScreenStateMapperTest {
    private val target = PhoneTarget(label = "Notes", packageName = "com.example.notes")
    private val lease =
        PhoneLeaseFacts(
            target = target,
            summary = "write a note",
            actions = setOf(PhoneActionScope.Tap, PhoneActionScope.Back),
        )

    private fun facts(
        halt: PhoneHaltSnapshot? = null,
        task: PhoneTaskRecord? = null,
    ) = PhoneScreenFacts(
        hostPackage = "io.andchamber.app",
        available = true,
        enabled = true,
        paired = true,
        stopChannel = PhoneStopChannel.Verified,
        targets = listOf(target),
        lease = lease,
        halt = halt,
        task = task,
    )

    @Test
    fun `discovery and native auto connect stay separate from pairing and the link`() {
        val endpoint =
            PhoneDiscoveredEndpoint(
                kind = PhoneDiscoveryKind.CONNECT,
                host = "127.0.0.1",
                port = 42000,
                serviceName = "adb-1",
                receivedAtMs = 1,
            )
        val core = PhoneState(enabled = true, epoch = 1, connection = PhoneConnectionState.DISCONNECTED)
        val mapped =
            phoneScreenState(
                core,
                facts().copy(
                    paired = true,
                    discoveryPairing = PhoneDiscoveryStatus.Ambiguous(2),
                    discoveryConnect = PhoneDiscoveryStatus.Ready(endpoint),
                    autoConnect = PhoneAutoConnect.Running,
                ),
            )
        assertEquals(PhoneDiscovery.Ambiguous, mapped.device.pairingDiscovery)
        assertEquals(PhoneDiscovery.Ready, mapped.device.connectDiscovery)
        assertEquals(PhoneAutoConnect.Running, mapped.device.autoConnect)
        // Found services and an in-flight auto connect are not a paired/connected claim.
        assertEquals(PhoneLink.Disconnected, mapped.device.link)
        assertEquals(true, mapped.device.paired)

        val timedOut =
            phoneScreenState(
                core,
                facts().copy(
                    discoveryPairing = PhoneDiscoveryStatus.TimedOut,
                    discoveryConnect = PhoneDiscoveryStatus.Unavailable,
                    autoConnect = PhoneAutoConnect.Failed,
                ),
            )
        assertEquals(PhoneDiscovery.TimedOut, timedOut.device.pairingDiscovery)
        assertEquals(PhoneDiscovery.Unavailable, timedOut.device.connectDiscovery)
        assertEquals(PhoneAutoConnect.Failed, timedOut.device.autoConnect)
    }

    @Test
    fun `a grant is an agent lease bound to the controller expiry`() {
        val core =
            PhoneState(
                enabled = true,
                epoch = 7,
                grant =
                    PhoneGrant(
                        epoch = 7,
                        targetPackage = target.packageName,
                        allowedActions = emptySet(),
                        grantedAtMs = 1_000,
                        expiresAtMs = 61_000,
                    ),
                connection = PhoneConnectionState.CONNECTED,
            )
        val mapped = phoneScreenState(core, facts())
        val control = mapped.control as PhoneControl.Agent
        assertEquals(61_000, control.lease.expiresAtMs)
        assertEquals(target, control.lease.target)
        assertTrue(mapped.task is PhoneTask.Active)
        assertEquals(PhoneLink.Connected, mapped.device.link)
    }

    @Test
    fun `a revoked lease keeps its reason and may still apply`() {
        val halt =
            PhoneHaltSnapshot(
                lease = lease,
                reason = PhoneHaltReason.HostReturned,
                confirmed = true,
                lastAction = PhoneLastAction.MayStillApply,
            )
        val record =
            PhoneTaskRecord(
                targetPackage = target.packageName,
                summary = "write a note",
                needsCheck = true,
                haltReason = PhoneHaltReason.HostReturned.name,
            )
        val core = PhoneState(enabled = true, epoch = 9, connection = PhoneConnectionState.CONNECTED)
        val mapped = phoneScreenState(core, facts(halt = halt, task = record))
        val control = mapped.control as PhoneControl.Halted
        assertEquals(PhoneHaltReason.HostReturned, control.reason)
        assertTrue(control.confirmed)
        assertEquals(PhoneLastAction.MayStillApply, control.lastAction)
        // A retained halted task stays the current task even while the needs-check record exists.
        // The record is evidence, not an ended task and not a success claim.
        val task = mapped.task as PhoneTask.Active
        assertEquals(target, task.target)
        assertEquals("write a note", task.summary)
    }

    @Test
    fun `a halted need-user task stays active while its needs-check record is kept`() {
        val halt =
            PhoneHaltSnapshot(
                lease = lease,
                reason = PhoneHaltReason.NeedUser,
                confirmed = true,
                lastAction = PhoneLastAction.NoneInFlight,
            )
        val record =
            PhoneTaskRecord(
                targetPackage = target.packageName,
                summary = "write a note",
                needsCheck = true,
                haltReason = PhoneHaltReason.NeedUser.name,
            )
        val core = PhoneState(enabled = true, epoch = 9, connection = PhoneConnectionState.CONNECTED)
        val mapped = phoneScreenState(core, facts(halt = halt, task = record))
        val control = mapped.control as PhoneControl.Halted
        assertEquals(PhoneHaltReason.NeedUser, control.reason)
        // The top task is the retained task waiting for the person, never "Ended / check results".
        val task = mapped.task as PhoneTask.Active
        assertEquals(target, task.target)
        assertEquals("write a note", task.summary)
    }

    @Test
    fun `an unknown halted task stays active and keeps its unknown reason`() {
        val halt =
            PhoneHaltSnapshot(
                lease = lease,
                reason = PhoneHaltReason.OutcomeUnknown,
                confirmed = true,
                lastAction = PhoneLastAction.UserChecked,
                acknowledged = true,
            )
        val record =
            PhoneTaskRecord(
                targetPackage = target.packageName,
                summary = "write a note",
                needsCheck = true,
                haltReason = PhoneHaltReason.OutcomeUnknown.name,
                acknowledged = true,
            )
        val core =
            PhoneState(
                enabled = true,
                epoch = 9,
                connection = PhoneConnectionState.CONNECTED,
                blocked = PhoneBlockReason.UNKNOWN_OUTCOME,
            )
        val mapped = phoneScreenState(core, facts(halt = halt, task = record))
        val control = mapped.control as PhoneControl.Halted
        assertEquals(PhoneHaltReason.OutcomeUnknown, control.reason)
        // Never presented as a completed/verified result.
        assertTrue(mapped.task is PhoneTask.Active)
    }

    @Test
    fun `after an explicit end only the needs-check record remains ended`() {
        val record =
            PhoneTaskRecord(
                targetPackage = target.packageName,
                summary = "write a note",
                needsCheck = true,
            )
        val core = PhoneState(enabled = true, epoch = 0)
        val ended =
            phoneScreenState(
                core,
                facts().copy(lease = null, halt = null, task = record),
            )
        assertTrue(ended.control is PhoneControl.None)
        val task = ended.task as PhoneTask.Ended
        assertEquals("write a note", task.result?.summary)
        assertTrue(task.result!!.items.isEmpty())

        val dismissed =
            phoneScreenState(
                core,
                facts().copy(lease = null, halt = null, task = null),
            )
        assertTrue(dismissed.control is PhoneControl.None)
        assertTrue(dismissed.task is PhoneTask.None)
    }

    @Test
    fun `a process-restored record without a halt stays ended and does not revive`() {
        val record =
            PhoneTaskRecord(
                targetPackage = target.packageName,
                summary = "write a note",
                needsCheck = true,
            )
        val core = PhoneState(enabled = true, epoch = 0)
        val mapped = phoneScreenState(core, facts().copy(lease = null, halt = null, task = record))
        assertTrue(mapped.control is PhoneControl.None)
        assertTrue(mapped.task is PhoneTask.Ended)
    }

    @Test
    fun `an unconfirmed halt says the stop is not settled`() {
        val halt =
            PhoneHaltSnapshot(
                lease = lease,
                reason = PhoneHaltReason.Paused,
                confirmed = false,
                lastAction = PhoneLastAction.MayStillApply,
            )
        val core = PhoneState(enabled = true, epoch = 3, paused = true, inFlightMutation = true, pausing = true)
        val control = phoneScreenState(core, facts(halt = halt)).control as PhoneControl.Halted
        assertFalse(control.confirmed)
        assertEquals(PhoneLastAction.MayStillApply, control.lastAction)
    }

    @Test
    fun `an acknowledged unknown outcome becomes user checked, not verified`() {
        val halt =
            PhoneHaltSnapshot(
                lease = lease,
                reason = PhoneHaltReason.OutcomeUnknown,
                confirmed = true,
                lastAction = PhoneLastAction.UserChecked,
                acknowledged = true,
            )
        val core =
            PhoneState(
                enabled = true,
                epoch = 4,
                blocked = PhoneBlockReason.UNKNOWN_OUTCOME,
            )
        val control = phoneScreenState(core, facts(halt = halt)).control as PhoneControl.Halted
        assertEquals(PhoneLastAction.UserChecked, control.lastAction)
    }

    @Test
    fun `a persisted task is ended with no verified steps`() {
        val record =
            PhoneTaskRecord(
                targetPackage = target.packageName,
                summary = "write a note",
                needsCheck = true,
            )
        val core = PhoneState(enabled = true, epoch = 0)
        val mapped = phoneScreenState(core, facts(task = record))
        assertTrue(mapped.control is PhoneControl.None)
        val task = mapped.task as PhoneTask.Ended
        assertTrue(task.result!!.items.isEmpty())
        assertNull(mapped.diagnostics.lastError)
    }

    @Test
    fun `only a clean agent finish becomes a light unverified note`() {
        val core = PhoneState(enabled = true, epoch = 0)
        val clean = PhoneTaskRecord(target.packageName, "write a note", needsCheck = true, agentReported = true)
        val light = phoneScreenState(core, facts(task = clean)).task as PhoneTask.Ended
        assertTrue(light.result.agentReported)
        assertTrue(light.result.items.isEmpty())

        // A halt reason (take-over, unknown, person stop) always keeps the full check card.
        val halted = clean.copy(haltReason = "OutcomeUnknown")
        assertFalse((phoneScreenState(core, facts(task = halted)).task as PhoneTask.Ended).result.agentReported)
        val plain = clean.copy(agentReported = false)
        assertFalse((phoneScreenState(core, facts(task = plain)).task as PhoneTask.Ended).result.agentReported)
    }

    @Test
    fun `the missing agent-tool step is a persistent technical token`() {
        val core = PhoneState(enabled = true, epoch = 1, connection = PhoneConnectionState.CONNECTED)
        val running =
            phoneScreenState(
                core,
                facts().copy(agentToolBlocker = PhoneAgentToolBlocker.RUNTIME_RESTART_REQUIRED),
            )
        assertEquals("runtime_restart_required", running.diagnostics.lastError)
        val stopped =
            phoneScreenState(
                core,
                facts().copy(agentToolBlocker = PhoneAgentToolBlocker.RUNTIME_NOT_RUNNING),
            )
        assertEquals("runtime_not_running", stopped.diagnostics.lastError)
        // Ready again: the blocker token is gone and a real technical error is shown instead.
        val cleared =
            phoneScreenState(
                core,
                facts().copy(
                    agentToolBlocker = null,
                    diagnostics = io.andchamber.app.ui.phone.PhoneDiagnostics(lastError = "TARGET_MISMATCH"),
                ),
            )
        assertEquals("TARGET_MISMATCH", cleared.diagnostics.lastError)
    }

    @Test
    fun `pending and stop channel pass through unchanged`() {
        val core = PhoneState(enabled = true, epoch = 1, connection = PhoneConnectionState.CONNECTED)
        val mapped =
            phoneScreenState(
                core,
                facts().copy(pending = PhonePending.Pairing, stopChannel = PhoneStopChannel.ChannelDisabled),
            )
        assertEquals(PhonePending.Pairing, mapped.pending)
        assertEquals(PhoneStopChannel.ChannelDisabled, mapped.stopChannel)
        assertEquals(PhoneLink.Connected, mapped.device.link)
    }

    @Test
    fun `standing projection is active only inside its monotonic window and never after revoke`() {
        val config =
            PhoneStandingConfig(
                apps = listOf(io.andchamber.app.phone.standing.PhoneStandingApp(target.label, target.packageName)),
                actions = setOf(io.andchamber.app.phone.core.PhoneActionKind.TAP),
                windowEndsAtWallMs = 10_000L,
                perTaskMs = 60_000L,
            )
        val core = PhoneState(enabled = true, epoch = 1, connection = PhoneConnectionState.CONNECTED)
        val active =
            phoneScreenState(
                core,
                facts().copy(
                    standingEnabled = true,
                    standingState = PhoneStandingState(config, endsAtElapsedMs = 5_000L),
                    runtimeStatus = io.andchamber.app.ui.phone.PhoneRuntimeStatus(io.andchamber.app.ui.phone.PhoneToolStatus.Ready),
                ),
                nowElapsedMs = 4_999L,
            )
        assertEquals(io.andchamber.app.ui.phone.PhoneStandingStatus.Active(5_000L), active.standing?.status)
        assertEquals(listOf(target.packageName), active.standing?.apps?.map { it.packageName })
        assertEquals(setOf(PhoneActionScope.Tap), active.standing?.actions)

        val expired = phoneScreenState(core, facts().copy(standingEnabled = true, standingState = PhoneStandingState(config, 5_000L)), nowElapsedMs = 5_000L)
        assertEquals(io.andchamber.app.ui.phone.PhoneStandingStatus.Expired, expired.standing?.status)

        val revoked = phoneScreenState(core, facts().copy(standingEnabled = true, standingState = PhoneStandingState(config.copy(revoked = true), 5_000L)), nowElapsedMs = 1L)
        assertEquals(io.andchamber.app.ui.phone.PhoneStandingStatus.Revoked, revoked.standing?.status)

        val permanent =
            phoneScreenState(
                core,
                facts().copy(
                    standingEnabled = true,
                    standingState =
                        PhoneStandingState(
                            config.copy(
                                windowEndsAtWallMs = PhoneTimeLimits.PERMANENT,
                                perTaskMs = PhoneTimeLimits.PERMANENT,
                            ),
                            endsAtElapsedMs = PhoneTimeLimits.PERMANENT,
                        ),
                ),
                nowElapsedMs = 9_999_999L,
            )
        assertEquals(
            io.andchamber.app.ui.phone.PhoneStandingStatus.Active(PhoneTimeLimits.PERMANENT),
            permanent.standing?.status,
        )
        assertEquals(true, permanent.standing?.perTaskPermanent)
    }

    @Test
    fun `standing surface is opt-in so the legacy per-task flow stays untouched`() {
        val core = PhoneState(enabled = true, epoch = 1, connection = PhoneConnectionState.CONNECTED)
        assertNull(phoneScreenState(core, facts()).standing)
        assertEquals(
            io.andchamber.app.ui.phone.PhoneStandingStatus.NotSet,
            phoneScreenState(core, facts().copy(standingEnabled = true)).standing?.status,
        )
    }

    @Test
    fun `observation summary comes from the controller, never node labels`() {
        val binding =
            PhoneObservationBinding(
                observationId = "obs-1",
                generation = 2,
                packageName = target.packageName,
                windowId = "win",
                rotation = 0,
                geometry = io.andchamber.app.phone.core.PhoneGeometry(1080, 2400, 0),
            )
        val core =
            PhoneState(
                enabled = true,
                epoch = 5,
                observation = PhoneObservationSummary(binding, nodeCount = 12, observedAtMs = 42),
                capabilities = PhoneCapabilities(observe = true),
            )
        val mapped = phoneScreenState(core, facts())
        assertEquals(42L, mapped.diagnostics.observedAtMs)
        assertEquals(12, mapped.diagnostics.observedNodes)
    }

    private fun standingScreenState() =
        PhoneStandingState(
            config =
                PhoneStandingConfig(
                    apps = listOf(io.andchamber.app.phone.standing.PhoneStandingApp(target.label, target.packageName)),
                    actions = setOf(io.andchamber.app.phone.core.PhoneActionKind.TAP),
                    windowEndsAtWallMs = 10_000L,
                    perTaskMs = 60_000L,
                ),
            endsAtElapsedMs = 5_000L,
        )

    private fun mapped(
        core: PhoneState,
        standingEnabled: Boolean = true,
        halt: PhoneHaltSnapshot? = null,
    ) = phoneScreenState(
        core,
        facts(halt = halt).copy(
            standingEnabled = standingEnabled,
            standingState = standingScreenState(),
        ),
        nowElapsedMs = 1_000L,
    )

    @Test
    fun `every native standing reason maps to exactly one screen pause reason`() {
        val expected =
            mapOf(
                PhoneStandingBlockReason.NEED_USER to PhoneStandingPauseReason.NeedUser,
                PhoneStandingBlockReason.TAKEOVER to PhoneStandingPauseReason.TakenOver,
                PhoneStandingBlockReason.UNKNOWN_OUTCOME to PhoneStandingPauseReason.OutcomeUnknown,
                PhoneStandingBlockReason.CONNECTION_LOST to PhoneStandingPauseReason.ConnectionLost,
                PhoneStandingBlockReason.DEVICE_LOCKED to PhoneStandingPauseReason.ScreenLocked,
                PhoneStandingBlockReason.RUNTIME_CHANGED to PhoneStandingPauseReason.RuntimeChanged,
            )
        assertEquals(PhoneStandingBlockReason.entries.toSet(), expected.keys)
        val core =
            PhoneState(
                enabled = true,
                epoch = 3,
                connection = PhoneConnectionState.CONNECTED,
            )
        expected.forEach { (native, screen) ->
            val pause =
                mapped(core.copy(standingBlocked = true, standingBlockReason = native))
                    .standing
                    ?.pause
            assertEquals("reason $native", screen, pause?.reason)
            // Only the unknown reason itself is fail-closed unchecked here.
            assertEquals(native == PhoneStandingBlockReason.UNKNOWN_OUTCOME, pause?.outcomeUnchecked)
        }
    }

    @Test
    fun `a scope with no block is never marked paused just because a lease is paused`() {
        val idle = PhoneState(enabled = true, epoch = 1, connection = PhoneConnectionState.CONNECTED)
        assertNull(mapped(idle).standing?.pause)
        assertNull(mapped(idle.copy(paused = true)).standing?.pause)
        // A reason without the boolean still counts as a block, and a lost reason is fail closed.
        val reasonOnly =
            mapped(idle.copy(standingBlocked = false, standingBlockReason = PhoneStandingBlockReason.TAKEOVER))
        assertEquals(PhoneStandingPauseReason.TakenOver, reasonOnly.standing?.pause?.reason)
        val lostReason = mapped(idle.copy(standingBlocked = true, standingBlockReason = null))
        assertEquals(PhoneStandingPauseReason.OutcomeUnknown, lostReason.standing?.pause?.reason)
        assertEquals(true, lostReason.standing?.pause?.outcomeUnchecked)
    }

    @Test
    fun `an ordinary unknown outcome block is a pause even before a standing reason is recorded`() {
        val core =
            PhoneState(
                enabled = true,
                epoch = 2,
                connection = PhoneConnectionState.CONNECTED,
                blocked = PhoneBlockReason.UNKNOWN_OUTCOME,
            )
        val pause = mapped(core).standing?.pause
        assertEquals(PhoneStandingPauseReason.OutcomeUnknown, pause?.reason)
        assertEquals(true, pause?.outcomeUnchecked)
    }

    @Test
    fun `an unacknowledged may-still-apply halt keeps the pause unchecked`() {
        val blocked =
            PhoneState(
                enabled = true,
                epoch = 4,
                connection = PhoneConnectionState.CONNECTED,
                standingBlocked = true,
                standingBlockReason = PhoneStandingBlockReason.TAKEOVER,
            )
        val open =
            PhoneHaltSnapshot(
                lease = lease,
                reason = PhoneHaltReason.OutcomeUnknown,
                confirmed = true,
                lastAction = PhoneLastAction.MayStillApply,
            )
        val checked = open.copy(lastAction = PhoneLastAction.UserChecked, acknowledged = true)
        assertEquals(true, mapped(blocked, halt = open).standing?.pause?.outcomeUnchecked)
        assertEquals(false, mapped(blocked, halt = checked).standing?.pause?.outcomeUnchecked)
        // A halt alone is a task fact, not a standing pause: the scope itself has no block.
        val noBlock = PhoneState(enabled = true, epoch = 4, connection = PhoneConnectionState.CONNECTED)
        assertNull(mapped(noBlock, halt = open).standing?.pause)
    }

    @Test
    fun `the standing surface off never projects a pause`() {
        val core =
            PhoneState(
                enabled = true,
                epoch = 1,
                connection = PhoneConnectionState.CONNECTED,
                standingBlocked = true,
                standingBlockReason = PhoneStandingBlockReason.RUNTIME_CHANGED,
            )
        assertNull(mapped(core, standingEnabled = false).standing)
    }
}
