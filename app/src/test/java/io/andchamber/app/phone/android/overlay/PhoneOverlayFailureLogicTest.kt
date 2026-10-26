package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.overlay.progress.PhoneTaskState
import io.andchamber.app.phone.overlay.safety.OverlayPhase
import io.andchamber.app.ui.phone.PhoneHaltReason
import io.andchamber.app.ui.phone.PhoneStopChannel
import io.andchamber.app.ui.phone.overlay.OverlayStopReason
import io.andchamber.app.ui.phone.overlay.OverlaySurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failed overlay surface stops honestly: it never pretends to be a person's take-over. */
class PhoneOverlayFailureLogicTest {
    @Test
    fun `a read restore timeout keeps the stopped retained state and says unreachable`() {
        val halt = phoneHaltReasonFor(PhoneOverlayFailure.READ_RESTORE_TIMEOUT)
        assertEquals(PhoneHaltReason.ScreenUncertain, halt)
        assertEquals(PhoneOverlayStopReason.OverlayUnreachable, overlayStopReasonFor(halt, null))

        assertEquals(
            PhoneOverlayPhase.UNKNOWN,
            overlayFailurePhase(
                PhoneOverlayPhase.RUNNING,
                retainedTask = true,
                stopReceipt = true,
                hadLiveLease = true,
            ),
        )
        assertTrue(overlayTaskIsRetained(PhoneTaskState.ACTIVE))
        assertTrue(overlayTaskIsRetained(PhoneTaskState.UNKNOWN_OUTCOME))
        assertTrue(overlayFailureMayShowStopped(PhoneOverlayFailure.READ_RESTORE_TIMEOUT))

        // A real confirmed hand-over already on screen is preserved, never rewound to RUNNING.
        assertEquals(
            PhoneOverlayPhase.WAITING_USER,
            overlayFailurePhase(
                PhoneOverlayPhase.WAITING_USER,
                retainedTask = true,
                stopReceipt = false,
                hadLiveLease = false,
            ),
        )
    }

    @Test
    fun `failure reasons are stop-channel or screen-uncertain and never a take-over`() {
        PhoneOverlayFailure.entries.forEach { failure ->
            assertFalse(failure.name, phoneHaltReasonFor(failure) == PhoneHaltReason.TakenOver)
        }
        assertEquals(
            PhoneHaltReason.StopChannelLost,
            phoneHaltReasonFor(PhoneOverlayFailure.PERMISSION_REVOKED),
        )
        assertEquals(
            PhoneHaltReason.StopChannelLost,
            phoneHaltReasonFor(PhoneOverlayFailure.DISPLAY_SERVICE_LOST),
        )
        assertEquals(
            PhoneHaltReason.StopChannelLost,
            phoneHaltReasonFor(PhoneOverlayFailure.NOTIFICATION_LOST),
        )
        assertEquals(
            PhoneHaltReason.ScreenUncertain,
            phoneHaltReasonFor(PhoneOverlayFailure.ATTACH_FAILED),
        )
        assertEquals(
            PhoneHaltReason.ScreenUncertain,
            phoneHaltReasonFor(PhoneOverlayFailure.VISIBILITY_UNCONFIRMED),
        )
        // A live-looking card must not keep claiming RUNNING after its stop was unconfirmed.
        assertEquals(
            PhoneOverlayPhase.UNKNOWN,
            overlayFailurePhase(
                PhoneOverlayPhase.RUNNING,
                retainedTask = false,
                stopReceipt = false,
                hadLiveLease = true,
            ),
        )
        // Only a real receipt for a live lease may say the agent was taken over.
        assertEquals(
            PhoneOverlayPhase.TAKEN_OVER,
            overlayFailurePhase(
                PhoneOverlayPhase.RUNNING,
                retainedTask = false,
                stopReceipt = true,
                hadLiveLease = true,
            ),
        )
        // A stale live-looking card without any lease is unknown, not a confirmed hand-over.
        assertEquals(
            PhoneOverlayPhase.UNKNOWN,
            overlayFailurePhase(
                PhoneOverlayPhase.RUNNING,
                retainedTask = false,
                stopReceipt = false,
                hadLiveLease = false,
            ),
        )
        // While a live lease is not confirmed revoked, even an old hand-over is uncertain.
        assertEquals(
            PhoneOverlayPhase.UNKNOWN,
            overlayFailurePhase(
                PhoneOverlayPhase.WAITING_USER,
                retainedTask = true,
                stopReceipt = false,
                hadLiveLease = true,
            ),
        )
        // An earlier confirmed hand-over stays visible when no new live lease needed stopping.
        assertEquals(
            PhoneOverlayPhase.TAKEN_OVER,
            overlayFailurePhase(
                PhoneOverlayPhase.TAKEN_OVER,
                retainedTask = false,
                stopReceipt = false,
                hadLiveLease = false,
            ),
        )
    }

