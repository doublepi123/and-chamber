package io.andchamber.app.browser.tool

import io.andchamber.app.browser.tool.RequestTracker.Claim
import io.andchamber.app.browser.tool.RequestTracker.Registration
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RequestTrackerTest {
    @Test
    fun `a cancel before the claim permanently refuses the request`() {
        val tracker = RequestTracker()
        val job = Job()

        assertFalse(tracker.cancel("session:call"))
        assertEquals(Claim.CANCELLED, tracker.claim("call", "session:call"))
        assertEquals(Registration.CANCELLED, tracker.register("session:call", job))
        assertFalse(job.isCancelled)

        assertEquals(
            "the key is not admitted by a later claim either",
            Claim.CANCELLED,
            tracker.claim("call", "session:call"),
        )
    }

    @Test
    fun `a cancel after registration cancels the job`() {
        val tracker = RequestTracker()
        val job = Job()

        assertEquals(Claim.CLAIMED, tracker.claim("call", "session:call"))
        assertEquals(Registration.REGISTERED, tracker.register("session:call", job))
        assertTrue(tracker.cancel("session:call"))
        assertTrue(job.isCancelled)
        assertFalse("a second cancel leaves a tombstone, not a match", tracker.cancel("session:call"))
        assertEquals("the tombstone still refuses the key", Claim.CANCELLED, tracker.claim("call2", "session:call"))
    }

    @Test
    fun `the same claim key executes once under concurrency`() {
        repeat(20) {
            val tracker = RequestTracker()
            val workers = 12
            val gate = CountDownLatch(1)
            val granted = AtomicInteger()
            val threads =
                List(workers) {
                    Thread {
                        gate.await()
                        if (tracker.claim("call", "session:call") == Claim.CLAIMED) granted.incrementAndGet()
                    }.apply { isDaemon = true; start() }
                }
            gate.countDown()
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
            assertEquals(1, granted.get())
        }
    }

    @Test
    fun `a cancel key is never evicted by later cancels`() {
        val tracker = RequestTracker()
        assertFalse(tracker.cancel("session:first"))
        // More tombstones than the old fixed capacity (1,024).
        repeat(2_000) { tracker.cancel("session:other-$it") }

        assertEquals(
            "an overflow must not re-admit a cancelled key",
            Claim.CANCELLED,
            tracker.claim("first", "session:first"),
        )
        assertEquals(Registration.CANCELLED, tracker.register("session:first", Job()))
    }

    @Test
    fun `register and cancel racing have exactly one consistent outcome`() {
        repeat(2_000) {
            val tracker = RequestTracker()
            val job = Job()
            val gate = CountDownLatch(1)
            var registration: Registration? = null
            var cancelled = false
            val register =
                Thread {
                    gate.await()
                    registration = tracker.register("session:call", job)
                }
            val cancel =
                Thread {
                    gate.await()
                    cancelled = tracker.cancel("session:call")
                }
            register.start()
            cancel.start()
            gate.countDown()
            register.join(5_000)
            cancel.join(5_000)

            when (registration) {
                Registration.REGISTERED -> {
                    assertTrue("cancel must match the job that registered first", cancelled)
                    assertTrue(job.isCancelled)
                }
                Registration.CANCELLED -> {
                    assertFalse("the tombstone means there was no job to cancel", cancelled)
                    assertFalse(job.isCancelled)
                }
                null -> throw AssertionError("register did not run")
            }
        }
    }

    @Test
    fun `clear cancels jobs and releases the whole launch state`() {
        val tracker = RequestTracker()
        val job = Job()
        tracker.claim("call", "session:call")
        tracker.register("session:call", job)
        tracker.cancel("session:other")
        tracker.clear()

        assertTrue(job.isCancelled)
        assertEquals(
            "a tombstone from the previous launch must not survive",
            Claim.CLAIMED,
            tracker.claim("other", "session:other"),
        )
    }
}
