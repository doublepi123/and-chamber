package io.andchamber.app.ui.phone

import androidx.compose.runtime.Immutable

/*
 * The phone-control screen's own model. It deliberately does not depend on `phone/core`, so the
 * screen stays stable while the controller changes: the integration layer maps controller state
 * into these types and wires the callbacks in [PhoneControlActions].
 *
 * Nothing here is a grant or a policy decision. The controller enforces its own rules; the screen
 * only refuses to *ask* for something it already knows will not be allowed.
 */

/** Fixed values the screen offers. The controller still enforces its own bounds. */
internal object PhoneUiLimits {
    /** Wireless debugging on this phone. Another host is a manual fallback. */
    const val DEFAULT_HOST = "127.0.0.1"

    /** Lease lengths offered, in minutes. Short on purpose; the controller caps at 30. */
    val DURATION_CHOICES_MIN: List<Int> = listOf(2, 5, 10, 15)
    const val DEFAULT_DURATION_MIN = 5

    const val MAX_SUMMARY_CHARS = 200
    const val PAIRING_CODE_LENGTH = 6
    const val MAX_HOST_CHARS = 253

    /**
     * "No time limit", chosen explicitly by the person for the standing scope or each task in it.
     * The same value as the native `PhoneTimeLimits.PERMANENT`, kept here so the screen doesn't
     * depend on `phone/core`. Used as-is for durations ([PhoneStandingRequest.windowMs],
     * [PhoneStandingRequest.perTaskMs]) and deadlines ([PhoneStandingStatus.Active.endsAtMs],
     * [PhoneLease.expiresAtMs]); never turned into minutes or added to a clock.
     */
    const val PERMANENT_MS: Long = Long.MAX_VALUE
}

/** Everything the screen shows. Immutable; the integration layer publishes a new value per change. */
@Immutable
internal data class PhoneScreenState(
    /** False in builds where the experiment is not offered. Production default. */
    val available: Boolean = false,
    /** The person's switch. Off by default. */
    val enabled: Boolean = false,
    /** This app's package (use `context.packageName`, which a debug test id may change). */
    val hostPackage: String = "io.andchamber.app",
    val device: PhoneDeviceState = PhoneDeviceState(),
    /** Whether a stop entry the person can reach from another app is verified. */
    val stopChannel: PhoneStopChannel = PhoneStopChannel.Unknown,
    val control: PhoneControl = PhoneControl.None,
    val task: PhoneTask = PhoneTask.None,
    /** Apps the person may pick from. Shown only natively; never sent to the model as a list. */
    val targets: List<PhoneTarget> = emptyList(),
    /** What the agent asked for, if it asked. The person still picks and confirms everything. */
    val request: PhoneAgentRequest? = null,
    /** An operation in progress; buttons that would race it are disabled. */
    val pending: PhonePending? = null,
    /**
     * A request was refused because one operation was still running (core `BUSY`). Shown as a
     * passive notice with no retry button; clear it once the running operation settles.
     */
    val busy: PhoneBusy? = null,
    val diagnostics: PhoneDiagnostics = PhoneDiagnostics(),
    /**
     * Configure-once mode: a standing scope within which chat requests start directly. Null keeps
     * the per-task native grant flow.
     */
    val standing: PhoneStanding? = null,
)

// ---- configure once, then use from chat ----------------------------------------------------------

/** Whether the running local OpenChamber really loaded the phone tool. A reachable port isn't enough. */
internal enum class PhoneToolStatus {
    /** The native side confirmed this launch loaded the tool. */
    Ready,

    /** The runtime runs, but started without the tool: one confirmed restart is needed. */
    NeedsRestart,

    /** The confirmed restart is in progress. */
    Restarting,

    /** The local OpenChamber isn't running. */
    RuntimeNotRunning,

    /** Not known yet. Treated as not ready. */
    Unknown,
}

@Immutable
internal data class PhoneRuntimeStatus(
    val tool: PhoneToolStatus = PhoneToolStatus.Unknown,
    /** A short label for the runtime, e.g. "OpenChamber 2.4.0 on this phone". */
    val label: String? = null,
)

internal sealed interface PhoneStandingStatus {
    data object NotSet : PhoneStandingStatus

