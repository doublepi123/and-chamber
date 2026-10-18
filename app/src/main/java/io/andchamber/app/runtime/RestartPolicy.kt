package io.andchamber.app.runtime

/**
 * Crash-restart budget. Each automatic restart consumes one backoff step; the budget is only
 * refilled after the server has stayed healthy for [healthyResetMillis] (so a crash loop that
 * restarts every few seconds never refills it). Manual Start calls [reset] directly.
 *
 * Pure: the caller supplies the clock, so the policy is unit-tested without coroutines.
 */
class RestartPolicy(
    private val backoffMillis: List<Long> = listOf(2_000L, 5_000L, 15_000L, 30_000L),
    private val healthyResetMillis: Long = HEALTHY_RESET_MILLIS,
) {
    init {
        require(backoffMillis.isNotEmpty()) { "At least one backoff step is required" }
        require(healthyResetMillis > 0) { "healthyResetMillis must be positive" }
    }

    private var attempts = 0
    private var healthySinceMillis: Long? = null

    /** Delay before the next restart, or null when the budget is spent. */
    fun nextDelayMillis(): Long? {
        healthySinceMillis = null
        if (attempts >= backoffMillis.size) return null
        return backoffMillis[attempts++]
    }

    /**
     * Called on every healthy observation. The budget refills only once health has held for the
     * full window; an unhealthy observation in between restarts the window.
     */
    fun onHealthy(nowMillis: Long) {
        val since = healthySinceMillis ?: nowMillis.also { healthySinceMillis = it }
        if (nowMillis - since >= healthyResetMillis) attempts = 0
    }

    /** Manual start or a clean stop: start from a full budget. */
    fun reset() {
        attempts = 0
        healthySinceMillis = null
    }

    companion object {
        const val HEALTHY_RESET_MILLIS = 120_000L
    }
}
