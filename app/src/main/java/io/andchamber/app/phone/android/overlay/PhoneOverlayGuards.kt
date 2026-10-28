package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.core.PhoneRect
import io.andchamber.app.phone.overlay.safety.OverlayPhase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether a window may be attached at all: overlay mode is effective (permission held) and the
 * designer content exists. Permission is still only a permission; this says "allowed to try".
 */
internal fun overlayMayAttach(
    contentAvailable: Boolean,
    requested: PhoneOverlayMode,
    gate: PhoneOverlayGate,
): Boolean = contentAvailable && effectiveOverlayMode(requested, gate) == PhoneOverlayMode.OVERLAY

/**
 * Whether a live lease must fail closed because the task selected Overlay but its stop surface
 * cannot exist. Only a selected overlay fails closed here; a default/explicit notification-only
 * grant keeps its native notification entry and must not be revoked for a missing overlay window.
 * An attach/remove failure while selected overlay is separately failed closed by the coordinator.
 */
internal fun overlayLeaseMustFailClosed(
    hasLease: Boolean,
    mode: PhoneOverlayMode,
    contentAvailable: Boolean,
    gate: PhoneOverlayGate,
): Boolean =
    hasLease && mode == PhoneOverlayMode.OVERLAY &&
        !overlayMayAttach(contentAvailable, mode, gate)

/**
 * A native explicit switch away from Overlay while a lease is live is a stop, not a silent
 * fallback. The coordinator must revoke first; only then may it persist notification-only.
 */
internal fun overlayModeChangeRequiresStop(
    current: PhoneOverlayMode,
    requested: PhoneOverlayMode,
    hasLease: Boolean,
): Boolean =
    hasLease && current == PhoneOverlayMode.OVERLAY && requested != PhoneOverlayMode.OVERLAY

/** One bounded driver read hide, identified by the exact authority it froze. */
internal data class PhoneOverlayReadLease(
    val owner: String,
    val generation: Long,
    val revision: Long,
    val phase: OverlayPhase,
)

/**
 * Covers the whole hide-scheduling critical section. [hide] is inside the try, so a cancellation
 * after the main thread removed the window but before [hide] could return still runs [restore].
 * [restore] must be owner-bound, bounded and non-cancellable internally so it runs even when this
 * call is already cancelled; it may be a no-op when no window was actually removed. A refused hide
 * throws before [block]; a cancelled/timed-out read never turns into a success.
 */
internal suspend fun <T> withOverlayReadHide(
    lease: PhoneOverlayReadLease,
    hide: suspend () -> Boolean,
    restore: suspend (PhoneOverlayReadLease) -> Unit,
    block: suspend () -> T,
): T {
    try {
        if (!hide()) throw PhoneOverlayHideRefused(PhoneOverlayHideRefusal.NOT_ATTACHED)
        return block()
    } finally {
        restore(lease)
    }
}

/**
 * Main-thread/IO ticket state for a read hide. [uncertainLease] is set when the bounded Main
 * restore did not finish in time; expectation must be UNAVAILABLE until the exact ticket is
 * settled. A newer ticket or normal restore wins without being cleared by an old timeout.
 */
internal class PhoneOverlayReadHideTickets {
    @Volatile
    private var active: PhoneOverlayReadLease? = null

    @Volatile
    private var uncertain: PhoneOverlayReadLease? = null

    @get:Synchronized
    val uncertainLease: PhoneOverlayReadLease? get() = uncertain

    @Synchronized
    fun activeLease(): PhoneOverlayReadLease? = active

    @Synchronized
    fun install(lease: PhoneOverlayReadLease) {
        active = lease
        uncertain = null
    }

    /** Normal restore won: clear this exact ticket and any uncertainty it had. */
    @Synchronized
    fun settle(lease: PhoneOverlayReadLease): Boolean {
        if (active !== lease) return false
        active = null
        if (uncertain === lease) uncertain = null
        return true
    }

    /** Main restore timed out. Mark only the still-active ticket; never a newer one. */
    @Synchronized
    fun markTimedOut(lease: PhoneOverlayReadLease): Boolean {
        if (active !== lease) return false
        uncertain = lease
        return true
    }

    /**
     * Independent Main cleanup after the timeout. Returns the exact ticket only when it still
     * owned the hidden window; a newer ticket is left untouched.
     */
    @Synchronized
    fun settleTimedOut(lease: PhoneOverlayReadLease): PhoneOverlayReadLease? {
        if (uncertain !== lease) return null
        uncertain = null
        if (active !== lease) return null
        active = null
        return lease
    }

    @Synchronized
    fun clear() {
        active = null
        uncertain = null
    }
}

