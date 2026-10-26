package io.andchamber.app.phone.android.overlay

import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneOverlayViewportTest {
    @Test fun expandedPanelFitsTheAvailableKeyboardViewport() {
        val geometry = PhoneOverlayGeometry(360, 240, 24, 100, 16, 56, 360, 320)
        val placement = overlayPlacement(
            PhoneOverlayWindowState("task", expanded = true, offsetY = 220), geometry,
        )
        assertTrue(placement.topPx >= geometry.topInsetPx + geometry.sideMarginPx)
        assertTrue(placement.heightPx > 0)
        assertTrue(placement.topPx + placement.heightPx <=
            geometry.screenHeightPx - geometry.bottomInsetPx - geometry.sideMarginPx)
    }
}
