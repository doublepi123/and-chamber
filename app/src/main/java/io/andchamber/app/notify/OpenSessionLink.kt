package io.andchamber.app.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A session the user asked to open from a notification. Set by the activity on cold start and on
 * `onNewIntent`; observed through [pending] and taken once by the UI, which opens it in the local
 * web screen as `<base>/?session=<id>`. Process-wide so a notification tap that launches a fresh
 * activity still reaches the screen.
 */
object OpenSessionLink {
    private val mutablePending = MutableStateFlow<Target?>(null)

    /** The target waiting to be opened, or null. Observe it, then [take] it to open it. */
    val pending: StateFlow<Target?> = mutablePending.asStateFlow()

    data class Target(val sessionId: String, val directory: String?)

    /** Stores a target from an intent's extras. A missing session id clears nothing and is ignored. */
    fun offer(
        sessionId: String?,
        directory: String?,
    ) {
        if (sessionId.isNullOrBlank()) return
        mutablePending.value = Target(sessionId, directory?.takeIf { it.isNotBlank() })
    }

    /** Returns and clears the pending target, so it opens once. */
    fun take(): Target? = mutablePending.getAndUpdate { null }

    /** The URL path and query the local web UI understands for a session. */
    fun pathFor(sessionId: String): String = "/?session=" + java.net.URLEncoder.encode(sessionId, "UTF-8").replace("+", "%20")
}
