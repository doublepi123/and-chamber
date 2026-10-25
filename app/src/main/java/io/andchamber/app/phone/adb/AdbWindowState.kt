// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

import io.andchamber.app.phone.core.PhoneGeometry
import io.andchamber.app.phone.core.PhoneNode
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Unreliable or missing window-manager output. Callers must fail closed. */
class AdbWindowStateException(message: String, cause: Throwable? = null) : Exception(message, cause)

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

    fun parse(text: String): AdbFocusedWindow {
        if (text.isEmpty() || text.length > MAX_BYTES) {
            throw AdbWindowStateException("window state output is missing or too large")
        }
        val focus = CURRENT_FOCUS.find(text)
            ?: throw AdbWindowStateException("mCurrentFocus is unavailable")
        val app = FOCUSED_APP.find(text)
            ?: throw AdbWindowStateException("mFocusedApp is unavailable")

        val focusPackage = focus.groupValues[3]
        val focusActivity = focus.groupValues[4]
        val appPackage = app.groupValues[3]
        val appActivity = app.groupValues[4]
        if (!PACKAGE.matches(focusPackage) || !PACKAGE.matches(appPackage)) {
            throw AdbWindowStateException("window state has an invalid package")
        }
        if (!validActivity(focusActivity) || !validActivity(appActivity)) {
            throw AdbWindowStateException("window state has an invalid activity")
        }
        val task = app.groupValues[5]
        if (task.isBlank() || focus.groupValues[1].isBlank() || app.groupValues[1].isBlank()) {
            throw AdbWindowStateException("window state tokens are missing")
        }
        return AdbFocusedWindow(
            currentFocusToken = focus.groupValues[1],
            currentFocusPackage = focusPackage,
            currentFocusActivity = focusActivity,
            focusedAppToken = app.groupValues[1],
            focusedAppPackage = appPackage,
            focusedAppActivity = appActivity,
            focusedAppTask = task,
        )
    }

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
