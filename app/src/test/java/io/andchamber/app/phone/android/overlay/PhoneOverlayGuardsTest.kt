package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.core.PhoneRect
import io.andchamber.app.phone.overlay.progress.PhoneStopAcknowledgement
import io.andchamber.app.phone.overlay.safety.OverlayPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay is display and stop only, and it is never assumed visible. These cases pin the gates the
 * coordinator relies on: permission is not visibility, a hidden collection must revoke, and a stop is
 * only acknowledged after the native owner confirmed it.
 */
class PhoneOverlayGuardsTest {
    @Test
    fun `overlay permission is required and falls back to notification mode`() {
        assertEquals(PhoneOverlayGate.NEEDS_PERMISSION, overlayGate(canDrawOverlays = false))
        assertEquals(PhoneOverlayGate.READY, overlayGate(canDrawOverlays = true))
        assertEquals(
            PhoneOverlayMode.OFF,
            effectiveOverlayMode(PhoneOverlayMode.OFF, PhoneOverlayGate.NEEDS_PERMISSION),
        )
        assertEquals(
            PhoneOverlayMode.NOTIFICATION_ONLY,
            effectiveOverlayMode(PhoneOverlayMode.NOTIFICATION_ONLY, PhoneOverlayGate.READY),
        )
        // Asking for overlay without the grant degrades to the verified notification mode, never off.
        assertEquals(
            PhoneOverlayMode.NOTIFICATION_ONLY,
            effectiveOverlayMode(PhoneOverlayMode.OVERLAY, PhoneOverlayGate.NEEDS_PERMISSION),
        )
        assertEquals(
            PhoneOverlayMode.OVERLAY,
            effectiveOverlayMode(PhoneOverlayMode.OVERLAY, PhoneOverlayGate.READY),
        )
    }

    @Test
    fun `a window is only attempted for effective overlay mode with real content`() {
        assertTrue(overlayMayAttach(contentAvailable = true, PhoneOverlayMode.OVERLAY, PhoneOverlayGate.READY))
        assertFalse("no designer content: never an empty window", overlayMayAttach(false, PhoneOverlayMode.OVERLAY, PhoneOverlayGate.READY))
        assertFalse("permission missing: notification mode instead", overlayMayAttach(true, PhoneOverlayMode.OVERLAY, PhoneOverlayGate.NEEDS_PERMISSION))
        assertFalse(overlayMayAttach(true, PhoneOverlayMode.NOTIFICATION_ONLY, PhoneOverlayGate.READY))
        assertFalse(overlayMayAttach(true, PhoneOverlayMode.OFF, PhoneOverlayGate.READY))
    }

    @Test
    fun `a timed out read may fail closed only for its own current owner`() {
        val old = PhoneOverlayReadLease("grant-7", 2, 5, OverlayPhase.COLLECTION_HIDDEN)
        assertTrue(overlayReadTimeoutStillOwns(old, 7, "grant-7", 2))
        assertFalse("a newer live epoch must not be revoked by the old cleanup", overlayReadTimeoutStillOwns(old, 8, "grant-7", 2))
        assertFalse("a newer owner generation wins", overlayReadTimeoutStillOwns(old, 7, "grant-7", 3))
        assertTrue(overlayReadTimeoutStillOwns(old, null, "grant-7", 2))
        assertFalse(overlayReadTimeoutStillOwns(old, null, "grant-8", 2))
        assertFalse(overlayReadTimeoutStillOwns(old, null, "retained", 2))
    }

    @Test
    fun `task progress is accepted only for the live epoch or exact display owner`() {
        assertTrue(overlayTaskSnapshotBelongsToDisplay(snapshotGrantEpoch = 7, liveEpoch = 7, ownerKey = "grant-7"))
        assertFalse(overlayTaskSnapshotBelongsToDisplay(snapshotGrantEpoch = 6, liveEpoch = 7, ownerKey = "grant-7"))
        assertTrue(overlayTaskSnapshotBelongsToDisplay(snapshotGrantEpoch = 7, liveEpoch = null, ownerKey = "grant-7"))
        assertFalse(overlayTaskSnapshotBelongsToDisplay(snapshotGrantEpoch = 6, liveEpoch = null, ownerKey = "grant-7"))
        assertFalse(overlayTaskSnapshotBelongsToDisplay(snapshotGrantEpoch = 7, liveEpoch = null, ownerKey = "grant-8"))
        assertFalse(overlayTaskSnapshotBelongsToDisplay(snapshotGrantEpoch = 7, liveEpoch = null, ownerKey = "retained"))
    }

