package io.andchamber.app.phone.android.overlay

import io.andchamber.app.notify.SessionNoticeKind
import io.andchamber.app.notify.SessionNoticeProjection
import io.andchamber.app.notify.SessionPromptChoice
import io.andchamber.app.notify.SessionPromptDeliveryState
import io.andchamber.app.notify.SessionPromptProjection
import io.andchamber.app.phone.core.PhoneTimeLimits
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.overlay.progress.PhoneTaskSnapshot
import io.andchamber.app.phone.overlay.progress.PhoneTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay question is bound to exactly one native task owner. A retained task may show it without
 * a live lease; a null route, another session, another epoch or an old launch may not.
 */
class PhoneTaskConversationTest {
    private fun snapshot(
        key: PhoneTaskKey = PhoneTaskKey("task-1", "runtime-1"),
        epoch: Long = 7L,
        routeSession: String? = "ses_1",
        state: PhoneTaskState = PhoneTaskState.ACTIVE,
    ) = PhoneTaskSnapshot(
        key = key,
        grantEpoch = epoch,
        routeSession = routeSession,
        summary = "summary",
        packageName = "io.example.app",
        state = state,
        startedAtMs = 1_000L,
        updatedAtMs = 2_000L,
        events = emptyList(),
        stopAck = null,
        finishReported = false,
        business = null,
    )

    private fun projection(
        launchId: Long? = 11L,
        sessionId: String? = "ses_1",
        generation: Long = 3L,
        delivery: SessionPromptDeliveryState = SessionPromptDeliveryState.Ready,
    ) = SessionPromptProjection(
        launchId = launchId,
        sessionId = sessionId,
        directory = "/repo",
        formId = "frm_1",
        schemaFingerprint = "schema-1",
        noticeTitle = "Question",
        noticeBody = "Choose one",
        questionTitle = "Next step",
        questionDescription = "Which fixture action should I run next?",
        choices = listOf(SessionPromptChoice("a", "Option A"), SessionPromptChoice("b", "Option B")),
        generation = generation,
        deliveryState = delivery,
    )

    private fun owner(
        taskId: String = "task-1",
        runtimeId: String = "runtime-1",
        epoch: Long = 7L,
        route: String = "ses_1",
        launch: Long = 11L,
    ) = PhoneTaskConversationOwner(taskId, runtimeId, epoch, route, launch)

    @Test
    fun `the current task session shows its choice with the frozen generation`() {
        val view = phoneOverlayPromptView(projection(), snapshot(), 11L)

        assertEquals("frm_1", view!!.formId)
        assertEquals(3L, view.generation)
        assertEquals("Next step", view.questionTitle)
        assertEquals("Which fixture action should I run next?", view.questionDescription)
        assertEquals(listOf("a", "b"), view.choices.map { it.value })
        assertEquals(PhoneOverlayPromptDelivery.Ready, view.delivery)
        assertTrue(view.canSubmit)
    }

    @Test
    fun `a retained task without a live lease can still show the question`() {
        // The snapshot keeps the route session; no live lease is an input here.
        assertEquals(
            PhoneOverlayPromptDelivery.Ready,
            phoneOverlayPromptView(projection(), snapshot(state = PhoneTaskState.WAITING_USER), 11L)!!.delivery,
        )
    }

    @Test
    fun `null or different route sessions never borrow another session`() {
        assertNull(phoneOverlayPromptView(projection(), snapshot(routeSession = null), 11L))
        assertNull(phoneOverlayPromptView(projection(), snapshot(routeSession = "ses_2"), 11L))
    }

    @Test
    fun `an old launch or an ended task hides the question`() {
        assertNull(phoneOverlayPromptView(projection(), snapshot(), 12L))
        assertNull(phoneOverlayPromptView(projection(launchId = null), snapshot(), 11L))
        assertNull(phoneOverlayPromptView(projection(), snapshot(state = PhoneTaskState.ENDED_PENDING_CHECK), 11L))
    }

