package io.andchamber.app.runtime.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Deterministic pump tests. Time is never guessed with sleeps: every ordering is driven by a queue
 * or latch, and every wait has a generous timeout that fails the test instead of hanging it.
 */
class GuestOutputPumpTest {
    private val waitMillis = 5_000L

    /** One scripted read result: bytes, end-of-stream, or an error to throw. */
    private sealed interface Step {
        class Bytes(val text: String) : Step

        object Eof : Step

        class Fail(val error: IOException) : Step
    }

    /**
     * A pipe stand-in driven by a queue. A read blocks until the test pushes the next [Step].
     * [close] behaves like a real pipe: it makes a blocked or future read fail with IOException("Stream closed").
     */
    private class ScriptedStream : InputStream() {
        private companion object {
            /** Same bound as the test class's waitMillis; a nested class cannot read the outer instance. */
            const val SCRIPT_WAIT_MILLIS = 5_000L
        }

        private val steps = LinkedBlockingQueue<Step>()

        @Volatile var closed = false
            private set

        fun push(step: Step) {
            steps.put(step)
        }

        override fun read(): Int = throw IllegalStateException("not used")

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val step = steps.poll(SCRIPT_WAIT_MILLIS, TimeUnit.MILLISECONDS) ?: throw IllegalStateException("test script ran dry")
            if (closed) throw IOException("Stream closed")
            return when (step) {
                is Step.Bytes -> {
                    val bytes = step.text.toByteArray()
                    System.arraycopy(bytes, 0, b, off, bytes.size)
                    bytes.size
                }
                Step.Eof -> -1
                is Step.Fail -> throw step.error
            }
        }

