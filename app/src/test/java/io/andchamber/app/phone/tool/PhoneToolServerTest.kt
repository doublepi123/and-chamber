package io.andchamber.app.phone.tool

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneBlockReason
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneStandingBlockReason
import io.andchamber.app.phone.core.PhoneTimeLimits
import io.andchamber.app.phone.overlay.progress.PhoneMutationDelivery
import io.andchamber.app.phone.overlay.progress.PhoneNativeCallOutcome
import io.andchamber.app.phone.overlay.progress.PhoneProgressApply
import io.andchamber.app.phone.overlay.progress.PhoneProgressStore
import io.andchamber.app.phone.overlay.progress.PhoneStopAcknowledgement
import io.andchamber.app.phone.overlay.progress.PhoneTaskEvent
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventError
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventHandler
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventReply
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.standing.PhoneTaskStartResult
import io.andchamber.app.phone.standing.PhoneTaskStartState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhoneToolServerTest {
    private fun RawResponse.ok(): Boolean = json["ok"]!!.toString().toBoolean()
    private fun RawResponse.code(): String = (json["error"] as JsonObject)["code"]!!.toString().trim('"')
    private fun RawResponse.action(): String = json["action"]!!.toString().trim('"')
    private fun RawResponse.epoch(): Long = json["epoch"]!!.toString().toLong()
    private fun RawResponse.data(): JsonObject = json["data"] as JsonObject
    private fun RawResponse.observationId(): String = data()["observationId"]!!.toString().trim('"')

    private fun snapshotBody(
        session: String = "ses-a",
        callId: String = "snap-1",
    ): String = toolBody(session, callId, input("snapshot"))

    private fun tapBody(
        observationId: String,
        session: String = "ses-a",
        callId: String = "tap-1",
        ref: String = "n1",
    ): String = toolBody(session, callId, input("tap", observationId, mapOf("ref" to ref)))

    private fun requestBody(
        summary: String,
        packageName: String? = null,
        session: String = "ses-a",
        callId: String = "req-1",
    ): String {
        val extra = buildMap<String, Any> {
            put("summary", summary)
            packageName?.let { put("packageName", it) }
        }
        return toolBody(session, callId, input("request", extra = extra))
    }

    @Test
    fun `status needs no grant, reports the epoch and never claims the session route`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            val before = httpPost(fixture.port, toolBody("ses-b", "s1", input("status")))
            assertEquals(200, before.status)
            assertTrue(before.ok())
            assertEquals("phone.status", before.action())
            assertEquals(0L, before.epoch())
            val data = before.data()
            assertEquals("false", data["enabled"].toString())
            assertEquals("true", data["runtimeReady"].toString())
            assertEquals("false", data["pausing"].toString())
            assertEquals("false", data["pendingConfirmation"].toString())
            assertEquals("DISABLED", data["phase"].toString().trim('"'))

            // Session A's status must not take the route from B or claim it for A.
            val statusA = httpPost(fixture.port, toolBody("ses-a", "s2", input("status")))
            assertTrue(statusA.ok())

            // The fixed schema is enforced on the server too, not only by the plugin.
            val strict = httpPost(
                fixture.port,
                toolBody("ses-b", "s3", input("status", extra = mapOf("ref" to "n1"))),
            )
            assertEquals(400, strict.status)
            assertEquals("INVALID_REQUEST", strict.code())

            fixture.granted()
            val snapA = httpPost(fixture.port, snapshotBody(session = "ses-a", callId = "a-snap"))
            assertTrue(snapA.ok())
            assertEquals("phone.snapshot", snapA.action())
            assertTrue(snapA.epoch() > 0)

            // If status had claimed the route for B, this action would be SESSION_BUSY.
            val tapA = httpPost(fixture.port, tapBody(snapA.observationId(), session = "ses-a", callId = "a-tap"))
            assertTrue(tapA.ok())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `status reports a permanent lease as no countdown instead of a huge remaining`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            val noGrant = httpPost(fixture.port, toolBody("ses-b", "s1", input("status")))
            assertTrue(noGrant.ok())
            assertEquals("null", noGrant.data()["leaseRemainingMs"].toString())
            assertEquals("false", noGrant.data()["leasePermanent"].toString())

            fixture.granted(ttlMs = 60_000L)
            val finite = httpPost(fixture.port, toolBody("ses-b", "s2", input("status")))
            assertTrue(finite.ok())
            assertEquals("60000", finite.data()["leaseRemainingMs"].toString())
            assertEquals("false", finite.data()["leasePermanent"].toString())

            fixture.granted(ttlMs = PhoneTimeLimits.PERMANENT)
            val permanent = httpPost(fixture.port, toolBody("ses-b", "s3", input("status")))
            assertTrue(permanent.ok())
            // Never serialise Long.MAX_VALUE - now as a remaining duration, nor any value above the
            // JavaScript safe-integer range.
            assertEquals("null", permanent.data()["leaseRemainingMs"].toString())
            assertEquals("true", permanent.data()["leasePermanent"].toString())
            assertFalse(permanent.body.contains(Long.MAX_VALUE.toString()))
            assertFalse(permanent.body.contains("9223372036854775807"))

            fixture.controller.revoke()
            val revoked = httpPost(fixture.port, toolBody("ses-b", "s4", input("status")))
            assertTrue(revoked.ok())
            assertEquals("null", revoked.data()["leaseRemainingMs"].toString())
            assertEquals("false", revoked.data()["leasePermanent"].toString())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a real action requires enable, connection, grant and a ready launch`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            val disabled = httpPost(fixture.port, snapshotBody())
            assertFalse(disabled.ok())
            assertEquals("DISABLED", disabled.code())

            fixture.controller.enable()
            val disconnected = httpPost(fixture.port, snapshotBody())
            assertEquals("NOT_CONNECTED", disconnected.code())

            fixture.granted()
            fixture.ready = null
            val notReady = httpPost(fixture.port, snapshotBody())
            assertEquals("RUNTIME_NOT_READY", notReady.code())

            fixture.ready = fixture.launchId
            val ready = httpPost(fixture.port, snapshotBody())
            assertTrue(ready.ok())
            val data = ready.data()
            assertEquals(TARGET, data["packageName"]!!.toString().trim('"'))
            assertEquals(1080, data["displayWidth"]!!.toString().toInt())
            assertEquals(2400, data["displayHeight"]!!.toString().toInt())
            assertTrue((data["nodes"] as kotlinx.serialization.json.JsonArray).isNotEmpty())

            fixture.controller.revoke()
            val revoked = httpPost(fixture.port, snapshotBody(callId = "snap-2"))
            assertEquals("NOT_GRANTED", revoked.code())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `one session owns a grant epoch and a new epoch reopens the route`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.granted()
            val first = httpPost(fixture.port, snapshotBody(session = "ses-a", callId = "a-1"))
            val observation = first.observationId()
            val firstEpoch = first.epoch()
            assertTrue(httpPost(fixture.port, tapBody(observation, "ses-a", "a-2")).ok())

            val other = httpPost(fixture.port, snapshotBody(session = "ses-b", callId = "b-1"))
            assertFalse(other.ok())
            assertEquals("SESSION_BUSY", other.code())

            val statusB = httpPost(fixture.port, toolBody("ses-b", "b-2", input("status")))
            assertTrue(statusB.ok())
            assertEquals(firstEpoch, statusB.epoch())

            fixture.controller.grant(TARGET, PhoneActionKind.values().toSet(), 60_000L)
            val newEpoch = fixture.controller.state.value.epoch
            assertTrue(newEpoch > firstEpoch)
            // The native side publishes a manual route for the new epoch; otherwise every action is
            // refused while the grant/publish window is open.
            assertTrue(fixture.publishRoute())
            val bAgain = httpPost(fixture.port, snapshotBody(session = "ses-b", callId = "b-3"))
            assertTrue(bAgain.ok())
            assertEquals(newEpoch, bAgain.epoch())

            val aAgain = httpPost(fixture.port, snapshotBody(session = "ses-a", callId = "a-3"))
            assertEquals("SESSION_BUSY", aAgain.code())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `tap, input and capture are bound to the live observation`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.granted()
            val snap = httpPost(fixture.port, snapshotBody(callId = "s1"))
            val observation = snap.observationId()

            val byRef = httpPost(fixture.port, tapBody(observation, callId = "t1"))
            assertTrue(byRef.ok())
            assertEquals("true", byRef.data()["sent"].toString())
            assertEquals("tap", byRef.data()["action"]!!.toString().trim('"'))
            assertTrue(fixture.driver.dispatchedActions().last() is io.andchamber.app.phone.core.PhoneDriverAction.Tap)

            val snap2 = httpPost(fixture.port, snapshotBody(callId = "s2"))
            val byPoint = httpPost(
                fixture.port,
                toolBody("ses-a", "t2", input("tap", snap2.observationId(), mapOf("x" to 150, "y" to 230))),
            )
            assertTrue(byPoint.ok())

            val snap3 = httpPost(fixture.port, snapshotBody(callId = "s3"))
            val outOfBounds = httpPost(
                fixture.port,
                toolBody("ses-a", "t3", input("tap", snap3.observationId(), mapOf("x" to 5_000, "y" to 5_000))),
            )
            assertEquals("OUT_OF_BOUNDS", outOfBounds.code())

            val snap4 = httpPost(fixture.port, snapshotBody(callId = "s4"))
            val missingObservation = httpPost(
                fixture.port,
                toolBody("ses-a", "t4", input("tap", null, mapOf("ref" to "n1"))),
            )
            assertEquals("OBSERVATION_REQUIRED", missingObservation.code())

            val stale = httpPost(fixture.port, tapBody("obs-does-not-exist", callId = "t5"))
            assertEquals("STALE_OBSERVATION", stale.code())

            // A stale observation must not have reached the driver.
            assertEquals(2, fixture.driver.dispatchedActions().size)

            val snap5 = httpPost(fixture.port, snapshotBody(callId = "s5"))
            val typing = httpPost(
                fixture.port,
                toolBody("ses-a", "i1", input("input", snap5.observationId(), mapOf("text" to "hello", "ref" to "n2"))),
            )
            assertTrue(typing.ok())
            assertEquals("input", typing.data()["action"]!!.toString().trim('"'))

            val snap6 = httpPost(fixture.port, snapshotBody(callId = "s6"))
            val unicode = httpPost(
                fixture.port,
                toolBody("ses-a", "i2", input("input", snap6.observationId(), mapOf("text" to "你好"))),
            )
            assertEquals("UNICODE_UNSUPPORTED", unicode.code())

            val snap7 = httpPost(fixture.port, snapshotBody(callId = "s7"))
            val capture = httpPost(
                fixture.port,
                toolBody("ses-a", "c1", input("capture", snap7.observationId())),
            )
            assertTrue(capture.ok())
            val captureData = capture.data()
            assertEquals("image/png", captureData["mime"]!!.toString().trim('"'))
            assertEquals(
                java.util.Base64.getEncoder().encodeToString(FakePhoneDriver.PNG),
                captureData["base64"]!!.toString().trim('"'),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `request starts a task in the standing scope and status never leaks it or claims PENDING`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.standingReady()
            val summary = "Book a table for two at 19:00"
            val started = httpPost(fixture.port, requestBody(summary, TARGET))
            assertEquals(200, started.status)
            assertTrue(started.ok())
            assertEquals("phone.request", started.action())
            val state = started.data()["state"]!!.toString().trim('"')
            assertTrue("state was $state", state == "RUNNING" || state == "READY")
            assertFalse("PENDING must not be a primary-mode reply", state == "PENDING_CONFIRMATION")
            val epoch = started.json["epoch"]!!.toString().toLongOrNull()
            assertNotNull(epoch)
            assertEquals(fixture.controller.state.value.grant!!.epoch, epoch)
            assertEquals(1, fixture.taskStarter.requests.size)
            assertEquals("ses-a", fixture.taskStarter.requests.single().first)
            assertEquals(summary, fixture.taskStarter.requests.single().second)
            assertEquals(TARGET, fixture.taskStarter.requests.single().third)
            assertEquals(TARGET, fixture.controller.state.value.grant!!.targetPackage)

            val status = httpPost(fixture.port, toolBody("ses-b", "s1", input("status")))
            assertTrue(status.ok())
            assertEquals("false", status.data()["pendingConfirmation"].toString())
            assertEquals("true", status.data()["standingConfigured"].toString())
            assertFalse("status leaked the summary", status.body.contains(summary))
            assertFalse("status leaked the target package", status.body.contains(TARGET))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `apps exposes only the preauthorized app list and needs no grant`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.standingReady()
            val response = httpPost(fixture.port, toolBody("ses-a", "a1", input("apps")))
            assertTrue(response.ok())
            assertEquals("phone.apps", response.action())
            val apps = response.data()["apps"] as JsonArray
            assertEquals(1, apps.size)
            assertTrue(response.body.contains(TARGET))
            assertTrue(response.body.contains("Fixture"))
            assertFalse("the installed catalogue must not leak", response.body.contains("com.android.settings"))
            assertEquals(
                setOf("TAP", "SWIPE", "INPUT", "BACK"),
                (response.data()["actions"] as JsonArray).map { it.toString().trim('"') }.toSet(),
            )

            val status = httpPost(fixture.port, toolBody("ses-b", "s1", input("status")))
            assertTrue(status.ok())
            assertEquals("true", status.data()["standingConfigured"].toString())
            assertEquals("false", status.data()["nativeTaskEvents"].toString())
            assertEquals("true", status.data()["runtimeReady"].toString())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `open_app delegates only a preauthorized package and never launches another`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.standingReady()
            val opened = httpPost(
                fixture.port,
                toolBody("ses-a", "o1", input("open_app", extra = mapOf("packageName" to TARGET))),
            )
            assertTrue(opened.ok())
            assertEquals("OPENED", opened.data()["state"]!!.toString().trim('"'))
            assertEquals(listOf(TARGET), fixture.taskStarter.opened)

            val denied = httpPost(
                fixture.port,
                toolBody("ses-a", "o2", input("open_app", extra = mapOf("packageName" to "com.android.settings"))),
            )
            assertEquals("TARGET_FORBIDDEN", denied.code())
            assertEquals(listOf(TARGET), fixture.taskStarter.opened)

            val missing = httpPost(fixture.port, toolBody("ses-a", "o3", input("open_app")))
            assertEquals(400, missing.status)
            assertEquals("INVALID_REQUEST", missing.code())

            fixture.taskStarter.availableFlag = false
            val unavailable = httpPost(
                fixture.port,
                toolBody("ses-a", "o4", input("open_app", extra = mapOf("packageName" to TARGET))),
            )
            assertEquals("STANDING_UNAVAILABLE", unavailable.code())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a direct start publishes the route for that chat and refuses another session`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.standingReady()
            assertTrue(
                httpPost(fixture.port, requestBody("first task", TARGET, session = "ses-a", callId = "r1")).ok(),
            )
            assertTrue(httpPost(fixture.port, snapshotBody(session = "ses-a", callId = "s1")).ok())
            assertEquals(
                "SESSION_BUSY",
                httpPost(fixture.port, snapshotBody(session = "ses-b", callId = "b1")).code(),
            )
            // A status call from another session is still harmless and does not take the route.
            assertTrue(httpPost(fixture.port, toolBody("ses-b", "s2", input("status"))).ok())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a direct request after a previous task creates a fresh epoch`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.standingReady()
            val first = httpPost(fixture.port, requestBody("first task", TARGET, callId = "r1"))
            assertTrue(first.ok())
            val firstEpoch = first.json["epoch"]!!.toString().toLong()
            val second = httpPost(fixture.port, requestBody("second task", TARGET, callId = "r2"))
            assertTrue(second.ok())
            val secondEpoch = second.json["epoch"]!!.toString().toLong()

            assertTrue("every task must have a new epoch", secondEpoch > firstEpoch)
            assertEquals(secondEpoch, fixture.controller.state.value.grant!!.epoch)
            assertEquals(2, fixture.taskStarter.requests.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `request enforces the runtime, standing apps, blockers and fixed schema`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.ready = null
            assertEquals("RUNTIME_NOT_READY", httpPost(fixture.port, requestBody("book", TARGET)).code())

            fixture.ready = fixture.launchId
            fixture.standingReady()
            assertEquals(
                "TARGET_FORBIDDEN",
                httpPost(fixture.port, requestBody("book", "io.andchamber.app")).code(),
            )
            assertEquals(
                "TARGET_FORBIDDEN",
                httpPost(fixture.port, requestBody("book", "com.android.settings")).code(),
            )
            assertEquals(0, fixture.taskStarter.requests.size)

            val blank = httpPost(fixture.port, requestBody("   ", TARGET))
            assertEquals(400, blank.status)
            assertEquals("INVALID_REQUEST", blank.code())
            val missingPackage = httpPost(fixture.port, requestBody("book"))
            assertEquals(400, missingPackage.status)
            assertEquals("INVALID_REQUEST", missingPackage.code())
            val extra = httpPost(
                fixture.port,
                toolBody(
                    "ses-a",
                    "r9",
                    input("request", extra = mapOf("summary" to "book", "packageName" to TARGET, "ref" to "n1")),
                ),
            )
            assertEquals(400, extra.status)
            assertEquals("INVALID_REQUEST", extra.code())

            fixture.taskStarter.availableFlag = false
            assertEquals("STANDING_UNAVAILABLE", httpPost(fixture.port, requestBody("book", TARGET, callId = "r3")).code())
            fixture.taskStarter.availableFlag = true

            fixture.controller.blockStanding(PhoneStandingBlockReason.TAKEOVER)
            assertEquals("STANDING_BLOCKED", httpPost(fixture.port, requestBody("book", TARGET, callId = "r4")).code())
            val blocked = httpPost(fixture.port, toolBody("ses-b", "s2", input("status")))
            assertTrue(blocked.ok())
            assertEquals("true", blocked.data()["standingBlocked"].toString())
            assertEquals("TAKEOVER", blocked.data()["standingBlockReason"]!!.toString().trim('"'))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a stale Started result is rolled back for its exact epoch and never answered as success`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.standingReady()
            val staleKey = PhoneTaskKey("task-stale", "runtime-1")
            fixture.taskStarter.startOverride =
                PhoneTaskStartResult.Started(epoch = 0L, key = staleKey, state = PhoneTaskStartState.RUNNING)

            val stale = httpPost(fixture.port, requestBody("book", TARGET, callId = "r1"))
            assertFalse(stale.ok())
            assertEquals("EPOCH_CHANGED", stale.code())
            assertEquals("the exact stale epoch is rolled back once", listOf(0L to staleKey), fixture.taskStarter.aborted)
            assertNull("a failed start leaves no grant", fixture.controller.state.value.grant)

            // A real start afterwards still works and never aborts a different epoch.
            fixture.taskStarter.startOverride = null
            val started = httpPost(fixture.port, requestBody("book", TARGET, callId = "r2"))
            assertTrue(started.ok())
            assertEquals(1, fixture.taskStarter.aborted.size)
            assertEquals(started.epoch(), fixture.controller.state.value.grant!!.epoch)
            assertEquals("ses-a", fixture.server.router.currentOwner())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a start whose route cannot be published is rolled back exactly once`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.standingReady()
            // Another epoch already owns the route, so this fresh task can never publish one.
            assertTrue(fixture.server.router.publish(50L, "ses-other"))
            val key = PhoneTaskKey("task-1", "runtime-1")
            fixture.taskStarter.startOverride =
                PhoneTaskStartResult.Started(epoch = 1L, key = key, state = PhoneTaskStartState.RUNNING)

            val response = httpPost(fixture.port, requestBody("book", TARGET))
            assertFalse(response.ok())
            assertEquals("ROUTE_NOT_PUBLISHED", response.code())
            assertEquals(1L, response.epoch())
            assertEquals(listOf(1L to key), fixture.taskStarter.aborted)
            assertEquals("the unrelated route is untouched", "ses-other", fixture.server.router.currentOwner())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a failed rollback still answers failure and never claims a running task`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.standingReady()
            val key = PhoneTaskKey("task-1", "runtime-1")
            fixture.taskStarter.startOverride =
                PhoneTaskStartResult.Started(epoch = 0L, key = key, state = PhoneTaskStartState.RUNNING)
            fixture.taskStarter.abortResult = false
            fixture.taskStarter.abortFailure = IllegalStateException("no rollback")

            val response = httpPost(fixture.port, requestBody("book", TARGET))
            assertEquals(200, response.status)
            assertFalse(response.ok())
            assertEquals("EPOCH_CHANGED", response.code())
            assertEquals(listOf(0L to key), fixture.taskStarter.aborted)
            assertFalse(response.body.contains("RUNNING"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a failed start never rolls back the newer live task`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.standingReady()
            assertTrue(httpPost(fixture.port, requestBody("first", TARGET, callId = "r1")).ok())
            val live = fixture.controller.state.value.grant!!.epoch
            val staleKey = PhoneTaskKey("task-old", "runtime-1")
            fixture.taskStarter.startOverride =
                PhoneTaskStartResult.Started(epoch = live, key = staleKey, state = PhoneTaskStartState.RUNNING)

            val stale = httpPost(fixture.port, requestBody("second", TARGET, callId = "r2"))
            assertFalse(stale.ok())
            assertEquals("EPOCH_CHANGED", stale.code())
            assertEquals(listOf(live to staleKey), fixture.taskStarter.aborted)
            assertEquals("the live grant is untouched", live, fixture.controller.state.value.grant!!.epoch)
            assertEquals("ses-a", fixture.server.router.currentOwner())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a successful start publishes its route once and never aborts`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.standingReady()
            val started = httpPost(fixture.port, requestBody("book", TARGET))
            assertTrue(started.ok())
            assertEquals(emptyList<Pair<Long, PhoneTaskKey>>(), fixture.taskStarter.aborted)
            assertEquals("ses-a", fixture.server.router.currentOwner())
            assertEquals(started.epoch(), fixture.controller.state.value.grant!!.epoch)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `the plugin ready handshake marks only this launch and clears on stop`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            assertFalse(fixture.server.pluginReady)

            // A launch that is no longer the ready one is refused and marks nothing.
            fixture.ready = null
            val stale = fixture.postReady()
            assertEquals(409, stale.status)
            assertFalse(fixture.server.pluginReady)

            fixture.ready = fixture.launchId
            val ready = fixture.postReady()
            assertEquals(200, ready.status)
            assertTrue(ready.json["ready"]!!.toString().toBoolean())
            assertFalse("the ack carries no token", ready.body.contains(TOKEN))
            assertTrue(fixture.server.pluginReady)

            // Handshaking is idempotent and creates no grant or epoch.
            assertEquals(200, fixture.postReady().status)
            assertTrue(fixture.server.pluginReady)
            assertNull(fixture.controller.state.value.grant)
            assertEquals(0L, fixture.controller.state.value.epoch)

            fixture.server.stop()
            assertFalse("stop clears readiness", fixture.server.pluginReady)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `the ready handshake refuses a wrong token, body, launch or method`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            assertEquals(401, fixture.postReady(token = "0".repeat(64)).status)
            assertEquals(401, fixture.postReady(token = null).status)

            val wrongBodies = listOf(
                """{"version":1,"plugin":"andchamber-phone"}""",
                """{"version":1,"plugin":"andchamber-phone","tool":"other_tool"}""",
                """{"version":1,"plugin":"other","tool":"andchamber_phone"}""",
                """{"version":2,"plugin":"andchamber-phone","tool":"andchamber_phone"}""",
                """{"version":"1","plugin":"andchamber-phone","tool":"andchamber_phone"}""",
                """{"version":1,"plugin":"andchamber-phone","tool":"andchamber_phone","sessionID":"ses-a"}""",
                """{"version":1,"plugin":"andchamber-phone","tool":"andchamber_phone","token":"$TOKEN"}""",
            )
            for (body in wrongBodies) {
                assertEquals(400, fixture.postReady(body).status)
            }

            assertEquals(
                403,
                fixture.postReady(extraHeaders = listOf("Origin" to "http://127.0.0.1")).status,
            )
            assertEquals(
                403,
                httpPost(fixture.port, fixture.readyBody, path = PhoneToolProtocol.READY_PATH, host = "localhost:${fixture.port}").status,
            )
            assertEquals(
                404,
                httpPost(
                    fixture.port,
                    fixture.readyBody,
                    path = PhoneToolProtocol.READY_PATH,
                    method = "GET",
                ).status,
            )
            assertFalse(fixture.server.pluginReady)

            // Only the exact fixed handshake with this launch's token marks ready.
            assertEquals(200, fixture.postReady().status)
            assertTrue(fixture.server.pluginReady)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a grant without a published route refuses every real action`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.granted(publishRoute = false)
            val blocked = httpPost(fixture.port, snapshotBody(callId = "s1"))
            assertEquals("ROUTE_NOT_PUBLISHED", blocked.code())

            // Native publishes the manual route; only then may the first action claim.
            assertTrue(fixture.publishRoute())
            assertTrue(httpPost(fixture.port, snapshotBody(callId = "s2")).ok())
            assertEquals(
                "SESSION_BUSY",
                httpPost(fixture.port, snapshotBody(session = "ses-b", callId = "b1")).code(),
            )

            val epoch = fixture.controller.state.value.epoch
            assertFalse("a duplicate publish must not change the route", fixture.server.publishGrantRoute(epoch, "ses-a"))
            assertFalse("a stale publish must not change the route", fixture.server.publishGrantRoute(epoch - 1, "ses-b"))
            assertTrue(httpPost(fixture.port, snapshotBody(callId = "s3")).ok())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a pinned route refuses another session before any real action`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.granted(pinnedSession = "ses-a")
            assertEquals(
                "SESSION_BUSY",
                httpPost(fixture.port, snapshotBody(session = "ses-b", callId = "b1")).code(),
            )
            assertTrue(httpPost(fixture.port, snapshotBody(session = "ses-a", callId = "a1")).ok())
            assertEquals(
                "SESSION_BUSY",
                httpPost(fixture.port, snapshotBody(session = "ses-b", callId = "b2")).code(),
            )
            // status is always allowed and never claims.
            assertTrue(httpPost(fixture.port, toolBody("ses-b", "b3", input("status"))).ok())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a stale epoch never replaces a newer published route`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.granted(pinnedSession = "ses-a")
            val firstEpoch = fixture.controller.state.value.epoch

            fixture.controller.grant(TARGET, PhoneActionKind.values().toSet(), 60_000L)
            val secondEpoch = fixture.controller.state.value.epoch
            assertTrue(secondEpoch > firstEpoch)
            assertTrue(fixture.publishRoute("ses-b"))

            assertFalse(
                "an old epoch publish must not change the new route",
                fixture.server.publishGrantRoute(firstEpoch, "ses-a"),
            )
            assertEquals(
                "SESSION_BUSY",
                httpPost(fixture.port, snapshotBody(session = "ses-a", callId = "a1")).code(),
            )
            assertTrue(httpPost(fixture.port, snapshotBody(session = "ses-b", callId = "b1")).ok())
            assertFalse(
                "a duplicate publish must not change the route",
                fixture.server.publishGrantRoute(secondEpoch, "ses-b"),
            )
            assertTrue(httpPost(fixture.port, snapshotBody(session = "ses-b", callId = "b2")).ok())
        } finally {
            fixture.close()
        }
    }

    /**
     * Admit a snapshot, block after admission, replace the grant, then release. The admitted request
     * must be refused against its own frozen epoch; it must never observe the new grant's screen.
     */
    private fun admittedSnapshotRace(publishNewRoute: Boolean) = runBlocking {
        val fixture = PhoneServerFixture()
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        try {
            fixture.granted()
            val oldEpoch = fixture.controller.state.value.epoch
            val entered = CountDownLatch(1)
            fixture.server.afterAdmissionForTest = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val pending = executor.submit<RawResponse> {
                httpPost(fixture.port, snapshotBody(callId = "old-admitted"))
            }
            assertTrue("the request was never admitted", entered.await(5, TimeUnit.SECONDS))

            // Native regrant while the admitted snapshot is paused; the new epoch may be unpublished
            // or pinned to a different session.
            fixture.controller.grant(TARGET, PhoneActionKind.values().toSet(), 60_000L)
            val newEpoch = fixture.controller.state.value.epoch
            assertTrue(newEpoch > oldEpoch)
            if (publishNewRoute) {
                assertTrue(fixture.server.publishGrantRoute(newEpoch, "ses-b"))
            }

            val observesBefore = fixture.driver.observeCalls.get()
            release.countDown()
            val response = pending.get(5, TimeUnit.SECONDS)

            assertEquals("the old request must not adopt the new epoch", "EPOCH_CHANGED", response.code())
            assertEquals("the refusal reports the admitted epoch", oldEpoch, response.epoch())
            assertEquals(
                "the new grant's screen was never observed",
                observesBefore,
                fixture.driver.observeCalls.get(),
            )
            assertEquals("no capture under the new grant", 0, fixture.driver.captureCalls.get())
            assertEquals("no dispatch under the new grant", 0, fixture.driver.dispatched.size)
        } finally {
            fixture.server.afterAdmissionForTest = null
            release.countDown()
            executor.shutdownNow()
            fixture.close()
        }
    }

    @Test
    fun `an admitted snapshot cannot run against a regrant without a published route`() {
        admittedSnapshotRace(publishNewRoute = false)
    }

    @Test
    fun `an admitted snapshot cannot run against a regrant pinned to another session`() {
        admittedSnapshotRace(publishNewRoute = true)
    }

    @Test
    fun `task progress requires the explicit epoch, native route and a task`() = runBlocking {
        val key = PhoneTaskKey("task-1", "runtime-1")
        val events = RecordingTaskEvents(currentKey = key) { PhoneTaskEventReply.Recorded(42) }
        val fixture = PhoneServerFixture(taskEvents = events)
        try {
            fixture.granted()
            val epoch = fixture.controller.state.value.epoch
            val response = httpPost(
                fixture.port,
                toolBody(
                    "ses-a",
                    "ev-1",
                    input("progress", extra = mapOf("epoch" to epoch, "phase" to "checking the form")),
                ),
            )
            assertTrue(response.ok())
            assertEquals("PROGRESS", response.data()["state"]!!.toString().trim('"'))
            assertEquals("42", response.data()["seq"].toString())
            assertEquals("MODEL", response.data()["source"]!!.toString().trim('"'))
            assertEquals("false", response.data()["verified"].toString())
            assertEquals(
                listOf(
                    HandledTaskEvent(
                        key,
                        epoch,
                        "ses-a",
                        PhoneTaskEvent.Progress("ev-1", "checking the form"),
                    ),
                ),
                events.handled,
            )

            val missingEpoch = httpPost(
                fixture.port,
                toolBody("ses-a", "ev-0", input("progress", extra = mapOf("phase" to "x"))),
            )
            assertEquals(400, missingEpoch.status)
            assertEquals("INVALID_REQUEST", missingEpoch.code())

            val stale = httpPost(
                fixture.port,
                toolBody(
                    "ses-a",
                    "ev-2",
                    input("progress", extra = mapOf("epoch" to epoch - 1, "phase" to "stale")),
                ),
            )
            assertEquals("EPOCH_CHANGED", stale.code())
            assertEquals(1, events.handled.size)

            val other = httpPost(
                fixture.port,
                toolBody(
                    "ses-b",
                    "ev-3",
                    input("progress", extra = mapOf("epoch" to epoch, "phase" to "not mine")),
                ),
            )
            assertEquals("SESSION_BUSY", other.code())
            assertEquals(1, events.handled.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `task events fail closed when no native task or handler exists`() = runBlocking {
        val noTask = PhoneServerFixture(taskEvents = RecordingTaskEvents(currentKey = null))
        try {
            noTask.granted()
            val epoch = noTask.controller.state.value.epoch
            val response = httpPost(
                noTask.port,
                toolBody("ses-a", "ev-1", input("progress", extra = mapOf("epoch" to epoch, "phase" to "x"))),
            )
            assertEquals("NO_TASK", response.code())
        } finally {
            noTask.close()
        }

        val unavailable = PhoneServerFixture()
        try {
            val response = httpPost(
                unavailable.port,
                toolBody("ses-a", "ev-2", input("progress", extra = mapOf("epoch" to 1L, "phase" to "x"))),
            )
            assertEquals("TASK_EVENTS_UNAVAILABLE", response.code())
            val status = httpPost(unavailable.port, toolBody("ses-a", "s1", input("status")))
            assertTrue(status.ok())
            assertEquals("false", status.data()["taskPresent"].toString())
        } finally {
            unavailable.close()
        }
    }

    @Test
    fun `status reports only a task boolean and never its content`() = runBlocking {
        val key = PhoneTaskKey("task-secret-id", "runtime-secret-id")
        val events = RecordingTaskEvents(currentKey = key)
        val fixture = PhoneServerFixture(taskEvents = events)
        try {
            val status = httpPost(fixture.port, toolBody("ses-a", "s1", input("status")))
            assertTrue(status.ok())
            assertEquals("true", status.data()["taskPresent"].toString())
            assertFalse(status.body.contains("task-secret-id"))
            assertFalse(status.body.contains("runtime-secret-id"))
            assertFalse(status.body.contains("summary"))
            assertFalse(status.body.contains("packageName"))
            assertFalse(status.body.contains("events"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `status never reads the native task summary target or events`() = runBlocking {
        val store = PhoneProgressStore(clock = { 1_000L })
        val events =
            object : PhoneTaskEventHandler {
                override fun currentTask(epoch: Long): PhoneTaskKey? = store.currentTask(epoch)

                override suspend fun handle(
                    key: PhoneTaskKey,
                    epoch: Long,
                    sessionLabel: String,
                    event: PhoneTaskEvent,
                ): PhoneTaskEventReply = PhoneTaskEventReply.Refused(PhoneTaskEventError.UNAVAILABLE)
            }
        val fixture = PhoneServerFixture(taskEvents = events)
        try {
            fixture.granted()
            val epoch = fixture.controller.state.value.epoch
            assertEquals(
                PhoneProgressApply.ACCEPTED,
                store.startTask(
                    eventId = "start",
                    key = PhoneTaskKey("task-secret-id", "runtime-secret-id"),
                    epoch = epoch,
                    routeSession = "ses-a",
                    summary = "SENSITIVE-TASK-SUMMARY",
                    packageName = "com.example.secret",
                ),
            )
            val status = httpPost(fixture.port, toolBody("ses-a", "s1", input("status")))
            assertTrue(status.ok())
            assertEquals("true", status.data()["taskPresent"].toString())
            for (secret in listOf(
                "SENSITIVE-TASK-SUMMARY",
                "com.example.secret",
                "task-secret-id",
                "runtime-secret-id",
            )) {
                assertFalse("status leaked $secret", status.body.contains(secret))
            }
            assertFalse(status.body.contains("\"summary\""))
            assertFalse(status.body.contains("\"packageName\""))
            assertFalse(status.body.contains("\"events\""))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `need_user and finish map only after a native reply and stay unverified`() = runBlocking {
        val key = PhoneTaskKey("task-1", "runtime-1")
        val ack = PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true)
        val events = RecordingTaskEvents(currentKey = key)
        val fixture = PhoneServerFixture(taskEvents = events)
        try {
            fixture.granted()
            val epoch = fixture.controller.state.value.epoch

            events.reply = { PhoneTaskEventReply.HaltUnconfirmed() }
            val unconfirmed = httpPost(
                fixture.port,
                toolBody("ses-a", "n1", input("need_user", extra = mapOf("epoch" to epoch, "reason" to "help"))),
            )
            assertEquals("STOP_UNCONFIRMED", unconfirmed.code())

            events.reply = { PhoneTaskEventReply.WaitingForUser(8, ack) }
            val waiting = httpPost(
                fixture.port,
                toolBody("ses-a", "n2", input("need_user", extra = mapOf("epoch" to epoch, "reason" to "help"))),
            )
            assertTrue(waiting.ok())
            assertEquals("WAITING_USER", waiting.data()["state"]!!.toString().trim('"'))
            assertEquals("false", waiting.data()["verified"].toString())
            assertEquals("true", waiting.data()["sentActionsMayStillApply"].toString())

            events.reply = { PhoneTaskEventReply.EndedPendingCheck(9, ack) }
            val finish = httpPost(
                fixture.port,
                toolBody(
                    "ses-a",
                    "f1",
                    input("finish", extra = mapOf("epoch" to epoch, "summary" to "I reported the end")),
                ),
            )
            assertTrue(finish.ok())
            assertEquals("ENDED_PENDING_CHECK", finish.data()["state"]!!.toString().trim('"'))
            assertEquals("false", finish.data()["verified"].toString())
            assertFalse(finish.body.contains("\"success\""))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `an event admitted under an epoch cannot execute after a regrant`() = runBlocking {
        val key = PhoneTaskKey("task-1", "runtime-1")
        val events = RecordingTaskEvents(currentKey = key)
        val fixture = PhoneServerFixture(taskEvents = events)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            fixture.granted()
            val epoch = fixture.controller.state.value.epoch
            events.expectedEpoch = epoch
            fixture.server.afterAdmissionForTest = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val pending = executor.submit<RawResponse> {
                httpPost(
                    fixture.port,
                    toolBody("ses-a", "ev-late", input("progress", extra = mapOf("epoch" to epoch, "phase" to "old epoch"))),
                )
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            fixture.controller.grant(TARGET, PhoneActionKind.values().toSet(), 60_000L)
            val newEpoch = fixture.controller.state.value.epoch
            assertTrue(newEpoch > epoch)
            assertTrue(fixture.publishRoute("ses-b"))
            events.expectedEpoch = newEpoch

            release.countDown()
            val response = pending.get(5, TimeUnit.SECONDS)
            assertEquals("EPOCH_CHANGED", response.code())
            assertEquals("the refusal reports the admitted epoch", epoch, response.epoch())
            assertEquals(epoch, events.handled.single().epoch)
        } finally {
            fixture.server.afterAdmissionForTest = null
            release.countDown()
            executor.shutdownNow()
            fixture.close()
        }
    }

    @Test
    fun `a cancel for an event call prevents the native handler from running`() = runBlocking {
        val key = PhoneTaskKey("task-1", "runtime-1")
        val events = RecordingTaskEvents(currentKey = key)
        val fixture = PhoneServerFixture(taskEvents = events)
        try {
            fixture.granted()
            val epoch = fixture.controller.state.value.epoch
            val cancelled = httpPost(
                fixture.port,
                cancelBody("ses-a", "ev-cancel"),
                path = PhoneToolEndpoint.CANCEL_PATH,
            )
            assertEquals(200, cancelled.status)
            assertEquals("false", cancelled.json["cancelled"].toString())

            val response = httpPost(
                fixture.port,
                toolBody("ses-a", "ev-cancel", input("progress", extra = mapOf("epoch" to epoch, "phase" to "x"))),
            )
            assertEquals("CANCELLED", response.code())
            assertTrue(events.handled.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a repeated call id is refused after it was handled`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.granted()
            assertTrue(httpPost(fixture.port, snapshotBody(callId = "same")).ok())
            val duplicate = httpPost(fixture.port, snapshotBody(callId = "same"))
            assertEquals("DUPLICATE_CALL", duplicate.code())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `transport requires the launch token, the exact host, no origin and bounded bodies`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            val body = toolBody("ses-a", "s1", input("status"))
            assertEquals(401, httpPost(fixture.port, body, token = null).status)
            assertEquals(401, httpPost(fixture.port, body, token = "b".repeat(64)).status)
            assertEquals(403, httpPost(fixture.port, body, extraHeaders = listOf("Origin" to "http://127.0.0.1")).status)
            assertEquals(403, httpPost(fixture.port, body, host = "localhost:${fixture.port}").status)
            assertEquals(404, httpPost(fixture.port, body, method = "GET").status)
            assertEquals(404, httpPost(fixture.port, body, path = "/v1/other").status)
            assertEquals(413, httpPost(fixture.port, body, omitContentLength = true).status)
            assertEquals(413, httpPost(fixture.port, body, transferEncoding = "chunked").status)
            assertEquals(
                413,
                httpPost(fixture.port, body, contentLengthOverride = PhoneToolProtocol.MAX_BODY_BYTES + 1).status,
            )
            assertEquals(400, httpPost(fixture.port, "{not json").status)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a second action while one is in flight is refused with BUSY`() = runBlocking {
        val fixture = PhoneServerFixture()
        val executor = Executors.newSingleThreadExecutor()
        try {
            fixture.granted()
            val snap = httpPost(fixture.port, snapshotBody(callId = "s1"))
            val release = CountDownLatch(1)
            fixture.driver.releaseDispatch = release
            val pending = executor.submit<RawResponse> {
                httpPost(fixture.port, tapBody(snap.observationId(), callId = "t1"))
            }
            assertTrue(fixture.driver.dispatchStarted.await(5, TimeUnit.SECONDS))

            val busy = httpPost(fixture.port, tapBody(snap.observationId(), callId = "t2"))
            assertEquals("BUSY", busy.code())

            release.countDown()
            assertTrue(pending.get(5, TimeUnit.SECONDS).ok())
        } finally {
            executor.shutdownNow()
            fixture.close()
        }
    }

    @Test
    fun `another session cannot cancel this request`() = runBlocking {
        val fixture = PhoneServerFixture()
        val executor = Executors.newSingleThreadExecutor()
        try {
            fixture.granted()
            val snap = httpPost(fixture.port, snapshotBody(callId = "s1"))
            val release = CountDownLatch(1)
            fixture.driver.releaseDispatch = release
            val pending = executor.submit<RawResponse> {
                httpPost(fixture.port, tapBody(snap.observationId(), callId = "t1"))
            }
            assertTrue(fixture.driver.dispatchStarted.await(5, TimeUnit.SECONDS))

            val foreign = httpPost(fixture.port, cancelBody("ses-b", "t1"), path = PhoneToolEndpoint.CANCEL_PATH)
            assertEquals(200, foreign.status)
            assertEquals("false", foreign.json["cancelled"].toString())

            release.countDown()
            val result = pending.get(5, TimeUnit.SECONDS)
            assertTrue("the foreign cancel must not abort the action", result.ok())
        } finally {
            executor.shutdownNow()
            fixture.close()
        }
    }

    @Test
    fun `a matching cancel aborts its own request and never claims a rollback`() = runBlocking {
        val fixture = PhoneServerFixture()
        val executor = Executors.newSingleThreadExecutor()
        try {
            fixture.granted()
            val snap = httpPost(fixture.port, snapshotBody(callId = "s1"))
            val release = CountDownLatch(1)
            fixture.driver.releaseDispatch = release
            val pending = executor.submit<RawResponse> {
                runCatching { httpPost(fixture.port, tapBody(snap.observationId(), callId = "t1")) }.getOrNull()
            }
            assertTrue(fixture.driver.dispatchStarted.await(5, TimeUnit.SECONDS))

            val matched = httpPost(fixture.port, cancelBody("ses-a", "t1"), path = PhoneToolEndpoint.CANCEL_PATH)
            assertEquals("true", matched.json["cancelled"].toString())

            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (fixture.controller.state.value.blocked == null && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(PhoneBlockReason.UNKNOWN_OUTCOME, fixture.controller.state.value.blocked)
            // A mutation that may have been injected blocks new mutations instead of guessing.
            val blocked = httpPost(fixture.port, toolBody("ses-a", "s2", input("status")))
            assertTrue(blocked.ok())
            assertEquals("UNKNOWN_OUTCOME", blocked.data()["blocked"]!!.toString().trim('"'))
        } finally {
            executor.shutdownNow()
            fixture.close()
        }
    }

    @Test
    fun `a cancel tombstone refuses the request even if it arrives later`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.granted()
            val early = httpPost(fixture.port, cancelBody("ses-a", "later"), path = PhoneToolEndpoint.CANCEL_PATH)
            assertEquals(200, early.status)
            assertEquals("false", early.json["cancelled"].toString())

            val refused = httpPost(fixture.port, snapshotBody(callId = "later"))
            assertEquals("CANCELLED", refused.code())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a full tombstone table refuses a new cancel and keeps the recorded one`() = runBlocking {
        val fixture = PhoneServerFixture(requestCapacity = 1)
        try {
            fixture.granted()
            val first = httpPost(fixture.port, cancelBody("ses-a", "key-1"), path = PhoneToolEndpoint.CANCEL_PATH)
            assertEquals(200, first.status)
            assertEquals("false", first.json["cancelled"].toString())

            val full = httpPost(fixture.port, cancelBody("ses-a", "key-2"), path = PhoneToolEndpoint.CANCEL_PATH)
            assertEquals(429, full.status)

            val stillCancelled = httpPost(fixture.port, snapshotBody(callId = "key-1"))
            assertEquals("CANCELLED", stillCancelled.code())

            // The refused tombstone never displaced the recorded one, and the unchosen id is free.
            val allowed = httpPost(fixture.port, snapshotBody(callId = "key-2"))
            assertTrue(allowed.ok())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a full request table refuses new calls without evicting old records`() = runBlocking {
        val fixture = PhoneServerFixture(requestCapacity = 1)
        try {
            fixture.granted()
            assertTrue(httpPost(fixture.port, snapshotBody(callId = "one")).ok())
            val capacity = httpPost(fixture.port, snapshotBody(callId = "two"))
            assertEquals("CAPACITY", capacity.code())
            val duplicate = httpPost(fixture.port, snapshotBody(callId = "one"))
            assertEquals("DUPLICATE_CALL", duplicate.code())
            val status = httpPost(fixture.port, toolBody("ses-a", "three", input("status")))
            assertTrue(status.ok())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `stop closes the loopback listener`() = runBlocking {
        val fixture = PhoneServerFixture()
        val port = fixture.port
        fixture.close()
        try {
            httpPost(port, toolBody("ses-a", "s1", input("status")))
            fail("the stopped server still accepted a connection")
        } catch (_: ConnectException) {
            // Expected: the listener is gone.
        } catch (_: SocketException) {
            // Expected: the listener is gone.
        }
    }

    @Test
    fun `the grant epoch is reported and changes with a new grant`() = runBlocking {
        val fixture = PhoneServerFixture()
        try {
            fixture.granted()
            val first = fixture.controller.state.value.epoch
            val status = httpPost(fixture.port, toolBody("ses-a", "s1", input("status")))
            assertEquals(first, status.epoch())
            assertEquals("READY", status.data()["phase"]!!.toString().trim('"'))

            fixture.now += 60_001L
            val expired = httpPost(fixture.port, snapshotBody(callId = "s2"))
            assertEquals("LEASE_EXPIRED", expired.code())
            fixture.controller.refresh()
            assertNotEquals(first, fixture.controller.state.value.epoch)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a manual first claim binds only the verified router session`() = runBlocking {
        val key = PhoneTaskKey("task-1", "runtime-1")
        val events = RecordingTaskEvents(currentKey = key)
        val fixture = PhoneServerFixture(taskEvents = events)
        try {
            fixture.granted(pinnedSession = null)
            val epoch = fixture.controller.state.value.epoch
            val snap = httpPost(fixture.port, snapshotBody(callId = "s1"))
            assertTrue(snap.ok())
            assertEquals(listOf(RouteClaim(key, epoch, "ses-a")), events.routeClaims)

            val foreign = httpPost(
                fixture.port,
                snapshotBody(session = "ses-b", callId = "b1"),
            )
            assertEquals("SESSION_BUSY", foreign.code())
            assertEquals("the wrong session never emits a route claim", 1, events.routeClaims.size)

            val progress = httpPost(
                fixture.port,
                toolBody(
                    "ses-a",
                    "p1",
                    input("progress", extra = mapOf("epoch" to epoch, "phase" to "ses-b")),
                ),
            )
            assertTrue(progress.ok())
            assertEquals("a model phase string cannot change the owner", "ses-a", events.routeClaims.last().session)
            assertEquals("only the first manual claim emits the callback", 1, events.routeClaims.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a manual task may bind its first claim from a task event`() = runBlocking {
        val key = PhoneTaskKey("task-1", "runtime-1")
        val events = RecordingTaskEvents(currentKey = key)
        val fixture = PhoneServerFixture(taskEvents = events)
        try {
            fixture.granted(pinnedSession = null)
            val epoch = fixture.controller.state.value.epoch
            val progress = httpPost(
                fixture.port,
                toolBody(
                    "ses-a",
                    "p1",
                    input("progress", extra = mapOf("epoch" to epoch, "phase" to "starting")),
                ),
            )
            assertTrue(progress.ok())
            assertEquals(listOf(RouteClaim(key, epoch, "ses-a")), events.routeClaims)
            val foreign = httpPost(
                fixture.port,
                toolBody(
                    "ses-b",
                    "p2",
                    input("progress", extra = mapOf("epoch" to epoch, "phase" to "not mine")),
                ),
            )
            assertEquals("SESSION_BUSY", foreign.code())
            assertEquals(1, events.routeClaims.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a pinned route does not emit a manual route claim`() = runBlocking {
        val key = PhoneTaskKey("task-1", "runtime-1")
        val events = RecordingTaskEvents(currentKey = key)
        val fixture = PhoneServerFixture(taskEvents = events)
        try {
            fixture.granted(pinnedSession = "ses-a")
            assertTrue(httpPost(fixture.port, snapshotBody(callId = "s1")).ok())
            assertTrue("a pinned route was already bound at native start", events.routeClaims.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `real controller results reach the native callback without payload`() = runBlocking {
        val key = PhoneTaskKey("task-1", "runtime-1")
        val events = RecordingTaskEvents(currentKey = key)
        val fixture = PhoneServerFixture(taskEvents = events)
        try {
            fixture.granted()
            val epoch = fixture.controller.state.value.epoch

            val snap = httpPost(fixture.port, snapshotBody(callId = "s1"))
            assertTrue(snap.ok())
            assertEquals(PhoneNativeCallOutcome.Observed(captured = false), events.nativeCalls.last().outcome)
            assertEquals("s1", events.nativeCalls.last().requestId)
            assertEquals(key, events.nativeCalls.last().key)
            assertEquals(epoch, events.nativeCalls.last().epoch)

            val capture = httpPost(
                fixture.port,
                toolBody("ses-a", "c1", input("capture", snap.observationId())),
            )
            assertTrue(capture.ok())
            assertEquals(PhoneNativeCallOutcome.Observed(captured = true), events.nativeCalls.last().outcome)

            val snap2 = httpPost(fixture.port, snapshotBody(callId = "s2"))
            fixture.driver.dispatchReceipt = PhoneDispatchReceipt.Sent
            val tap = httpPost(fixture.port, tapBody(snap2.observationId(), callId = "t1"))
            assertTrue(tap.ok())
            assertEquals(
                PhoneNativeCallOutcome.Mutation(PhoneMutationDelivery.SENT),
                events.nativeCalls.last().outcome,
            )

            val snap3 = httpPost(fixture.port, snapshotBody(callId = "s3"))
            val unicode = httpPost(
                fixture.port,
                toolBody("ses-a", "u1", input("input", snap3.observationId(), mapOf("text" to "你好"))),
            )
            assertEquals("UNICODE_UNSUPPORTED", unicode.code())
            assertEquals(PhoneNativeCallOutcome.Refused, events.nativeCalls.last().outcome)

            val snap4 = httpPost(fixture.port, snapshotBody(callId = "s4"))
            fixture.driver.dispatchReceipt = PhoneDispatchReceipt.Unknown()
            val unknown = httpPost(fixture.port, tapBody(snap4.observationId(), callId = "t2"))
            assertEquals("OUTCOME_UNKNOWN", unknown.code())
            assertEquals(
                PhoneNativeCallOutcome.Mutation(PhoneMutationDelivery.UNKNOWN),
                events.nativeCalls.last().outcome,
            )
            assertEquals(
                "no payload is attached to the callback",
                listOf("s1", "c1", "s2", "t1", "s3", "u1", "s4", "t2"),
                events.nativeCalls.map { it.requestId },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `an old admitted controller result is reported with its old key and epoch`() = runBlocking {
        val key = PhoneTaskKey("task-1", "runtime-1")
        val events = RecordingTaskEvents(currentKey = key)
        val fixture = PhoneServerFixture(taskEvents = events)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            fixture.granted()
            val epoch = fixture.controller.state.value.epoch
            fixture.server.afterAdmissionForTest = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val pending = executor.submit<RawResponse> {
                httpPost(fixture.port, snapshotBody(callId = "old"))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            fixture.controller.grant(TARGET, PhoneActionKind.values().toSet(), 60_000L)
            assertTrue(fixture.publishRoute("ses-b"))
            release.countDown()
            val response = pending.get(5, TimeUnit.SECONDS)
            assertEquals("EPOCH_CHANGED", response.code())
            assertEquals(epoch, response.epoch())

            val call = events.nativeCalls.single()
            assertEquals(key, call.key)
            assertEquals(epoch, call.epoch)
            assertEquals(PhoneNativeCallOutcome.Refused, call.outcome)
        } finally {
            fixture.server.afterAdmissionForTest = null
            release.countDown()
            executor.shutdownNow()
            fixture.close()
        }
    }

    private data class HandledTaskEvent(
        val key: PhoneTaskKey,
        val epoch: Long,
        val session: String,
        val event: PhoneTaskEvent,
    )

    private data class NativeCall(
        val key: PhoneTaskKey,
        val epoch: Long,
        val requestId: String,
        val outcome: PhoneNativeCallOutcome,
    )

    private data class RouteClaim(
        val key: PhoneTaskKey,
        val epoch: Long,
        val session: String,
    )

    private class RecordingTaskEvents(
        var currentKey: PhoneTaskKey? = null,
        var expectedEpoch: Long? = null,
        var reply: (PhoneTaskEvent) -> PhoneTaskEventReply = { PhoneTaskEventReply.Recorded(1) },
    ) : PhoneTaskEventHandler {
        override val available: Boolean = true
        val handled = mutableListOf<HandledTaskEvent>()
        val nativeCalls = mutableListOf<NativeCall>()
        val routeClaims = mutableListOf<RouteClaim>()

        override fun currentTask(epoch: Long): PhoneTaskKey? {
            val expected = expectedEpoch
            return if (expected != null && expected != epoch) null else currentKey
        }

        override suspend fun handle(
            key: PhoneTaskKey,
            epoch: Long,
            sessionLabel: String,
            event: PhoneTaskEvent,
        ): PhoneTaskEventReply {
            handled += HandledTaskEvent(key, epoch, sessionLabel, event)
            val expected = expectedEpoch
            if (expected != null && expected != epoch) {
                return PhoneTaskEventReply.Refused(PhoneTaskEventError.EPOCH_CHANGED)
            }
            return reply(event)
        }

        override fun onNativeCallCompleted(
            key: PhoneTaskKey,
            admittedEpoch: Long,
            requestId: String,
            outcome: PhoneNativeCallOutcome,
        ) {
            nativeCalls += NativeCall(key, admittedEpoch, requestId, outcome)
        }

        override fun onRouteClaimed(
            key: PhoneTaskKey,
            admittedEpoch: Long,
            sessionLabel: String,
        ) {
            routeClaims += RouteClaim(key, admittedEpoch, sessionLabel)
        }
    }
}
