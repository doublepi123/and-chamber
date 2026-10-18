package io.andchamber.app.notify

/** Which Android channel an event belongs to. Names map to the channels created by the notifier. */
enum class Channel(val id: String) {
    AgentFinished("agent-finished"),
    NeedsInput("needs-input"),
    Errors("errors"),
    Other("other"),
}

/** What the notifier should do with one OpenChamber event. */
sealed interface Delivery {
    /** Post a notification on [channel] with [kind]-specific actions. */
    data class Post(val channel: Channel) : Delivery

    /** Not shown: the app is in the foreground and the web UI already shows the event. */
    data object Suppressed : Delivery
}

object NotifyPolicy {
    /** Channel for an OpenChamber event kind. Plugin and unknown kinds go to Other. */
    fun channelFor(kind: DesktopNotification.Kind): Channel =
        when (kind) {
            DesktopNotification.Kind.Ready, DesktopNotification.Kind.Goal -> Channel.AgentFinished
            DesktopNotification.Kind.Question, DesktopNotification.Kind.Permission -> Channel.NeedsInput
            DesktopNotification.Kind.Error, DesktopNotification.Kind.RestartInterrupted -> Channel.Errors
            DesktopNotification.Kind.Plugin, DesktopNotification.Kind.Unknown -> Channel.Other
        }

    /**
     * OpenChamber events are shown only while the app is in the background. In the foreground the web UI
     * already shows its own toast, so the native copy would be a duplicate.
     */
    fun deliveryFor(
        kind: DesktopNotification.Kind,
        appInForeground: Boolean,
    ): Delivery = if (appInForeground) Delivery.Suppressed else Delivery.Post(channelFor(kind))

    /**
     * AndChamber's own runtime errors are always shown, even in the foreground, because the web UI does
     * not show them.
     */
    fun runtimeErrorDelivery(): Delivery = Delivery.Post(Channel.Errors)
}
