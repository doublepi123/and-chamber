package io.andchamber.app.runtime.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Orchestration regression for the production teardown path. These tests call
 * [GuestOutputPump.awaitAndTearDown], the same function [OpenChamberInstaller.GuestRunner.run] calls,
 * with a fake command wait. Nothing here starts PRoot or a real process.
 *
 * Clock choice: the fake clock is used where the test only needs the deadline position. The
 * "abort does not get a fresh window" check needs real elapsed time, because a fake clock does not
 * move while the drain waits, so that test uses the real monotonic clock with a short budget.
 */
class GuestOutputTeardownTest {
    private val waitMillis = 5_000L
    private val budgetNanos = 300L * 1_000_000L

    /** Clock the test moves only inside the fake command wait, so deadline placement is explicit. */
    private class FakeClock {
        private val now = AtomicLong(0L)

        fun read(): Long = now.get()

        fun advanceMillis(millis: Long) {
            now.addAndGet(millis * 1_000_000L)
        }
    }

    /** Queue-driven pipe: push text, then end-of-stream or a failure. A read blocks until something is pushed. */
    private class Pipe : InputStream() {
        private val readWaitMillis = 5_000L

        private val steps = LinkedBlockingQueue<Any>()

        fun push(text: String) = steps.put(text)

        fun eof() = steps.put(EOF)

        override fun read(): Int = throw IllegalStateException("not used")

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val step = steps.poll(readWaitMillis, TimeUnit.MILLISECONDS) ?: throw IllegalStateException("pipe script ran dry")
            return when (step) {
                is String -> {
                    val bytes = step.toByteArray()
                    System.arraycopy(bytes, 0, b, off, bytes.size)
                    bytes.size
                }
                is IOException -> throw step
                else -> -1
            }
        }

        override fun close() {
            steps.offer(IOException("Stream closed"))
        }