    @Test
    fun `only a ready single choice can submit`() {
        assertTrue(phoneOverlayPromptView(projection(), snapshot(), 11L)!!.canSubmit)
        assertFalse(
            phoneOverlayPromptView(
                projection(delivery = SessionPromptDeliveryState.Sending),
                snapshot(),
                11L,
            )!!.canSubmit,
        )
        assertFalse(
            phoneOverlayPromptView(
                projection(delivery = SessionPromptDeliveryState.Unknown),
                snapshot(),
                11L,
            )!!.canSubmit,
        )
        assertFalse(
            phoneOverlayPromptView(
                projection(delivery = SessionPromptDeliveryState.Blocked),
                snapshot(),
                11L,
            )!!.canSubmit,
        )
    }

    @Test
    fun `the submit fence accepts exactly the frozen task, epoch, route and launch`() {
        assertTrue(
            phoneTaskConversationStillOwns(owner(), snapshot(), launchId = 11L, projection = projection()),
        )
    }

    @Test
    fun `a new task, new epoch, null route, old launch or ended task refuses the submit`() {
        assertFalse(
            "new task",
            phoneTaskConversationStillOwns(
                owner(),
                snapshot(key = PhoneTaskKey("task-2", "runtime-1")),
                11L,
                projection(),
            ),
        )
        assertFalse(
            "new runtime",
            phoneTaskConversationStillOwns(
                owner(),
                snapshot(key = PhoneTaskKey("task-1", "runtime-2")),
                11L,
                projection(),
            ),
        )
        assertFalse(
            "new epoch",
            phoneTaskConversationStillOwns(owner(), snapshot(epoch = 8L), 11L, projection()),
        )
        assertFalse(
            "null route",
            phoneTaskConversationStillOwns(owner(), snapshot(routeSession = null), 11L, projection()),
        )
        assertFalse(
            "old launch",
            phoneTaskConversationStillOwns(owner(), snapshot(), launchId = 12L, projection = projection()),
        )
        assertFalse(
            "ended task",
            phoneTaskConversationStillOwns(
                owner(),
                snapshot(state = PhoneTaskState.ENDED_PENDING_CHECK),
                11L,
                projection(),
            ),
        )
        assertFalse(
            "projection moved to another session",
            phoneTaskConversationStillOwns(
                owner(),
                snapshot(),
                11L,
                projection(sessionId = "ses_2"),
            ),
        )
    }

    @Test
    fun `submit collapses once and only the same answer identity may reopen on failure`() {
        val owner = owner()
        assertNull("a false submit never collapses", promptSubmitWatchFor(false, owner, "frm_1", 3L))

        val watch = promptSubmitWatchFor(true, owner, "frm_1", 3L)!!
        assertEquals("frm_1", watch.formId)
        assertEquals(3L, watch.generation)

        listOf(
            SessionPromptDeliveryState.Unknown,
            SessionPromptDeliveryState.Refused,
            SessionPromptDeliveryState.Superseded,
            SessionPromptDeliveryState.Blocked,
        ).forEach { delivery ->
            assertTrue("$delivery reopens the read-only panel", promptSubmitShouldExpand(watch, true, true, delivery))
        }
        listOf(
            SessionPromptDeliveryState.Idle,
            SessionPromptDeliveryState.Checking,
            SessionPromptDeliveryState.Ready,
            SessionPromptDeliveryState.Sending,
            SessionPromptDeliveryState.Accepted,
            SessionPromptDeliveryState.Gone,
            SessionPromptDeliveryState.Unsupported,
        ).forEach { delivery ->
            assertFalse("$delivery stays collapsed", promptSubmitShouldExpand(watch, true, true, delivery))
        }
        assertFalse("a new task must not reopen the old panel", promptSubmitShouldExpand(watch, false, true, SessionPromptDeliveryState.Unknown))
        assertFalse("an old generation/form must not reopen", promptSubmitShouldExpand(watch, true, false, SessionPromptDeliveryState.Unknown))
        assertFalse("no submit watch has nothing to reopen", promptSubmitShouldExpand(null, true, true, SessionPromptDeliveryState.Unknown))
    }

    @Test
    fun `a new task key is a hard boundary even when the route session is unchanged`() {
        val old = PhoneTaskKey("task-1", "runtime-1")
        val new = PhoneTaskKey("task-2", "runtime-1")
        val step = conversationReconcile(old, 7L, new, 8L, "ses_1")
        assertTrue(step.invalidate)
        assertNull("a new task key never keeps the old session", step.keepSession)
        assertFalse("a new task key never auto-refreshes the global pending form", step.refresh)
    }

