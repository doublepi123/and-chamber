package io.andchamber.app.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A native standing pause: never shown as ready, resumed only through a confirmed person request. */
class PhoneStandingPauseLogicTest {
    private val notes = PhoneTarget("Notes", "com.example.notes")
    private val lease = PhoneLease(notes, "Add a note", setOf(PhoneActionScope.Tap), expiresAtMs = 600_000)
    private val runtimePause = PhoneStandingPause(PhoneStandingPauseReason.RuntimeChanged, outcomeUnchecked = false)

    private fun standing(
        pause: PhoneStandingPause? = runtimePause,
        tool: PhoneToolStatus = PhoneToolStatus.Ready,
        status: PhoneStandingStatus = PhoneStandingStatus.Active(PhoneUiLimits.PERMANENT_MS),
        delivery: PhoneContinueDelivery = PhoneContinueDelivery.Idle,
    ) = PhoneStanding(
        status = status,
        apps = listOf(notes),
        actions = setOf(PhoneActionScope.Tap),
        runtime = PhoneRuntimeStatus(tool),
        continuation = PhoneContinuation(delivery = delivery),
        pause = pause,
    )

    /** Connected, switched on, tool loaded, no task: the reported "Ready but refused" case. */
    private val paused =
        PhoneScreenState(
            available = true,
            enabled = true,
            device = PhoneDeviceState(link = PhoneLink.Connected),
            stopChannel = PhoneStopChannel.Verified,
            targets = listOf(notes),
            standing = standing(),
        )

    private fun withStanding(s: PhoneScreenState, st: PhoneStanding) = s.copy(standing = st)

    @Test
    fun `a native pause is never ready and never offers opening the chat`() {
        assertEquals(StandingReadiness.Paused, standingReadiness(paused))
        assertFalse(chatCanUsePhone(paused))
        // Without the pause the same state is ready, so the pause alone decides.
        assertEquals(StandingReadiness.Ready, standingReadiness(withStanding(paused, standing(pause = null))))
        assertTrue(chatCanUsePhone(withStanding(paused, standing(pause = null))))
        // Base readiness ignores the pause and is never Paused.
        assertEquals(StandingReadiness.Ready, standingBaseReadiness(paused))
        PhoneStandingPauseReason.entries.forEach { reason ->
            val s = withStanding(paused, standing(pause = PhoneStandingPause(reason, outcomeUnchecked = false)))
            assertEquals(StandingReadiness.Paused, standingReadiness(s))
            assertFalse(chatCanUsePhone(s))
        }
    }

    @Test
    fun `missing link, tool or scope is still named first, and expired or revoked never become active`() {
        assertEquals(StandingReadiness.NotConnected, standingReadiness(paused.copy(device = PhoneDeviceState(link = PhoneLink.Lost))))
        assertEquals(StandingReadiness.NeedsRestart, standingReadiness(withStanding(paused, standing(tool = PhoneToolStatus.NeedsRestart))))
        assertEquals(StandingReadiness.ToolUnknown, standingReadiness(withStanding(paused, standing(tool = PhoneToolStatus.Unknown))))
        assertEquals(StandingReadiness.Expired, standingReadiness(withStanding(paused, standing(status = PhoneStandingStatus.Expired))))
        assertEquals(StandingReadiness.Revoked, standingReadiness(withStanding(paused, standing(status = PhoneStandingStatus.Revoked))))
        assertFalse(canResumeStanding(withStanding(paused, standing(status = PhoneStandingStatus.Expired))))
        assertFalse(canResumeStanding(withStanding(paused, standing(status = PhoneStandingStatus.Revoked))))
        assertFalse(canResumeStanding(paused.copy(standing = null)))
    }

    @Test
    fun `no task and a runtime-changed pause offers the resume entry`() {
        assertTrue(canResumeStanding(paused))
        assertNull(standingResumeBlock(paused))
        // After a finished task (result card) the same entry applies.
        val ended = paused.copy(task = PhoneTask.Ended(PhoneTaskResult(notes, "Add a note", emptyList())))
        assertTrue(canResumeStanding(ended))
        // Nothing to resume without a pause.
        assertFalse(canResumeStanding(withStanding(paused, standing(pause = null))))
        assertNull(standingResumeBlock(withStanding(paused, standing(pause = null))))
    }

    @Test
    fun `every resume precondition refuses on its own`() {
        val cases =
            mapOf(
                "switch off" to paused.copy(enabled = false),
                "not connected" to paused.copy(device = PhoneDeviceState(link = PhoneLink.Disconnected)),
                "tool restarting" to withStanding(paused, standing(tool = PhoneToolStatus.Restarting)),
                "runtime not running" to withStanding(paused, standing(tool = PhoneToolStatus.RuntimeNotRunning)),
                "stop entry" to paused.copy(stopChannel = PhoneStopChannel.ChannelDisabled),
                "pending" to paused.copy(pending = PhonePending.Connecting),
                "resume in flight" to paused.copy(pending = PhonePending.Resuming),
                "wake-up sending" to withStanding(paused, standing(delivery = PhoneContinueDelivery.Sending)),
                "agent holds a lease" to paused.copy(control = PhoneControl.Agent(lease), task = PhoneTask.Active(notes, "Add a note")),
                "stopped task" to paused.copy(control = PhoneControl.Halted(lease, PhoneHaltReason.TakenOver, true, PhoneLastAction.NoneInFlight)),
                "unchecked outcome" to withStanding(paused, standing(pause = PhoneStandingPause(PhoneStandingPauseReason.OutcomeUnknown, outcomeUnchecked = true))),
            )
        cases.forEach { (name, s) -> assertFalse(name, canResumeStanding(s)) }
    }

