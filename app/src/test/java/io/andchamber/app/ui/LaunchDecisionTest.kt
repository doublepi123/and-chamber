package io.andchamber.app.ui

import io.andchamber.app.runtime.FailurePhase
import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.RuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LaunchDecisionTest {
    private val running = RuntimeState.Running("http://127.0.0.1:3000", 3000, 1L)

    @Test
    fun `detecting waits`() {
        assertEquals(LaunchTarget.Wait, launchTarget(RuntimeState.Detecting))
    }

    @Test
    fun `running and starting open the local server`() {
        assertEquals(LaunchTarget.OpenLocal, launchTarget(running))
        assertEquals(LaunchTarget.OpenLocal, launchTarget(RuntimeState.Starting("Starting")))
    }

    @Test
    fun `stopped starts then opens`() {
        assertEquals(LaunchTarget.StartAndOpenLocal, launchTarget(RuntimeState.Stopped))
    }

    @Test
    fun `setup, failures, unsupported and stopping stay on home`() {
        val home =
            listOf(
                RuntimeState.NotInstalled,
                RuntimeState.UnsupportedAbi,
                RuntimeState.Installing(InstallStep.InstallPackages, 0.5f, null),
                RuntimeState.Failed("boom", recoverable = true, phase = FailurePhase.Start),
                RuntimeState.Failed("boom", recoverable = false, phase = FailurePhase.Install),
                RuntimeState.Stopping("Stopping"),
            )
        home.forEach { assertEquals("for $it", LaunchTarget.Home, launchTarget(it)) }
    }

    @Test
    fun `cover only when the launch may open the local server`() {
        assertEquals(LaunchStage.Waiting, initialLaunchStage(true, true, RuntimeState.Detecting))
        assertEquals(LaunchStage.Opening, initialLaunchStage(true, true, running))
        assertEquals(LaunchStage.Opening, initialLaunchStage(true, true, RuntimeState.Stopped))
        assertEquals(LaunchStage.Done, initialLaunchStage(true, true, RuntimeState.NotInstalled))
        assertEquals(LaunchStage.Done, initialLaunchStage(true, true, RuntimeState.Failed("x", true)))
    }

    @Test
    fun `no cover when the setting is off or the launch was already handled`() {
        assertEquals(LaunchStage.Done, initialLaunchStage(true, false, RuntimeState.Detecting))
        assertEquals(LaunchStage.Done, initialLaunchStage(false, true, RuntimeState.Detecting))
        assertEquals(LaunchStage.Done, initialLaunchStage(false, true, running))
    }

    @Test
    fun `a requested start reads as starting until the runtime moves`() {
        val stopped = RuntimeState.Stopped
        assertEquals(RuntimeState.Starting(null), shownLocalState(stopped, pendingStartFrom = stopped))
        val failed = RuntimeState.Failed("exit 1", recoverable = true, phase = FailurePhase.Start)
        assertEquals(RuntimeState.Starting(null), shownLocalState(failed, pendingStartFrom = failed))
    }

    @Test
    fun `real states pass through`() {
        assertSame(running, shownLocalState(running, pendingStartFrom = RuntimeState.Stopped))
        val starting = RuntimeState.Starting("Starting OpenChamber on port 3000")
        assertSame(starting, shownLocalState(starting, pendingStartFrom = RuntimeState.Stopped))
        assertSame(RuntimeState.Stopped, shownLocalState(RuntimeState.Stopped, pendingStartFrom = null))
        // A new failure after the start is the answer to it, not the old one.
        val old = RuntimeState.Failed("old", recoverable = true, phase = FailurePhase.Start)
        val new = RuntimeState.Failed("new", recoverable = true, phase = FailurePhase.Start)
        assertSame(new, shownLocalState(new, pendingStartFrom = old))
    }
}
