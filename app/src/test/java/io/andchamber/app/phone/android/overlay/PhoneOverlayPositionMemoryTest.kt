package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.android.PhoneProcessNonce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Position memory is geometry only. Rotating back restores the person's own posture; a takeover or a
 * new task keeps the current native phase and only the placement is restored.
 */
class PhoneOverlayPositionMemoryTest {
    private val portrait =
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
    private val landscape =
        portrait.copy(
            screenWidthPx = 2400,
            screenHeightPx = 1080,
            topInsetPx = 60,
            bottomInsetPx = 40,
        )

    @Test
    fun `portrait right offset comes back after landscape left offset`() {
        val memory = PhoneOverlayPositionMemory()
        memory.remember(0, PhoneOverlaySide.RIGHT, 700)
        memory.remember(1, PhoneOverlaySide.LEFT, 200)

        assertEquals(PhoneOverlayPosition(PhoneOverlaySide.LEFT, 200), memory.forRotation(1))
        assertEquals(PhoneOverlayPosition(PhoneOverlaySide.RIGHT, 700), memory.forRotation(0))
        assertNull("an unvisited rotation has no posture", memory.forRotation(2))
    }

    @Test
    fun `a taken-over task keeps its phase while the position comes from memory`() {
        val memory = PhoneOverlayPositionMemory()
        memory.remember(0, PhoneOverlaySide.LEFT, 640)
        val takenOver =
            PhoneOverlayWindowState(
                ownerKey = "grant-9",
                generation = 4,
                revision = 12,
                phase = PhoneOverlayPhase.TAKEN_OVER,
                targetLabel = "Notes",
                summary = "write a note",
            )
        val remembered = memory.forRotation(0)!!
        val restored = takenOver.withDrag(remembered.side, remembered.offsetY)
        assertEquals("only geometry is restored", PhoneOverlayPhase.TAKEN_OVER, restored.phase)
        assertEquals("grant-9", restored.ownerKey)
        assertEquals(4, restored.generation)
        assertEquals("write a note", restored.summary)
        assertEquals(PhoneOverlaySide.LEFT, restored.side)
        assertEquals(640, restored.offsetY)
        assertTrue("old observations are invalidated by the new revision", restored.revision > takenOver.revision)
    }

    @Test
    fun `a restored posture is clamped to the new rotation's usable screen`() {
        val memory =PhoneOverlayPositionMemory()
        memory.remember(0, PhoneOverlaySide.RIGHT, 2_200)
        val state = PhoneOverlayWindowState(ownerKey = "grant-3", phase = PhoneOverlayPhase.RUNNING)
        val restored = state.withDrag(memory.forRotation(0)!!.side, memory.forRotation(0)!!.offsetY)
        val clamped = overlayReflow(restored, landscape)
        val maxTop = landscape.screenHeightPx - landscape.bottomInsetPx - landscape.collapsedHeightPx - landscape.sideMarginPx
        assertTrue(clamped.offsetY <= maxTop)
        assertTrue(clamped.offsetY >= landscape.topInsetPx + landscape.sideMarginPx)
    }

    @Test
    fun `impossible rotations are ignored and the memory stays bounded`() {
        val memory = PhoneOverlayPositionMemory(capacity = 2)
        memory.remember(4, PhoneOverlaySide.LEFT, 100)
        memory.remember(-1, PhoneOverlaySide.RIGHT, 100)
        assertEquals(0, memory.size)
        assertNull(memory.forRotation(4))

        memory.remember(0, PhoneOverlaySide.LEFT, 100)
        memory.remember(1, PhoneOverlaySide.RIGHT, 200)
        memory.remember(2, PhoneOverlaySide.LEFT, 300)
        assertEquals(2, memory.size)
        assertNull("the oldest rotation is evicted", memory.forRotation(0))
        assertEquals(PhoneOverlayPosition(PhoneOverlaySide.RIGHT, 200), memory.forRotation(1))
        assertEquals(PhoneOverlayPosition(PhoneOverlaySide.LEFT, 300), memory.forRotation(2))
    }

    @Test
    fun `a configuration callback only acts for the current service and task owner`() {
        val current =
            PhoneOverlayDisplayOwner.mint(PhoneProcessNonce.value, 3L, "grant-7:5")
        assertTrue(overlayConfigurationOwnerAllowed(current, current))
        assertFalse("an older service instance is stale", overlayConfigurationOwnerAllowed(
            PhoneOverlayDisplayOwner.mint(PhoneProcessNonce.value, 2L, "grant-7:5"), current,
        ))
        assertFalse("a previous task owner is stale", overlayConfigurationOwnerAllowed(
            PhoneOverlayDisplayOwner.mint(PhoneProcessNonce.value, 3L, "grant-6:4"), current,
        ))
        assertFalse("another process's intent is refused", overlayConfigurationOwnerAllowed(
            PhoneOverlayDisplayOwner.mint("b".repeat(32), 3L, "grant-7:5"), current,
        ))
        assertFalse(overlayConfigurationOwnerAllowed(null, current))
        assertFalse(overlayConfigurationOwnerAllowed(current, null))
    }
}
