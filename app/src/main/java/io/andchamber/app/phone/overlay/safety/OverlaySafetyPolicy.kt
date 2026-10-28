// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.overlay.safety

import io.andchamber.app.phone.core.PhonePoint
import io.andchamber.app.phone.core.PhoneRect

/**
 * Pure safety policy. It never grants authority; it only refuses when the driver cannot prove that
 * its own overlay is in a usable state or when an injected coordinate/segment could hit it.
 *
 * A visible overlay is only usable when the parsed window has a surface, a frame inside the display,
 * the expected token and a live freshness deadline. Hidden/unavailable, stale, owner/generation/
 * revision and display changes all fail closed. The overlay focus is a separate Back hazard.
 */
object OverlaySafetyPolicy {
    fun visibleRefusal(witness: OverlayWitness, nowMs: Long): OverlayRefusal? {
        if (nowMs > witness.freshnessDeadlineMs) return OverlayRefusal.STALE
        if (witness.phase != OverlayPhase.VISIBLE) return OverlayRefusal.UNAVAILABLE
        if (!witness.hasSurface ||
            !witness.visible ||
            witness.windowKey == null ||
            witness.actualBounds.isEmpty()
        ) {
            return OverlayRefusal.UNAVAILABLE
        }
        if (witness.actualBounds.any { !it.isWithin(witness.display) }) {
            return OverlayRefusal.UNAVAILABLE
        }
        return null
    }

    fun identityRefusal(
        witness: OverlayWitness,
        observation: OverlayObservation,
        nowMs: Long,
    ): OverlayRefusal? {
        visibleRefusal(witness, nowMs)?.let { return it }
        if (witness.owner != observation.owner) return OverlayRefusal.OWNER_CHANGED
        if (witness.generation != observation.generation) return OverlayRefusal.GENERATION_CHANGED
        // The display revision moves on every cosmetic change (a chat notice, expand/collapse, a
        // drag). It is not part of the identity: each action re-reads the real window frame, and the
        // point/swipe/focus checks use that fresh frame. Owner, generation and display still match.
        if (witness.display != observation.display) return OverlayRefusal.GEOMETRY_CHANGED
        return null
    }

    /**
     * True when the refusal only says "this coordinate/segment/focus is under the overlay". The
     * overlay itself is fine; only this action is refused. Never a visibility loss.
     */
    fun isObstruction(refusal: OverlayRefusal): Boolean =
        refusal == OverlayRefusal.POINT_IN_OVERLAY ||
            refusal == OverlayRefusal.SWIPE_CROSSES_OVERLAY ||
            refusal == OverlayRefusal.OVERLAY_FOCUSED

    fun tapRefusal(
        witness: OverlayWitness,
        at: PhonePoint,
        nowMs: Long,
    ): OverlayRefusal? {
        visibleRefusal(witness, nowMs)?.let { return it }
        if (witness.actualBounds.any { pointInRect(it, at) }) {
            return OverlayRefusal.POINT_IN_OVERLAY
        }
        return null
    }

    fun inputFocusRefusal(
        witness: OverlayWitness,
        focus: PhonePoint?,
        nowMs: Long,
    ): OverlayRefusal? =
        if (focus == null) visibleRefusal(witness, nowMs) else tapRefusal(witness, focus, nowMs)

    fun swipeRefusal(
        witness: OverlayWitness,
        from: PhonePoint,
        to: PhonePoint,
        nowMs: Long,
    ): OverlayRefusal? {
        visibleRefusal(witness, nowMs)?.let { return it }
        if (witness.actualBounds.any { segmentIntersectsRect(from, to, it) }) {
            return OverlayRefusal.SWIPE_CROSSES_OVERLAY
        }
        return null
    }

    fun backRefusal(witness: OverlayWitness, nowMs: Long): OverlayRefusal? {
        visibleRefusal(witness, nowMs)?.let { return it }
        if (witness.focused) return OverlayRefusal.OVERLAY_FOCUSED
        return null
    }

    fun pointInRect(rect: PhoneRect, point: PhonePoint): Boolean =
        rect.right > rect.left &&
            rect.bottom > rect.top &&
            point.x >= rect.left && point.x < rect.right &&
            point.y >= rect.top && point.y < rect.bottom

    /**
     * Closed-rectangle segment test. It catches a swipe whose middle crosses the overlay even when
     * both endpoints are outside, and conservatively treats touching an edge as an intersection.
     */
    fun segmentIntersectsRect(from: PhonePoint, to: PhonePoint, rect: PhoneRect): Boolean {
        if (rect.right <= rect.left || rect.bottom <= rect.top) return false
        val dx = (to.x - from.x).toDouble()
        val dy = (to.y - from.y).toDouble()
        var t0 = 0.0
        var t1 = 1.0
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(
            (from.x - rect.left).toDouble(),
            (rect.right - from.x).toDouble(),
            (from.y - rect.top).toDouble(),
            (rect.bottom - from.y).toDouble(),
        )
        for (index in p.indices) {
            if (p[index] == 0.0) {
                if (q[index] < 0.0) return false
            } else {
                val r = q[index] / p[index]
                if (p[index] < 0.0) {
                    if (r > t1) return false
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return false
                    if (r < t1) t1 = r
                }
            }
        }
        return t0 <= t1
    }
}
