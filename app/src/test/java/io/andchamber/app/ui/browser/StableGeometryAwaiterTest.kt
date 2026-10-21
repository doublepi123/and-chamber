package io.andchamber.app.ui.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StableGeometryAwaiterTest {
    private data class Sample(val serial: Long, val fingerprint: String, val matches: Boolean = true)

    private class Probe {
        var latest: Sample? = null
        var requests = 0
            private set
        var current = true
        val pending = ArrayDeque<CompletableDeferred<Unit>>()

        fun request(): Long {
            requests += 1
            pending.removeFirstOrNull()?.complete(Unit)
            return requests.toLong()
        }
    }

    private suspend fun Probe.awaitPair(timeoutMs: Long = 2_000): Pair<Sample, Sample>? =
        awaitStableGeometryPair(
            request = ::request,
            latest = { latest },
            serial = Sample::serial,
            matches = Sample::matches,
            fingerprint = Sample::fingerprint,
            stillCurrent = { current },
            timeoutMs = timeoutMs,
        )

    @Test fun `a pending first callback does not return and requests once`() = runTest {
        val probe = Probe()
        val waiting = CompletableDeferred<Unit>()
        probe.pending += waiting
        val result = async { probe.awaitPair() }
        waiting.await()
        advanceTimeBy(64)
        assertEquals(1, probe.requests)
        assertEquals(false, result.isCompleted)
        result.cancel()
    }

    @Test fun `a complete first sample stays pending until a second request`() = runTest {
        val probe = Probe()
        val secondRequested = CompletableDeferred<Unit>()
        probe.pending += secondRequested
        val result = async { probe.awaitPair() }
        advanceTimeBy(16)
        probe.latest = Sample(1, "wide")
        advanceTimeBy(16)
        secondRequested.await()
        assertEquals(2, probe.requests)
        assertEquals(false, result.isCompleted)
        result.cancel()
    }

    @Test fun `an old serial is ignored and a matching new second returns`() = runTest {
        val probe = Probe()
        probe.latest = Sample(0, "old")
        val result = async { probe.awaitPair() }
        advanceTimeBy(16)
        assertEquals(1, probe.requests)
        assertEquals(false, result.isCompleted)
        probe.latest = Sample(1, "wide")
        advanceTimeBy(16)
        probe.latest = Sample(2, "wide")
        advanceTimeBy(16)
        assertEquals(Sample(1, "wide") to Sample(2, "wide"), result.await())
        assertEquals(2, probe.requests)
    }

    @Test fun `a changed fingerprint starts a fresh pair`() = runTest {
        val probe = Probe()
        val result = async { probe.awaitPair() }
        advanceTimeBy(16)
        probe.latest = Sample(1, "wide")
        advanceTimeBy(32)
        probe.latest = Sample(2, "narrow")
        advanceTimeBy(32)
        probe.latest = Sample(4, "narrow")
        advanceTimeBy(16)
        assertEquals(Sample(2, "narrow") to Sample(4, "narrow"), result.await())
    }

    @Test fun `an externally superseded serial is accepted`() = runTest {
        val probe = Probe()
        val result = async { probe.awaitPair() }
        advanceTimeBy(16)
        probe.latest = Sample(7, "wide")
        advanceTimeBy(32)
        probe.latest = Sample(9, "wide")
        advanceTimeBy(16)
        assertEquals(Sample(7, "wide") to Sample(9, "wide"), result.await())
    }

    @Test fun `a failed match or a lost source returns null`() = runTest {
        val rejected = Probe()
        val rejectedResult = async { rejected.awaitPair(timeoutMs = 80) }
        advanceTimeBy(16)
        rejected.latest = Sample(1, "wide", matches = false)
        advanceTimeBy(80)
        assertNull(rejectedResult.await())

        val lost = Probe()
        val lostResult = async { lost.awaitPair() }
        advanceTimeBy(16)
        lost.current = false
        advanceTimeBy(16)
        assertNull(lostResult.await())
    }

    @Test fun `timeout does not return a cached sample`() = runTest {
        val probe = Probe()
        probe.latest = Sample(0, "cached")
        val result = async { probe.awaitPair(timeoutMs = 48) }
        advanceTimeBy(64)
        assertNull(result.await())
        assertEquals(1, probe.requests)
    }
}
