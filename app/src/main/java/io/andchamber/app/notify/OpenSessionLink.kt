package io.andchamber.app.notify

/**
 * A session the user asked to open from a notification. Set by the activity on cold start and on
 * `onNewIntent`; consumed once by the local web screen, which then loads `<base>/?session=<id>`.
 * Process-wide so a notification tap that launches a fresh activity still reaches the screen.
 */
object OpenSessionLink {
    @Volatile
    private var pending: Target? = null

    data class Target(val sessionId: String, val directory: String?)

    /** Stores a target from an intent's extras. A missing session id clears nothing and is ignored. */
    fun offer(
        sessionId: String?,
        directory: String?,
    ) {
        if (sessionId.isNullOrBlank()) return
        pending = Target(sessionId, directory?.takeIf { it.isNotBlank() })
    }

    /** Returns and clears the pending target, so it opens once. */
    fun take(): Target? {
        val target = pending
        pending = null
        return target
    }

    /** The URL path and query the local web UI understands for a session. */
    fun pathFor(sessionId: String): String = "/?session=" + java.net.URLEncoder.encode(sessionId, "UTF-8").replace("+", "%20")
}