        override fun close() {
            closed = true
            // Wake a blocked read with the failure a real closed pipe produces. offer() is used, not put(),
            // so a caller whose interrupt flag is set still completes the close.
            steps.offer(Step.Fail(IOException("Stream closed")))
        }
    }

    private fun pump(
        stream: InputStream,
        childAlive: () -> Boolean = { true },
        onLine: (String) -> Unit,
    ) = GuestOutputPump(stream, childAlive = childAlive, onLine = onLine).also { it.start() }

    private fun deadline(millis: Long) = System.nanoTime() + millis * 1_000_000L

    // --- normal exit: trailing lines must survive -----------------------------------------------

    /**
     * The child has already exited while the consumer is still busy with an earlier line. The pipe
     * still holds "last" and "tail" and then end-of-stream. drain must wait for the reader to reach
     * end-of-stream and deliver both. An implementation that closed the pipe first would lose them.
     */
    @Test
    fun `drain delivers trailing lines still in the pipe after the child exited`() {
        val stream = ScriptedStream()
        val delivered = mutableListOf<String>()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val pump =
            pump(stream, childAlive = { false }) { line ->
                if (line == "first") {
                    firstEntered.countDown()
                    releaseFirst.await(waitMillis, TimeUnit.MILLISECONDS)
                }
                delivered += line
            }
        stream.push(Step.Bytes("first\nlast\ntail\n"))
        stream.push(Step.Eof)
        assertTrue("consumer must be busy with the first line", firstEntered.await(waitMillis, TimeUnit.MILLISECONDS))

        // Drain is called while the reader is still mid-stream; it must not close the pipe.
        val result = arrayOfNulls<Throwable>(1)
        val drainer = Thread { result[0] = pump.drain(deadline(waitMillis)) }
        drainer.start()
        releaseFirst.countDown()
        drainer.join(waitMillis)

        assertFalse("drain must return", drainer.isAlive)
        assertNull(result[0])
        assertEquals(listOf("first", "last", "tail"), delivered)
        assertFalse(stream.closed)
    }

    @Test
    fun `drain reports a reader that is still running at the deadline`() {
        val stream = ScriptedStream()
        val pump = pump(stream) { }
        // No EOF is pushed, so the reader blocks in read until the deadline passes.
        val failure = pump.drain(deadline(100))

        assertTrue("expected ReaderStillRunning, got $failure", failure is GuestOutputPump.ReaderStillRunning)
        // Clean up the blocked reader through the normal abort path: a clean close is not a failure.
        assertNull(pump.abort(deadline(waitMillis)))
    }

    // --- abort: deadline on close and join, no success while the reader runs ----------------------

    /** A blocking close must not hang abort past its deadline; the result is a failure, not success. */
    @Test
    fun `abort returns within the deadline when the reader cannot be unblocked`() {
        val stuck =
            object : InputStream() {
                private val never = CountDownLatch(1)

                override fun read(): Int = throw IllegalStateException("not used")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    never.await(waitMillis, TimeUnit.MILLISECONDS)
                    return -1
                }

                // A close that does not unblock the read, as a misbehaving native pipe could be.
                override fun close() = Unit
            }
        val pump = pump(stuck) { }

        val started = System.nanoTime()
        val failure = pump.abort(deadline(300))
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue("reader still running must be a failure: $failure", failure is GuestOutputPump.ReaderStillRunning)
        assertTrue("abort must respect its deadline (${elapsedMillis}ms)", elapsedMillis < 2_000)
    }

    /** A blocked observer also cannot make abort wait past the deadline, and the result is a failure. */
    @Test
    fun `abort returns within the deadline while an observer is blocked`() {
        val stream = ScriptedStream()
        val observerEntered = CountDownLatch(1)
        val observerRelease = CountDownLatch(1)
        val pump =
            pump(stream) {
                observerEntered.countDown()
                observerRelease.await(waitMillis, TimeUnit.MILLISECONDS)
            }
        stream.push(Step.Bytes("x\n"))
        assertTrue("observer must be entered", observerEntered.await(waitMillis, TimeUnit.MILLISECONDS))

        val failure = pump.abort(deadline(300))
        assertTrue("an observer still running at the deadline is a failure: $failure", failure is GuestOutputPump.ReaderStillRunning)
        observerRelease.countDown()
    }

    /** Stop-style abort on a clean pipe: close unblocks the read, and no failure is reported. */
    @Test
    fun `abort on a blocked clean pipe returns null`() {
        val stream = ScriptedStream()
        val pump = pump(stream) { }
        assertNull(pump.abort(deadline(waitMillis)))
        assertTrue(stream.closed)
        assertFalse("the reader ends once its pipe is closed", pump.stillRunning)
    }

    /**
     * abort stops delivery for lines that have not started. The consumer is blocked on "one" when abort
     * runs, so "two" is still unread. Once "one" completes, "two" must not reach the consumer.
     * Only the in-progress line may finish; that is the documented limit.
     */
    @Test
    fun `abort does not deliver a line that had not started when it ran`() {
        val stream = ScriptedStream()
        val delivered = mutableListOf<String>()
        val oneEntered = CountDownLatch(1)
        val releaseOne = CountDownLatch(1)
        val pump =
            pump(stream) { line ->
                if (line == "one") {
                    oneEntered.countDown()
                    releaseOne.await(waitMillis, TimeUnit.MILLISECONDS)
                }
                delivered += line
            }
        stream.push(Step.Bytes("one\ntwo\n"))
        assertTrue(oneEntered.await(waitMillis, TimeUnit.MILLISECONDS))

        // abort gives up after its short deadline because the in-progress observer is still blocked.
        assertTrue(pump.abort(deadline(200)) is GuestOutputPump.ReaderStillRunning)
        releaseOne.countDown()
        assertNull("second abort waits for the reader to finish", pump.abort(deadline(waitMillis)))

        assertEquals("only the in-progress line may complete", listOf("one"), delivered)
    }

    // --- read versus observer classification ------------------------------------------------------

    @Test
    fun `read io error after the caller aborted is absorbed`() {
        val stream = ScriptedStream()
        val pump = pump(stream) { }
        // abort closes the stream, which pushes a closed-pipe failure that the reader must absorb.
        assertNull(pump.abort(deadline(waitMillis)))
    }

    @Test
    fun `read io error after the child exited is absorbed`() {
        val stream = ScriptedStream()
        val pump = pump(stream, childAlive = { false }) { }
        stream.push(Step.Fail(IOException("Stream closed")))

        assertNull(pump.drain(deadline(waitMillis)))
    }

    /** Child alive and nobody asked to close: an interrupted read is a real failure, not a silent success. */
    @Test
    fun `unexpected interrupted read while the child runs is a failure`() {
        val stream = ScriptedStream()
        val pump = pump(stream, childAlive = { true }) { }
        val error = InterruptedIOException("read interrupted")
        stream.push(Step.Fail(error))

        val failure = pump.drain(deadline(waitMillis))
        assertSame("the read error must be the reported failure", error, failure)
        pump.abort(deadline(waitMillis))
    }

    /** An IOException thrown by the observer is never treated as an expected stream close. */
    @Test
    fun `observer io exception is a failure even when the child has exited`() {
        val stream = ScriptedStream()
        val observerError = IOException("disk full while writing the install log")
        val pump = pump(stream, childAlive = { false }) { throw observerError }
        stream.push(Step.Bytes("x\n"))

        val failure = pump.drain(deadline(waitMillis))
        assertSame("observer IOException must surface", observerError, failure)
    }

    @Test
    fun `observer io exception is a failure while the child runs`() {
        val stream = ScriptedStream()
        val observerError = IOException("observer write failed")
        val pump = pump(stream, childAlive = { true }) { throw observerError }
        stream.push(Step.Bytes("x\n"))

        assertSame(observerError, pump.drain(deadline(waitMillis)))
    }

    /**
     * abort runs while the observer is in progress, and the observer throws only after abort has started.
     * Whichever order the threads take, the observer's error must be the result, never a silent null.
     */
    @Test
    fun `observer io exception raised during an abort is returned`() {
        val stream = ScriptedStream()
        val observerError = IOException("observer write failed")
        val observerEntered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val pump =
            pump(stream, childAlive = { true }) {
                observerEntered.countDown()
                gate.await(waitMillis, TimeUnit.MILLISECONDS)
                throw observerError
            }
        stream.push(Step.Bytes("x\n"))
        assertTrue(observerEntered.await(waitMillis, TimeUnit.MILLISECONDS))

        val result = arrayOfNulls<Throwable>(1)
        val aborter = Thread { result[0] = pump.abort(deadline(waitMillis)) }
        aborter.start()
        gate.countDown()
        aborter.join(waitMillis)

        assertFalse("abort must return", aborter.isAlive)
        assertSame("an observer error raised during abort must be returned", observerError, result[0])
    }

    @Test
    fun `runtime error from observer is recorded as the failure`() {
        val stream = ScriptedStream()
        val pump = pump(stream) { error("observer blew up") }
        stream.push(Step.Bytes("x\n"))

        val failure = pump.drain(deadline(waitMillis))
        assertTrue(failure is IllegalStateException)
        assertEquals("observer blew up", failure?.message)
        pump.abort(deadline(waitMillis))
    }

    // --- interruption --------------------------------------------------------------------------------

    /**
     * An interrupt while joining in abort: the join stops, the interrupt flag is restored on the caller,
     * and the caller receives the interruption as a failure instead of a silent success.
     */
    @Test
    fun `interrupt during abort join is restored and returned as the failure`() {
        val stream = ScriptedStream()
        val observerRelease = CountDownLatch(1)
        val pump =
            pump(stream) {
                observerRelease.await(waitMillis, TimeUnit.MILLISECONDS)
            }
        stream.push(Step.Bytes("x\n"))

        var result: Throwable? = null
        var flagAfter = false
        val caller =
            Thread {
                Thread.currentThread().interrupt()
                result = pump.abort(deadline(waitMillis))
                flagAfter = Thread.currentThread().isInterrupted
            }
        caller.start()
        caller.join(waitMillis)

        assertTrue("abort must return", !caller.isAlive)
        assertTrue("interrupt flag must be restored to the caller", flagAfter)
        assertTrue("interrupted abort must not look like success: $result", result != null)
        observerRelease.countDown()
    }

    // --- normal end and absence of late writes ------------------------------------------------------

    @Test
    fun `normal end of output delivers every line and reports no failure`() {
        val stream = ScriptedStream()
        val lines = mutableListOf<String>()
        val pump = pump(stream, childAlive = { false }) { lines += it }
        stream.push(Step.Bytes("a\nb\n"))
        stream.push(Step.Eof)

        assertNull(pump.drain(deadline(waitMillis)))
        assertEquals(listOf("a", "b"), lines)
    }
}
