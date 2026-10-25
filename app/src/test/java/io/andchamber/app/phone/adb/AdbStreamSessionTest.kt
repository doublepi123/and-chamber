package io.andchamber.app.phone.adb

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the P1 driver bug: LibADB reports both a normal remote CLSE and a dead connection
 * as an IOException on AdbStream.read. The single classifier in [AdbStreamSession] must treat only
 * a close with the connection still established as completion. A partial read followed by a dead
 * link must become OUTCOME_UNKNOWN (afterWrite for inject), never a successful result/Sent.
 *
 * These tests exercise the exact helper called by LibAdbDevice.readService/inject, with a stream
 * that yields bytes and then throws a real IOException. If a future change reintroduces a
 * swallow-all catch that returns success, the connection-loss cases fail.
 */
class AdbStreamSessionTest {
    @Test
    fun normalCloseAfterDataIsCompletion() {
        val link = FakeLink(aliveState = true)
        val stream = FakeStream(listOf("abc".toByteArray()), link = link)
        val bytes = AdbStreamSession.readAll(
            link = link,
            stream = stream,
            maxBytes = 64,
            expired = { false },
            afterWrite = false,
        )
        assertArrayEquals("abc".toByteArray(), bytes)
    }

    @Test
    fun normalCloseWithoutDataCompletesAFixedCommand() {
        val link = FakeLink(aliveState = true)
        val stream = FakeStream(emptyList(), link = link)
        AdbStreamSession.drain(
            link = link,
            stream = stream,
            maxOutputBytes = 64,
            expired = { false },
            afterWrite = true,
        )
    }

    @Test
    fun connectionLossAfterDataIsOutcomeUnknown() {
        val link = FakeLink(aliveState = true)
        val stream = FakeStream(
            chunks = listOf("abc".toByteArray()),
            link = link,
            onEnd = { link.aliveState = false },
        )
        val failure = assertThrows(AdbDeviceException::class.java) {
            AdbStreamSession.readAll(link, stream, 64, { false }, afterWrite = false)
        }
        assertEquals(AdbDeviceFailure.OUTCOME_UNKNOWN, failure.failure)
        assertTrue(!failure.afterWrite)

        val injectStream = FakeStream(
            chunks = listOf("abc".toByteArray()),
            link = link,
            onEnd = { link.aliveState = false },
        )
        val injectFailure = assertThrows(AdbDeviceException::class.java) {
            AdbStreamSession.drain(link, injectStream, 64, { false }, afterWrite = true)
        }
        assertEquals(AdbDeviceFailure.OUTCOME_UNKNOWN, injectFailure.failure)
        assertTrue(injectFailure.afterWrite)
    }

    @Test
    fun connectionLossBeforeAnyDataIsOutcomeUnknown() {
        val link = FakeLink(aliveState = true)
        val stream = FakeStream(
            chunks = emptyList(),
            link = link,
            onEnd = { link.aliveState = false },
        )
        val failure = assertThrows(AdbDeviceException::class.java) {
            AdbStreamSession.drain(link, stream, 64, { false }, afterWrite = true)
        }
        assertEquals(AdbDeviceFailure.OUTCOME_UNKNOWN, failure.failure)
        assertTrue(failure.afterWrite)
    }

    @Test
    fun deadLinkAtNormalEndIsNotSuccess() {
        val link = FakeLink(aliveState = true)
        val stream = FakeStream(
            chunks = listOf("abc".toByteArray()),
            link = link,
            end = IOException("Stream closed."),
            onEnd = { link.aliveState = false },
            throwOnEnd = false,
        )
        val failure = assertThrows(AdbDeviceException::class.java) {
            AdbStreamSession.drain(link, stream, 64, { false }, afterWrite = true)
        }
        assertEquals(AdbDeviceFailure.OUTCOME_UNKNOWN, failure.failure)
        assertTrue(failure.afterWrite)
    }

    @Test
    fun deadLinkAtReadAllEndIsNotSuccess() {
        val link = FakeLink(aliveState = true)
        val stream = FakeStream(
            chunks = listOf("abc".toByteArray()),
            link = link,
            onEnd = { link.aliveState = false },
            throwOnEnd = false,
        )
        val failure = assertThrows(AdbDeviceException::class.java) {
            AdbStreamSession.readAll(link, stream, 64, { false }, afterWrite = false)
        }
        assertEquals(AdbDeviceFailure.OUTCOME_UNKNOWN, failure.failure)
    }

    @Test
    fun watchdogExpiryIsDeadlineNotUnknownOrSuccess() {
        val link = FakeLink(aliveState = true)
        val stream = FakeStream(emptyList(), link = link)
        val failure = assertThrows(AdbDeviceException::class.java) {
            AdbStreamSession.drain(link, stream, 64, { true }, afterWrite = true)
        }
        assertEquals(AdbDeviceFailure.DEADLINE, failure.failure)
        assertTrue(failure.afterWrite)
    }

    @Test
    fun interruptedReadIsCancelled() {
        val link = FakeLink(aliveState = true)
        val stream = FakeStream(
            chunks = emptyList(),
            link = link,
            end = IOException().apply { initCause(InterruptedException()) },
        )
        val failure = assertThrows(AdbDeviceException::class.java) {
            AdbStreamSession.drain(link, stream, 64, { false }, afterWrite = true)
        }
        assertEquals(AdbDeviceFailure.CANCELLED, failure.failure)
        assertTrue(failure.afterWrite)
    }

    @Test
    fun outputOverflowIsBounded() {
        val link = FakeLink(aliveState = true)
        val stream = FakeStream(listOf(ByteArray(100)), link = link)
        val failure = assertThrows(AdbDeviceException::class.java) {
            AdbStreamSession.readAll(link, stream, maxBytes = 10, expired = { false }, afterWrite = false)
        }
        assertEquals(AdbDeviceFailure.LIMIT, failure.failure)

        val injectStream = FakeStream(listOf(ByteArray(10), ByteArray(10)), link = link)
        val injectFailure = assertThrows(AdbDeviceException::class.java) {
            AdbStreamSession.drain(link, injectStream, maxOutputBytes = 10, expired = { false }, afterWrite = true)
        }
        assertEquals(AdbDeviceFailure.LIMIT, injectFailure.failure)
        assertTrue(injectFailure.afterWrite)
    }

    private class FakeLink(var aliveState: Boolean) : AdbIo {
        override val alive: Boolean
            get() = aliveState

        override fun open(destination: String): AdbIoStream =
            error("not used by the classifier tests")
    }

    private class FakeStream(
        private val chunks: List<ByteArray>,
        private val link: FakeLink,
        private val end: IOException = IOException("Stream closed."),
        private val onEnd: () -> Unit = {},
        private val throwOnEnd: Boolean = true,
    ) : AdbIoStream {
        private val remaining = ArrayDeque(chunks)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val chunk = remaining.removeFirstOrNull()
                ?: run {
                    onEnd()
                    if (throwOnEnd) throw end
                    return -1
                }
            val count = minOf(length, chunk.size)
            System.arraycopy(chunk, 0, buffer, offset, count)
            return count
        }

        override fun close() = Unit
    }
}
