package io.andchamber.app.ui

import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import io.andchamber.app.runtime.FailurePhase
import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenAwakeTest {
    private val running = RuntimeState.Running(url = "http://127.0.0.1:3000", port = 3000, sinceEpochMs = 1L)

    private val notRunning =
        listOf(
            RuntimeState.Detecting,
            RuntimeState.UnsupportedAbi,
            RuntimeState.NotInstalled,
            RuntimeState.Installing(InstallStep.setupSteps.first(), progress = 0.5f, detail = null),
            RuntimeState.Stopped,
            RuntimeState.Starting(detail = null),
            RuntimeState.Stopping(detail = null),
            RuntimeState.Failed("exit 1", recoverable = true, phase = FailurePhase.Runtime),
            RuntimeState.Failed("broken", recoverable = false),
        )

    // --- Policy matrix ---

    @Test
    fun `holds only when on, running, in front and not on a remote page`() {
        for (life in Lifecycle.State.entries) {
            for (viewed in listOf(null, ServerTarget.LOCAL_ID, "remote-1")) {
                val expected = life.isAtLeast(Lifecycle.State.STARTED) && viewed != "remote-1"
                assertEquals(
                    "on/Running/$life/$viewed",
                    expected,
                    ScreenAwakePolicy.keepScreenOn(enabled = true, state = running, lifecycle = life, viewedTargetId = viewed),
                )
                assertFalse(
                    "off/Running/$life/$viewed",
                    ScreenAwakePolicy.keepScreenOn(enabled = false, state = running, lifecycle = life, viewedTargetId = viewed),
                )
            }
        }
    }

    @Test
    fun `every state but Running lets the screen sleep`() {
        for (state in notRunning) {
            for (life in listOf(Lifecycle.State.STARTED, Lifecycle.State.RESUMED)) {
                assertFalse("$state/$life", ScreenAwakePolicy.keepScreenOn(true, state, life, null))
                assertFalse("$state/$life/local", ScreenAwakePolicy.keepScreenOn(true, state, life, ServerTarget.LOCAL_ID))
            }
        }
    }

    @Test
    fun `background and destroyed never hold`() {
        for (life in listOf(Lifecycle.State.DESTROYED, Lifecycle.State.INITIALIZED, Lifecycle.State.CREATED)) {
            assertFalse("$life", ScreenAwakePolicy.keepScreenOn(true, running, life, ServerTarget.LOCAL_ID))
        }
    }

    @Test
    fun `only Home and the local page count as not remote`() {
        assertFalse(ScreenAwakePolicy.remoteInView(null))
        assertFalse(ScreenAwakePolicy.remoteInView(ServerTarget.LOCAL_ID))
        assertTrue(ScreenAwakePolicy.remoteInView("3f2a-uuid"))
        assertTrue(ScreenAwakePolicy.remoteInView(""))
    }

    // --- Setting: default and persistence ---

    @Test
    fun `off by default, in a fresh file and without a file`() {
        assertFalse(ScreenAwakeController(MemoryPrefs()).enabled.value)
        assertFalse(ScreenAwakeController(null).enabled.value)
        assertNull(ScreenAwakeController(null).viewedTargetId.value)
    }

    @Test
    fun `the choice is saved under its own key and read back by a new process`() {
        val store = MemoryPrefs()
        val controller = ScreenAwakeController(store)

        controller.setEnabled(true)

        assertTrue(controller.enabled.value)
        assertEquals(true, store.all[ScreenAwakeController.KEY_KEEP_SCREEN_ON])
        assertTrue(ScreenAwakeController(store).enabled.value)

        controller.setEnabled(false)
        assertFalse(ScreenAwakeController(store).enabled.value)
    }

    @Test
    fun `its key never collides with UiPrefs, and UiPrefs keeps working beside it`() {
        val store = MemoryPrefs()
        val controller = ScreenAwakeController(store)
        val ui = UiPrefs(store)
        controller.setEnabled(true)
        ui.updateAutoOpenLocal(false)

        assertTrue(ScreenAwakeController(store).enabled.value)
        assertFalse(UiPrefs(store).autoOpenLocal)
        assertTrue(
            ScreenAwakeController.KEY_KEEP_SCREEN_ON !in
                setOf(UiPrefs.KEY_AUTO_OPEN_LOCAL, UiPrefs.KEY_LAST_TARGET_ID),
        )
        assertFalse(ScreenAwakeController.KEY_KEEP_SCREEN_ON.startsWith(UiPrefs.PAGE_PATH_PREFIX))
        assertFalse(ScreenAwakeController.KEY_KEEP_SCREEN_ON.startsWith(UiPrefs.PAGE_ORIGIN_PREFIX))
    }

    @Test
    fun `two readers of one file stay in step`() {
        val store = MemoryPrefs()
        val activitySide = ScreenAwakeController(store)
        val homeSide = ScreenAwakeController(store)

        homeSide.setEnabled(true)

        assertTrue(activitySide.enabled.value)
    }

    @Test
    fun `follows the instance UiPrefs records as being viewed`() {
        val store = MemoryPrefs()
        val controller = ScreenAwakeController(store)
        val ui = UiPrefs(store)

        ui.setLastTargetId("remote-1")
        assertEquals("remote-1", controller.viewedTargetId.value)
        ui.setLastTargetId(ServerTarget.LOCAL_ID)
        assertEquals(ServerTarget.LOCAL_ID, controller.viewedTargetId.value)
        ui.clearLastTarget()
        assertNull(controller.viewedTargetId.value)
    }

    @Test
    fun `a remote left open last time reads as in view from the start`() {
        val store = MemoryPrefs()
        UiPrefs(store).setLastTargetId("remote-1")

        assertEquals("remote-1", ScreenAwakeController(store).viewedTargetId.value)
    }

    // --- The window hold ---

    @Test
    fun `the hold changes the flag only on a change and never clears a flag it didn't set`() {
        val calls = mutableListOf<Boolean>()
        val hold = ScreenAwakeHold { calls += it }

        hold.release()
        hold.update(false)
        assertEquals(emptyList<Boolean>(), calls)

        hold.update(true)
        hold.update(true)
        hold.update(false)
        hold.update(true)
        hold.release()
        hold.release()
        assertEquals(listOf(true, false, true, false), calls)
        assertFalse(hold.held)
    }

    @Test
    fun `follows the server, the lifecycle and the page, and releases when it ends`() =
        runTest(UnconfinedTestDispatcher()) {
            val enabled = MutableStateFlow(false)
            val runtime = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
            val lifecycle = MutableStateFlow(Lifecycle.State.CREATED)
            val viewed = MutableStateFlow<String?>(null)
            val flag = FakeFlag()
            val job =
                launch {
                    holdScreenAwake(screenAwakeFlow(enabled, runtime, lifecycle, viewed), ScreenAwakeHold(flag))
                }

            lifecycle.value = Lifecycle.State.RESUMED
            runtime.value = running
            assertFalse("default off", flag.on)

            enabled.value = true
            assertTrue("on, running, resumed", flag.on)

            lifecycle.value = Lifecycle.State.STARTED
            assertTrue("still visible (paused)", flag.on)
            lifecycle.value = Lifecycle.State.CREATED
            assertFalse("background", flag.on)
            lifecycle.value = Lifecycle.State.RESUMED
            assertTrue("back in front", flag.on)

            viewed.value = "remote-1"
            assertFalse("remote page", flag.on)
            viewed.value = ServerTarget.LOCAL_ID
            assertTrue("local page", flag.on)

            runtime.value = RuntimeState.Stopping(detail = null)
            assertFalse("stopping", flag.on)
            runtime.value = RuntimeState.Stopped
            assertFalse("stopped", flag.on)
            runtime.value = running
            assertTrue(flag.on)
            runtime.value = RuntimeState.Failed("exit 1", recoverable = true)
            assertFalse("failed", flag.on)
            runtime.value = running
            runtime.value = RuntimeState.NotInstalled
            assertFalse("uninstalled", flag.on)
            runtime.value = running
            assertTrue(flag.on)

            enabled.value = false
            assertFalse("turned off", flag.on)
            enabled.value = true
            assertTrue(flag.on)

            job.cancel()
            assertFalse("released when the activity goes", flag.on)
            // One window call per real change (no repeats for STARTED, or for Stopping -> Stopped).
            assertEquals(List(14) { it % 2 == 0 }, flag.calls)
        }

    @Test
    fun `ending while off never touches the window`() =
        runTest(UnconfinedTestDispatcher()) {
            val flag = FakeFlag()
            val job =
                launch {
                    holdScreenAwake(
                        screenAwakeFlow(
                            MutableStateFlow(false),
                            MutableStateFlow(running),
                            MutableStateFlow(Lifecycle.State.RESUMED),
                            MutableStateFlow(null),
                        ),
                        ScreenAwakeHold(flag),
                    )
                }
            job.cancel()
            assertEquals(emptyList<Boolean>(), flag.calls)
        }

    private class FakeFlag : ScreenOnFlag {
        val calls = mutableListOf<Boolean>()
        val on: Boolean get() = calls.lastOrNull() == true

        override fun set(on: Boolean) {
            calls += on
        }
    }

    /** Enough of SharedPreferences, with change listeners; edits apply at once. */
    private class MemoryPrefs : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()
        private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = HashMap(values)

        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = key in values

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners -= listener
        }

        private inner class Editor : SharedPreferences.Editor {
            private val puts = LinkedHashMap<String, Any?>()
            private val removes = HashSet<String>()

            override fun putString(key: String, value: String?) = apply { puts[key] = value }

            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }

            override fun putInt(key: String, value: Int) = apply { puts[key] = value }

            override fun putLong(key: String, value: Long) = apply { puts[key] = value }

            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }

            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }

            override fun remove(key: String) = apply { removes += key }

            override fun clear() = apply { removes += values.keys }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                val changed = removes + puts.keys
                removes.forEach(values::remove)
                values.putAll(puts)
                for (key in changed) listeners.toList().forEach { it.onSharedPreferenceChanged(this@MemoryPrefs, key) }
            }
        }
    }
}
