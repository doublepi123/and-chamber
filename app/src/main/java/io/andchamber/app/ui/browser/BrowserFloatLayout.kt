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

// ---- Fixed-viewport window layout (all values in dp) -------------------------------------------

/** Height of the top bar (address and window buttons) and of the bottom bar (agent control). */
internal const val BROWSER_BAR_HEIGHT = 48f

/** Narrowest small window: a short address plus three 48dp buttons. */
internal const val MIN_FLOAT_WIDTH = 240f

/** Smallest page a small window may show; below this the page is too small to follow. */
internal const val MIN_FLOAT_PAGE_HEIGHT = 160f
internal const val MIN_FLOAT_PAGE_WIDTH = 160f

/** A small window must fit in this share of the area above the composer, or full screen is used. */
internal const val MAX_FLOAT_HEIGHT_FRACTION = 0.85f

/** Smallest page area full screen may show before the window folds to its bar. */
internal const val MIN_FULL_PAGE_HEIGHT = 72f

/** Gap kept between a small window and the edges of its area. */
internal const val FLOAT_MARGIN = 8f

/** A default small window takes at most this share of the height above the chat composer. */
internal const val DEFAULT_FLOAT_HEIGHT_FRACTION = 0.75f

internal enum class BrowserPresentation {
    /** A small window that can be moved and resized. The page is scaled down, not reflowed. */
    Float,

    /** Fills the safe area of the screen. The page is drawn 1:1 when the keyboard is closed. */
    Full,

    /** Only the top bar: there is not enough room to show the page. The page is not presented. */
    Collapsed,
}

internal data class WindowRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * The page's fixed size. It is the full-screen page area (the safe area minus the two bars), so
 * full screen shows the page 1:1 and the small window shows the same layout scaled down. It does
 * not depend on the keyboard, the composer, or the small window's size; it changes only when the
 * safe area itself changes (rotation, window resize).
 */
internal fun browserViewportDp(safeWidth: Float, safeHeight: Float): Pair<Float, Float> =
    safeWidth.coerceAtLeast(1f) to (safeHeight - 2 * BROWSER_BAR_HEIGHT).coerceAtLeast(1f)

/**
 * The page's fixed size in physical pixels. Phone layout: the full-screen page area from
 * [browserViewportDp] at the screen's density. Computer layout: the agent's requested CSS size
 * (default 1920 x 1080), independent of the phone's screen, rotation, keyboard and window; mapped
 * by [desktopViewportPx]. A missing or invalid desktop size falls back to the phone size.
 */
internal fun browserPageViewportPx(
    desktop: Boolean,
    desktopCssWidth: Int,
    desktopCssHeight: Int,
    phoneWidthPx: Int,
    phoneHeightPx: Int,
): Pair<Int, Int> =
    if (desktop && desktopCssWidth > 0 && desktopCssHeight > 0) {
        desktopViewportPx(desktopCssWidth) to desktopViewportPx(desktopCssHeight)
    } else {
        phoneWidthPx to phoneHeightPx
    }

/** Computer-layout CSS px to the page's physical px. 1:1 for now (the engine's own mapping). */
internal fun desktopViewportPx(css: Int): Int = css

/**
 * The page proportions the window lays out with, in dp. Phone layout keeps the phone viewport.
 * Computer layout keeps the phone's width and the desktop page's aspect ratio, so a small window
 * or full screen shows the whole wide page without empty bands inside the window.
 */
internal fun browserLayoutViewportDp(
    phoneViewport: Pair<Float, Float>,
    desktop: Boolean,
    desktopCssWidth: Int,
    desktopCssHeight: Int,
): Pair<Float, Float> =
    if (desktop && desktopCssWidth > 0 && desktopCssHeight > 0) {
        phoneViewport.first to (phoneViewport.first * desktopCssHeight / desktopCssWidth).coerceAtLeast(1f)
    } else {
        phoneViewport
    }

