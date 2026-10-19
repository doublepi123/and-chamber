package io.andchamber.app.runtime.install

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Reads one guest command's combined output on a daemon thread and hands each line to [onLine].
 *
 * Failure classification (the reader thread itself never dies with an uncaught throwable):
 *  - An [IOException] raised by the READ is expected only when the pipe was closed on purpose by
 *    [abort], or the child had already exited when the read failed. Otherwise it is a failure.
 *  - Any throwable raised by [onLine] is a separate observer failure. It is never treated as an
 *    expected stream close, even when it is an IOException.
 *  - Recording a failure never throws.
 *
 * Two entry points. Each waits at most until the deadline the caller passes in:
 *  - [drain]: the child exited normally. Waits for end-of-stream, so trailing lines are delivered.
 *    If end-of-stream does not arrive before the deadline, the caller must [abort].
 *  - [abort]: timeout, interrupt or error path. Sets [closing] (no new line starts delivery after
 *    this point), closes the stream on a helper thread to unblock the reader, and joins both until
 *    the deadline.
 *
 * Limits, stated precisely (not a guarantee of more):
 *  - [closing] stops only deliveries that have not started. An [onLine] call already running cannot be
 *    cancelled, and it may still complete after [abort] returns.
 *  - A reader still running at the deadline is reported as [ReaderStillRunning]. The reader is
 *    abandoned as a daemon thread, not waited on, so it may remain alive for a while.
 *  - A close that blocks past the deadline is abandoned on its helper thread (daemon).
 *  - Lines collected through [onLine] may be incomplete when a failure is returned. Consumers must
 *    read them through a thread-safe snapshot and must not assume completeness after a failure.
 *  - An interrupt during [drain] or [abort] is restored on the calling thread and returned as the
 *    failure. It does not shorten the deadline or make the wait unbounded.
 */
internal class GuestOutputPump(
    private val input: InputStream,
    private val childAlive: () -> Boolean,
    private val onLine: (String) -> Unit,
    threadName: String = "openchamber-install-output",
    /** Source of deadlines. drain, abort and the teardown helper all read the same clock. */
    private val clock: () -> Long = System::nanoTime,
) {
    /** Set by [abort]; no line that has not started delivery is delivered after this. */
    @Volatile private var closing = false

    /** Read or observer failure, first one wins. Written only by the reader thread. */
    @Volatile private var failure: Throwable? = null

    private val thread =
        Thread({
            try {
                readLoop()
            } catch (error: Throwable) {
                // Defensive: readLoop already classifies everything, this only keeps the thread from dying.
                recordFailure(error)
            }
        }, threadName).apply { isDaemon = true }

    private fun readLoop() {
        val reader = input.bufferedReader()
        while (true) {
            val line: String?
            try {
                line = reader.readLine()
            } catch (error: IOException) {
                // A read error is expected only when we closed the pipe or the child is already gone.
                if (!closing && childAlive()) recordFailure(error)
                return
            }
            if (line == null) return // end of stream
            if (closing) return
            try {
                onLine(line)
            } catch (error: Throwable) {
                // Observer errors are never an expected close, whatever their type.
                recordFailure(error)
                return
            }
        }
    }

    private fun recordFailure(error: Throwable) {
        if (failure == null) failure = error
    }

    fun start() {
        thread.start()
    }

    /**
     * Waits for end-of-stream until [deadlineNanos]. Returns null when the stream ended cleanly and no
     * failure was recorded. Returns [ReaderStillRunning] when the deadline passed first, in which case
     * the caller must still call [abort]. Returns the recorded failure otherwise.
     */
    fun drain(deadlineNanos: Long): Throwable? {
        val remaining = deadlineNanos - clock()
        var interrupted: InterruptedException? = null
        if (thread.isAlive && remaining > 0) {
            try {
                thread.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L))
            } catch (error: InterruptedException) {
                // Restore the flag and report it; the caller aborts the pump on any non-null result.
                interrupted = error
                Thread.currentThread().interrupt()
            }
        }
        failure?.let { return it }
        interrupted?.let { return it }
        return if (thread.isAlive) ReaderStillRunning() else null
    }

    /**
     * Stops delivery, closes the stream and joins until [deadlineNanos]. Returns null only when the
     * reader ended and recorded no failure. Otherwise returns the failure, or [ReaderStillRunning].
     * The close and the join share one deadline. An interrupt during the join is re-asserted on
     * the calling thread and returned as the failure, not swallowed.
     */
    fun abort(deadlineNanos: Long): Throwable? {
        closing = true
        // close() may itself block on some stream implementations, so it runs on a helper thread and
        // only the caller's wait is bounded. A close that never returns leaves that helper behind (daemon).
        val closer = Thread({ runCatching { input.close() } }, "openchamber-output-close").apply { isDaemon = true }
        var interrupted: InterruptedException? = null
        try {
            closer.start()
            awaitBounded(closer, deadlineNanos)
            awaitBounded(thread, deadlineNanos)
        } catch (error: InterruptedException) {
            interrupted = error
            Thread.currentThread().interrupt()
        }
        failure?.let { return it }
        if (thread.isAlive) return interrupted ?: ReaderStillRunning()
        return interrupted
    }

    /** Joins [target] until [deadlineNanos]. An interrupt propagates to the caller. */
    private fun awaitBounded(
        target: Thread,
        deadlineNanos: Long,
    ) {
        val remaining = deadlineNanos - clock()
        if (target.isAlive && remaining > 0) {
            target.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L))
        }
    }

    /**
     * Waits for the command through [awaitCommand], then tears the pump down. The teardown deadline is
     * created only after [awaitCommand] returns, so a long command never spends the teardown budget.
     * [drain] and any later [abort] share that one deadline; the catch path creates its own deadline
     * when it enters abort. Returns normally on a clean drain. Throws the primary error on abort, with
     * teardown errors as suppressed. Interrupt status is restored before the error is thrown.
     */
    fun awaitAndTearDown(
        awaitCommand: () -> Boolean,
        teardownBudgetNanos: Long,
        timeoutFailure: () -> Throwable,
        readerFailure: (Throwable) -> Throwable,
    ) {
        val exited =
            try {
                awaitCommand()
            } catch (primary: Throwable) {
                if (primary is InterruptedException) Thread.currentThread().interrupt()
                suppressAbortFailure(primary, abort(clock() + teardownBudgetNanos))
                throw primary
            }
        if (!exited) {
            val primary = timeoutFailure()
            suppressAbortFailure(primary, abort(clock() + teardownBudgetNanos))
            throw primary
        }
        // Normal exit: the deadline starts now, after the command has finished.
        val deadline = clock() + teardownBudgetNanos
        val drainFailure = drain(deadline) ?: return
        val primary = readerFailure(drainFailure)
        // abort may hand back the same reader error that drain already reported as the cause.
        suppressAbortFailure(primary, abort(deadline)?.takeUnless { it === drainFailure })
        throw primary
    }

    /** Adds a teardown error as suppressed, skipping the primary itself (self-suppression is illegal). */
    private fun suppressAbortFailure(
        primary: Throwable,
        teardown: Throwable?,
    ) {
        if (teardown != null && teardown !== primary) primary.addSuppressed(teardown)
    }

    /** Whether the reader is still running. Diagnostic only; a live reader is never treated as finished. */
    val stillRunning: Boolean get() = thread.isAlive

    /** The reader did not end before the deadline. Its lines are not guaranteed complete. */
    class ReaderStillRunning : IllegalStateException("Output reader did not finish before its deadline")
}
