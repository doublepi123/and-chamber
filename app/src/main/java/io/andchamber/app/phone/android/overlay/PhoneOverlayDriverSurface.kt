package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.overlay.safety.OverlayPhase

/**
 * What the first driver read should do while waiting for the manager's surface.
 *
 * [READY] only means the manager intends to show exactly this epoch's window and its own barrier
 * reports VISIBLE. It is not proof of visibility: the driver still runs its independent witness.
 * [STALE] means the live epoch changed and the caller must refuse immediately.
 */
internal enum class OverlayDriverSurfaceGate { NOT_REQUIRED, READY, WAITING, STALE }

/**
 * Pure gate for [PhoneOverlayCoordinator.awaitDriverSurface].
 *
 * Notification-only needs no overlay. For a selected overlay, only the exact epoch's current grant
 * owner may become ready. A changed live epoch is stale; a different display owner only waits,
 * since the Main collector may not have projected the new grant yet. It never makes an old window
 * ready for a new task.
 */
internal fun overlayDriverSurfaceGate(
    overlaySelected: Boolean,
    epoch: Long,
    liveEpoch: Long?,
    ownerKey: String,
    hostVisible: Boolean,
    surfaceShown: Boolean,
    hiddenForRead: Boolean,
    phase: OverlayPhase,
): OverlayDriverSurfaceGate =
    when {
        !overlaySelected -> OverlayDriverSurfaceGate.NOT_REQUIRED
        liveEpoch != epoch -> OverlayDriverSurfaceGate.STALE
        hostVisible || !surfaceShown || hiddenForRead -> OverlayDriverSurfaceGate.WAITING
        ownerKey != grantOwner(epoch) -> OverlayDriverSurfaceGate.WAITING
        phase != OverlayPhase.VISIBLE -> OverlayDriverSurfaceGate.WAITING
        else -> OverlayDriverSurfaceGate.READY
    }

internal fun grantOwner(epoch: Long): String = "$GRANT_OWNER_PREFIX$epoch"

private const val GRANT_OWNER_PREFIX = "grant-"
