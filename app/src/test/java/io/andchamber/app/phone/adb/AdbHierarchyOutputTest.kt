package io.andchamber.app.phone.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The hierarchy idle failure is one fixed whole output, not a substring. A page that merely
 * contains the same words must still reach the bounded XML parser, and partial/invalid XML must
 * keep its existing DRIVER behavior.
 */
class AdbHierarchyOutputTest {
    private val message = "ERROR: could not get idle state."

    @Test
    fun fixedIdleMessageIsClassified() {
        assertEquals(
            AdbDeviceFailure.HIERARCHY_NOT_IDLE,
            AdbHierarchyOutput.failure(message.toByteArray()),
        )
        // The service output may carry the usual trailing newline; only whitespace is trimmed.
        assertEquals(
            AdbDeviceFailure.HIERARCHY_NOT_IDLE,
            AdbHierarchyOutput.failure("\n  $message \r\n".toByteArray()),
        )
    }

    @Test
    fun validXmlContainingTheSameTextIsStillAPage() {
        val xml = """
            <hierarchy rotation="0">
              <node class="android.widget.TextView" package="com.example.app"
                    text="$message" bounds="[0,0][1080,200]" />
            </hierarchy>
        """.trimIndent()

        assertNull(AdbHierarchyOutput.failure(xml.toByteArray()))
        val parsed = AdbHierarchyParser.parse(xml.toByteArray())
        assertEquals("com.example.app", parsed.packageName)
        assertEquals(message, parsed.nodes.single().label)
    }

    @Test
    fun emptyDumpIsNotIdleNotADriverFailure() {
        // Observed on a real API 37 device: a dump that overlaps another on-device dump fails
        // with "UiAutomationService already registered" and prints nothing at all.
        assertEquals(AdbDeviceFailure.HIERARCHY_NOT_IDLE, AdbHierarchyOutput.failure(byteArrayOf()))
    }

    @Test
    fun emptyDumpIsReReadWithinTheSameDeadline() {
        var now = 0L
        val deadlines = mutableListOf<Long>()
        val outputs = ArrayDeque(listOf(byteArrayOf(), byteArrayOf(), "<hierarchy/>".toByteArray()))
        val bytes = AdbHierarchyOutput.readRetryingEmpty(
            deadlineMs = 15_000L,
            nowMs = { now },
            sleep = { now += it },
            read = { left ->
                deadlines += left
                now += 500L
                outputs.removeFirst()
            },
        )
        assertEquals("<hierarchy/>", String(bytes))
        assertEquals(3, deadlines.size)
        // Every re-read only gets what is really left of the one call deadline.
        assertEquals(listOf(15_000L, 13_800L, 12_600L), deadlines)
    }

    @Test
    fun emptyDumpRetriesAreBoundedByCountAndDeadline() {
        var now = 0L
        var reads = 0
        val stillEmpty = AdbHierarchyOutput.readRetryingEmpty(
            deadlineMs = 60_000L,
            nowMs = { now },
            sleep = { now += it },
            read = { reads += 1; now += 100L; byteArrayOf() },
        )
        assertEquals(0, stillEmpty.size)
        assertEquals(1 + AdbHierarchyOutput.EMPTY_RETRIES, reads)

        now = 0L
        reads = 0
        AdbHierarchyOutput.readRetryingEmpty(
            deadlineMs = 4_000L,
            nowMs = { now },
            sleep = { now += it },
            read = { reads += 1; now += 1_500L; byteArrayOf() },
        )
        // After one 1.5 s read only 2.5 s is left, below the 0.7 s wait + 2 s minimum read: stop.
        assertEquals(1, reads)
    }

    @Test
    fun nonEmptyOutputIsNeverReRead() {
        var reads = 0
        AdbHierarchyOutput.readRetryingEmpty(15_000L, { 0L }, { }) { reads += 1; message.toByteArray() }
        assertEquals(1, reads)
    }

    @Test
    fun unknownOutputIsNotIdle() {
        assertNull(AdbHierarchyOutput.failure("ERROR: could not get idle state".toByteArray()))
        assertNull(AdbHierarchyOutput.failure("$message extra".toByteArray()))
        assertNull(
            AdbHierarchyOutput.failure("UI hierchary dumped to: /dev/tty".toByteArray()),
        )
    }

    @Test
    fun partialOrInvalidXmlIsNotIdle() {
        val partial = "<hierarchy rotation=\"0\"><node class=\"android.widget.TextView\" " +
            "package=\"com.example.app\" text=\"$message\" bounds=\"[0,0][10,10]\""
        assertNull(AdbHierarchyOutput.failure(partial.toByteArray()))
        assertThrows(AdbHierarchyException::class.java) {
            AdbHierarchyParser.parse(partial.toByteArray())
        }
        assertNull(AdbHierarchyOutput.failure("prefix $message suffix".toByteArray()))
    }
}
