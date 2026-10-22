package io.andchamber.app.browser

/**
 * Per-stage wall clock for the true-local entry. The install and start budgets each start when
 * their stage is first entered; later state updates (Installing steps, Starting detail changes)
 * never extend either budget. The overall budget starts once for the whole ensureRunning loop.
 *
 * Pure Kotlin so the deterministic fake-clock tests can run as plain JUnit; the device test passes
 * Android's elapsedRealtime as the clock.
 */
internal class StageBudget(
    private val clock: () -> Long,
    private val installBudgetMs: Long = 20 * 60_000L,
    private val startBudgetMs: Long = 2 * 60_000L,
    private val overallBudgetMs: Long = 25 * 60_000L,
) {
    private val overallStartedAt = clock()
    private var installEnteredAt: Long? = null
    private var startEnteredAt: Long? = null

    fun enterInstalling() {
        if (installEnteredAt == null) installEnteredAt = clock()
    }

    fun enterStarting() {
        if (startEnteredAt == null) startEnteredAt = clock()
    }

    fun installElapsedMs(): Long? = installEnteredAt?.let { clock() - it }

    fun startElapsedMs(): Long? = startEnteredAt?.let { clock() - it }

    fun overallElapsedMs(): Long = clock() - overallStartedAt

    fun installTimedOut(): Boolean = installElapsedMs()?.let { it > installBudgetMs } ?: false

    fun startTimedOut(): Boolean = startElapsedMs()?.let { it > startBudgetMs } ?: false

    fun overallTimedOut(): Boolean = overallElapsedMs() > overallBudgetMs
}
