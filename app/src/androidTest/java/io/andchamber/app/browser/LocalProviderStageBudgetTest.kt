package io.andchamber.app.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure fake-clock checks for [StageBudget]; no Android APIs and no device. */
class LocalProviderStageBudgetTest {
    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun installFiveMinutesThenStartingAtZeroDoesNotConsumeTheStartBudget() {
        val clock = FakeClock(0L)
        val budget = StageBudget(clock)
        budget.enterInstalling()
        clock.now = 5 * 60_000L
        budget.enterStarting()
        assertFalse(budget.installTimedOut())
        assertFalse(budget.startTimedOut())
        assertFalse(budget.overallTimedOut())
    }

    @Test
    fun starting121SecondsTimesOutBut120SecondsDoesNot() {
        val clock = FakeClock(0L)
        val budget = StageBudget(clock)
        budget.enterStarting()
        clock.now = 120_000L
        assertFalse(budget.startTimedOut())
        clock.now = 121_000L
        assertTrue(budget.startTimedOut())
    }

    @Test
    fun startingStepChangesDoNotRefreshTheStartBudget() {
        val clock = FakeClock(0L)
        val budget = StageBudget(clock)
        budget.enterStarting()
        clock.now = 100_000L
        budget.enterStarting()
        assertFalse(budget.startTimedOut())
        clock.now = 121_000L
        assertTrue(budget.startTimedOut())
    }

    @Test
    fun installBudgetStartsAtFirstInstallingAndDoesNotResetOnStepChanges() {
        val clock = FakeClock(0L)
        val budget = StageBudget(clock)
        budget.enterInstalling()
        clock.now = 5 * 60_000L
        budget.enterInstalling()
        clock.now = 20 * 60_000L
        assertFalse(budget.installTimedOut())
        clock.now = 20 * 60_000L + 1
        assertTrue(budget.installTimedOut())
    }

    @Test
    fun startBudgetStartsOnlyWhenStartingIsFirstEntered() {
        val clock = FakeClock(0L)
        val budget = StageBudget(clock)
        clock.now = 24 * 60_000L
        budget.enterStarting()
        clock.now = 26 * 60_000L
        assertFalse(budget.startTimedOut())
        clock.now = 26 * 60_000L + 1
        assertTrue(budget.startTimedOut())
        assertTrue(budget.overallTimedOut())
    }

    @Test
    fun overallBudgetIsTwentyFiveMinutesEvenWithAFreshStartStage() {
        val clock = FakeClock(0L)
        val budget = StageBudget(clock)
        clock.now = 24 * 60_000L
        budget.enterStarting()
        assertFalse(budget.overallTimedOut())
        clock.now = 25 * 60_000L + 1
        assertTrue(budget.overallTimedOut())
    }
}