    /**
     * In effect until [endsAtMs] (same clock as the screen's `clock`). [PhoneUiLimits.PERMANENT_MS]:
     * the person chose no end time; it still ends when revoked or phone control is turned off.
     */
    data class Active(val endsAtMs: Long) : PhoneStandingStatus

    data object Expired : PhoneStandingStatus

    data object Revoked : PhoneStandingStatus
}

/**
 * Why the native side suspended chat use of a saved standing scope. One value per native standing
 * block reason, so the integration layer can map them exhaustively. Display input only.
 */
internal enum class PhoneStandingPauseReason {
    /** The agent asked for a person (a real need_user event). */
    NeedUser,

    /** The person took over the phone. */
    TakenOver,

    /** A dispatched action's outcome is not known. */
    OutcomeUnknown,

    /** The device link or the stop entry dropped. */
    ConnectionLost,

    /** The screen was locked. */
    ScreenLocked,

    /** The local OpenChamber runtime changed (restarted). */
    RuntimeChanged,
}

/**
 * Chat use of the standing scope is suspended until a native person resume. The saved scope itself
 * is unchanged: no re-pairing and no new scope are needed. Opening this screen, reconnecting or the
 * tool becoming ready never ends it.
 */
@Immutable
internal data class PhoneStandingPause(
    val reason: PhoneStandingPauseReason,
    /**
     * An uncertain action has not been checked by the person yet. While true, resuming is never
     * offered. No default on purpose: the integration layer must state it.
     */
    val outcomeUnchecked: Boolean,
)

/**
 * Where a person-confirmed "continue" stands. Never a replay of an action.
 *
 * - [Idle]: nothing requested for this stop.
 * - [Sending]: requested; resuming and waking the chat are in progress.
 * - [Delivered]: the task runs again and the chat was told.
 * - [Failed]: the task runs again (access restored), but the one chat wake-up didn't arrive. Never
 *   re-sent; the person looks in the chat.
 * - [NotResumed]: a technical failure before access came back. The original task stays stopped and
 *   kept; the person may confirm again (see [PhoneContinuation.failure]).
 */
internal enum class PhoneContinueDelivery { Idle, Sending, Delivered, Failed, NotResumed }

/**
 * Why a confirmed continue didn't resume ([PhoneContinueDelivery.NotResumed]). Fixed values mapped by
 * the integration layer; the screen shows fixed copy, never raw text.
 */
internal enum class PhoneContinueFailure {
    /** The scope/runtime/tool wasn't ready at that moment. */
    NotReady,

    /** The saved scope no longer allows it (expired, revoked, changed). */
    ScopeUnavailable,

    /** The stop entry (notification/overlay) couldn't be shown. */
    StopChannelUnavailable,

    /** The task's app couldn't be brought back or isn't allowed any more. */
    TargetUnavailable,

    /** The screen wasn't in a state the agent may act on (e.g. locked, another app on top). */
    ScreenNotIdle,

    /** Reading the screen again took too long. */
    ReadTimeout,

    /** The window kept changing while it was read. */
    WindowUnstable,

    ConnectionLost,

    /** Superseded or cancelled (another stop, a new task, the screen left). */
    Cancelled,

    Other,
}

/** The "continue beside the chat" entry after take over / need you / unknown. */
@Immutable
internal data class PhoneContinuation(
    /** The agent explicitly asked for a person (a real need_user event, not inferred). */
    val needUser: Boolean = false,
    /** The agent's own words for that request. Not verified. */
    val agentNote: String? = null,
    val delivery: PhoneContinueDelivery = PhoneContinueDelivery.Idle,
    /** Set only with [PhoneContinueDelivery.NotResumed]: why the continue didn't resume. */
    val failure: PhoneContinueFailure? = null,
)

/** The standing scope. Chat requests inside it start without a per-task native grant. */
@Immutable
internal data class PhoneStanding(
    val status: PhoneStandingStatus = PhoneStandingStatus.NotSet,
    val apps: List<PhoneTarget> = emptyList(),
    val actions: Set<PhoneActionScope> = emptySet(),
    /** Each task's own lease, inside the standing window. Ignored when [perTaskPermanent]. */
    val perTaskMinutes: Int = PhoneUiLimits.DEFAULT_DURATION_MIN,
    /**
     * The person chose no time limit per task. A task then still ends with a finite standing window,
     * and stops on take-over, screen lock, connection loss or turning phone control off.
     */
    val perTaskPermanent: Boolean = false,
    val runtime: PhoneRuntimeStatus = PhoneRuntimeStatus(),
    val continuation: PhoneContinuation = PhoneContinuation(),
    /** Native standing block, if any. Never Ready while set; only [PhoneControlActions.onResumeStanding] asks to end it. */
    val pause: PhoneStandingPause? = null,
)

