package io.andchamber.app.ui.web

/** Numeric-only, visual-viewport CSS coordinates. JS subtracts visualViewport.offsetTop once. */
internal data class ComposerMetrics(
    val top: Double,
    val bottom: Double,
    val viewportWidth: Double,
    val viewportHeight: Double,
    val visible: Boolean,
) {
    fun valid(): Boolean = listOf(top, bottom, viewportWidth, viewportHeight).all { it.isFinite() } &&
        viewportWidth in 1.0..MAX_CSS && viewportHeight in 1.0..MAX_CSS &&
        top >= 0.0 && bottom >= top && bottom <= viewportHeight &&
        (if (visible) bottom > top else top == 0.0 && bottom == 0.0)

    companion object { private const val MAX_CSS = 32768.0 }
}

/** Keeps old documents and old native sizes from supplying a layout constraint for the new page. */
internal data class ComposerMeasurement(
    val metrics: ComposerMetrics,
    val consoleGeneration: Long,
    val viewGeneration: Int,
    val nativeWidth: Int,
    val nativeHeight: Int,
) {
    fun validityReason(currentConsole: Long, currentView: Int, width: Int, height: Int): String = when {
        !matchesComposerDocument(consoleGeneration, currentConsole, viewGeneration, currentView) -> "stale-document"
        width != nativeWidth || height != nativeHeight -> "native-size-changed"
        width <= 0 || height <= 0 -> "no-native-size"
        !metrics.valid() -> "invalid-css-metrics"
        !metrics.visible -> "composer-not-visible"
        metrics.top * width / metrics.viewportWidth >= height -> "composer-outside-native"
        // Chromium can round the visual viewport height up by one CSS pixel. A constant
        // physical-pixel tolerance rejects valid landscape metrics at higher visual scales.
        // Keep CSS validation exact; only tolerate this bounded native projection rounding.
        metrics.bottom * width / metrics.viewportWidth > height + width / metrics.viewportWidth -> "mapped-bottom-outside-native"
        else -> "accepted"
    }
    fun topPx(currentConsole: Long, currentView: Int, width: Int, height: Int): Float? {
        if (!matchesComposerDocument(consoleGeneration, currentConsole, viewGeneration, currentView) ||
            width != nativeWidth || height != nativeHeight || width <= 0 || height <= 0 ||
            !metrics.valid() || !metrics.visible) return null
        // Uniform visual-CSS -> physical transform uses full WebView width, not clipped width,
        // DPR, target-page desktop layout, or a second height-derived scale.
        val scale = width / metrics.viewportWidth
        val top = metrics.top * scale
        val bottom = metrics.bottom * scale
        if (!top.isFinite() || top < 0 || top >= height || bottom > height + scale) return null
        return top.toFloat()
    }
}

internal fun matchesComposerDocument(sentConsole: Long, currentConsole: Long, sentView: Int, currentView: Int): Boolean =
    sentConsole == currentConsole && sentView == currentView

/** WebView and overlay share their parent bounds. Null means the conservative fallback is needed. */
internal fun composerClearancePx(top: Float?, fullHeight: Int, gap: Float): Float? {
    if (top == null || !top.isFinite() || top < 0f || top > fullHeight || fullHeight <= 0 ||
        !gap.isFinite() || gap < 0f) return null
    return (fullHeight - top + gap).coerceIn(0f, fullHeight.toFloat())
}

/** Numeric-only native diagnostic snapshot. Stored on the console View, never exposed to JS. */
internal data class ComposerLayoutSnapshot(
    val measurement: ComposerMeasurement?,
    val consoleGeneration: Long,
    val viewGeneration: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val selectedClearancePx: Float,
    val fallback: Boolean,
    val validityReason: String,
)