    @Test
    fun `native halt reasons map to the visible stop explanation`() {
        assertEquals(PhoneOverlayStopReason.Expired, overlayStopReasonFor(PhoneHaltReason.Expired, null))
        assertEquals(PhoneOverlayStopReason.NativeOpened, overlayStopReasonFor(PhoneHaltReason.HostReturned, null))
        assertEquals(PhoneOverlayStopReason.TargetLeft, overlayStopReasonFor(PhoneHaltReason.TargetLeft, null))
        assertEquals(PhoneOverlayStopReason.ScreenLocked, overlayStopReasonFor(PhoneHaltReason.ScreenLocked, null))
        assertEquals(PhoneOverlayStopReason.ConnectionLost, overlayStopReasonFor(PhoneHaltReason.ConnectionLost, null))
        assertEquals(PhoneOverlayStopReason.RuntimeChanged, overlayStopReasonFor(PhoneHaltReason.RuntimeChanged, null))
        assertEquals(
            PhoneOverlayStopReason.OverlayUnreachable,
            overlayStopReasonFor(PhoneHaltReason.StopChannelLost, null),
        )
        assertEquals(
            PhoneOverlayStopReason.OverlayUnreachable,
            overlayStopReasonFor(PhoneHaltReason.ScreenUncertain, null),
        )
        assertEquals(
            PhoneOverlayStopReason.AgentAskedForYou,
            overlayStopReasonFor(PhoneHaltReason.TakenOver, PhoneTaskState.WAITING_USER),
        )
        // A plain pause / person take-over has no "why it stopped" badge.
        assertNull(overlayStopReasonFor(PhoneHaltReason.Paused, PhoneTaskState.ACTIVE))
        assertNull(overlayStopReasonFor(PhoneHaltReason.TakenOver, PhoneTaskState.ACTIVE))
    }

    @Test
    fun `opening the chat or the continue confirmation never revokes the lease`() {
        assertFalse(overlayEntryRevokesLease(PhoneOverlayEntry.OpenChat))
        assertFalse(overlayEntryRevokesLease(PhoneOverlayEntry.ContinueInChat))
        assertTrue(overlayEntryRevokesLease(PhoneOverlayEntry.ReturnToNative))
    }

    @Test
    fun `continue in chat needs a native halted screen and a hand-over phase`() {
        assertFalse(overlayCanContinueInChat(screen = null, phase = PhoneOverlayPhase.TAKEN_OVER))
        assertFalse(overlayCanContinueInChat(screen = null, phase = PhoneOverlayPhase.UNKNOWN))
    }

    @Test
    fun `moved to notification is claimed only when that notification really exists`() {
        assertEquals(
            OverlaySurfaceMode.MovedToNotification,
            overlaySurfaceAfterFailure(PhoneOverlayFailure.ATTACH_FAILED, notificationLive = true),
        )
        assertNull(overlaySurfaceAfterFailure(PhoneOverlayFailure.ATTACH_FAILED, notificationLive = false))
        assertNull(overlaySurfaceAfterFailure(PhoneOverlayFailure.NOTIFICATION_LOST, notificationLive = true))
        assertNull(overlaySurfaceAfterFailure(PhoneOverlayFailure.DISPLAY_SERVICE_LOST, notificationLive = true))
    }

    @Test
    fun `the fallback entry needs both a service owner and a verified stop channel`() {
        assertTrue(verifiedNotificationFallback("owner", PhoneStopChannel.Verified))
        assertFalse(verifiedNotificationFallback(null, PhoneStopChannel.Verified))
        assertFalse(verifiedNotificationFallback("owner", PhoneStopChannel.Unknown))
        assertFalse(verifiedNotificationFallback("owner", PhoneStopChannel.NotificationsDisabled))
        assertFalse(verifiedNotificationFallback("owner", PhoneStopChannel.ChannelDisabled))
    }

    @Test
    fun `a timed out read from an old owner never touches the new owner`() {
        val old = PhoneOverlayReadLease("grant-1", generation = 2, revision = 7, phase = OverlayPhase.CAPTURE_HIDDEN)
        assertTrue(overlayReadTimeoutStillOwns(old, liveEpoch = 1, ownerKey = "grant-1", generation = 2))
        // A newer lease/generation is not this read's owner.
        assertFalse(overlayReadTimeoutStillOwns(old, liveEpoch = 2, ownerKey = "grant-2", generation = 3))
        assertFalse(overlayReadTimeoutStillOwns(old, liveEpoch = null, ownerKey = "grant-1", generation = 3))
        // A failed attach is not retried until a real new authority arrives.
        assertFalse(overlayAttachMayRetry(suppressed = true, newAuthority = false))
        assertTrue(overlayAttachMayRetry(suppressed = true, newAuthority = true))
    }

    @Test
    fun `an ended task is not retained`() {
        assertFalse(overlayTaskIsRetained(PhoneTaskState.ENDED_PENDING_CHECK))
        assertFalse(overlayTaskIsRetained(null))
        assertEquals(
            OverlayStopReason.OverlayUnreachable,
            PhoneOverlayStopReason.OverlayUnreachable.toUiStopReason(),
        )
    }
}
