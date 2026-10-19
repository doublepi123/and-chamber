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

    /** Not shown: the local OpenChamber page is on screen and shows the event itself. */
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
     * True only while the user can actually see the local OpenChamber page: the screen shows the local
     * server ([isLocal]), it is resumed ([resumed]: not paused, not behind another activity or a file
     * picker, not mid-transition), and nothing covers the page ([overlayShown]: starting, errors,
     * connecting, an isolation panel, a page that hasn't painted yet). Home, a remote server or any
     * covered page is false, so the event is posted natively.
     */
    fun localPageShowing(
        isLocal: Boolean,
        resumed: Boolean,
        overlayShown: Boolean,
    ): Boolean = isLocal && resumed && !overlayShown

    /**
     * Whether an event may be left to the page: only when OpenChamber marks it [requireHidden] (its
     * "hidden-only" mode) and the local page is showing. In "always" mode (requireHidden false) the user
     * wants a system notification even while looking at the page, which only the app can post.
     */
    fun leaveToPage(
        requireHidden: Boolean,
        localPageShowing: Boolean,
    ): Boolean = requireHidden && localPageShowing

    /**
     * OpenChamber events are posted unless [localPageShowing] holds: then the local web UI on screen
     * already shows the event, so a native copy would be a duplicate. Everywhere else (Home, a remote
     * server's page, a covered page, the background) the native notification is the only place the
     * event shows up.
     */
    fun deliveryFor(
        kind: DesktopNotification.Kind,
        localPageShowing: Boolean,
    ): Delivery = if (localPageShowing) Delivery.Suppressed else Delivery.Post(channelFor(kind))

    /**
     * AndChamber's own runtime errors are always shown, even in the foreground, because the web UI does
     * not show them.
     */
    fun runtimeErrorDelivery(): Delivery = Delivery.Post(Channel.Errors)
}
