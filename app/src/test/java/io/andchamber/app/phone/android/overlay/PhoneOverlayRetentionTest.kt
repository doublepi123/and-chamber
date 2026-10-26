package io.andchamber.app.phone.android.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneOverlayRetentionTest {
    @Test fun noTaskOrDemoNeverUsesThePreviousTasksRetentionClock() {
        assertFalse(taskDisplayRetentionElapsed(0, 9_000_000, 600_000, false))
        assertFalse(taskDisplayRetentionElapsed(1, 9_000_000, 600_000, true))
        assertFalse(taskDisplayRetentionElapsed(9_000_000, 9_600_000, 600_000, false))
        assertTrue(taskDisplayRetentionElapsed(9_000_000, 9_600_001, 600_000, false))
    }
}
