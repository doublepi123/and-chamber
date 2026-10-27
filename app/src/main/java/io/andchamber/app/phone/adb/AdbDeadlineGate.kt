// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

/**
 * Minimal, bounded gate between a blocking ADB operation ending and its deadline watchdog firing.
 *
 * Exactly one side owns the outcome, decided against the real monotonic deadline:
 * - a normal end before the deadline claims completion and may interrupt the watchdog;
 * - an end at/after the deadline does not claim completion, so the watchdog still performs the
 *   existing hard close even when its thread is scheduled late;
 * - expiry first is never downgraded to a normal completion.
 *
 * The gate never closes, joins or waits for anything itself. It belongs to one operation's own
 * watchdog and is not a general scheduling primitive.
 */
internal class DeadlineGate(
    private val deadlineAtMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()
    private var finished = false
    private var expiredFlag = false

    val expired: Boolean
        get() = synchronized(lock) { expiredFlag }

    /** True once expiry was claimed or the real monotonic deadline has passed. */
    val deadlinePassed: Boolean
        get() = synchronized(lock) { expiredFlag || nowMs() >= deadlineAtMs }

    /**
     * Claims a normal end. False means the real deadline already passed or expiry was claimed; the
     * gate then stays open so the watchdog can still hard-close. A repeated call after a successful
     * finish stays true, so `cancel` can interrupt an already-finished operation.
     */
    fun finish(): Boolean = synchronized(lock) {
        if (expiredFlag) return@synchronized false
        if (!finished && nowMs() >= deadlineAtMs) return@synchronized false
        finished = true
        true
    }

    /** Claims expiry for the watchdog thread. False means completion won or expiry was claimed. */
    fun expire(): Boolean = synchronized(lock) {
        if (finished || expiredFlag) return@synchronized false
        expiredFlag = true
        true
    }
}
