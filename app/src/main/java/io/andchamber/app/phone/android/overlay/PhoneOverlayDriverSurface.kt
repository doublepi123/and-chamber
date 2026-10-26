package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.overlay.safety.OverlayPhase

/**
 * What the first driver read should do while waiting for the manager's surface.
 *
 * [READY] only means the manager intends to show exactly this epoch's window and its own barrier
 * reports VISIBLE. It is not proof of visibility: the driver still runs its independent witness.
 * [STALE] means the epoch/owner changed and the caller must refuse immediately.
 */
internal enum class OverlayDriverSurfaceGate { NOT_REQUIRED, READY, WAITING, STALE }

/**
 * Pure gate for [PhoneOverlayCoordinator.awaitDriverSurface].
 *
 * Notification-only needs no overlay. For a selected overlay, only the exact epoch's current grant
 * owner may become ready; a different grant owner or a changed live epoch is stale. A not-yet-bound
 * owner (`retained`/empty) is still waiting for the manager, not stale.
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
        ownerKey.startsWith(GRANT_OWNER_PREFIX) && ownerKey != grantOwner(epoch) ->
            OverlayDriverSurfaceGate.STALE

        hostVisible || !surfaceShown || hiddenForRead -> OverlayDriverSurfaceGate.WAITING
        ownerKey != grantOwner(epoch) -> OverlayDriverSurfaceGate.WAITING
        phase != OverlayPhase.VISIBLE -> OverlayDriverSurfaceGate.WAITING
        else -> OverlayDriverSurfaceGate.READY
    }

internal fun grantOwner(epoch: Long): String = "$GRANT_OWNER_PREFIX$epoch"

private const val GRANT_OWNER_PREFIX = "grant-"
