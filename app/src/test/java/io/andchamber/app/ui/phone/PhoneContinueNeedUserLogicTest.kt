package io.andchamber.app.ui.phone

import io.andchamber.app.R
import io.andchamber.app.ui.phone.overlay.OverlayAction
import io.andchamber.app.ui.phone.overlay.OverlayPhase
import io.andchamber.app.ui.phone.overlay.PhoneOverlayState
import io.andchamber.app.ui.phone.overlay.bodyPanelActions
import io.andchamber.app.ui.phone.overlay.continueInChatLabel
import io.andchamber.app.ui.phone.overlay.panelActions
import io.andchamber.app.ui.phone.overlay.pinnedPanelAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent asked for the person: say so, keep the task, and continue it once per stop. */
class PhoneContinueNeedUserLogicTest {
    private val notes = PhoneTarget("Notes", "com.example.notes")
    private val lease = PhoneLease(notes, "Add a note", setOf(PhoneActionScope.Tap), expiresAtMs = 600_000)

    private fun standing(
        delivery: PhoneContinueDelivery = PhoneContinueDelivery.Idle,
        failure: PhoneContinueFailure? = null,
        needUser: Boolean = false,
        tool: PhoneToolStatus = PhoneToolStatus.Ready,
        status: PhoneStandingStatus = PhoneStandingStatus.Active(PhoneUiLimits.PERMANENT_MS),
        pause: PhoneStandingPause? = PhoneStandingPause(PhoneStandingPauseReason.NeedUser, outcomeUnchecked = false),
    ) = PhoneStanding(
        status = status,
        apps = listOf(notes),
        actions = setOf(PhoneActionScope.Tap),
        runtime = PhoneRuntimeStatus(tool),
        continuation = PhoneContinuation(needUser = needUser, delivery = delivery, failure = failure),
        pause = pause,
    )

    private fun halted(
        reason: PhoneHaltReason = PhoneHaltReason.NeedUser,
        confirmed: Boolean = true,
        last: PhoneLastAction = PhoneLastAction.NoneInFlight,
        standing: PhoneStanding = standing(),
    ) = PhoneScreenState(
        available = true,
        enabled = true,
        device = PhoneDeviceState(link = PhoneLink.Connected),
        stopChannel = PhoneStopChannel.Verified,
        targets = listOf(notes),
        control = PhoneControl.Halted(lease, reason, confirmed, last),
        task = PhoneTask.Active(notes, "Add a note"),
        standing = standing,
    )

    @Test
    fun `need user and a person take-over are told apart`() {
        // The explicit native reason decides, even with no task progress projection at all.
        assertTrue(haltWaitsForPerson(halted()))
        assertTrue(haltWaitsForPerson(halted(standing = standing(needUser = false))))
        assertTrue(haltWaitsForPerson(halted().copy(standing = null)))
        assertFalse(haltWaitsForPerson(halted(reason = PhoneHaltReason.TakenOver)))
        // A person's explicit take-over on an old waiting task stays a take-over: a stale
        // "waiting for you" projection (and its agent note) never overrides the native reason.
        val staleTakeOver =
            halted(reason = PhoneHaltReason.TakenOver, standing = standing(needUser = true))
                .let { it.copy(standing = it.standing!!.copy(continuation = it.standing!!.continuation.copy(agentNote = "please log in"))) }
        assertFalse(haltWaitsForPerson(staleTakeOver))
        // Every other reason is false too, with or without a stale flag.
        PhoneHaltReason.entries.filter { it != PhoneHaltReason.NeedUser }.forEach { reason ->
            assertFalse(reason.name, haltWaitsForPerson(halted(reason = reason, standing = standing(needUser = true))))
            assertFalse(reason.name, haltWaitsForPerson(halted(reason = reason, standing = standing(needUser = false))))
        }
        // Not stopped: never waiting, even if the projection says so.
        assertFalse(haltWaitsForPerson(halted(standing = standing(needUser = true)).copy(control = PhoneControl.None)))
        assertFalse(haltWaitsForPerson(halted(standing = standing(needUser = true)).copy(control = PhoneControl.Agent(lease))))
        assertEquals(R.string.phone_halt_need_user, PhoneHaltReason.NeedUser.label())
        assertEquals(R.string.phone_halt_taken_over, PhoneHaltReason.TakenOver.label())
    }

