package io.andchamber.app.phone.android.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry and read-hide ownership are manager state, not composition. These deterministic cases pin
 * the transitions that keep an old observation or an old read from reviving a newer window.
 */
class PhoneOverlayWindowStateTest {
    private val geometry =
        PhoneOverlayGeometry(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            topInsetPx = 100,
            bottomInsetPx = 80,
            sideMarginPx = 40,
            collapsedHeightPx = 150,
            expandedHeightPx = 900,
            expandedWidthPx = 800,
        )

    private fun base() =
        PhoneOverlayWindowState(
            ownerKey = "grant-7",
            generation = 2,
            revision = 5,
            phase = PhoneOverlayPhase.RUNNING,
            targetLabel = "Notes",
            summary = "write a note",
            leaseEndsAtMs = 99_000,
        )

    @Test
    fun `expand, collapse and end confirmation bump the revision and belong to the manager`() {
        val expanded = base().withExpanded(true)
        assertTrue(expanded.expanded)
        assertEquals(6, expanded.revision)

        val confirming = expanded.withEndConfirm(confirming = true)
        assertTrue(confirming.endConfirm)
        assertTrue(confirming.expanded)
        assertEquals(7, confirming.revision)

        val cancelled = confirming.withEndConfirm(confirming = false)
        assertFalse(cancelled.endConfirm)
        assertEquals(8, cancelled.revision)

        val collapsed = cancelled.withExpanded(false)
        assertFalse(collapsed.expanded)
        assertFalse("collapsing drops a stale end confirmation", collapsed.endConfirm)
        assertEquals(9, collapsed.revision)
    }

    @Test
    fun `side moves and drags bump the revision, a repeated side is a no-op`() {
        val left = base().withSide(PhoneOverlaySide.LEFT)
        assertEquals(PhoneOverlaySide.LEFT, left.side)
        assertEquals(6, left.revision)
        assertEquals("same edge changes nothing", left, left.withSide(PhoneOverlaySide.LEFT))

        val dragged = left.withDrag(PhoneOverlaySide.RIGHT, 500)
        assertEquals(PhoneOverlaySide.RIGHT, dragged.side)
        assertEquals(500, dragged.offsetY)
        assertEquals(7, dragged.revision)
    }

    @Test
    fun `a phase change bumps content revision but never the display generation`() {
        val paused = base().withPhase(PhoneOverlayPhase.PAUSED)
        assertEquals(PhoneOverlayPhase.PAUSED, paused.phase)
        assertEquals(6, paused.revision)
        assertEquals("a phase change keeps the display owner", 2, paused.generation)
        assertEquals("same content is a no-op", paused, paused.withPhase(PhoneOverlayPhase.PAUSED))

        val newOwner = paused.withNewOwner("grant-8")
        assertEquals(3, newOwner.generation)
        assertEquals("grant-8", newOwner.ownerKey)
        assertFalse(newOwner.expanded)
        assertFalse(newOwner.endConfirm)
        assertEquals(0, newOwner.offsetY)
    }

    @Test
    fun `the window title is the stable token across revisions and follows the generation`() {
        val base = base()
        val title = base.windowTitle
        assertEquals(title, base.withExpanded(true).withSide(PhoneOverlaySide.LEFT).windowTitle)
        assertTrue(base.withNewOwner("grant-7").windowTitle != title)
        assertEquals("andchamber-phone-overlay:grant-7:2", title)
    }

    @Test
    fun `placement clamps the collapsed window and bounds the expanded panel`() {
        val collapsed = overlayPlacement(base().withDrag(PhoneOverlaySide.RIGHT, 9_000), geometry)
        assertEquals("collapsed uses wrap content", 0, collapsed.widthPx)
        assertEquals(geometry.collapsedHeightPx, collapsed.heightPx)
        assertEquals(2400 - 80 - 150 - 40, collapsed.topPx)
        assertEquals(geometry.sideMarginPx, collapsed.horizontalMarginPx)

        val negative = overlayPlacement(base().withDrag(PhoneOverlaySide.LEFT, -500), geometry)
        assertEquals(geometry.topInsetPx + geometry.sideMarginPx, negative.topPx)

        val expanded = overlayPlacement(base().withExpanded(true), geometry)
        assertEquals(800, expanded.widthPx)
        assertEquals(900, expanded.heightPx)
    }

