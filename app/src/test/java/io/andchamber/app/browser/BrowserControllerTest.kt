package io.andchamber.app.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class BrowserControllerTest {
    private val sleeperGate = CompletableDeferred<Unit>()
    @get:Rule val folder = TemporaryFolder()
    @get:Rule val watcher = object : org.junit.rules.TestWatcher() {
        override fun starting(description: org.junit.runner.Description) {
            println("START ${description.methodName}")
        }

        override fun finished(description: org.junit.runner.Description) {
            println("END ${description.methodName}")
        }
    }

    @Test
    fun `enable requires a live runtime launch listener and credential write`() = runBlocking {
        val missingLaunch = harness(launchId = { null })
        missingLaunch.controller.attachListener(boundListener())
        missingLaunch.controller.attach(missingLaunch.driver)
        missingLaunch.controller.setVisible(true)
        assertFalse(missingLaunch.controller.enableFromUi())

        val harness = harness()
        harness.controller.attach(harness.driver)
        harness.controller.setVisible(true)
        assertFalse(harness.controller.enableFromUi())
    }

    @Test
    fun `a changed runtime launch is refused and revokes before dispatch`() = runBlocking {
        val launch = AtomicLong(7L)
        val harness = harness(launchId = { launch.get() })
        harness.enable()
        launch.set(8L)
        val reply = harness.submit(BrowserMethod.SNAPSHOT, "after-restart")
        assertEquals(BrowserErrorCode.REVOKED, reply.error?.code)
        assertEquals(BrowserPhase.DISABLED, harness.controller.state.value.phase)
        assertEquals(0, harness.driver.calls.get())
    }

    @Test
    fun `an old listener stop cannot close the listener started by a later enable`() = runBlocking {
        val firstStop = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        var starts = 0
        val harness = harness()
        harness.controller.attachListener(object : BrowserListener {
            override fun start(onBound: (endpoint: String) -> Unit) {
                starts += 1
                val endpoint = "http://127.0.0.1:${8 + starts}/v1/command"
                harness.controller.bindEndpoint(endpoint)
                onBound(endpoint)
            }

            override suspend fun stop() {
                firstStop.complete(Unit)
                releaseStop.await()
            }
        })
        harness.controller.attach(harness.driver)
        harness.controller.setVisible(true)
        assertTrue(harness.controller.enableFromUi())
        val closing = async { harness.controller.disable() }
        firstStop.await()
        val second = async { harness.controller.enableFromUi() }
        yieldOnce()
        releaseStop.complete(Unit)
        closing.await()
        assertTrue(second.await())
        assertEquals(BrowserPhase.ENABLED, harness.controller.state.value.phase)
        assertTrue(harness.controller.tokenMatches(harness.published.token))
    }

    @Test
    fun `takeOverFromUi completes an in-flight side effect immediately and invalidates the view`() = runBlocking {
        val harness = harness()
        harness.enable()
        val entered = CompletableDeferred<Unit>()
        harness.driver.handler = {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
            BrowserReply(ok = true)
        }
        val reply = async { harness.submit(BrowserMethod.CLICK, "click-1") }
        entered.await()
        harness.controller.takeOverFromUi()
        assertEquals(BrowserErrorCode.OUTCOME_UNKNOWN, reply.await().error?.code)
        assertTrue(harness.controller.state.value.viewInvalidated)
        assertTrue(harness.controller.state.value.viewInvalidated)
        assertEquals(BrowserPhase.TAKEN_OVER, harness.controller.state.value.phase)
    }

    @Test
    fun `a hanging side effect times out from admission and does not return ok`() = runBlocking {
        val harness = harness()
        harness.enable()
        harness.driver.handler = {
            sleeperGate.await()
            BrowserReply(ok = true)
        }
        val reply = harness.submit(BrowserMethod.CLICK, "slow", timeoutMs = 20)
        assertEquals(BrowserErrorCode.OUTCOME_UNKNOWN, reply.error?.code)
        assertTrue(harness.controller.state.value.viewInvalidated)
    }

    @Test
    fun `a hanging snapshot times out and the next request still runs`() = runBlocking {
        val harness = harness()
        harness.enable()
        val entered = CompletableDeferred<Unit>()
        harness.driver.handler = {
            if (it.id == "hung") {
                entered.complete(Unit)
                sleeperGate.await()
            }
            BrowserReply(ok = true)
        }
        val timed = async { harness.submit(BrowserMethod.SNAPSHOT, "hung", timeoutMs = 150) }
        entered.await()
        assertEquals(BrowserErrorCode.TIMEOUT, timed.await().error?.code)
        withTimeout(1_000) {
            while (!harness.driver.cancelled.get()) yieldOnce()
        }
        assertTrue(harness.driver.cancelled.get())
        harness.driver.handler = { BrowserReply(ok = true) }
        assertTrue(harness.submit(BrowserMethod.SNAPSHOT, "next").ok)
    }

    @Test
    fun `a blocking main driver cannot report success after its deadline`() = runBlocking {
        val harness = harness()
        harness.enable()
        harness.driver.handler = {
            Thread.sleep(100)
            BrowserReply(ok = true)
        }
        val reply = harness.submit(BrowserMethod.SNAPSHOT, "blocked", timeoutMs = 20)
        assertEquals(BrowserErrorCode.TIMEOUT, reply.error?.code)
        assertFalse(reply.ok)
    }

    @Test
    fun `disable of a dispatched click invalidates the view`() = runBlocking {
        val harness = harness()
        harness.enable()
        val entered = CompletableDeferred<Unit>()
        harness.driver.handler = {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
            BrowserReply(ok = true)
        }
        val reply = async { harness.submit(BrowserMethod.CLICK, "click-disable") }
        entered.await()
        harness.controller.disable()
        assertEquals(BrowserErrorCode.OUTCOME_UNKNOWN, reply.await().error?.code)
        assertTrue(harness.controller.state.value.viewInvalidated)
    }

    @Test
    fun `takeover after a completed click does not invalidate the view`() = runBlocking {
        val harness = harness()
        harness.enable()
        assertTrue(harness.submit(BrowserMethod.CLICK, "done").ok)
        harness.controller.takeOverFromUi()
        assertFalse(harness.controller.state.value.viewInvalidated)
        assertEquals(BrowserPhase.TAKEN_OVER, harness.controller.state.value.phase)
    }

    @Test
    fun `revokeRuntimeLaunch removes only the lease issued for that launch`() = runBlocking {
        val launch = AtomicLong(4L)
        val harness = harness(launchId = { launch.get() })
        harness.controller.attachListener(object : BrowserListener {
            override fun start(onBound: (endpoint: String) -> Unit) {
                val endpoint = "http://127.0.0.1:9/v1/command"
                harness.controller.bindEndpoint(endpoint)
                onBound(endpoint)
            }

            override suspend fun stop() = Unit
        })
        harness.controller.attach(harness.driver)
        harness.controller.setVisible(true)
        assertTrue(harness.controller.enableFromUi())
        val oldToken = harness.published.token
        harness.controller.revokeRuntimeLaunch(3L)
        assertEquals(BrowserPhase.ENABLED, harness.controller.state.value.phase)
        assertTrue(harness.controller.tokenMatches(oldToken))
        harness.controller.revokeRuntimeLaunch(4L)
        assertEquals(BrowserPhase.DISABLED, harness.controller.state.value.phase)
        assertFalse(harness.controller.tokenMatches(oldToken))
        launch.set(5L)
        assertTrue(harness.controller.enableFromUi())
        assertFalse(harness.published.token == oldToken)
        harness.controller.revokeRuntimeLaunch(4L)
        assertEquals(BrowserPhase.ENABLED, harness.controller.state.value.phase)
    }

    @Test
    fun `takeOverFromUi clears the lease before returning`() = runBlocking {
        val harness = harness()
        harness.enable()
        val before = harness.controller.state.value.generation
        harness.controller.takeOverFromUi()
        assertEquals(BrowserPhase.TAKEN_OVER, harness.controller.state.value.phase)
        assertTrue(harness.controller.state.value.generation > before)
        assertFalse(harness.controller.tokenMatches(harness.published.token))
    }

    @Test
    fun `disable during enable cannot be overwritten by a late publish`() = runBlocking {
        val events = mutableListOf<String>()
        val harness = harness(publisher = object : CredentialPublisher {
            override suspend fun publish(credential: BrowserCredential) {
                events += "publish"
                error("disk full")
            }

            override suspend fun delete() {
                events += "delete"
            }
        })
        harness.controller.attachListener(boundListener())
        harness.controller.attach(harness.driver)
        harness.controller.setVisible(true)
        assertFalse(harness.controller.enableFromUi())
        assertEquals(BrowserPhase.DISABLED, harness.controller.state.value.phase)
    }

    @Test
    fun `lease expiry disables state without a new command`() = runBlocking {
        val harness = harness()
        harness.enable()
        val token = harness.published.token
        harness.clock.add(BrowserLimits.LEASE_MS)
        harness.clock.releaseSleepers()
        while (harness.controller.state.value.phase == BrowserPhase.ENABLED) yieldOnce()
        assertEquals(BrowserPhase.DISABLED, harness.controller.state.value.phase)
        assertFalse(harness.controller.tokenMatches(token))
    }

    @Test
    fun `duplicate ids are retained until the lease cap`() = runBlocking {
        val harness = harness()
        harness.enable()
        repeat(BrowserLimits.MAX_SEEN_IDS) { index ->
            assertTrue(harness.submit(BrowserMethod.SNAPSHOT, "id-$index").ok)
        }
        assertEquals(BrowserErrorCode.REVOKED, harness.submit(BrowserMethod.SNAPSHOT, "overflow").error?.code)
        assertEquals(BrowserErrorCode.REVOKED, harness.submit(BrowserMethod.SNAPSHOT, "id-0").error?.code)
    }

    @Test
    fun `one in-flight call is busy`() = runBlocking {
        val harness = harness()
        harness.enable()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.driver.handler = {
            entered.complete(Unit)
            release.await()
            BrowserReply(ok = true)
        }
        val first = async { harness.submit(BrowserMethod.SNAPSHOT, "op-1") }
        entered.await()
        assertEquals(BrowserErrorCode.REVOKED, harness.submit(BrowserMethod.SNAPSHOT, "op-2").error?.code)
        release.complete(Unit)
        assertTrue(first.await().ok)
    }

    private fun harness(
        publisher: CredentialPublisher? = null,
        launchId: () -> Long? = { 1L },
    ): Harness {
        val clock = MutableClock(1_000L)
        val published = Published()
        val credentials = publisher ?: object : CredentialPublisher {
            override suspend fun publish(credential: BrowserCredential) {
                published.token = credential.token
            }

            override suspend fun delete() {
                published.token = ""
            }
        }
        val controller = BrowserControllerImpl(
            credentialFile = File(folder.root, "browser-control.json"),
            clock = BrowserClock.frozen { clock.now() },
            credentials = credentials,
            sessionIdFactory = { "session-1" },
            sleeper = { clock.park() },
            currentRuntimeLaunchId = launchId,
        )
        return Harness(controller, FakeDriver(), clock, published)
    }

    private class MutableClock(start: Long) {
        private val value = AtomicLong(start)
        val sleepers = java.util.Collections.synchronizedList(mutableListOf<CompletableDeferred<Unit>>())
        fun now(): Long = value.get()
        fun add(ms: Long) = value.addAndGet(ms)
        suspend fun park() {
            val gate = CompletableDeferred<Unit>()
            sleepers += gate
            gate.await()
        }

        fun releaseSleepers() {
            val pending = sleepers.toList()
            sleepers.clear()
            pending.forEach { it.complete(Unit) }
        }
    }

    private class Published(var token: String = "")

    private class FakeDriver : BrowserDriver {
        val calls = AtomicInteger()
        var handler: suspend (BrowserRequest) -> BrowserReply = { BrowserReply(ok = true) }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        override suspend fun execute(request: BrowserRequest): BrowserReply {
            calls.incrementAndGet()
            try {
                return handler(request)
            } catch (cancelledCall: kotlinx.coroutines.CancellationException) {
                cancelled.set(true)
                throw cancelledCall
            }
        }
    }

    private class Harness(
        val controller: BrowserControllerImpl,
        val driver: FakeDriver,
        val clock: MutableClock,
        val published: Published,
    ) {
        suspend fun enable() {
            controller.attachListener(object : BrowserListener {
                override fun start(onBound: (endpoint: String) -> Unit) {
                    val endpoint = "http://127.0.0.1:9/v1/command"
                    controller.bindEndpoint(endpoint)
                    onBound(endpoint)
                }

                override suspend fun stop() = Unit
            })
            controller.attach(driver)
            controller.setVisible(true)
            check(controller.enableFromUi())
        }

        suspend fun submit(method: BrowserMethod, id: String, timeoutMs: Long? = null): BrowserReply =
            controller.submit(BrowserRequest(id, method, timeoutMs = timeoutMs), "session-1")
    }

    private fun boundListener() = object : BrowserListener {
        override fun start(onBound: (endpoint: String) -> Unit) {
            onBound("http://127.0.0.1:9/v1/command")
        }

        override suspend fun stop() = Unit
    }

    private suspend fun yieldOnce() = coroutineScope { launch { }.join() }
}