/**
 * Bounded restore scheduling. A timeout marks the ticket uncertain and invokes [onTimeout] (which
 * must only post independent Main cleanup, never block waiting for Main). The caller's cancelled
 * scope cannot suppress this post; the read is not reported as stopped or rolled back.
 */
internal suspend fun settleReadHideWithTimeout(
    lease: PhoneOverlayReadLease,
    tickets: PhoneOverlayReadHideTickets,
    timeoutMs: Long = 1_000L,
    restore: suspend () -> Unit,
    onTimeout: () -> Unit,
): Boolean {
    val settled =
        withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMs) {
                restore()
                true
            } ?: false
        }
    if (!settled) {
        if (tickets.markTimedOut(lease)) onTimeout()
    }
    return settled
}

/**
 * Production frame receipt for a restored read. The ticket/owner/generation/current view must all
 * still match before and after the injected first-frame wait. The caller renders the latest state
 * and calls this again when control facts changed during the wait.
 */
internal suspend fun awaitCurrentReadFrame(
    lease: PhoneOverlayReadLease,
    tickets: PhoneOverlayReadHideTickets,
    ownerKey: () -> String,
    generation: () -> Long,
    viewIsCurrent: () -> Boolean,
    awaitFrame: suspend () -> Unit,
): Boolean {
    if (tickets.activeLease() !== lease) return false
    if (!viewIsCurrent()) return false
    if (ownerKey() != lease.owner || generation() != lease.generation) return false
    awaitFrame()
    if (tickets.activeLease() !== lease) return false
    if (!viewIsCurrent()) return false
    return ownerKey() == lease.owner && generation() == lease.generation
}

/** A task snapshot speaks only for the live epoch, or for the exact display owner after it ends. */
internal fun overlayTaskSnapshotBelongsToDisplay(
    snapshotGrantEpoch: Long,
    liveEpoch: Long?,
    ownerKey: String,
): Boolean =
    if (liveEpoch != null) {
        snapshotGrantEpoch == liveEpoch
    } else {
        ownerKey == "grant-$snapshotGrantEpoch"
    }

/** A timed-out read may fail closed only if its exact owner/generation is still current. */
internal fun overlayReadTimeoutStillOwns(
    lease: PhoneOverlayReadLease,
    liveEpoch: Long?,
    ownerKey: String,
    generation: Long,
): Boolean =
    lease.owner == (liveEpoch?.let { "grant-$it" } ?: ownerKey) &&
        lease.generation == generation

/**
 * Geometry-only tick application. A reflow may move the capsule but must never write old phase,
 * label, summary or lease facts back over a newer native-authority state.
 */
internal fun overlayGeometryUpdate(
    current: PhoneOverlayWindowState,
    reflowed: PhoneOverlayWindowState,
): PhoneOverlayWindowState =
    if (current.side == reflowed.side && current.offsetY == reflowed.offsetY) {
        current
    } else {
        current.withDrag(reflowed.side, reflowed.offsetY)
    }

/**
 * One automatic occlusion escape chosen by [overlayMakeWayPlacement]. It is layout only: never a
 * grant, a stop, a reminder of the person's posture, or a visibility claim.
 */
internal data class PhoneOverlayMakeWayChoice(
    val side: PhoneOverlaySide,
    val offsetY: Int,
)

/**
 * Pure placement choice for the driver's automatic "move out of the way" request.
 *
 * The predicted capsule frame is [frameWidthPx] x [frameHeightPx] at the clamped position for each
 * candidate (the same [overlayPlacement] clamp the live window uses). Candidates, in order:
 * 1. the opposite edge at the current height (usually enough and keeps the vertical posture);
 * 2. the same edge at the vertical half farthest from the avoided rectangles;
 * 3. the opposite edge at that same far half.
 *
 * A candidate is accepted only when its predicted frame lies inside the display and intersects none
 * of [avoid]. `null` means no candidate is clear; the driver then refuses the action instead of
 * injecting. Nothing here reads a window, grants or persists anything.
 */
