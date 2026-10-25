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
)

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

/** An app the person can choose. [system] marks apps the outer layer knows are system apps. */
@Immutable
internal data class PhoneTarget(
    val label: String,
    val packageName: String,
    val system: Boolean = false,
)

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
    val expiresAtMs: Long,
)

internal enum class PhoneHaltReason {
    Paused,
    TakenOver,
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

internal enum class PhonePending { Enabling, Pairing, Connecting, Disconnecting, Granting, Pausing, Finishing, Observing }

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
    /** Post the pairing notification with RemoteInput; [host] is the host to pair with. */
    val onBeginPairingNotification: (host: String) -> Unit = {},
    val onCancelPairingNotification: () -> Unit = {},
    val onPair: (PhonePairRequest) -> Unit = {},
    val onConnect: (PhoneConnectRequest) -> Unit = {},
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
)
