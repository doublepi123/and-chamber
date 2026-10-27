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

    /** Bounded re-reads of an empty dump inside the same call deadline. */
    const val EMPTY_RETRIES: Int = 6
    const val EMPTY_RETRY_DELAY_MS: Long = 700L

    /** A re-read is only started when at least this much of the deadline is left for it. */
    const val EMPTY_RETRY_MIN_READ_MS: Long = 2_000L

    /**
     * Non-null only for the exact fixed idle message or a dump that stayed empty. An empty dump is
     * the observed output while another on-device dump still holds UiAutomation: no page was
     * observed, and it is not a connection-loss claim. Every other output keeps the old path.
     */
    fun failure(bytes: ByteArray): AdbDeviceFailure? {
        if (bytes.isEmpty()) return AdbDeviceFailure.HIERARCHY_NOT_IDLE
        val text = String(bytes, Charsets.UTF_8).trim()
        return if (text == NOT_IDLE_MESSAGE) AdbDeviceFailure.HIERARCHY_NOT_IDLE else null
    }

    /**
     * Reads once, then re-reads an empty result at most [EMPTY_RETRIES] times, each only while
     * enough of [deadlineMs] remains. Every read gets the real remaining time. Non-empty output,
     * including the fixed idle sentence, is returned as is; an exception from [read] propagates.
     */
    fun readRetryingEmpty(
        deadlineMs: Long,
        nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
        sleep: (Long) -> Unit = ::sleepInterruptibly,
        read: (Long) -> ByteArray,
    ): ByteArray {
        val started = nowMs()
        fun remaining(): Long = deadlineMs - (nowMs() - started)
        var bytes = read(deadlineMs)
        var retries = 0
        while (bytes.isEmpty() && retries < EMPTY_RETRIES) {
            if (remaining() <= EMPTY_RETRY_DELAY_MS + EMPTY_RETRY_MIN_READ_MS) break
            sleep(EMPTY_RETRY_DELAY_MS)
            retries += 1
            val left = remaining()
            if (left <= 0L) break
            bytes = read(left)
        }
        return bytes
    }

    private fun sleepInterruptibly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AdbDeviceException(AdbDeviceFailure.CANCELLED, cause = failure)
        }
    }
}
