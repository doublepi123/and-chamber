package io.andchamber.app.browser.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class ProviderHttpTransportTest {
    @Test
    fun `same connection yields ready then the request and the token stays out of the url`() = runBlocking {
        val server = server()
        try {
            server.enqueue(sse(ready("e1", "c1") + requestEvent("e1", "c1", "r1"), hang = true))
            val transport = transport(server, maxRetries = 0)
            val seen = mutableListOf<String>()
            val done = CompletableDeferred<Unit>()
            val job = launch {
                withTimeout(3_000) {
                    transport.events().collect { event ->
                        seen += event
                        if (seen.size == 2) done.complete(Unit)
                    }
                }
            }
            withTimeout(3_000) { done.await() }
            assertTrue(ProviderProtocol.parseReady(seen[0])?.connectionId == "c1")
            assertEquals("r1", ProviderProtocol.parseRequest(seen[1], "c1")?.requestId)
            val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/openchamber/events?provider=android", recorded.path)
            assertEquals("secret-token", recorded.getHeader("X-AndChamber-Browser-Token"))
            assertNull(recorded.getHeader("Origin"))
            assertFalse(recorded.path.orEmpty().contains("secret-token"))
            assertFalse(recorded.requestUrl.toString().contains("secret-token"))
            transport.stop()
            job.cancel()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `chunked lines still assemble one ready event`() = runBlocking {
        val server = server()
        try {
            val body = ready("epoch", "conn-9") + "\n"
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setChunkedBody(body, 5)
                    .setSocketPolicy(SocketPolicy.KEEP_OPEN),
            )
            val transport = transport(server, maxRetries = 0)
            val event = withTimeout(3_000) { transport.events().first() }
            assertEquals("conn-9", ProviderProtocol.parseReady(event)?.connectionId)
            assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Origin"))
            transport.stop()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an oversized event is dropped and the next event is kept`() = runBlocking {
        val server = server()
        try {
            val huge = "data: " + "x".repeat(ProviderProtocol.MAX_SSE_EVENT_CHARS + 8) + "\n\n"
            server.enqueue(sse(ready("e1", "c1") + huge + requestEvent("e1", "c1", "kept"), hang = true))
            val transport = transport(server, maxRetries = 0)
            val seen = mutableListOf<String>()
            val done = CompletableDeferred<Unit>()
            val job = launch {
                transport.events().collect { event ->
                    seen += event
                    if (ProviderProtocol.parseRequest(event, "c1")?.requestId == "kept") done.complete(Unit)
                }
            }
            withTimeout(3_000) { done.await() }
            assertEquals("kept", seen.mapNotNull { ProviderProtocol.parseRequest(it, "c1")?.requestId }.single())
            job.cancel()
            transport.stop()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a redirect is refused and the token is not sent onward`() = runBlocking {
        val server = server()
        val elsewhere = server()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/stolen").toString()))
            elsewhere.enqueue(MockResponse().setResponseCode(200).setBody("nope"))
            val transport = transport(server, maxRetries = 0)
            val failed = async { runCatching { withTimeout(2_000) { transport.events().first() } } }
            val recorded = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }!!
            assertEquals("secret-token", recorded.getHeader("X-AndChamber-Browser-Token"))
            assertNull(recorded.getHeader("Origin"))
            assertFalse(recorded.path.orEmpty().contains("secret-token"))
            assertTrue(failed.await().isFailure)
            assertEquals(0, elsewhere.requestCount)
            transport.stop()
        } finally {
            server.shutdown()
            elsewhere.shutdown()
        }
    }

    @Test
    fun `disconnect then a new ready is a new connection and the old request is not replayed`() = runBlocking {
        val server = server()
        try {
            server.enqueue(sse(ready("e1", "c-old") + requestEvent("e1", "c-old", "r-old"), hang = false))
            server.enqueue(sse(ready("e2", "c-new") + requestEvent("e2", "c-new", "r-new"), hang = true))
            val transport = transport(server, maxRetries = 1, retryDelayMs = 20)
            val seen = mutableListOf<String>()
            val done = CompletableDeferred<Unit>()
            val job = launch {
                withTimeout(4_000) {
                    transport.events().collect { event ->
                        seen += event
                        if (ProviderProtocol.parseRequest(event, "c-new")?.requestId == "r-new") done.complete(Unit)
                    }
                }
            }
            withTimeout(4_000) { done.await() }
            val ids = seen.mapNotNull { ProviderProtocol.parseReady(it)?.connectionId }
            assertEquals(listOf("c-old", "c-new"), ids)
            val requestIds = seen.mapNotNull { event ->
                ProviderProtocol.parseRequest(event, "c-old")?.requestId
                    ?: ProviderProtocol.parseRequest(event, "c-new")?.requestId
            }
            assertEquals(listOf("r-old", "r-new"), requestIds)
            assertEquals(2, server.requestCount)
            transport.stop()
            job.cancel()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `stop closes a long stream and claim posts nonce without Origin`() = runBlocking {
        val server = server()
        try {
            server.enqueue(sse(ready("e1", "c1"), hang = true))
            server.enqueue(MockResponse().setBody("""{"granted":true,"claimNonce":"nonce-1","remainingMs":900}"""))
            val transport = transport(server, maxRetries = 0)
            val started = CompletableDeferred<Unit>()
            val job = launch {
                transport.events().collect {
                    started.complete(Unit)
                }
            }
            withTimeout(3_000) { started.await() }
            val claim = withTimeout(2_000) { transport.claim("r1", "c1") }
            assertEquals(true, claim?.granted)
            assertEquals("nonce-1", claim?.nonce)
            server.takeRequest(1, TimeUnit.SECONDS)
            val claimRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/browser-control/claim", claimRequest.path)
            assertEquals("secret-token", claimRequest.getHeader("X-AndChamber-Browser-Token"))
            assertNull(claimRequest.getHeader("Origin"))
            assertFalse(claimRequest.body.readUtf8().contains("secret-token"))
            transport.stop()
            withTimeout(2_000) { job.join() }
        } finally {
            server.shutdown()
        }
    }

    private fun server(): MockWebServer = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

    private fun transport(server: MockWebServer, maxRetries: Int, retryDelayMs: Long = 500L): ProviderHttpTransport {
        val raw = server.url("/").toString()
        val origin = "http://127.0.0.1:" + raw.substringAfterLast(':').removeSuffix("/")
        check(ProviderProtocol.isLoopbackOrigin(origin)) { raw }
        return ProviderHttpTransport(
            origin = origin,
            token = "secret-token",
            retryDelayMs = retryDelayMs,
            maxRetries = maxRetries,
            connectTimeoutMs = 1_000,
            readTimeoutMs = 0,
            postTimeoutMs = 1_000,
        )
    }

    private fun sse(body: String, hang: Boolean): MockResponse {
        val response = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
        if (hang) response.setSocketPolicy(SocketPolicy.KEEP_OPEN)
        return response
    }

    private fun ready(epoch: String, connection: String) =
        "data: {\"type\":\"openchamber:event-stream-ready\",\"properties\":{\"protocolVersion\":1,\"serverEpoch\":\"$epoch\",\"connectionId\":\"$connection\"}}\n\n"

    private fun requestEvent(epoch: String, connection: String, id: String) =
        "data: {\"type\":\"openchamber:browser-control-request\",\"properties\":{\"protocolVersion\":1,\"serverEpoch\":\"$epoch\",\"connectionId\":\"$connection\",\"requestId\":\"$id\",\"action\":\"browser.snapshot\",\"parameters\":{},\"context\":{\"directory\":\"/root\",\"sessionId\":\"s1\"},\"deadlineAtEpochMs\":10,\"timeoutMs\":1000}}\n\n"

}
