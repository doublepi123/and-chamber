package io.andchamber.app.ui.browser

/** Fresh main-thread readback after takeover, never a remembered composition snapshot. */
internal data class BrowserTouchFocusState(
    val touched: Any?,
    val original: Any?,
    val current: Any?,
    val selectedTabPresent: Boolean,
    val shown: Boolean,
    val retired: Boolean,
    val hostAttached: Boolean,
    val foreground: Boolean,
    val attached: Boolean,
    val viewShown: Boolean,
    val windowFocused: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val focusableInTouchMode: Boolean,
    val mouseMode: Boolean,
    val injectingMouse: Boolean,
    val mousePressActive: Boolean,
    val focused: Boolean,
)

internal enum class BrowserTouchFocusResult {
    SKIPPED, ALREADY_FOCUSED, REQUESTED, REFUSED;

    // Focus is neither input admission nor authority. Always continue normal WebView touch dispatch.
    val consumeTouch: Boolean get() = false
}

/** Called only by the admitted touch-mode listener; no mount/restore or asynchronous callbacks. */
internal fun handoffBrowserTouchFocus(
    isDown: Boolean,
    inputAdmitted: Boolean,
    beforeFocus: () -> Unit,
    readState: () -> BrowserTouchFocusState,
    requestFocus: () -> Boolean,
): BrowserTouchFocusResult {
    if (!isDown || !inputAdmitted) return BrowserTouchFocusResult.SKIPPED
    beforeFocus()
    val state = readState()
    with(state) {
        if (touched == null || touched !== original || touched !== current ||
            !selectedTabPresent || !shown || retired || !hostAttached || !foreground ||
            !attached || !viewShown || !windowFocused || !enabled || !focusable ||
            !focusableInTouchMode || mouseMode || injectingMouse || mousePressActive
        ) return BrowserTouchFocusResult.SKIPPED
        if (focused) return BrowserTouchFocusResult.ALREADY_FOCUSED
    }
    return if (requestFocus()) BrowserTouchFocusResult.REQUESTED else BrowserTouchFocusResult.REFUSED
}
