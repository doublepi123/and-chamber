package io.andchamber.app.browser.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ProviderClientTest {
    @Test
    fun `lost claim does not execute and unauthorized snapshot does not touch the page`() = runBlocking {
        val transport = ScriptTransport(claim = ProviderClaim(false, "", 0))
        val executor = RecordingExecutor()
        val client = client(transport, executor)
        client.start()
        transport.emit(ready("e1", "c1"))
        transport.emit(request("e1", "c1", "r1"))
        withTimeout(1_000) { transport.claimed.await() }
        delay(30)
        assertEquals(0, executor.calls.get())
        assertTrue(transport.results.isEmpty())
        client.stop()
    }

    @Test
    fun `null claim and http failure do not execute`() = runBlocking {
        val transport = ScriptTransport(claim = null)
        val executor = RecordingExecutor()
        val client = client(transport, executor)
        client.start()
        transport.emit(ready("e1", "c1"))
        transport.emit(request("e1", "c1", "r1"))
        withTimeout(1_000) { transport.claimed.await() }
        delay(30)
        assertEquals(0, executor.calls.get())
        assertTrue(transport.results.isEmpty())
        client.stop()
    }

    @Test
    fun `deadline after claim posts one error and does not retry or execute`() = runBlocking {
        var now = 0L
        val transport = ScriptTransport(
            claim = ProviderClaim(true, "nonce-1", 10),
            beforeClaim = { now = 1_000L },
        )
        val executor = RecordingExecutor()
        val client = BrowserProviderClient(
            origin = "http://127.0.0.1:3000",
            token = "secret-token",
            runtimeLaunchId = 1L,
            instanceKey = "local",
            clock = { now },
            transport = transport,
            executor = executor,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        client.start()
        transport.emit(ready("e1", "c1"))
        transport.emit(request("e1", "c1", "r1"))
        val posted = withTimeout(1_000) { transport.posted.await() }
        assertEquals(0, executor.calls.get())
        assertEquals(1, transport.results.size)
        assertEquals(ProviderCodes.DEADLINE, posted.errorCode)
        assertEquals("nonce-1", posted.nonce)
        delay(40)
        assertEquals(1, transport.resultCalls.get())
        client.stop()
    }

    @Test
    fun `claim success executes once and posts the nonce once`() = runBlocking {
        val transport = ScriptTransport(claim = ProviderClaim(true, "nonce-9", 5_000))
        val executor = RecordingExecutor()
        val client = client(transport, executor)
        client.start()
        transport.emit(ready("e1", "c1"))
        transport.emit(request("e1", "c1", "r1"))
        val posted = withTimeout(1_000) { transport.posted.await() }
        assertEquals(1, executor.calls.get())
        assertTrue(posted.result.ok)
        assertEquals("nonce-9", posted.nonce)
        assertEquals("c1", posted.connectionId)
        delay(40)
        assertEquals(1, transport.resultCalls.get())
        client.stop()
    }

    @Test
    fun `execute failure posts one outcome-unknown and does not retry`() = runBlocking {
        val transport = ScriptTransport(claim = ProviderClaim(true, "nonce-2", 5_000))
        val executor = RecordingExecutor(fail = true)
        val client = client(transport, executor)
        client.start()
        transport.emit(ready("e1", "c1"))
        transport.emit(request("e1", "c1", "r1"))
        val posted = withTimeout(1_000) { transport.posted.await() }
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, posted.errorCode)
        delay(40)
        assertEquals(1, transport.resultCalls.get())
        client.stop()
    }

    private val adopted = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Test
    fun `cancellation after claim posts no late result once the connection has changed`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = ScriptTransport(claim = ProviderClaim(true, "nonce-3", 5_000))
        val executor = RecordingExecutor(block = {
            started.complete(Unit)
            release.await()
        })
        val client = client(transport, executor)
        client.start()
        transport.emit(ready("e1", "c1"))
        transport.emit(request("e1", "c1", "r1"))
        withTimeout(1_000) { started.await() }
        transport.emit(ready("e2", "c2"))
        // Release only after the new connection is adopted; otherwise the old one is still current.
        withTimeout(1_000) { while (adopted.lastOrNull() != "c2") delay(5) }
        release.complete(Unit)
        delay(50)
        assertTrue(transport.results.isEmpty())
        client.stop()
    }

    @Test
    fun `a new connection ignores the old request and does not replay it`() = runBlocking {
        val transport = ScriptTransport(claim = ProviderClaim(true, "nonce-4", 5_000))
        val executor = RecordingExecutor()
        val client = client(transport, executor)
        client.start()
        transport.emit(ready("e1", "c-old"))
        transport.emit(ready("e2", "c-new"))
        transport.emit(request("e1", "c-old", "r-old"))
        transport.emit(request("e2", "c-new", "r-new"))
        val posted = withTimeout(1_000) { transport.posted.await() }
        assertEquals("r-new", posted.requestId)
        assertEquals("c-new", posted.connectionId)
        delay(40)
        assertEquals(listOf("r-new"), transport.results.map { it.requestId })
        assertEquals(1, executor.calls.get())
        client.stop()
    }

    @Test
    fun `a cancel event cancels the matching job and posts no late result`() = runBlocking {
        val cancelled = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = ScriptTransport(claim = ProviderClaim(true, "nonce-5", 5_000))
        val executor = RecordingExecutor(block = {
            started.complete(Unit)
            release.await()
        })
        val client = client(transport, executor, onCancel = { cancelled += it })
        client.start()
        transport.emit(ready("e1", "c1"))
        transport.emit(request("e1", "c1", "r1"))
        withTimeout(1_000) { started.await() }
        transport.emit(
            """{"type":"openchamber:browser-control-cancel","properties":{"protocolVersion":1,"serverEpoch":"e1","connectionId":"c1","requestId":"r1","reason":"timeout"}}""",
        )
        delay(40)
        release.complete(Unit)
        delay(40)
        assertTrue(transport.results.isEmpty())
        assertEquals(listOf("r1"), cancelled)
        client.stop()
    }

    @Test
    fun `disconnect after claim cancels work and a new ready does not inherit the grant`() = runBlocking {
        val connections = mutableListOf<String?>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = ScriptTransport(claim = ProviderClaim(true, "nonce-6", 5_000))
        val executor = RecordingExecutor(block = {
            started.complete(Unit)
            release.await()
        })
        val client = BrowserProviderClient(
            origin = "http://127.0.0.1:3000",
            token = "secret-token",
            runtimeLaunchId = 1L,
            instanceKey = "local",
            clock = { 0L },
            transport = transport,
            executor = executor,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onConnection = { connections += it },
        )
        client.start()
        transport.emit(ready("e1", "c1"))
        client.grant("session", "tab")
        transport.emit(request("e1", "c1", "r1"))
        withTimeout(1_000) { started.await() }
        transport.emit(ProviderProtocol.disconnectEvent())
        delay(40)
        assertTrue(transport.results.isEmpty())
        assertEquals(listOf("c1", null), connections)
        transport.emit(ready("e2", "c2"))
        delay(20)
        assertEquals(null, client.registry().currentGrant()?.connectionId)
        release.complete(Unit)
        client.stop()
    }

    private fun client(
        transport: ProviderTransport,
        executor: ProviderExecutor,
        onCancel: (String) -> Unit = {},
    ) = BrowserProviderClient(
        origin = "http://127.0.0.1:3000",
        token = "secret-token",
        runtimeLaunchId = 1L,
        instanceKey = "local",
        clock = { 0L },
        transport = transport,
        executor = executor,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        onConnection = { id -> if (id != null) adopted.add(id) },
        onCancel = onCancel,
    )

    private class RecordingExecutor(
        private val fail: Boolean = false,
        private val block: suspend () -> Unit = {},
    ) : ProviderExecutor {
        val calls = AtomicInteger(0)
        override suspend fun execute(
            request: ProviderRequest,
            registry: ProviderRegistry,
            mode: ProviderMode,
            visibleTabId: String?,
            deadlineElapsed: Long,
            clock: () -> Long,
        ): ProviderResult {
            calls.incrementAndGet()
            block()
            if (fail) error("execute failed")
            return ProviderResult(true, kotlinx.serialization.json.buildJsonObject {})
        }
    }

    private class Posted(
        val requestId: String,
        val connectionId: String,
        val nonce: String,
        val result: ProviderResult,
    ) {
        val errorCode: String? get() = result.errorCode
    }

    private class ScriptTransport(
        private val claim: ProviderClaim?,
        private val beforeClaim: () -> Unit = {},
    ) : ProviderTransport {
        val results = mutableListOf<Posted>()
        val resultCalls = AtomicInteger(0)
        val claimed = CompletableDeferred<Unit>()
        val posted = CompletableDeferred<Posted>()
        private val events = Channel<String>(8)

        override suspend fun open(url: String, headers: Map<String, String>): ProviderProtocol.Ready =
            error("events() is the only stream")

        override fun events(): Flow<String> = events.consumeAsFlow()

        override suspend fun claim(requestId: String, connectionId: String): ProviderClaim? {
            beforeClaim()
            claimed.complete(Unit)
            return claim
        }

        override suspend fun result(requestId: String, connectionId: String, nonce: String, result: ProviderResult) {
            resultCalls.incrementAndGet()
            val postedResult = Posted(requestId, connectionId, nonce, result)
            results += postedResult
            posted.complete(postedResult)
        }

        fun emit(event: String) {
            check(events.trySend(event).isSuccess)
        }
    }

    private fun ready(epoch: String, connection: String) =
        """{"type":"openchamber:event-stream-ready","properties":{"protocolVersion":1,"serverEpoch":"$epoch","connectionId":"$connection"}}"""

    private fun request(epoch: String, connection: String, id: String) =
        """{"type":"openchamber:browser-control-request","properties":{"protocolVersion":1,"serverEpoch":"$epoch","connectionId":"$connection","requestId":"$id","action":"browser.snapshot","parameters":{},"context":{"directory":"/proj","sessionId":"s1"},"deadlineAtEpochMs":10,"timeoutMs":1000}}"""

}