    @Test
    fun `an unchecked uncertain action is never resumable, whatever the reason`() {
        PhoneStandingPauseReason.entries.forEach { reason ->
            val s = withStanding(paused, standing(pause = PhoneStandingPause(reason, outcomeUnchecked = true)))
            assertFalse(canResumeStanding(s))
            assertEquals(StandingResumeBlock.OutcomeUnchecked, standingResumeBlock(s))
        }
        // Checked: resumable like any other pause.
        assertTrue(canResumeStanding(withStanding(paused, standing(pause = PhoneStandingPause(PhoneStandingPauseReason.OutcomeUnknown, outcomeUnchecked = false)))))
    }

    @Test
    fun `the resume block names the first thing to fix`() {
        val halted = paused.copy(control = PhoneControl.Halted(lease, PhoneHaltReason.TakenOver, true, PhoneLastAction.NoneInFlight))
        assertEquals(StandingResumeBlock.StoppedTask, standingResumeBlock(halted))
        assertEquals(StandingResumeBlock.TaskRunning, standingResumeBlock(paused.copy(control = PhoneControl.Agent(lease))))
        assertEquals(StandingResumeBlock.NotReady, standingResumeBlock(paused.copy(device = PhoneDeviceState())))
        assertEquals(StandingResumeBlock.StopChannel, standingResumeBlock(paused.copy(stopChannel = PhoneStopChannel.Unknown)))
        assertEquals(StandingResumeBlock.Busy, standingResumeBlock(paused.copy(pending = PhonePending.Resuming)))
    }

    @Test
    fun `a stopped task still continues through its own entry despite the pause it caused`() {
        val halted = paused.copy(control = PhoneControl.Halted(lease, PhoneHaltReason.TakenOver, true, PhoneLastAction.NoneInFlight))
        assertEquals(StandingReadiness.Paused, standingReadiness(halted))
        assertTrue(canContinueInChat(halted))
        assertFalse(canResumeStanding(halted))
        // The continue entry's own rules still hold.
        assertFalse(canContinueInChat(halted.copy(control = PhoneControl.Halted(lease, PhoneHaltReason.OutcomeUnknown, true, PhoneLastAction.MayStillApply))))
        assertFalse(canContinueInChat(withStanding(halted, standing(tool = PhoneToolStatus.NeedsRestart))))
        assertEquals(
            io.andchamber.app.R.string.phone_continue_blocked_not_ready,
            continueBlockedReason(withStanding(halted, standing(tool = PhoneToolStatus.Unknown))),
        )
        // Paused alone never makes the stopped card say "not ready".
        assertEquals(io.andchamber.app.R.string.phone_continue_blocked_generic, continueBlockedReason(halted.copy(pending = PhonePending.Granting)))
    }

    @Test
    fun `only the dialog confirm asks, once, and only while still allowed`() {
        var calls = 0
        assertTrue(confirmResumeStanding(paused) { calls++ })
        assertEquals(1, calls)
        // Re-checked at the tap: a state that changed under the open dialog asks nothing.
        assertFalse(confirmResumeStanding(paused.copy(pending = PhonePending.Resuming)) { calls++ })
        assertFalse(confirmResumeStanding(paused.copy(device = PhoneDeviceState())) { calls++ })
        assertFalse(confirmResumeStanding(withStanding(paused, standing(pause = PhoneStandingPause(PhoneStandingPauseReason.OutcomeUnknown, true)))) { calls++ })
        assertFalse(confirmResumeStanding(withStanding(paused, standing(pause = null))) { calls++ })
        assertEquals(1, calls)
        // Pure readers never call the action (screen open / reconnect / recomposition).
        var silent = 0
        PhoneControlActions(onResumeStanding = { silent++ })
        standingReadiness(paused)
        canResumeStanding(paused)
        standingResumeBlock(paused)
        standingPausedWithoutControl(paused)
        assertEquals(0, silent)
    }

    @Test
    fun `the status strip never reads as plain idle-and-ready beside a pause`() {
        assertTrue(standingPausedWithoutControl(paused))
        assertFalse(standingPausedWithoutControl(withStanding(paused, standing(pause = null))))
        assertFalse(standingPausedWithoutControl(paused.copy(control = PhoneControl.Halted(lease, PhoneHaltReason.TakenOver, true, PhoneLastAction.NoneInFlight))))
        assertFalse(standingPausedWithoutControl(paused.copy(standing = null)))
    }
}
