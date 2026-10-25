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

    /**
     * The last revision this process wrote for each id, recorded before the write. The system can show
     * an older notification for a while after a newer write; [latestWritten] is the local authority
     * that closes that gap.
     */
    val latestWritten = HashMap<Int, String>()

    /**
     * The last revision a user swiped away while its Sending/Checking write was in flight. A late
     * result or recovery for exactly that revision must not redraw the notification. A new publish for
     * the id clears the entry. Bounded, like the local revision table.
     */
    val dismissedRevisions = LinkedHashMap<Int, String>()
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
     * Runs one write for [id] under the lock, with a fresh token. The pending "Sent" of [id] is dropped first,
     * and the token becomes [NotificationRevisionState.latestWritten] before the write (so a newer revision
     * is authoritative even while the system still shows the old notification).
     * When [sent] is true the new token becomes the pending "Sent"; a failed write leaves nothing pending.
     */
    fun <T> publish(
        id: Int,
        sent: Boolean,
        write: (token: String) -> T,
    ): T =
        synchronized(state.lock) {
            state.pendingSent.remove(id)
            state.dismissedRevisions.remove(id)
            val token = newToken()
            state.latestWritten[id] = token
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

    /**
     * Runs [write] only while [token] is not older than the newest revision this process wrote for [id]
     * ([NotificationRevisionState.latestWritten]) and is the one the system shows. The system can lag
     * behind a newer publish; checking only it would let a stale action run while the old notification
     * is still visible. When there is no local record (a fresh process after a restart) the system's
     * revision is trusted instead, so actions still work after process death. Both checks and the write
     * happen under [state]'s lock, so no newer write can land between them (no TOCTOU). A missing or
     * failed read runs nothing. True when [write] ran.
     *
     * Unlike [cancelIfCurrent] this does not consult the pending "Sent" table: a plain post (sent =
     * false) can be matched too. [write] may itself write through this gate; the lock is reentrant.
     */
    fun ifCurrent(
        id: Int,
        token: String,
        write: () -> Unit,
    ): Boolean =
        synchronized(state.lock) {
            // Only a newer local token vetoes; no record means "trust the system" (process restart).
            if (state.dismissedRevisions[id] == token) return@synchronized false
            val newest = state.latestWritten[id]
            if (newest != null && newest != token) return@synchronized false
            val shown = runCatching { readRevision(id) }.getOrNull()
            if (shown == null || shown != token) return@synchronized false
            write()
            true
        }

    /** Records that the user swiped the notification showing [token]; its late result must not redraw. */
    fun markDismissed(
        id: Int,
        token: String,
    ) {
        synchronized(state.lock) {
            state.dismissedRevisions[id] = token
            if (state.dismissedRevisions.size > MAX_DISMISSED) {
                state.dismissedRevisions.entries.firstOrNull()?.let { state.dismissedRevisions.remove(it.key) }
            }
        }
    }

    /**
     * Runs [write] only while [token] is still the newest revision this process wrote for [id]
     * ([NotificationRevisionState.latestWritten]). The system is deliberately not consulted: this is
     * the recovery path for a lagging SystemUI, and a locally-owned revision must be able to repost an
     * actionable notification. A newer local write makes it a no-op. The lock is reentrant.
     */
    fun <T> publishIfLatest(
        id: Int,
        token: String,
        write: () -> T,
    ): T? =
        synchronized(state.lock) {
            if (state.dismissedRevisions[id] == token) return@synchronized null
            if (state.latestWritten[id] != token) return@synchronized null
            write()
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

    /** Removes [id] now, whatever its revision, and drops its pending "Sent" and latest token. */
    fun cancel(id: Int) {
        synchronized(state.lock) {
            state.pendingSent.remove(id)
            state.latestWritten.remove(id)
            state.dismissedRevisions.remove(id)
            remove(id)
        }
    }

    private companion object {
        const val MAX_DISMISSED = 128
    }
}
