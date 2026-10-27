// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

/**
 * The only accepted idle-failure signature of `uiautomator dump /dev/tty`.
 *
 * API 35 AOSP also exits 0 for this failure and prints the fixed sentence below. The service read
 * has no separate exit status, so this classifier accepts nothing else: it is a whole-output
 * equality after trimming, never a substring search. A page label, a node text or any surrounding
 * (partial/invalid) XML that merely contains those words is not this failure and remains an
 * ordinary parser/[AdbDeviceFailure.DRIVER] case.
 */
internal object AdbHierarchyOutput {
    const val NOT_IDLE_MESSAGE: String = "ERROR: could not get idle state."

    /** Non-null only for the exact fixed idle message; every other output keeps the old path. */
    fun failure(bytes: ByteArray): AdbDeviceFailure? {
        if (bytes.isEmpty()) return null
        val text = String(bytes, Charsets.UTF_8).trim()
        return if (text == NOT_IDLE_MESSAGE) AdbDeviceFailure.HIERARCHY_NOT_IDLE else null
    }
}
