package io.andchamber.app.phone.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    private class Fixture(requestCapacity: Int = 8) {
        var now = 1_000L
        private var requestIds = 0
        private var observationIds = 0
        val driver = FakePhoneDriver()
        val controller = PhoneController(
            driver = driver,
            clock = { now },
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
        var observeHandler: (suspend () -> PhoneSnapshot)? = null
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
            enteredObserve.complete(Unit)
            observeFailure?.let { throw PhoneDriverException(it) }
            return observeHandler?.invoke() ?: snapshot.copy()
        }

        override suspend fun capture(
            binding: PhoneObservationBinding,
            deadlineMs: Long,
        ): PhoneImage {
            captureCalls += 1
            captureFailure?.let { throw PhoneDriverException(it) }
            return image
        }

        override suspend fun dispatch(
            request: PhoneDispatchRequest,
            deadlineMs: Long,
        ): PhoneDispatchReceipt {
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
