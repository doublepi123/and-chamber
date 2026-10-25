package io.andchamber.app.data

import io.andchamber.app.notify.ApiRequest
import io.andchamber.app.notify.InlineFormAction
import io.andchamber.app.notify.InlineFormCommand
import io.andchamber.app.notify.InlineFormKey
import io.andchamber.app.notify.InlineFormStatus
import io.andchamber.app.notify.InlineNotificationFormController
import io.andchamber.app.notify.InlineNotificationFormStore
import io.andchamber.app.notify.LocalApiNotificationFormTransport
import io.andchamber.app.notify.NotificationActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The notification client must not leave a blocking OkHttp call behind: a delayed response is
 * actually cancelled with the coroutine, the client mutex is released, and a cancelled POST is never
 * sent a second time. MockWebServer delays are real network waits, not fake coroutine delays.
 */
class LocalApiClientCancellationTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() =
        LocalApiClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            password = { "ui-secret" },
            localReady = LocalReadiness { 1L },
        )

    private fun login(): MockResponse =
        MockResponse().setResponseCode(200).addHeader("Set-Cookie", "oc_ui_session_3000=tok; Path=/; HttpOnly")

    private fun loginCookie(value: String): MockResponse =
        MockResponse().setResponseCode(200).addHeader("Set-Cookie", "oc_ui_session_3000=$value; Path=/; HttpOnly")

    private fun delayed(
        body: String,
        delaySeconds: Long = 2,
    ): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setBody(body)
            .setBodyDelay(delaySeconds, TimeUnit.SECONDS)

    private fun timedOut(
        milliseconds: Long,
        block: suspend () -> Unit,
    ): Boolean {
        val started = System.nanoTime()
        val failure = runCatching { runBlocking { withTimeout(milliseconds) { block() } } }.exceptionOrNull()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
        assertTrue("expected CancellationException, was $failure", failure is CancellationException)
        assertTrue("the call returned after $elapsedMillis ms instead of being cancelled", elapsedMillis < 1_500L)
        return true
    }

    @Test
    fun `a delayed pending is cancelled and does not keep the client blocked`() {
        server.enqueue(login())
        server.enqueue(delayed("""{"pending":{}}"""))
        val client = client()

        assertTrue(timedOut(400) { client.pending() })

        // The cancelled call released the mutex; a later action goes through normally.
        server.enqueue(MockResponse().setResponseCode(204))
        val next =
            runBlocking {
                withTimeout(3_000) {
                    client.send(NotificationActions.formReply("ses_1", "frm_1", "env", "prod"))
                }
            }
        assertTrue(next.ok)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a cancelled POST is not sent again`() {
        server.enqueue(login())
        server.enqueue(delayed("{}"))
        val client = client()

        assertTrue(timedOut(400) { client.send(NotificationActions.formReply("ses_1", "frm_1", "env", "prod")) })

        // Give a potential automatic retry time to show up; there must be only login + one POST.
        Thread.sleep(300)
        assertEquals("login plus exactly one POST", 2, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `a real pending timeout is CheckFailed and never posts`() =
        runBlocking {
            val form =
                """{"id":"frm_1","sessionID":"ses_1","fields":[
                   {"key":"a","type":"string","required":true},
                   {"key":"b","type":"string","required":true}]}"""
            val pending = Json.parseToJsonElement("""{"ses_1":{"forms":[$form]}}""") as JsonObject
            server.enqueue(login())
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"pending":$pending}""")
                    .setBodyDelay(4, TimeUnit.SECONDS),
            )
            val client = client()
            val controller =
                InlineNotificationFormController(
                    transport = LocalApiNotificationFormTransport(client),
                    key = InlineFormKey("ses_1", null, "frm_1", 7),
                    store = InlineNotificationFormStore(),
                    budgetMillis = 1_000L,
                )
            val start = controller.begin(pending)
            val second =
                controller.act(
                    InlineFormAction(start.token!!, start.generation, InlineFormCommand.Reply("a1")),
                )

            val startedAt = System.nanoTime()
            val result =
                controller.act(
                    InlineFormAction(second.token!!, second.generation, InlineFormCommand.Reply("b1")),
                )
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L

            assertEquals("a pending timeout is unsent, never POST-unknown", InlineFormStatus.CheckFailed, result.status)
            assertTrue("an explicit retry is offered", io.andchamber.app.notify.InlineFormActionKind.Retry in result.actions)
            assertTrue("the timeout really returned after $elapsedMillis ms", elapsedMillis < 2_000L)
            Thread.sleep(200)
            assertEquals("login + pending only, no POST", 2, server.requestCount)
        }

    @Test
    fun `a cancelled form POST stays unknown and is not retried`() =
        runBlocking {
            val form =
                """{"id":"frm_1","sessionID":"ses_1","fields":[
                   {"key":"a","type":"string","required":true},
                   {"key":"b","type":"string","required":true}]}"""
            val pending = Json.parseToJsonElement("""{"ses_1":{"forms":[$form]}}""") as JsonObject
            server.enqueue(login())
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"pending":$pending}"""))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{}")
                    .setBodyDelay(4, TimeUnit.SECONDS),
            )
            val client = client()
            val controller =
                InlineNotificationFormController(
                    transport = LocalApiNotificationFormTransport(client),
                    key = InlineFormKey("ses_1", null, "frm_1", 7),
                    store = InlineNotificationFormStore(),
                    budgetMillis = 2_000L,
                )
            val start = controller.begin(pending)
            val second =
                controller.act(
                    InlineFormAction(start.token!!, start.generation, InlineFormCommand.Reply("a1")),
                )

            val startedAt = System.nanoTime()
            val result =
                controller.act(
                    InlineFormAction(second.token!!, second.generation, InlineFormCommand.Reply("b1")),
                )
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L

            assertEquals("a started POST is unknown, never unsent", InlineFormStatus.ResultUnknown, result.status)
            assertTrue("the controller returned after $elapsedMillis ms", elapsedMillis < 4_000L)
            Thread.sleep(300)
            assertEquals("login + pending + exactly one POST", 3, server.requestCount)
        }

    @Test
    fun `a cancelled call's late cookies cannot overwrite a newer login`() =
        runBlocking {
            var launch = 1L
            val client =
                LocalApiClient(
                    baseUrl = { server.url("/").toString().trimEnd('/') },
                    password = { "ui-secret" },
                    localReady = LocalReadiness { launch },
                )
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var commits = 0
            client.beforeCookieCommitForTest = {
                commits += 1
                if (commits == 2) {
                    // Hold A's business response cookies before they can reach the shared jar.
                    held.complete(Unit)
                    release.await()
                }
            }
            server.enqueue(loginCookie("A"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{}")
                    .addHeader("Set-Cookie", "oc_ui_session_3000=A2; Path=/; HttpOnly"),
            )

            val a = async { client.send(ApiRequest("GET", "/a", null)) }
            held.await()

            // A is cancelled while its response cookies are held; the mutex is released.
            a.cancelAndJoin()

            // A new lease logs in as B before A's held callback is released.
            launch = 2L
            client.beforeCookieCommitForTest = null
            server.enqueue(loginCookie("B"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            client.send(ApiRequest("GET", "/b", null))

            release.complete(Unit)

            // The next request must carry B's session, never A's late A2 cookie.
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            client.send(ApiRequest("GET", "/c", null))

            repeat(4) { server.takeRequest() }
            val last = server.takeRequest()
            val cookie = last.getHeader("Cookie").orEmpty()
            assertTrue("expected B session cookie, got: $cookie", cookie.contains("oc_ui_session_3000=B"))
            assertFalse("A's late cookie must not win", cookie.contains("oc_ui_session_3000=A2"))
        }

    @Test
    fun `a cancelled GET is not replayed as a business request`() {
        server.enqueue(login())
        server.enqueue(delayed("""{"pending":{}}"""))
        val client = client()

        assertTrue(timedOut(400) { client.send(ApiRequest("GET", "/api/sessions/status", null)) })

        Thread.sleep(300)
        assertEquals(2, server.requestCount)
    }
}
