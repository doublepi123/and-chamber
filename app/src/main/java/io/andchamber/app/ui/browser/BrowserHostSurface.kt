package io.andchamber.app.ui.browser

/**
 * Frontend-facing projection of [NativeBrowserPresenter]: the trusted console lends the presenter
 * the chat session it shows. Opening, selecting and closing tabs are native actions now, so no
 * page script drives them.
 */
internal class BrowserHostSurface(
    private val presenter: NativeBrowserPresenter,
) {
    /** A different chat session drops the current grant. It does not grant the new session. */
    fun context(sessionId: String) {
        // The console reports the chat it is showing on every chat change. This is an identity the
        // presenter may use to let that session's open take the window; it is not a grant.
        presenter.setConsoleSession(sessionId)
        val state = presenter.state.value
        val owner = state.tabs.firstOrNull { it.active }?.owner
            ?: state.tabs.firstOrNull { it.id == state.selectedTabId }?.owner
        if ((!owner.isNullOrBlank() && owner != sessionId) || (sessionId.isBlank() && state.agentOn)) {
            presenter.revokeAll("session-changed")
        }
    }
}
