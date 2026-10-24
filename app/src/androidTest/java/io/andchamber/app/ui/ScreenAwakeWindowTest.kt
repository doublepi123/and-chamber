package io.andchamber.app.ui

import android.os.SystemClock
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.testhost.WebViewRegressionActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real window: [keepScreenOnWhileLocalRuns] sets and clears FLAG_KEEP_SCREEN_ON as the setting,
 * the server, the lifecycle and the page in view change, and clears it when the owner is destroyed.
 * The lifecycle is a test-owned registry, so each state is reached on purpose; leaving the real app
 * for the background is checked on the device separately.
 */
@RunWith(AndroidJUnit4::class)
class ScreenAwakeWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewRegressionActivity>
    private lateinit var activity: WebViewRegressionActivity
    private lateinit var owner: TestOwner

    private val running = RuntimeState.Running(url = "http://127.0.0.1:3000", port = 3000, sinceEpochMs = 1L)
    private val enabled = MutableStateFlow(false)
    private val runtime = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
    private val viewed = MutableStateFlow<String?>(null)

    @Before fun setUp() {
        scenario = ActivityScenario.launch(WebViewRegressionActivity::class.java)
        scenario.onActivity { activity = it }
        main {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            owner = TestOwner()
            owner.registry.currentState = Lifecycle.State.CREATED
        }
    }

    @After fun tearDown() {
        main { if (owner.registry.currentState != Lifecycle.State.DESTROYED) owner.registry.currentState = Lifecycle.State.DESTROYED }
        scenario.close()
    }

    @Test fun flagFollowsEveryInputAndIsClearedOnDestroy() {
        // The extension under test runs against the test owner, not the host activity.
        main { owner.bind() }
        assertFlag(false, "created, off")

        main {
            owner.registry.currentState = Lifecycle.State.RESUMED
            runtime.value = running
        }
        assertFlag(false, "default off")

        main { enabled.value = true }
        assertFlag(true, "on, running, resumed")

        main { owner.registry.currentState = Lifecycle.State.STARTED }
        assertFlag(true, "started (paused) still holds")
        main { owner.registry.currentState = Lifecycle.State.CREATED }
        assertFlag(false, "stopped (background)")
        main { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertFlag(true, "back in front")

        main { viewed.value = "remote-1" }
        assertFlag(false, "remote page")
        main { viewed.value = ServerTarget.LOCAL_ID }
        assertFlag(true, "local page")

        main { runtime.value = RuntimeState.Stopped }
        assertFlag(false, "server stopped")
        main { runtime.value = running }
        assertFlag(true, "running again")
        main { runtime.value = RuntimeState.Failed("exit 1", recoverable = true) }
        assertFlag(false, "server failed")
        main { runtime.value = RuntimeState.NotInstalled }
        assertFlag(false, "not installed")
        main { runtime.value = running }
        assertFlag(true, "running again")

        main { enabled.value = false }
        assertFlag(false, "turned off")
        main { enabled.value = true }
        assertFlag(true, "turned on")

        main { owner.registry.currentState = Lifecycle.State.DESTROYED }
        assertFlag(false, "destroyed")
    }

    private inner class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun bind() {
            keepScreenOnWhileLocalRuns(activity.window, enabled, runtime, viewed)
        }
    }

    private fun flagOn(): Boolean {
        var on = false
        main { on = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        return on
    }

    private fun assertFlag(
        expected: Boolean,
        what: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + 2_000
        while (flagOn() != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        if (expected) assertTrue(what, flagOn()) else assertFalse(what, flagOn())
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
