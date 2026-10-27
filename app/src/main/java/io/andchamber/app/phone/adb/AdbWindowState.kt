// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

import io.andchamber.app.phone.core.PhoneGeometry
import io.andchamber.app.phone.core.PhoneNode
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Why a window-manager read could not produce the focused window. Callers must fail closed. */
enum class AdbWindowStateFailure {
    /**
     * A complete official field line is explicitly null during a normal window transition (for
     * example `mCurrentFocus=null` while `mFocusedApp` is still present). No window evidence exists
     * to use; the caller may retry only inside its existing bounded startup budget.
     */
    NOT_READY,
    /** Missing, truncated, oversized, malformed or otherwise unreliable output. */
    INVALID,
}

/** Unreliable or missing window-manager output. Callers must fail closed. */
class AdbWindowStateException(
    message: String,
    cause: Throwable? = null,
    val failure: AdbWindowStateFailure = AdbWindowStateFailure.INVALID,
) : Exception(message, cause)

/**
 * The two real window-manager identities the driver can verify from a fixed, read-only command:
 * the current input-focus window token and the focused app activity token.
 *
 * This is not a cross-process atomic snapshot. It is read before and after an observation and
 * before/after a capture, so a same-package new activity or dialog cannot silently replace the
 * observed window.
 */
data class AdbFocusedWindow(
    val currentFocusToken: String,
    val currentFocusPackage: String,
    val currentFocusActivity: String,
    val focusedAppToken: String,
    val focusedAppPackage: String,
    val focusedAppActivity: String,
    val focusedAppTask: String,
)

object AdbWindowStateParser {
    /** `dumpsys window` is about 55 KiB on API 35; the cap is deliberately generous but bounded. */
    const val MAX_BYTES: Int = 1024 * 1024

    private val CURRENT_FOCUS = Regex(
        """mCurrentFocus=Window\{([0-9a-fA-F]+)\s+u(\d+)\s+([^\s/}]+)/([^\s}]+)\}""",
    )
    private val FOCUSED_APP = Regex(
        """mFocusedApp=ActivityRecord\{([0-9a-fA-F]+)\s+u(\d+)\s+([^\s/}]+)/([^\s}]+)\s+t(\d+)\}""",
    )
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private const val CURRENT_FOCUS_FIELD = "mCurrentFocus"
    private const val FOCUSED_APP_FIELD = "mFocusedApp"

    fun parse(text: String): AdbFocusedWindow {
        if (text.isEmpty() || text.length > MAX_BYTES) {
            throw AdbWindowStateException("window state output is missing or too large")
        }
        val focus = CURRENT_FOCUS.find(text)
        val app = FOCUSED_APP.find(text)

        // A present match is still validated first: malformed data is INVALID even when the other
        // official field is explicitly null.
        focus?.let(::validateFocus)
        app?.let(::validateApp)

        if (focus == null && !hasExactNullFieldLine(text, CURRENT_FOCUS_FIELD)) {
            throw AdbWindowStateException("mCurrentFocus is unavailable")
        }
        if (app == null && !hasExactNullFieldLine(text, FOCUSED_APP_FIELD)) {
            throw AdbWindowStateException("mFocusedApp is unavailable")
        }
        if (focus == null || app == null) {
            // At least one official field is a complete `=null` line and has no window match. This
            // is the decisive transient-transition signature; it never yields a partial window.
            throw AdbWindowStateException(
                "window focus is not ready",
                failure = AdbWindowStateFailure.NOT_READY,
            )
        }

        return AdbFocusedWindow(
            currentFocusToken = focus.groupValues[1],
            currentFocusPackage = focus.groupValues[3],
            currentFocusActivity = focus.groupValues[4],
            focusedAppToken = app.groupValues[1],
            focusedAppPackage = app.groupValues[3],
            focusedAppActivity = app.groupValues[4],
            focusedAppTask = app.groupValues[5],
        )
    }