    @Test
    fun `only a selected overlay lease fails closed when its surface is unavailable`() {
        assertFalse(
            "a default/off grant keeps its native notification stop entry",
            overlayLeaseMustFailClosed(hasLease = true, PhoneOverlayMode.OFF, contentAvailable = true, PhoneOverlayGate.READY),
        )
        assertFalse(
            "an explicit notification-only grant is not revoked by a missing overlay",
            overlayLeaseMustFailClosed(
                hasLease = true,
                PhoneOverlayMode.NOTIFICATION_ONLY,
                contentAvailable = true,
                PhoneOverlayGate.READY,
            ),
        )
        assertFalse(
            "a selected overlay with permission and content need not revoke",
            overlayLeaseMustFailClosed(hasLease = true, PhoneOverlayMode.OVERLAY, contentAvailable = true, PhoneOverlayGate.READY),
        )
        assertTrue(
            "selected overlay with lost permission fails closed",
            overlayLeaseMustFailClosed(hasLease = true, PhoneOverlayMode.OVERLAY, contentAvailable = true, PhoneOverlayGate.NEEDS_PERMISSION),
        )
        assertTrue(
            "selected overlay with an unavailable platform fails closed",
            overlayLeaseMustFailClosed(hasLease = true, PhoneOverlayMode.OVERLAY, contentAvailable = true, PhoneOverlayGate.UNAVAILABLE),
        )
        assertTrue(
            "selected overlay with missing content fails closed",
            overlayLeaseMustFailClosed(hasLease = true, PhoneOverlayMode.OVERLAY, contentAvailable = false, PhoneOverlayGate.READY),
        )
        assertFalse(
            "no lease: nothing to revoke",
            overlayLeaseMustFailClosed(hasLease = false, PhoneOverlayMode.OVERLAY, contentAvailable = false, PhoneOverlayGate.NEEDS_PERMISSION),
        )
    }

    @Test
    fun `leaving overlay while leased requires a native stop`() {
        assertFalse(overlayModeChangeRequiresStop(PhoneOverlayMode.NOTIFICATION_ONLY, PhoneOverlayMode.NOTIFICATION_ONLY, true))
        assertFalse(overlayModeChangeRequiresStop(PhoneOverlayMode.NOTIFICATION_ONLY, PhoneOverlayMode.OVERLAY, true))
        assertTrue(overlayModeChangeRequiresStop(PhoneOverlayMode.OVERLAY, PhoneOverlayMode.NOTIFICATION_ONLY, true))
        assertTrue(overlayModeChangeRequiresStop(PhoneOverlayMode.OVERLAY, PhoneOverlayMode.OFF, true))
        assertFalse(overlayModeChangeRequiresStop(PhoneOverlayMode.OVERLAY, PhoneOverlayMode.NOTIFICATION_ONLY, false))
        assertFalse(overlayModeChangeRequiresStop(PhoneOverlayMode.OVERLAY, PhoneOverlayMode.OVERLAY, true))
    }

