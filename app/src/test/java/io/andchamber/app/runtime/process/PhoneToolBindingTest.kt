package io.andchamber.app.runtime.process

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneConnectionResult
import io.andchamber.app.phone.core.PhoneConnectionState
import io.andchamber.app.phone.core.PhoneController
import io.andchamber.app.phone.core.PhoneGrantResult
import io.andchamber.app.phone.overlay.progress.PhoneTaskEvent
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventHandler
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventReply
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.standing.PhoneOpenAppResult
import io.andchamber.app.phone.standing.PhoneStandingApp
import io.andchamber.app.phone.standing.PhoneStandingRefusal
import io.andchamber.app.phone.standing.PhoneStandingView
import io.andchamber.app.phone.standing.PhoneTaskStartResult
import io.andchamber.app.phone.standing.PhoneTaskStarter
import io.andchamber.app.phone.tool.FakePhoneDriver
import io.andchamber.app.phone.tool.PhonePendingProposal
import io.andchamber.app.phone.tool.PhoneToolEndpoint
import io.andchamber.app.phone.tool.PhoneToolTransport
import io.andchamber.app.phone.tool.RawResponse
import io.andchamber.app.phone.tool.TARGET
import io.andchamber.app.phone.tool.httpPost
import io.andchamber.app.phone.tool.input
import io.andchamber.app.phone.tool.toolBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PhoneToolBindingTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Controller with a fake driver and a binding whose loopback transport is replaced. */
    private class PhoneToolTestScope(
        val home: File,
        val trustedRoot: File,
        transportFactoryOverride: ((Long, String) -> PhoneToolTransport)? = null,
        useRealTransport: Boolean = false,
        taskEvents: PhoneTaskEventHandler = PhoneTaskEventHandler.Unavailable,
        taskStarter: PhoneTaskStarter = PhoneTaskStarter.Unavailable,
        registrationProbe: PhonePluginRegistrationWaiter = PhonePluginRegistrationWaiter.Unavailable,
    ) {
        private val target = "com.example.target"
        var now = 1_000L
        private val observations = AtomicInteger(0)
        private val ports = AtomicInteger(43210)
        private val tokens = AtomicInteger(0)
        val driver = FakePhoneDriver()
        val controller =
            PhoneController(
                driver = driver,
                clock = { now },
                newObservationId = { "obs-" + observations.incrementAndGet() },
            )
        val transports = mutableListOf<FakeTransport>()
        var unavailable: String? = null
        private val resolvedFactory: ((Long, String) -> PhoneToolTransport)? =
            if (useRealTransport) {
                null
            } else {
                { launchId: Long, token: String ->
                    transportFactoryOverride?.invoke(launchId, token)
                        ?: FakeTransport(port = ports.getAndIncrement()).also { transports += it }
                }
            }
        val binding =
            PhoneToolBinding(
                controller = controller,
                readyLaunch = { currentLaunch },
                taskEvents = taskEvents,
                taskStarter = taskStarter,
                registrationProbe = registrationProbe,
                transportFactory = resolvedFactory,
                tokenSource = { "%064x".format(tokens.getAndIncrement()) },
                onUnavailable = { unavailable = it },
            )

        @Volatile var currentLaunch: Long? = 1L

        suspend fun granted() {
            // The native driver reports the runtime epoch it was built for; DI aligns this with the
            // launch id the runtime host passes to onRuntimeChanged.
            driver.runtimeId = "launch-$currentLaunch"
            controller.enable()
            check(controller.connect() is PhoneConnectionResult.Connected)
            check(controller.grant(target, PhoneActionKind.values().toSet(), 60_000L) is PhoneGrantResult.Granted)
        }
    }

    @Test
    fun `a blocked old stop never clears a newer launch's proposal or grant`() = runBlocking {
        val home = folder.newFolder("home-stop-race")
        val stopEntered = CountDownLatch(1)
        val stopRelease = CountDownLatch(1)
        val scope =
            PhoneToolTestScope(
                home,
                folder.root,
                transportFactoryOverride = { launchId, _ ->
                    if (launchId == 1L) {
                        FakeTransport(port = 43210, stopEntered = stopEntered, stopRelease = stopRelease)
                    } else {
                        FakeTransport(port = 43211)
                    }
                },
            )
        try {
            assertTrue(scope.binding.start(1L, home, folder.root))
            val endpointOne = PhoneToolEndpoint.hostFile(home).readText()

            // The old launch's stop is stuck in transport.stop(), outside the binding lock.
            val stopper = Thread { scope.binding.stop(1L) }.apply { isDaemon = true; start() }
            assertTrue("the old stop never reached the transport", stopEntered.await(5, TimeUnit.SECONDS))

            // A new launch completes, gets a native grant and a new proposal meanwhile.
            scope.currentLaunch = 2L
            assertTrue(scope.binding.start(2L, home, folder.root))
            scope.granted()
            val grant = scope.controller.state.value.grant
            assertNotNull(grant)
            assertEquals(
                PhonePendingProposal.CREATED,
                scope.binding.pendingRequests.propose("ses-a", "new launch task", TARGET),
            )
            val endpointTwo = PhoneToolEndpoint.hostFile(home).readText()
            assertNotEquals(endpointOne, endpointTwo)

            stopRelease.countDown()
            stopper.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse("the old stop never finished", stopper.isAlive)

            // The late old stop must not clear or revoke anything belonging to launch 2.
            assertEquals("new launch task", scope.binding.pendingRequest.value!!.summary)
            assertEquals("ses-a", scope.binding.pendingRequest.value!!.sessionLabel)
            assertEquals(grant, scope.controller.state.value.grant)
            assertEquals("launch-2", scope.controller.state.value.runtimeId)
            assertEquals(endpointTwo, PhoneToolEndpoint.hostFile(home).readText())
        } finally {
            stopRelease.countDown()
            scope.binding.stop(2L)
        }
    }

    @Test
    fun `a late old start never writes back the runtime identity or clears a newer proposal`() = runBlocking {
        val home = folder.newFolder("home-start-race")
        val factoryEntered = CountDownLatch(1)
        val factoryRelease = CountDownLatch(1)
        val scope =
            PhoneToolTestScope(
                home,
                folder.root,
                transportFactoryOverride = { launchId, _ ->
                    if (launchId == 1L) {
                        factoryEntered.countDown()
                        factoryRelease.await(5, TimeUnit.SECONDS)
                        FakeTransport(port = 43210)
                    } else {
                        FakeTransport(port = 43211)
                    }
                },
            )
        try {
            var oldStarted: Boolean? = null
            val older =
                Thread { oldStarted = scope.binding.start(1L, home, folder.root) }.apply {
                    isDaemon = true
                    start()
                }
            assertTrue("the old start never blocked", factoryEntered.await(5, TimeUnit.SECONDS))

            // The newer launch installs and gets both a grant and a proposal while the old start is
            // still inside its transport factory.
            scope.currentLaunch = 2L
            assertTrue(scope.binding.start(2L, home, folder.root))
            scope.granted()
            val grant = scope.controller.state.value.grant
            assertNotNull(grant)
            assertEquals(
                PhonePendingProposal.CREATED,
                scope.binding.pendingRequests.propose("ses-a", "new launch task", TARGET),
            )
            val endpointTwo = PhoneToolEndpoint.hostFile(home).readText()

            factoryRelease.countDown()
            older.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse("the old start never finished", older.isAlive)

            assertEquals(false, oldStarted)
            assertEquals("launch-2", scope.controller.state.value.runtimeId)
            assertEquals(grant, scope.controller.state.value.grant)
            assertEquals("new launch task", scope.binding.pendingRequest.value!!.summary)
            assertEquals(endpointTwo, PhoneToolEndpoint.hostFile(home).readText())
        } finally {
            factoryRelease.countDown()
            scope.binding.stop(2L)
        }
    }

    @Test
    fun `publishGrantRoute relays only the current launch and current epoch`() = runBlocking {
        val home = folder.newFolder("home-route")
        val scope = PhoneToolTestScope(home, folder.root)
        try {
            assertTrue(scope.binding.start(1L, home, folder.root))
            scope.granted()
            val epoch = scope.controller.state.value.epoch

            assertTrue(scope.binding.publishGrantRoute(epoch, "ses-a"))
            assertEquals(listOf(epoch to "ses-a"), scope.transports[0].published)

            assertFalse("a wrong epoch is refused", scope.binding.publishGrantRoute(epoch + 1, "ses-a"))
            assertFalse("an invalid label is refused", scope.binding.publishGrantRoute(epoch, "bad\nlabel"))
            assertEquals("refused publishes never reach the transport", 1, scope.transports[0].published.size)

            scope.currentLaunch = 99L
            assertFalse("a non-ready launch is refused", scope.binding.publishGrantRoute(epoch, "ses-a"))
            assertEquals(1, scope.transports[0].published.size)
            scope.currentLaunch = 1L

            scope.transports[0].publishResult = false
            assertFalse("a transport refusal is fail-closed", scope.binding.publishGrantRoute(epoch, "ses-a"))

            scope.controller.revoke()
            assertFalse("a revoked grant is refused", scope.binding.publishGrantRoute(epoch, null))
            assertFalse("None is fail-closed", PhoneToolHost.None.publishGrantRoute(epoch, "ses-a"))
        } finally {
            scope.binding.stop(1L)
        }
    }

    @Test
    fun `a route published through the binding refuses another session end to end`() = runBlocking {
        val home = folder.newFolder("home-route-real")
        val scope = PhoneToolTestScope(home, folder.root, useRealTransport = true)
        try {
            assertTrue(scope.binding.start(1L, home, folder.root))
            scope.granted()
            val epoch = scope.controller.state.value.epoch
            val (port, token) = endpoint(home)

            // grant -> publish window: a racing action is refused and claims nothing.
            val early = httpPost(port, toolBody("ses-a", "a0", input("snapshot")), token = token)
            assertEquals("ROUTE_NOT_PUBLISHED", early.errorCode())

            assertTrue(scope.binding.publishGrantRoute(epoch, "ses-a"))
            val foreign = httpPost(port, toolBody("ses-b", "b1", input("snapshot")), token = token)
            assertEquals("SESSION_BUSY", foreign.errorCode())
            assertTrue(httpPost(port, toolBody("ses-a", "a1", input("snapshot")), token = token).okBody())
        } finally {
            scope.binding.stop(1L)
        }
    }

    private fun RawResponse.errorCode(): String =
        ((json["error"] as JsonObject)["code"]!!).toString().trim('"')

    private fun RawResponse.okBody(): Boolean = json["ok"]!!.toString().toBoolean()

    @Test
    fun `isReady follows the current launch and needs that launch's plugin handshake`() = runBlocking {
        val home = folder.newFolder("home-ready")
        val scope = PhoneToolTestScope(home, folder.root)
        try {
            assertFalse("no tool is installed yet", scope.binding.isReady())
            assertTrue(scope.binding.start(1L, home, folder.root))
            assertFalse("a listening server is not readiness", scope.binding.isReady())

            // Only this launch's setup handshake makes it ready.
            scope.transports[0].pluginReady = true
            assertTrue(scope.binding.isReady())

            // A newer ready launch does not make an old active launch ready.
            scope.currentLaunch = 2L
            assertFalse(scope.binding.isReady())
            assertTrue(scope.binding.start(2L, home, folder.root))
            assertFalse("a new launch never inherits the old handshake", scope.binding.isReady())
            scope.transports[1].pluginReady = true
            assertTrue(scope.binding.isReady())

            scope.granted()
            val sequence = scope.controller.state.value.sequence
            val grant = scope.controller.state.value.grant
            assertNotNull(grant)
            val endpoint = PhoneToolEndpoint.hostFile(home).readText()
            repeat(3) { assertTrue(scope.binding.isReady()) }
            assertEquals(
                "readiness must not publish, revoke or change the epoch",
                sequence,
                scope.controller.state.value.sequence,
            )
            assertEquals(grant, scope.controller.state.value.grant)
            assertEquals(
                "readiness must not rewrite the endpoint",
                endpoint,
                PhoneToolEndpoint.hostFile(home).readText(),
            )

            scope.transports[1].pluginReady = false
            assertFalse("a lost handshake is not ready", scope.binding.isReady())
            scope.transports[1].pluginReady = true

            scope.currentLaunch = null
            assertFalse("a missing ready launch means no tool", scope.binding.isReady())

            scope.binding.stop(2L)
            assertFalse(scope.binding.isReady())
            assertFalse("None is never ready", PhoneToolHost.None.isReady())
        } finally {
            scope.binding.stop(2L)
        }
    }

    @Test
    fun `the real transport is ready only after the exact ready handshake and not after stop`() = runBlocking {
        val home = folder.newFolder("home-ready-real")
        val scope = PhoneToolTestScope(home, folder.root, useRealTransport = true)
        try {
            assertTrue(scope.binding.start(1L, home, folder.root))
            val (port, token) = endpoint(home)
            assertFalse("the endpoint file alone is not readiness", scope.binding.isReady())

            val wrongBody =
                """{"version":1,"plugin":"andchamber-phone","tool":"andchamber_phone","sessionID":"ses-a"}"""
            val refused = httpPost(
                port,
                wrongBody,
                path = "/v1/ready",
                token = token,
            )
            assertEquals(400, refused.status)
            assertFalse(scope.binding.isReady())

            val ready = httpPost(
                port,
                """{"version":1,"plugin":"andchamber-phone","tool":"andchamber_phone"}""",
                path = "/v1/ready",
                token = token,
            )
            assertEquals(200, ready.status)
            assertTrue(scope.binding.isReady())

            // A stale launch's handshake is refused without changing anything.
            scope.currentLaunch = null
            val stale = httpPost(
                port,
                """{"version":1,"plugin":"andchamber-phone","tool":"andchamber_phone"}""",
                path = "/v1/ready",
                token = token,
            )
            assertEquals(409, stale.status)
            assertFalse(scope.binding.isReady())

            scope.currentLaunch = 1L
            assertTrue(scope.binding.isReady())
            scope.binding.start(2L, home, folder.root)
            assertFalse("a new launch starts without a handshake", scope.binding.isReady())
            scope.binding.stop(2L)
            assertFalse(scope.binding.isReady())
        } finally {
            scope.binding.stop(2L)
        }
    }

    @Test
    fun `binding forwards task events to the native handler`() = runBlocking {
        val home = folder.newFolder("home-task-events")
        val key = PhoneTaskKey("task-1", "runtime-1")
        val events = RecordingTaskEvents(key)
        val scope =
            PhoneToolTestScope(
                home,
                folder.root,
                useRealTransport = true,
                taskEvents = events,
            )
        try {
            assertTrue(scope.binding.start(1L, home, folder.root))
            scope.granted()
            val epoch = scope.controller.state.value.epoch
            assertTrue(scope.binding.publishGrantRoute(epoch, "ses-a"))
            val (port, token) = endpoint(home)
            val response = httpPost(
                port,
                toolBody(
                    "ses-a",
                    "ev-1",
                    input("progress", extra = mapOf("epoch" to epoch, "phase" to "checking")),
                ),
                token = token,
            )
            assertTrue(response.okBody())
            assertEquals(
                listOf<PhoneTaskEvent>(PhoneTaskEvent.Progress("ev-1", "checking")),
                events.handled,
            )
        } finally {
            scope.binding.stop(1L)
        }
    }

    @Test
    fun `the binding forwards the native standing starter to the loopback server`() = runBlocking {
        val home = folder.newFolder("home-standing")
        val starter = object : PhoneTaskStarter {
            override val available: Boolean = true

            override fun standing(): PhoneStandingView =
                PhoneStandingView(
                    apps = listOf(PhoneStandingApp("Fixture", "com.example.target")),
                    actions = setOf(PhoneActionKind.TAP),
                    windowEndsAtMs = 10_000_000L,
                )

            override suspend fun request(
                sessionId: String,
                summary: String,
                packageName: String,
            ): PhoneTaskStartResult = PhoneTaskStartResult.Refused(PhoneStandingRefusal.NOT_CONFIGURED)

            override suspend fun openApp(
                sessionId: String,
                packageName: String,
            ): PhoneOpenAppResult =
                PhoneOpenAppResult.Refused(PhoneStandingRefusal.NOT_CONFIGURED)

            override suspend fun abortStarted(
                epoch: Long,
                key: PhoneTaskKey,
            ): Boolean = false
        }
        val scope = PhoneToolTestScope(home, folder.root, useRealTransport = true, taskStarter = starter)
        try {
            assertTrue(scope.binding.start(1L, home, folder.root))
            val (port, token) = endpoint(home)
            val response = httpPost(port, toolBody("ses-a", "a1", input("apps")), token = token)
            assertTrue(response.okBody())
            assertTrue(response.body.contains("com.example.target"))
            assertTrue(response.body.contains("Fixture"))
            assertFalse(response.body.contains("io.andchamber.app"))
        } finally {
            scope.binding.stop(2L)
        }
    }

    private class RecordingTaskEvents(
        private val key: PhoneTaskKey,
    ) : PhoneTaskEventHandler {
        val handled = mutableListOf<PhoneTaskEvent>()

        override fun currentTask(epoch: Long): PhoneTaskKey? = key

        override suspend fun handle(
            key: PhoneTaskKey,
            epoch: Long,
            sessionLabel: String,
            event: PhoneTaskEvent,
        ): PhoneTaskEventReply {
            handled += event
            return PhoneTaskEventReply.Recorded(3)
        }
    }

    private fun endpoint(home: File): Pair<Int, String> {
        val root = Json.parseToJsonElement(PhoneToolEndpoint.hostFile(home).readText()).jsonObject
        val url = root["url"]!!.jsonPrimitive.content
        return java.net.URI(url).port to root["token"]!!.jsonPrimitive.content
    }



    private class DeferredRegistrationProbe : PhonePluginRegistrationWaiter {
        override val available: Boolean = true
        private val calls = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
        private val gates = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

        private fun call(launchId: Long): CompletableDeferred<Unit> =
            calls.getOrPut(launchId) { CompletableDeferred() }

        private fun gate(launchId: Long): CompletableDeferred<Boolean> =
            gates.getOrPut(launchId) { CompletableDeferred() }

        suspend fun awaitCalled(launchId: Long) {
            call(launchId).await()
        }

        fun complete(
            launchId: Long,
            registered: Boolean,
        ) {
            gate(launchId).complete(registered)
        }

        override suspend fun awaitRegistered(launchId: Long): Boolean {
            call(launchId).complete(Unit)
            return gate(launchId).await()
        }
    }

    @Test
    fun `isReady needs the exact launch rpc registration and never a legacy transport flag`() = runBlocking {
        val home = folder.newFolder("home-rpc-ready")
        val probe = DeferredRegistrationProbe()
        val scope = PhoneToolTestScope(home, folder.root, registrationProbe = probe)
        try {
            assertTrue(scope.binding.start(1L, home, folder.root))
            // A legacy setup handshake alone must not claim readiness when the RPC probe is wired.
            scope.transports.single().pluginReady = true
            probe.awaitCalled(1L)
            assertFalse(scope.binding.isReady())
            probe.complete(1L, registered = true)
            withTimeout(5_000) {
                while (!scope.binding.isReady()) delay(10)
            }
            assertTrue(scope.binding.isReady())
        } finally {
            scope.binding.stop(1L)
        }
    }

    @Test
    fun `stop cancels a pending rpc registration and ignores a late success`() = runBlocking {
        val home = folder.newFolder("home-rpc-stop")
        val probe = DeferredRegistrationProbe()
        val scope = PhoneToolTestScope(home, folder.root, registrationProbe = probe)
        try {
            assertTrue(scope.binding.start(1L, home, folder.root))
            probe.awaitCalled(1L)
            scope.binding.stop(1L)
            probe.complete(1L, registered = true)
            delay(100)
            assertFalse("a late registration for a stopped launch is not ready", scope.binding.isReady())
        } finally {
            scope.binding.stop(1L)
        }
    }

    @Test
    fun `a new launch never inherits an old rpc registration`() = runBlocking {
        val home = folder.newFolder("home-rpc-new-launch")
        val probe = DeferredRegistrationProbe()
        val scope = PhoneToolTestScope(home, folder.root, registrationProbe = probe)
        try {
            assertTrue(scope.binding.start(1L, home, folder.root))
            probe.awaitCalled(1L)
            assertTrue(scope.binding.start(2L, home, folder.root))
            scope.currentLaunch = 2L
            probe.awaitCalled(2L)
            probe.complete(1L, registered = true)
            delay(100)
            assertFalse("the new launch must not inherit launch 1's registration", scope.binding.isReady())
            probe.complete(2L, registered = true)
            withTimeout(5_000) {
                while (!scope.binding.isReady()) delay(10)
            }
            assertTrue(scope.binding.isReady())
        } finally {
            scope.binding.stop(2L)
        }
    }

    private class FakeTransport(
        private val port: Int,
        private val startEntered: CountDownLatch? = null,
        private val startRelease: CountDownLatch? = null,
        private val stopEntered: CountDownLatch? = null,
        private val stopRelease: CountDownLatch? = null,
    ) : PhoneToolTransport {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)

        /** Test-controlled stand-in for the plugin's `/v1/ready` handshake. */
        @Volatile
        override var pluginReady: Boolean = false

        override fun start(): Int {
            starts.incrementAndGet()
            startEntered?.countDown()
            startRelease?.await(5, TimeUnit.SECONDS)
            return port
        }

        override fun stop() {
            pluginReady = false
            stops.incrementAndGet()
            stopEntered?.countDown()
            stopRelease?.await(5, TimeUnit.SECONDS)
        }

        val published = mutableListOf<Pair<Long, String?>>()
        var publishResult = true

        override fun publishGrantRoute(
            epoch: Long,
            confirmedSessionLabel: String?,
        ): Boolean {
            published += epoch to confirmedSessionLabel
            return publishResult
        }
    }

    @Test
    fun `start writes a private endpoint and records the runtime epoch`() = runBlocking {
        val home = folder.newFolder("home-start")
        val scope = PhoneToolTestScope(home, folder.root)
        scope.granted()
        val firstEpoch = scope.controller.state.value.epoch

        assertTrue(scope.binding.start(1L, home, folder.root))
        val file = PhoneToolEndpoint.hostFile(home)
        assertTrue(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS),
        )
        assertEquals(PhoneToolEndpoint.content(43210, "%064x".format(0)), file.readText())
        assertEquals("launch-1", scope.controller.state.value.runtimeId)
        assertEquals("a first runtime assignment keeps the already valid grant", firstEpoch, scope.controller.state.value.epoch)
        assertNotNull(scope.controller.state.value.grant)

        // A new launch is a new runtime epoch: the old grant and connection are dropped.
        assertTrue(scope.binding.start(2L, home, folder.root))
        assertEquals(PhoneToolEndpoint.content(43211, "%064x".format(1)), file.readText())
        assertEquals("launch-2", scope.controller.state.value.runtimeId)
        assertNull(scope.controller.state.value.grant)
        assertEquals(PhoneConnectionState.DISCONNECTED, scope.controller.state.value.connection)
        assertEquals(1, scope.transports[0].stops.get())
    }

    @Test
    fun `stop removes this launch's endpoint, stops the server and revokes the grant`() = runBlocking {
        val home = folder.newFolder("home-stop")
        val scope = PhoneToolTestScope(home, folder.root)
        assertTrue(scope.binding.start(1L, home, folder.root))
        scope.granted()
        val grantedEpoch = scope.controller.state.value.epoch

        scope.binding.stop(1L)

        assertFalse(Files.exists(PhoneToolEndpoint.hostFile(home).toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals(1, scope.transports[0].stops.get())
        assertNull(scope.controller.state.value.grant)
        assertTrue(scope.controller.state.value.epoch > grantedEpoch)
        assertEquals(io.andchamber.app.phone.core.PhoneEvent.REVOKED, scope.controller.state.value.lastEvent)

        // Idempotent.
        scope.binding.stop(1L)
        assertEquals(1, scope.transports[0].stops.get())
    }

    @Test
    fun `a stop before start refuses the launch and writes nothing`() {
        val home = folder.newFolder("home-stop-first")
        val scope = PhoneToolTestScope(home, folder.root)

        scope.binding.stop(1L)

        assertFalse(scope.binding.start(1L, home, folder.root))
        assertTrue(scope.transports.isEmpty())
        assertFalse(Files.exists(PhoneToolEndpoint.hostFile(home).toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a delayed stop of the old launch never deletes or revokes the new one`() = runBlocking {
        val home = folder.newFolder("home-delayed")
        val scope = PhoneToolTestScope(home, folder.root)

        assertTrue(scope.binding.start(1L, home, folder.root))
        assertTrue(scope.binding.start(2L, home, folder.root))
        scope.granted()
        val token = "%064x".format(1)
        val file = PhoneToolEndpoint.hostFile(home)
        assertEquals(PhoneToolEndpoint.content(43211, token), file.readText())

        scope.binding.stop(1L)

        assertTrue(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals(PhoneToolEndpoint.content(43211, token), file.readText())
        assertNotNull("the old stop must not revoke the new launch's grant", scope.controller.state.value.grant)
        assertEquals(0, scope.transports[1].stops.get())
        assertFalse("a revoked launch cannot start again", scope.binding.start(1L, home, folder.root))
    }

    @Test
    fun `clearStaleEndpoint leaves a live launch's file alone and removes a leftover`() = runBlocking {
        val home = folder.newFolder("home-stale")
        val scope = PhoneToolTestScope(home, folder.root)
        assertTrue(scope.binding.start(1L, home, folder.root))
        val file = PhoneToolEndpoint.hostFile(home)

        assertTrue(scope.binding.clearStaleEndpoint(home, folder.root))
        assertTrue(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))

        scope.binding.stop(1L)
        file.writeText("""{"version":1,"url":"http://127.0.0.1:1/v1/tool","token":"stale"}""")
        assertTrue(scope.binding.clearStaleEndpoint(home, folder.root))
        assertFalse(Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a transport failure still leaves the runtime running`() {
        val home = folder.newFolder("home-fail")
        val scope =
            PhoneToolTestScope(
                home,
                folder.root,
                transportFactoryOverride = { _, _ -> throw IllegalStateException("no transport") },
            )

        assertFalse(scope.binding.start(1L, home, folder.root))
        assertNotNull(scope.unavailable)
        assertFalse("a failed launch is never ready", scope.binding.isReady())
        assertFalse(Files.exists(PhoneToolEndpoint.hostFile(home).toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a stop while the transport is starting wins and tears it down`() {
        val home = folder.newFolder("home-blocking")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var transport: FakeTransport
        val scope =
            PhoneToolTestScope(
                home,
                folder.root,
                transportFactoryOverride = { _, _ ->
                    FakeTransport(port = 43210, startEntered = entered, startRelease = release)
                        .also { transport = it }
                },
            )
        var started: Boolean? = null
        val worker =
            Thread { started = scope.binding.start(1L, home, folder.root) }.apply {
                isDaemon = true
                start()
            }
        assertTrue("start never blocked in the transport", entered.await(5, TimeUnit.SECONDS))

        scope.binding.stop(1L)
        release.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(5))

        assertEquals(false, started)
        assertEquals("the aborted start stops its own transport", 1, transport.stops.get())
        assertFalse(Files.exists(PhoneToolEndpoint.hostFile(home).toPath(), LinkOption.NOFOLLOW_LINKS))
    }
}
