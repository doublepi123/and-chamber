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
    fun unknownOutputIsNotIdle() {
        assertNull(AdbHierarchyOutput.failure(byteArrayOf()))
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
