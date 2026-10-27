package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneErrorCode
import io.andchamber.app.phone.standing.PhoneTaskStartFailureStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The bounded observation loop used by a standing start, open-app and continuation. It has one
 * absolute budget, retries only real window/target transitions, and never hides a terminal typed
 * error behind a later connection error.
 */
class PhoneStandingObserveLoopTest {
    private val target = "com.example.target"

    @Test
    fun `about six seconds of busy reads still succeeds inside the shared budget`() = runTest {
        var now = 0L
        var reads = 0
        val result =
            observeStandingWithinBudget(
                deadlineElapsedMs = 15_000L,
                nowElapsedMs = { now },
                expectedPackage = target,
                delayFor = { wait -> now += wait },
            ) {
                reads += 1
                now += 1_000L
                if (reads < 6) {
                    PhoneStandingObserveRead.Retryable(PhoneErrorCode.WINDOW_CHANGED)
                } else {
                    PhoneStandingObserveRead.Observed(target)
                }
            }
        assertTrue(result is PhoneStandingObserveResult.Observed)
        assertEquals(6, reads)
        // Six one-second reads plus five 500ms waits: about six seconds of busy reads, not a fresh
        // 15s per attempt.
        assertEquals(8_500L, now)
    }

    @Test
    fun `a terminal read stops immediately and keeps its first typed reason`() = runTest {
        var reads = 0
        val result =
            observeStandingWithinBudget(
                deadlineElapsedMs = 15_000L,
                nowElapsedMs = { 0L },
                expectedPackage = target,
            ) {
                reads += 1
                // The old bug retried this and then reported the later NOT_CONNECTED instead.
                if (reads == 1) {
                    PhoneStandingObserveRead.Failed(PhoneErrorCode.DEADLINE)
                } else {
                    PhoneStandingObserveRead.Failed(PhoneErrorCode.NOT_CONNECTED)
                }
            }
        assertEquals(1, reads)
        assertEquals(
            PhoneErrorCode.DEADLINE,
            (result as PhoneStandingObserveResult.Failed).error,
        )
    }

    @Test
    fun `persistent hierarchy busy stops after one read and does not judge the connection`() = runTest {
        val hierarchyBusy = PhoneErrorCode.HIERARCHY_NOT_IDLE
        var reads = 0
        val result =
            observeStandingWithinBudget(
                deadlineElapsedMs = 15_000L,
                nowElapsedMs = { 0L },
                expectedPackage = target,
            ) {
                reads += 1
                PhoneStandingObserveRead.Failed(hierarchyBusy)
            }
        assertEquals(1, reads)
        assertEquals(hierarchyBusy, (result as PhoneStandingObserveResult.Failed).error)
        assertFalse(hierarchyBusy.retryableStandingObserve())
    }

    @Test
    fun `read timeout stops after one read and carries its first cause`() = runTest {
        val readTimeout = PhoneErrorCode.READ_TIMEOUT
        var reads = 0
        val result =
            observeStandingWithinBudget(
                deadlineElapsedMs = 15_000L,
                nowElapsedMs = { 0L },
                expectedPackage = target,
            ) {
                reads += 1
                if (reads == 1) {
                    PhoneStandingObserveRead.Failed(readTimeout)
                } else {
                    PhoneStandingObserveRead.Failed(PhoneErrorCode.NOT_CONNECTED)
                }
            }
        assertEquals(1, reads)
        assertEquals(readTimeout, (result as PhoneStandingObserveResult.Failed).error)
        assertFalse(readTimeout.retryableStandingObserve())
    }

    @Test
    fun `window transitions retry inside the same budget and never renew it`() = runTest {
        var now = 0L
        val remainingSeen = mutableListOf<Long>()
        var reads = 0
        val result =
            observeStandingWithinBudget(
                deadlineElapsedMs = 15_000L,
                nowElapsedMs = { now },
                expectedPackage = target,
                delayFor = { wait -> now += wait },
            ) { remaining ->
                remainingSeen += remaining
                reads += 1
                now += 8_000L
                if (reads == 1) {
                    PhoneStandingObserveRead.Retryable(PhoneErrorCode.WINDOW_CHANGED)
                } else {
                    PhoneStandingObserveRead.Retryable(PhoneErrorCode.GEOMETRY_CHANGED)
                }
            }
        assertEquals(2, reads)
        // The second read got only the real remainder (6.5s), not a fresh 15s.
        assertEquals(listOf(15_000L, 6_500L), remainingSeen)
        assertEquals(
            PhoneErrorCode.DEADLINE,
            (result as PhoneStandingObserveResult.Failed).error,
        )
        assertTrue(now > 15_000L)
    }

