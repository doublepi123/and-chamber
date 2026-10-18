package io.andchamber.app.notify

/**
 * Which notification actions an event supports, decided from the event kind alone. Form answers are
 * decided later, once the pending form is fetched; see [FormAnswer].
 *
 *  - ready / goal / error (with a session): a reply field (RemoteInput) that sends a prompt.
 *  - permission (with a session and a request id in the tag): Allow once / Always allow / Deny.
 *  - question: fetched first; a single choice or text field gets answer actions (see [FormAnswer]).
 *  - plugin / opencode-restart-interrupted, or anything without a session: tap to open only.
 */
object ActionPlan {
    enum class Kind {
        Reply,
        Permission,
        Question,
        OpenOnly,
    }

    fun forEvent(event: DesktopNotification): Kind {
        val sessionId = event.sessionId ?: return Kind.OpenOnly
        return when (event.kind) {
            DesktopNotification.Kind.Ready, DesktopNotification.Kind.Goal, DesktopNotification.Kind.Error -> Kind.Reply
            DesktopNotification.Kind.Permission ->
                if (NotificationPayload.permissionRequestId(event.tag, sessionId) != null) Kind.Permission else Kind.OpenOnly
            DesktopNotification.Kind.Question -> Kind.Question
            DesktopNotification.Kind.Plugin, DesktopNotification.Kind.RestartInterrupted, DesktopNotification.Kind.Unknown -> Kind.OpenOnly
        }
    }
}