/** What the person confirmed natively for the standing scope. */
@Immutable
internal data class PhoneStandingRequest(
    val apps: List<PhoneTarget>,
    val actions: Set<PhoneActionScope>,
    /** [PhoneUiLimits.PERMANENT_MS] only when the person explicitly chose "Permanent". */
    val windowMs: Long,
    /** [PhoneUiLimits.PERMANENT_MS] only when the person explicitly chose "Permanent". */
    val perTaskMs: Long,
)

/** Device connection (wireless debugging), separate from who controls and from the task. */
@Immutable
internal data class PhoneDeviceState(
    val link: PhoneLink = PhoneLink.Disconnected,
    /** True when pairing is known to have succeeded before; null when unknown. */
    val paired: Boolean? = null,
    val hints: PhoneEndpointHints = PhoneEndpointHints(),
    /** The latest problem, if the last pairing/connection attempt failed or the link dropped. */
    val problem: PhoneDeviceProblem? = null,
    val pairingNotice: PhonePairingNotice = PhonePairingNotice.Idle,
    /**
     * Native search for *this phone's own* pairing service while the pairing notification waits.
     * Never another host, never a network scan; the person still types the code themselves.
     */
    val pairingDiscovery: PhoneDiscovery = PhoneDiscovery.Idle,
    /** Native search for this phone's own connection service, before an automatic connect. */
    val connectDiscovery: PhoneDiscovery = PhoneDiscovery.Idle,
    /**
     * A connect the native side started from the found port (after pairing, or from
     * [PhoneControlActions.onAutoConnect]). Connecting is never a grant and never ends a pause.
     */
    val autoConnect: PhoneAutoConnect = PhoneAutoConnect.Idle,
)

/** One native search for a local wireless-debugging service. Only real search results, no guesses. */
internal enum class PhoneDiscovery {
    /** Not searching (not started, cancelled, or no longer relevant). */
    Idle,

    Searching,

    /** Exactly one matching service on this phone was found. */
    Ready,

    /** The search couldn't run (e.g. the system service failed, no Wi-Fi). */
    Unavailable,

    /** Nothing was found in time. */
    TimedOut,

    /** More than one candidate on this phone; the native side won't pick one. */
    Ambiguous,
}

/** An automatic connect to the port the native side found. Manual connects stay [Idle]. */
internal enum class PhoneAutoConnect { Idle, Running, Failed }

internal enum class PhoneLink { Disconnected, Connecting, Connected, Lost }

/** Prefill only. Ports may come from discovery; the person can always type them. */
@Immutable
internal data class PhoneEndpointHints(
    val host: String = PhoneUiLimits.DEFAULT_HOST,
    val pairingPort: Int? = null,
    val connectPort: Int? = null,
)

internal enum class PhoneDeviceProblem {
    WirelessDebuggingOff,
    NoWifi,
    PairingCodeRejected,
    PairingPortUnreachable,
    ConnectRefused,
    NotAuthorized,
    ConnectionLost,
    Other,
}

/** The "enter the code from the notification" flow. The code itself never reaches this screen. */
internal enum class PhonePairingNotice {
    Idle,

    /** A pairing notification was requested. Posting it does not mean the person can see it. */
    Waiting,

    /** Notifications are off for the app, so the pairing notification can't be shown. */
    NotificationsBlocked,
}

/** A stop entry the person can reach while another app is in front. Only [Verified] allows a start. */
internal enum class PhoneStopChannel {
    Verified,
    NotificationsDisabled,
    ChannelDisabled,
    Unknown,
}

/**
 * An app the person can choose. [system] marks apps the outer layer knows are system apps.
 * [verdict], when the native policy supplies it, decides instead of the screen's fallback rules, so
 * ordinary preinstalled or Google apps can be allowed while settings/permission/password apps stay
 * refused. The host app is always refused.
 */
