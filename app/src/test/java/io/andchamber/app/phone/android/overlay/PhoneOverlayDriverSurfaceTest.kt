package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.overlay.safety.OverlayPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/** The driver's first-read wait is a manager attach gate, never a visibility claim. */
class PhoneOverlayDriverSurfaceTest {
    @Test
    fun `notification only needs no overlay surface`() {
        assertEquals(
            OverlayDriverSurfaceGate.NOT_REQUIRED,
            gate(overlaySelected = false),
        )
    }

    @Test
    fun `host visible is not ready yet`() {
        assertEquals(
            OverlayDriverSurfaceGate.WAITING,
            gate(hostVisible = true),
        )
    }

    @Test
    fun `a different display owner waits while the live epoch still belongs to this start`() {
        assertEquals(
            OverlayDriverSurfaceGate.WAITING,
            gate(ownerKey = "grant-99"),
        )
        assertEquals(OverlayDriverSurfaceGate.WAITING, gate(ownerKey = "grant-5"))
        assertEquals(OverlayDriverSurfaceGate.READY, gate(ownerKey = "grant-7"))
    }

    @Test
    fun `a changed live epoch is stale immediately`() {
        assertEquals(
            OverlayDriverSurfaceGate.STALE,
            gate(liveEpoch = 8),
        )
        assertEquals(
            OverlayDriverSurfaceGate.STALE,
            gate(liveEpoch = null),
        )
        assertEquals(OverlayDriverSurfaceGate.STALE, gate(liveEpoch = 8, ownerKey = "grant-5"))
    }

    @Test
    fun `a read-hidden window is not ready`() {
        assertEquals(
            OverlayDriverSurfaceGate.WAITING,
            gate(hiddenForRead = true),
        )
    }

    @Test
    fun `an unattached or unbound owner waits for the manager`() {
        assertEquals(
            OverlayDriverSurfaceGate.WAITING,
            gate(surfaceShown = false),
        )
        assertEquals(
            OverlayDriverSurfaceGate.WAITING,
            gate(ownerKey = "retained"),
        )
    }

    @Test
    fun `only the exact epoch with a visible barrier is ready`() {
        assertEquals(OverlayDriverSurfaceGate.READY, gate())
        assertEquals(
            OverlayDriverSurfaceGate.WAITING,
            gate(phase = OverlayPhase.UNAVAILABLE),
        )
        assertEquals(
            OverlayDriverSurfaceGate.WAITING,
            gate(phase = OverlayPhase.CAPTURE_HIDDEN),
        )
    }

    private fun gate(
        overlaySelected: Boolean = true,
        epoch: Long = 7,
        liveEpoch: Long? = 7,
        ownerKey: String = "grant-7",
        hostVisible: Boolean = false,
        surfaceShown: Boolean = true,
        hiddenForRead: Boolean = false,
        phase: OverlayPhase = OverlayPhase.VISIBLE,
    ): OverlayDriverSurfaceGate =
        overlayDriverSurfaceGate(
            overlaySelected = overlaySelected,
            epoch = epoch,
            liveEpoch = liveEpoch,
            ownerKey = ownerKey,
            hostVisible = hostVisible,
            surfaceShown = surfaceShown,
            hiddenForRead = hiddenForRead,
            phase = phase,
        )
}
