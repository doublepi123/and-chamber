package io.andchamber.app.ui.browser

import io.andchamber.app.browser.provider.ProviderMode

/**
 * The smallest per-page recovery classification. It is derived from explicit presenter state,
 * never from a page-provided string.
 */
internal enum class PageRecovery {
    /** Normal page: reads and mutations follow the normal grant. */
    NONE,
    /** A click/type effect is uncertain: only snapshot/capture reads are admitted. */
    OBSERVE_ONLY,
    /** The document identity/renderer is invalid: the UI must reopen, not replay. */
    REOPEN_REQUIRED,
}

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
    /**
     * The agent still holds the current, valid grant for the shown page, but an unconfirmed
     * click/type limits it to snapshot/capture ([PageRecovery.OBSERVE_ONLY]). Derived from the live
     * grant plus recovery, independently of [agentOn]; the UI offers Take over, which revokes it.
     * Presentation only: it is not authority and does not change the mutation gate.
     */
    val agentReadOnly: Boolean = false,
    /**
     * The shown page's own main-frame address as the session last recorded it (load start, commit,
     * finish, or a same-document history update the URL policy allows). Display only: it is not a
     * navigation receipt, not proof of a document and never a grant.
     */
    val displayUrl: String? = null,
    val pageError: String? = null,
    val pageReady: Boolean = false,
    /** True only for a document-invalid/unavailable page; the UI offers reload/reopen. */
    val requiresReopen: Boolean = false,
    /**
     * A dispatched click/type left its effect unconfirmed. Reads stay possible with the same
     * existing grant; the UI asks the person to check the page and hand control back explicitly.
     * Mutations are refused (OUTCOME_UNKNOWN) and the old action is never replayed.
     */
    val actionOutcomeUnknown: Boolean = false,
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
    /** The selected tab's configured desktop CSS viewport; mobile pages keep the stored pair. */
    val desktopWidth: Int = BrowserDesktopViewport.DEFAULT_WIDTH,
    val desktopHeight: Int = BrowserDesktopViewport.DEFAULT_HEIGHT,
    val mouseMode: Boolean = false,
    /** Pointer position in CSS px. This is not a WebView pixel or a screenshot pixel. */
    val pointerX: Float = 0f,
    val pointerY: Float = 0f,
    val pointerUi: PointerUi = PointerUi(0, 0L, 0f, 0f, false),
)

/**
 * One display-only report from a page session: its view and load ticket, and the address and error
 * it now shows. The presenter uses the identity only to drop reports from an older page/view/load.
 */
data class BrowserPageStatus(
    val viewId: String,
    val ticket: Long,
    val displayUrl: String?,
    val pageError: String?,
    /** Display text reported by the page; never a navigation receipt or a trust proof. */
    val title: String? = null,
)

data class NativeBrowserTab(
    val id: String,
    val title: String,
    val url: String,
    val owner: String,
    val active: Boolean,
)

/**
 * Deterministic selection after closing [closingTabId]. [orderedTabIds] is the tab order before the
 * close: prefer the next tab in that order, otherwise the previous one, and null when no tab remains.
 * Closing a background tab never calls this; it does not move the selection.
 */
internal fun nextSelectedTabAfterClose(orderedTabIds: List<String>, closingTabId: String): String? {
    val index = orderedTabIds.indexOf(closingTabId)
    if (index < 0) return null
    return orderedTabIds.getOrNull(index + 1) ?: orderedTabIds.getOrNull(index - 1)
}
