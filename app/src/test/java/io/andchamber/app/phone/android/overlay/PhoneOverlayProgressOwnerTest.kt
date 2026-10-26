package io.andchamber.app.phone.android.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneOverlayProgressOwnerTest {
    @Test fun oldOrDemoProgressNeverLeaksIntoAnotherSurface() {
        assertTrue(overlayProgressMatchesSurface(8, 8, false))
        assertFalse(overlayProgressMatchesSurface(9, 8, false))
        assertFalse(overlayProgressMatchesSurface(8, 8, true))
        assertFalse(overlayProgressMatchesSurface(null, null, false))
    }
}
