package io.andchamber.app.runtime

/**
 * Decides whether an unexpectedly dead server gets another automatic restart, and how long to wait
 * first. Pure so the policy is unit-tested without coroutines or processes.
 */
class RestartPolicy(
    private val backoffMillis: List<Long> = listOf(2_000L, 5_000L, 15_000L, 30_000L),
) {
    init {
        require(backoffMillis.isNotEmpty()) { "At least one backoff step is required" }
    }

    private var attempts = 0

    /** The delay before the next restart, or null when the policy has given up. */
    fun nextDelayMillis(): Long? {
        if (attempts >= backoffMillis.size) return null
        return backoffMillis[attempts++]
    }

    /** A restart came up and stayed healthy: the next crash starts a fresh backoff. */
    fun reset() {
        attempts = 0
    }
}