    @Test
    fun `the continue button says I'm done only when the agent asked`() {
        assertEquals(R.string.phone_continue_after_need_user, continueButtonLabel(true, PhoneContinueDelivery.Idle))
        assertEquals(R.string.phone_continue_in_chat, continueButtonLabel(false, PhoneContinueDelivery.Idle))
        assertEquals(R.string.phone_continue_again, continueButtonLabel(true, PhoneContinueDelivery.NotResumed))
        assertEquals(R.string.phone_continue_again, continueButtonLabel(false, PhoneContinueDelivery.NotResumed))
    }

    @Test
    fun `a need-user stop continues despite the pause it caused`() {
        assertEquals(StandingReadiness.Paused, standingReadiness(halted()))
        assertTrue(canContinueInChat(halted()))
        assertFalse(canResumeStanding(halted()))
    }

    @Test
    fun `only idle or not-resumed may ask again, never a second wake-up`() {
        assertTrue(continueRequestAllowed(PhoneContinueDelivery.Idle))
        assertTrue(continueRequestAllowed(PhoneContinueDelivery.NotResumed))
        listOf(PhoneContinueDelivery.Sending, PhoneContinueDelivery.Delivered, PhoneContinueDelivery.Failed).forEach {
            assertFalse(it.name, continueRequestAllowed(it))
            assertFalse(it.name, canContinueInChat(halted(standing = standing(delivery = it))))
        }
        PhoneContinueFailure.entries.forEach { f ->
            assertTrue(f.name, canContinueInChat(halted(standing = standing(delivery = PhoneContinueDelivery.NotResumed, failure = f))))
        }
        assertEquals(R.string.phone_continue_blocked_sending, continueBlockedReason(halted(standing = standing(delivery = PhoneContinueDelivery.Sending))))
        assertEquals(R.string.phone_continue_blocked_already, continueBlockedReason(halted(standing = standing(delivery = PhoneContinueDelivery.Failed))))
        assertEquals(R.string.phone_continue_blocked_already, continueBlockedReason(halted(standing = standing(delivery = PhoneContinueDelivery.Delivered))))
    }

    @Test
    fun `not resumed keeps every other native guard`() {
        val again = standing(delivery = PhoneContinueDelivery.NotResumed, failure = PhoneContinueFailure.ReadTimeout)
        val base = halted(standing = again)
        val cases =
            mapOf(
                "stop not confirmed" to halted(confirmed = false, standing = again),
                "unchecked outcome" to halted(last = PhoneLastAction.MayStillApply, standing = again),
                "not connected" to base.copy(device = PhoneDeviceState(link = PhoneLink.Lost)),
                "switch off" to base.copy(enabled = false),
                "tool not ready" to halted(standing = again.copy(runtime = PhoneRuntimeStatus(PhoneToolStatus.NeedsRestart))),
                "expired" to halted(standing = again.copy(status = PhoneStandingStatus.Expired)),
                "revoked" to halted(standing = again.copy(status = PhoneStandingStatus.Revoked)),
                "stop entry" to base.copy(stopChannel = PhoneStopChannel.NotificationsDisabled),
                "pending" to base.copy(pending = PhonePending.Granting),
                "app out of scope" to halted(standing = again.copy(apps = listOf(PhoneTarget("O", "com.example.o")))),
                "no standing" to base.copy(standing = null),
            )
        cases.forEach { (name, s) -> assertFalse(name, canContinueInChat(s)) }
        assertTrue(canContinueInChat(base))
    }

