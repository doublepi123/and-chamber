package io.andchamber.app.ui.browser

/**
 * The one-shot navigation event a `browser.type` submit (`submit:true`) emits from the page
 * session after the Enter key has been dispatched. The presenter owns the action transaction and
 * continues a GET as a controlled replacement; it never replays a POST, redirect or refused URL.
 *
 * This type is the public contract between the session/driver engine and the presenter. The
 * engine emits [Rejected] with a fixed internal reason enum; page text must never be placed in
 * [Rejected.reason].
 */
sealed interface BrowserSubmitNavigation {
    /** The submit produced a main-frame GET for [url]. The presenter applies URL policy again. */
    data class Get(val url: String) : BrowserSubmitNavigation

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
    data class SameDocument(val url: String, val documentId: String) : BrowserSubmitNavigation

    /**
     * The submit cannot continue as a GET (POST, redirect, policy refusal, lost document, ...).
     * [reason] is a fixed engine constant, never page content.
     */
    data class Rejected(val reason: String) : BrowserSubmitNavigation
}
