package io.andchamber.app.service

import io.andchamber.app.runtime.FailurePhase
import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.service.RuntimeServicePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service lifecycle as a sequence of (busy, state) observations. Each test replays what the
 * controller publishes for one user flow and checks when the service would stay up or stop.
 */
class RuntimeServicePolicyTest {
    private fun replay(vararg steps: Pair<Boolean, RuntimeState>): List<Action> =
        steps.map { (busy, state) -> RuntimeServicePolicy.decide(busy, state) }

    @Test
    fun `install then start keeps the service through the intermediate Stopped and reaches Running`() {
        val actions =
            replay(
                true to RuntimeState.Installing(InstallStep.DownloadRootfs, 0f, null),
                true to RuntimeState.Installing(InstallStep.Verify, 1f, null),
                true to RuntimeState.Stopped, // between install and start: must NOT end the service
                true to RuntimeState.Starting("Starting"),
                true to RuntimeState.Running("http://127.0.0.1:3000", 3000, 1L),
                false to RuntimeState.Running("http://127.0.0.1:3000", 3000, 1L),
            )

        assertTrue(actions.all { it == Action.KeepRunning })
    }

    @Test
    fun `repair keeps the service through its internal stop and reinstall`() {
        val actions =
            replay(
                true to RuntimeState.Stopping("Stopping OpenChamber"),
                true to RuntimeState.Stopped,
                true to RuntimeState.Installing(InstallStep.InstallPackages, null, null),
                true to RuntimeState.Stopped,
                true to RuntimeState.Starting("Starting"),
                false to RuntimeState.Running("http://127.0.0.1:3000", 3000, 1L),
            )

        assertTrue(actions.all { it == Action.KeepRunning })
    }

    @Test
    fun `watchdog recovery that ends in a final Failed releases the wake lock and stops`() {
        val running = replay(false to RuntimeState.Running("http://127.0.0.1:3000", 3000, 1L))
        assertEquals(listOf(Action.KeepRunning), running)

        // Crash: recovery is queued (busy), then the budget is spent.
        val recovery =
            replay(
                true to RuntimeState.Failed("OpenChamber stopped unexpectedly", true, FailurePhase.Runtime),
                true to RuntimeState.Starting("restarting in 2s"),
                true to RuntimeState.Failed("OpenChamber kept stopping", true, FailurePhase.Runtime),
                false to RuntimeState.Failed("OpenChamber kept stopping", true, FailurePhase.Runtime),
            )

        assertEquals(Action.Finish, recovery.last())
        assertFalse(RuntimeServicePolicy.wakeLockHeld(recovery.last()))
    }

    @Test
    fun `uninstall ends at NotInstalled and the service stops`() {
        val actions =
            replay(
                true to RuntimeState.Stopping("Stopping OpenChamber"),
                true to RuntimeState.Stopped,
                false to RuntimeState.NotInstalled,
            )

        assertEquals(listOf(Action.KeepRunning, Action.KeepRunning, Action.Finish), actions)
    }

    @Test
    fun `wake lock is held exactly while the service is foreground`() {
        assertTrue(RuntimeServicePolicy.wakeLockHeld(Action.KeepRunning))
        assertFalse(RuntimeServicePolicy.wakeLockHeld(Action.Finish))
    }

    @Test
    fun `a failure while idle with nothing queued stops the service`() {
        assertEquals(Action.Finish, RuntimeServicePolicy.decide(false, RuntimeState.Failed("x", true, FailurePhase.Install)))
        assertEquals(Action.Finish, RuntimeServicePolicy.decide(false, RuntimeState.UnsupportedAbi))
    }

    @Test
    fun `queued work keeps the service even in a terminal state`() {
        assertEquals(Action.KeepRunning, RuntimeServicePolicy.decide(true, RuntimeState.Stopped))
    }
}
