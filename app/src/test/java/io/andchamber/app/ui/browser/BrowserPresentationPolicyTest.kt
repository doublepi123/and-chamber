package io.andchamber.app.ui.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserPresentationPolicyTest {
    private fun bounds(height: Float, clearance: Float = 136f) =
        editableFloatBounds(floatWindowBounds(328f, height, clearance, false))

    @Test fun preferredFractionIsNotInsufficientSafeSpace() {
        val preferred = floatWindowBounds(328f, 360f, 136f, false)
        assertTrue(preferred.height < 176f)
        val actual = editableFloatBounds(preferred)
        assertEquals(216f, actual.height, 0f)
        assertFalse(presentationBlocked(false, actual))
        assertTrue(actual.height <= actual.areaHeight)
    }

    @Test fun genuinelyInsufficientSpaceBlocksAndKeyboardRecoveryDoesNotReleaseLatch() {
        val short = bounds(300f)
        val latch = presentationBlocked(false, short)
        assertTrue(latch)
        assertTrue(presentationBlocked(latch, bounds(700f)))
        assertFalse(releasePresentationLatch(false, bounds(700f)))
    }

    @Test fun onlyExplicitEnlargeWithEnoughRoomReleases() {
        assertFalse(releasePresentationLatch(true, bounds(300f)))
        assertTrue(releasePresentationLatch(true, bounds(700f)))
        assertFalse(presentationBlocked(false, bounds(700f)))
    }

    @Test fun zeroAndNarrowAreasStayNonNegativeAndBlocked() {
        for (height in listOf(-1f, 0f, 10f, 136f, 137f)) {
            val b = bounds(height)
            assertTrue(b.height >= 0)
            assertTrue(b.height <= b.areaHeight)
            assertTrue(presentationBlocked(false, b))
        }
        assertTrue(presentationBlocked(false, editableFloatBounds(floatWindowBounds(120f, 700f, 136f, false))))
    }

    @Test fun editableFloorKeepsComposerAndDragBounds() {
        val b = bounds(360f)
        assertEquals(224f, b.areaHeight, 0f)
        assertEquals(-8f, b.clampY(-1000f), 0f)
        assertEquals(0f, b.clampY(1000f), 0f)
        assertTrue(b.height + -b.clampY(-1000f) <= b.areaHeight)
        assertEquals(0f, b.clampX(1000f), 0f)
    }

    @Test fun ordinaryLargeLayoutAndRequestedSizeRemainIntact() {
        val original = floatWindowBounds(800f, 700f, 72f, true, 420f, 400f)
        assertEquals(original, editableFloatBounds(original))
    }

    @Test fun thresholdIsInclusiveAndDoesNotDependOnFraction() {
        assertFalse(presentationBlocked(false, bounds(352f)))
        assertTrue(presentationBlocked(false, bounds(351f)))
    }

    @Test fun nativeHideAlwaysFollowsCoreGateAndRecoveryDoesNotGrant() {
        val order = mutableListOf<String>()
        var grant = true
        fun gate(blocked: Boolean) { order += "gate:$blocked"; if (blocked) grant = false }
        applyBrowserPresentation(true, ::gate) { order += "native:$it"; assertFalse(grant) }
        applyBrowserPresentation(false, ::gate) { order += "native:$it"; assertFalse(grant) }
        assertEquals(listOf("gate:true", "native:true", "gate:false", "native:false"), order)
    }
}