/**
 * Whether a change of the page's fixed size is a phone-screen change the UI must report as a
 * geometry change. Only phone layout before and after: a computer-layout size or a layout switch
 * is the agent's own resize, which already invalidates and reloads the page; reporting it again
 * would cancel that resize, or the agent action running after it.
 */
internal fun browserViewportChangeInvalidates(
    previousDesktop: Boolean,
    previousPx: Pair<Int, Int>,
    desktop: Boolean,
    px: Pair<Int, Int>,
): Boolean = !previousDesktop && !desktop && previousPx != px

/** What the person chose for the small window. Null means "use the default". */
internal data class FloatPlacement(val scale: Float? = null, val left: Float? = null, val top: Float? = null)

internal data class BrowserWindowLayout(
    val presentation: BrowserPresentation,
    val window: WindowRect,
    /** Small window scale actually used (page dp per viewport dp). 0 when not a small window. */
    val scale: Float,
    val showBottomBar: Boolean,
    /** True when the page is not presented (folded to the bar). */
    val blocked: Boolean,
    /** Smallest and largest small-window scale for this area; used by resize. */
    val minScale: Float,
    val maxScale: Float,
    /** The keyboard is open (full screen then uses the compact toolbar). */
    val imeOpen: Boolean = false,
    /** Full screen was chosen because a small window can't fit, not because the person asked. */
    val autoFull: Boolean = false,
    /** Largest left/top a small window of this size may have (the smallest is [FLOAT_MARGIN]). */
    val maxLeft: Float = FLOAT_MARGIN,
    val maxTop: Float = FLOAT_MARGIN,
)

/** Moves a small window by ([dx], [dy]) dp and keeps all of it inside its area. */
internal fun movedFloatPlacement(layout: BrowserWindowLayout, placement: FloatPlacement, dx: Float, dy: Float): FloatPlacement {
    val left = (placement.left ?: layout.window.left).coerceIn(FLOAT_MARGIN, layout.maxLeft)
    val top = (placement.top ?: layout.window.top).coerceIn(FLOAT_MARGIN, layout.maxTop)
    return placement.copy(
        left = (left + dx).coerceIn(FLOAT_MARGIN, layout.maxLeft),
        top = (top + dy).coerceIn(FLOAT_MARGIN, layout.maxTop),
    )
}

/**
 * Lays out the browser window in a safe area of [safeWidth] x [safeHeight] dp (system bars and
 * cutout removed, keyboard not removed).
 *
 * - [imeOverlap]: how much of the safe area's bottom the keyboard covers.
 * - [composerClearance]: the chat composer's height above the keyboard (or above the bottom of the
 *   safe area); a small window stays above it.
 * - [viewportWidth]/[viewportHeight]: the fixed page size from [browserViewportDp].
 *
 * A small window when one fits above the composer. Otherwise (landscape, short screens, the
 * keyboard open) full screen with a compact toolbar: the bottom bar is dropped while the keyboard
 * is open, so the page keeps as much room as possible. The keyboard is never a reason to stop
 * showing the page. Only when not even a [MIN_FULL_PAGE_HEIGHT] page fits under the top bar does
 * the window fold to its bar ([BrowserWindowLayout.blocked]).
 */