    @Test
    fun `a native stop ack only claims blocked operations and never a rollback`() {
        val confirmed = PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true)
        assertTrue(confirmed.confirmed)
        assertTrue("sent commands are never claimed as rolled back", confirmed.sentActionsMayStillApply)
        assertFalse(
            "new operations must really be blocked",
            PhoneStopAcknowledgement(newOperationsBlocked = false, lateReadsBlocked = true).confirmed,
        )
        assertFalse(
            "late reads must really be blocked",
            PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = false).confirmed,
        )
    }

    @Test
    fun `overlay mode needs both the grant and a demo the person really saw`() {
        assertFalse(
            "no seen demo: overlay mode must stay unavailable",
            overlayModeSelectionAllowed(PhoneOverlayMode.OVERLAY, PhoneOverlayGate.READY, demoSeen = false),
        )
        assertTrue(overlayModeSelectionAllowed(PhoneOverlayMode.OVERLAY, PhoneOverlayGate.READY, demoSeen = true))
        assertFalse(
            "the grant can be withdrawn after the test",
            overlayModeSelectionAllowed(PhoneOverlayMode.OVERLAY, PhoneOverlayGate.NEEDS_PERMISSION, demoSeen = true),
        )
        // The verified notification mode is always selectable.
        assertTrue(overlayModeSelectionAllowed(PhoneOverlayMode.NOTIFICATION_ONLY, PhoneOverlayGate.NEEDS_PERMISSION, demoSeen = false))
        assertTrue(overlayModeSelectionAllowed(PhoneOverlayMode.OFF, PhoneOverlayGate.READY, demoSeen = false))
    }
    @Test
    fun `an automatic make way picks the opposite edge at the same height first`() {
        val geometry = PhoneOverlayGeometry(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            topInsetPx = 0,
            bottomInsetPx = 0,
            sideMarginPx = 40,
            collapsedHeightPx = 150,
            expandedHeightPx = 900,
            expandedWidthPx = 800,
        )
        // The capsule is on the right edge around y=1000; the avoided rect is under it.
        val avoid = listOf(PhoneRect(900, 980, 1080, 1180))
        val choice = overlayMakeWayPlacement(
            currentSide = PhoneOverlaySide.RIGHT,
            currentTopPx = 1000,
            frameWidthPx = 160,
            frameHeightPx = 150,
            geometry = geometry,
            avoid = avoid,
        )
        assertEquals(PhoneOverlayMakeWayChoice(PhoneOverlaySide.LEFT, 1000), choice)
    }

    @Test
    fun `a make way moves to the vertical half farthest from the obstruction`() {
        val geometry = PhoneOverlayGeometry(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            topInsetPx = 0,
            bottomInsetPx = 0,
            sideMarginPx = 40,
            collapsedHeightPx = 150,
            expandedHeightPx = 900,
            expandedWidthPx = 800,
        )
        // Both edges at y≈1000 are covered; the obstruction sits high, so the bottom half wins.
        val avoid = listOf(PhoneRect(0, 960, 1080, 1200))
        val choice = overlayMakeWayPlacement(
            currentSide = PhoneOverlaySide.RIGHT,
            currentTopPx = 1000,
            frameWidthPx = 160,
            frameHeightPx = 150,
            geometry = geometry,
            avoid = avoid,
        )
        assertEquals(PhoneOverlaySide.RIGHT, choice?.side)
        // Lowest allowed top: 2400 - 0 - 150 - 40 = 2210.
        assertEquals(2210, choice?.offsetY)
    }

    @Test
    fun `a make way refuses when no placement avoids the whole screen`() {
        val geometry = PhoneOverlayGeometry(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            topInsetPx = 50,
            bottomInsetPx = 0,
            sideMarginPx = 40,
            collapsedHeightPx = 150,
            expandedHeightPx = 900,
            expandedWidthPx = 800,
        )
        assertNull(
            overlayMakeWayPlacement(
                currentSide = PhoneOverlaySide.RIGHT,
                currentTopPx = 1000,
                frameWidthPx = 160,
                frameHeightPx = 150,
                geometry = geometry,
                avoid = listOf(PhoneRect(0, 0, 1080, 2400)),
            ),
        )
        assertNull(
            "an empty avoid list is not a reason to move anything",
            overlayMakeWayPlacement(
                currentSide = PhoneOverlaySide.RIGHT,
                currentTopPx = 1000,
                frameWidthPx = 160,
                frameHeightPx = 150,
                geometry = geometry,
                avoid = emptyList(),
            ),
        )
    }

    @Test
    fun `a make way never predicts a frame outside the display`() {
        val geometry = PhoneOverlayGeometry(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            topInsetPx = 0,
            bottomInsetPx = 0,
            sideMarginPx = 40,
            collapsedHeightPx = 150,
            expandedHeightPx = 900,
            expandedWidthPx = 800,
        )
        // A frame as wide as the display cannot sit on an edge with the side margin: no candidate.
        assertNull(
            overlayMakeWayPlacement(
                currentSide = PhoneOverlaySide.RIGHT,
                currentTopPx = 1000,
                frameWidthPx = 1080,
                frameHeightPx = 150,
                geometry = geometry,
                avoid = listOf(PhoneRect(0, 900, 1080, 1300)),
            ),
        )
    }

}
