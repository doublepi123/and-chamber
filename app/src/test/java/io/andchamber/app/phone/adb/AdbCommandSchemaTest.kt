package io.andchamber.app.phone.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbCommandSchemaTest {
    @Test
    fun tapAndSwipeAndBackUseTheFixedSchema() {
        assertEquals(
            "shell:input tap 120 340",
            AdbCommandSchema.destination(AdbInputCommand.Tap(120, 340)),
        )
        assertEquals(
            "shell:input swipe 10 20 30 40 250",
            AdbCommandSchema.destination(AdbInputCommand.Swipe(10, 20, 30, 40, 250)),
        )
        assertEquals(
            "shell:input keyevent KEYCODE_BACK",
            AdbCommandSchema.destination(AdbInputCommand.Back),
        )
    }

    @Test
    fun textIsQuotedAndSpacesSurvive() {
        assertEquals(
            "shell:input text 'hello world'",
            AdbCommandSchema.destination(AdbInputCommand.Text("hello world")),
        )
    }

    @Test
    fun singleQuoteCannotBreakOutOfTheArgument() {
        val destination = AdbCommandSchema.destination(
            AdbInputCommand.Text("a'; echo no"),
        )
        assertEquals("shell:input text 'a'\\''; echo no'", destination)
    }

    @Test
    fun textLimitsAreExplicit() {
        assertEquals(AdbTextRefusal.EMPTY, AdbCommandSchema.textRefusal(""))
        assertEquals(AdbTextRefusal.CONTROL, AdbCommandSchema.textRefusal("a\nb"))
        assertEquals(AdbTextRefusal.NON_ASCII, AdbCommandSchema.textRefusal("中文"))
        assertEquals(AdbTextRefusal.PERCENT, AdbCommandSchema.textRefusal("100%"))
        assertEquals(
            AdbTextRefusal.TOO_LONG,
            AdbCommandSchema.textRefusal("a".repeat(AdbCommandSchema.MAX_INPUT_CHARS + 1)),
        )
        assertNull(AdbCommandSchema.textRefusal("Plain ASCII 123 !@#"))
        assertNotNull(AdbCommandSchema.textRefusal("😀"))
    }

    @Test
    fun unsupportedTextCannotReachTheDestinationBuilder() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbCommandSchema.destination(AdbInputCommand.Text("中文"))
        }
    }

    @Test
    fun destinationStringsAreNeverArbitraryShell() {
        val commands = listOf(
            AdbInputCommand.Tap(0, 0),
            AdbInputCommand.Swipe(1, 2, 3, 4, 50),
            AdbInputCommand.Back,
            AdbInputCommand.Text("ok"),
        )
        commands.map(AdbCommandSchema::destination).forEach {
            assert(it.startsWith("shell:input ")) { it }
        }
    }
}
