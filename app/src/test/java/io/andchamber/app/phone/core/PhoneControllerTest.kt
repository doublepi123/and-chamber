package io.andchamber.app.phone.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import io.andchamber.app.phone.standing.PhoneStandingGrantResult
import io.andchamber.app.phone.standing.PhoneStandingScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneControllerTest {
    @Test
    fun `default is disabled and the agent cannot grant itself`() = runTest {
        val fixture = Fixture()

        assertFalse(fixture.controller.state.value.enabled)
        assertEquals(PhonePhase.DISABLED, fixture.controller.state.value.phase)
        assertEquals(0L, fixture.controller.state.value.epoch)

        val call = fixture.controller.execute(fixture.request(PhoneAgentAction.Observe))
        assertTrue(call is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.DISABLED, (call as PhoneCallResult.Refused).error)

        val grant = fixture.controller.grant(TARGET, setOf(PhoneActionKind.TAP), 60_000L)
        assertTrue(grant is PhoneGrantResult.Refused)
        assertEquals(PhoneErrorCode.DISABLED, (grant as PhoneGrantResult.Refused).error)
        assertEquals(0, fixture.driver.observeCalls)
    }

    @Test
    fun `grant is exclusive and a new grant rejects the old epoch and old package`() = runTest {
        val fixture = Fixture()
        val first = fixture.active(target = TARGET)
        fixture.observe("r1")

        val second = fixture.controller.grant(OTHER, setOf(PhoneActionKind.TAP), 60_000L)
        assertTrue(second is PhoneGrantResult.Granted)
        val secondGrant = (second as PhoneGrantResult.Granted).grant

        assertNotEquals(first.epoch, secondGrant.epoch)
        assertEquals(OTHER, fixture.controller.state.value.grant?.targetPackage)

        val oldEpoch = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, epoch = first.epoch, requestId = "r2"),
        )
        assertEquals(PhoneErrorCode.EPOCH_CHANGED, (oldEpoch as PhoneCallResult.Refused).error)

        // The driver still reports the old target; the new grant must not observe it.
        val wrongTarget = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, epoch = secondGrant.epoch, requestId = "r3"),
        )
        assertEquals(PhoneErrorCode.TARGET_MISMATCH, (wrongTarget as PhoneCallResult.Refused).error)
        assertNull(fixture.controller.state.value.observation)
    }

    @Test
    fun `grant is independent from connection and the first connect keeps it`() = runTest {
        val fixture = Fixture()
        fixture.controller.enable()

        val granted = fixture.controller.grant(TARGET, setOf(PhoneActionKind.TAP), 60_000L)
        assertTrue(granted is PhoneGrantResult.Granted)
        val grant = (granted as PhoneGrantResult.Granted).grant
        assertNull(fixture.controller.state.value.connectionId)

        val disconnected = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "r1"),
        )
        assertEquals(PhoneErrorCode.NOT_CONNECTED, (disconnected as PhoneCallResult.Refused).error)

        assertTrue(fixture.controller.connect() is PhoneConnectionResult.Connected)
        assertEquals(grant.epoch, fixture.controller.state.value.grant?.epoch)

        val observation = fixture.observe("r2")
        val tap = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r3",
                observation = observation.binding,
            ),
        )
        assertTrue(tap is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.SENT, (tap as PhoneCallResult.Dispatched).delivery)
    }

    @Test
    fun `lock and host return revoke the lease without dropping the connection`() = runTest {
        val fixture = Fixture()
        fixture.active()

        fixture.controller.onDeviceLocked()
        assertNull(fixture.controller.state.value.grant)
        assertEquals(PhoneConnectionState.CONNECTED, fixture.controller.state.value.connection)
        assertEquals(PhoneEvent.LOCKED, fixture.controller.state.value.lastEvent)

        fixture.controller.grant(TARGET, setOf(PhoneActionKind.TAP), 60_000L)
        fixture.controller.onHostReturned()
        assertNull(fixture.controller.state.value.grant)
        assertEquals(PhoneEvent.HOST_RETURNED, fixture.controller.state.value.lastEvent)
    }

    @Test
    fun `lease expiry revokes and is published`() = runTest {        val fixture = Fixture()
        val grant = fixture.active(ttlMs = PhoneLimits.MIN_LEASE_MS)
        fixture.observe("r1")

        fixture.now += PhoneLimits.MIN_LEASE_MS + 1
        val call = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, epoch = grant.epoch, requestId = "r2"),
        )

        assertEquals(PhoneErrorCode.LEASE_EXPIRED, (call as PhoneCallResult.Refused).error)
        assertNull(fixture.controller.state.value.grant)
        assertEquals(PhoneEvent.LEASE_EXPIRED, fixture.controller.state.value.lastEvent)
        assertEquals(PhonePhase.NO_GRANT, fixture.controller.state.value.phase)
    }

    @Test
    fun `host system and non target packages are refused`() = runTest {
        val fixture = Fixture()
        fixture.controller.enable()

        assertEquals(
            PhoneErrorCode.TARGET_FORBIDDEN,
            (fixture.controller.grant("io.andchamber.app", setOf(PhoneActionKind.TAP), 60_000L)
                as PhoneGrantResult.Refused).error,
        )
        assertEquals(
            PhoneErrorCode.TARGET_FORBIDDEN,
            (fixture.controller.grant("android", setOf(PhoneActionKind.TAP), 60_000L)
                as PhoneGrantResult.Refused).error,
        )
        assertEquals(
            PhoneErrorCode.TARGET_FORBIDDEN,
            (fixture.controller.grant("com.android.settings", setOf(PhoneActionKind.TAP), 60_000L)
                as PhoneGrantResult.Refused).error,
        )

        fixture.active()
        fixture.driver.snapshot = snapshot(packageName = OTHER)
        val otherPackage = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "r1"),
        )
        assertEquals(PhoneErrorCode.TARGET_MISMATCH, (otherPackage as PhoneCallResult.Refused).error)
        assertNull(fixture.controller.state.value.observation)

        fixture.driver.snapshot = snapshot(windowId = "")
        val unknownWindow = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "r2"),
        )
        assertEquals(PhoneErrorCode.WINDOW_CHANGED, (unknownWindow as PhoneCallResult.Refused).error)
    }

    @Test
    fun `old observation and old capture binding are refused before dispatch`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val first = fixture.observe("r1")
        val second = fixture.observe("r2")

        val staleTap = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r3",
                observation = first.binding,
            ),
        )
        assertEquals(PhoneErrorCode.STALE_OBSERVATION, (staleTap as PhoneCallResult.Refused).error)
        assertTrue(fixture.driver.dispatched.isEmpty())

        val staleCapture = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Capture,
                requestId = "r4",
                observation = first.binding,
            ),
        )
        assertEquals(PhoneErrorCode.STALE_OBSERVATION, (staleCapture as PhoneCallResult.Refused).error)
        assertEquals(0, fixture.driver.captureCalls)

        val capture = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Capture,
                requestId = "r5",
                observation = second.binding,
            ),
        )
        assertTrue(capture is PhoneCallResult.Captured)
        assertEquals(second.binding, (capture as PhoneCallResult.Captured).capture.binding)
        assertEquals(1, fixture.driver.captureCalls)

        val tap = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r6",
                observation = second.binding,
            ),
        )
        assertTrue(tap is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.SENT, (tap as PhoneCallResult.Dispatched).delivery)
        assertEquals(1, fixture.driver.dispatched.size)
        assertEquals(PhonePoint(60, 70), (fixture.driver.dispatched.single().action as PhoneDriverAction.Tap).at)
    }

    @Test
    fun `reconnect does not restore the previous lease`() = runTest {
        val fixture = Fixture()
        val grant = fixture.active()
        fixture.observe("r1")

        val reconnect = fixture.controller.connect()
        assertTrue(reconnect is PhoneConnectionResult.Connected)
        assertEquals(2, fixture.driver.connectCalls)
        assertNull(fixture.controller.state.value.grant)
        assertNotEquals(grant.epoch, fixture.controller.state.value.epoch)

        val old = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, epoch = grant.epoch, requestId = "r2"),
        )
        assertEquals(PhoneErrorCode.EPOCH_CHANGED, (old as PhoneCallResult.Refused).error)
    }

    @Test
    fun `duplicate request id is refused and not replayed`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")

        val first = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "same",
                observation = observation.binding,
            ),
        )
        assertTrue(first is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.SENT, (first as PhoneCallResult.Dispatched).delivery)

        val duplicate = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "same",
                observation = observation.binding,
            ),
        )
        assertEquals(PhoneErrorCode.DUPLICATE, (duplicate as PhoneCallResult.Refused).error)
        assertEquals(1, fixture.driver.dispatched.size)
    }

    @Test
    fun `capacity refuses new ids without evicting old records`() = runTest {
        val fixture = Fixture(requestCapacity = 2)
        fixture.active()
        fixture.observe("r1")
        fixture.observe("r2")

        val full = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "r3"),
        )
        assertEquals(PhoneErrorCode.CAPACITY, (full as PhoneCallResult.Refused).error)

        val duplicate = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "r1"),
        )
        assertEquals(PhoneErrorCode.DUPLICATE, (duplicate as PhoneCallResult.Refused).error)
        assertEquals(2, fixture.driver.observeCalls)
    }

    @Test
    fun `a permanent lease does not bypass duplicate or capacity refusal`() = runTest {
        val fixture = Fixture(requestCapacity = 2)
        fixture.controller.enable()
        assertTrue(fixture.controller.connect() is PhoneConnectionResult.Connected)
        assertTrue(
            fixture.controller.grant(TARGET, setOf(PhoneActionKind.TAP), PhoneTimeLimits.PERMANENT)
                is PhoneGrantResult.Granted,
        )

        fixture.observe("r1")
        fixture.observe("r2")
        val full = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "r3"),
        )
        assertEquals(PhoneErrorCode.CAPACITY, (full as PhoneCallResult.Refused).error)

        val duplicate = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "r1"),
        )
        assertEquals(PhoneErrorCode.DUPLICATE, (duplicate as PhoneCallResult.Refused).error)
        assertEquals("a permanent lease must not clear or evict request records", 2, fixture.driver.observeCalls)

        // A new permanent grant is still a new epoch: old ids are rejected, not replayed.
        val oldEpoch = fixture.controller.state.value.epoch
        assertTrue(
            fixture.controller.grant(TARGET, setOf(PhoneActionKind.TAP), PhoneTimeLimits.PERMANENT)
                is PhoneGrantResult.Granted,
        )
        val stale = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, epoch = oldEpoch, requestId = "r4"),
        )
        assertEquals(PhoneErrorCode.EPOCH_CHANGED, (stale as PhoneCallResult.Refused).error)
        assertEquals(2, fixture.driver.observeCalls)
    }

    @Test
    fun `pause during dispatch reports unknown and blocks later mutations`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        val release = CompletableDeferred<Unit>()
        fixture.driver.dispatchHandler = {
            release.await()
            PhoneDispatchReceipt.Sent
        }

        val call = async {
            fixture.controller.execute(
                fixture.request(
                    PhoneAgentAction.TapRef("n1"),
                    requestId = "pause-1",
                    observation = observation.binding,
                ),
            )
        }
        fixture.driver.enteredDispatch.await()
        assertTrue(fixture.controller.state.value.inFlightMutation)
        fixture.controller.pause()
        assertTrue(fixture.controller.state.value.pausing)
        assertTrue(fixture.controller.state.value.inFlightMutation)

        val result = call.await()
        assertTrue(result is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.UNKNOWN, (result as PhoneCallResult.Dispatched).delivery)
        assertEquals(PhoneErrorCode.PAUSED, result.error)
        assertEquals(PhoneBlockReason.UNKNOWN_OUTCOME, fixture.controller.state.value.blocked)
        assertTrue(fixture.controller.state.value.paused)
        assertFalse(fixture.controller.state.value.inFlightMutation)
        assertFalse(fixture.controller.state.value.pausing)

        fixture.controller.resume()
        val fresh = fixture.observe("r2")
        val refused = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "pause-2",
                observation = fresh.binding,
            ),
        )
        assertEquals(PhoneErrorCode.OUTCOME_UNKNOWN, (refused as PhoneCallResult.Refused).error)
    }

    @Test
    fun `a second concurrent mutation is busy and never dispatched`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        val release = CompletableDeferred<Unit>()
        fixture.driver.dispatchHandler = {
            release.await()
            PhoneDispatchReceipt.Sent
        }

        val first = async {
            fixture.controller.execute(
                fixture.request(
                    PhoneAgentAction.TapRef("n1"),
                    requestId = "first",
                    observation = observation.binding,
                ),
            )
        }
        fixture.driver.enteredDispatch.await()
        assertTrue(fixture.controller.state.value.inFlightMutation)

        val second = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "second",
                observation = observation.binding,
            ),
        )
        assertEquals(PhoneErrorCode.BUSY, (second as PhoneCallResult.Refused).error)
        assertEquals(1, fixture.driver.dispatched.size)

        val observe = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "observe-busy"),
        )
        assertEquals(PhoneErrorCode.BUSY, (observe as PhoneCallResult.Refused).error)
        assertEquals(1, fixture.driver.observeCalls)

        release.complete(Unit)
        val result = first.await()
        assertTrue(result is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.SENT, (result as PhoneCallResult.Dispatched).delivery)
        assertEquals(1, fixture.driver.dispatched.size)
        assertFalse(fixture.controller.state.value.inFlightMutation)
    }

    @Test
    fun `pause during driver preflight is rejected by the admission guard`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        val preflight = CompletableDeferred<Unit>()
        var guardRefusal: PhoneErrorCode? = null
        var injected = 0
        fixture.driver.dispatchHandler = { request ->
            withContext(NonCancellable) {
                preflight.await()
                guardRefusal = request.guard.check()
                if (guardRefusal == null) injected += 1
            }
            PhoneDispatchReceipt.NotSent(PhoneDriverFailure.ADMISSION_REFUSED)
        }

        val call = async {
            fixture.controller.execute(
                fixture.request(
                    PhoneAgentAction.TapRef("n1"),
                    requestId = "guard-1",
                    observation = observation.binding,
                ),
            )
        }
        fixture.driver.enteredDispatch.await()
        assertTrue(fixture.controller.state.value.inFlightMutation)

        fixture.controller.pause()
        assertTrue(fixture.controller.state.value.paused)
        assertTrue(fixture.controller.state.value.pausing)
        assertTrue(fixture.controller.state.value.inFlightMutation)

        preflight.complete(Unit)
        advanceUntilIdle()

        val result = call.await()
        assertTrue(result is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.UNKNOWN, (result as PhoneCallResult.Dispatched).delivery)
        assertEquals(PhoneErrorCode.PAUSED, result.error)
        assertEquals(PhoneErrorCode.PAUSED, guardRefusal)
        assertEquals(0, injected)
        assertFalse(fixture.controller.state.value.inFlightMutation)
        assertFalse(fixture.controller.state.value.pausing)
    }

    @Test
    fun `sent action forces a new observation before the next mutation`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")

        val first = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r2",
                observation = observation.binding,
            ),
        )
        assertTrue(first is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.SENT, (first as PhoneCallResult.Dispatched).delivery)
        assertNull(fixture.controller.state.value.observation)

        val stale = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r3",
                observation = observation.binding,
            ),
        )
        assertEquals(PhoneErrorCode.OBSERVATION_REQUIRED, (stale as PhoneCallResult.Refused).error)
        assertEquals(1, fixture.driver.dispatched.size)

        val fresh = fixture.observe("r4")
        val again = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r5",
                observation = fresh.binding,
            ),
        )
        assertTrue(again is PhoneCallResult.Dispatched)
        assertEquals(2, fixture.driver.dispatched.size)
    }

    @Test
    fun `a late old epoch result does not overwrite a new grant`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        val preflight = CompletableDeferred<Unit>()
        var guardRefusal: PhoneErrorCode? = null
        fixture.driver.dispatchHandler = { request ->
            withContext(NonCancellable) {
                preflight.await()
                guardRefusal = request.guard.check()
            }
            PhoneDispatchReceipt.NotSent(PhoneDriverFailure.ADMISSION_REFUSED)
        }

        val old = async {
            fixture.controller.execute(
                fixture.request(
                    PhoneAgentAction.TapRef("n1"),
                    requestId = "old-1",
                    observation = observation.binding,
                ),
            )
        }
        fixture.driver.enteredDispatch.await()

        val grant = fixture.controller.grant(TARGET, setOf(PhoneActionKind.TAP), 60_000L)
        assertTrue(grant is PhoneGrantResult.Granted)
        val newEpoch = (grant as PhoneGrantResult.Granted).grant.epoch

        preflight.complete(Unit)
        advanceUntilIdle()
        old.await()

        assertEquals(PhoneErrorCode.EPOCH_CHANGED, guardRefusal)
        assertEquals(newEpoch, fixture.controller.state.value.grant?.epoch)
        assertNull(fixture.controller.state.value.observation)
        assertFalse(fixture.controller.state.value.inFlightMutation)
    }

    @Test
    fun `a late observation is not republished after pause`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val gate = CompletableDeferred<Unit>()
        fixture.driver.observeHandler = {
            withContext(NonCancellable) {
                gate.await()
                snapshot()
            }
        }

        val call = async {
            fixture.controller.execute(
                fixture.request(PhoneAgentAction.Observe, requestId = "late-1"),
            )
        }
        fixture.driver.enteredObserve.await()
        fixture.controller.pause()
        gate.complete(Unit)
        advanceUntilIdle()

        val result = call.await()
        assertTrue(result is PhoneCallResult.Refused)
        assertNull(fixture.controller.state.value.observation)
    }

    @Test
    fun `unknown outcome blocks new mutations until a person acknowledges`() = runTest {
        val fixture = Fixture()
        fixture.active()
        fixture.driver.dispatchReceipt = PhoneDispatchReceipt.Unknown()
        val observation = fixture.observe("r1")

        val first = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r2",
                observation = observation.binding,
            ),
        )
        assertTrue(first is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.UNKNOWN, (first as PhoneCallResult.Dispatched).delivery)
        assertEquals(PhoneErrorCode.OUTCOME_UNKNOWN, first.error)
        assertEquals(PhoneBlockReason.UNKNOWN_OUTCOME, fixture.controller.state.value.blocked)
        assertNull(fixture.controller.state.value.observation)

        val fresh = fixture.observe("r3")
        val blocked = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r4",
                observation = fresh.binding,
            ),
        )
        assertEquals(PhoneErrorCode.OUTCOME_UNKNOWN, (blocked as PhoneCallResult.Refused).error)
        assertEquals(1, fixture.driver.dispatched.size)

        fixture.controller.acknowledgeUnknownOutcome()
        fixture.driver.dispatchReceipt = PhoneDispatchReceipt.Sent
        val again = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r5",
                observation = fresh.binding,
            ),
        )
        assertTrue(again is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.SENT, (again as PhoneCallResult.Dispatched).delivery)
        assertEquals(2, fixture.driver.dispatched.size)
    }

    @Test
    fun `a new runtime revokes and rejects old epoch requests`() = runTest {
        val fixture = Fixture()
        val grant = fixture.active()
        fixture.observe("r1")

        fixture.controller.onRuntimeChanged("runtime-2")

        assertNull(fixture.controller.state.value.grant)
        assertEquals(PhoneConnectionState.DISCONNECTED, fixture.controller.state.value.connection)
        assertEquals("runtime-2", fixture.controller.state.value.runtimeId)

        val old = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, epoch = grant.epoch, requestId = "r2"),
        )
        assertEquals(PhoneErrorCode.EPOCH_CHANGED, (old as PhoneCallResult.Refused).error)
    }

    @Test
    fun `timeout of an in flight mutation is unknown and bounded`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        fixture.driver.dispatchHandler = { awaitCancellation() }

        val call = async {
            fixture.controller.execute(
                fixture.request(
                    PhoneAgentAction.TapRef("n1"),
                    requestId = "deadline-1",
                    observation = observation.binding,
                ),
                deadlineMs = 100L,
            )
        }
        fixture.driver.enteredDispatch.await()
        advanceTimeBy(101L)
        runCurrent()

        val result = call.await()
        assertTrue(result is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.UNKNOWN, (result as PhoneCallResult.Dispatched).delivery)
        assertEquals(PhoneErrorCode.DEADLINE, result.error)
        assertEquals(PhoneBlockReason.UNKNOWN_OUTCOME, fixture.controller.state.value.blocked)
    }

    @Test
    fun `a real tap then failing snapshot keeps the caller alive for the fixed error`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")

        val parent =
            async {
                val tap =
                    fixture.controller.execute(
                        fixture.request(
                            PhoneAgentAction.TapRef("n1"),
                            requestId = "tap-next",
                            observation = observation.binding,
                        ),
                    )
                assertTrue(tap is PhoneCallResult.Dispatched)
                assertEquals(PhoneDelivery.SENT, (tap as PhoneCallResult.Dispatched).delivery)

                // The page changed and the next observe hits a real window transition failure.
                fixture.driver.observeFailure = PhoneDriverFailure.WINDOW_CHANGED
                val snapshot =
                    fixture.controller.execute(
                        fixture.request(PhoneAgentAction.Observe, requestId = "snapshot-after-tap"),
                    )
                assertTrue(
                    "the caller job was cancelled by the failing snapshot child",
                    currentCoroutineContext().isActive,
                )
                snapshot
            }

        val result = parent.await()
        assertTrue(result is PhoneCallResult.Refused)
        assertEquals("snapshot-after-tap", result.requestId)
        assertEquals(PhoneErrorCode.WINDOW_CHANGED, (result as PhoneCallResult.Refused).error)
        assertFalse(parent.isCancelled)
    }

    @Test
    fun `observe driver error returns a fixed refusal without cancelling the caller`() = runTest {
        val fixture = Fixture()
        fixture.active()
        fixture.driver.observeFailure = PhoneDriverFailure.WINDOW_CHANGED

        val parent =
            async {
                val result =
                    fixture.controller.execute(
                        fixture.request(PhoneAgentAction.Observe, requestId = "observe-error"),
                    )
                assertTrue("the caller job was cancelled by the child driver error", currentCoroutineContext().isActive)
                result
            }

        val result = parent.await()
        assertTrue(result is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.WINDOW_CHANGED, (result as PhoneCallResult.Refused).error)
        assertFalse(parent.isCancelled)
    }

    @Test
    fun `capture driver error returns a fixed refusal without cancelling the caller`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        fixture.driver.captureFailure = PhoneDriverFailure.DRIVER

        val parent =
            async {
                val result =
                    fixture.controller.execute(
                        fixture.request(
                            PhoneAgentAction.Capture,
                            requestId = "capture-error",
                            observation = observation.binding,
                        ),
                    )
                assertTrue("the caller job was cancelled by the child driver error", currentCoroutineContext().isActive)
                result
            }

        val result = parent.await()
        assertTrue(result is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.DRIVER_UNAVAILABLE, (result as PhoneCallResult.Refused).error)
        assertFalse(parent.isCancelled)
    }

    @Test
    fun `mutation driver error after start stays unknown and keeps the caller alive`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        fixture.driver.dispatchHandler = { throw PhoneDriverException(PhoneDriverFailure.DRIVER) }

        val parent =
            async {
                val result =
                    fixture.controller.execute(
                        fixture.request(
                            PhoneAgentAction.TapRef("n1"),
                            requestId = "mutation-error",
                            observation = observation.binding,
                        ),
                    )
                assertTrue("the caller job was cancelled by the child driver error", currentCoroutineContext().isActive)
                result
            }

        val result = parent.await()
        assertTrue(result is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.UNKNOWN, (result as PhoneCallResult.Dispatched).delivery)
        assertEquals(PhoneErrorCode.DRIVER_UNAVAILABLE, result.error)
        assertEquals(PhoneBlockReason.UNKNOWN_OUTCOME, fixture.controller.state.value.blocked)
        assertFalse(parent.isCancelled)
    }

    @Test
    fun `a read timeout revokes control in the same call and keeps the first error`() = runTest {
        val fixture = Fixture()
        val grant = fixture.active()
        fixture.driver.observeFailure = PhoneDriverFailure.READ_TIMEOUT

        val call = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "read-timeout"),
        )

        assertTrue(call is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.READ_TIMEOUT, (call as PhoneCallResult.Refused).error)
        val state = fixture.controller.state.value
        assertEquals(PhoneConnectionState.DISCONNECTED, state.connection)
        assertNull(state.connectionId)
        assertNull(state.grant)
        assertNull(state.capabilities)
        assertNotEquals(grant.epoch, state.epoch)
        assertEquals(PhoneEvent.CONNECTION_LOST, state.lastEvent)
    }

    @Test
    fun `a hierarchy idle failure keeps the connection usable`() = runTest {
        val fixture = Fixture()
        val grant = fixture.active()
        val connectionId = fixture.controller.state.value.connectionId
        fixture.driver.observeFailure = PhoneDriverFailure.HIERARCHY_NOT_IDLE

        val call = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "not-idle"),
        )

        assertTrue(call is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.HIERARCHY_NOT_IDLE, (call as PhoneCallResult.Refused).error)
        val refusedState = fixture.controller.state.value
        assertEquals(PhoneConnectionState.CONNECTED, refusedState.connection)
        assertEquals(connectionId, refusedState.connectionId)
        assertEquals(grant.epoch, refusedState.epoch)
        assertNull(refusedState.observation)

        // The page becoming stable again does not need a new connection or grant.
        fixture.driver.observeFailure = null
        fixture.observe("r2")
        assertEquals(PhoneConnectionState.CONNECTED, fixture.controller.state.value.connection)
    }

    @Test
    fun `a capture read timeout publishes the same logical disconnect`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        fixture.driver.captureFailure = PhoneDriverFailure.READ_TIMEOUT

        val call = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Capture,
                requestId = "capture-read-timeout",
                observation = observation.binding,
            ),
        )

        assertTrue(call is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.READ_TIMEOUT, (call as PhoneCallResult.Refused).error)
        assertEquals(
            PhoneConnectionState.DISCONNECTED,
            fixture.controller.state.value.connection,
        )
        assertNull(fixture.controller.state.value.observation)
    }

    @Test
    fun `a normal read keeps the healthy link and epoch`() = runTest {
        val fixture = Fixture()
        val grant = fixture.active()
        val connectionId = fixture.controller.state.value.connectionId

        fixture.observe("r1")

        val state = fixture.controller.state.value
        assertEquals(PhoneConnectionState.CONNECTED, state.connection)
        assertEquals(connectionId, state.connectionId)
        assertEquals(grant.epoch, state.epoch)
        assertEquals(PhoneEvent.OBSERVED, state.lastEvent)
    }

    @Test
    fun `a mutation read timeout before the write is not sent and revokes`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        fixture.driver.dispatchReceipt =
            PhoneDispatchReceipt.NotSent(PhoneDriverFailure.READ_TIMEOUT)

        val call = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "not-sent-read-timeout",
                observation = observation.binding,
            ),
        )

        assertTrue(call is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.READ_TIMEOUT, (call as PhoneCallResult.Refused).error)
        assertEquals(PhoneDelivery.NOT_SENT, call.delivery)
        val state = fixture.controller.state.value
        assertEquals(PhoneConnectionState.DISCONNECTED, state.connection)
        assertNull("nothing was written, so no unknown block is needed", state.blocked)
    }

    @Test
    fun `an uncertain mutation read timeout stays unknown and needs a person check`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        fixture.driver.dispatchReceipt =
            PhoneDispatchReceipt.Unknown(PhoneDriverFailure.READ_TIMEOUT)

        val call = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "unknown-read-timeout",
                observation = observation.binding,
            ),
        )

        assertTrue(call is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.UNKNOWN, (call as PhoneCallResult.Dispatched).delivery)
        assertEquals(PhoneErrorCode.READ_TIMEOUT, call.error)
        val state = fixture.controller.state.value
        assertEquals(PhoneConnectionState.DISCONNECTED, state.connection)
        assertEquals("the person must still check the uncertain write", PhoneBlockReason.UNKNOWN_OUTCOME, state.blocked)
        assertTrue(state.standingBlocked)
        assertNull(state.observation)
        assertEquals("the uncertain action is never replayed", 1, fixture.driver.dispatched.size)

        fixture.controller.acknowledgeUnknownOutcome()
        assertNull(fixture.controller.state.value.blocked)
    }

    @Test
    fun `an inject deadline keeps the connection and unknown outcome`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        fixture.driver.dispatchReceipt = PhoneDispatchReceipt.Unknown(PhoneDriverFailure.TIMEOUT)

        val call = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "inject-timeout",
                observation = observation.binding,
            ),
        )

        assertTrue(call is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.UNKNOWN, (call as PhoneCallResult.Dispatched).delivery)
        assertEquals(PhoneErrorCode.DEADLINE, call.error)
        val state = fixture.controller.state.value
        assertEquals(PhoneConnectionState.CONNECTED, state.connection)
        assertEquals(PhoneBlockReason.UNKNOWN_OUTCOME, state.blocked)
    }

    @Test
    fun `a hard read timeout keeps READ_TIMEOUT as the first cause across the next attempt`() = runTest {
        val fixture = Fixture()
        fixture.active()
        fixture.driver.observeHandler = {
            // The LibADB hard watchdog expires on the driver budget (the total call minus the
            // reporting margin) and its fixed failure unwinds there, before the outer bound.
            delay(fixture.driver.lastObserveDeadline)
            throw PhoneDriverException(PhoneDriverFailure.READ_TIMEOUT)
        }

        val first = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "hard-read-timeout"),
            deadlineMs = 100L,
        )

        assertTrue(first is PhoneCallResult.Refused)
        val firstRefused = first as PhoneCallResult.Refused
        assertEquals(PhoneErrorCode.READ_TIMEOUT, firstRefused.error)
        assertNotEquals(PhoneErrorCode.DEADLINE, firstRefused.error)
        assertEquals("driver budget keeps the reporting margin", 90L, fixture.driver.lastObserveDeadline)
        val afterFirst = fixture.controller.state.value
        assertEquals(PhoneConnectionState.DISCONNECTED, afterFirst.connection)
        assertEquals(PhoneEvent.CONNECTION_LOST, afterFirst.lastEvent)
        assertNull(afterFirst.observation)
        val observeCallsAfterFirst = fixture.driver.observeCalls

        // The next attempt cannot reach the abandoned connection or overwrite the first cause.
        fixture.driver.observeFailure = null
        val next = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Observe,
                epoch = afterFirst.epoch,
                requestId = "next-attempt",
            ),
            deadlineMs = 100L,
        )
        assertTrue(next is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.NOT_GRANTED, (next as PhoneCallResult.Refused).error)
        assertEquals(observeCallsAfterFirst, fixture.driver.observeCalls)
        assertEquals(PhoneErrorCode.READ_TIMEOUT, firstRefused.error)
        assertEquals(PhoneEvent.CONNECTION_LOST, fixture.controller.state.value.lastEvent)
        assertNull(fixture.controller.state.value.observation)
    }

    @Test
    fun `the fresh total call budget leaves the driver only the reporting margin`() {
        val full = driverCallBudget(deadlineAtMs = 6_000L, nowMs = 1_000L)
        assertEquals(5_000L, full?.outerTimeoutMs)
        assertEquals(4_500L, full?.driverTimeoutMs)

        val short = driverCallBudget(deadlineAtMs = 1_100L, nowMs = 1_000L)
        assertEquals(100L, short?.outerTimeoutMs)
        assertEquals(90L, short?.driverTimeoutMs)

        assertNull("a passed plan deadline must not start the driver", driverCallBudget(6_000L, 6_000L))
        assertNull(driverCallBudget(6_000L, 6_001L))
    }

    @Test
    fun `a plan that expires before the driver starts never calls it`() = runTest {
        var clockCalls = 0
        val fixture = Fixture(
            clockOverride = {
                clockCalls += 1
                // plan + reserve have already run; the driver must not start now.
                if (clockCalls >= 4) 20_000L else 1_000L
            },
        )
        fixture.active()
        clockCalls = 0

        val call = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "past-deadline"),
            deadlineMs = 100L,
        )

        assertTrue(call is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.DEADLINE, (call as PhoneCallResult.Refused).error)
        assertEquals("no driver call may start after the plan deadline", 0, fixture.driver.observeCalls)
        assertEquals(
            "a deadline that never reached the driver does not guess a disconnect",
            PhoneConnectionState.CONNECTED,
            fixture.controller.state.value.connection,
        )
    }

    @Test
    fun `a snapshot arriving after the total call deadline is refused`() = runTest {
        val fixture = Fixture()
        fixture.active()
        fixture.driver.observeHandler = {
            delay(5_100L)
            fixture.driver.snapshot
        }

        val call = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "late-snapshot"),
            deadlineMs = 5_000L,
        )

        assertTrue(call is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.DEADLINE, (call as PhoneCallResult.Refused).error)
        assertNull(fixture.controller.state.value.observation)
    }

    @Test
    fun `a capture arriving after the total call deadline is refused`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        fixture.driver.captureHandler = {
            delay(5_100L)
            fixture.driver.image
        }

        val call = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Capture,
                requestId = "late-capture",
                observation = observation.binding,
            ),
            deadlineMs = 5_000L,
        )

        assertTrue(call is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.DEADLINE, (call as PhoneCallResult.Refused).error)
    }

    @Test
    fun `a late sent receipt is never reported as sent`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        fixture.driver.dispatchHandler = {
            delay(5_100L)
            PhoneDispatchReceipt.Sent
        }

        val call = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "late-sent",
                observation = observation.binding,
            ),
            deadlineMs = 5_000L,
        )

        assertTrue(call is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.UNKNOWN, (call as PhoneCallResult.Dispatched).delivery)
        assertEquals(PhoneErrorCode.DEADLINE, call.error)
        assertEquals(PhoneBlockReason.UNKNOWN_OUTCOME, fixture.controller.state.value.blocked)
    }

    @Test
    fun `a NotSent READ_TIMEOUT after the plan deadline still disconnects and keeps the cause`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        fixture.driver.dispatchHandler = {
            // The hard read watchdog already abandoned the connection; the controller's own
            // deadline passes before the receipt is processed.
            fixture.now = 11_000L
            PhoneDispatchReceipt.NotSent(PhoneDriverFailure.READ_TIMEOUT)
        }

        val call = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "late-not-sent-read-timeout",
                observation = observation.binding,
            ),
            deadlineMs = 5_000L,
        )

        assertTrue(call is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.READ_TIMEOUT, (call as PhoneCallResult.Refused).error)
        val state = fixture.controller.state.value
        assertEquals(PhoneConnectionState.DISCONNECTED, state.connection)
        assertNull(state.connectionId)
        assertNull(state.grant)
    }

    @Test
    fun `a read timeout only revokes the exact planned connection`() {
        assertTrue(readTimeoutOwnsConnection("conn-1", "conn-1"))
        assertFalse("a newer reconnect must survive a late old failure", readTimeoutOwnsConnection("conn-1", "conn-2"))
        assertFalse(readTimeoutOwnsConnection("conn-1", null))
        assertFalse(readTimeoutOwnsConnection(null, "conn-1"))
    }

    @Test
    fun `an unconfirmable read stays OUTCOME_UNKNOWN and does not fake a disconnect`() = runTest {
        val fixture = Fixture()
        val grant = fixture.active()
        fixture.driver.observeFailure = PhoneDriverFailure.OUTCOME_UNKNOWN

        val call = fixture.controller.execute(
            fixture.request(PhoneAgentAction.Observe, requestId = "outcome-unknown-read"),
        )

        assertTrue(call is PhoneCallResult.Refused)
        assertEquals(PhoneErrorCode.OUTCOME_UNKNOWN, (call as PhoneCallResult.Refused).error)
        val state = fixture.controller.state.value
        assertEquals(PhoneConnectionState.CONNECTED, state.connection)
        assertEquals(grant.epoch, state.epoch)
        assertNull(state.observation)
    }

    @Test
    fun `external cancellation propagates and pause still blocks new mutations`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")
        fixture.driver.dispatchHandler = { awaitCancellation() }

        val call =
            async {
                fixture.controller.execute(
                    fixture.request(
                        PhoneAgentAction.TapRef("n1"),
                        requestId = "external-cancel",
                        observation = observation.binding,
                    ),
                )
            }
        fixture.driver.enteredDispatch.await()
        call.cancel()

        var cancelled = false
        try {
            call.await()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("external cancellation must propagate", cancelled)
        assertTrue(call.isCancelled)
        // The mutation had started, so the core conservatively keeps the unknown-outcome block.
        assertEquals(PhoneBlockReason.UNKNOWN_OUTCOME, fixture.controller.state.value.blocked)

        fixture.controller.pause()
        val paused =
            fixture.controller.execute(
                fixture.request(
                    PhoneAgentAction.TapPoint(PhonePoint(10, 10)),
                    requestId = "paused-after-cancel",
                ),
            )
        assertEquals(PhoneErrorCode.PAUSED, (paused as PhoneCallResult.Refused).error)

        fixture.controller.resume()
        val fresh = fixture.observe("r2")
        val blocked =
            fixture.controller.execute(
                fixture.request(
                    PhoneAgentAction.TapRef("n1"),
                    requestId = "blocked-after-cancel",
                    observation = fresh.binding,
                ),
            )
        assertEquals(PhoneErrorCode.OUTCOME_UNKNOWN, (blocked as PhoneCallResult.Refused).error)
    }

    @Test
    fun `unicode input is refused explicitly unless the driver declares it`() = runTest {
        val noUnicode = Fixture()
        noUnicode.active()
        val observation = noUnicode.observe("r1")

        val refused = noUnicode.controller.execute(
            noUnicode.request(
                PhoneAgentAction.Input("你好", ref = "n2"),
                requestId = "r2",
                observation = observation.binding,
            ),
        )
        assertEquals(PhoneErrorCode.UNICODE_UNSUPPORTED, (refused as PhoneCallResult.Refused).error)
        assertTrue(noUnicode.driver.dispatched.isEmpty())

        val ascii = noUnicode.controller.execute(
            noUnicode.request(
                PhoneAgentAction.Input("hello", ref = "n2"),
                requestId = "r3",
                observation = observation.binding,
            ),
        )
        assertTrue(ascii is PhoneCallResult.Dispatched)

        val withUnicode = Fixture()
        withUnicode.driver.capabilities = fullCapabilities(unicodeInput = true)
        withUnicode.active()
        val unicodeObservation = withUnicode.observe("r1")
        val accepted = withUnicode.controller.execute(
            withUnicode.request(
                PhoneAgentAction.Input("你好", ref = "n2"),
                requestId = "r2",
                observation = unicodeObservation.binding,
            ),
        )
        assertTrue(accepted is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.SENT, (accepted as PhoneCallResult.Dispatched).delivery)
    }

    @Test
    fun `fine grained capabilities gate each action`() = runTest {
        val fixture = Fixture()
        fixture.driver.capabilities = fullCapabilities().copy(
            capture = false,
            swipe = false,
            back = false,
        )
        fixture.active()
        val observation = fixture.observe("r1")

        val capture = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Capture,
                requestId = "r2",
                observation = observation.binding,
            ),
        )
        assertEquals(PhoneErrorCode.UNSUPPORTED_ACTION, (capture as PhoneCallResult.Refused).error)

        val swipe = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Swipe(PhonePoint(100, 100), PhonePoint(500, 500), 200),
                requestId = "r3",
                observation = observation.binding,
            ),
        )
        assertEquals(PhoneErrorCode.UNSUPPORTED_ACTION, (swipe as PhoneCallResult.Refused).error)

        val back = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Back,
                requestId = "r4",
                observation = observation.binding,
            ),
        )
        assertEquals(PhoneErrorCode.UNSUPPORTED_ACTION, (back as PhoneCallResult.Refused).error)

        val tap = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r5",
                observation = observation.binding,
            ),
        )
        assertTrue(tap is PhoneCallResult.Dispatched)

        // A sent mutation discards the old snapshot, so a new one is required.
        val afterTap = fixture.observe("r6")
        val input = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Input("hello", ref = "n2"),
                requestId = "r7",
                observation = afterTap.binding,
            ),
        )
        assertTrue(input is PhoneCallResult.Dispatched)

        assertEquals(2, fixture.driver.dispatched.size)
        assertEquals(0, fixture.driver.captureCalls)

        val noObserve = Fixture()
        noObserve.driver.capabilities = fullCapabilities().copy(observe = false)
        noObserve.active()
        val observe = noObserve.controller.execute(
            noObserve.request(PhoneAgentAction.Observe, requestId = "r1"),
        )
        assertEquals(PhoneErrorCode.UNSUPPORTED_ACTION, (observe as PhoneCallResult.Refused).error)
    }

    @Test
    fun `bounded action shapes are resolved against the observation`() = runTest {
        val fixture = Fixture()
        fixture.active()
        val observation = fixture.observe("r1")

        val swipe = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Swipe(PhonePoint(100, 100), PhonePoint(500, 500), 200),
                requestId = "r2",
                observation = observation.binding,
            ),
        )
        assertTrue(swipe is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.SENT, (swipe as PhoneCallResult.Dispatched).delivery)

        val afterSwipe = fixture.observe("r2b")
        val input = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Input("hello", ref = "n2"),
                requestId = "r3",
                observation = afterSwipe.binding,
            ),
        )
        assertTrue(input is PhoneCallResult.Dispatched)
        val inputAction = fixture.driver.dispatched[1].action as PhoneDriverAction.Input
        assertEquals("hello", inputAction.text)
        assertEquals(PhonePoint(500, 340), inputAction.focus)

        val afterInput = fixture.observe("r3b")
        val back = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Back,
                requestId = "r4",
                observation = afterInput.binding,
            ),
        )
        assertTrue(back is PhoneCallResult.Dispatched)
        assertTrue(fixture.driver.dispatched[2].action is PhoneDriverAction.Back)

        val afterBack = fixture.observe("r4b")
        val outOfBounds = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapPoint(PhonePoint(2_000, 10)),
                requestId = "r5",
                observation = afterBack.binding,
            ),
        )
        assertEquals(PhoneErrorCode.OUT_OF_BOUNDS, (outOfBounds as PhoneCallResult.Refused).error)

        val notEditable = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.Input("hello", ref = "n1"),
                requestId = "r6",
                observation = afterBack.binding,
            ),
        )
        assertEquals(PhoneErrorCode.UNSUPPORTED_ACTION, (notEditable as PhoneCallResult.Refused).error)
        assertEquals(3, fixture.driver.dispatched.size)
    }

    @Test
    fun `a dispatch request without a controller guard denies by default`() {
        assertEquals(PhoneErrorCode.CANCELLED, PhoneAdmissionGuard.DENY.check())
    }

    @Test
    fun `state sequence is strictly monotonic`() = runTest {
        val fixture = Fixture()
        val sequences = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.controller.state.collect { sequences += it.sequence }
        }

        fixture.controller.enable()
        fixture.controller.connect()
        fixture.controller.grant(TARGET, setOf(PhoneActionKind.TAP), 60_000L)
        fixture.observe("r1")
        fixture.controller.pause()
        fixture.controller.resume()
        fixture.controller.revoke()
        runCurrent()

        assertTrue("only observed ${sequences.size} states", sequences.size >= 6)
        sequences.zipWithNext().forEach { (before, after) ->
            assertTrue("$before is not below $after", before < after)
        }
        assertEquals(fixture.controller.state.value.sequence, sequences.last())
        assertEquals(PhoneEvent.REVOKED, fixture.controller.state.value.lastEvent)
        assertNull(fixture.controller.state.value.observation)
    }

    @Test
    fun `a permanent native grant is never expired by time and still executes`() = runTest {
        val fixture = Fixture()
        fixture.controller.enable()
        assertTrue(fixture.controller.connect() is PhoneConnectionResult.Connected)

        val granted =
            fixture.controller.grant(TARGET, setOf(PhoneActionKind.TAP), PhoneTimeLimits.PERMANENT)
        val grant = (granted as PhoneGrantResult.Granted).grant
        assertEquals(PhoneTimeLimits.PERMANENT, grant.expiresAtMs)

        val observation = fixture.observe("r1")
        // Far beyond any finite lease, but still with room for a finite call deadline.
        fixture.now = 10L * 365L * 24L * 60L * 60L * 1_000L
        fixture.controller.refresh()
        assertEquals(grant, fixture.controller.state.value.grant)
        assertNotEquals(PhoneEvent.LEASE_EXPIRED, fixture.controller.state.value.lastEvent)
        assertEquals(PhonePhase.READY, fixture.controller.state.value.phase)

        val tap = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r2",
                observation = observation.binding,
            ),
        )
        assertTrue("a permanent lease refused a normal call: $tap", tap is PhoneCallResult.Dispatched)
        assertEquals(PhoneDelivery.SENT, (tap as PhoneCallResult.Dispatched).delivery)
    }

    @Test
    fun `permanent is the only ttl outside the finite lease range`() = runTest {
        val fixture = Fixture()
        fixture.controller.enable()

        listOf(0L, -1L, PhoneLimits.MAX_LEASE_MS + 1L).forEach { ttl ->
            val refused = fixture.controller.grant(TARGET, setOf(PhoneActionKind.TAP), ttl)
            assertEquals(
                "ttl=$ttl",
                PhoneErrorCode.INVALID_REQUEST,
                (refused as PhoneGrantResult.Refused).error,
            )
        }
        assertTrue(
            fixture.controller.grant(TARGET, setOf(PhoneActionKind.TAP), PhoneTimeLimits.PERMANENT)
                is PhoneGrantResult.Granted,
        )
    }

    @Test
    fun `an unknown outcome still blocks a permanent standing scope`() = runTest {
        val fixture = Fixture()
        fixture.controller.enable()
        assertTrue(fixture.controller.connect() is PhoneConnectionResult.Connected)
        val permanentScope = PhoneStandingScope(
            runtimeId = "runtime-1",
            sessionLabel = "ses-a",
            allowedPackages = listOf(TARGET),
            allowedActions = setOf(PhoneActionKind.TAP),
            windowEndsMs = PhoneTimeLimits.PERMANENT,
            perTaskMs = PhoneTimeLimits.PERMANENT,
        )
        val granted = fixture.controller.grantFromPreauthorization(permanentScope, TARGET)
        assertTrue(granted is PhoneStandingGrantResult.Granted)
        assertEquals(PhoneTimeLimits.PERMANENT, (granted as PhoneStandingGrantResult.Granted).grant.expiresAtMs)

        fixture.driver.dispatchReceipt = PhoneDispatchReceipt.Unknown()
        val observation = fixture.observe("r1")
        val first = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r2",
                observation = observation.binding,
            ),
        )
        assertEquals(PhoneDelivery.UNKNOWN, (first as PhoneCallResult.Dispatched).delivery)
        assertEquals(PhoneBlockReason.UNKNOWN_OUTCOME, fixture.controller.state.value.blocked)
        assertTrue(fixture.controller.state.value.standingBlocked)

        val fresh = fixture.observe("r3")
        val blocked = fixture.controller.execute(
            fixture.request(
                PhoneAgentAction.TapRef("n1"),
                requestId = "r4",
                observation = fresh.binding,
            ),
        )
        assertEquals(PhoneErrorCode.OUTCOME_UNKNOWN, (blocked as PhoneCallResult.Refused).error)
        assertEquals("the unknown outcome must not be replayed", 1, fixture.driver.dispatched.size)
    }

    private class Fixture(
        requestCapacity: Int = 8,
        clockOverride: (() -> Long)? = null,
    ) {
        var now = 1_000L
        private var requestIds = 0
        private var observationIds = 0
        val driver = FakePhoneDriver()
        val controller = PhoneController(
            driver = driver,
            clock = clockOverride ?: { now },
            requestCapacity = requestCapacity,
            newObservationId = { "obs-${++observationIds}" },
        )

        suspend fun active(
            target: String = TARGET,
            actions: Set<PhoneActionKind> = PhoneActionKind.values().toSet(),
            ttlMs: Long = 60_000L,
        ): PhoneGrant {
            controller.enable()
            assertTrue(controller.connect() is PhoneConnectionResult.Connected)
            val grant = controller.grant(target, actions, ttlMs)
            assertTrue(grant is PhoneGrantResult.Granted)
            return (grant as PhoneGrantResult.Granted).grant
        }

        suspend fun observe(requestId: String): PhoneObservation {
            val result = controller.execute(request(PhoneAgentAction.Observe, requestId = requestId))
            assertTrue("expected observation but was $result", result is PhoneCallResult.Observed)
            return (result as PhoneCallResult.Observed).observation
        }

        fun nextId(): String = "r-${++requestIds}"

        fun request(
            action: PhoneAgentAction,
            epoch: Long = controller.state.value.epoch,
            requestId: String? = null,
            observation: PhoneObservationBinding? = null,
            sessionLabel: String = "session-a",
        ): PhoneAgentRequest = PhoneAgentRequest(
            requestId = requestId ?: nextId(),
            epoch = epoch,
            sessionLabel = sessionLabel,
            action = action,
            observation = observation,
        )
    }

    private class FakePhoneDriver : PhoneDriver {
        var connection = PhoneConnection("conn-1", "runtime-1")
        var connectCalls = 0
        var disconnectCalls = 0
        var capabilities = fullCapabilities()
        var snapshot = snapshot()
        var image = PhoneImage(byteArrayOf(1, 2, 3), "image/png", 1080, 2400)
        var observeCalls = 0
        var captureCalls = 0
        var lastObserveDeadline = 0L
        var lastCaptureDeadline = 0L
        var lastDispatchDeadline = 0L
        var observeHandler: (suspend () -> PhoneSnapshot)? = null
        var captureHandler: (suspend () -> PhoneImage)? = null
        val dispatched = mutableListOf<PhoneDispatchRequest>()
        var dispatchHandler: (suspend (PhoneDispatchRequest) -> PhoneDispatchReceipt)? = null
        var dispatchReceipt: PhoneDispatchReceipt = PhoneDispatchReceipt.Sent
        var observeFailure: PhoneDriverFailure? = null
        var captureFailure: PhoneDriverFailure? = null
        val enteredDispatch = CompletableDeferred<Unit>()
        val enteredObserve = CompletableDeferred<Unit>()

        override fun capabilities(): PhoneCapabilities = capabilities

        override suspend fun connect(deadlineMs: Long): PhoneConnection {
            connectCalls += 1
            return connection
        }

        override suspend fun disconnect(deadlineMs: Long) {
            disconnectCalls += 1
        }

        override suspend fun observe(deadlineMs: Long): PhoneSnapshot {
            observeCalls += 1
            lastObserveDeadline = deadlineMs
            enteredObserve.complete(Unit)
            observeFailure?.let { throw PhoneDriverException(it) }
            return observeHandler?.invoke() ?: snapshot.copy()
        }

        override suspend fun capture(
            binding: PhoneObservationBinding,
            deadlineMs: Long,
        ): PhoneImage {
            captureCalls += 1
            lastCaptureDeadline = deadlineMs
            captureFailure?.let { throw PhoneDriverException(it) }
            return captureHandler?.invoke() ?: image
        }

        override suspend fun dispatch(
            request: PhoneDispatchRequest,
            deadlineMs: Long,
        ): PhoneDispatchReceipt {
            lastDispatchDeadline = deadlineMs
            dispatched += request
            enteredDispatch.complete(Unit)
            return dispatchHandler?.invoke(request) ?: dispatchReceipt
        }
    }

    private companion object {
        const val TARGET = "com.example.target"
        const val OTHER = "com.example.other"

        fun fullCapabilities(unicodeInput: Boolean = false): PhoneCapabilities = PhoneCapabilities(
            observe = true,
            capture = true,
            tap = true,
            swipe = true,
            input = true,
            unicodeInput = unicodeInput,
            back = true,
        )

        fun snapshot(
            packageName: String = TARGET,
            windowId: String = "window-1",
        ): PhoneSnapshot {
            val first = PhoneRect(10, 20, 110, 120)
            val second = PhoneRect(200, 300, 800, 380)
            return PhoneSnapshot(
                packageName = packageName,
                windowId = windowId,
                geometry = PhoneGeometry(1080, 2400, 0),
                nodes = listOf(
                    PhoneNode("n1", "button", "Open", first, clickable = true),
                    PhoneNode("n2", "textbox", "Name", second, editable = true),
                ),
                refs = mapOf("n1" to first, "n2" to second),
            )
        }
    }
}
