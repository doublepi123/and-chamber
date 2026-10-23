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
     * The person explicitly hid the window. The selected page is kept, but it is not presented and
     * it is not admitted. The UI's explicit show/restore, a new open, or an agent action that
     * restores the small window (never after an explicit takeover) clears this; a pause ending, a
     * probe or any other state publish never does.
     */
    val hidden: Boolean = false,
    /**
     * Monotonic one-shot request for the UI to present the small floating browser window. It
     * advances when an agent action or an explicit agent open brings back a window the person had
     * hidden. The UI should switch to its small (floating) presentation once per new value; it
     * never implies authority. Ordinary snapshots while the window is already shown do not advance
     * it, so a person's full-screen view is not reset on every action.
     */
    val presentationRequestSerial: Long = 0L,
    /**
     * The person explicitly took the page over. This is stronger than [hidden]: an agent action
     * may bring back a merely hidden window, but it never overrides this. A new explicit open or
     * an explicit Allow clears it. Presentation/authority only; it is not a grant.
     */
    val explicitTakeover: Boolean = false,
    /**
     * The floating window temporarily refuses to present this page for input. The page stays
     * mounted and the grant is kept; admission, allow and page commands refuse until the UI clears
     * the gate, then control resumes. This is not a hide and not a revoke.
     */
    val presentationBlocked: Boolean = false,
    /** Trusted console transport admission. It stays true while only presentation is blocked. */
    val transportEligible: Boolean = false,
    /** Binding-only monotonic true-to-false transport signal; UI does not use this value. */
    internal val transportInvalidation: Long = 0L,
    val eligible: Boolean = false,
    /**
     * Legacy Allow-tap notice. Default control is granted after an explicit open becomes ready, so
     * this stays false; the field remains for binding compatibility only.
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