@Immutable
internal data class PhoneTarget(
    val label: String,
    val packageName: String,
    val system: Boolean = false,
    val verdict: PhoneTargetVerdict? = null,
)

/** The native policy's decision for one app. */
internal enum class PhoneTargetVerdict { Allowed, Host, System, Sensitive }

internal enum class PhoneActionScope { Tap, Swipe, Input, Back }

/** Who is in control. Separate from the device link and the task. */
internal sealed interface PhoneControl {
    /** No lease: the agent can't act. */
    data object None : PhoneControl

    /** The agent holds a lease for [lease]. [actionInFlight]: a dispatched action hasn't settled. */
    data class Agent(
        val lease: PhoneLease,
        val actionInFlight: Boolean = false,
    ) : PhoneControl

    /**
     * The agent was stopped. [confirmed] is true only once the executor confirmed it took no more
     * actions; until then the pause was requested but is not yet in effect.
     */
    data class Halted(
        val lease: PhoneLease,
        val reason: PhoneHaltReason,
        val confirmed: Boolean,
        val lastAction: PhoneLastAction,
    ) : PhoneControl
}

@Immutable
internal data class PhoneLease(
    val target: PhoneTarget,
    val summary: String,
    val actions: Set<PhoneActionScope>,
    /** [PhoneUiLimits.PERMANENT_MS]: no time limit (shown without a countdown). */
    val expiresAtMs: Long,
)

internal enum class PhoneHaltReason {
    Paused,

    /** The person took over (stop/overlay take-over). Not an agent request. */
    TakenOver,

    /** The agent asked for the person (a real native-confirmed need_user). Not a take-over. */
    NeedUser,
    Expired,
    HostReturned,
    TargetLeft,
    ScreenLocked,
    ConnectionLost,
    StopChannelLost,
    ScreenUncertain,
    OutcomeUnknown,
    RuntimeChanged,
}

/** What is known about the last dispatched action when the agent stopped. */
internal enum class PhoneLastAction {
    /** The executor confirmed nothing was in flight. Only then may "no further steps" be said. */
    NoneInFlight,

    /** An action was dispatched (or it can't be told): it may still take effect. Must be checked. */
    MayStillApply,

    /** The person said they checked it. Not an execution confirmation. */
    UserChecked,
}

internal sealed interface PhoneTask {
    data object None : PhoneTask

    data class Active(
        val target: PhoneTarget,
        val summary: String,
    ) : PhoneTask

    data class Ended(val result: PhoneTaskResult) : PhoneTask
}

@Immutable
internal data class PhoneTaskResult(
    val target: PhoneTarget?,
    val summary: String,
    val items: List<PhoneResultItem>,
    /**
     * The agent itself reported the task finished while it still held a clean live grant (no stop,
     * no uncertain action). Still not verified, but it needs only a light, dismissible note instead
     * of the full "check the result" step. False for every person stop, take-over or uncertain end.
     */
    val agentReported: Boolean = false,
)

/** One reported step. [text] is the agent's own words, shown as-is. */
@Immutable
internal data class PhoneResultItem(
    val text: String,
    val check: PhoneCheck,
)

internal enum class PhoneCheck {
    /** The result was re-read from the target app's screen and matched. */
    Verified,

    /** The tool said ok, but nothing re-read the result. Never counted as done. */
    AgentReported,

    /** Interrupted, timed out or uncertain. */
    Unknown,
}

internal enum class PhoneOverall { Verified, Partial, NeedsCheck }

/** The agent's proposal. A package name here only preselects; the person still chooses. */
@Immutable
internal data class PhoneAgentRequest(
    val summary: String,
    val packageName: String? = null,
)

/** Why the last request was refused as busy. Never a prompt to press again. */
internal enum class PhoneBusy {
    /** The agent's new action was refused: its previous action hasn't settled. */
    AgentActionRefused,

    /** A button press here was ignored: another step (pairing, connecting, pausing…) is running. */
    RequestIgnored,
}

/** The two halt facts the screen needs, derived by [phoneHaltFacts]. */
@Immutable
internal data class PhoneHaltFacts(
    val confirmed: Boolean,
    val lastAction: PhoneLastAction,
)

