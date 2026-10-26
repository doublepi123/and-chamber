// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.overlay.safety

import io.andchamber.app.phone.core.PhoneGeometry
import io.andchamber.app.phone.core.PhoneRect

/**
 * What the platform manager says it is currently doing with the App's own overlay. It is an intent
 * and ownership claim, not visibility proof: the driver still parses `dumpsys window` and requires a
 * real surface/frame for [VISIBLE].
 */
enum class OverlayPhase {
    VISIBLE,
    CAPTURE_HIDDEN,
    COLLECTION_HIDDEN,
    UNAVAILABLE,
}

/**
 * Manager-side expectation for one overlay owner generation and geometry revision.
 * [freshnessDeadlineMs] is an absolute elapsedRealtime deadline. The driver fails closed when it has
 * passed. [windowToken] is the token the manager intended to show; without it the driver cannot
 * prove that a displayed overlay is really this overlay.
 */
data class OverlayExpectation(
    val owner: String,
    val generation: Long,
    val revision: Long,
    val phase: OverlayPhase,
    val windowToken: String?,
    val freshnessDeadlineMs: Long,
)

/**
 * Driver-side witness. [actualBounds] is empty for hidden/unavailable phases. [hasSurface] and
 * [visible] are the parsed WindowManager state, never `canDrawOverlays`/`addView`/focus alone.
 * [focused] means the current input-focus window is this overlay token.
 */
data class OverlayWitness(
    val owner: String,
    val generation: Long,
    val revision: Long,
    val phase: OverlayPhase,
    val windowToken: String?,
    val actualBounds: List<PhoneRect>,
    val hasSurface: Boolean,
    val visible: Boolean,
    val focused: Boolean,
    val display: PhoneGeometry,
    val freshnessDeadlineMs: Long,
) {
    /** The manager key that was matched (stable title, or a binder hash for tests/legacy). */
    val windowKey: String? get() = windowToken

    val isSelfVisible: Boolean
        get() = phase == OverlayPhase.VISIBLE &&
            hasSurface &&
            visible &&
            windowToken != null &&
            actualBounds.isNotEmpty()
}

/**
 * Manager-side, main-thread claim about the App's own overlay. It is deliberately a claim, not
 * visibility proof: the driver verifies it against the real window manager.
 *
 * [windowTitle] is the exact unique title the manager assigned to its own window, not a token copied
 * from `dumpsys`. The driver resolves that title to the real window. [intendedBounds] is only the layout the manager asked for and is
 * never used for exclusion. [revision] must be bumped on the main thread in the same transaction
 * as any real geometry change (move, expand/collapse, resize, rotation) and must not be bumped by
 * hide/restore. [capturedAtMs]/[freshnessDeadlineMs] are absolute elapsedRealtime values.
 */
data class SurfaceSnapshot(
    val owner: String,
    val generation: Long,
    val revision: Long,
    val phase: OverlayPhase,
    /** Exact `LayoutParams.title` used when the view was added. */
    val windowTitle: String?,
    val intendedBounds: List<PhoneRect>,
    val display: PhoneGeometry,
    val capturedAtMs: Long,
    val freshnessDeadlineMs: Long,
)

fun SurfaceSnapshot.asExpectation(): OverlayExpectation = OverlayExpectation(
    owner = owner,
    generation = generation,
    revision = revision,
    phase = phase,
    windowToken = windowTitle,
    freshnessDeadlineMs = freshnessDeadlineMs,
)

/**
 * What one observation was bound to. It deliberately has no lease, connection, action or TaskKey;
 * progress/task ownership remains an opaque [owner] string. A different revision invalidates queued
 * actions and old observations.
 */
data class OverlayObservation(
    val owner: String,
    val generation: Long,
    val revision: Long,
    val display: PhoneGeometry,
)

/** Fixed refusals. No geometry or label is carried into the model-facing error. */
enum class OverlayRefusal {
    UNAVAILABLE,
    STALE,
    OWNER_CHANGED,
    GENERATION_CHANGED,
    REVISION_CHANGED,
    GEOMETRY_CHANGED,
    POINT_IN_OVERLAY,
    SWIPE_CROSSES_OVERLAY,
    OVERLAY_FOCUSED,
}

/**
 * The one platform-manager handshake the driver needs. The production implementation owns the real
 * WindowManager on the main thread; this contract never manipulates a View.
 *
 * [surfaceSnapshot]/[expectation] must be non-blocking and safe to call from the driver's IO
 * thread. The deadline is an absolute elapsedRealtime value.
 *
 * [withContentHidden] hides only the overlay matching [owner]/[generation]/[revision] for the bounded
 * absolute [deadlineMs] lease, performs [block], and restores only if that same owner/revision is still
 * current.
 * It restores on cancellation/exception too. An intentional hide is a display operation, not a
 * connection loss and not a lease revoke. [phase] is only CAPTURE_HIDDEN or COLLECTION_HIDDEN.
 * The driver independently verifies from `dumpsys window` that the token no longer has a visible
 * surface before reading/capturing; a manager returning early without hiding fails closed.
 */
interface PhoneOverlayBarrier {
    /**
     * Read-only, dynamic mode gate. The driver samples it once per operation (observe/capture/
     * dispatch), freezes the value, and re-checks it before every injection. `false` keeps the
     * original no-overlay driver path; `true` requires the overlay witness plus the hide/restore
     * lease. `true` is the default: a barrier that does not implement the gate is authoritative.
     *
     * It must be non-blocking and safe from the driver's IO thread. A throw is treated as `true`
     * (fail closed). A mode change during one operation is a refusal, never a silent downgrade.
     */
    fun required(deadlineMs: Long): Boolean = true

    /**
     * Latest manager claim. Must be non-blocking and safe to call from the driver's IO thread: the
     * manager publishes a [SurfaceSnapshot] from the main thread and reads the latest one here. It
     * must never wait for, or call back into, the driver's window parsing. A manager that only keeps
     * an [OverlayExpectation] may return `snapshot.asExpectation()` instead.
     */
    fun expectation(deadlineMs: Long): OverlayExpectation

    suspend fun <T> withContentHidden(
        owner: String,
        generation: Long,
        revision: Long,
        phase: OverlayPhase,
        deadlineMs: Long,
        block: suspend () -> T,
    ): T
}
