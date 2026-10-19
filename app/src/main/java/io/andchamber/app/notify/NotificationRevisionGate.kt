package io.andchamber.app.notify

import java.util.UUID

/**
 * Process-wide authority for "Sent" clean-ups: the pending "Sent" revision of each notification id, if any.
 * Every write to an id drops that entry under [lock] before writing, so a clean-up still waiting on an older
 * "Sent" can no longer match, even while the system has not yet shown the newer notification.
 */
internal class NotificationRevisionState {
    val lock = Any()
    val pendingSent = HashMap<Int, String>()
}

/** The state every notifier and receiver in this process shares. Tests inject their own instance. */
internal val SharedNotificationRevisions = NotificationRevisionState()

/**
 * Local revisions of the notifications this app posts. Each write gets a fresh token, stored in the
 * notification's extras as a second check. The authority is [state]: a clean-up removes an id only while its
 * token is still the pending "Sent" recorded there, and the system also shows that token. Posting is
 * asynchronous, so the system alone can briefly show an older notification; it is never trusted on its own.
 * A failed read never removes anything.
 */
internal class NotificationRevisionGate(
    private val readRevision: (id: Int) -> String?,
    private val remove: (id: Int) -> Unit,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
    private val state: NotificationRevisionState = SharedNotificationRevisions,
) {
    /** Drops the pending "Sent" of [id]. Call before every write, including writes that are then skipped. */
    fun invalidate(id: Int) {
        synchronized(state.lock) {
            state.pendingSent.remove(id)
        }
    }

    /**
     * Runs one write for [id] under the lock, with a fresh token. The pending "Sent" of [id] is dropped first.
     * When [sent] is true the new token becomes the pending "Sent"; a failed write leaves nothing pending.
     */
    fun <T> publish(
        id: Int,
        sent: Boolean,
        write: (token: String) -> T,
    ): T =
        synchronized(state.lock) {
            state.pendingSent.remove(id)
            val token = newToken()
            val result = write(token)
            if (sent) state.pendingSent[id] = token
            result
        }

    /**
     * The clean-up of a "Sent" revision: removes [id] only while [token] is still its pending "Sent" and the
     * system shows the same token. A stale token (already replaced) changes nothing, so it cannot drop the
     * current pending entry. The pending entry is consumed only when [token] matches it. True when removed.
     */
    fun cancelIfCurrent(
        id: Int,
        token: String,
    ): Boolean =
        synchronized(state.lock) {
            val current = state.pendingSent[id] == token
            if (current) state.pendingSent.remove(id)
            val shown = if (current) runCatching { readRevision(id) }.getOrNull() else null
            val matches = current && shown == token
            if (matches) remove(id)
            matches
        }

    /** Drops the pending "Sent" [token] of [id] without removing anything (a clean-up that never ran). */
    fun forget(
        id: Int,
        token: String,
    ) {
        synchronized(state.lock) {
            if (state.pendingSent[id] == token) state.pendingSent.remove(id)
        }
    }

    /** Removes [id] now, whatever its revision, and drops any pending "Sent" for it. */
    fun cancel(id: Int) {
        synchronized(state.lock) {
            state.pendingSent.remove(id)
            remove(id)
        }
    }
}
