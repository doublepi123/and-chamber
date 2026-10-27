package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneGrant
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneObservationSummary
import io.andchamber.app.phone.core.PhoneState
import io.andchamber.app.phone.core.PhoneTimeLimits
import io.andchamber.app.phone.core.PhoneBlockReason
import io.andchamber.app.phone.core.PhoneConnectionState
import io.andchamber.app.ui.phone.PhoneActionScope
import io.andchamber.app.ui.phone.PhoneAutoConnect
import io.andchamber.app.ui.phone.PhoneControl
import io.andchamber.app.ui.phone.PhoneDiscovery
import io.andchamber.app.ui.phone.PhoneHaltReason
import io.andchamber.app.ui.phone.PhoneLastAction
import io.andchamber.app.ui.phone.PhoneLink
import io.andchamber.app.ui.phone.PhonePending
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
        // The task record is what keeps the summary after the controller dropped the grant, and the
        // result stays "needs checking" because no step was re-read.
        val task = mapped.task as PhoneTask.Ended
        assertEquals("write a note", task.result?.summary)
        assertTrue(task.result!!.items.isEmpty())
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
}
