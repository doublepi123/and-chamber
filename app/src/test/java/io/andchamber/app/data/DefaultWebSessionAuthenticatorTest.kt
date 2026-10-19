package io.andchamber.app.data

import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionCookies
import io.andchamber.app.runtime.WebSessionIdentity
import io.andchamber.app.runtime.WebSessionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Auth against an explicit per-profile cookie capability. The fake records what is stored and where;
 * it never touches Android. Fake credentials only, loopback MockWebServer, shut down in tearDown.
 */
class DefaultWebSessionAuthenticatorTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()

    /** Stores what the authenticator asks to write. Cookie reads only succeed on the bound origin. */
    private class FakeCookies(override val identity: WebSessionIdentity) : WebSessionCookies {
        val stored = mutableListOf<Pair<String, List<String>>>()
        var jar: String? = null

        override suspend fun cookieHeader(url: String): String? =
            if (canonicalWebOrigin(url) == identity.origin) jar else null

        override suspend fun storeCookies(
            url: String,
            headers: List<String>,
        ) {
            require(canonicalWebOrigin(url) == identity.origin) { "wrong origin" }
            stored += url to headers
            jar = headers.firstOrNull()?.substringBefore(';')
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

    private fun local(): ServerTarget = ServerTarget.Local(base())

    private fun remote(uiPassword: String?): ServerTarget = ServerTarget.Remote(id = "r1", displayName = "LAN", baseUrl = base(), uiPassword = uiPassword)

    private fun cookiesFor(target: ServerTarget) = FakeCookies(WebSessionIdentity(target.id, canonicalWebOrigin(target.baseUrl)!!))

    private fun health(ok: Boolean = true) =
        MockResponse().setBody(if (ok) """{"status":"ok","openchamberVersion":"2.4.0"}""" else """{"status":"down"}""")

    private fun loginOk(cookie: String = "oc_ui_session_3000=abc; Path=/; HttpOnly") =
        MockResponse().setBody("""{"success":true}""").addHeader("Set-Cookie", cookie)

    private fun auth(
        localPassword: () -> String? = { "local-secret" },
        ready: LocalReadiness = LocalReadiness { 7L },
        remoteStored: (ServerTarget.Remote) -> String? = { it.uiPassword },
    ) = DefaultWebSessionAuthenticator(client, localPassword, ready, remoteStored)

    // --- identity guard: no request, no secret read ---

    @Test
    fun `cookies bound to another target make no request and read no password`() =
        runBlocking {
            var reads = 0
            val target = local()
            val wrong = FakeCookies(WebSessionIdentity("other", canonicalWebOrigin(target.baseUrl)!!))

            val result = auth(localPassword = { reads++; "pw" }).prepare(target, wrong)

            assertTrue(result is WebSessionResult.Unreachable)
            assertEquals(0, server.requestCount)
            assertEquals(0, reads)
            assertTrue(wrong.stored.isEmpty())
        }

    @Test
    fun `cookies bound to another origin make no request and read no remote password`() =
        runBlocking {
            var reads = 0
            val target = remote("stored")
            val wrong = FakeCookies(WebSessionIdentity(target.id, "http://127.0.0.1:1"))

            val result = auth(remoteStored = { reads++; it.uiPassword }).prepare(target, wrong)

            assertTrue(result is WebSessionResult.Unreachable)
            assertEquals(0, server.requestCount)
            assertEquals(0, reads)
        }

    @Test
    fun `a target url that is not a plain http origin makes no request`() =
        runBlocking {
            val target = ServerTarget.Local("http://user:pw@127.0.0.1:3000")
            val cookies = FakeCookies(WebSessionIdentity(target.id, "http://127.0.0.1:3000"))

            assertTrue(auth().prepare(target, cookies) is WebSessionResult.Unreachable)
            assertEquals(0, server.requestCount)
        }

    // --- health and login ---

    @Test
    fun `health failure is Unreachable and stores nothing`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))
            val target = local()
            val cookies = cookiesFor(target)

            val result = auth().prepare(target, cookies)

            assertTrue(result is WebSessionResult.Unreachable)
            assertEquals(1, server.requestCount)
            assertTrue(cookies.stored.isEmpty())
        }

    @Test
    fun `unparseable health body is Unreachable`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("<html>not openchamber</html>"))
            val target = local()

            assertTrue(auth().prepare(target, cookiesFor(target)) is WebSessionResult.Unreachable)
        }

    @Test
    fun `login stores every set-cookie through the bound capability and reports Ready`() =
        runBlocking {
            server.enqueue(health())
            server.enqueue(
                loginOk().addHeader("Set-Cookie", "extra=1; Path=/"),
            )
            val target = local()
            val cookies = cookiesFor(target)

            val result = auth().prepare(target, cookies)

            assertEquals(WebSessionResult.Ready(base()), result)
            assertEquals(1, cookies.stored.size)
            assertEquals(base(), cookies.stored[0].first)
            assertEquals(2, cookies.stored[0].second.size)
            assertEquals("oc_ui_session_3000=abc; Path=/; HttpOnly", cookies.stored[0].second[0])

            server.takeRequest()
            val login = server.takeRequest()
            assertEquals("POST", login.method)
            assertEquals("/auth/session", login.path)
            val body = login.body.readUtf8()
            assertTrue(body, body.contains("\"password\":\"local-secret\""))
            assertTrue(body, body.contains("\"trustDevice\":true"))
        }

    @Test
    fun `wrong password is NeedsLogin and stores nothing`() =
        runBlocking {
            server.enqueue(health())
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid credentials"}"""))
            val target = local()
            val cookies = cookiesFor(target)

            assertEquals(WebSessionResult.NeedsLogin(base()), auth(localPassword = { "wrong" }).prepare(target, cookies))
            assertTrue(cookies.stored.isEmpty())
        }

    @Test
    fun `a redirect on login is NeedsLogin, stores no cookie and never reaches the redirect target`() =
        runBlocking {
            val elsewhere = MockWebServer()
            elsewhere.start()
            try {
                server.enqueue(health())
                server.enqueue(
                    MockResponse().setResponseCode(302)
                        .addHeader("Location", elsewhere.url("/auth/session").toString())
                        .addHeader("Set-Cookie", "trap=1; Path=/"),
                )
                val target = local()
                val cookies = cookiesFor(target)

                assertEquals(WebSessionResult.NeedsLogin(base()), auth().prepare(target, cookies))
                assertTrue(cookies.stored.isEmpty())
                assertEquals(0, elsewhere.requestCount)
            } finally {
                elsewhere.shutdown()
            }
        }

    @Test
    fun `a redirect on health is Unreachable and is not followed`() =
        runBlocking {
            val elsewhere = MockWebServer()
            elsewhere.start()
            try {
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", elsewhere.url("/health").toString()))
                val target = local()

                assertTrue(auth().prepare(target, cookiesFor(target)) is WebSessionResult.Unreachable)
                assertEquals(0, elsewhere.requestCount)
            } finally {
                elsewhere.shutdown()
            }
        }

    // --- session probe without a password ---

    @Test
    fun `remote without a stored password is Ready when the bound profile already has a session`() =
        runBlocking {
            server.enqueue(health())
            server.enqueue(MockResponse().setBody("""{"authenticated":true}"""))
            val target = remote(null)
            val cookies = cookiesFor(target)
            cookies.jar = "oc_ui_session_3000=kept"

            val result = auth().prepare(target, cookies)

            assertEquals(WebSessionResult.Ready(base()), result)
            server.takeRequest()
            assertEquals("oc_ui_session_3000=kept", server.takeRequest().getHeader("Cookie"))
        }

    @Test
    fun `remote without a stored password and no session is NeedsLogin`() =
        runBlocking {
            server.enqueue(health())
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"authenticated":false,"locked":true}"""))
            val target = remote(null)

            assertEquals(WebSessionResult.NeedsLogin(base()), auth().prepare(target, cookiesFor(target)))
        }

    @Test
    fun `remote with stored password logs in with that password`() =
        runBlocking {
            server.enqueue(health())
            server.enqueue(MockResponse().setBody("""{"success":true}""").addHeader("Set-Cookie", "s=1; Path=/"))
            val target = remote("stored")
            val cookies = cookiesFor(target)

            assertEquals(WebSessionResult.Ready(base()), auth().prepare(target, cookies))
            server.takeRequest()
            assertTrue(server.takeRequest().body.readUtf8().contains("\"password\":\"stored\""))
            assertEquals(1, cookies.stored.size)
        }

    // --- local readiness ---

    @Test
    fun `ready null sends no password and makes only the health probe`() =
        runBlocking {
            server.enqueue(health())
            var reads = 0
            val target = local()
            val cookies = cookiesFor(target)

            val result = auth(localPassword = { reads++; "pw" }, ready = LocalReadiness { null }).prepare(target, cookies)

            assertTrue(result is WebSessionResult.Unreachable)
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
            assertEquals(0, reads)
            assertTrue(cookies.stored.isEmpty())
        }

    @Test
    fun `a throwing readiness provider is treated as not trusted`() =
        runBlocking {
            server.enqueue(health())
            val target = local()

            val result = auth(ready = LocalReadiness { error("boom") }).prepare(target, cookiesFor(target))

            assertTrue(result is WebSessionResult.Unreachable)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a launch replaced just before the POST sends no password`() =
        runBlocking {
            var calls = 0
            server.enqueue(health())
            val target = local()

            val result =
                auth(ready = LocalReadiness { if (++calls == 1) 7L else 8L }).prepare(target, cookiesFor(target))

            assertTrue(result is WebSessionResult.Unreachable)
            assertEquals("health only, no POST", 1, server.requestCount)
        }

    @Test
    fun `a launch that changes while the login is in flight stores no cookie`() =
        runBlocking {
            var calls = 0
            server.enqueue(health())
            server.enqueue(loginOk())
            val target = local()
            val cookies = cookiesFor(target)

            // Trusted for both pre-POST checks, changed by the time the response is handled.
            val result = auth(ready = LocalReadiness { if (++calls <= 2) 7L else 8L }).prepare(target, cookies)

            assertTrue(result is WebSessionResult.Unreachable)
            assertTrue(cookies.stored.isEmpty())
        }

    // --- cancellation and serialization ---

    @Test
    fun `a cancelled attempt whose login completes later stores no cookie`() =
        runBlocking {
            val posted = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "GET") return health()
                        posted.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        return loginOk()
                    }
                }
            val target = local()
            val cookies = cookiesFor(target)
            val authenticator = auth()

            val job = launch(Dispatchers.Default) { authenticator.prepare(target, cookies) }
            assertTrue(posted.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()

            assertTrue("a cancelled attempt must not write cookies", cookies.stored.isEmpty())
        }

    @Test
    fun `a second attempt waits for the first on the same identity and the newest login is stored last`() =
        runBlocking {
            val release = CountDownLatch(1)
            val firstPosted = CountDownLatch(1)
            var posts = 0
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "GET") return health()
                        val n = synchronized(this) { ++posts }
                        if (n == 1) {
                            firstPosted.countDown()
                            release.await(5, TimeUnit.SECONDS)
                            return loginOk("oc_ui_session_3000=first; Path=/")
                        }
                        return loginOk("oc_ui_session_3000=second; Path=/")
                    }
                }
            val target = local()
            val cookies = cookiesFor(target)
            val authenticator = auth()

            val first = async(Dispatchers.Default) { authenticator.prepare(target, cookies) }
            assertTrue(firstPosted.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) { authenticator.prepare(target, cookies) }
            delay(300)
            assertEquals("second attempt must not reach the server while the first holds the lock", 2, server.requestCount)

            release.countDown()
            assertEquals(WebSessionResult.Ready(base()), first.await())
            assertEquals(WebSessionResult.Ready(base()), second.await())
            assertEquals(listOf("oc_ui_session_3000=first; Path=/", "oc_ui_session_3000=second; Path=/"), cookies.stored.map { it.second.single() })
            assertEquals("oc_ui_session_3000=second", cookies.jar)
        }

    // --- transport and client policy ---

    @Test
    fun `plain http to a non-loopback remote is refused without the insecure flag`() {
        assertFalse(okhttp3.HttpUrl.Builder().scheme("http").host("192.168.1.20").port(3000).build().isTransportSafe())
        assertTrue(okhttp3.HttpUrl.Builder().scheme("https").host("example.com").build().isTransportSafe())
        assertTrue(okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(3000).build().isTransportSafe())
        assertTrue(okhttp3.HttpUrl.Builder().scheme("http").host("localhost").port(3000).build().isTransportSafe())
    }

    @Test
    fun `the authenticator's own client has no cookie jar and follows no redirects`() {
        val authenticator = auth()
        val field = DefaultWebSessionAuthenticator::class.java.getDeclaredField("authClient").apply { isAccessible = true }
        val used = field.get(authenticator) as OkHttpClient
        assertTrue(used.cookieJar === CookieJar.NO_COOKIES)
        assertFalse(used.followRedirects)
        assertFalse(used.followSslRedirects)
    }

    @Test
    fun `unparseable base url is Unreachable without any request`() =
        runBlocking {
            val target = ServerTarget.Local("not a url")
            val cookies = FakeCookies(WebSessionIdentity(target.id, "http://127.0.0.1:1"))

            assertTrue(auth().prepare(target, cookies) is WebSessionResult.Unreachable)
            assertEquals(0, server.requestCount)
            assertNull(cookies.jar)
        }
}
