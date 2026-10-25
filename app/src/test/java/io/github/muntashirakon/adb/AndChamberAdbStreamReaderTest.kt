package io.github.muntashirakon.adb

import io.andchamber.app.phone.adb.AdbDeviceException
import io.andchamber.app.phone.adb.AdbDeviceFailure
import io.andchamber.app.phone.adb.AdbIo
import io.andchamber.app.phone.adb.AdbIoStream
import io.andchamber.app.phone.adb.AdbStreamSession
import java.io.ByteArrayOutputStream
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real LibADB 3.1.1 AdbStream regression, not a fake reader.
 *
 * The stream is instantiated without its constructor through the test-only Unsafe path and its four
 * reader fields are populated with the exact 3.1.1 layout. addPayload/notifyClose are the real
 * package-private SDK methods. The first test proves the shipped AdbStream.read() hangs after a
 * queued payload + CLSE; the rest prove the app-local reader drains the payload, segments large
 * payloads, returns EOF for empty CLSE, wakes on a delayed CLSE, and still lets the session classify
 * a dead link as OUTCOME_UNKNOWN.
 */
class AndChamberAdbStreamReaderTest {
    @Test
    fun shippedReadHangsAfterQueuedPayloadThenClse() {
        val stream = newStream()
        stream.addPayload("abc".toByteArray())
        stream.notifyClose(true)

        // The first SDK read consumes the queued payload and returns it.
        val first = ByteArray(16)
        assertEquals(3, stream.read(first, 0, first.size))

        val executor = daemonExecutor()
        try {
            val second = executor.submit<Int> { stream.read(ByteArray(16), 0, 16) }
            assertThrows(TimeoutException::class.java) {
                second.get(300, TimeUnit.MILLISECONDS)
            }
            second.cancel(true)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun queuedPayloadThenClseDrainsDataAndEofsWithoutHanging() {
        val stream = newStream()
        val reader = AndChamberAdbStreamReader(stream)
        stream.addPayload("abc".toByteArray())
        stream.notifyClose(true)

        assertArrayEquals("abc".toByteArray(), readFully(reader, chunk = 16))
    }

    @Test
    fun segmentedLargePayloadIsNotTruncated() {
        val stream = newStream(bufferCapacity = 1024)
        val reader = AndChamberAdbStreamReader(stream)
        val payload = ByteArray(300) { (it % 97).toByte() }
        stream.addPayload(payload)
        stream.notifyClose(true)

        assertArrayEquals(payload, readFully(reader, chunk = 7))
    }

    @Test
    fun emptyCloseIsNormalEof() {
        val stream = newStream()
        val reader = AndChamberAdbStreamReader(stream)
        stream.notifyClose(true)

        assertTrue(readFully(reader, chunk = 16).isEmpty())
    }

    @Test
    fun delayedClseWakesTheWaitingReader() {
        val stream = newStream()
        val reader = AndChamberAdbStreamReader(stream)
        val executor = daemonExecutor()
        try {
            val future = executor.submit<ByteArray> { readFully(reader, chunk = 8) }
            Thread.sleep(150L)
            stream.addPayload("xy".toByteArray())
            stream.notifyClose(true)
            assertArrayEquals("xy".toByteArray(), future.get(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun realReaderOnDeadLinkIsOutcomeUnknownForInjection() {
        val stream = newStream()
        val reader = AndChamberAdbStreamReader(stream)
        val link = object : AdbIo {
            override val alive: Boolean = false

            override fun open(destination: String): AdbIoStream =
                error("not used by the session")
        }
        val ioStream = object : AdbIoStream {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                reader.read(buffer, offset, length)

            override fun close() = Unit
        }
        stream.addPayload("abc".toByteArray())
        stream.notifyClose(true)

        val failure = assertThrows(AdbDeviceException::class.java) {
            AdbStreamSession.drain(link, ioStream, 64, { false }, afterWrite = true)
        }
        assertEquals(AdbDeviceFailure.OUTCOME_UNKNOWN, failure.failure)
        assertTrue(failure.afterWrite)
    }

    private fun readFully(reader: AndChamberAdbStreamReader, chunk: Int): ByteArray {
        val executor = daemonExecutor()
        return try {
            executor.submit<ByteArray> {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(chunk)
                while (true) {
                    val read = reader.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }.get(2, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    private fun newStream(bufferCapacity: Int = 1024): AdbStream {
        val stream = ALLOCATE.invoke(UNSAFE, AdbStream::class.java) as AdbStream
        putObject(stream, "mReadQueue", ConcurrentLinkedQueue<ByteArray>())
        putObject(stream, "mReadBuffer", ByteBuffer.allocate(bufferCapacity).also { it.flip() })
        putBoolean(stream, "mIsClosed", false)
        putBoolean(stream, "mPendingClose", false)
        return stream
    }

    private fun putObject(target: Any, name: String, value: Any) {
        val field = field(name)
        PUT_OBJECT.invoke(UNSAFE, target, OBJECT_FIELD_OFFSET.invoke(UNSAFE, field), value)
    }

    private fun putBoolean(target: Any, name: String, value: Boolean) {
        val field = field(name)
        PUT_BOOLEAN.invoke(UNSAFE, target, OBJECT_FIELD_OFFSET.invoke(UNSAFE, field), value)
    }

    private fun field(name: String): Field {
        val field = AdbStream::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field
    }

    private fun daemonExecutor() = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adb-stream-test").apply { isDaemon = true }
    }

    private companion object {
        val UNSAFE: Any = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)

        val ALLOCATE = UNSAFE.javaClass.getMethod("allocateInstance", Class::class.java)
        val OBJECT_FIELD_OFFSET = UNSAFE.javaClass.getMethod("objectFieldOffset", Field::class.java)
        val PUT_OBJECT = UNSAFE.javaClass.getMethod(
            "putObject",
            Any::class.java,
            java.lang.Long.TYPE,
            Any::class.java,
        )
        val PUT_BOOLEAN = UNSAFE.javaClass.getMethod(
            "putBoolean",
            Any::class.java,
            java.lang.Long.TYPE,
            java.lang.Boolean.TYPE,
        )
    }
}
