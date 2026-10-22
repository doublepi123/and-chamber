package io.andchamber.app.ui.browser

import io.andchamber.app.browser.provider.ProviderMode

/**
 * What the floating window may render. This is presentation only: no token, no claim nonce, and
 * no page command. [viewGeneration] advances whenever the owned WebView is replaced.
 */
data class NativeBrowserState(
    val tabs: List<NativeBrowserTab> = emptyList(),
    val selectedTabId: String? = null,
    val shown: Boolean = false,
    val mode: ProviderMode = ProviderMode.MANUAL,
    val agentOn: Boolean = false,
    val pageError: String? = null,
    val pageReady: Boolean = false,
    val viewHandle: Int = 0,
    val viewGeneration: Long = 0L,
    val hostAttached: Boolean = false,
    val documentEpoch: Long = 0L,
    val paused: Boolean = false,
    /**
     * The floating window currently refuses to present this page for input. The page may stay
     * mounted; admission, allow and page commands refuse until the UI clears the gate.
     */
    val presentationBlocked: Boolean = false,
    /** Trusted console transport admission. It stays true while only presentation is blocked. */
    val transportEligible: Boolean = false,
    /** Binding-only monotonic true-to-false transport signal; UI does not use this value. */
    internal val transportInvalidation: Long = 0L,
    val eligible: Boolean = false,
    /**
     * The selected tab was opened by an agent `open` and the person has not allowed, taken over or
     * navigated it by hand since. Presentation only: it is not authority and grants nothing.
     */
    val awaitingAllow: Boolean = false,
    /** A provider connection is live. Only a flag: the connection id itself is not exposed. */
    val connected: Boolean = false,
    val pageRetired: Boolean = false,
    val mousePressActive: Boolean = false,
    val desktopMode: Boolean = false,
    val mouseMode: Boolean = false,
    /** Pointer position in CSS px. This is not a WebView pixel or a screenshot pixel. */
    val pointerX: Float = 0f,
    val pointerY: Float = 0f,
    val pointerUi: PointerUi = PointerUi(0, 0L, 0f, 0f, false),
)

data class NativeBrowserTab(
    val id: String,
    val title: String,
    val url: String,
    val owner: String,
    val active: Boolean,
)