    @Test
    fun `the same task key with a new epoch revalidates the same task only`() {
        val key = PhoneTaskKey("task-1", "runtime-1")
        val step = conversationReconcile(key, 7L, key, 8L, "ses_1")
        assertTrue(step.invalidate)
        assertEquals("ses_1", step.keepSession)
        assertTrue("the same task may recheck its own pending question", step.refresh)

        val noRoute = conversationReconcile(key, 7L, key, 8L, null)
        assertTrue(noRoute.invalidate)
        assertNull(noRoute.keepSession)
        assertFalse(noRoute.refresh)
    }

    @Test
    fun `a cleared task or an unchanged owner has no adopted question`() {
        val key = PhoneTaskKey("task-1", "runtime-1")
        val cleared = conversationReconcile(key, 7L, null, null, null)
        assertTrue(cleared.invalidate)
        assertNull(cleared.keepSession)
        assertFalse(cleared.refresh)

        assertFalse(conversationReconcile(key, 7L, key, 7L, "ses_1").invalidate)
        assertFalse(conversationReconcile(null, null, null, null, null).invalidate)
    }

    @Test
    fun `a plain notice is bound to the same task session and launch`() {
        val notice =
            SessionNoticeProjection(
                launchId = 11L,
                sessionId = "ses_1",
                kind = SessionNoticeKind.Ready,
                title = "Agent finished",
                body = "Done",
                generation = 4L,
            )
        val view = phoneOverlayNoticeView(notice, snapshot(), 11L)!!
        assertEquals(PhoneOverlayNoticeKind.Ready, view.kind)
        assertEquals("Agent finished", view.title)
        assertEquals("Done", view.body)
        assertEquals(4L, view.generation)

        assertNull(phoneOverlayNoticeView(notice, snapshot(routeSession = null), 11L))
        assertNull(phoneOverlayNoticeView(notice, snapshot(routeSession = "ses_2"), 11L))
        assertNull(phoneOverlayNoticeView(notice, snapshot(), 12L))
        assertNull(phoneOverlayNoticeView(notice.copy(launchId = null), snapshot(), 11L))
    }

    @Test
    fun `a new owner clears the notice without touching a question`() {
        val notice =
            PhoneOverlayNoticeView(
                kind = PhoneOverlayNoticeKind.Goal,
                title = "Goal",
                body = "Done",
                generation = 1L,
            )
        val base = PhoneOverlayWindowState(ownerKey = "grant-7").withSessionNotice(notice)
        assertEquals(notice, base.sessionNotice)

        val newOwner = base.withNewOwner("grant-8")
        assertNull(newOwner.sessionNotice)
    }

    @Test
    fun `a permanent lease has no countdown while a finite grant keeps its deadline`() {
        val permanent = overlayLeasePresentation(PhoneTimeLimits.PERMANENT)
        assertNull("never emit Long.MAX_VALUE as a countdown", permanent.leaseEndsAtMs)
        assertTrue(permanent.leasePermanent)

        val finite = overlayLeasePresentation(123_456L)
        assertEquals(123_456L, finite.leaseEndsAtMs)
        assertFalse(finite.leasePermanent)

        val none = overlayLeasePresentation(null)
        assertNull(none.leaseEndsAtMs)
        assertFalse(none.leasePermanent)
    }

    @Test
    fun `a new owner clears a rendered question and a prompt change bumps the revision`() {
        val base = PhoneOverlayWindowState(ownerKey = "grant-7", leaseEndsAtMs = 99_000L)
        val prompt =
            phoneOverlayPromptView(projection(), snapshot(), 11L)!!
        val withPrompt = base.withSessionPrompt(prompt)
        assertTrue(withPrompt.revision > base.revision)
        assertEquals(prompt, withPrompt.sessionPrompt)

        val newOwner = withPrompt.withNewOwner("grant-8")
        assertNull("a new native owner never inherits the old question", newOwner.sessionPrompt)
        assertFalse(newOwner.leasePermanent)
    }
}
