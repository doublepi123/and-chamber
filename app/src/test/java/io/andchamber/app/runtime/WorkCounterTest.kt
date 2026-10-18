package io.andchamber.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkCounterTest {
    @Test
    fun `busy follows the count`() {
        val counter = WorkCounter()
        assertFalse(counter.busy.value)
        counter.begin()
        counter.begin()
        assertTrue(counter.busy.value)
        counter.end()
        assertTrue("one item is still queued", counter.busy.value)
        counter.end()
        assertFalse(counter.busy.value)
    }

    @Test(expected = IllegalStateException::class)
    fun `end without begin is a bug and is refused`() {
        WorkCounter().end()
    }

    /**
     * Real threads: a worker keeps finishing its operation while a requester queues the next one.
     * Busy must never read false while the count is above zero, and must end false once drained.
     */
    @Test
    fun `concurrent begin and end never publish false while work is counted`() {
        val counter = WorkCounter()
        val iterations = 20_000
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val violations = java.util.concurrent.atomic.AtomicInteger(0)

        // Previous operation finishing on one thread...
        counter.begin()
        val finisher =
            pool.submit {
                start.await()
                repeat(iterations) {
                    counter.begin() // the next request is queued before the previous one ends
                    counter.end() // previous one ends
                }
                counter.end() // the last request ends
            }
        // ...while a new request is queued on another thread and watchers sample the flag.
        val watcher =
            pool.submit {
                start.await()
                repeat(iterations) {
                    val busy = counter.busy.value
                    if (!busy && counter.pending() > 0) violations.incrementAndGet()
                }
            }
        start.countDown()
        finisher.get(60, TimeUnit.SECONDS)
        watcher.get(60, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals("busy was false while work was counted", 0, violations.get())
        assertEquals(0, counter.pending())
        assertFalse(counter.busy.value)
    }
}
