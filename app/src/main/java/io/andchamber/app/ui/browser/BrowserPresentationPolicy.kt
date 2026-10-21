package io.andchamber.app.ui.browser

// 48dp title + up to 40dp status + 48dp actions + 8dp content padding + 72dp page.
internal const val MIN_EDITABLE_WINDOW_HEIGHT = 216f

/** Preferred size is not the available safe region. Keep the original drag/clamp coordinate space. */
internal fun editableFloatBounds(preferred: FloatWindowBounds): FloatWindowBounds = preferred.copy(
    height = preferred.height.coerceAtLeast(minOf(MIN_EDITABLE_WINDOW_HEIGHT, preferred.areaHeight)),
)

internal fun presentationBlocked(latched: Boolean, bounds: FloatWindowBounds): Boolean =
    latched || bounds.areaHeight < MIN_EDITABLE_WINDOW_HEIGHT || bounds.areaWidth < 176f

/** Recovery is a human decision, never a consequence of the keyboard closing. */
internal fun releasePresentationLatch(userEnlarge: Boolean, bounds: FloatWindowBounds): Boolean =
    userEnlarge && !presentationBlocked(false, bounds)

/** Used by the actual native mount update; no frame can be hidden before core input is gated. */
internal fun applyBrowserPresentation(blocked: Boolean, gate: (Boolean) -> Unit, applyNative: (Boolean) -> Unit) {
    gate(blocked)
    applyNative(blocked)
}
