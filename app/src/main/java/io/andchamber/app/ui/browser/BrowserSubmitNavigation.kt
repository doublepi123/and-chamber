package io.andchamber.app.ui.browser

/** The one native action that may own a navigation receipt. */
enum class BrowserActionKind { ENTER, CLICK }

/**
 * The one-shot navigation event a native action emits from the page session after it was dispatched:
 * an Enter for `browser.type` with `submit:true` ([BrowserActionKind.ENTER]) or a synthetic click
 * for `browser.click` ([BrowserActionKind.CLICK]).
 *
 * The presenter owns the action transaction and continues a plain GET as a controlled replacement;
 * it never replays a POST, redirect or refused URL. [token] is the engine's opaque, one-shot arm
 * identity captured when the action was armed. An event is only accepted while the presenter's
 * receipt is bound to that exact token; a page navigation with no valid arm stays blocked.
 *
 * This type is the public contract between the session/driver engine and the presenter. The
 * engine emits [Rejected] with a fixed internal reason enum; page text must never be placed in
 * [Rejected.reason].
 */
sealed interface BrowserSubmitNavigation {
    /** The engine's one-shot arm token; empty only for a direct test callback without an arm. */
    val token: String

    /** The action that owns this event. */
    val kind: BrowserActionKind

    /** The action produced a main-frame GET for [url]. The presenter applies URL policy again. */
    data class Get(
        val url: String,
        override val token: String = "",
        override val kind: BrowserActionKind = BrowserActionKind.ENTER,
    ) : BrowserSubmitNavigation

    /**
     * The armed Enter changed only the top-level URL in the same document (an SPA/history route
     * change), for example `pushState`/`replaceState` without a main-frame navigation.
     *
     * The engine emits this only for the current armed Enter, from a native history candidate that
     * kept the original document witness, within the same origin, with a changed URL that matches
     * both the native and JS URL views, and inside the intent deadline. The engine must not also
     * emit [Get]/[Rejected] for the same Enter.
     *
     * [documentId] is the unchanged document witness; [url] is the new top-level URL, never page
     * text. The presenter re-verifies both read-only and never navigates, grants or reloads.
     */
    data class SameDocument(
        val url: String,
        val documentId: String,
        override val token: String = "",
        override val kind: BrowserActionKind = BrowserActionKind.ENTER,
    ) : BrowserSubmitNavigation

    /**
     * The action cannot continue as a GET (POST, redirect, policy refusal, lost document, ...).
     * [reason] is a fixed engine constant, never page content.
     */
    data class Rejected(
        val reason: String,
        override val token: String = "",
        override val kind: BrowserActionKind = BrowserActionKind.ENTER,
    ) : BrowserSubmitNavigation
}
