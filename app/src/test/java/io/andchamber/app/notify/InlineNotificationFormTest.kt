package io.andchamber.app.notify

import io.andchamber.app.data.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

/** Focused business tests for the notification-bar, field-by-field form backend. */
class InlineNotificationFormTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private val threeFields =
        """{"id":"frm_1","sessionID":"ses_1","title":"Deploy","fields":[
           {"key":"env","title":"Environment","type":"string","description":"target env","required":true,"placeholder":"dev","default":"dev"},
           {"key":"branch","title":"Branch","type":"string"},
           {"key":"note","title":"Note","type":"string"}]}"""

    private val twoFields =
        """{"id":"frm_1","sessionID":"ses_1","title":"Two","fields":[
           {"key":"a","title":"A","type":"string","required":true},
           {"key":"b","title":"B","type":"string"}]}"""

    private val singleField =
        """{"id":"frm_1","sessionID":"ses_1","title":"One","fields":[
           {"key":"only","title":"Only","type":"string","required":true}]}"""

    private fun pending(
        form: String,
        sessionId: String = "ses_1",
    ): JsonObject = obj("""{"$sessionId":{"forms":[$form]}}""")

    private class FakeTransport : NotificationFormTransport {
        val pendingResults = ArrayDeque<JsonObject?>()
        val requests = mutableListOf<ApiRequest>()
        val directories = mutableListOf<String?>()
        var pendingCalls = 0
        var sendStatus = 204
        var sendBody = ""
        var pendingDelayMillis = 0L
        var sendDelayMillis = 0L

        override suspend fun pending(): JsonObject? {
            pendingCalls += 1
            if (pendingDelayMillis > 0) delay(pendingDelayMillis)
            return pendingResults.removeFirstOrNull()
        }

        override suspend fun send(
            request: ApiRequest,
            directory: String?,
        ): ApiResult {
            requests += request
            directories += directory
            if (sendDelayMillis > 0) delay(sendDelayMillis)
            return ApiResult(sendStatus, sendBody)
        }
    }

    private fun controller(
        transport: FakeTransport,
        store: InlineNotificationFormStore,
        notificationId: Int = 7,
        formId: String = "frm_1",
        budgetMillis: Long = 6_500L,
    ) = InlineNotificationFormController(
        transport = transport,
        key = InlineFormKey("ses_1", "/repo", formId, notificationId),
        store = store,
        budgetMillis = budgetMillis,
    )

    private fun reply(
        projection: InlineFormProjection,
        text: String,
    ) = InlineFormAction(projection.token!!, projection.generation, InlineFormCommand.Reply(text))

    private fun command(
        projection: InlineFormProjection,
        command: InlineFormCommand,
    ) = InlineFormAction(projection.token!!, projection.generation, command)

    private fun answers(transport: FakeTransport): Map<String, String> {
        val answer = transport.requests.single().body!!["answer"] as JsonObject
        return answer.mapValues { (_, value) -> value.toString().trim('"') }
    }

    // --- staging and the single final send ---

    @Test
    fun `three fields stage locally and the last reply sends the whole map once`() =
        runTest {
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore())

            val start = form.begin(pending(threeFields))
            assertEquals(InlineFormOutcome.Render, start.outcome)
            assertEquals(0, start.index)
            assertEquals(3, start.count)
            assertEquals("Environment", start.title)
            assertEquals("Deploy", start.formTitle)
            assertTrue(InlineFormActionKind.Reply in start.actions)
            assertFalse("required field cannot be skipped", InlineFormActionKind.Skip in start.actions)
            assertFalse(InlineFormActionKind.Back in start.actions)

            val second = form.act(reply(start, "prod"))
            assertEquals(1, second.index)
            assertTrue(InlineFormActionKind.Back in second.actions)
            assertEquals("nothing is sent for an intermediate field", 0, transport.requests.size)

            val third = form.act(reply(second, "main"))
            assertEquals(2, third.index)
            assertTrue(third.isLast)
            assertEquals(0, transport.requests.size)

            transport.pendingResults += pending(threeFields)
            val done = form.act(reply(third, "ship"))

            assertEquals(InlineFormOutcome.Sent, done.outcome)
            assertEquals(InlineFormStatus.Sent, done.status)
            assertEquals(1, transport.requests.size)
            assertEquals("POST", transport.requests.single().method)
            assertEquals("/api/session/ses_1/form/frm_1/reply", transport.requests.single().path)
            assertEquals(mapOf("env" to "prod", "branch" to "main", "note" to "ship"), answers(transport))
            assertEquals(listOf<String?>("/repo"), transport.directories)
        }

    @Test
    fun `a duplicate final reply cannot send a second time`() =
        runTest {
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore())
            val start = form.begin(pending(twoFields))
            val second = form.act(reply(start, "a1"))
            transport.pendingResults += pending(twoFields)
            val final = reply(second, "b1")

            assertEquals(InlineFormOutcome.Sent, form.act(final).outcome)
            assertEquals(1, transport.requests.size)

            assertEquals(InlineFormOutcome.Ignored, form.act(final).outcome)
            assertEquals("a replayed action does nothing", 1, transport.requests.size)
        }

    @Test
    fun `two receivers acting at once send once`() =
        runTest {
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore())
            val start = form.begin(pending(twoFields))
            val second = form.act(reply(start, "a1"))
            transport.pendingResults += pending(twoFields)
            val final = reply(second, "b1")

            val results =
                coroutineScope {
                    listOf(async { form.act(final) }, async { form.act(final) }).awaitAll()
                }

            assertEquals(1, results.count { it.outcome == InlineFormOutcome.Sent })
            assertEquals(1, results.count { it.outcome == InlineFormOutcome.Ignored })
            assertEquals("only the winning receiver reads pending", 1, transport.pendingCalls)
            assertEquals(1, transport.requests.size)
        }

    @Test
    fun `one notification's slow send does not block another notification`() =
        runTest {
            val store = InlineNotificationFormStore()
            val aStarted = CompletableDeferred<Unit>()
            val releaseA = CompletableDeferred<Unit>()
            val transportA =
                object : NotificationFormTransport {
                    override suspend fun pending(): JsonObject = pending(twoFields)

                    override suspend fun send(
                        request: ApiRequest,
                        directory: String?,
                    ): ApiResult {
                        aStarted.complete(Unit)
                        releaseA.await()
                        return ApiResult(204, "")
                    }
                }
            val a =
                InlineNotificationFormController(
                    transport = transportA,
                    key = InlineFormKey("ses_1", "/repo", "frm_1", 7),
                    store = store,
                )
            val aStart = a.begin(pending(twoFields))
            val aSecond = a.act(reply(aStart, "a1"))
            val aFinal = async { a.act(reply(aSecond, "a2")) }
            aStarted.await()

            // A different notification must not wait behind A's in-flight request.
            val transportB = FakeTransport()
            val b = controller(transportB, store, notificationId = 8)
            val bStart = b.begin(pending(twoFields))
            val bSecond = b.act(reply(bStart, "b1"))
            assertEquals("B advanced while A is still sending", 1, bSecond.index)
            assertFalse(aFinal.isCompleted)

            releaseA.complete(Unit)
            assertEquals(InlineFormOutcome.Sent, aFinal.await().outcome)
        }

    @Test
    fun `a pending check past the budget is clearly unsent`() =
        runTest {
            val transport = FakeTransport().apply { pendingDelayMillis = 10_000L }
            val form = controller(transport, InlineNotificationFormStore(), budgetMillis = 1_000L)
            val start = form.begin(pending(twoFields))
            val second = form.act(reply(start, "a1"))
            transport.pendingResults += pending(twoFields)

            val timedOut = form.act(reply(second, "b1"))

            assertEquals(InlineFormStatus.CheckFailed, timedOut.status)
            assertTrue(InlineFormActionKind.Retry in timedOut.actions)
            assertEquals("a pending timeout never sends", 0, transport.requests.size)
        }

    @Test
    fun `a send past the budget is unknown and is not retried automatically`() =
        runTest {
            val transport =
                FakeTransport().apply {
                    sendDelayMillis = 10_000L
                    sendStatus = 204
                }
            val form = controller(transport, InlineNotificationFormStore(), budgetMillis = 1_000L)
            val start = form.begin(pending(twoFields))
            val second = form.act(reply(start, "a1"))
            transport.pendingResults += pending(twoFields)
            val final = reply(second, "b1")

            val timedOut = form.act(final)

            assertEquals(InlineFormStatus.ResultUnknown, timedOut.status)
            assertTrue("an explicit retry is available", InlineFormActionKind.Retry in timedOut.actions)
            assertEquals(1, transport.requests.size)

            // Neither a replayed action nor a normal reply may resend automatically.
            assertEquals(InlineFormOutcome.Ignored, form.act(final).outcome)
            assertEquals(1, transport.requests.size)

            // Only the explicit Retry may send again, after its own pending check.
            transport.sendDelayMillis = 0L
            transport.pendingResults += pending(twoFields)
            val retried = form.act(command(timedOut, InlineFormCommand.Retry))
            assertEquals(InlineFormOutcome.Sent, retried.outcome)
            assertEquals(2, transport.requests.size)
        }

    @Test
    fun `cancellation after the send started keeps an unknown result`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val transport =
                object : NotificationFormTransport {
                    var attempts = 0

                    override suspend fun pending(): JsonObject = pending(twoFields)

                    override suspend fun send(
                        request: ApiRequest,
                        directory: String?,
                    ): ApiResult {
                        attempts += 1
                        started.complete(Unit)
                        delay(Long.MAX_VALUE)
                        return ApiResult(204, "")
                    }
                }
            val store = InlineNotificationFormStore()
            val form =
                InlineNotificationFormController(
                    transport = transport,
                    key = InlineFormKey("ses_1", "/repo", "frm_1", 7),
                    store = store,
                )
            val start = form.begin(pending(twoFields))
            val second = form.act(reply(start, "a1"))
            val job = async { form.act(reply(second, "b1")) }
            started.await()

            job.cancelAndJoin()

            val draft = store.find(7)!!
            assertEquals("a started send is never treated as unsent", InlineFormStatus.ResultUnknown, draft.status)
            assertFalse("no send is left marked in flight", draft.sending)
            assertEquals(1, transport.attempts)
        }

    // --- back/edit, skip, required, defaults and text fidelity ---

    @Test
    fun `back reopens the previous field and keeps the other staged answers`() =
        runTest {
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore())
            val start = form.begin(pending(threeFields))
            val second = form.act(reply(start, "prod"))
            val third = form.act(reply(second, "main"))
            assertEquals(2, third.index)

            val back = form.act(command(third, InlineFormCommand.Back))
            assertEquals(InlineFormOutcome.Render, back.outcome)
            assertEquals(1, back.index)
            assertEquals("Branch", back.title)

            val edited = form.act(reply(back, "main2"))
            assertEquals(2, edited.index)
            transport.pendingResults += pending(threeFields)
            val done = form.act(reply(edited, "ship"))

            assertEquals(InlineFormOutcome.Sent, done.outcome)
            assertEquals(mapOf("env" to "prod", "branch" to "main2", "note" to "ship"), answers(transport))
        }

    @Test
    fun `back on the first field changes nothing`() =
        runTest {
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore())
            val start = form.begin(pending(threeFields))

            val back = form.act(command(start, InlineFormCommand.Back))

            assertEquals(InlineFormOutcome.Ignored, back.outcome)
            assertEquals(0, transport.requests.size)
        }

    @Test
    fun `skip omits an optional field and skip on a required field sends nothing`() =
        runTest {
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore())
            val start = form.begin(pending(threeFields))
            val second = form.act(reply(start, "prod"))
            assertTrue("optional field can be skipped", InlineFormActionKind.Skip in second.actions)

            val third = form.act(command(second, InlineFormCommand.Skip))
            assertEquals(2, third.index)
            transport.pendingResults += pending(threeFields)
            val done = form.act(reply(third, "ship"))

            assertEquals(InlineFormOutcome.Sent, done.outcome)
            assertFalse("the skipped optional field is absent", "branch" in answers(transport))
            assertEquals(mapOf("env" to "prod", "note" to "ship"), answers(transport))

            val requiredTransport = FakeTransport()
            val required = controller(requiredTransport, InlineNotificationFormStore())
            val requiredStart = required.begin(pending(twoFields))
            val refused = required.act(command(requiredStart, InlineFormCommand.Skip))

            assertEquals(InlineFormOutcome.Render, refused.outcome)
            assertEquals(InlineFormStatus.RequiredMissing, refused.status)
            assertEquals("a required skip does not advance", 0, refused.index)
            assertEquals(0, requiredTransport.requests.size)
        }

    @Test
    fun `defaults are kept and user text is not trimmed`() =
        runTest {
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore())
            val start = form.begin(pending(threeFields))

            // Empty reply on a required field with a default: the default is used and the step advances.
            val second = form.act(reply(start, ""))
            assertEquals(1, second.index)

            val third = form.act(reply(second, "  main  "))
            transport.pendingResults += pending(threeFields)
            val done = form.act(reply(third, ""))

            assertEquals(InlineFormOutcome.Sent, done.outcome)
            val sent = answers(transport)
            assertEquals("default is preserved", "dev", sent["env"])
            assertEquals("whitespace is not trimmed", "  main  ", sent["branch"])
            assertFalse("an empty optional value is omitted", "note" in sent)
        }

    @Test
    fun `a blank required value advances nothing and sends nothing`() =
        runTest {
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore())
            val start = form.begin(pending(twoFields))

            val missing = form.act(reply(start, "   "))

            assertEquals(InlineFormStatus.RequiredMissing, missing.status)
            assertEquals(0, missing.index)
            assertEquals(0, transport.requests.size)
            assertEquals(0, transport.pendingCalls)
        }

    // --- identity, TTL, process loss and schema changes ---

    @Test
    fun `a stale form read cannot delete a newer draft on the same notification id`() =
        runTest {
            val transport = FakeTransport()
            val store = InlineNotificationFormStore()
            val formA =
                """{"id":"frm_A","sessionID":"ses_1","title":"A","fields":[
                   {"key":"a","title":"A","type":"string","required":true},
                   {"key":"b","title":"B","type":"string"}]}"""
            val formB =
                """{"id":"frm_B","sessionID":"ses_1","title":"B","fields":[
                   {"key":"x","title":"X","type":"string","required":true},
                   {"key":"y","title":"Y","type":"string"}]}"""
            val a = controller(transport, store, notificationId = 7, formId = "frm_A")
            val b = controller(transport, store, notificationId = 7, formId = "frm_B")

            assertEquals(0, a.begin(pending(formA)).index)
            val bStart = b.begin(pending(formB)) // the new form replaces the old draft
            assertEquals("frm_B", store.find(7)?.key?.formId)

            // A late refresh for the old form must not delete B's draft on the shared notification id.
            val stale = a.begin(pending(formB))
            assertEquals(InlineFormOutcome.Ignored, stale.outcome)
            assertEquals("frm_B", store.find(7)?.key?.formId)

            // A failed read by the old identity must not overwrite B with a placeholder either.
            val failed = a.begin(null)
            assertEquals(InlineFormOutcome.Ignored, failed.outcome)
            assertEquals("frm_B", store.find(7)?.key?.formId)
            assertEquals(0, transport.requests.size)

            val bNext = b.act(reply(bStart, "x"))
            assertEquals(1, bNext.index)
        }

    @Test
    fun `new and old notification drafts are isolated and a new form overwrites the old one`() =
        runTest {
            val transport = FakeTransport()
            val store = InlineNotificationFormStore()
            val old = controller(transport, store, notificationId = 7)
            val new = controller(transport, store, notificationId = 8)

            val oldStart = old.begin(pending(twoFields))
            val newStart = new.begin(pending(twoFields))
            assertNotEquals(oldStart.token, newStart.token)

            val oldSecond = old.act(reply(oldStart, "old-a"))
            assertEquals(1, oldSecond.index)
            // The other notification's draft is untouched.
            val newAgain = new.begin(pending(twoFields))
            assertEquals(0, newAgain.index)

            // A different form on the same notification id replaces the old draft entirely.
            val otherForm =
                """{"id":"frm_2","sessionID":"ses_1","title":"Other","fields":[
                   {"key":"x","title":"X","type":"string","required":true},
                   {"key":"y","title":"Y","type":"string"}]}"""
            val other = controller(transport, store, notificationId = 7, formId = "frm_2")
            val replaced = other.begin(pending(otherForm))
            assertEquals(0, replaced.index)
            assertEquals("Other", replaced.formTitle)

            val stale = old.act(reply(oldSecond, "should-not-send"))
            assertEquals(InlineFormOutcome.Ignored, stale.outcome)
            assertEquals(0, transport.requests.size)
        }

    @Test
    fun `an expired draft cannot act and a fresh process starts at the first field`() =
        runTest {
            var now = 1_000L
            val transport = FakeTransport()
            val store = InlineNotificationFormStore(ttlMillis = 10 * 60_000L, now = { now })
            val form = controller(transport, store)
            val start = form.begin(pending(twoFields))
            val second = form.act(reply(start, "old-a"))
            assertEquals(1, second.index)

            now += 10 * 60_000L + 1
            val stale = form.act(reply(second, "old-b"))
            assertEquals(InlineFormOutcome.Ignored, stale.outcome)
            assertEquals(0, transport.requests.size)

            // Process loss: a new store has no answers; begin reads pending and starts again at field one.
            val freshStore = InlineNotificationFormStore(now = { now })
            val fresh = controller(transport, freshStore)
            val restarted = fresh.begin(pending(twoFields))
            assertEquals(0, restarted.index)
            assertEquals("A", restarted.title)
            assertEquals(0, transport.requests.size)

            val freshSecond = fresh.act(reply(restarted, "new-a"))
            transport.pendingResults += pending(twoFields)
            val done = fresh.act(reply(freshSecond, "new-b"))
            assertEquals(InlineFormOutcome.Sent, done.outcome)
            assertEquals(mapOf("a" to "new-a", "b" to "new-b"), answers(transport))
            assertFalse("old answers are not restored", answers(transport).containsValue("old-a"))
        }

    @Test
    fun `a sending draft is never expired even when its TTL has passed`() {
        var now = 0L
        val store = InlineNotificationFormStore(ttlMillis = 1_000L, now = { now })
        val form = PendingForms.inlineForm(pending(twoFields), "ses_1", "frm_1")!!
        val draft =
            store.newDraft(
                InlineFormKey("ses_1", "/repo", "frm_1", 7),
                "t1",
                form,
                InlineFormStatus.ResultUnknown,
            )!!
        draft.sending = true

        now = 10_000L
        assertNotNull("find must not expire an in-flight draft", store.find(7))

        draft.sending = false
        assertNull("once idle, the TTL applies again", store.find(7))
    }

    @Test
    fun `a send longer than the TTL keeps its unknown result for an explicit retry`() =
        runTest {
            var now = 0L
            val store = InlineNotificationFormStore(ttlMillis = 1_000L, now = { now })
            var sends = 0
            val transport =
                object : NotificationFormTransport {
                    override suspend fun pending(): JsonObject? = pending(twoFields)

                    override suspend fun send(
                        request: ApiRequest,
                        directory: String?,
                    ): ApiResult {
                        sends += 1
                        now = 10_000L // the request outlives the TTL
                        return ApiResult(503, "")
                    }
                }
            val form =
                InlineNotificationFormController(
                    transport = transport,
                    key = InlineFormKey("ses_1", "/repo", "frm_1", 7),
                    store = store,
                )
            val start = form.begin(pending(twoFields))
            val second = form.act(reply(start, "a1"))
            val unknown = form.act(reply(second, "b1"))
            assertEquals(InlineFormStatus.ResultUnknown, unknown.status)
            assertEquals(1, sends)

            // The completed send touched the draft, so the explicit Retry can still read it.
            val retried = form.act(command(unknown, InlineFormCommand.Retry))
            assertEquals(InlineFormStatus.ResultUnknown, retried.status)
            assertEquals(2, sends)
        }

    @Test
    fun `a pending read failure keeps the draft and retry sends explicitly`() =
        runTest {
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore())
            val start = form.begin(pending(twoFields))
            val second = form.act(reply(start, "a1"))

            // No pending result queued: the check fails.
            val failed = form.act(reply(second, "b1"))
            assertEquals(InlineFormOutcome.Render, failed.outcome)
            assertEquals(InlineFormStatus.CheckFailed, failed.status)
            assertTrue(InlineFormActionKind.Retry in failed.actions)
            assertEquals("a failed check never sends", 0, transport.requests.size)

            // Re-posting from the same form keeps the explicit Retry: it must not become a fresh Reply.
            val reread = form.begin(pending(twoFields))
            assertEquals(InlineFormStatus.CheckFailed, reread.status)
            assertTrue(InlineFormActionKind.Retry in reread.actions)
            assertFalse(InlineFormActionKind.Reply in reread.actions)

            val stillFailed = form.act(command(failed, InlineFormCommand.Retry))
            assertEquals(InlineFormStatus.CheckFailed, stillFailed.status)
            assertEquals(0, transport.requests.size)

            transport.pendingResults += pending(twoFields)
            val retried = form.act(command(stillFailed, InlineFormCommand.Retry))
            assertEquals(InlineFormOutcome.Sent, retried.outcome)
            assertEquals(1, transport.requests.size)
            assertEquals(mapOf("a" to "a1", "b" to "b1"), answers(transport))
        }

    @Test
    fun `a changed schema is never sent and restarts at the first field`() =
        runTest {
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore())
            val start = form.begin(pending(twoFields))
            val second = form.act(reply(start, "old-a"))
            val changed =
                """{"id":"frm_1","sessionID":"ses_1","title":"Two","fields":[
                   {"key":"a","title":"A","type":"string","required":true},
                   {"key":"b","title":"B","type":"string"},
                   {"key":"c","title":"C","type":"string","required":true}]}"""
            transport.pendingResults += pending(changed)

            val refused = form.act(reply(second, "old-b"))
            assertEquals(InlineFormStatus.SchemaChanged, refused.status)
            assertEquals(0, refused.index)
            assertEquals(3, refused.count)
            assertEquals("nothing is sent for the old schema", 0, transport.requests.size)

            val step2 = form.act(reply(refused, "new-a"))
            val step3 = form.act(reply(step2, "new-b"))
            transport.pendingResults += pending(changed)
            val done = form.act(reply(step3, "new-c"))
            assertEquals(InlineFormOutcome.Sent, done.outcome)
            assertEquals(mapOf("a" to "new-a", "b" to "new-b", "c" to "new-c"), answers(transport))
            assertFalse(answers(transport).containsValue("old-a"))
        }

    // --- send outcomes ---

    @Test
    fun `a 5xx or an unanswered request is unknown and never retried automatically`() =
        runTest {
            listOf(503, 0).forEach { status ->
                val transport = FakeTransport().apply { sendStatus = status }
                val form = controller(transport, InlineNotificationFormStore())
                val start = form.begin(pending(twoFields))
                val second = form.act(reply(start, "a1"))
                transport.pendingResults += pending(twoFields)
                val final = reply(second, "b1")

                val unknown = form.act(final)
                assertEquals("$status is unknown", InlineFormStatus.ResultUnknown, unknown.status)
                assertEquals(1, transport.requests.size)
                assertTrue(InlineFormActionKind.Retry in unknown.actions)
                assertFalse("no direct resend action", InlineFormActionKind.Reply in unknown.actions)

                assertEquals(InlineFormOutcome.Ignored, form.act(reply(unknown, "direct-reply")).outcome)
                assertEquals(InlineFormOutcome.Ignored, form.act(final).outcome)
                assertEquals("no automatic retry", 1, transport.requests.size)

                transport.sendStatus = 204
                transport.pendingResults += pending(twoFields)
                val retried = form.act(command(unknown, InlineFormCommand.Retry))
                assertEquals("an explicit retry may send again", InlineFormOutcome.Sent, retried.outcome)
                assertEquals(2, transport.requests.size)
            }
        }

    @Test
    fun `an unknown result stays unknown when its retry check cannot be read`() =
        runTest {
            val transport = FakeTransport().apply { sendStatus = 503 }
            val form = controller(transport, InlineNotificationFormStore())
            val start = form.begin(pending(twoFields))
            val second = form.act(reply(start, "a1"))
            transport.pendingResults += pending(twoFields)
            val unknown = form.act(reply(second, "b1"))
            assertEquals(InlineFormStatus.ResultUnknown, unknown.status)

            // No pending result queued: the retry's check fails. It must not claim "nothing was sent".
            val stillUnknown = form.act(command(unknown, InlineFormCommand.Retry))
            assertEquals(InlineFormStatus.ResultUnknown, stillUnknown.status)
            assertTrue(InlineFormActionKind.Retry in stillUnknown.actions)
            assertEquals(1, transport.requests.size)

            transport.sendStatus = 204
            transport.pendingResults += pending(twoFields)
            val retried = form.act(command(stillUnknown, InlineFormCommand.Retry))
            assertEquals(InlineFormOutcome.Sent, retried.outcome)
            assertEquals(2, transport.requests.size)
        }

    @Test
    fun `a refusal keeps the draft without echoing the server body`() =
        runTest {
            val transport = FakeTransport().apply {
                sendStatus = 400
                sendBody = """{"error":"refused","answer":{"a":"a1","b":"b1"}}"""
            }
            val form = controller(transport, InlineNotificationFormStore())
            val start = form.begin(pending(twoFields))
            val second = form.act(reply(start, "a1"))
            transport.pendingResults += pending(twoFields)
            val refused = form.act(reply(second, "b1"))

            assertEquals(InlineFormOutcome.Render, refused.outcome)
            assertEquals(InlineFormStatus.SendFailed, refused.status)
            assertEquals(1, transport.requests.size)
            assertFalse("the body never becomes display text", refused.toString().contains("refused"))
        }

    // --- local cancel, privacy, one-field forms, fallback and capacity ---

    @Test
    fun `cancel only clears the local interaction`() =
        runTest {
            val transport = FakeTransport()
            val store = InlineNotificationFormStore()
            val form = controller(transport, store)
            val start = form.begin(pending(threeFields))

            val cancelled = form.act(command(start, InlineFormCommand.Cancel))

            assertEquals(InlineFormOutcome.Dismissed, cancelled.outcome)
            assertEquals(InlineFormStatus.Cancelled, cancelled.status)
            assertEquals(0, transport.pendingCalls)
            assertEquals(0, transport.requests.size)
            assertEquals(0, store.size())
            assertEquals(InlineFormOutcome.Ignored, form.act(reply(start, "ignored")).outcome)
        }

    @Test
    fun `no staged answer ever appears in a projection or a command toString`() =
        runTest {
            val secret = "sk-live-424242"
            val transport = FakeTransport().apply {
                sendStatus = 503
                sendBody = """{"answer":{"a":"$secret"}}"""
            }
            val store = InlineNotificationFormStore()
            val form = controller(transport, store)
            val start = form.begin(pending(twoFields))
            val step = form.act(reply(start, secret))
            assertFalse("the step projection is value-free", step.toString().contains(secret))
            assertFalse("the stored draft is value-free", store.find(7)!!.toString().contains(secret))
            assertFalse("the reply command is redacted", InlineFormCommand.Reply(secret).toString().contains(secret))
            assertFalse(InlineFormAction(start.token!!, start.generation, InlineFormCommand.Reply(secret)).toString().contains(secret))

            transport.pendingResults += pending(twoFields)
            val unknown = form.act(reply(step, secret))
            assertEquals(InlineFormStatus.ResultUnknown, unknown.status)
            assertFalse(unknown.toString().contains(secret))
        }

    @Test
    fun `a single text field uses the same one-step flow`() =
        runTest {
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore())

            val start = form.begin(pending(singleField))
            assertEquals(1, start.count)
            assertEquals(0, start.index)
            assertTrue(start.isLast)
            assertFalse(InlineFormActionKind.Back in start.actions)

            transport.pendingResults += pending(singleField)
            val done = form.act(reply(start, "hello"))

            assertEquals(InlineFormOutcome.Sent, done.outcome)
            assertEquals(mapOf("only" to "hello"), answers(transport))
        }

    @Test
    fun `a custom q0 with non-empty options is free text and sends once`() =
        runTest {
            val q0 =
                """{"id":"frm_q","sessionID":"ses_1","title":"Questions","fields":[
                   {"key":"q0","title":"Which three values?","type":"string","required":true,
                    "options":[{"value":"a","label":"A"},{"value":"b","label":"B"}],"custom":true}]}"""
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore(), formId = "frm_q")

            val start = form.begin(pending(q0))
            assertEquals(InlineFormOutcome.Render, start.outcome)
            assertEquals(0, start.index)
            assertEquals(1, start.count)
            assertTrue(InlineFormActionKind.Reply in start.actions)

            transport.pendingResults += pending(q0)
            val done = form.act(reply(start, "merge a + b + c"))

            assertEquals(InlineFormOutcome.Sent, done.outcome)
            assertEquals(mapOf("q0" to "merge a + b + c"), answers(transport))
        }

    @Test
    fun `three custom question fields with options are staged one by one`() =
        runTest {
            val questions =
                """{"id":"frm_q","sessionID":"ses_1","title":"Questions","fields":[
                   {"key":"q0","title":"Q0","type":"string","required":true,
                    "options":[{"value":"a","label":"A"}],"custom":true},
                   {"key":"q1","title":"Q1","type":"string","required":true,
                    "options":[],"custom":true},
                   {"key":"q2","title":"Q2","type":"string",
                    "options":[{"value":"c","label":"C"}],"custom":true}]}"""
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore(), formId = "frm_q")

            // The app's own Fields classification must not start rendering options inline.
            assertEquals(FormAnswer.OpenOnly, PendingForms.classifyForm(obj(questions)))

            val start = form.begin(pending(questions))
            assertEquals(0, start.index)
            assertEquals(3, start.count)
            val second = form.act(reply(start, "one"))
            assertEquals(1, second.index)
            val third = form.act(reply(second, "two"))
            assertEquals(2, third.index)
            assertEquals(0, transport.requests.size)

            transport.pendingResults += pending(questions)
            val done = form.act(reply(third, "three"))

            assertEquals(InlineFormOutcome.Sent, done.outcome)
            assertEquals(mapOf("q0" to "one", "q1" to "two", "q2" to "three"), answers(transport))
        }

    @Test
    fun `options without explicit custom true are refused inline and keep the choice fallback`() =
        runTest {
            val customFalse =
                """{"id":"frm_q","sessionID":"ses_1","title":"Closed","fields":[
                   {"key":"q0","title":"Q0","type":"string","required":true,
                    "options":[{"value":"a","label":"A"},{"value":"b","label":"B"}],"custom":false}]}"""
            val customMissing =
                """{"id":"frm_q","sessionID":"ses_1","title":"Closed","fields":[
                   {"key":"q0","title":"Q0","type":"string","required":true,
                    "options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}]}"""
            val transport = FakeTransport()
            val store = InlineNotificationFormStore()
            val form = controller(transport, store, formId = "frm_q")

            listOf(customFalse, customMissing).forEach { raw ->
                assertNull(PendingForms.inlineForm(pending(raw), "ses_1", "frm_q"))
                assertTrue(
                    "a valid closed choice keeps the existing Choice fallback",
                    PendingForms.classifyForm(obj(raw)) is FormAnswer.Choice,
                )
                assertEquals(InlineFormOutcome.Fallback, form.begin(pending(raw)).outcome)
                assertEquals(0, store.size())
            }
            assertEquals(0, transport.requests.size)
        }

    @Test
    fun `empty options are free text only with explicit custom true`() =
        runTest {
            val customTrue =
                """{"id":"frm_q","sessionID":"ses_1","title":"Custom","fields":[
                   {"key":"q0","title":"Q0","type":"string","required":true,
                    "options":[],"custom":true}]}"""
            val customFalse =
                """{"id":"frm_q","sessionID":"ses_1","title":"Closed","fields":[
                   {"key":"q0","title":"Q0","type":"string","required":true,
                    "options":[],"custom":false}]}"""

            val openTransport = FakeTransport()
            val open = controller(openTransport, InlineNotificationFormStore(), formId = "frm_q")
            val start = open.begin(pending(customTrue))
            assertEquals(InlineFormOutcome.Render, start.outcome)
            openTransport.pendingResults += pending(customTrue)
            val done = open.act(reply(start, "free text"))
            assertEquals(InlineFormOutcome.Sent, done.outcome)
            assertEquals(mapOf("q0" to "free text"), answers(openTransport))

            // An empty non-custom options array is a closed choice, not free text.
            assertNull(PendingForms.inlineForm(pending(customFalse), "ses_1", "frm_q"))
            assertEquals(FormAnswer.OpenOnly, PendingForms.classifyForm(obj(customFalse)))
            val closedTransport = FakeTransport()
            val closed = controller(closedTransport, InlineNotificationFormStore(), formId = "frm_q")
            assertEquals(InlineFormOutcome.Fallback, closed.begin(pending(customFalse)).outcome)
            assertEquals(0, closedTransport.requests.size)
        }

    @Test
    fun `a last-step change to a closed choice is refused before POST`() =
        runTest {
            val openFields =
                """{"id":"frm_q","sessionID":"ses_1","title":"Questions","fields":[
                   {"key":"q0","title":"Q0","type":"string","required":true,"options":[],"custom":true},
                   {"key":"q1","title":"Q1","type":"string","required":true,"options":[],"custom":true}]}"""
            val closedFields =
                """{"id":"frm_q","sessionID":"ses_1","title":"Questions","fields":[
                   {"key":"q0","title":"Q0","type":"string","required":true,"options":[],"custom":true},
                   {"key":"q1","title":"Q1","type":"string","required":true,
                    "options":[{"value":"a","label":"A"}],"custom":false}]}"""
            val transport = FakeTransport()
            val store = InlineNotificationFormStore()
            val form = controller(transport, store, formId = "frm_q")
            val start = form.begin(pending(openFields))
            val second = form.act(reply(start, "one"))
            assertEquals(1, second.index)

            transport.pendingResults += pending(closedFields)
            val refused = form.act(reply(second, "two"))

            assertEquals(InlineFormOutcome.Fallback, refused.outcome)
            assertEquals("the old map must not be sent", 0, transport.requests.size)
            assertEquals("the inline draft is gone", 0, store.size())
        }

    @Test
    fun `a pattern change that stays inline-answerable restarts without POST`() =
        runTest {
            val before =
                """{"id":"frm_q","sessionID":"ses_1","title":"Questions","fields":[
                   {"key":"q0","title":"Q0","type":"string","required":true,"options":[],"custom":true},
                   {"key":"q1","title":"Q1","type":"string","required":true,"options":[],"custom":true}]}"""
            val after =
                """{"id":"frm_q","sessionID":"ses_1","title":"Questions","fields":[
                   {"key":"q0","title":"Q0","type":"string","required":true,"options":[],"custom":true},
                   {"key":"q1","title":"Q1","type":"string","required":true,"options":[],"custom":true,
                    "pattern":"^[a-z]+$"}]}"""
            val transport = FakeTransport()
            val form = controller(transport, InlineNotificationFormStore(), formId = "frm_q")
            val start = form.begin(pending(before))
            val second = form.act(reply(start, "one"))
            assertEquals(1, second.index)

            transport.pendingResults += pending(after)
            val restarted = form.act(reply(second, "two"))

            assertEquals(InlineFormStatus.SchemaChanged, restarted.status)
            assertEquals(0, restarted.index)
            assertEquals(0, transport.requests.size)
        }

    @Test
    fun `illegal options or custom types are refused inline`() {
        val invalid =
            listOf(
                """{"id":"frm_q","sessionID":"ses_1","fields":[
                   {"key":"q0","type":"string","options":"not-an-array","custom":true}]}""",
                """{"id":"frm_q","sessionID":"ses_1","fields":[
                   {"key":"q0","type":"string","options":[{"value":"a","label":"A"}],"custom":"true"}]}""",
                """{"id":"frm_q","sessionID":"ses_1","fields":[
                   {"key":"q0","type":"string","options":[{"value":"a","label":"A"}],"custom":1}]}""",
                """{"id":"frm_q","sessionID":"ses_1","fields":[
                   {"key":"q0","type":"string","options":[],"custom":null}]}""",
            )

        invalid.forEach { raw ->
            assertNull(raw, PendingForms.inlineForm(pending(raw), "ses_1", "frm_q"))
        }
    }

    @Test
    fun `duplicate field keys fall back instead of overwriting one answer`() =
        runTest {
            val transport = FakeTransport()
            val store = InlineNotificationFormStore()
            val duplicateKeys =
                """{"id":"frm_1","sessionID":"ses_1","title":"Duplicate","fields":[
                   {"key":"same","title":"One","type":"string","required":true},
                   {"key":"same","title":"Two","type":"string"}]}"""

            assertNull(PendingForms.inlineForm(pending(duplicateKeys), "ses_1", "frm_1"))

            val start = controller(transport, store).begin(pending(duplicateKeys))
            assertEquals(InlineFormOutcome.Fallback, start.outcome)
            assertEquals(0, store.size())
            assertEquals(0, transport.requests.size)
        }

    @Test
    fun `a form with options or a conditional field falls back instead of bypassing privacy rules`() =
        runTest {
            val transport = FakeTransport()
            val store = InlineNotificationFormStore()
            val form = controller(transport, store)
            val complexForms =
                listOf(
                    """{"id":"frm_1","sessionID":"ses_1","title":"Pick","fields":[
                       {"key":"pick","title":"Pick","type":"string","options":[{"value":"x","label":"X"}]}]}""",
                    """{"id":"frm_1","sessionID":"ses_1","title":"Conditional","fields":[
                       {"key":"a","title":"A","type":"string","when":{"field":"b","equals":"yes"}},
                       {"key":"b","title":"B","type":"string"}]}""",
                    """{"id":"frm_1","sessionID":"ses_1","title":"Hidden","fields":[
                       {"key":"a","title":"A","type":"string","hidden":true},
                       {"key":"b","title":"B","type":"string"}]}""",
                )

            complexForms.forEach { raw ->
                val start = form.begin(pending(raw))
                assertEquals(InlineFormOutcome.Fallback, start.outcome)
                assertEquals(InlineFormStatus.Unavailable, start.status)
                assertEquals(0, store.size())
            }
            assertEquals(0, transport.requests.size)
        }

    @Test
    fun `capacity never evicts a sending draft`() {
        val store = InlineNotificationFormStore(capacity = 1)
        val form = PendingForms.inlineForm(pending(twoFields), "ses_1", "frm_1")!!
        val sending = store.newDraft(InlineFormKey("ses_1", "/repo", "frm_1", 7), "t1", form, InlineFormStatus.Editing)!!
        sending.sending = true

        assertNull("an in-flight draft is not evicted", store.newDraft(InlineFormKey("ses_1", "/repo", "frm_2", 8), "t2", form, InlineFormStatus.Editing))
        assertNotNull(store.find(7))

        sending.sending = false
        assertNotNull(store.newDraft(InlineFormKey("ses_1", "/repo", "frm_2", 8), "t2", form, InlineFormStatus.Editing))
        assertEquals(1, store.size())
        assertNull("the idle draft was evicted", store.find(7))
        assertNotNull(store.find(8))
    }
}
