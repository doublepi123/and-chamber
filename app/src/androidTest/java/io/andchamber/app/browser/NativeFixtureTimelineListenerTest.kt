package io.andchamber.app.browser

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFixtureTimelineListenerTest {
    @Test fun replacingObservedSourceKeepsBothInstallIdentitiesWithoutOldWrites() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val first = View(instrumentation.targetContext)
            val second = View(instrumentation.targetContext)
            val timeline = NativeFixtureTimeline({ null }, { null })
            timeline.install(first)
            timeline.install(second)
            first.viewTreeObserver.dispatchOnGlobalLayout()
            val events = JSONObject(timeline.json()).getJSONArray("events")
            assertEquals(2, events.length())
            assertEquals(System.identityHashCode(first), events.getJSONArray(0).getInt(3))
            assertEquals(System.identityHashCode(second), events.getJSONArray(1).getInt(3))
            assertTrue(events.getJSONArray(1).isNull(4))
            timeline.close()
            second.viewTreeObserver.dispatchOnGlobalLayout()
            assertEquals(2L, JSONObject(timeline.json()).getLong("seq"))
        }
    }

    @Test fun installationAndCleanupAreReadOnlyAndStaleSourcesCannotAppend() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val target = View(instrumentation.targetContext).apply { isFocusableInTouchMode = true }
            val timeline = NativeFixtureTimeline({ null }, { null })
            timeline.install(target)
            val first = JSONObject(timeline.json())
            assertEquals(1, first.getJSONArray("events").length())
            assertTrue(target.isFocusableInTouchMode)
            assertFalse(target.hasFocus())
            // No selected presenter target: the original tree listener must unregister, not append.
            target.viewTreeObserver.dispatchOnGlobalLayout()
            timeline.mark(NativeFixtureTimelineRing.Kind.COMMAND_END)
            assertEquals(1L, JSONObject(timeline.json()).getLong("seq"))
            timeline.close()
            timeline.close()
            target.viewTreeObserver.dispatchOnGlobalLayout()
            assertEquals(1L, JSONObject(timeline.json()).getLong("seq"))
            assertTrue(target.isFocusableInTouchMode)
            assertFalse(target.hasFocus())
        }
    }
}
