package io.andchamber.app.browser.tool

import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderExecutor
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.browser.provider.ProviderRegistry
import io.andchamber.app.browser.provider.ProviderRequest
import io.andchamber.app.browser.provider.ProviderResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowserToolServerTest {
    private val token = "b".repeat(64)

    private class RecordingExecutor(
        private val respond: suspend (ProviderRequest) -> ProviderResult = {
            ProviderResult(ok = true, data = buildJsonObject { put("ok", true) })
        },
    ) : ProviderExecutor {
        val requests = mutableListOf<ProviderRequest>()
        val calls = AtomicInteger()

        override suspend fun execute(
            request: ProviderRequest,
            registry: ProviderRegistry,
            mode: ProviderMode,
            visibleTabId: String?,
            deadlineElapsed: Long,
            clock: () -> Long,
        ): ProviderResult {
            requests += request
            calls.incrementAndGet()
            return respond(request)
        }
    }

    private fun server(
        executor: ProviderExecutor = RecordingExecutor(),
        onCancel: (String) -> Unit = {},
    ): BrowserToolServer =
        BrowserToolServer(
            launchId = 7L,
            token = token,
            executor = executor,
            registry = ProviderRegistry(),
            clock = { 0L },
            onCancel = onCancel,
        )

    @Test
    fun `without a connection the tool answers unavailable and does not touch the executor`() {
        val executor = RecordingExecutor()
        val server = server(executor)
        val port = server.start()
        try {
            val reply = post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("call-1"))
            assertEquals(200, reply.status)
            val body = Json.parseToJsonElement(reply.body).jsonObject
            assertFalse(body.getValue("ok").jsonPrimitive.content.toBoolean())
            assertEquals(
                "BROWSER_PAGE_UNAVAILABLE",
                body.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
            )
            assertEquals(0, executor.calls.get())
            assertFalse(reply.body.contains(token))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `maps the plugin call to a provider request and keeps parameter text`() {
        val executor = RecordingExecutor()
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            val reply =
                post(
                    port,
                    BrowserToolEndpoint.TOOL_PATH,
                    """{"version":1,"sessionID":"session-1","callID":"call_1","input":{"action":"click","selector":"#a","submit":true,"n":3,"skip":null}}""",
                )
            assertEquals(200, reply.status)
            assertTrue(Json.parseToJsonElement(reply.body).jsonObject.getValue("ok").jsonPrimitive.content.toBoolean())

            val request = executor.requests.single()
            assertEquals("launch-7", request.serverEpoch)
            assertEquals("conn-1", request.connectionId)
            assertEquals("call_1", request.requestId)
            assertEquals("browser.click", request.action)
            assertEquals("session-1", request.sessionId)
            assertEquals("", request.directory)
            assertEquals(20_000L, request.timeoutMs)
            assertEquals("#a", request.parameters["selector"])
            assertEquals("true", request.parameters["submit"])
            assertEquals("3", request.parameters["n"])
            assertFalse(request.parameters.containsKey("skip"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `open gets the longer timeout and an unsafe callID becomes a digest`() {
        val executor = RecordingExecutor()
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            val unsafe = "call id with spaces.and/more"
            post(port, BrowserToolEndpoint.TOOL_PATH, toolBody(unsafe, action = "open"))
            val request = executor.requests.single()
            assertEquals(45_000L, request.timeoutMs)
            assertEquals("browser.open", request.action)
            assertTrue(request.requestId.matches(Regex("^c-[0-9a-f]{40}$")))
            assertNotEquals(unsafe, request.requestId)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `an open allow wait is added to the page load timeout`() {
        val executor = RecordingExecutor()
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            val reply = post(
                port,
                BrowserToolEndpoint.TOOL_PATH,
                toolBody("wait-call", action = "open", allowWaitMs = "60000"),
            )
            assertEquals(200, reply.status)
            val request = executor.requests.single()
            assertEquals(60_000, request.allowWaitMs)
            assertEquals(105_000L, request.timeoutMs)
            assertEquals("browser.open", request.action)
            assertFalse("the wait is not a page parameter", request.parameters.containsKey("allowWaitMs"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `absent or zero allow wait keeps the plain open timeout`() {
        val executor = RecordingExecutor()
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("no-wait", action = "open"))
            post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("zero-wait", action = "open", allowWaitMs = "0"))
            assertEquals(listOf(0, 0), executor.requests.map { it.allowWaitMs })
            assertEquals(listOf(45_000L, 45_000L), executor.requests.map { it.timeoutMs })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `an allow wait outside the accepted range is refused before the executor`() {
        val executor = RecordingExecutor()
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            val cases = listOf("-1", "120001", "1.5", "\"60000\"", "true", "null")
            cases.forEachIndexed { index, raw ->
                val reply =
                    post(
                        port,
                        BrowserToolEndpoint.TOOL_PATH,
                        toolBody("bad-wait-$index", action = "open", allowWaitMs = raw),
                    )
                assertEquals("allowWaitMs=$raw", 400, reply.status)
                assertEquals("allowWaitMs=$raw", ProviderCodes.INVALID_REQUEST, code(reply))
            }
            assertEquals("no invalid wait ever reaches the page", 0, executor.calls.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `an allow wait at the range bounds is accepted`() {
        val executor = RecordingExecutor()
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("min-wait", action = "open", allowWaitMs = "0"))
            post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("max-wait", action = "open", allowWaitMs = "120000"))
            assertEquals(120_000, executor.requests.last().allowWaitMs)
            assertEquals(45_000L + 120_000L, executor.requests.last().timeoutMs)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a repeated callID is refused and never executes twice`() {
        val executor = RecordingExecutor()
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            val first = post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("same-call"))
            assertEquals(200, first.status)
            val second = post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("same-call"))
            assertEquals(200, second.status)

            assertEquals(1, executor.calls.get())
            val body = Json.parseToJsonElement(second.body).jsonObject
            assertEquals("DUPLICATE_CALL", body.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `the same callID in different sessions executes even after the first completes`() {
        val executor = RecordingExecutor()
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            val first = post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("shared-call", sessionId = "session-1"))
            val second = post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("shared-call", sessionId = "session-2"))

            assertEquals(200, first.status)
            assertEquals(200, second.status)
            assertEquals(null, code(first))
            assertEquals(null, code(second))
            assertEquals(2, executor.calls.get())
            assertEquals(listOf("session-1", "session-2"), executor.requests.map { it.sessionId })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `concurrent identical calls execute once and the rest are DUPLICATE_CALL`() {
        val executor = RecordingExecutor {
            Thread.sleep(100)
            ProviderResult(ok = true, data = buildJsonObject { put("ok", true) })
        }
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        val workers = 8
        val gate = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val replies = Collections.synchronizedList(mutableListOf<Reply>())
        try {
            repeat(workers) {
                Thread {
                    gate.await()
                    runCatching { replies += post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("same-call")) }
                    done.countDown()
                }.apply { isDaemon = true; start() }
            }
            gate.countDown()
            assertTrue("workers did not finish", done.await(10, TimeUnit.SECONDS))

            assertEquals(workers, replies.size)
            assertEquals("only one claim may execute", 1, executor.calls.get())
            assertEquals(workers - 1, replies.count { code(it) == BrowserToolServer.DUPLICATE_CALL })
            replies.forEach { reply ->
                assertEquals(200, reply.status)
                val body = Json.parseToJsonElement(reply.body).jsonObject
                assertEquals("browser.snapshot", body.getValue("action").jsonPrimitive.content)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a duplicate call envelope carries the action so the plugin accepts it`() {
        val executor = RecordingExecutor()
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("dup-action"))
            val second = post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("dup-action"))

            val body = Json.parseToJsonElement(second.body).jsonObject
            assertEquals(1, body.getValue("version").jsonPrimitive.content.toInt())
            assertEquals("false", body.getValue("ok").jsonPrimitive.content)
            assertEquals("browser.snapshot", body.getValue("action").jsonPrimitive.content)
            assertEquals(BrowserToolServer.DUPLICATE_CALL, code(second))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a cancel that arrives before registration refuses the late request`() {
        val executor = RecordingExecutor()
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            val cancel = post(port, BrowserToolEndpoint.CANCEL_PATH, cancelBody("session-1", "early-call"))
            assertEquals(200, cancel.status)
            assertFalse(
                Json.parseToJsonElement(cancel.body).jsonObject.getValue("cancelled").jsonPrimitive.content.toBoolean(),
            )

            val reply = post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("early-call"))
            val body = Json.parseToJsonElement(reply.body).jsonObject
            assertEquals("false", body.getValue("ok").jsonPrimitive.content)
            assertEquals("browser.snapshot", body.getValue("action").jsonPrimitive.content)
            assertEquals(ProviderCodes.CANCELLED, code(reply))
            assertEquals("a tombstoned request never executes", 0, executor.calls.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a cancelled key is never re-admitted after many other cancels`() {
        val executor = RecordingExecutor()
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            post(port, BrowserToolEndpoint.CANCEL_PATH, cancelBody("session-1", "over-cancelled"))
            // More tombstones than the old fixed capacity; eviction must not re-admit the key.
            repeat(1_025) { post(port, BrowserToolEndpoint.CANCEL_PATH, cancelBody("session-1", "other-$it")) }

            val reply = post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("over-cancelled"))
            assertEquals(200, reply.status)
            assertEquals(ProviderCodes.CANCELLED, code(reply))
            assertEquals("an evicted cancel must never execute", 0, executor.calls.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a cancel for another session does not cancel this session's call`() {
        val started = CountDownLatch(1)
        val presenterCancels = AtomicInteger()
        val executor =
            object : ProviderExecutor {
                override suspend fun execute(
                    request: ProviderRequest,
                    registry: ProviderRegistry,
                    mode: ProviderMode,
                    visibleTabId: String?,
                    deadlineElapsed: Long,
                    clock: () -> Long,
                ): ProviderResult {
                    started.countDown()
                    awaitCancellation()
                }
            }
        val server = server(executor, onCancel = { presenterCancels.incrementAndGet() })
        val port = server.start()
        server.setConnection("conn-1")
        try {
            val worker =
                Thread {
                    runCatching { post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("shared-call")) }
                }.apply { isDaemon = true; start() }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            val wrong = post(port, BrowserToolEndpoint.CANCEL_PATH, cancelBody("session-2", "shared-call"))
            assertFalse(
                "another session must not cancel this call",
                Json.parseToJsonElement(wrong.body).jsonObject.getValue("cancelled").jsonPrimitive.content.toBoolean(),
            )
            assertEquals("presenter cancellation is never called for a mismatch", 0, presenterCancels.get())

            val right = post(port, BrowserToolEndpoint.CANCEL_PATH, cancelBody("session-1", "shared-call"))
            assertTrue(
                Json.parseToJsonElement(right.body).jsonObject.getValue("cancelled").jsonPrimitive.content.toBoolean(),
            )
            assertEquals(1, presenterCancels.get())
            worker.join(5_000)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `capture response keeps the base64 image payload`() {
        val executor = RecordingExecutor {
            ProviderResult(
                ok = true,
                data = buildJsonObject {
                    put("base64", "aGVsbG8=")
                    put("mime", "image/png")
                },
            )
        }
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            val reply = post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("cap-1", action = "capture"))
            val body = Json.parseToJsonElement(reply.body).jsonObject
            assertEquals("aGVsbG8=", body.getValue("data").jsonObject.getValue("base64").jsonPrimitive.content)
            assertEquals("browser.capture", body.getValue("action").jsonPrimitive.content)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `provider error messages never echo page text`() {
        val executor = RecordingExecutor {
            ProviderResult(false, errorCode = "AUTHORIZATION_REQUIRED", message = "page says SECRET")
        }
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            val reply = post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("denied-1"))
            assertFalse(reply.body.contains("SECRET"))
            val error = Json.parseToJsonElement(reply.body).jsonObject.getValue("error").jsonObject
            assertEquals("AUTHORIZATION_REQUIRED", error.getValue("code").jsonPrimitive.content)
            assertTrue(error.getValue("message").jsonPrimitive.content.isNotBlank())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `cancel stops the in-flight request and reports it`() {
        val started = CountDownLatch(1)
        val executor =
            object : ProviderExecutor {
                override suspend fun execute(
                    request: ProviderRequest,
                    registry: ProviderRegistry,
                    mode: ProviderMode,
                    visibleTabId: String?,
                    deadlineElapsed: Long,
                    clock: () -> Long,
                ): ProviderResult {
                    started.countDown()
                    awaitCancellation()
                }
            }
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            val worker =
                Thread {
                    runCatching { post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("slow-call")) }
                }.apply { isDaemon = true; start() }
            assertTrue("request never reached the executor", started.await(5, TimeUnit.SECONDS))

            val cancel =
                post(
                    port,
                    BrowserToolEndpoint.CANCEL_PATH,
                    """{"version":1,"sessionID":"session-1","callID":"slow-call"}""",
                )
            assertEquals(200, cancel.status)
            assertTrue(Json.parseToJsonElement(cancel.body).jsonObject.getValue("cancelled").jsonPrimitive.content.toBoolean())
            worker.join(5_000)
            assertFalse(worker.isAlive)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `dropping the connection cancels in-flight work`() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val executor =
            object : ProviderExecutor {
                override suspend fun execute(
                    request: ProviderRequest,
                    registry: ProviderRegistry,
                    mode: ProviderMode,
                    visibleTabId: String?,
                    deadlineElapsed: Long,
                    clock: () -> Long,
                ): ProviderResult {
                    started.countDown()
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.countDown()
                    }
                }
            }
        val server = server(executor)
        val port = server.start()
        server.setConnection("conn-1")
        try {
            val worker =
                Thread {
                    runCatching { post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("drop-call")) }
                }.apply { isDaemon = true; start() }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            server.setConnection(null)

            assertTrue("in-flight work survives a dropped connection", cancelled.await(5, TimeUnit.SECONDS))
            worker.join(5_000)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `auth host origin size method and path are rejected with fixed errors`() {
        val server = server()
        val port = server.start()
        server.setConnection("conn-1")
        try {
            assertEquals(401, post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("a"), token = null).status)
            assertEquals(401, post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("a"), token = "c".repeat(64)).status)
            assertEquals(403, post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("a"), origin = "http://example.com").status)
            assertEquals(403, post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("a"), host = "localhost:$port").status)
            assertEquals(404, post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("a"), method = "GET").status)
            assertEquals(404, post(port, "/v1/nope", toolBody("a")).status)
            assertEquals(413, post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("a"), chunked = true).status)
            assertEquals(
                413,
                post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("a"), contentLengthOverride = 65_537).status,
            )
            val unauthorized = post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("a"), token = null)
            assertFalse(unauthorized.body.contains(token))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `closed server stops accepting connections`() {
        val server = server()
        val port = server.start()
        server.stop()
        assertTrue(runCatching { post(port, BrowserToolEndpoint.TOOL_PATH, toolBody("a")) }.isFailure)
    }

    private fun cancelBody(
        sessionId: String,
        callId: String,
    ): String = """{"version":1,"sessionID":"$sessionId","callID":"$callId"}"""

    private fun code(reply: Reply): String? =
        runCatching {
            Json.parseToJsonElement(reply.body)
                .jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content
        }.getOrNull()

    private fun toolBody(
        callId: String,
        action: String = "snapshot",
        allowWaitMs: String? = null,
        sessionId: String = "session-1",
    ): String =
        buildJsonObject {
            put("version", 1)
            put("sessionID", sessionId)
            put("callID", callId)
            // Raw JSON so a test can send a type the contract does not accept.
            if (allowWaitMs != null) put("allowWaitMs", kotlinx.serialization.json.Json.parseToJsonElement(allowWaitMs))
            put(
                "input",
                buildJsonObject {
                    put("action", action)
                },
            )
        }.toString()

    private data class Reply(
        val status: Int,
        val body: String,
    )

    private fun post(
        port: Int,
        path: String,
        body: String,
        token: String? = this.token,
        host: String = "127.0.0.1:$port",
        origin: String? = null,
        method: String = "POST",
        chunked: Boolean = false,
        contentLengthOverride: Int? = null,
    ): Reply {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val bytes = body.toByteArray(Charsets.UTF_8)
            val headers =
                buildString {
                    append("$method $path HTTP/1.1\r\n")
                    append("Host: $host\r\n")
                    token?.let { append("Authorization: Bearer $it\r\n") }
                    origin?.let { append("Origin: $it\r\n") }
                    if (chunked) {
                        append("Transfer-Encoding: chunked\r\n")
                    } else {
                        append("Content-Length: ${contentLengthOverride ?: bytes.size}\r\n")
                    }
                    append("Content-Type: application/json\r\n")
                    append("\r\n")
                }
            val out = socket.getOutputStream()
            out.write(headers.toByteArray(Charsets.US_ASCII))
            if (!chunked && contentLengthOverride == null) out.write(bytes)
            out.flush()
            val input = BufferedInputStream(socket.getInputStream())
            val raw = input.readBytes()
            val text = raw.toString(Charsets.UTF_8)
            val status = text.lineSequence().firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull()
                ?: return Reply(0, "")
            return Reply(status, text.substringAfter("\r\n\r\n", ""))
        }
    }
}
