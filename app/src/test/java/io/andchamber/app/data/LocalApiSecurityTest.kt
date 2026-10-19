package io.andchamber.app.data

import io.andchamber.app.notify.ApiRequest
import io.andchamber.app.notify.NotificationActions
import io.andchamber.app.notify.PermissionDecision
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Trust rules of [LocalApiClient]: the password and session cookie only reach the launch that
 * [LocalReadiness] currently reports, on the same canonical loopback origin. Fake credentials only,
 * loopback only, every MockWebServer is shut down in finally.
 */
class LocalApiSecurityTest {
    private val fakePassword = "FAKE-PW-SECURITY-456"
    private val loginCookie = "oc_ui_session_3000=TOKEN_A; Path=/; HttpOnly"

    /** Mutable readiness the tests flip to simulate restarts, stops and crashes. */
    private class Ready(initial: Long?) {
        val value = AtomicReference<Long?>(initial)
        val provider = LocalReadiness { value.get() }
    }

    private fun freeLoopbackPort(): Int =
        ServerSocket().use { socket ->
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            socket.localPort
        }

    private fun serverOn(port: Int): MockWebServer {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), port)
        return server
    }

    private fun dispatcher(handler: (RecordedRequest) -> MockResponse): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
        }

    private fun login200(): MockResponse =
        MockResponse().setResponseCode(200).addHeader("Set-Cookie", loginCookie)

    private fun clientFor(
        url: () -> String,
        ready: Ready,
    ) = LocalApiClient(baseUrl = url, password = { fakePassword }, localReady = ready.provider)

    // --- stable trusted launch ---

    @Test
    fun `stable trusted launch logs in once and a 401 signs in again and retries`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(login200())
                server.enqueue(MockResponse().setResponseCode(401))
                server.enqueue(login200())
                server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                val ready = Ready(1L)
                val client = clientFor({ server.url("/").toString().trimEnd('/') }, ready)

                val result = client.send(NotificationActions.sendReply("ses_1", "hi"))

                assertTrue(result.ok)
                assertEquals(4, server.requestCount)
                assertEquals("/auth/session", server.takeRequest().path)
                assertEquals("/api/session/ses_1/prompt", server.takeRequest().path)
                assertEquals("/auth/session", server.takeRequest().path)
                assertEquals("/api/session/ses_1/prompt", server.takeRequest().path)
            } finally {
                server.shutdown()
            }
        }

    // --- readiness gate ---

    @Test
    fun `ready null sends nothing and does not release the password`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(login200())
                val ready = Ready(null)
                val client = clientFor({ server.url("/").toString().trimEnd('/') }, ready)

                val result = client.send(NotificationActions.sendReply("ses_1", "x"))

                assertFalse(result.ok)
                assertEquals(0, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `a throwing readiness provider fails closed with no network`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val client =
                    LocalApiClient(
                        baseUrl = { server.url("/").toString().trimEnd('/') },
                        password = { fakePassword },
                        localReady = LocalReadiness { throw IllegalStateException("provider broke") },
                    )

                val result = client.send(NotificationActions.sendReply("ses_1", "x"))

                assertFalse(result.ok)
                assertEquals(0, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `the default readiness denies everything`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val client = LocalApiClient(baseUrl = { server.url("/").toString().trimEnd('/') }, password = { fakePassword })

                assertFalse(client.send(NotificationActions.sendReply("ses_1", "x")).ok)
                assertEquals(0, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `same port takeover while readiness is null gets zero requests even though the url still points there`() =
        runBlocking {
            val port = freeLoopbackPort()
            val url = "http://127.0.0.1:$port"
            val ready = Ready(1L)
            val client = clientFor({ url }, ready)

            val original = serverOn(port)
            try {
                original.enqueue(login200())
                original.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                assertTrue(client.send(NotificationActions.sendReply("ses_1", "first")).ok)
                original.takeRequest()
                original.takeRequest()
            } finally {
                original.shutdown()
            }

            // The child is gone and readiness says so; the controller URL still names the port.
            ready.value.set(null)
            val takeover = serverOn(port)
            try {
                takeover.enqueue(login200())
                takeover.enqueue(MockResponse().setResponseCode(200))

                val result = client.send(NotificationActions.sendReply("ses_1", "second"))

                assertFalse(result.ok)
                assertEquals("takeover receives nothing", 0, takeover.requestCount)
            } finally {
                takeover.shutdown()
            }
        }

    // --- launch changes while a send is in flight ---

    @Test
    fun `launch change during the business call drops the response and the cookie`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val ready = Ready(1L)
                server.dispatcher =
                    dispatcher { request ->
                        if (request.path == "/auth/session") {
                            login200()
                        } else {
                            ready.value.set(2L) // the child is replaced while answering
                            MockResponse().setResponseCode(200).setBody("{}")
                        }
                    }
                val client = clientFor({ server.url("/").toString().trimEnd('/') }, ready)

                val result = client.send(NotificationActions.sendReply("ses_1", "x"))

                assertFalse("the response of a replaced launch is not returned as success", result.ok)
                assertEquals(2, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `launch change after a 401 never signs in again or sends the password`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val ready = Ready(1L)
                server.dispatcher =
                    dispatcher { request ->
                        when (request.path) {
                            "/auth/session" -> login200()
                            else -> {
                                ready.value.set(2L)
                                MockResponse().setResponseCode(401)
                            }
                        }
                    }
                val client = clientFor({ server.url("/").toString().trimEnd('/') }, ready)

                val result = client.send(NotificationActions.sendReply("ses_1", "x"))

                assertFalse(result.ok)
                assertEquals("no second login, no retry", 2, server.requestCount)
                val bodies = List(server.requestCount) { server.takeRequest() }.map { it.body.readUtf8() }
                assertFalse("the password is only ever sent by the first login", bodies.drop(1).any { it.contains(fakePassword) })
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `a relaunch on the same port does not reuse the old session cookie`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(login200())
                server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                server.enqueue(login200())
                server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                val ready = Ready(1L)
                val client = clientFor({ server.url("/").toString().trimEnd('/') }, ready)
                assertTrue(client.send(NotificationActions.sendReply("ses_1", "a")).ok)

                ready.value.set(2L)
                assertTrue(client.send(NotificationActions.sendReply("ses_1", "b")).ok)

                server.takeRequest()
                server.takeRequest()
                val relogin = server.takeRequest()
                assertEquals("/auth/session", relogin.path)
                assertNull("no old cookie on the new launch", relogin.getHeader("Cookie"))
            } finally {
                server.shutdown()
            }
        }

    // --- origin rules ---

    @Test
    fun `a non-loopback base url is refused with no network`() =
        runBlocking {
            val ready = Ready(1L)
            val client = clientFor({ "http://192.168.1.20:3000" }, ready)

            assertEquals(0, client.send(NotificationActions.sendReply("ses_1", "x")).status)
        }

    @Test
    fun `a base url with a path or credentials is refused with no network`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val port = server.port
                val ready = Ready(1L)
                assertFalse(clientFor({ "http://127.0.0.1:$port/extra" }, ready).send(NotificationActions.sendReply("ses_1", "x")).ok)
                assertFalse(clientFor({ "http://user:pw@127.0.0.1:$port" }, ready).send(NotificationActions.sendReply("ses_1", "x")).ok)
                assertEquals(0, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `requests that leave the origin are refused before any network`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(login200())
                val ready = Ready(1L)
                val client = clientFor({ server.url("/").toString().trimEnd('/') }, ready)

                val protocolRelative = client.send(ApiRequest("GET", "//evil.example/steal", null))
                val absolute = client.send(ApiRequest("GET", "http://evil.example/steal", null))
                val backslash = client.send(ApiRequest("GET", "/\\evil.example", null))

                assertFalse(protocolRelative.ok)
                assertFalse(absolute.ok)
                assertFalse(backslash.ok)
                assertEquals("no login and no business request", 0, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `redirects are not followed and the password is not re-sent`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val elsewhere = MockWebServer()
                elsewhere.start()
                try {
                    server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", elsewhere.url("/auth/session").toString()))
                    val ready = Ready(1L)
                    val client = clientFor({ server.url("/").toString().trimEnd('/') }, ready)

                    val result = client.send(NotificationActions.sendReply("ses_1", "x"))

                    assertFalse(result.ok)
                    assertEquals(0, elsewhere.requestCount)
                } finally {
                    elsewhere.shutdown()
                }
            } finally {
                server.shutdown()
            }
        }

    // --- concurrency ---

    @Test
    fun `a second send waits for the first so no login or cookie interleaves`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            val release = CountDownLatch(1)
            val businessSeen = CountDownLatch(1)
            try {
                server.dispatcher =
                    dispatcher { request ->
                        if (request.path == "/auth/session") {
                            login200()
                        } else {
                            businessSeen.countDown()
                            release.await(5, TimeUnit.SECONDS)
                            MockResponse().setResponseCode(200).setBody("{}")
                        }
                    }
                val ready = Ready(1L)
                val client = clientFor({ server.url("/").toString().trimEnd('/') }, ready)

                val first = async { client.send(NotificationActions.sendReply("ses_1", "a")) }
                businessSeen.await(5, TimeUnit.SECONDS)
                val second = async { client.send(NotificationActions.sendReply("ses_1", "b")) }
                delay(300)

                assertEquals("the second send has not touched the server yet", 2, server.requestCount)

                release.countDown()
                assertTrue(first.await().ok)
                assertTrue(second.await().ok)
                assertEquals("one login, two business calls", 3, server.requestCount)
            } finally {
                release.countDown()
                server.shutdown()
            }
        }

    // --- B1: business POST must never be replayed by OkHttp (503 + Retry-After: 0) ---

    private fun assertBusinessNotReplayed(
        request: ApiRequest,
        expectedPath: String,
    ) {
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(login200())
                server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("busy"))
                server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // spare: must never be consumed
                val client = clientFor({ server.url("/").toString().trimEnd('/') }, Ready(1L))

                val result = client.send(request)

                assertEquals(503, result.status)
                assertEquals("one login and one business POST only", 2, server.requestCount)
                assertEquals("/auth/session", server.takeRequest().path)
                val business = server.takeRequest()
                assertEquals("POST", business.method)
                assertEquals(expectedPath, business.path)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun `prompt 503 with Retry-After 0 is not replayed by okhttp`() {
        assertBusinessNotReplayed(NotificationActions.sendReply("ses_1", "hi"), "/api/session/ses_1/prompt")
    }

    @Test
    fun `permission reply 503 with Retry-After 0 is not replayed by okhttp`() {
        assertBusinessNotReplayed(
            NotificationActions.permissionReply("ses_1", "req_1", PermissionDecision.Once),
            "/api/session/ses_1/permission/req_1/reply",
        )
    }

    @Test
    fun `form reply 503 with Retry-After 0 is not replayed by okhttp`() {
        assertBusinessNotReplayed(
            NotificationActions.formReply("ses_1", "form_1", "choice", "yes"),
            "/api/session/ses_1/form/form_1/reply",
        )
    }

    @Test
    fun `the origin helper keeps only a canonical plain loopback origin`() {
        assertEquals("http://127.0.0.1:3000/", canonicalLoopbackOrigin("http://127.0.0.1:3000")?.toString())
        assertNull(canonicalLoopbackOrigin("https://127.0.0.1:3000"))
        assertNull(canonicalLoopbackOrigin("http://example.com:3000"))
        assertNull(canonicalLoopbackOrigin(null))
    }
}