    private fun validateFocus(match: MatchResult) {
        if (!PACKAGE.matches(match.groupValues[3])) {
            throw AdbWindowStateException("window state has an invalid package")
        }
        if (!validActivity(match.groupValues[4])) {
            throw AdbWindowStateException("window state has an invalid activity")
        }
        if (match.groupValues[1].isBlank()) {
            throw AdbWindowStateException("window state tokens are missing")
        }
    }

    private fun validateApp(match: MatchResult) {
        if (!PACKAGE.matches(match.groupValues[3])) {
            throw AdbWindowStateException("window state has an invalid package")
        }
        if (!validActivity(match.groupValues[4])) {
            throw AdbWindowStateException("window state has an invalid activity")
        }
        if (match.groupValues[1].isBlank() || match.groupValues[5].isBlank()) {
            throw AdbWindowStateException("window state tokens are missing")
        }
    }

    /**
     * True only when one complete, trimmed output line is exactly the official null field. A
     * window title, a longer line or a suffix that merely contains the words is not this signal.
     */
    private fun hasExactNullFieldLine(text: String, field: String): Boolean =
        text.lineSequence().any { it.trim() == "$field=null" }

    private fun validActivity(value: String): Boolean =
        value.isNotBlank() && value.length <= 200 && value.none { it.isISOControl() }
}

/**
 * The opaque value stored in [io.andchamber.app.phone.core.PhoneSnapshot.windowId].
 * It contains both the real window identity and the observed content fingerprint; no raw dumpsys,
 * label or activity text is exposed.
 */
data class AdbWindowWitness(
    val windowDigest: String,
    val contentDigest: String,
) {
    fun encode(): String = PREFIX + windowDigest + "." + contentDigest

    companion object {
        private const val PREFIX = "w1."
        private val FORMAT = Regex("^w1\\.([0-9a-f]{40})\\.([0-9a-f]{40})$")

        fun parse(value: String): AdbWindowWitness? {
            if (value.length > 128 || value.any { it.isISOControl() }) return null
            val match = FORMAT.matchEntire(value) ?: return null
            return AdbWindowWitness(match.groupValues[1], match.groupValues[2])
        }
    }
}

object AdbWindowIdentity {
    /**
     * The window digest covers the real focus tokens/activities plus package, rotation and display.
     * The content digest covers the bounded nodes (refs, roles, redacted labels, bounds and flags).
     * A same-package new activity, dialog, moved element or changed label changes at least one.
     */
    fun witness(
        focus: AdbFocusedWindow,
        packageName: String,
        geometry: PhoneGeometry,
        nodes: List<PhoneNode>,
    ): AdbWindowWitness = AdbWindowWitness(
        windowDigest = hash(
            sequenceOf(
                "window-v1",
                focus.currentFocusToken,
                focus.currentFocusPackage,
                focus.currentFocusActivity,
                focus.focusedAppToken,
                focus.focusedAppPackage,
                focus.focusedAppActivity,
                focus.focusedAppTask,
                packageName,
                geometry.rotation.toString(),
                geometry.displayWidth.toString(),
                geometry.displayHeight.toString(),
            ),
        ),
        contentDigest = hash(
            sequenceOf("content-v1") + nodes.asSequence().flatMap { node ->
                sequenceOf(
                    node.ref,
                    node.role,
                    node.label,
                    node.bounds.left.toString(),
                    node.bounds.top.toString(),
                    node.bounds.right.toString(),
                    node.bounds.bottom.toString(),
                    node.clickable.toString(),
                    node.editable.toString(),
                    node.focused.toString(),
                )
            },
        ),
    )

    private fun hash(parts: Sequence<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val length = ByteBuffer.allocate(4)
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            length.clear()
            length.putInt(bytes.size)
            digest.update(length.array())
            digest.update(bytes)
        }
        return digest.digest().take(HASH_BYTES)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private const val HASH_BYTES = 20
}
