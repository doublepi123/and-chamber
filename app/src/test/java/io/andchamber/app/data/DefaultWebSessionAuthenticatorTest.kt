package io.andchamber.app.data

import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DefaultWebSessionAuthenticatorTest {
    private lateinit var server: MockWebServer
    private val cookies = RecordingCookies()
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()

    private class RecordingCookies : WebCookieSink {
        val headers = mutableListOf<Pair<String, String>>()
        var flushes = 0

        override fun setCookie(
            url: String,
            header: String,
        ) {
            headers += url to header
        }

        override fun flush() {
            flushes++
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun local(password: String?) =
        DefaultWebSessionAuthenticator(
            client = client,
            cookies = cookies,
            localPassword = { password },
        )

    private fun remote(uiPassword: String?) =
        ServerTarget.Remote(id = "r1", displayName = "LAN", baseUrl = base(), uiPassword = uiPassword)

    private fun authenticator(password: String?) = local(password)

    private fun health(ok: Boolean = true) =
        MockResponse().setBody(if (ok) """{"status":"ok","openchamberVersion":"2.4.0"}""" else """{"status":"down"}""")

    @Test
    fun `health failure is Unreachable`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))

            val result = authenticator("pw").prepare(ServerTarget.Local(base()))

            assertTrue(result is WebSessionResult.Unreachable)
            assertEquals(1, server.requestCount)
            assertTrue(cookies.headers.isEmpty())
        }

    @Test
    fun `unparseable health body is Unreachable`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("<html>not openchamber</html>"))

            val result = authenticator("pw").prepare(ServerTarget.Local(base()))

            assertTrue(result is WebSessionResult.Unreachable)
        }

    @Test
    fun `login sets every session cookie and reports Ready`() =
        runBlocking {
            server.enqueue(health())
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"success":true}""")
                    .addHeader("Set-Cookie", "oc_ui_session_3000=abc; Path=/; HttpOnly; SameSite=Strict")
                    .addHeader("Set-Cookie", "extra=1; Path=/"),
            )

            val result = authenticator("local-secret").prepare(ServerTarget.Local(base()))

            assertEquals(WebSessionResult.Ready(base()), result)
            assertEquals(2, cookies.headers.size)
            assertEquals("oc_ui_session_3000=abc; Path=/; HttpOnly; SameSite=Strict", cookies.headers[0].second)
            assertTrue(cookies.flushes >= 1)

            // The health check and then the login POST, which carries the password and trustDevice.
            server.takeRequest()
            val login = server.takeRequest()
            assertEquals("POST", login.method)
            assertEquals("/auth/session", login.path)
            val body = login.body.readUtf8()
            assertTrue(body, body.contains("\"password\":\"local-secret\""))
            assertTrue(body, body.contains("\"trustDevice\":true"))
        }

    @Test
    fun `wrong password is NeedsLogin`() =
        runBlocking {
            server.enqueue(health())
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid credentials"}"""))

            val result = authenticator("wrong").prepare(ServerTarget.Local(base()))

            assertEquals(WebSessionResult.NeedsLogin(base()), result)
            assertTrue(cookies.headers.isEmpty())
        }

    @Test
    fun `remote without stored password is Ready when the server already accepts the session`() =
        runBlocking {
            server.enqueue(health())
            server.enqueue(MockResponse().setBody("""{"authenticated":true}"""))

            val result = DefaultWebSessionAuthenticator(client, cookies, { null }).prepare(remote(null))

            assertEquals(WebSessionResult.Ready(base()), result)
        }

    @Test
    fun `remote without stored password and no session is NeedsLogin`() =
        runBlocking {
            server.enqueue(health())
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"authenticated":false,"locked":true}"""))

            val result = DefaultWebSessionAuthenticator(client, cookies, { null }).prepare(remote(null))

            assertEquals(WebSessionResult.NeedsLogin(base()), result)
        }

    @Test
    fun `remote with stored password logs in with that password`() =
        runBlocking {
            server.enqueue(health())
            server.enqueue(MockResponse().setBody("""{"success":true}""").addHeader("Set-Cookie", "s=1; Path=/"))

            val result = DefaultWebSessionAuthenticator(client, cookies, { null }).prepare(remote("stored"))

            assertEquals(WebSessionResult.Ready(base()), result)
            server.takeRequest()
            assertTrue(server.takeRequest().body.readUtf8().contains("\"password\":\"stored\""))
        }

    @Test
    fun `unparseable base url is Unreachable without any request`() =
        runBlocking {
            val result = authenticator("pw").prepare(ServerTarget.Local("not a url"))

            assertTrue(result is WebSessionResult.Unreachable)
            assertEquals(0, server.requestCount)
        }
}
