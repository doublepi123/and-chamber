package io.andchamber.app.notify

import io.andchamber.app.data.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The form screen's controller: load one form and submit its whole answer map at most once. */
class NotificationFormControllerTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private val formJson =
        """{"id":"frm_1","sessionID":"ses_1","title":"Deploy","fields":[
           {"key":"env","title":"Environment","type":"string","description":"target env","required":true,"placeholder":"dev","default":"dev"},
           {"key":"branch","title":"Branch","type":"string"},
           {"key":"note","title":"Note","type":"string"}]}"""

    private val form = PendingForms.classifyForm(obj(formJson)) as FormAnswer.Fields

    private fun pendingState(
        formJson: String = this.formJson,
        sessionId: String = "ses_1",
    ): JsonObject = obj("""{"$sessionId":{"forms":[$formJson]}}""")

    private class FakeTransport : NotificationFormTransport {
        val pendingResults = mutableListOf<JsonObject?>()
        var sendResult: ApiResult = ApiResult(204, "")
        val requests = mutableListOf<ApiRequest>()
        val directories = mutableListOf<String?>()
        var pendingCalls = 0

        override suspend fun pending(): JsonObject? {
            pendingCalls += 1
            return if (pendingResults.isEmpty()) null else pendingResults.removeAt(0)
        }

        override suspend fun send(
            request: ApiRequest,
            directory: String?,
        ): ApiResult {
            requests += request
            directories += directory
            return sendResult
        }
    }

    private fun controller(transport: FakeTransport) =
        NotificationFormController(transport, sessionId = "ses_1", directory = "/repo", formId = "frm_1")

    // --- loading ---

    @Test
    fun `load reads the pending form and returns all of its fields`() = runTest {
        val transport = FakeTransport().apply { pendingResults += pendingState() }

        assertEquals(NotificationFormLoad.Ready(form), controller(transport).load())
    }

    @Test
    fun `a failed read is CheckFailed and never Gone`() = runTest {
        assertEquals(NotificationFormLoad.CheckFailed, controller(FakeTransport()).load())
    }

    @Test
    fun `a form that is no longer pending is Gone`() = runTest {
        val transport = FakeTransport().apply { pendingResults += obj("""{"ses_1":{"forms":[]}}""") }

        assertEquals(NotificationFormLoad.Gone, controller(transport).load())
    }

    @Test
    fun `a form this screen cannot fill is Unavailable`() = runTest {
        val choice =
            """{"id":"frm_1","sessionID":"ses_1","title":"Pick","fields":[{"key":"a","type":"string","options":[{"value":"x","label":"X"}]}]}"""
        val transport = FakeTransport().apply { pendingResults += pendingState(choice) }

        assertEquals(NotificationFormLoad.Unavailable, controller(transport).load())
    }

    // --- required values are checked before anything is sent ---

    @Test
    fun `a missing required value keeps the screen open and sends nothing`() = runTest {
        val transport = FakeTransport()

        val submission = controller(transport).submit(form, mapOf("env" to "  ", "branch" to "main"))

        assertEquals(ActionStep.KeepSendFailed, submission.step)
        assertEquals("Environment", submission.reason)
        assertEquals("pending is not read before validation", 0, transport.pendingCalls)
        assertTrue("no request is sent", transport.requests.isEmpty())
    }

    @Test
    fun `an absent required value is reported by its title`() = runTest {
        val transport = FakeTransport()

        val submission = controller(transport).submit(form, mapOf("branch" to "main"))

        assertEquals(ActionStep.KeepSendFailed, submission.step)
        assertEquals("Environment", submission.reason)
        assertTrue(transport.requests.isEmpty())
    }

    // --- the whole answer map is sent once ---

    @Test
    fun `a valid submit sends every field once with trimmed values and drops unknown keys`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                sendResult = ApiResult(204, "")
            }

        val submission =
            controller(transport).submit(
                form,
                mapOf(
                    "env" to " prod ",
                    "branch" to " main ",
                    "note" to " ship ",
                    "unknown" to "must not be sent",
                    "empty" to "   ",
                ),
            )

        assertEquals(ActionStep.Cancel, submission.step)
        assertEquals(null, submission.reason)
        assertEquals("exactly one request", 1, transport.requests.size)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/session/ses_1/form/frm_1/reply", request.path)
        assertEquals(listOf<String?>("/repo"), transport.directories)
        val answer = request.body!!["answer"] as JsonObject
        assertEquals(setOf("env", "branch", "note"), answer.keys)
        assertEquals("prod", answer["env"]!!.toString().trim('"'))
        assertEquals("main", answer["branch"]!!.toString().trim('"'))
        assertEquals("ship", answer["note"]!!.toString().trim('"'))
    }

    @Test
    fun `a refusal keeps the screen open with a fixed status and does not resend`() =
        runTest {
            listOf(400, 409).forEach { status ->
                val transport =
                    FakeTransport().apply {
                        pendingResults += pendingState()
                        pendingResults += pendingState()
                        sendResult = ApiResult(status, "refused")
                    }

                val submission = controller(transport).submit(form, mapOf("env" to "prod", "branch" to "main"))

                assertEquals("$status keeps the form", ActionStep.KeepSendFailed, submission.step)
                assertEquals("the body is never a reason", null, submission.reason)
                assertEquals("$status is sent once", 1, transport.requests.size)
            }
        }

    @Test
    fun `a refusal body with sensitive values never becomes a reason`() = runTest {
        val secret = "sk-live-424242"
        val body = """{"error":"invalid answer","answer":{"env":"prod","note":"$secret"}}"""
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState()
                sendResult = ApiResult(400, body)
            }

        val submission = controller(transport).submit(form, mapOf("env" to "prod", "note" to secret))

        assertEquals(ActionStep.KeepSendFailed, submission.step)
        assertEquals(null, submission.reason)
        assertFalse("the refusal body must not reach the screen", submission.reason?.contains(secret) == true)
        assertFalse("no other field carries the body", submission.toString().contains(secret))
    }

    @Test
    fun `a query failure before sending is KeepCheckFailed and nothing is sent`() = runTest {
        val transport = FakeTransport()

        val submission = controller(transport).submit(form, mapOf("env" to "prod"))

        assertEquals(ActionStep.KeepCheckFailed, submission.step)
        assertEquals(null, submission.reason)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a query failure after a refusal is not treated as Gone`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                // The second check fails: nothing is known, so the form must not be cancelled.
                sendResult = ApiResult(400, "refused")
            }

        val submission = controller(transport).submit(form, mapOf("env" to "prod"))

        assertEquals(ActionStep.KeepSendFailed, submission.step)
        assertEquals("the body is not echoed even here", null, submission.reason)
    }

    @Test
    fun `an unknown result is kept while the form is still pending`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += pendingState()
                sendResult = ApiResult(503, "server error")
            }

        val submission = controller(transport).submit(form, mapOf("env" to "prod"))

        assertEquals(ActionStep.KeepResultUnknown, submission.step)
        assertEquals("the body is never a reason", null, submission.reason)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `an unknown result whose form disappeared is cancelled`() = runTest {
        val transport =
            FakeTransport().apply {
                pendingResults += pendingState()
                pendingResults += obj("""{"ses_1":{"forms":[]}}""")
                sendResult = ApiResult(503, "server error")
            }

        assertEquals(ActionStep.Cancel, controller(transport).submit(form, mapOf("env" to "prod")).step)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `the form screen action identity includes the form, notification, session and directory`() {
        fun spec(
            formId: String = "frm_1",
            notificationId: Int = 7,
            sessionId: String = "ses_1",
            directory: String? = "/repo",
        ) = NotificationActionIdentity.Spec(
            action = NotificationFormActivity.ACTION_ANSWER_FORM,
            notificationId = notificationId,
            sessionId = sessionId,
            directory = directory,
            requestId = formId,
        )

        val base = NotificationActionIdentity.dataUri(spec())
        assertEquals(base, NotificationActionIdentity.dataUri(spec()))
        assertNotEquals(base, NotificationActionIdentity.dataUri(spec(formId = "frm_2")))
        assertNotEquals(base, NotificationActionIdentity.dataUri(spec(notificationId = 8)))
        assertNotEquals(base, NotificationActionIdentity.dataUri(spec(sessionId = "ses_2")))
        assertNotEquals(base, NotificationActionIdentity.dataUri(spec(directory = "/other")))
        assertNotEquals(base, NotificationActionIdentity.dataUri(spec(directory = null)))
        assertTrue(base.startsWith(NotificationActionIdentity.DATA_SCHEME + "://action/v1/"))
        assertTrue("the form id is the identity request", base.contains("request=frm_1"))
    }
}
