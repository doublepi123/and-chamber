// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

/** The only mutations the driver can inject. No generic shell command crosses this boundary. */
sealed interface AdbInputCommand {
    data class Tap(val x: Int, val y: Int) : AdbInputCommand

    data class Swipe(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val durationMs: Int,
    ) : AdbInputCommand

    data class Text(val text: String) : AdbInputCommand

    data object Back : AdbInputCommand
}

/** Why a text value cannot be entered by the first version. */
enum class AdbTextRefusal {
    EMPTY,
    TOO_LONG,
    CONTROL,
    NON_ASCII,
    PERCENT,
}

/**
 * Fixed command schema.
 *
 * Every shell destination is generated from typed values; the agent can never supply a command
 * string. `input text` is limited to printable ASCII because Android's `input` cannot reliably
 * enter other characters; the driver reports that as an unsupported action instead of claiming a
 * Chinese or emoji input succeeded. There is no Enter/IME submit action.
 */
object AdbCommandSchema {
    const val MAX_INPUT_CHARS: Int = 200
    const val MAX_WINDOW_TOKEN_CHARS: Int = 16

    const val HIERARCHY_DESTINATION: String = "shell:uiautomator dump /dev/tty"
    const val WINDOW_STATE_DESTINATION: String = "shell:dumpsys window"
    const val DISPLAY_SIZE_DESTINATION: String = "shell:wm size"
    const val CAPTURE_DESTINATION: String = "exec:screencap -p"

    /**
     * `dumpsys window <token>` prints the full state of one window, including its real `Frames:`
     * line. The full `dumpsys window` list on API 35 omits that frame, so the overlay witness needs
     * this second, fixed read. Only a bounded hexadecimal window token is accepted; there is still
     * no agent-supplied command string.
     */
    fun windowDetailDestination(windowToken: String): String {
        require(isWindowToken(windowToken)) { "invalid window token" }
        return "shell:dumpsys window $windowToken"
    }

    fun isWindowToken(value: String): Boolean =
        value.length in 1..MAX_WINDOW_TOKEN_CHARS && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    fun textRefusal(text: String): AdbTextRefusal? {
        if (text.isEmpty()) return AdbTextRefusal.EMPTY
        if (text.length > MAX_INPUT_CHARS) return AdbTextRefusal.TOO_LONG
        if (text.any { it.isISOControl() }) return AdbTextRefusal.CONTROL
        if (text.any { it.code !in 0x20..0x7E }) return AdbTextRefusal.NON_ASCII
        // `input text` maps %s to a space, so a literal percent would be entered differently.
        if (text.contains('%')) return AdbTextRefusal.PERCENT
        return null
    }

    /** Builds the one destination string for [command]. Arguments are integers or quoted text. */
    fun destination(command: AdbInputCommand): String = when (command) {
        is AdbInputCommand.Tap -> "shell:input tap ${command.x} ${command.y}"
        is AdbInputCommand.Swipe ->
            "shell:input swipe ${command.fromX} ${command.fromY} ${command.toX} ${command.toY} " +
                command.durationMs
        is AdbInputCommand.Text -> {
            require(textRefusal(command.text) == null) { "unsupported text" }
            "shell:input text " + shellQuote(command.text)
        }
        AdbInputCommand.Back -> "shell:input keyevent KEYCODE_BACK"
    }

    /** POSIX single-quote quoting. The fixed schema is the primary defence; this is defence in depth. */
    fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"
}