/** [Resuming]: a person-confirmed standing resume ([PhoneControlActions.onResumeStanding]) is running. */
internal enum class PhonePending { Enabling, Pairing, Connecting, Disconnecting, Granting, Pausing, Finishing, Observing, Resuming }

/** Shown only in the collapsed details. [lastError] is a raw technical line, never a secret. */
@Immutable
internal data class PhoneDiagnostics(
    val runtime: String? = null,
    val connection: String? = null,
    val observedAtMs: Long? = null,
    val observedNodes: Int? = null,
    val lastError: String? = null,
)

/**
 * A pairing attempt. Not a data class, so the code never appears in `toString()` (logs, crash
 * reports). The screen clears its own copy of the code as soon as this is handed over.
 */
internal class PhonePairRequest(
    val host: String,
    val port: Int,
    val code: String,
) {
    override fun toString(): String = "PhonePairRequest(host=$host, port=$port, code=<redacted>)"
}

@Immutable
internal data class PhoneConnectRequest(
    val host: String,
    val port: Int,
)

/** What the person confirmed natively. A new request every time; nothing is renewed implicitly. */
@Immutable
internal data class PhoneGrantRequest(
    val target: PhoneTarget,
    val summary: String,
    val durationMs: Long,
    val actions: Set<PhoneActionScope>,
)

/**
 * Everything the screen can ask for. Each callback is a request; the screen shows the outcome only
 * through the next [PhoneScreenState].
 */
@Immutable
internal class PhoneControlActions(
    val onBack: () -> Unit = {},
    /** True only after the person confirmed the risks. False ends any task and disconnects. */
    val onEnable: (Boolean) -> Unit = {},
    val onOpenWirelessSettings: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
    /** Re-check whether the stop entry can be shown (e.g. after returning from settings). */
    val onRecheckStopChannel: () -> Unit = {},
    /**
     * Post the pairing notification with RemoteInput; [host] is the host to pair with. The main
     * path passes [PhoneUiLimits.DEFAULT_HOST]: the native side then looks for this phone's pairing
     * port itself and asks only for the code. Also used by "search again" while pairing.
     */
    val onBeginPairingNotification: (host: String) -> Unit = {},
    val onCancelPairingNotification: () -> Unit = {},
    val onPair: (PhonePairRequest) -> Unit = {},
    val onConnect: (PhoneConnectRequest) -> Unit = {},
    /**
     * Find this phone's own connection port and connect to it (no port typed). Also "search
     * again" after a failed search. Only restores the link: never a grant, never ends a pause.
     */
    val onAutoConnect: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    /** A fresh, natively confirmed grant. Also the only way to resume after a pause. */
    val onGrant: (PhoneGrantRequest) -> Unit = {},
    val onPause: () -> Unit = {},
    /** Pause the agent and bring the target app forward for the person. */
    val onTakeOver: () -> Unit = {},
    val onFinish: () -> Unit = {},
    /** Read the target screen again, for the details shown here. */
    val onObserve: () -> Unit = {},
    val onOpenTarget: (packageName: String) -> Unit = {},
    /** The person says they checked an uncertain action. Never a retry. */
    val onAcknowledgeChecked: () -> Unit = {},
    val onDismissResult: () -> Unit = {},
    // ---- configure once (all optional; unused while `standing` is null) ----
    /** Save the standing scope the person confirmed. Replaces any previous one. */
    val onSaveStanding: (PhoneStandingRequest) -> Unit = {},
    val onRevokeStanding: () -> Unit = {},
    /** Restart the local OpenChamber so it loads the phone tool. Only after the person confirmed. */
    val onRestartRuntime: () -> Unit = {},
    /** Re-read whether the tool is loaded. */
    val onRecheckRuntime: () -> Unit = {},
    /** Open the local chat. Doesn't revoke anything by itself. */
    val onOpenChat: () -> Unit = {},
    /**
     * After the person confirmed: let the stopped task continue inside the standing scope and wake
     * the chat. The agent observes again first; nothing earlier is re-sent.
     */
    val onContinueInChat: () -> Unit = {},
    /**
     * Only after the person confirmed in the dialog: end a standing pause that has no stopped task,
     * so chat may start new tasks inside the same saved scope. Never starts a task, never re-sends,
     * never extends or changes the scope. Never called on screen open or reconnect.
     */
    val onResumeStanding: () -> Unit = {},
)
