package io.andchamber.app.ui.browser

/** All values use the same unit (dp in Compose, arbitrary units in JVM tests). */
internal data class FloatWindowBounds(val width: Float, val height: Float, val areaWidth: Float, val areaHeight: Float) {
    // Offsets are relative to the bottom-right anchor, not the title's 48dp hit area.
    fun clampX(x: Float): Float = x.coerceIn(-(areaWidth - width).coerceAtLeast(0f), 0f)
    fun clampY(y: Float): Float = y.coerceIn(-(areaHeight - height).coerceAtLeast(0f), 0f)
    fun x(saved: Float, enlarged: Boolean): Float = if (enlarged) 0f else clampX(saved)
    fun y(saved: Float, enlarged: Boolean): Float = if (enlarged) 0f else clampY(saved)
}

internal fun floatWindowBounds(width: Float, height: Float, clearance: Float, tablet: Boolean,
    requestedWidth: Float? = null, requestedHeight: Float? = null,
): FloatWindowBounds {
    val availableWidth = width.coerceAtLeast(0f)
    val availableHeight = (height - clearance.coerceAtLeast(0f)).coerceAtLeast(0f)
    val initialHeight = if (tablet) 280f else minOf(240f, availableHeight * 0.42f)
    return FloatWindowBounds(
        (requestedWidth ?: if (tablet) 360f else 328f).coerceIn(0f, availableWidth),
        (requestedHeight ?: initialHeight).coerceIn(0f, availableHeight),
        availableWidth, availableHeight,
    )
}

internal fun floatWidthDp(screenWidthDp: Int, tablet: Boolean): Int =
    minOf(if (tablet) 360 else 328, (screenWidthDp - 32).coerceAtLeast(0))

internal fun floatHeightDp(availableHeightDp: Int): Int =
    minOf(240, (availableHeightDp.coerceAtLeast(0) * 0.42f).toInt())