    @Test
    fun `a late successful read past the shared deadline is not accepted`() = runTest {
        var now = 0L
        val result =
            observeStandingWithinBudget(
                deadlineElapsedMs = 15_000L,
                nowElapsedMs = { now },
                expectedPackage = target,
            ) {
                now += 15_001L
                PhoneStandingObserveRead.Observed(target)
            }
        assertTrue(result is PhoneStandingObserveResult.Failed)
        assertEquals(PhoneErrorCode.DEADLINE, (result as PhoneStandingObserveResult.Failed).error)
    }

    @Test
    fun `a different foreground package retries only within the budget`() = runTest {
        var now = 0L
        var reads = 0
        val result =
            observeStandingWithinBudget(
                deadlineElapsedMs = 15_000L,
                nowElapsedMs = { now },
                expectedPackage = target,
                delayFor = { wait -> now += wait },
            ) {
                reads += 1
                now += 1_500L
                PhoneStandingObserveRead.Observed("com.example.other")
            }
        assertEquals(6, reads)
        assertTrue(now <= 15_000L)
        assertEquals(PhoneErrorCode.TARGET_MISMATCH, (result as PhoneStandingObserveResult.Failed).error)
    }

    @Test
    fun `cancellation propagates without another attempt`() = runTest {
        var reads = 0
        try {
            observeStandingWithinBudget(
                deadlineElapsedMs = 15_000L,
                nowElapsedMs = { 0L },
                expectedPackage = target,
            ) {
                reads += 1
                throw CancellationException("cancelled")
            }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected: the loop must not swallow it and retry.
        }
        assertEquals(1, reads)
    }

    @Test
    fun `an old owner rollback never targets a newer live task`() {
        assertFalse(
            standingRollbackTargets(
                requestedEpoch = 7L,
                liveEpoch = 8L,
                retainedEpoch = 7L,
                identityMatchesRequestedKey = true,
                identityOwnedByNewerTask = true,
            ),
        )
        assertFalse(
            standingRollbackTargets(
                requestedEpoch = 7L,
                liveEpoch = 8L,
                retainedEpoch = null,
                identityMatchesRequestedKey = false,
            ),
        )
    }

    @Test
    fun `only window transitions and target instability are retryable`() {
        listOf(
            PhoneErrorCode.WINDOW_CHANGED,
            PhoneErrorCode.GEOMETRY_CHANGED,
            PhoneErrorCode.TARGET_MISMATCH,
        ).forEach { code ->
            assertTrue(code.name, code.retryableStandingObserve())
            assertTrue(code.name, code.toStandingObserveRead() is PhoneStandingObserveRead.Retryable)
        }
        listOf(
            PhoneErrorCode.HIERARCHY_NOT_IDLE,
            PhoneErrorCode.READ_TIMEOUT,
            PhoneErrorCode.DEADLINE,
            PhoneErrorCode.NOT_CONNECTED,
            PhoneErrorCode.CANCELLED,
            PhoneErrorCode.TARGET_FORBIDDEN,
            PhoneErrorCode.DRIVER_UNAVAILABLE,
            PhoneErrorCode.STALE_OBSERVATION,
        ).forEach { code ->
            assertFalse(code.name, code.retryableStandingObserve())
            assertTrue(code.name, code.toStandingObserveRead() is PhoneStandingObserveRead.Failed)
        }
    }

    @Test
    fun `failure tokens are fixed stage and error names only`() {
        assertEquals(
            "start_failed_observation_deadline",
            standingStartFailureToken(PhoneTaskStartFailureStage.OBSERVATION, PhoneErrorCode.DEADLINE),
        )
        assertEquals(
            "start_failed_stop_entry",
            standingStartFailureToken(PhoneTaskStartFailureStage.STOP_ENTRY, null),
        )
    }
}
