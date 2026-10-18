package io.andchamber.app.data

import io.andchamber.app.notify.ApiRequest
import io.andchamber.app.notify.NotificationActions
import io.andchamber.app.notify.NotificationSettingsBootstrap
import io.andchamber.app.notify.NotificationBootstrapStore
import io.andchamber.app.notify.PermissionDecision
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalApiClientTest {
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

    private fun client(password: String? = "ui-secret") =
        LocalApiClient(baseUrl = { server.url("/").toString().trimEnd('/') }, password = { password })

    private fun login(): MockResponse = MockResponse().setResponseCode(200).addHeader("Set-Cookie", "oc_ui_session_3000=tok; Path=/; HttpOnly")

    @Test
    fun `reply posts the prompt text with the directory header and the session cookie`() =
        runBlocking {
            server.enqueue(login())
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

            val result = client().send(NotificationActions.sendReply("ses_1", "continue"), directory = "/home/x/my repo")

            assertTrue(result.ok)
            assertEquals("POST", server.takeRequest().method)
            val prompt = server.takeRequest()
            assertEquals("/api/session/ses_1/prompt", prompt.path)
            assertEquals("""{"text":"continue"}""", prompt.body.readUtf8())
            assertEquals("%2Fhome%2Fx%2Fmy%20repo", prompt.getHeader("x-opencode-directory"))
        }

    @Test
    fun `reply body is exactly the text field`() =
        runBlocking {
            server.enqueue(login())
            server.enqueue(MockResponse().setResponseCode(200))

            client().send(NotificationActions.sendReply("ses_1", "hi there"))
            server.takeRequest()
            val body = server.takeRequest().body.readUtf8()

            assertEquals("""{"text":"hi there"}""", body)
        }

    @Test
    fun `permission decision posts the decision to the request route`() =
        runBlocking {
            server.enqueue(login())
            server.enqueue(MockResponse().setResponseCode(204))

            val result = client().send(NotificationActions.permissionReply("ses_1", "per_9", PermissionDecision.Always))

            assertTrue(result.ok)
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("/api/session/ses_1/permission/per_9/reply", request.path)
            assertEquals("""{"decision":"always"}""", request.body.readUtf8())
        }

    @Test
    fun `form answer posts the record keyed by field`() =
        runBlocking {
            server.enqueue(login())
            server.enqueue(MockResponse().setResponseCode(204))

            client().send(NotificationActions.formReply("ses_1", "frm_1", "env", "prod"))
            server.takeRequest()

            val request = server.takeRequest()
            assertEquals("/api/session/ses_1/form/frm_1/reply", request.path)
            assertEquals("""{"answer":{"env":"prod"}}""", request.body.readUtf8())
        }

    @Test
    fun `a 401 signs in again and retries once`() =
        runBlocking {
            server.enqueue(login())
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(login())
            server.enqueue(MockResponse().setResponseCode(200))

            val result = client().send(NotificationActions.sendReply("ses_1", "again"))

            assertTrue(result.ok)
            assertEquals(4, server.requestCount)
        }

    @Test
    fun `without a password nothing is sent to the server`() =
        runBlocking {
            val result = client(password = null).send(NotificationActions.sendReply("ses_1", "x"))

            assertFalse(result.ok)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a non-loopback address is refused`() =
        runBlocking {
            val remote = LocalApiClient(baseUrl = { "http://192.168.1.20:3000" }, password = { "pw" })
            val result = remote.send(ApiRequest("GET", "/x", null))
            assertEquals(0, result.status)
        }

    // --- settings bootstrap ---

    private class MemStore(var done: Boolean = false) : NotificationBootstrapStore {
        override fun bootstrapped() = done

        override fun markBootstrapped() {
            done = true
        }
    }

    @Test
    fun `bootstrap turns the setting on when it is undefined, then never again`() =
        runBlocking {
            server.enqueue(login())
            server.enqueue(MockResponse().setBody("""{"theme":"dark"}"""))
            server.enqueue(MockResponse().setResponseCode(200))
            val store = MemStore()
            val bootstrap = NotificationSettingsBootstrap(client(), store)

            assertTrue(bootstrap.ensureEnabled())
            assertTrue(store.done)

            // The second start makes no request at all.
            val before = server.requestCount
            assertFalse(bootstrap.ensureEnabled())
            assertEquals(before, server.requestCount)
        }

    @Test
    fun `bootstrap leaves a user choice alone and still marks itself done`() =
        runBlocking {
            server.enqueue(login())
            server.enqueue(MockResponse().setBody("""{"nativeNotificationsEnabled":false}"""))
            val store = MemStore()

            assertFalse(NotificationSettingsBootstrap(client(), store).ensureEnabled())
            assertTrue(store.done)
            assertEquals("only the read, never a write", 2, server.requestCount)
        }

    @Test
    fun `bootstrap does not mark itself done when the write fails, so it retries next start`() =
        runBlocking {
            server.enqueue(login())
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(500))
            val store = MemStore()

            assertFalse(NotificationSettingsBootstrap(client(), store).ensureEnabled())
            assertFalse(store.done)
        }
}
