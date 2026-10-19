package io.andchamber.app.data

import io.andchamber.app.runtime.WebSessionCookies
import io.andchamber.app.runtime.WebSessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HttpDownloadTransferTest {
    private lateinit var first: MockWebServer
    private lateinit var second: MockWebServer
    private val transfer = HttpDownloadTransfer(newHttpDownloadClient())

    private class FakeCookies(origin: String) : WebSessionCookies {
        override val identity = WebSessionIdentity(targetId = "target", origin = origin)
        val queriedUrls = mutableListOf<String>()
        val stored = mutableListOf<String>()

        override suspend fun cookieHeader(url: String): String? {
            queriedUrls += url
            return "oc_ui_session_test=secret"
        }

        override suspend fun storeCookies(
            url: String,
            headers: List<String>,
        ) {
            stored += headers
        }
    }

    private class RecordingDestination : HttpDownloadDestination {
        val buffer = ByteArrayOutputStream()
        var created = false
        var committed = false
        var aborted = false

        override fun create(
            fileName: String,
            mimeType: String,
        ): PendingDownload =
            object : PendingDownload {
                init {
                    created = true
                }

                override fun openStream(): OutputStream = buffer

                override fun commit(): String {
                    committed = true
                    return fileName
                }

                override fun abort() {
                    aborted = true
                }
            }
    }

    @Before
    fun setUp() {
        first = MockWebServer().apply { start() }
        second = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        first.shutdown()
        second.shutdown()
    }

    private fun originOf(server: MockWebServer): String = canonicalWebOrigin(server.url("/").toString())!!

    private fun download(
        cookies: FakeCookies,
        url: String,
        destination: RecordingDestination,
    ): HttpDownloadResult =
        runBlocking {
            transfer.download(
                cookies,
                HttpDownloadRequest(url = url, fileName = "file.bin", mimeType = null, userAgent = "UA/1"),
                destination,
            )
        }

    @Test
    fun sameOriginDownloadCarriesCookieAcrossRedirect() {
        first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
        first.enqueue(MockResponse().setBody("payload"))
        val cookies = FakeCookies(originOf(first))
        val dest = RecordingDestination()

        val result = download(cookies, first.url("/start").toString(), dest)

        assertEquals(HttpDownloadResult.Saved("file.bin"), result)
        assertTrue(dest.committed)
        assertFalse(dest.aborted)
        assertEquals("payload", dest.buffer.toString(Charsets.UTF_8.name()))
        val start = first.takeRequest()
        val final = first.takeRequest()
        assertEquals("oc_ui_session_test=secret", start.getHeader("Cookie"))
        assertEquals("oc_ui_session_test=secret", final.getHeader("Cookie"))
        assertEquals("UA/1", final.getHeader("User-Agent"))
        assertNull(final.getHeader("Authorization"))
        assertNull(final.getHeader("Origin"))
        assertNull(final.getHeader("Referer"))
    }

    @Test
    fun nonBindingInitialUrlIsAnonymous() {
        first.enqueue(MockResponse().setBody("public"))
        val cookies = FakeCookies("https://somewhere-else.example")
        val dest = RecordingDestination()

        val result = download(cookies, first.url("/public").toString(), dest)

        assertTrue(result is HttpDownloadResult.Saved)
        assertNull(first.takeRequest().getHeader("Cookie"))
        assertTrue(cookies.queriedUrls.isEmpty())
    }

    @Test
    fun crossPortRedirectRemovesCookieForWholeChain() {
        // A (binding) -> B (other port) -> A again: only the first hop may carry the cookie.
        val backToA = first.url("/landing").toString()
        second.enqueue(MockResponse().setResponseCode(302).addHeader("Location", backToA))
        first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/hop").toString()))
        first.enqueue(MockResponse().setBody("ok"))
        val cookies = FakeCookies(originOf(first))
        val dest = RecordingDestination()

        val result = download(cookies, first.url("/start").toString(), dest)

        assertTrue(result is HttpDownloadResult.Saved)
        assertEquals("oc_ui_session_test=secret", first.takeRequest().getHeader("Cookie"))
        assertNull(second.takeRequest().getHeader("Cookie"))
        assertNull(first.takeRequest().getHeader("Cookie"))
        assertTrue(cookies.queriedUrls.all { it.startsWith(originOf(first)) })
    }

    @Test
    fun responseSetCookieIsNeitherStoredNorResent() {
        first.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Set-Cookie", "evil=1; Path=/")
                .addHeader("Location", "/next"),
        )
        first.enqueue(MockResponse().setBody("x"))
        val cookies = FakeCookies(originOf(first))

        download(cookies, first.url("/start").toString(), RecordingDestination())

        first.takeRequest()
        assertEquals("oc_ui_session_test=secret", first.takeRequest().getHeader("Cookie"))
        assertTrue(cookies.stored.isEmpty())
    }

    @Test
    fun redirectLoopFails() {
        repeat(6) { first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/loop")) }
        val dest = RecordingDestination()

        val result = download(FakeCookies(originOf(first)), first.url("/loop").toString(), dest)

        assertTrue(result is HttpDownloadResult.Failed)
        assertFalse(dest.committed)
    }

    @Test
    fun invalidLocationStopsChainWithoutFollowingIt() {
        // Only the scheme/userinfo refusal is exercised here; https-to-http downgrade is covered by
        // DownloadRedirectPolicy tests because MockWebServer has no TLS.
        first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "ftp://127.0.0.1/a"))
        val dest = RecordingDestination()

        val result = download(FakeCookies(originOf(first)), first.url("/x").toString(), dest)

        assertTrue(result is HttpDownloadResult.Failed)
        assertEquals(1, first.requestCount)
        assertFalse(dest.committed)
    }

    @Test
    fun httpErrorStatusFailsAndCommitsNothing() {
        first.enqueue(MockResponse().setResponseCode(403).setBody("no"))
        val dest = RecordingDestination()

        val result = download(FakeCookies(originOf(first)), first.url("/x").toString(), dest)

        assertEquals(HttpDownloadResult.Failed("HTTP 403"), result)
        assertFalse(dest.committed)
    }

    @Test
    fun truncatedBodyAbortsPartialOutput() {
        first.enqueue(
            MockResponse()
                .setBody("a".repeat(200_000))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val dest = RecordingDestination()

        val result = download(FakeCookies(originOf(first)), first.url("/x").toString(), dest)

        assertTrue(result is HttpDownloadResult.Failed)
        assertTrue(dest.aborted)
        assertFalse(dest.committed)
    }

    @Test
    fun cancellationAbortsPartialAndIsRethrown() {
        // The server answers at once; nothing waits on the server, so shutdown stays immediate. The
        // destination parks on its first write, after bytes reached the file. The test cancels while it
        // is parked and then releases it, so cancellation is observed at the next copy step. No sleeps.
        first.enqueue(MockResponse().setBody("partial-body"))
        val dest = ParkingDestination()
        var outcome: Result<HttpDownloadResult>? = null

        runBlocking {
            val job =
                launch(Dispatchers.IO) {
                    outcome =
                        runCatching {
                            transfer.download(
                                FakeCookies(originOf(first)),
                                HttpDownloadRequest(first.url("/slow").toString(), "slow.bin", null, null),
                                dest,
                            )
                        }
                }
            try {
                check(dest.parked.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS)) { "download never reached the body write" }
                job.cancel()
            } finally {
                dest.release.countDown()
            }
            job.join()
        }

        val error = outcome?.exceptionOrNull()
        assertTrue("cancellation must propagate, got $outcome", error is CancellationException)
        assertTrue("partial bytes must exist before cancel", dest.buffer.size() > 0)
        assertTrue(dest.created)
        assertTrue(dest.aborted)
        assertFalse(dest.committed)
    }

    /** Parks the first body write until the test releases it; bounded so a broken test fails instead of hanging. */
    private class ParkingDestination : HttpDownloadDestination {
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val buffer = ByteArrayOutputStream()
        var created = false
        var committed = false
        var aborted = false

        override fun create(
            fileName: String,
            mimeType: String,
        ): PendingDownload {
            created = true
            return object : PendingDownload {
                override fun openStream(): OutputStream =
                    object : OutputStream() {
                        private var parkedOnce = false

                        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                        override fun write(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ) {
                            buffer.write(b, off, len)
                            if (!parkedOnce) {
                                parkedOnce = true
                                parked.countDown()
                                check(release.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS)) { "barrier was never released" }
                            }
                        }
                    }

                override fun commit(): String {
                    committed = true
                    return fileName
                }

                override fun abort() {
                    aborted = true
                }
            }
        }
    }

    private companion object {
        const val BARRIER_WAIT_SECONDS = 10L
    }
}
