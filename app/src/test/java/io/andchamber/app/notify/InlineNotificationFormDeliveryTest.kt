package io.andchamber.app.notify

import io.andchamber.app.data.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification wiring of the inline form: routing, the button plan, the action identity and the
 * revision-scoped delivery. The surface is a fake that behaves like the revision gate (one shown
 * revision per id, every write needs it), so stale and replayed actions are checked without Android.
 */
class InlineNotificationFormDeliveryTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun pending(form: String): JsonObject = obj("""{"ses_1":{"forms":[$form]}}""")

    private val three =
        """{"id":"frm_1","sessionID":"ses_1","title":"Pair","fields":[
           {"key":"a","title":"Field A","type":"string","required":true,"default":"dflt-a"},
           {"key":"b","title":"Field B","type":"string"},
           {"key":"c","title":"Field C","type":"string","required":true}]}"""

    private val one =
        """{"id":"frm_1","sessionID":"ses_1","title":"One","fields":[
           {"key":"only","title":"Only","type":"string","required":true}]}"""

    private val choice =
        """{"id":"frm_1","sessionID":"ses_1","fields":[
           {"key":"pick","type":"string","options":[{"value":"x","label":"X"},{"value":"y","label":"Y"}]}]}"""

    private val withOptions =
        """{"id":"frm_1","sessionID":"ses_1","fields":[
           {"key":"a","type":"string"},
           {"key":"b","type":"string","options":[{"value":"x","label":"X"}]}]}"""

    // --- routing: plain text is inline, never the form screen ---

    @Test
    fun `plain text forms of one or many fields are answered inline`() {
        assertEquals(QuestionRoute.Inline("frm_1"), QuestionRoute.decide(pending(three), "ses_1"))
        assertEquals(QuestionRoute.Inline("frm_1"), QuestionRoute.decide(pending(one), "ses_1"))
    }

    @Test
    fun `a choice keeps its buttons and nothing else reaches Fields or Text`() {
        val route = QuestionRoute.decide(pending(choice), "ses_1")
        assertTrue(route is QuestionRoute.Classic && route.answer is FormAnswer.Choice)

        val mixed = QuestionRoute.decide(pending(withOptions), "ses_1")
        assertEquals(QuestionRoute.Classic(FormAnswer.OpenOnly), mixed)
    }

    @Test
    fun `duplicate field keys are not answered inline`() {
        val dup =
            """{"id":"frm_1","sessionID":"ses_1","fields":[
               {"key":"a","type":"string"},{"key":"a","type":"string"}]}"""
        assertEquals(QuestionRoute.Classic(FormAnswer.OpenOnly), QuestionRoute.decide(pending(dup), "ses_1"))
    }

    @Test
    fun `a failed read is unresolved and never guesses a form`() {
        assertEquals(QuestionRoute.Unresolved, QuestionRoute.decide(null, "ses_1"))
        assertEquals(QuestionRoute.NoSingleForm, QuestionRoute.decide(pending(three), null))
        assertEquals(QuestionRoute.NoSingleForm, QuestionRoute.decide(obj("""{"ses_1":{"forms":[]}}"""), "ses_1"))
        assertEquals(
            "several forms are ambiguous",
            QuestionRoute.NoSingleForm,
            QuestionRoute.decide(obj("""{"ses_1":{"forms":[$one,${one.replace("frm_1", "frm_2")}]}}"""), "ses_1"),
        )
    }

    // --- buttons: at most three, reply first, cancel only in a free slot ---

    private fun projection(
        actions: Set<InlineFormActionKind>,
        index: Int = 0,
        count: Int = 3,
    ) = InlineFormProjection(
        InlineFormOutcome.Render,
        InlineFormStatus.Editing,
        token = "tok",
        generation = 1,
        index = index,
        count = count,
        actions = actions,
    )

    @Test
    fun `never more than three buttons and reply always comes first`() {
        val all = setOf(InlineFormActionKind.Reply, InlineFormActionKind.Back, InlineFormActionKind.Skip, InlineFormActionKind.Cancel)
        val full = InlineFormLayouts.buttons(projection(all, index = 1))
        assertEquals(listOf(InlineFormButton.Answer, InlineFormButton.Back, InlineFormButton.Skip), full)

        val first = InlineFormLayouts.buttons(projection(setOf(InlineFormActionKind.Reply, InlineFormActionKind.Cancel)))
        assertEquals("cancel only when a slot is free", listOf(InlineFormButton.Answer, InlineFormButton.Cancel), first)

        val last = InlineFormLayouts.buttons(projection(all, index = 2))
        assertEquals(listOf(InlineFormButton.AnswerAndSend, InlineFormButton.Back, InlineFormButton.SkipAndSend), last)

        val single = InlineFormLayouts.buttons(projection(setOf(InlineFormActionKind.Reply, InlineFormActionKind.Cancel), count = 1))
        assertEquals("a single field is the last field", InlineFormButton.AnswerAndSend, single.first())

        val retry = InlineFormLayouts.buttons(projection(setOf(InlineFormActionKind.Retry, InlineFormActionKind.Cancel)))
        assertEquals(listOf(InlineFormButton.Retry, InlineFormButton.Cancel), retry)
        assertTrue(InlineFormLayouts.hasDismiss(projection(all)))
    }

    @Test
    fun `only the reply buttons carry input and the last step announces the send`() {
        InlineFormButton.entries.forEach { button ->
            val reply = button == InlineFormButton.Answer || button == InlineFormButton.AnswerAndSend
            assertEquals(button.name, reply, button.carriesInput)
        }
        assertTrue(InlineFormWire.mayCallNetwork(InlineFormButton.AnswerAndSend.command))
        assertTrue(InlineFormWire.mayCallNetwork(InlineFormButton.SkipAndSend.command))
        assertTrue(InlineFormWire.mayCallNetwork(InlineFormButton.Retry.command))
        assertTrue("the recovery checks pending", InlineFormWire.mayCallNetwork(InlineFormWire.RESUME))
        assertFalse(InlineFormWire.mayCallNetwork(InlineFormButton.Answer.command))
        assertFalse(InlineFormWire.mayCallNetwork(InlineFormButton.Back.command))
        assertFalse(InlineFormWire.mayCallNetwork(InlineFormButton.Skip.command))
    }

    // --- identity: every part separates PendingIntents ---

    private fun inlineSpec(
        token: String = "tok",
        generation: Long = 1,
        revision: String = "rev",
        command: String = InlineFormWire.REPLY,
        formId: String = "frm_1",
        notificationId: Int = 7,
        sessionId: String = "ses_1",
        directory: String? = "/repo",
    ) = NotificationActionIdentity.Spec(
        action = NotificationActionReceiver.ACTION_INLINE_FORM,
        notificationId = notificationId,
        sessionId = sessionId,
        directory = directory,
        formId = formId,
        token = token,
        generation = generation,
        revision = revision,
        command = command,
    )

    @Test
    fun `each identity part makes a distinct inline action`() {
        val base = NotificationActionIdentity.dataUri(inlineSpec())
        listOf(
            inlineSpec(token = "tok2"),
            inlineSpec(generation = 2),
            inlineSpec(revision = "rev2"),
            inlineSpec(command = InlineFormWire.BACK),
            inlineSpec(formId = "frm_2"),
            inlineSpec(notificationId = 8),
            inlineSpec(sessionId = "ses_2"),
            inlineSpec(directory = null),
            inlineSpec(directory = ""),
        ).forEach { other ->
            assertNotEquals(other.toString(), base, NotificationActionIdentity.dataUri(other))
        }
        assertEquals(base, NotificationActionIdentity.dataUri(inlineSpec()))
    }

    @Test
    fun `inline fields cannot be confused with permission fields or each other`() {
        val asDecision =
            NotificationActionIdentity.Spec(
                NotificationActionReceiver.ACTION_INLINE_FORM,
                7,
                "ses_1",
                requestId = "frm_1",
                decision = "rev",
            )
        val asInline = NotificationActionIdentity.Spec(NotificationActionReceiver.ACTION_INLINE_FORM, 7, "ses_1", formId = "frm_1", revision = "rev")
        assertNotEquals(NotificationActionIdentity.dataUri(asDecision), NotificationActionIdentity.dataUri(asInline))

        // A delimiter inside a token cannot fake another field.
        val forged = inlineSpec(token = "tok&gen=1")
        val honest = inlineSpec(token = "tok", generation = 1)
        assertNotEquals(NotificationActionIdentity.dataUri(forged), NotificationActionIdentity.dataUri(honest))
    }

    @Test
    fun `an older identity without inline fields keeps its exact uri`() {
        val reply = NotificationActionIdentity.Spec(NotificationActionReceiver.ACTION_REPLY, 101, "ses_1", directory = "/repo")
        assertEquals(
            "andchamber-notify://action/v1/io.andchamber.app.action.NOTIFY_REPLY/101?session=ses_1&directory=%2Frepo",
            NotificationActionIdentity.dataUri(reply),
        )
    }

    // --- delivery against a revision-gated fake surface ---

    private class FakeTransport(var form: String?) : NotificationFormTransport {
        val requests = mutableListOf<ApiRequest>()
        var pendingFails = false
        var sendStatus = 204

        override suspend fun pending(): JsonObject? {
            if (pendingFails) return null
            val f = form ?: return Json.parseToJsonElement("""{"ses_1":{"forms":[]}}""") as JsonObject
            return Json.parseToJsonElement("""{"ses_1":{"forms":[$f]}}""") as JsonObject
        }

        override suspend fun send(
            request: ApiRequest,
            directory: String?,
        ): ApiResult {
            requests += request
            return ApiResult(sendStatus, "echo: secret-value")
        }
    }

    /** One shown revision per id; every scoped write needs it, like [NotificationRevisionGate.ifCurrent]. */
    private class FakeSurface : InlineFormSurface {
        var shown: String? = null
        var latest: String? = null
        var counter = 0
        var refuseWrites = false
        /** When set, the next write updates only the local revision; the system keeps showing the old one. */
        var lagNextWrite = false
        /** When set, the next scoped step update is replaced by a newer revision instead of writing. */
        var loseRevisionOnUpdate = false
        /** Simulates a system that takes longer than the action budget to show a revision. */
        var awaitDelayMillis = 0L
        /** False after the user swiped the notification away (no active notification to update). */
        var activeExists = true
        /** The revision the user dismissed; a late result for it must not redraw anything. */
        var dismissedRevision: String? = null
        var onSendingWritten: ((String) -> Unit)? = null
        val views = mutableListOf<InlineFormView>()
        val writes = mutableListOf<String>()
        var cancelled = 0

        private fun next(kind: String): String? {
            if (refuseWrites) return null
            counter += 1
            val token = "rev$counter"
            latest = token
            if (lagNextWrite) {
                lagNextWrite = false
            } else {
                shown = token
            }
            writes += kind
            return token
        }

        override fun isInlineRevisionCurrent(
            id: Int,
            revision: String,
        ) = activeExists && owns(revision)

        private fun owns(revision: String): Boolean =
            if (latest != null) latest == revision && shown == revision else shown == revision

        private fun blocked(revision: String): Boolean = !activeExists || dismissedRevision == revision

        override fun hasNewerThan(
            id: Int,
            revision: String,
        ) = if (latest != null) latest != revision else shown != null && shown != revision

        override suspend fun awaitShown(
            id: Int,
            revision: String,
        ): Boolean {
            if (awaitDelayMillis > 0) kotlinx.coroutines.delay(awaitDelayMillis)
            return activeExists && shown == revision
        }

        override fun postInline(
            id: Int,
            channel: Channel,
            key: InlineFormKey,
            view: InlineFormView,
        ): String? {
            views += view
            return next("post")
        }

        override fun updateInlineIfRevision(
            id: Int,
            revision: String,
            key: InlineFormKey,
            view: InlineFormView,
        ): String? {
            if (loseRevisionOnUpdate) {
                loseRevisionOnUpdate = false
                counter += 1
                latest = "rev$counter"
                shown = latest
                return null
            }
            if (blocked(revision) || !owns(revision)) return null
            views += view
            return next("step")
        }

        override fun inlineSendingIfRevision(
            id: Int,
            revision: String,
            key: InlineFormKey,
            retry: Boolean,
        ): String? {
            if (blocked(revision) || !owns(revision)) return null
            val token = next(if (retry) "checking" else "sending") ?: return null
            onSendingWritten?.invoke(token)
            return token
        }

        override fun inlineDoneIfRevision(
            id: Int,
            revision: String,
            done: InlineFormDone,
        ): String? = if (blocked(revision) || !owns(revision)) null else next("done-$done")

        override fun postInlineLookup(
            id: Int,
            channel: Channel,
            sessionId: String,
            directory: String?,
        ): String? = next("lookup")

        override fun updateInlineLookupIfRevision(
            id: Int,
            revision: String,
            sessionId: String,
            directory: String?,
        ): String? = if (!owns(revision)) null else next("lookup")

        override fun inlineFallbackIfRevision(
            id: Int,
            revision: String,
            sessionId: String,
            directory: String?,
        ): String? = if (!owns(revision)) null else next("fallback")

        override fun cancelIfRevision(
            id: Int,
            token: String,
        ): Boolean {
            if (!owns(token)) return false
            shown = null
            latest = null
            cancelled += 1
            writes += "cancel"
            return true
        }

        override fun repostInlineIfLatest(
            id: Int,
            revision: String,
            key: InlineFormKey,
            view: InlineFormView,
        ): String? {
            if (blocked(revision) || latest != revision) return null
            views += view
            return next("recover")
        }

        override fun repostInlineDoneIfLatest(
            id: Int,
            revision: String,
            done: InlineFormDone,
        ): String? {
            if (blocked(revision) || latest != revision) return null
            return next("recover-$done")
        }
    }

    private class Harness(form: String?) {
        val transport = FakeTransport(form)
        val surface = FakeSurface()
        val store = InlineNotificationFormStore()
        val delivery = InlineNotificationFormDelivery(surface, transport, store)

        val current: InlineFormProjection get() = surface.views.last().projection

        fun action(
            button: InlineFormButton,
            projection: InlineFormProjection = current,
            revision: String = surface.shown!!,
        ) = NotificationActionIdentity.Spec(
            action = NotificationActionReceiver.ACTION_INLINE_FORM,
            notificationId = 7,
            sessionId = "ses_1",
            directory = "/repo",
            formId = "frm_1",
            token = projection.token,
            generation = projection.generation,
            revision = revision,
            command = button.command,
        )

        suspend fun start(formId: String = "frm_1"): InlineStart {
            val pending = transport.pending()!!
            return delivery.start(7, Channel.NeedsInput, "ses_1", "/repo", formId, pending)
        }
    }

    @Test
    fun `the first notification is already the first field and nothing is sent`() =
        runTest {
            val h = Harness(three)
            assertEquals(InlineStart.Posted, h.start())
            assertEquals(listOf("post"), h.surface.writes)
            assertEquals(0, h.current.index)
            assertEquals(3, h.current.count)
            assertEquals(InlineFormButton.Answer, InlineFormLayouts.buttons(h.current).first())
            assertTrue(h.transport.requests.isEmpty())
        }

    @Test
    fun `three fields advance locally and only the last sends the whole map once`() =
        runTest {
            val h = Harness(three)
            h.start()
            h.delivery.handle(h.action(InlineFormButton.Answer), "v-a")
            assertEquals(1, h.current.index)
            h.delivery.handle(h.action(InlineFormButton.Skip), null)
            assertEquals(2, h.current.index)
            assertTrue("intermediate steps stay local", h.transport.requests.isEmpty())

            val done = h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "v-c")

            assertEquals(1, h.transport.requests.size)
            val answer = h.transport.requests.single().body!!["answer"] as JsonObject
            assertEquals(setOf("a", "c"), answer.keys)
            assertEquals(listOf("post", "step", "step", "sending", "done-Sent"), h.surface.writes)
            assertNotNull("the done status is returned for the delayed clear", done)
            assertEquals(h.surface.shown, done)
        }

    @Test
    fun `a single text field is answered and sent from the first notification`() =
        runTest {
            val h = Harness(one)
            h.start()
            assertEquals(InlineFormButton.AnswerAndSend, InlineFormLayouts.buttons(h.current).first())
            h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "only-value")
            assertEquals(1, h.transport.requests.size)
            assertEquals(listOf("post", "sending", "done-Sent"), h.surface.writes)
        }

    @Test
    fun `a replayed or stale action changes neither the draft nor the notification`() =
        runTest {
            val h = Harness(three)
            h.start()
            val first = h.action(InlineFormButton.Answer)
            h.delivery.handle(first, "v-a")
            val writes = h.surface.writes.toList()
            val step = h.current

            // The same broadcast again: its revision is no longer shown.
            assertNull(h.delivery.handle(first, "other"))
            assertEquals(writes, h.surface.writes)
            assertEquals(step, h.current)

            // The draft did not move either: the current step still acts normally.
            h.delivery.handle(h.action(InlineFormButton.Answer), "v-b")
            assertEquals(2, h.current.index)
            assertTrue(h.transport.requests.isEmpty())
        }

    @Test
    fun `a duplicated final broadcast cannot send twice`() =
        runTest {
            val h = Harness(one)
            h.start()
            val final = h.action(InlineFormButton.AnswerAndSend)
            h.delivery.handle(final, "x")
            h.delivery.handle(final, "x")
            assertEquals(1, h.transport.requests.size)
        }

    @Test
    fun `a lost draft restarts at field one and the old text is dropped`() =
        runTest {
            val h = Harness(three)
            h.start()
            h.delivery.handle(h.action(InlineFormButton.Answer), "v-a")
            val onSecond = h.action(InlineFormButton.Answer)
            // Process death: the draft store is empty, the notification still shows field two.
            val fresh = Harness(three).also { it.surface.shown = h.surface.shown }
            fresh.surface.views += h.surface.views

            fresh.delivery.handle(onSecond, "v-b")

            assertTrue("nothing is sent", fresh.transport.requests.isEmpty())
            assertEquals("back to field one", 0, fresh.current.index)
            assertEquals(InlineFormNotice.Restarted, fresh.surface.views.last().notice)
        }

    @Test
    fun `a final send after a lost draft never submits`() =
        runTest {
            val h = Harness(one)
            h.start()
            val final = h.action(InlineFormButton.AnswerAndSend)
            val fresh = Harness(one).also { it.surface.shown = h.surface.shown }
            fresh.surface.views += h.surface.views

            fresh.delivery.handle(final, "value")

            assertTrue(fresh.transport.requests.isEmpty())
            assertEquals(InlineFormNotice.Restarted, fresh.surface.views.last().notice)
        }

    @Test
    fun `a refused refresh keeps the shown step working without replaying text`() =
        runTest {
            val h = Harness(three)
            h.start()
            val onFirst = h.action(InlineFormButton.Answer)
            h.surface.refuseWrites = true
            h.delivery.handle(onFirst, "v-a")
            // The draft advanced but the system still shows field one with its old (current) revision.
            h.surface.refuseWrites = false
            h.delivery.handle(h.action(InlineFormButton.Answer, projection = h.surface.views.first().projection), "late")

            assertEquals("the true step is shown again", 1, h.current.index)
            assertTrue(h.transport.requests.isEmpty())
            assertNull("no restart notice: the draft is intact", h.surface.views.last().notice)
        }

    @Test
    fun `cancel removes the notification and swiping drops the draft`() =
        runTest {
            val h = Harness(three)
            h.start()
            h.delivery.handle(h.action(InlineFormButton.Cancel), null)
            assertEquals(1, h.surface.cancelled)
            assertTrue(h.transport.requests.isEmpty())

            val s = Harness(three)
            s.start()
            val dismiss = s.action(InlineFormButton.Answer).copy(command = InlineFormWire.DISMISS)
            s.surface.shown = null // the user swiped it away
            s.delivery.handle(dismiss, null)
            assertEquals("the draft is gone", 0, s.store.size())
            assertTrue(s.transport.requests.isEmpty())
        }

    @Test
    fun `a swipe of a replaced notification leaves the new draft alone`() =
        runTest {
            val h = Harness(three)
            h.start()
            val oldDismiss = h.action(InlineFormButton.Answer).copy(command = InlineFormWire.DISMISS)
            h.delivery.handle(h.action(InlineFormButton.Answer), "v-a")
            h.delivery.handle(oldDismiss, null)
            assertEquals(1, h.store.size())
            h.delivery.handle(h.action(InlineFormButton.Answer), "v-b")
            assertEquals(2, h.current.index)
        }

    @Test
    fun `a failed check keeps the draft and retry is explicit`() =
        runTest {
            val h = Harness(one)
            h.start()
            h.transport.pendingFails = true
            h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "v")
            assertEquals(InlineFormStatus.CheckFailed, h.current.status)
            assertEquals(listOf(InlineFormButton.Retry, InlineFormButton.Cancel), InlineFormLayouts.buttons(h.current))
            assertTrue(h.transport.requests.isEmpty())

            h.transport.pendingFails = false
            h.delivery.handle(h.action(InlineFormButton.Retry), null)
            assertEquals(1, h.transport.requests.size)
            assertEquals("checking", h.surface.writes[h.surface.writes.size - 2])
        }

    @Test
    fun `a refusal keeps the step with a fixed status and never the server body`() =
        runTest {
            val h = Harness(one)
            h.start()
            h.transport.sendStatus = 400
            h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "v")
            assertEquals(InlineFormStatus.SendFailed, h.current.status)
            assertFalse(h.surface.views.toString().contains("secret-value"))
            assertFalse("the typed answer never reaches a view", h.surface.views.toString().contains("\"v\""))
        }

    @Test
    fun `a form answered elsewhere ends with a done status`() =
        runTest {
            val h = Harness(one)
            h.start()
            h.transport.form = null
            h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "v")
            assertEquals("done-Gone", h.surface.writes.last())
            assertTrue(h.transport.requests.isEmpty())
        }

    @Test
    fun `no view ever carries a typed value or a default`() =
        runTest {
            val h = Harness(three)
            h.start()
            h.delivery.handle(h.action(InlineFormButton.Answer), "typed-secret-1")
            h.delivery.handle(h.action(InlineFormButton.Back), null)
            h.delivery.handle(h.action(InlineFormButton.Answer), "typed-secret-2")
            h.delivery.handle(h.action(InlineFormButton.Skip), null)
            val shown = h.surface.views.toString()
            listOf("typed-secret-1", "typed-secret-2", "dflt-a").forEach { secret ->
                assertFalse(secret, shown.contains(secret))
            }
        }

    // --- ownership: only a truly lost draft restarts ---

    private val formB = three.replace("frm_1", "frm_2").replace("Field A", "B first")

    @Test
    fun `an old form's action does nothing while a newer form owns the id`() =
        runTest {
            val h = Harness(three)
            h.start()
            h.delivery.handle(h.action(InlineFormButton.Answer), "a-1")
            val oldShown = h.surface.shown!!
            val oldStep = h.current

            // Form B takes the same notification id (a new question on the same tag).
            h.transport.form = formB
            assertEquals(InlineStart.Posted, h.start("frm_2"))
            val bStep = h.current
            assertEquals(1, h.store.size())

            // A's button, replayed with B's current revision (worst case: revision check passes).
            val forged = h.action(InlineFormButton.Answer, projection = oldStep, revision = h.surface.shown!!)
            val writes = h.surface.writes.toList()
            assertNull(h.delivery.handle(forged, "late"))
            assertEquals("no step, no write, no restart", writes, h.surface.writes)
            assertEquals(bStep, h.current)
            assertNotEquals(oldShown, h.surface.shown)

            // B's draft is intact and advances normally.
            val bAction = h.action(InlineFormButton.Answer).copy(formId = "frm_2")
            h.delivery.handle(bAction, "b-1")
            assertEquals(1, h.current.index)
            assertNull(h.surface.views.last().notice)
            assertTrue(h.transport.requests.isEmpty())
        }

    @Test
    fun `a late question of an old form leaves the newer form's notification alone`() =
        runTest {
            val h = Harness(formB)
            h.start("frm_2")
            h.delivery.handle(h.action(InlineFormButton.Answer).copy(formId = "frm_2"), "b-1")
            val writes = h.surface.writes.toList()

            // A failed read for the old form A: never creates a draft over B or redraws it.
            val lateNull = h.delivery.startLookup(7, Channel.NeedsInput, "ses_1", "/repo")
            assertEquals(InlineStart.Skipped, lateNull)
            assertEquals(writes, h.surface.writes)
            assertEquals(1, h.current.index)
        }

    @Test
    fun `a duplicate action on a live draft re-renders the true step and never resets it`() =
        runTest {
            val h = Harness(three)
            h.start()
            val first = h.action(InlineFormButton.Answer)
            h.delivery.handle(h.action(InlineFormButton.Answer), "a-1")
            h.delivery.handle(h.action(InlineFormButton.Answer), "b-1")
            assertEquals(2, h.current.index)

            // An old generation arriving with the current revision (e.g. a refused refresh earlier).
            val stale = first.copy(revision = h.surface.shown!!)
            h.delivery.handle(stale, "late")

            assertEquals("still on the true step", 2, h.current.index)
            assertNull("not a restart", h.surface.views.last().notice)
            assertEquals(1, h.store.size())
            assertTrue(h.transport.requests.isEmpty())

            // The staged answers survived: the last field sends a, b and c.
            h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "c-1")
            val answer = h.transport.requests.single().body!!["answer"] as JsonObject
            assertEquals(setOf("a", "b", "c"), answer.keys)
        }

    @Test
    fun `a lost draft restart never shows sending or sends`() =
        runTest {
            val h = Harness(one)
            h.start()
            val final = h.action(InlineFormButton.AnswerAndSend)
            val fresh = Harness(one).also { it.surface.shown = h.surface.shown }
            fresh.surface.views += h.surface.views

            fresh.delivery.handle(final, "value")

            assertEquals(listOf("step"), fresh.surface.writes)
            assertTrue(fresh.transport.requests.isEmpty())
            assertEquals(InlineFormNotice.Restarted, fresh.surface.views.last().notice)
        }

    @Test
    fun `retry after an unknown result keeps unknown when the check fails`() =
        runTest {
            val h = Harness(one)
            h.start()
            h.transport.sendStatus = 503
            h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "v")
            assertEquals(InlineFormStatus.ResultUnknown, h.current.status)

            h.transport.pendingFails = true
            h.delivery.handle(h.action(InlineFormButton.Retry), null)
            assertEquals("never claims nothing was sent", InlineFormStatus.ResultUnknown, h.current.status)
            assertEquals(1, h.transport.requests.size)
        }

    @Test
    fun `the lookup retry starts the form only after a real read`() =
        runTest {
            val h = Harness(three)
            h.transport.pendingFails = true
            assertEquals(InlineStart.Posted, h.delivery.startLookup(7, Channel.NeedsInput, "ses_1", "/repo"))
            val lookup =
                NotificationActionIdentity.Spec(
                    action = NotificationActionReceiver.ACTION_INLINE_FORM,
                    notificationId = 7,
                    sessionId = "ses_1",
                    directory = "/repo",
                    revision = h.surface.shown,
                    command = InlineFormWire.LOOKUP,
                )
            h.delivery.handle(lookup, null)
            assertEquals(listOf("lookup", "lookup"), h.surface.writes)

            h.transport.pendingFails = false
            h.delivery.handle(lookup.copy(revision = h.surface.shown), null)
            assertEquals("step", h.surface.writes.last())
            assertEquals(0, h.current.index)
            assertTrue(h.transport.requests.isEmpty())

            // A form that cannot be answered inline only says so.
            val other = Harness(withOptions)
            other.transport.pendingFails = true
            other.delivery.startLookup(7, Channel.NeedsInput, "ses_1", "/repo")
            other.transport.pendingFails = false
            other.delivery.handle(lookup.copy(revision = other.surface.shown), null)
            assertEquals("fallback", other.surface.writes.last())
        }

    // --- recovery when the system lags, a newer revision wins, or the process died ---

    @Test
    fun `a system slower than the await budget still gets an actionable required-field step`() =
        runTest {
            val h = Harness(one)
            h.start()
            // "Sending…" is written locally; the system does not show it within the budget, and the
            // scoped result write therefore cannot run. Recovery must post a fresh, tappable step.
            h.surface.lagNextWrite = true
            h.surface.awaitDelayMillis = 10_000L

            h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "", budgetMillis = 1_000L)

            assertTrue("a blank required field is never sent", h.transport.requests.isEmpty())
            assertEquals(InlineFormStatus.RequiredMissing, h.current.status)
            assertEquals(listOf(InlineFormButton.AnswerAndSend, InlineFormButton.Cancel), InlineFormLayouts.buttons(h.current))
            assertEquals("recover", h.surface.writes.last())
        }

    @Test
    fun `a late swipe does not clear a newer draft on the same notification id`() =
        runTest {
            val h = Harness(three)
            h.start()
            val oldRevision = h.surface.shown!!
            val discard =
                NotificationActionIdentity.Spec(
                    action = NotificationActionReceiver.ACTION_INLINE_FORM,
                    notificationId = 7,
                    sessionId = "ses_1",
                    directory = "/repo",
                    formId = "frm_1",
                    revision = oldRevision,
                    command = InlineFormWire.DISCARD_PENDING,
                )
            // A newer write already owns the id.
            h.surface.latest = "newer"
            h.surface.shown = "newer"

            h.delivery.handle(discard, null)

            assertEquals("the newer draft survives", 1, h.store.size())
        }

    @Test
    fun `a user swipe during a pending POST never redraws the notification`() =
        runTest {
            listOf(400, 503).forEach { status ->
                val h = Harness(one)
                h.start()
                // The Sending write exists, the user swipes it, and the POST result (failed/unknown)
                // arrives afterwards. It must not redraw or re-send anything.
                h.surface.lagNextWrite = true
                h.surface.onSendingWritten = { token -> h.surface.dismissedRevision = token }
                h.transport.sendStatus = status

                h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "v")

                assertEquals("exactly the already-started POST", 1, h.transport.requests.size)
                assertEquals("no result step or recovery after the swipe", listOf("post", "sending"), h.surface.writes)
            }
        }

    @Test
    fun `recovery requires the original notification to still exist`() =
        runTest {
            val h = Harness(one)
            h.start()
            h.surface.lagNextWrite = true
            // The system removed the notification before the result write: no repost may recreate it.
            h.surface.onSendingWritten = { h.surface.activeExists = false }

            h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "")

            assertTrue(h.transport.requests.isEmpty())
            assertEquals(listOf("post", "sending"), h.surface.writes)
            assertEquals("nothing was redrawn", 1, h.surface.views.size)
        }

    @Test
    fun `a successful send whose done status cannot wait for the system still finishes visibly`() =
        runTest {
            val h = Harness(one)
            h.start()
            h.surface.lagNextWrite = true
            h.surface.awaitDelayMillis = 10_000L

            val done = h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "v", budgetMillis = 1_000L)

            assertEquals(1, h.transport.requests.size)
            assertNotNull("the finished revision is returned for the delayed clear", done)
            assertEquals("recover-Sent", h.surface.writes.last())
        }

    @Test
    fun `recovery never overwrites a newer revision on the same id`() =
        runTest {
            val h = Harness(one)
            h.start()
            val old = h.surface.shown
            // The system lags behind "Sending…", and while the required-field result is being written
            // another event takes the id. Both the scoped write and the recovery have to lose.
            h.surface.lagNextWrite = true
            h.surface.loseRevisionOnUpdate = true

            h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "")

            assertEquals("no recovery write", false, h.surface.writes.contains("recover"))
            assertNotEquals(old, h.surface.shown)
            assertEquals(h.surface.shown, h.surface.latest)
        }

    @Test
    fun `a sending notification recovery restarts field one without replaying the old answer`() =
        runTest {
            val h = Harness(three)
            h.start()
            // Process death: the notification still shows the first step, the draft store is empty.
            val fresh = InlineNotificationFormDelivery(h.surface, h.transport, InlineNotificationFormStore())
            val resume =
                NotificationActionIdentity.Spec(
                    action = NotificationActionReceiver.ACTION_INLINE_FORM,
                    notificationId = 7,
                    sessionId = "ses_1",
                    directory = "/repo",
                    formId = "frm_1",
                    revision = h.surface.shown,
                    command = InlineFormWire.RESUME,
                )

            fresh.handle(resume, null)

            assertTrue("a recovery never sends", h.transport.requests.isEmpty())
            assertEquals(0, h.current.index)
            assertEquals(InlineFormNotice.Restarted, h.surface.views.last().notice)
        }

    @Test
    fun `an unknown result is only checked by recovery, never posted again`() =
        runTest {
            val h = Harness(one)
            h.start()
            h.transport.sendStatus = 503
            h.delivery.handle(h.action(InlineFormButton.AnswerAndSend), "v")
            assertEquals(InlineFormStatus.ResultUnknown, h.current.status)
            assertEquals(1, h.transport.requests.size)

            h.transport.sendStatus = 204
            val resume =
                NotificationActionIdentity.Spec(
                    action = NotificationActionReceiver.ACTION_INLINE_FORM,
                    notificationId = 7,
                    sessionId = "ses_1",
                    directory = "/repo",
                    formId = "frm_1",
                    revision = h.surface.shown,
                    command = InlineFormWire.RESUME,
                )
            h.delivery.handle(resume, null)

            assertEquals("recovery only reads pending", 1, h.transport.requests.size)
            assertEquals(InlineFormStatus.ResultUnknown, h.current.status)
            assertEquals(listOf(InlineFormButton.Retry, InlineFormButton.Cancel), InlineFormLayouts.buttons(h.current))
        }
}