    @Test
    fun `not resumed belongs to the stopped card, never the scope card`() {
        val notResumed = halted(standing = standing(delivery = PhoneContinueDelivery.NotResumed))
        assertNull(standingDeliveryNotice(notResumed))
        assertNull(standingDeliveryNotice(notResumed.copy(control = PhoneControl.None, task = PhoneTask.None)))
        // A resumed task whose wake-up failed still shows it, read-only.
        val running = notResumed.copy(control = PhoneControl.Agent(lease), standing = standing(delivery = PhoneContinueDelivery.Failed))
        assertEquals(PhoneContinueDelivery.Failed, standingDeliveryNotice(running))
    }

    @Test
    fun `every failure reason has fixed copy`() {
        assertEquals(PhoneContinueFailure.entries.size, PhoneContinueFailure.entries.map { it.label() }.toSet().size)
    }

    @Test
    fun `a pause with no task kept says it starts a new task`() {
        val noTask = halted().copy(control = PhoneControl.None, task = PhoneTask.None)
        assertTrue(standingResumeStartsFresh(noTask))
        assertTrue(standingResumeStartsFresh(noTask.copy(task = PhoneTask.Ended(PhoneTaskResult(notes, "Add a note", emptyList())))))
        assertFalse(standingResumeStartsFresh(halted()))
        assertFalse(standingResumeStartsFresh(noTask.copy(standing = standing(pause = null))))
    }

    @Test
    fun `status strip says waiting for you only for a need-user stop`() {
        // The strip reads the same rule as the halt card.
        assertTrue(haltWaitsForPerson(halted()))
        assertFalse(haltWaitsForPerson(halted(reason = PhoneHaltReason.Paused)))
        assertFalse(haltWaitsForPerson(halted(reason = PhoneHaltReason.TakenOver, standing = standing(needUser = true))))
    }

