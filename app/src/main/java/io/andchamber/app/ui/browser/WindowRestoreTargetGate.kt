package io.andchamber.app.ui.browser

import kotlin.math.abs

/**
 * Core-owned B5 restore target barrier. Pure rules only: no Android View, no coroutine, no WebView. The presenter
 * wires these checks to live samples; the JVM tests exercise the same functions.
 *
 * An [WindowContentBinding] is an opaque observation handle for one window transition. Its identity (===) is the
 * only thing the Core accepts. It grants no authority: the Core keeps the full page/runtime/console context
 * privately and rechecks it at every step.
 */
internal class WindowContentBinding internal constructor(
    val change: WindowChangeToken,
    val sourceHandle: Int,
    val viewId: String,
    val afterLayoutRevision: Long,
)

/**
 * The content rectangle the AndroidView/FrameLayout was actually allocated, in physical px, read in window
 * coordinates. Built by the UI from real layout coordinates. Never copied from the current native frame.
 */
internal data class WindowContentTarget(
    val binding: WindowContentBinding,
    val layoutRevision: Long,
    val windowLeftPx: Float,
    val windowTopPx: Float,
    val widthPx: Int,
    val heightPx: Int,
)

/** Explicit quantisation tolerances. Each is named and justified; none is a relaxed match. */
internal object WindowRestoreTolerance {
    /** Native frame vs the target rectangle. Both come from integer window coordinates, so ±1 px is the rounding. */
    const val NATIVE_RECT_PX = 1
    /**
     * The CSS projection `s * visualHeight` against the target height. The visual height is a float measured from
     * the same frame; ±2 physical px absorbs the rounding of two integer frame edges and the float visual height.
     */
    const val PROJECTION_PX = 2.0
}

/** True only for a target whose numbers are finite and whose rectangle is a real, positive area. */
internal fun WindowContentTarget.isWellFormed(): Boolean =
    windowLeftPx.isFinite() && windowTopPx.isFinite() && widthPx > 0 && heightPx > 0 && layoutRevision >= 0L

/** Native frame matches the independent target within the explicit integer rounding. Source frame is not desired. */
internal fun nativeMatchesTarget(nativeLeft: Int, nativeTop: Int, nativeWidth: Int, nativeHeight: Int, target: WindowContentTarget): Boolean =
    abs(nativeLeft - target.windowLeftPx) <= WindowRestoreTolerance.NATIVE_RECT_PX &&
        abs(nativeTop - target.windowTopPx) <= WindowRestoreTolerance.NATIVE_RECT_PX &&
        abs(nativeWidth - target.widthPx) <= WindowRestoreTolerance.NATIVE_RECT_PX &&
        abs(nativeHeight - target.heightPx) <= WindowRestoreTolerance.NATIVE_RECT_PX

/**
 * Restore-specific CSS projection. The scale is the native width over the visual width, so the projected height of
 * the visual viewport in physical px is `s * visualHeight`. It must equal the target height. This deliberately does
 * not require layout or client size to equal the visual size, which pinch and IME depend on.
 */
internal fun cssProjectionMatchesTarget(nativeWidth: Int, visualWidth: Float, visualHeight: Float, target: WindowContentTarget): Boolean {
    if (nativeWidth <= 0 || !visualWidth.isFinite() || !visualHeight.isFinite() || visualWidth <= 0f || visualHeight <= 0f) return false
    val scale = nativeWidth.toDouble() / visualWidth.toDouble()
    if (!scale.isFinite() || scale <= 0.0) return false
    return abs(scale * visualHeight - target.heightPx) <= WindowRestoreTolerance.PROJECTION_PX
}
