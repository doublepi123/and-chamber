package io.andchamber.app.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneStandingLogicTest {
    private val host = "io.andchamber.app"
    private val notes = PhoneTarget("Notes", "com.example.notes")
    private val lease = PhoneLease(notes, "Add a note", setOf(PhoneActionScope.Tap), expiresAtMs = 600_000)
    private fun standing(
        tool: PhoneToolStatus = PhoneToolStatus.Ready,
        status: PhoneStandingStatus = PhoneStandingStatus.Active(3_600_000),
        apps: List<PhoneTarget> = listOf(notes),
        continuation: PhoneContinuation = PhoneContinuation(),
    ) = PhoneStanding(status = status, apps = apps, actions = setOf(PhoneActionScope.Tap), runtime = PhoneRuntimeStatus(tool), continuation = continuation)

    private val connected =
        PhoneScreenState(
            available = true,
            enabled = true,
            hostPackage = host,
            device = PhoneDeviceState(link = PhoneLink.Connected),
            stopChannel = PhoneStopChannel.Verified,
            targets = listOf(notes),
            standing = standing(),
        )

    private fun halted(
        reason: PhoneHaltReason = PhoneHaltReason.TakenOver,
        confirmed: Boolean = true,
        last: PhoneLastAction = PhoneLastAction.NoneInFlight,
    ) = connected.copy(control = PhoneControl.Halted(lease, reason, confirmed, last))

    @Test
    fun `chat may use the phone only when the tool is confirmed loaded`() {
        assertEquals(StandingReadiness.Ready, standingReadiness(connected))
        assertTrue(chatCanUsePhone(connected))
        mapOf(
            PhoneToolStatus.NeedsRestart to StandingReadiness.NeedsRestart,
            PhoneToolStatus.Restarting to StandingReadiness.Restarting,
            PhoneToolStatus.RuntimeNotRunning to StandingReadiness.RuntimeNotRunning,
            // A reachable endpoint isn't "loaded": unknown is never shown as ready.
            PhoneToolStatus.Unknown to StandingReadiness.ToolUnknown,
        ).forEach { (tool, expected) ->
            val s = connected.copy(standing = standing(tool = tool))
            assertEquals(expected, standingReadiness(s))
            assertFalse(chatCanUsePhone(s))
        }
    }

    @Test
    fun `a saved scope without a connection or switch is not ready`() {
        assertEquals(StandingReadiness.NotConnected, standingReadiness(connected.copy(device = PhoneDeviceState(link = PhoneLink.Lost))))
        assertEquals(StandingReadiness.NotConnected, standingReadiness(connected.copy(enabled = false)))
        assertEquals(StandingReadiness.Expired, standingReadiness(connected.copy(standing = standing(status = PhoneStandingStatus.Expired))))
        assertEquals(StandingReadiness.Revoked, standingReadiness(connected.copy(standing = standing(status = PhoneStandingStatus.Revoked))))
        assertEquals(StandingReadiness.NotSet, standingReadiness(connected.copy(standing = null)))
    }

    @Test
    fun `per-task mode stays the default and is untouched`() {
        val perTask = connected.copy(standing = null)
        assertFalse(usesStanding(perTask))
        assertFalse(showsStandingCard(perTask))
        assertEquals(PhoneStep.Grant, phoneStep(perTask))
        assertTrue(showsStandingCard(connected))
        assertFalse(showsStandingCard(connected.copy(device = PhoneDeviceState())))
    }

    @Test
    fun `saving a scope needs a connection, a stop entry, allowed apps and actions`() {
        val tap = setOf(PhoneActionScope.Tap)
        assertNull(standingBlock(connected, listOf(notes), tap))
        assertEquals(StandingBlock.NotConnected, standingBlock(connected.copy(device = PhoneDeviceState()), listOf(notes), tap))
        assertEquals(StandingBlock.StopChannel, standingBlock(connected.copy(stopChannel = PhoneStopChannel.NotificationsDisabled), listOf(notes), tap))
        assertEquals(StandingBlock.NoApps, standingBlock(connected, emptyList(), tap))
        val many = (1..MAX_STANDING_APPS + 1).map { PhoneTarget("A$it", "com.example.a$it") }
        assertEquals(StandingBlock.TooManyApps, standingBlock(connected, many, tap))
        assertEquals(StandingBlock.AppNotAllowed, standingBlock(connected, listOf(PhoneTarget("Me", host)), tap))
        assertEquals(StandingBlock.NoActions, standingBlock(connected, listOf(notes), emptySet()))
    }

    @Test
    fun `native verdict can allow ordinary google apps but never the host or sensitive ones`() {
        val maps = PhoneTarget("Maps", "com.google.android.apps.maps")
        assertEquals(TargetBlock.System, targetBlock(maps, host))
        assertNull(targetBlock(maps.copy(verdict = PhoneTargetVerdict.Allowed), host))
        assertEquals(TargetBlock.Sensitive, targetBlock(PhoneTarget("Pay", "com.example.pay", verdict = PhoneTargetVerdict.Sensitive), host))
        // The host is refused even if a verdict says allowed.
        assertEquals(TargetBlock.Host, targetBlock(PhoneTarget("Me", host, verdict = PhoneTargetVerdict.Allowed), host))
        assertEquals(TargetBlock.Invalid, targetBlock(PhoneTarget("Bad", "not a pkg", verdict = PhoneTargetVerdict.Allowed), host))
        assertEquals(listOf(notes), sanitizeStandingApps(listOf(notes, notes, PhoneTarget("Me", host)), host))
    }

    @Test
    fun `continue beside the chat only after a confirmed stop, inside a ready scope`() {
        assertTrue(canContinueInChat(halted()))
        assertTrue(canContinueInChat(halted(last = PhoneLastAction.UserChecked)))
        assertFalse(canContinueInChat(halted(confirmed = false)))
        assertFalse(canContinueInChat(halted(last = PhoneLastAction.MayStillApply)))
        assertFalse(canContinueInChat(halted().copy(standing = standing(tool = PhoneToolStatus.NeedsRestart))))
        assertFalse(canContinueInChat(halted().copy(standing = standing(apps = listOf(PhoneTarget("Other", "com.example.other"))))))
        assertFalse(canContinueInChat(halted().copy(standing = standing(continuation = PhoneContinuation(delivery = PhoneContinueDelivery.Sending)))))
        assertFalse(canContinueInChat(halted().copy(pending = PhonePending.Granting)))
        assertFalse(canContinueInChat(halted().copy(standing = null)))
        // Never while the agent still holds a lease.
        assertFalse(canContinueInChat(connected.copy(control = PhoneControl.Agent(lease))))
    }

    @Test
    fun `the blocked reason names the first thing to fix`() {
        assertEquals(io.andchamber.app.R.string.phone_continue_blocked_settling, continueBlockedReason(halted(confirmed = false)))
        assertEquals(io.andchamber.app.R.string.phone_continue_blocked_check, continueBlockedReason(halted(last = PhoneLastAction.MayStillApply)))
        assertEquals(
            io.andchamber.app.R.string.phone_continue_blocked_not_ready,
            continueBlockedReason(halted().copy(standing = standing(tool = PhoneToolStatus.Unknown))),
        )
        assertEquals(
            io.andchamber.app.R.string.phone_continue_blocked_scope,
            continueBlockedReason(halted().copy(standing = standing(apps = listOf(PhoneTarget("O", "com.example.o"))))),
        )
    }

    @Test
    fun `delivery state shows on the standing card outside the stopped card, once`() {
        fun withDelivery(s: PhoneScreenState, d: PhoneContinueDelivery) =
            s.copy(standing = s.standing!!.copy(continuation = PhoneContinuation(delivery = d)))
        val running = connected.copy(control = PhoneControl.Agent(lease))
        // Running after a failed wake-up: the failure is visible, not hidden behind a live task.
        assertEquals(PhoneContinueDelivery.Failed, standingDeliveryNotice(withDelivery(running, PhoneContinueDelivery.Failed)))
        // Sending stays Sending; it is never reported as delivered.
        assertEquals(PhoneContinueDelivery.Sending, standingDeliveryNotice(withDelivery(running, PhoneContinueDelivery.Sending)))
        assertEquals(PhoneContinueDelivery.Delivered, standingDeliveryNotice(withDelivery(running, PhoneContinueDelivery.Delivered)))
        // No task at all (back to the scope view): still reported.
        assertEquals(PhoneContinueDelivery.Failed, standingDeliveryNotice(withDelivery(connected, PhoneContinueDelivery.Failed)))
        // The stopped card shows it itself: not twice.
        assertNull(standingDeliveryNotice(withDelivery(halted(), PhoneContinueDelivery.Failed)))
        assertNull(standingDeliveryNotice(withDelivery(running, PhoneContinueDelivery.Idle)))
        assertNull(standingDeliveryNotice(running.copy(standing = null)))
    }

    @Test
    fun `window countdown only for an active scope`() {
        assertEquals(60, standingMinutesLeft(PhoneStandingStatus.Active(3_600_000), 0))
        assertNull(standingMinutesLeft(PhoneStandingStatus.Expired, 0))
    }
}
