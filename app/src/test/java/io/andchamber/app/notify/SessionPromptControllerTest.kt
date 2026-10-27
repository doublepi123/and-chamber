package io.andchamber.app.notify

import io.andchamber.app.data.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone overlay's notification question: one pending single-choice form, one manual answer, a
 * re-read before the one send, and no automatic retry after an unknown result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionPromptControllerTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private val choiceForm =
        """{"id":"frm_1","sessionID":"ses_1","title":"Questions",
           "metadata":{"kind":"question","tool":{"messageID":"msg_1","id":"tool_1"}},"fields":[
           {"key":"answer","title":"Next step","description":"Which fixture action should I run next?",
            "type":"string","custom":true,"options":[
             {"value":"a","label":"Option A"},{"value":"b","label":"Option B"}]}]}"""

    private val changedForm =
        """{"id":"frm_1","sessionID":"ses_1","title":"Questions",
           "metadata":{"kind":"question","tool":{"messageID":"msg_1","id":"tool_1"}},"fields":[
           {"key":"answer","title":"Next step","description":"Which fixture action should I run next?",
            "type":"string","custom":true,"options":[
             {"value":"a","label":"Option A"},{"value":"b","label":"Option B"},
             {"value":"c","label":"Option C"}]}]}"""

    private val metadataChangedForm =
        """{"id":"frm_1","sessionID":"ses_1","title":"Questions",
           "metadata":{"kind":"question","tool":{"messageID":"msg_1","id":"tool_2"}},"fields":[
           {"key":"answer","title":"Next step","description":"Which fixture action should I run next?",
            "type":"string","custom":true,"options":[
             {"value":"a","label":"Option A"},{"value":"b","label":"Option B"}]}]}"""

    private val secondForm =
        """{"id":"frm_2","sessionID":"ses_1","title":"Questions",
           "metadata":{"kind":"question","tool":{"messageID":"msg_2","id":"tool_2"}},"fields":[
           {"key":"answer","title":"Next step","description":"Which fixture action should I run next?",
            "type":"string","custom":true,"options":[
             {"value":"a","label":"Option A"},{"value":"b","label":"Option B"}]}]}"""

    private fun pendingState(
        form: String = choiceForm,
        sessionId: String = "ses_1",
    ): JsonObject = obj("""{"$sessionId":{"forms":[$form]}}""")

    private fun question(
        sessionId: String = "ses_1",
        directory: String? = "/repo",
        body: String = "Choose one",
    ) = DesktopNotification(
        kind = DesktopNotification.Kind.Question,
        title = "Question",
        body = body,
        tag = "question-$sessionId",
        sessionId = sessionId,
        directory = directory,
    )

    private fun notice(
        kind: DesktopNotification.Kind,
        sessionId: String = "ses_1",
        title: String = "Notice",
        body: String = "Some notice",
    ) = DesktopNotification(
        kind = kind,
        title = title,
        body = body,
        tag = "notice-$sessionId",
        sessionId = sessionId,
        directory = "/repo",
    )

    private class FakeTransport : SessionPromptTransport {
        val pendingResults = ArrayDeque<JsonObject?>()
        var sendResult: ApiResult = ApiResult(204, "")
        val requests = mutableListOf<ApiRequest>()
        val directories = mutableListOf<String?>()
        var pendingCalls = 0
        var sendGate: CompletableDeferred<Unit>? = null

        override suspend fun pending(): JsonObject? {
            pendingCalls += 1
            return if (pendingResults.isEmpty()) null else pendingResults.removeFirst()
        }

        override suspend fun send(
            request: ApiRequest,
            directory: String?,
        ): ApiResult {
            requests += request
            directories += directory
            sendGate?.await()
            return sendResult
        }
    }

    private fun TestScope.controller(
        transport: FakeTransport,
        launch: () -> Long? = { 7L },
        taskSession: () -> String? = { null },
    ) = SessionPromptController(
        transport = transport,
        currentLaunchId = launch,
        currentTaskSession = taskSession,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
    )

    @Test
    fun `a same-session single choice is shown and answered with exactly one form reply`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState()
            }
        val controller = controller(transport)

        controller.onNotification(question())
        advanceUntilIdle()

        val projection = controller.projection.value
        assertEquals(SessionPromptDeliveryState.Ready, projection.deliveryState)
        assertEquals("frm_1", projection.formId)
        assertEquals(listOf("a", "b"), projection.choices.map { it.value })
        assertEquals("Option A", projection.choices.first().label)
        assertEquals("Next step", projection.questionTitle)
        assertEquals("Which fixture action should I run next?", projection.questionDescription)
        assertEquals("Which fixture action should I run next?", projection.noticeBody)
        assertEquals(1, transport.pendingCalls)

        assertTrue(controller.submit("frm_1", projection.generation, "a") { true })
        advanceUntilIdle()

        assertEquals(1, transport.requests.size)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/session/ses_1/form/frm_1/reply", request.path)
        val answer = request.body!!["answer"] as JsonObject
        assertEquals("a", answer["answer"]!!.toString().trim('"'))
        assertEquals(listOf<String?>("/repo"), transport.directories)
        assertEquals(SessionPromptDeliveryState.Accepted, controller.projection.value.deliveryState)
    }

    @Test
    fun `a second tap while sending is ignored and sends once`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState()
                sendGate = CompletableDeferred()
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        val generation = controller.projection.value.generation

        assertTrue(controller.submit("frm_1", generation, "a") { true })
        runCurrent()
        assertEquals(SessionPromptDeliveryState.Sending, controller.projection.value.deliveryState)
        assertFalse(controller.submit("frm_1", generation, "b") { true })

        transport.sendGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a wrong form id, generation or choice is refused with no request`() = runTest {
        val transport = FakeTransport().apply { pendingResults += pendingState() }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        val generation = controller.projection.value.generation

        assertFalse(controller.submit("frm_other", generation, "a") { true })
        assertFalse(controller.submit("frm_1", generation + 1, "a") { true })
        assertFalse(controller.submit("frm_1", generation, "not-an-option") { true })
        advanceUntilIdle()

        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a changed launch before the tap sends nothing`() = runTest {
        val transport = FakeTransport().apply { pendingResults += pendingState() }
        var launch = 7L
        val controller = controller(transport, launch = { launch })
        controller.onNotification(question())
        advanceUntilIdle()
        val generation = controller.projection.value.generation

        launch = 8L
        assertFalse(controller.submit("frm_1", generation, "a") { true })
        advanceUntilIdle()
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a failed pre-send check is Unknown and never retried`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += null
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        val generation = controller.projection.value.generation

        assertTrue(controller.submit("frm_1", generation, "a") { true })
        advanceUntilIdle()

        assertEquals(SessionPromptDeliveryState.Unknown, controller.projection.value.deliveryState)
        assertTrue(transport.requests.isEmpty())
        assertFalse("Unknown is never submitted again automatically", controller.submit("frm_1", generation, "a") { true })
        advanceUntilIdle()
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a changed schema between the projection and the send sends nothing`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState(changedForm)
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        val generation = controller.projection.value.generation

        assertTrue(controller.submit("frm_1", generation, "a") { true })
        advanceUntilIdle()

        assertEquals(SessionPromptDeliveryState.Superseded, controller.projection.value.deliveryState)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a changed question metadata between the projection and the send sends nothing`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState(metadataChangedForm)
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        val generation = controller.projection.value.generation

        assertTrue(controller.submit("frm_1", generation, "a") { true })
        advanceUntilIdle()

        assertEquals(SessionPromptDeliveryState.Superseded, controller.projection.value.deliveryState)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a form answered elsewhere before the send is Gone with no request`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += obj("""{"ses_1":{"forms":[]}}""")
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        val generation = controller.projection.value.generation

        assertTrue(controller.submit("frm_1", generation, "a") { true })
        advanceUntilIdle()

        assertEquals(SessionPromptDeliveryState.Gone, controller.projection.value.deliveryState)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `an unknown result does not send a retry`() = runTest {
        listOf(0, 503).forEach { status ->
            val transport =
                FakeTransport().apply {
                    pendingResults += pendingState()
                    pendingResults += pendingState()
                    sendResult = ApiResult(status, "server error")
                }
            val controller = controller(transport)
            controller.onNotification(question())
            advanceUntilIdle()
            val generation = controller.projection.value.generation

            assertTrue(controller.submit("frm_1", generation, "a") { true })
            advanceUntilIdle()
            advanceUntilIdle()

            assertEquals("$status sends once", 1, transport.requests.size)
            assertEquals(
                "$status is unknown",
                SessionPromptDeliveryState.Unknown,
                controller.projection.value.deliveryState,
            )
            assertFalse(controller.submit("frm_1", generation, "a") { true })
            advanceUntilIdle()
            assertEquals("$status is never retried automatically", 1, transport.requests.size)
        }
    }

    @Test
    fun `an unknown send stays terminal across refresh`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState()
                sendResult = ApiResult(503, "server error")
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        val generation = controller.projection.value.generation

        assertTrue(controller.submit("frm_1", generation, "a") { true })
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Unknown, controller.projection.value.deliveryState)

        // A refresh returns the same pending form. It must not turn Unknown back into Ready.
        transport.pendingResults += pendingState()
        controller.refresh()
        advanceUntilIdle()

        assertEquals(SessionPromptDeliveryState.Unknown, controller.projection.value.deliveryState)
        assertTrue(controller.projection.value.choices.isEmpty())
        assertEquals(1, transport.requests.size)
        assertEquals(3, transport.pendingCalls)
    }

    @Test
    fun `an accepted form is never offered again by a duplicate notification`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState()
                sendResult = ApiResult(204, "")
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()

        assertTrue(controller.submit("frm_1", controller.projection.value.generation, "a") { true })
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Accepted, controller.projection.value.deliveryState)

        transport.pendingResults += pendingState()
        controller.onNotification(question())
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Accepted, controller.projection.value.deliveryState)
        assertTrue(controller.projection.value.choices.isEmpty())
        assertEquals(1, transport.requests.size)

        // The server removes the whole pending session after settling a form. A refresh of a known
        // answer keeps its exact terminal fact, never an unsupported-question prompt or another POST.
        listOf(obj("""{}"""), obj("""{"ses_1":{"permissions":[]}}""")).forEach { empty ->
            transport.pendingResults += empty
            controller.refresh()
            advanceUntilIdle()
            assertEquals(SessionPromptDeliveryState.Accepted, controller.projection.value.deliveryState)
            assertFalse(controller.submit("frm_1", controller.projection.value.generation, "a") { true })
            assertEquals(1, transport.requests.size)
        }

        // A new event without a pending form must not borrow an older form's Accepted fact.
        transport.pendingResults += obj("""{"ses_1":{"forms":[]}}""")
        controller.onNotification(question())
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Gone, controller.projection.value.deliveryState)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a changed schema on the same form cannot borrow a resubmit`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState()
                sendResult = ApiResult(503, "server error")
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()

        assertTrue(controller.submit("frm_1", controller.projection.value.generation, "a") { true })
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Unknown, controller.projection.value.deliveryState)

        transport.pendingResults += pendingState(changedForm)
        controller.refresh()
        advanceUntilIdle()

        assertEquals(SessionPromptDeliveryState.Unknown, controller.projection.value.deliveryState)
        assertTrue(controller.projection.value.choices.isEmpty())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a new form id can still be answered after an unknown older form`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState()
                sendResult = ApiResult(503, "server error")
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        assertTrue(controller.submit("frm_1", controller.projection.value.generation, "a") { true })
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Unknown, controller.projection.value.deliveryState)

        transport.pendingResults += pendingState(secondForm)
        controller.onNotification(question())
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Ready, controller.projection.value.deliveryState)
        assertEquals("frm_2", controller.projection.value.formId)

        transport.pendingResults += pendingState(secondForm)
        transport.sendResult = ApiResult(204, "")
        assertTrue(controller.submit("frm_2", controller.projection.value.generation, "a") { true })
        advanceUntilIdle()

        assertEquals(SessionPromptDeliveryState.Accepted, controller.projection.value.deliveryState)
        assertEquals(2, transport.requests.size)
        assertEquals("/api/session/ses_1/form/frm_2/reply", transport.requests.last().path)
    }

    @Test
    fun `a new epoch of the same task invalidates the old render but can re-answer its own form`() = runTest {
        val transport = FakeTransport().apply { pendingResults += pendingState() }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        val oldGeneration = controller.projection.value.generation
        assertEquals(SessionPromptDeliveryState.Ready, controller.projection.value.deliveryState)

        // Same task key, new grant epoch: the old selection/generation is no longer submittable.
        controller.invalidate(keepSession = "ses_1")
        assertEquals(SessionPromptDeliveryState.Checking, controller.projection.value.deliveryState)
        assertFalse(controller.submit("frm_1", oldGeneration, "a") { true })
        assertTrue(transport.requests.isEmpty())

        // The same task rechecks its own pending question and shows it with a fresh generation.
        transport.pendingResults += pendingState()
        controller.refresh()
        advanceUntilIdle()
        val fresh = controller.projection.value
        assertEquals(SessionPromptDeliveryState.Ready, fresh.deliveryState)
        assertTrue("a fresh generation is required", fresh.generation != oldGeneration)
        assertFalse("the old render generation still cannot submit", controller.submit("frm_1", oldGeneration, "a") { true })

        transport.pendingResults += pendingState()
        transport.sendResult = ApiResult(204, "")
        assertTrue(controller.submit("frm_1", fresh.generation, "a") { true })
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Accepted, controller.projection.value.deliveryState)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a new task key clears the old form and never rebinds it from pending`() = runTest {
        val transport = FakeTransport().apply { pendingResults += pendingState() }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Ready, controller.projection.value.deliveryState)

        // New task key, same session: clear without refreshing or adopting the global pending form.
        controller.invalidate(keepSession = null)
        assertEquals(SessionPromptDeliveryState.Idle, controller.projection.value.deliveryState)
        assertNull(controller.projection.value.sessionId)

        // A duplicate question event for the old form cannot rebind it to the new task owner.
        transport.pendingResults += pendingState()
        controller.onNotification(question())
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Superseded, controller.projection.value.deliveryState)
        assertTrue(controller.projection.value.choices.isEmpty())
        assertFalse(controller.submit("frm_1", controller.projection.value.generation, "a") { true })
        assertTrue(transport.requests.isEmpty())

        // A genuinely new form for the new task is still answerable.
        transport.pendingResults += pendingState(secondForm)
        controller.onNotification(question())
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Ready, controller.projection.value.deliveryState)
        assertEquals("frm_2", controller.projection.value.formId)

        transport.pendingResults += pendingState(secondForm)
        transport.sendResult = ApiResult(204, "")
        assertTrue(controller.submit("frm_2", controller.projection.value.generation, "a") { true })
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Accepted, controller.projection.value.deliveryState)
        assertEquals(1, transport.requests.size)
        assertEquals("/api/session/ses_1/form/frm_2/reply", transport.requests.single().path)
    }

    @Test
    fun `a refused form is not offered again by a refresh`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState()
                sendResult = ApiResult(409, "")
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        assertTrue(controller.submit("frm_1", controller.projection.value.generation, "a") { true })
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Refused, controller.projection.value.deliveryState)

        transport.pendingResults += pendingState()
        controller.refresh()
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Refused, controller.projection.value.deliveryState)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a refusal is explicit and its body is never echoed`() = runTest {
        val secret = "sk-live-424242"
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState()
                sendResult = ApiResult(400, """{"error":"$secret"}""")
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()

        assertTrue(controller.submit("frm_1", controller.projection.value.generation, "a") { true })
        advanceUntilIdle()

        assertEquals(SessionPromptDeliveryState.Refused, controller.projection.value.deliveryState)
        assertFalse(controller.projection.value.toString().contains(secret))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a failed refresh is Unknown, not answered`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += null
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        assertEquals(SessionPromptDeliveryState.Ready, controller.projection.value.deliveryState)

        controller.refresh()
        advanceUntilIdle()

        assertEquals(SessionPromptDeliveryState.Unknown, controller.projection.value.deliveryState)
        assertEquals(2, transport.pendingCalls)

        // A failed read did not send, so a later successful read may offer the form again.
        transport.pendingResults += pendingState()
        controller.refresh()
        advanceUntilIdle()

        assertEquals(SessionPromptDeliveryState.Ready, controller.projection.value.deliveryState)
        assertEquals(3, transport.pendingCalls)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `permission and non-question events never enter the projection`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport)

        controller.onNotification(
            question().copy(kind = DesktopNotification.Kind.Permission, tag = "permission-ses_1:req_1"),
        )
        advanceUntilIdle()

        assertEquals(SessionPromptDeliveryState.Idle, controller.projection.value.deliveryState)
        assertNull(controller.projection.value.sessionId)
        assertEquals(0, transport.pendingCalls)
    }

    @Test
    fun `a plain notice is shown without touching a pending question`() = runTest {
        val transport = FakeTransport().apply { pendingResults += pendingState() }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        val questionBefore = controller.projection.value
        assertEquals(SessionPromptDeliveryState.Ready, questionBefore.deliveryState)

        controller.onNotification(notice(DesktopNotification.Kind.Ready, title = "Agent finished", body = "Done"))
        advanceUntilIdle()

        assertEquals(questionBefore, controller.projection.value)
        val value = controller.notice.value!!
        assertEquals(SessionNoticeKind.Ready, value.kind)
        assertEquals("Agent finished", value.title)
        assertEquals("Done", value.body)
        assertEquals(1, transport.pendingCalls)
    }

    @Test
    fun `permission and unknown kinds are not shown as plain notices`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport)

        controller.onNotification(
            notice(DesktopNotification.Kind.Permission).copy(tag = "permission-ses_1:req_1"),
        )
        controller.onNotification(notice(DesktopNotification.Kind.Unknown))
        advanceUntilIdle()

        assertNull(controller.notice.value)
        assertEquals(SessionPromptDeliveryState.Idle, controller.projection.value.deliveryState)
        assertEquals(0, transport.pendingCalls)
    }

    @Test
    fun `a notice for another task session is ignored and the body is bounded`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport, taskSession = { "ses_other" })
        controller.onNotification(notice(DesktopNotification.Kind.Goal, body = "x".repeat(5_000)))
        advanceUntilIdle()
        assertNull(controller.notice.value)

        val same = controller(transport)
        same.onNotification(notice(DesktopNotification.Kind.Error, body = "x".repeat(5_000)))
        advanceUntilIdle()
        assertEquals(640, same.notice.value!!.body!!.length)
        assertEquals(SessionNoticeKind.Error, same.notice.value!!.kind)
    }

    @Test
    fun `invalidation clears the old notice with the old task owner`() = runTest {
        val controller = controller(FakeTransport())
        controller.onNotification(notice(DesktopNotification.Kind.Ready))
        advanceUntilIdle()
        assertTrue(controller.notice.value != null)

        controller.invalidate(keepSession = "ses_1")
        assertNull(controller.notice.value)
    }

    @Test
    fun `a pinned custom true question still offers only its fixed options`() = runTest {
        val transport = FakeTransport().apply { pendingResults += pendingState() }
        val controller = controller(transport)

        controller.onNotification(question())
        advanceUntilIdle()

        val projection = controller.projection.value
        assertEquals(SessionPromptDeliveryState.Ready, projection.deliveryState)
        assertEquals(listOf("a", "b"), projection.choices.map { it.value })
        assertFalse("no free-form input is offered", projection.choices.isEmpty())
    }

    @Test
    fun `a different phone task session is ignored`() = runTest {
        val transport = FakeTransport().apply { pendingResults += pendingState() }
        val controller = controller(transport, taskSession = { "ses_other" })

        controller.onNotification(question())
        advanceUntilIdle()

        assertEquals(SessionPromptDeliveryState.Idle, controller.projection.value.deliveryState)
        assertEquals(0, transport.pendingCalls)
    }

    @Test
    fun `invalidation clears a question from another task and renews a same-session generation`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState()
            }
        val controller = controller(transport)
        controller.onNotification(question())
        advanceUntilIdle()
        val first = controller.projection.value
        assertEquals(SessionPromptDeliveryState.Ready, first.deliveryState)

        controller.invalidate(keepSession = "ses_2")
        assertEquals(SessionPromptDeliveryState.Idle, controller.projection.value.deliveryState)
        assertNull(controller.projection.value.noticeTitle)

        controller.onNotification(question())
        advanceUntilIdle()
        val second = controller.projection.value
        val generation = second.generation

        controller.invalidate(keepSession = "ses_1")
        assertEquals("ses_1", controller.projection.value.sessionId)
        assertTrue("a new epoch must invalidate the old selection", controller.projection.value.generation != generation)
    }

    @Test
    fun `the notification and question text are bounded in memory`() = runTest {
        val longForm =
            """{"id":"frm_1","sessionID":"ses_1","title":"Questions","fields":[
               {"key":"answer","title":"Next step","description":"${"y".repeat(5_000)}","type":"string",
                "options":[{"value":"a","label":"Option A"}]}]}"""
        val transport = FakeTransport().apply { pendingResults += pendingState(longForm) }
        val controller = controller(transport)

        controller.onNotification(question(body = "x".repeat(5_000)))
        advanceUntilIdle()

        assertEquals(640, controller.projection.value.noticeBody!!.length)
        assertEquals(640, controller.projection.value.questionDescription!!.length)
    }
}
