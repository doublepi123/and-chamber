package io.andchamber.app.phone.core

/**
 * One explicit sentinel for "the person chose no time limit".
 *
 * The native settings writer stores this same value for both an unlimited duration and an unlimited
 * deadline, so a restore of wall-clock + elapsed time cannot invent a permanent scope from a wrapped
 * or overflowing number. Nothing in the agent/tool surface may produce this value: it is written
 * only from native configuration after the person confirms the permanent choice.
 *
 * A finite duration must never be turned into [PERMANENT] by arithmetic. [expiresAt] refuses an
 * overflow instead of silently upgrading it.
 */
object PhoneTimeLimits {
    /** The single explicit "no time limit" sentinel; never produced by adding a finite duration. */
    const val PERMANENT: Long = Long.MAX_VALUE

    /** True only for the explicit sentinel, not for an arbitrary huge number. */
    fun isPermanent(value: Long): Boolean = value == PERMANENT

    /**
     * Absolute deadline for one duration.
     *
     * [PERMANENT] maps to [PERMANENT] without arithmetic, so `now + PERMANENT` can never wrap. A
     * finite positive duration is bounded so the sum stays a finite, non-sentinel value; a caller
     * that overflows gets an [IllegalArgumentException] rather than an accidental permanent grant.
     *
     * Range policy for a finite duration (1 s .. 30 min, or the remaining standing window) stays with
     * the caller; this helper only enforces non-negative [now], a positive finite [durationMs] and a
     * representable result.
     */
    fun expiresAt(
        now: Long,
        durationMs: Long,
    ): Long {
        require(now >= 0L) { "now must not be negative" }
        if (isPermanent(durationMs)) return PERMANENT
        require(durationMs > 0L && !isPermanent(durationMs)) { "duration must be positive and finite" }
        // Keep the result representable and distinct from the permanent sentinel.
        require(now < PERMANENT - durationMs) { "deadline overflow" }
        return now + durationMs
    }

    /**
     * A finite deadline is expired at or after [deadlineMs]. A permanent deadline never expires by
     * time; only an explicit native/user stop or lock revokes it.
     */
    fun isExpired(
        now: Long,
        deadlineMs: Long,
    ): Boolean = !isPermanent(deadlineMs) && now >= deadlineMs

    /**
     * Remaining time for status/UI, or null for a permanent deadline (no countdown). Never returns a
     * huge "remaining" number for [PERMANENT]; a finite deadline that passed reports 0.
     */
    fun remainingMs(
        now: Long,
        deadlineMs: Long,
    ): Long? {
        if (isPermanent(deadlineMs)) return null
        return (deadlineMs - now).coerceAtLeast(0L)
    }
}
