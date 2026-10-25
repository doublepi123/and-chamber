// SPDX-License-Identifier: Apache-2.0
//
// App-local reader for LibADB Android 3.1.1 AdbStream.
//
// Upstream source (Apache-2.0 option; see third_party/phone-adb/README.md):
// https://github.com/MuntashirAkon/libadb-android/blob/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb/AdbStream.java
//
// Upstream defect in 3.1.1: notifyClose(true) with a non-empty mReadQueue only sets
// mPendingClose=true and leaves mIsClosed=false. read() consumes the queued data and returns; the
// next read() sees an empty queue, waits while (!mIsClosed), ignores mPendingClose, and never reaches
// its final pendingClose check. A short response that queues payload then sends CLSE therefore blocks
// forever. This adapter performs the same single-reader buffer/queue loop under the same mReadQueue
// monitor, checks pending close in the wait predicate, drains buffered/queued data before EOF, and
// does not call the broken wait path. The field layout is pinned to 3.1.1 and any mismatch fails
// closed. No protocol or cryptographic code is implemented here.

package io.github.muntashirakon.adb

import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.Queue

/** The pinned LibADB 3.1.1 stream layout is unavailable or the reader cannot safely continue. */
class AndChamberAdbStreamReaderException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Single-reader replacement for `AdbStream.read` that closes the pending-close hang.
 *
 * Preconditions match the driver: one stream, one reader. The SDK's addPayload/notifyClose both
 * synchronize and notify on `mReadQueue`; this loop waits on that same monitor and rechecks all
 * fields after every wake. Data is copied from the SDK buffer or polled from the queue before any EOF
 * decision, so the last queued packet is never lost.
 */
class AndChamberAdbStreamReader(private val stream: AdbStream) {
    private val fields: Fields = LAYOUT

    init {
        // Fail during LibAdbStream construction rather than on a later read.
        fields
    }

    fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw AndChamberAdbStreamReaderException("invalid read bounds")
        }
        if (length == 0) return 0
        return try {
            val queue = fields.readQueue.get(stream) as? Queue<*>
                ?: throw AndChamberAdbStreamReaderException("read queue is missing")
            @Suppress("UNCHECKED_CAST")
            val payloads = queue as Queue<ByteArray>
            synchronized(payloads) {
                while (true) {
                    val buffer = fields.readBuffer.get(stream) as? ByteBuffer
                        ?: throw AndChamberAdbStreamReaderException("read buffer is missing")
                    if (buffer.hasRemaining()) {
                        return copyBuffer(buffer, bytes, offset, length)
                    }
                    val data = payloads.poll()
                    if (data != null) {
                        if (data.isEmpty()) continue
                        buffer.clear()
                        buffer.put(data)
                        buffer.flip()
                        if (buffer.hasRemaining()) {
                            return copyBuffer(buffer, bytes, offset, length)
                        }
                        continue
                    }
                    val closed = fields.isClosed.getBoolean(stream)
                    val pendingClose = fields.pendingClose.getBoolean(stream)
                    if (closed || pendingClose) {
                        if (pendingClose && !closed) {
                            // Keep the SDK state consistent for a later close/read attempt.
                            runCatching { fields.isClosed.setBoolean(stream, true) }
                        }
                        return -1
                    }
                    awaitOnQueue(payloads)
                }
                @Suppress("UNREACHABLE_CODE")
                -1
            }
        } catch (failure: AndChamberAdbStreamReaderException) {
            throw failure
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw AndChamberAdbStreamReaderException("ADB stream read failed", failure)
        }
    }

    private fun copyBuffer(
        buffer: ByteBuffer,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = minOf(length, buffer.remaining())
        buffer.get(bytes, offset, count)
        return count
    }

    private fun awaitOnQueue(queue: Queue<ByteArray>) {
        try {
            WAIT.invoke(queue)
        } catch (failure: InvocationTargetException) {
            val cause = failure.targetException
            if (cause is InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("ADB stream read interrupted", cause)
            }
            throw AndChamberAdbStreamReaderException("ADB stream wait failed", cause)
        } catch (failure: Throwable) {
            throw AndChamberAdbStreamReaderException("ADB stream wait failed", failure)
        }
    }

    private class Fields(
        val readQueue: Field,
        val readBuffer: Field,
        val isClosed: Field,
        val pendingClose: Field,
    )

    private companion object {
        val WAIT = java.lang.Object::class.java.getMethod("wait")

        val LAYOUT: Fields by lazy {
            try {
                val queue = AdbStream::class.java.getDeclaredField("mReadQueue")
                    .apply { isAccessible = true }
                val buffer = AdbStream::class.java.getDeclaredField("mReadBuffer")
                    .apply { isAccessible = true }
                val closed = AdbStream::class.java.getDeclaredField("mIsClosed")
                    .apply { isAccessible = true }
                val pending = AdbStream::class.java.getDeclaredField("mPendingClose")
                    .apply { isAccessible = true }
                if (!Queue::class.java.isAssignableFrom(queue.type) ||
                    !ByteBuffer::class.java.isAssignableFrom(buffer.type) ||
                    closed.type != java.lang.Boolean.TYPE ||
                    pending.type != java.lang.Boolean.TYPE
                ) {
                    throw AndChamberAdbStreamReaderException("ADB 3.1.1 stream field types mismatch")
                }
                Fields(queue, buffer, closed, pending)
            } catch (failure: AndChamberAdbStreamReaderException) {
                throw failure
            } catch (failure: Throwable) {
                throw AndChamberAdbStreamReaderException(
                    "ADB 3.1.1 stream layout mismatch",
                    failure,
                )
            }
        }
    }
}