    @Test
    fun `a drop on the bottom end zone asks for end confirmation, elsewhere it snaps`() {
        val endZone = overlayDragRelease(base(), dragX = 100f, dragY = 2_350f, geometry)
        assertTrue("a release near the bottom arms the end confirm", endZone.endConfirm)
        assertTrue(endZone.expanded)

        val snapped = overlayDragRelease(base(), dragX = 200f, dragY = 800f, geometry)
        assertFalse(snapped.endConfirm)
        assertEquals(PhoneOverlaySide.LEFT, snapped.side)

        val paused = overlayDragRelease(base().withPhase(PhoneOverlayPhase.PAUSED), 100f, 2_350f, geometry)
        assertFalse("only a running task can be ended from the drop zone", paused.endConfirm)
    }

    @Test
    fun `rotation or an IME inset change reflows the window back inside`() {
        val farDown = base().withDrag(PhoneOverlaySide.RIGHT, 2_300)
        val reflowed = overlayReflow(farDown, geometry)
        assertTrue(reflowed.offsetY <= 2400 - 80 - 150 - 40)
    }

    @Test
    fun `a read hide is refused unless exactly the attached owner, generation and revision match`() {
        val state = base()
        assertNull(
            "the exact current display may be hidden",
            overlayHideRefusal("grant-7", 2, 5, state, attached = true, hiddenForRead = false),
        )
        assertEquals(
            PhoneOverlayHideRefusal.NOT_ATTACHED,
            overlayHideRefusal("grant-7", 2, 5, state, attached = false, hiddenForRead = false),
        )
        assertEquals(
            PhoneOverlayHideRefusal.HIDDEN_FOR_READ,
            overlayHideRefusal("grant-7", 2, 5, state, attached = true, hiddenForRead = true),
        )
        assertEquals(
            PhoneOverlayHideRefusal.OWNER_CHANGED,
            overlayHideRefusal("grant-8", 2, 5, state, attached = true, hiddenForRead = false),
        )
        assertEquals(
            PhoneOverlayHideRefusal.GENERATION_CHANGED,
            overlayHideRefusal("grant-7", 3, 5, state, attached = true, hiddenForRead = false),
        )
        assertEquals(
            PhoneOverlayHideRefusal.REVISION_CHANGED,
            overlayHideRefusal("grant-7", 2, 6, state, attached = true, hiddenForRead = false),
        )
    }

    @Test
    fun `a read restores the latest authority for the same owner and generation`() {
        val state = base()
        assertNull(
            "the same display owner/read may restore",
            overlayRestoreRefusal("grant-7", 2, state, attached = false, detached = false),
        )
        assertEquals(
            PhoneOverlayHideRefusal.NOT_ATTACHED,
            overlayRestoreRefusal("grant-7", 2, state, attached = false, detached = true),
        )
        // A control/geometry change during the read is restored at its current value, never the
        // old RUNNING snapshot. The old read is only owner/generation-bound.
        val paused = state.withPhase(PhoneOverlayPhase.PAUSED)
        assertNull(
            "a newer phase is restored at its latest value",
            overlayRestoreRefusal("grant-7", 2, paused, attached = false, detached = false),
        )
        val takenOver = state.withPhase(PhoneOverlayPhase.TAKEN_OVER)
        assertNull(
            "a takeover during the read is restored at its latest stop phase",
            overlayRestoreRefusal("grant-7", 2, takenOver, attached = false, detached = false),
        )
        val newOwner = state.withNewOwner("grant-8")
        assertEquals(
            PhoneOverlayHideRefusal.OWNER_CHANGED,
            overlayRestoreRefusal("grant-7", 2, newOwner, attached = false, detached = false),
        )
        val newGeneration = state.copy(generation = 3)
        assertEquals(
            PhoneOverlayHideRefusal.GENERATION_CHANGED,
            overlayRestoreRefusal("grant-7", 2, newGeneration, attached = false, detached = false),
        )
    }

    @Test
    fun `reflow applies geometry only and never writes old control facts back`() {
        val paused = base().withPhase(PhoneOverlayPhase.PAUSED)
        val oldRunningReflow = base().withDrag(PhoneOverlaySide.LEFT, 300)
        val updated = overlayGeometryUpdate(paused, oldRunningReflow)

        assertEquals("reflow must not revive RUNNING", PhoneOverlayPhase.PAUSED, updated.phase)
        assertEquals(paused.targetLabel, updated.targetLabel)
        assertEquals(paused.summary, updated.summary)
        assertEquals(paused.generation, updated.generation)
        assertEquals(PhoneOverlaySide.LEFT, updated.side)
        assertEquals(300, updated.offsetY)
    }

    @Test
    fun `the demo answer is only accepted after the tap reached native`() {
        assertFalse("confirming while only Showing is refused", demoConfirmationAllowed(tapReceived = false))
        assertTrue(demoConfirmationAllowed(tapReceived = true))
    }
}