internal fun browserWindowLayout(
    safeWidth: Float,
    safeHeight: Float,
    imeOverlap: Float,
    composerClearance: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    fullRequested: Boolean,
    placement: FloatPlacement = FloatPlacement(),
): BrowserWindowLayout {
    val areaW = safeWidth.coerceAtLeast(0f)
    val ime = imeOverlap.coerceIn(0f, safeHeight.coerceAtLeast(0f))
    val fullH = (safeHeight - ime).coerceAtLeast(0f)
    val floatBottom = (fullH - composerClearance.coerceAtLeast(0f)).coerceAtLeast(0f)
    val vpW = viewportWidth.coerceAtLeast(1f)
    val vpH = viewportHeight.coerceAtLeast(1f)
    val imeOpen = ime > 0f

    val chrome = 2 * BROWSER_BAR_HEIGHT
    val minScale = maxOf(MIN_FLOAT_PAGE_HEIGHT / vpH, MIN_FLOAT_PAGE_WIDTH / vpW)
    val maxScale = minOf(1f, (areaW - 2 * FLOAT_MARGIN) / vpW, (floatBottom - 2 * FLOAT_MARGIN - chrome) / vpH)
    // Low areas (landscape, keyboard) can't hold a useful small window without covering the chat.
    val floatFits = maxScale >= minScale && areaW - 2 * FLOAT_MARGIN >= MIN_FLOAT_WIDTH &&
        chrome + vpH * minScale <= floatBottom * MAX_FLOAT_HEIGHT_FRACTION

    if (fullRequested || !floatFits) {
        val bottomBar = !imeOpen && fullH - chrome >= MIN_FULL_PAGE_HEIGHT
        val pageHeight = fullH - if (bottomBar) chrome else BROWSER_BAR_HEIGHT
        if (pageHeight >= MIN_FULL_PAGE_HEIGHT) {
            return BrowserWindowLayout(BrowserPresentation.Full, WindowRect(0f, 0f, areaW, fullH), 0f,
                showBottomBar = bottomBar, blocked = false, minScale, maxScale, imeOpen, autoFull = !fullRequested)
        }
        // Not even a sliver of page fits: fold to the bar and stop presenting (fail closed).
        val width = minOf(areaW - 2 * FLOAT_MARGIN, COLLAPSED_MAX_WIDTH).coerceAtLeast(minOf(areaW, MIN_FLOAT_WIDTH))
        val left = (areaW - FLOAT_MARGIN - width).coerceAtLeast(0f)
        val top = (fullH - BROWSER_BAR_HEIGHT).coerceAtLeast(0f)
        return BrowserWindowLayout(
            BrowserPresentation.Collapsed, WindowRect(left, top, width, minOf(BROWSER_BAR_HEIGHT, fullH)),
            0f, showBottomBar = false, blocked = true, minScale, maxScale, imeOpen,
        )
    }

    // As wide as the bars need (no empty sides), unless that would cover too much of the chat.
    val defaultScale = minOf(MIN_FLOAT_WIDTH / vpW, (floatBottom * DEFAULT_FLOAT_HEIGHT_FRACTION - chrome) / vpH)
    val scale = (placement.scale ?: defaultScale).coerceIn(minScale, maxScale)
    val width = maxOf(vpW * scale, MIN_FLOAT_WIDTH)
    val height = chrome + vpH * scale
    val maxLeft = (areaW - FLOAT_MARGIN - width).coerceAtLeast(FLOAT_MARGIN)
    val maxTop = (floatBottom - FLOAT_MARGIN - height).coerceAtLeast(FLOAT_MARGIN)
    val left = (placement.left ?: maxLeft).coerceIn(FLOAT_MARGIN, maxLeft)
    val top = (placement.top ?: maxTop).coerceIn(FLOAT_MARGIN, maxTop)
    return BrowserWindowLayout(
        BrowserPresentation.Float, WindowRect(left, top, width, height), scale,
        showBottomBar = true, blocked = false, minScale, maxScale, imeOpen,
        maxLeft = maxLeft, maxTop = maxTop,
    )
}

/** Widest folded bar (no room for any page): it only says so and offers Hide. */
internal const val COLLAPSED_MAX_WIDTH = 360f

/**
 * Scale after moving the resize grip by ([dx], [dy]) dp, where positive means "grow" on that axis.
 * The page keeps its proportions, so the larger of the two moves wins and a diagonal drag does not
 * count twice. Always clamped to [minScale]..[maxScale].
 */
internal fun resizedFloatScale(
    startScale: Float,
    dx: Float,
    dy: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    minScale: Float,
    maxScale: Float,
): Float {
    val byWidth = dx / viewportWidth.coerceAtLeast(1f)
    val byHeight = dy / viewportHeight.coerceAtLeast(1f)
    val next = startScale + if (kotlin.math.abs(byWidth) > kotlin.math.abs(byHeight)) byWidth else byHeight
    return if (maxScale < minScale) minScale else next.coerceIn(minScale, maxScale)
}
