package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.android.PhoneProcessNonce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display-service notification identity. A fixed request code or a bare action is not an owner:
 * an old notification must never close a newer display, and a late service destroy must never touch it.
 */
class PhoneOverlayDisplayOwnerTest {
    @Test
    fun `an owner carries the process nonce, instance and opaque task owner`() {
        val owner = PhoneOverlayDisplayOwner.mint("a".repeat(32), 4L, "grant-7:3")
        assertTrue(PhoneOverlayDisplayOwner.hasCurrentNonce(owner).not()) // not this process's nonce
        assertEquals("grant-7:3", PhoneOverlayDisplayOwner.taskOwner(owner))
        assertTrue(owner.startsWith("a".repeat(32) + "|4|"))
    }

    @Test
    fun `only the exact current owner matches`() {
        val current = PhoneOverlayDisplayOwner.mint(PhoneProcessNonce.value, 2L, "grant-7:3")
        assertTrue(PhoneOverlayDisplayOwner.hasCurrentNonce(current))
        assertTrue(PhoneOverlayDisplayOwner.matches(current, current))
        assertFalse(PhoneOverlayDisplayOwner.matches(current, null))
        assertFalse(PhoneOverlayDisplayOwner.matches(null, current))
        // An older instance of the same task is not the current owner.
        val older = PhoneOverlayDisplayOwner.mint(PhoneProcessNonce.value, 1L, "grant-7:3")
        assertFalse(PhoneOverlayDisplayOwner.matches(older, current))
        // A newer task owner is not the old one either.
        val newer = PhoneOverlayDisplayOwner.mint(PhoneProcessNonce.value, 2L, "grant-8:4")
        assertFalse(PhoneOverlayDisplayOwner.matches(newer, current))
    }

    @Test
    fun `close and open data URIs round-trip the exact owner`() {
        val owner = PhoneOverlayDisplayOwner.mint(PhoneProcessNonce.value, 9L, "retained:5")
        assertEquals(owner, PhoneOverlayDisplayOwner.fromData(PhoneOverlayDisplayOwner.closeIntentData(owner)))
        assertEquals(owner, PhoneOverlayDisplayOwner.fromData(PhoneOverlayDisplayOwner.openIntentData(owner)))
        assertNull(PhoneOverlayDisplayOwner.fromData("https://example.com/close/x"))
        assertNull(PhoneOverlayDisplayOwner.fromData(null))
        assertTrue(PhoneOverlayDisplayOwner.closeIntentData(owner) != PhoneOverlayDisplayOwner.openIntentData(owner))
    }

    @Test
    fun `the unavailable display bridge fails closed`() {
        val bridge = PhoneOverlayDisplayBridge.Unavailable
        assertEquals("", bridge.taskOwner())
        assertFalse("no coordinator means no close", bridge.onCloseRequested("any"))
        // The other calls are no-ops; calling them must not throw.
        bridge.onServiceStarted("any")
        bridge.onServiceDestroyed("any")
        bridge.onPermissionLost("any")
    }

    @Test
    fun `closing is refused while a lease is live and allowed only for a finished task`() {
        assertFalse("a live task must not silently close", overlayCloseAllowed(hasLease = true, phase = PhoneOverlayPhase.NEEDS_CHECK))
        assertTrue(overlayCloseAllowed(hasLease = false, phase = PhoneOverlayPhase.NEEDS_CHECK))
        assertTrue(overlayCloseAllowed(hasLease = false, phase = PhoneOverlayPhase.ENDED))
        assertFalse("a retained takeover is not a finished result", overlayCloseAllowed(hasLease = false, phase = PhoneOverlayPhase.TAKEN_OVER))
        assertFalse(overlayCloseAllowed(hasLease = false, phase = PhoneOverlayPhase.RUNNING))
    }
}
