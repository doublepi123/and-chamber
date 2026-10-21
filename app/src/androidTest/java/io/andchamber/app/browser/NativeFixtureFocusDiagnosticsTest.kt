package io.andchamber.app.browser

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFixtureFocusDiagnosticsTest {
    @Test fun readsIdentityFlagsAndOwnersWithoutChangingFocus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val root = FrameLayout(context)
            val target = FrameLayout(context).apply { isFocusableInTouchMode = true }
            val child = View(context)
            val other = View(context)
            root.addView(target)
            target.addView(child)
            root.addView(other)
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            val beforeFocus = root.findFocus()
            val sample = NativeFixtureFocusDiagnostics.snapshot(target, target, other, child)
            assertTrue(sample.getBoolean("targetIsSelected"))
            assertFalse(sample.getBoolean("targetVisible"))
            assertTrue(sample.getJSONObject("target").getBoolean("isFocusableInTouchMode"))
            assertEquals(System.identityHashCode(target), sample.getJSONObject("target").getInt("identity"))
            assertFalse(sample.getJSONObject("activityCurrentFocus").getBoolean("belongsToTarget"))
            assertTrue(sample.getJSONObject("decorFindFocus").getBoolean("belongsToTarget"))
            assertFalse(sample.getJSONObject("decorFindFocus").getBoolean("isTarget"))
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS,
                sample.getJSONArray("ancestors").getJSONObject(0).getInt("descendantFocusability"))
            assertEquals(1, sample.getJSONArray("ancestors").length())
            assertFalse(sample.getBoolean("ancestorsTruncated"))
            assertSame(beforeFocus, root.findFocus())
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, root.descendantFocusability)
            assertTrue(target.isFocusableInTouchMode)
            assertFalse(NativeFixtureFocusDiagnostics.snapshot(target, other, null, null)
                .getBoolean("targetIsSelected"))
        }
    }

    @Test fun missingViewsStayUnknownNotFalseFocusEvidence() {
        val sample = NativeFixtureFocusDiagnostics.snapshot(null, null, null, null)
        for (key in listOf("target", "selectedView", "targetVisible", "activityCurrentFocus", "decorFindFocus")) {
            assertTrue(key, sample.isNull(key))
        }
        assertFalse(sample.getBoolean("targetIsSelected"))
        assertEquals(0, sample.getJSONArray("ancestors").length())
    }
}