internal fun overlayMakeWayPlacement(
    currentSide: PhoneOverlaySide,
    currentTopPx: Int,
    frameWidthPx: Int,
    frameHeightPx: Int,
    geometry: PhoneOverlayGeometry,
    avoid: List<PhoneRect>,
): PhoneOverlayMakeWayChoice? {
    if (avoid.isEmpty()) return null
    val width = frameWidthPx.coerceIn(1, geometry.screenWidthPx)
    val height = frameHeightPx.coerceIn(1, geometry.screenHeightPx)

    fun clampedTop(offsetY: Int): Int =
        overlayPlacement(
            PhoneOverlayWindowState(
                ownerKey = MAKE_WAY_PLACEMENT_OWNER,
                expanded = false,
                offsetY = offsetY,
            ),
            geometry,
        ).topPx

    fun frame(side: PhoneOverlaySide, topPx: Int): PhoneRect {
        val left =
            if (side == PhoneOverlaySide.LEFT) {
                geometry.sideMarginPx
            } else {
                geometry.screenWidthPx - geometry.sideMarginPx - width
            }
        return PhoneRect(left, topPx, left + width, topPx + height)
    }

    fun clear(side: PhoneOverlaySide, topPx: Int): Boolean {
        val candidate = frame(side, topPx)
        if (candidate.left < 0 || candidate.right > geometry.screenWidthPx) return false
        return avoid.none { overlayRectsIntersect(candidate, it) }
    }

    val opposite =
        if (currentSide == PhoneOverlaySide.LEFT) PhoneOverlaySide.RIGHT else PhoneOverlaySide.LEFT
    val sameHeight = clampedTop(currentTopPx)
    if (clear(opposite, sameHeight)) {
        return PhoneOverlayMakeWayChoice(opposite, sameHeight)
    }
    val lowestTop = clampedTop(Int.MAX_VALUE)
    val highestTop = clampedTop(Int.MIN_VALUE)
    val avoidCenterY = (avoid.minOf { it.top } + avoid.maxOf { it.bottom }) / 2
    val farTop = if (avoidCenterY <= (highestTop + lowestTop) / 2) lowestTop else highestTop
    if (clear(currentSide, farTop)) return PhoneOverlayMakeWayChoice(currentSide, farTop)
    if (clear(opposite, farTop)) return PhoneOverlayMakeWayChoice(opposite, farTop)
    return null
}

private const val MAKE_WAY_PLACEMENT_OWNER = "make-way"

/** Half-open rectangle intersection: touching edges do not count as covering. */
private fun overlayRectsIntersect(
    first: PhoneRect,
    second: PhoneRect,
): Boolean =
    first.left < second.right && second.left < first.right &&
        first.top < second.bottom && second.top < first.bottom

/**
 * Confirming "I saw it" is only accepted after the demo's take-over really reached the native side.
 * A person pressing confirm while the capsule is still merely Showing cannot unlock overlay mode.
 */
internal fun demoConfirmationAllowed(tapReceived: Boolean): Boolean = tapReceived

/** A demo owns its separate deadline; device uptime is not a task-retention clock. */
internal fun taskDisplayRetentionElapsed(lastAuthorityMs: Long, nowMs: Long, retentionMs: Long, demoActive: Boolean): Boolean =
    !demoActive && lastAuthorityMs > 0L && nowMs - lastAuthorityMs > retentionMs

/**
 * Overlay may only be selected after the person actually saw the demo capsule and confirmed it, and
 * while the draw-over grant is held. The demo reaches no native control, so this gate never confers
 * visibility or authority; it only stops a mode that was never demonstrated.
 */
internal fun overlayModeSelectionAllowed(
    requested: PhoneOverlayMode,
    gate: PhoneOverlayGate,
    demoSeen: Boolean,
): Boolean =
    requested != PhoneOverlayMode.OVERLAY || (gate == PhoneOverlayGate.READY && demoSeen)

/** The overlay's own phase. It never grants: every transition below is display or stop only. */
internal enum class PhoneOverlayPhase {
    /** No lease and no retained result to show. */
    IDLE,
    RUNNING,

    /** A pause was requested and has not settled; the overlay must not claim it stopped. */
    PAUSING,
    PAUSED,

    /** A native stop was requested but the controller has not confirmed it yet. */
    STOP_PENDING,

    /** The native facts are contradictory or a stop receipt was not confirmed. */
    UNKNOWN,

    /** A takeover stopped the agent; new reads/writes are disabled, sent commands are not rolled back. */
    TAKEN_OVER,

    /** Waiting for the person after a need_user event; the native side already stopped the lease. */
    WAITING_USER,

    /** Result card after a confirmed end; still shows the retained needs-check facts. */
    NEEDS_CHECK,
    ENDED,
}

/** Closing the display is allowed only after the task ended and no lease remains. */
internal fun overlayCloseAllowed(
    hasLease: Boolean,
    phase: PhoneOverlayPhase,
): Boolean = !hasLease && (phase == PhoneOverlayPhase.NEEDS_CHECK || phase == PhoneOverlayPhase.ENDED)

/** A stop request from the overlay never resumes anything; it may only stop or go back to native. */
internal enum class PhoneOverlayStopRequest {
    /** Refuse new actions and revoke the whole lease, but stay in the current app (no launch). */
    TAKE_OVER,

    /** Person confirmed End: native owner revoke, then the needs-check card. */
    END_CONFIRMED,
}