    @Test
    fun `overlay promotes the existing continue entry only for need-user`() {
        val needUser = PhoneOverlayState(phase = OverlayPhase.NeedUser, retained = true, canContinueInChat = true)
        assertEquals(OverlayAction.ContinueInChat, panelActions(needUser).first())
        assertEquals(io.andchamber.app.R.string.phone_overlay_btn_continue_after_need_user, continueInChatLabel(OverlayPhase.NeedUser))
        assertEquals(io.andchamber.app.R.string.phone_overlay_btn_continue_in_chat, continueInChatLabel(OverlayPhase.TakenOver))
        // Not offered: the safe actions stay, with "open the phone screen" first.
        val blocked = needUser.copy(canContinueInChat = false)
        assertFalse(OverlayAction.ContinueInChat in panelActions(blocked))
        assertEquals(OverlayAction.OpenNative, panelActions(blocked).first())
        // Take-over keeps its order; unknown/unavailable never offer continue.
        val taken = needUser.copy(phase = OverlayPhase.TakenOver)
        assertEquals(OverlayAction.OpenNative, panelActions(taken).first())
        assertEquals(OverlayAction.ContinueInChat, panelActions(taken)[1])
        listOf(OverlayPhase.Unknown, OverlayPhase.Unavailable, OverlayPhase.NeedUserPending, OverlayPhase.StopUnconfirmed).forEach {
            assertFalse(it.name, OverlayAction.ContinueInChat in panelActions(needUser.copy(phase = it)))
        }
        // The demo never offers it.
        assertFalse(OverlayAction.ContinueInChat in panelActions(needUser.copy(demo = true)))
        // No grant/resume action exists in the overlay at all.
        assertEquals(
            setOf("TakeOver", "Pause", "OpenNative", "End", "Collapse", "RetryTakeOver", "CloseResult", "OpenChat", "ContinueInChat"),
            OverlayAction.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `overlay pins the existing continue entry above the scroll, never twice`() {
        val needUser = PhoneOverlayState(phase = OverlayPhase.NeedUser, retained = true, canContinueInChat = true)
        assertEquals(OverlayAction.ContinueInChat, pinnedPanelAction(needUser))
        assertFalse(OverlayAction.ContinueInChat in bodyPanelActions(needUser))
        // Stop/end stay reachable in the body.
        assertTrue(OverlayAction.End in bodyPanelActions(needUser))
        assertTrue(OverlayAction.OpenNative in bodyPanelActions(needUser))
        assertEquals(panelActions(needUser).size - 1, bodyPanelActions(needUser).size)
        // Nothing pinned when continuing isn't offered, for other phases, the demo or an end confirmation.
        listOf(
            needUser.copy(canContinueInChat = false),
            needUser.copy(phase = OverlayPhase.TakenOver),
            needUser.copy(phase = OverlayPhase.Unknown),
            needUser.copy(phase = OverlayPhase.NeedUserPending),
            needUser.copy(phase = OverlayPhase.StopUnconfirmed),
            needUser.copy(phase = OverlayPhase.Unavailable),
            needUser.copy(demo = true),
            needUser.copy(endConfirm = true),
        ).forEach { s ->
            assertNull(s.phase.name, pinnedPanelAction(s))
            assertEquals(s.phase.name, panelActions(s), bodyPanelActions(s))
        }
    }

    @Test
    fun `native card leads with continue only for a settled need-user or not-resumed stop`() {
        assertTrue(haltContinueFirst(halted()))
        assertFalse(stopControlsAboveStep(halted()))
        val notResumed = halted(reason = PhoneHaltReason.TakenOver, standing = standing(delivery = PhoneContinueDelivery.NotResumed, failure = PhoneContinueFailure.ScreenNotIdle))
        assertTrue(haltContinueFirst(notResumed))
        // Same for need-user + not resumed.
        assertTrue(haltContinueFirst(halted(standing = standing(delivery = PhoneContinueDelivery.NotResumed))))
        // Original order (check first / plain take-over / per-task mode / unsettled).
        listOf(
            halted(reason = PhoneHaltReason.TakenOver),
            halted(reason = PhoneHaltReason.TakenOver, standing = standing(needUser = true)),
            halted(confirmed = false),
            halted(last = PhoneLastAction.MayStillApply),
            halted(last = PhoneLastAction.MayStillApply, standing = standing(delivery = PhoneContinueDelivery.NotResumed)),
            halted().copy(standing = null),
            halted().copy(control = PhoneControl.None),
        ).forEachIndexed { i, s ->
            assertFalse("case $i", haltContinueFirst(s))
            assertTrue("case $i", stopControlsAboveStep(s))
        }
        // A continue in flight (a new grant may already exist under the old halt) or done keeps
        // the stop controls on top; only Idle and NotResumed lead with continue.
        listOf(PhoneContinueDelivery.Sending, PhoneContinueDelivery.Delivered, PhoneContinueDelivery.Failed).forEach { d ->
            listOf(halted(standing = standing(delivery = d)), halted(reason = PhoneHaltReason.TakenOver, standing = standing(delivery = d))).forEach { s ->
                assertFalse(d.name, haltContinueFirst(s))
                assertTrue(d.name, stopControlsAboveStep(s))
            }
        }
        listOf(PhoneContinueDelivery.Idle, PhoneContinueDelivery.NotResumed).forEach { d ->
            assertTrue(d.name, haltContinueFirst(halted(standing = standing(delivery = d))))
            assertFalse(d.name, stopControlsAboveStep(halted(standing = standing(delivery = d))))
        }
        // Layout only: the button's guard is unchanged (e.g. a disconnected need-user stop leads but stays disabled).
        val lost = halted().copy(device = PhoneDeviceState(link = PhoneLink.Lost))
        assertTrue(haltContinueFirst(lost))
        assertFalse(canContinueInChat(lost))
    }
}
