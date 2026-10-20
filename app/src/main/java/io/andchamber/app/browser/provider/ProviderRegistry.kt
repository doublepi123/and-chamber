package io.andchamber.app.browser.provider

import java.util.UUID

class ProviderRegistry(
    private val newTabId: () -> String = { "tab-" + UUID.randomUUID() },
) {
    private val tabs = linkedMapOf<String, ProviderTab>()
    private var grant: ProviderGrant? = null
    private var epoch = 0L
    private val seen = mutableSetOf<String>()

    fun tabs(): List<ProviderTab> = tabs.values.toList()

    /**
     * Opens or replaces a tab owned by [sessionId]. A missing [tabId] always allocates a new UUID
     * and never overwrites another session. Naming another session's tab is refused.
     */
    fun open(sessionId: String, directory: String, instanceKey: String, url: String, tabId: String?): ProviderTab {
        val id = tabId?.takeIf { it.isNotBlank() } ?: newTabId()
        val existing = tabs[id]
        if (existing != null && (existing.ownerSessionId != sessionId || existing.directory != directory)) {
            error("other session")
        }
        val tab = ProviderTab(id, instanceKey, directory, sessionId, url)
        tabs[id] = tab
        return tab
    }

    fun tabFor(sessionId: String, tabId: String?): ProviderTab? {
        val id = tabId ?: tabs.values.lastOrNull { it.ownerSessionId == sessionId }?.tabId ?: return null
        val tab = tabs[id] ?: return null
        if (tab.ownerSessionId != sessionId) return null
        return tab
    }

    fun updateUrl(tabId: String, url: String) {
        val existing = tabs[tabId] ?: return
        tabs[tabId] = existing.copy(url = url)
    }

    fun updateTitle(tabId: String, title: String) {
        val existing = tabs[tabId] ?: return
        tabs[tabId] = existing.copy(title = title)
    }

    fun find(tabId: String): ProviderTab? = tabs[tabId]

    /** True when [tabId] names a tab that already exists and is not owned by [sessionId]. */
    fun ownedByOther(sessionId: String, directory: String, tabId: String): Boolean {
        val existing = tabs[tabId] ?: return false
        return existing.ownerSessionId != sessionId || existing.directory != directory
    }

    fun close(tabId: String): ProviderTab? {
        val removed = tabs.remove(tabId) ?: return null
        if (grant?.tabId == tabId) grant = null
        return removed
    }

    fun clear() {
        tabs.clear()
        grant = null
        seen.clear()
    }

    /**
     * Binds authority to one launch, connection, session and tab. Switching any of those requires
     * a new grant. The connection itself is not authority.
     */
    fun grant(runtimeLaunchId: Long, connectionId: String, sessionId: String, tabId: String): ProviderGrant {
        val tab = tabs[tabId] ?: error("missing tab")
        if (tab.ownerSessionId != sessionId) error("other session")
        epoch += 1
        return ProviderGrant(runtimeLaunchId, connectionId, sessionId, tabId, epoch).also { grant = it }
    }

    fun revoke() {
        grant = null
    }

    /**
     * A session or tab switch drops authority before the new page can be driven.
     * Naming the same visible tab is a no-op. Cookies stay with the instance; this only clears the grant.
     */
    fun onVisibleChanged(sessionId: String?, tabId: String?) {
        val active = grant ?: return
        if (active.sessionId != sessionId || active.tabId != tabId) grant = null
    }

    fun currentGrant(): ProviderGrant? = grant

    /** Bumps the grant epoch and clears authority. Returns the epoch that in-flight work must not match. */
    fun revokeEpoch(): Long {
        epoch += 1
        grant = null
        return epoch
    }

    fun authorized(request: ProviderRequest, connectionId: String, visibleTabId: String?): Boolean {
        val active = grant ?: return false
        val session = request.sessionId ?: return false
        val tab = tabFor(session, request.parameters["tabId"]) ?: return false
        return active.connectionId == connectionId &&
            active.sessionId == session &&
            active.tabId == tab.tabId &&
            tab.tabId == visibleTabId
    }

    fun claimKey(epoch: String, requestId: String): Boolean = seen.add("$epoch:$requestId")
}
