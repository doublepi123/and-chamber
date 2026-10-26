package io.andchamber.app.phone.android

/**
 * Native-only one-shot wake-up of the original chat after a confirmed continuation.
 *
 * The production implementation reuses `LocalApiClient.send(NotificationActions.sendReply(...))`
 * and reports true only for a 2xx response. It never retries, never logs the session id or prompt,
 * and never turns a failed send into a rollback of a lease that was already established.
 */
internal fun interface PhoneStandingContinueSender {
    suspend fun send(
        sessionId: String,
        text: String,
    ): Boolean

    object Unavailable : PhoneStandingContinueSender {
        override suspend fun send(
            sessionId: String,
            text: String,
        ): Boolean = false
    }
}

/** Fixed, bounded continuation prompt. It asks for a fresh observation and forbids replay. */
internal const val STANDING_CONTINUE_PROMPT =
    "Continue the previous task. First observe the phone screen again; do not repeat any action that already happened."
