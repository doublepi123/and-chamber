package io.andchamber.app.browser

/**
 * Pure decision for geometry-diag. The selected tab decides everything. An earlier unknown page that is no
 * longer selected does not leak into the answer. Unreadable state is null, never false.
 */
internal object GeometryDiagPolicy {
    /** outcomeUnknown of the selected page: null when the selected tab or its flag cannot be read. */
    fun outcomeUnknownFor(selected: String?, boundTabId: String?, boundUnknown: Boolean?): Boolean? {
        if (selected == null) return null
        if (boundTabId != null && boundTabId != selected) return null
        return boundUnknown
    }

    /** True when the grant names the selected tab and its owner; null when the selected tab is unknown. */
    fun grantForSelected(selected: String?, grantTabId: String?, grantSessionId: String?, owner: String?): Boolean? {
        if (selected == null) return null
        if (owner == null) return false
        return grantTabId == selected && grantSessionId == owner
    }
}