        private companion object {
            val EOF = Any()
        }
    }

    /**
     * The command runs for 5 s of fake time, longer than the 300 ms teardown budget. Its last lines are
     * still unread when it exits, and end-of-stream arrives shortly after the exit. The production wait must
     * start the teardown window only after the command returns, so the trailing lines are delivered and the
     * call succeeds.
     *
     * Red/green: the end-of-stream is delayed by 100 ms of real time. An implementation that computed the
     * deadline before the command (the old production order) would already be expired here, so drain would
     * not wait, the reader would still be running, and the call would throw. This test was written to fail
     * against that order.
     */
    @Test
    fun `a command longer than the teardown budget still gets a full window and keeps its trailing lines`() {
        val clock = FakeClock()
        val pipe = Pipe()
        val delivered = mutableListOf<String>()
        val pump = GuestOutputPump(pipe, childAlive = { false }, onLine = { delivered += it }, clock = clock::read)
        pump.start()

        pump.awaitAndTearDown(
            awaitCommand = {
                clock.advanceMillis(5_000)
                pipe.push("first\ntrailing\n")
                Thread { Thread.sleep(100); pipe.eof() }.apply { isDaemon = true; start() }
                true
            },
            teardownBudgetNanos = budgetNanos,
            timeoutFailure = { IllegalStateException("timeout must not fire") },
            readerFailure = { IllegalStateException("reader must not fail", it) },
        )

        assertEquals(listOf("first", "trailing"), delivered)
    }

    /**
     * Timeout path with an observer error already recorded. The primary timeout error keeps its identity,
     * and the reader error is attached as suppressed. It does not replace the primary.
     */
    @Test
    fun `timeout keeps the primary error and attaches the reader failure as suppressed`() {
        val clock = FakeClock()
        val pipe = Pipe()
        val observerError = IllegalArgumentException("observer bug")
        val observed = CountDownLatch(1)
        val pump =
            GuestOutputPump(
                pipe,
                childAlive = { true },
                onLine = {
                    observed.countDown()
                    throw observerError
                },
                clock = clock::read,
            )
        pump.start()
        val primary = IllegalStateException("Command timed out")

        val thrown =
            runCatching {
                pump.awaitAndTearDown(
                    awaitCommand = {
                        pipe.push("x\n")
                        check(observed.await(waitMillis, TimeUnit.MILLISECONDS)) { "observer did not run" }
                        false
                    },
                    teardownBudgetNanos = budgetNanos,
                    timeoutFailure = { primary },
                    readerFailure = { IllegalStateException("unused", it) },
                )
            }.exceptionOrNull()

        assertSame("the primary timeout error is the one thrown", primary, thrown)
        assertEquals(1, primary.suppressed.count { it === observerError })
    }

    /**
     * Normal exit where the reader does not reach end-of-stream within the teardown budget. Drain waits one
     * budget of real time, then abort must reuse the same deadline (already spent) and return at once. A
     * fresh budget would add a second real wait, so the total is checked against a bound below two budgets.
     * Real monotonic clock is used on purpose: a fake clock does not advance while drain waits.
     */
    @Test
    fun `drain and abort share one deadline and abort does not get a fresh window`() {
        val pipe = Pipe()
        val pump = GuestOutputPump(pipe, childAlive = { false }, onLine = { })
        pump.start()
        var reported: Throwable? = null

        val startedAt = System.nanoTime()
        // The pipe stays open, so the reader is unfinished and awaitAndTearDown throws. Capture it.
        val thrown =
            runCatching {
                pump.awaitAndTearDown(
                    awaitCommand = { true },
                    teardownBudgetNanos = budgetNanos,
                    timeoutFailure = { IllegalStateException("unused") },
                    readerFailure = { cause ->
                        reported = cause
                        IllegalStateException("Output reader failed", cause)
                    },
                )
            }.exceptionOrNull()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        // End the reader only after timing, so the blocked read does not add to the measurement.
        pipe.eof()

        assertTrue("expected the failure to be the output reader error, got $thrown", thrown is IllegalStateException)
        assertTrue("reader was not finished: $reported", reported is GuestOutputPump.ReaderStillRunning)
        assertTrue(
            "abort must not add a fresh budget (took ${elapsedMillis}ms; one budget is about 300ms, two would be about 600ms)",
            elapsedMillis < 550,
        )
    }

    /**
     * Interrupt during the command wait: the interrupt is the primary error (same instance), and the
     * interrupt flag is restored on the calling thread before teardown.
     */
    @Test
    fun `interrupt during the command is the primary error and the interrupt flag is preserved`() {
        val pipe = Pipe()
        val pump = GuestOutputPump(pipe, childAlive = { true }, onLine = { })
        pump.start()
        val interrupted = InterruptedException("command wait interrupted")

        val thrown =
            runCatching {
                pump.awaitAndTearDown(
                    awaitCommand = { throw interrupted },
                    teardownBudgetNanos = budgetNanos,
                    timeoutFailure = { IllegalStateException("unused") },
                    readerFailure = { IllegalStateException("unused", it) },
                )
            }.exceptionOrNull()
        val flagSeen = Thread.currentThread().isInterrupted
        Thread.interrupted() // clear the flag so the rest of this JVM's tests are unaffected
        pipe.eof()

        assertSame("the interrupt is the primary error", interrupted, thrown)
        assertTrue("the interrupt flag must be restored to the caller", flagSeen)
    }

    /**
     * Real blocking close: close() itself waits on a latch, and read stays blocked until released. Abort
     * must return within its deadline and must not report a clean close, because the reader never
     * finished. The latches are released only after the assertions.
     */
    @Test
    fun `a close that blocks is bounded by the abort deadline and is not reported as a clean close`() {
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val stream =
            object : InputStream() {
                override fun read(): Int = throw IllegalStateException("not used")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    releaseRead.await(waitMillis, TimeUnit.MILLISECONDS)
                    return -1
                }

                override fun close() {
                    closeEntered.countDown()
                    releaseClose.await(waitMillis, TimeUnit.MILLISECONDS)
                }
            }
        val pump = GuestOutputPump(stream, childAlive = { true }, onLine = { })
        pump.start()

        val started = System.nanoTime()
        val result = pump.abort(System.nanoTime() + budgetNanos)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue("close must have been entered", closeEntered.await(waitMillis, TimeUnit.MILLISECONDS))
        assertTrue("abort must return near its 300 ms deadline (${elapsedMillis}ms)", elapsedMillis < 2_000)
        assertTrue("an unfinished reader is a failure, not a clean close: $result", result is GuestOutputPump.ReaderStillRunning)
        assertFalse("the caller must not be left interrupted", Thread.currentThread().isInterrupted)

        releaseClose.countDown()
        releaseRead.countDown()
    }

    /** Clean end of output through the same production function: delivered, no error. */
    @Test
    fun `a clean command with no trailing output returns without error`() {
        val pipe = Pipe()
        val delivered = mutableListOf<String>()
        val pump = GuestOutputPump(pipe, childAlive = { false }, onLine = { delivered += it })
        pump.start()
        pipe.push("only\n")
        pipe.eof()

        pump.awaitAndTearDown(
            awaitCommand = { true },
            teardownBudgetNanos = budgetNanos,
            timeoutFailure = { IllegalStateException("unused") },
            readerFailure = { IllegalStateException("unused", it) },
        )

        assertEquals(listOf("only"), delivered)
    }
}
