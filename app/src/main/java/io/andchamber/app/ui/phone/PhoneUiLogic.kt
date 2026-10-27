package io.andchamber.app.ui.phone

/*
 * Pure presentation rules for the phone-control screen: no Android or Compose imports, so they are
 * unit-tested directly. The controller is authoritative; these only decide what the screen offers.
 */

/** The one step the screen puts in front. */
internal enum class PhoneStep {
    /** The experiment is not offered in this build. */
    Unavailable,

    /** The switch is off. */
    Off,

    /** Pair and connect wireless debugging. */
    Connect,

    /** Pick an app and confirm a short grant. */
    Grant,

    /** The agent holds a lease. */
    Running,

    /** The agent was stopped; the screen shows what is and isn't known. */
    Halted,

    /** The task ended; results wait to be checked. */
    Result,
}

internal fun phoneStep(state: PhoneScreenState): PhoneStep =
    when {
        !state.available -> PhoneStep.Unavailable
        !state.enabled -> PhoneStep.Off
        state.control is PhoneControl.Agent -> PhoneStep.Running
        state.control is PhoneControl.Halted -> PhoneStep.Halted
        state.task is PhoneTask.Ended -> PhoneStep.Result
        state.device.link != PhoneLink.Connected -> PhoneStep.Connect
        else -> PhoneStep.Grant
    }

/** Why an app can't be chosen. Mirrors the controller's refusal list; the controller still decides. */
internal enum class TargetBlock { Host, System, Invalid, Sensitive }

private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
private val SYSTEM_PACKAGES =
    setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
    )
private val SYSTEM_PREFIXES = listOf("android.", "com.android.", "com.google.android.")

internal fun targetBlock(
    target: PhoneTarget,
    hostPackage: String,
): TargetBlock? =
    when {
        target.packageName == hostPackage || target.packageName.startsWith("$hostPackage.") -> TargetBlock.Host
        target.packageName.isBlank() || target.packageName.length > 200 -> TargetBlock.Invalid
        // Core platform/settings packages are refused whatever a verdict says.
        target.packageName in SYSTEM_PACKAGES -> TargetBlock.System
        !PACKAGE_PATTERN.matches(target.packageName) -> TargetBlock.Invalid
        // The native policy decides when it gave a verdict: it may allow ordinary preinstalled apps.
        target.verdict == PhoneTargetVerdict.Allowed -> null
        target.verdict == PhoneTargetVerdict.Host -> TargetBlock.Host
        target.verdict == PhoneTargetVerdict.System -> TargetBlock.System
        target.verdict == PhoneTargetVerdict.Sensitive -> TargetBlock.Sensitive
        // Without a verdict, the screen's conservative fallback applies.
        target.system || target.packageName in SYSTEM_PACKAGES -> TargetBlock.System
        SYSTEM_PREFIXES.any { target.packageName.startsWith(it) } -> TargetBlock.System
        !PACKAGE_PATTERN.matches(target.packageName) -> TargetBlock.Invalid
        else -> null
    }

/** Allowed apps first (by label), blocked ones after, filtered by [query] on label or package. */
internal fun visibleTargets(
    targets: List<PhoneTarget>,
    hostPackage: String,
    query: String,
): List<Pair<PhoneTarget, TargetBlock?>> {
    val needle = query.trim()
    return targets
        .asSequence()
        .filter { needle.isEmpty() || it.label.contains(needle, ignoreCase = true) || it.packageName.contains(needle, ignoreCase = true) }
        .map { it to targetBlock(it, hostPackage) }
        .sortedWith(compareBy<Pair<PhoneTarget, TargetBlock?>> { it.second != null }.thenBy { it.first.label.lowercase() })
        .toList()
}

/** Rows the app picker shows. Blocked apps are summarised in one line unless searched for. */
internal data class TargetListing(
    val rows: List<Pair<PhoneTarget, TargetBlock?>>,
    /** Allowed apps not shown (search to find them). */
    val moreAllowed: Int,
    /** Blocked apps not shown; their reason appears only when a search names them. */
    val hiddenBlocked: Int,
)

/** Allowed apps listed without a search: enough to pick from without pushing the form away. */
internal const val TARGETS_DEFAULT_ROWS = 5

/** Allowed matches listed for a search. */
internal const val TARGETS_SEARCH_ROWS = 10

/** Blocked matches listed for a search, each with its reason. */
internal const val TARGETS_SEARCH_BLOCKED_ROWS = 3

/**
 * Compact picker contents. Without a search only allowed apps are listed (a few); every blocked app
 * is folded into a count. A search lists allowed matches first, then a few blocked matches with
 * their reason, so a person looking for one learns why it can't be chosen. The chosen app always
 * stays listed.
 */
internal fun targetListing(
    targets: List<PhoneTarget>,
    hostPackage: String,
    query: String,
    selected: String?,
): TargetListing {
    val matches = visibleTargets(targets, hostPackage, query)
    val allowed = matches.filter { it.second == null }
    val blocked = matches.filter { it.second != null }
    val searching = query.isNotBlank()
    val allowedRows = allowed.take(if (searching) TARGETS_SEARCH_ROWS else TARGETS_DEFAULT_ROWS)
    val blockedRows = if (searching) blocked.take(TARGETS_SEARCH_BLOCKED_ROWS) else emptyList()
    var rows = allowedRows + blockedRows
    val chosen = allowed.firstOrNull { it.first.packageName == selected }
    if (chosen != null && chosen !in rows) rows = listOf(chosen) + rows
    val shownAllowed = rows.count { it.second == null }
    return TargetListing(
        rows = rows,
        moreAllowed = allowed.size - shownAllowed,
        hiddenBlocked = blocked.size - blockedRows.size,
    )
}

/** The first reason a grant can't be asked for yet, in the order the person should fix them. */
internal enum class GrantBlock { NotConnected, RuntimeNotReady, StopChannel, NoTarget, TargetNotAllowed, NoSummary, SummaryTooLong, NoActions, Busy }

internal fun grantBlock(
    state: PhoneScreenState,
    target: PhoneTarget?,
    summary: String,
    actions: Set<PhoneActionScope>,
): GrantBlock? =
    when {
        state.device.link != PhoneLink.Connected -> GrantBlock.NotConnected
        phoneRuntimeGate(state) != null -> GrantBlock.RuntimeNotReady
        state.stopChannel != PhoneStopChannel.Verified -> GrantBlock.StopChannel
        target == null -> GrantBlock.NoTarget
        targetBlock(target, state.hostPackage) != null -> GrantBlock.TargetNotAllowed
        summary.isBlank() -> GrantBlock.NoSummary
        summary.trim().length > PhoneUiLimits.MAX_SUMMARY_CHARS -> GrantBlock.SummaryTooLong
        actions.isEmpty() -> GrantBlock.NoActions
        state.pending != null -> GrantBlock.Busy
        else -> null
    }

/**
 * A halted task whose device link is down gets the normal connect form under the halt card, so the
 * person can restore the transport without ending the task. Connecting changes only the link: the
 * control stays Halted and continuing still needs a fresh native confirmation.
 */
internal fun showsReconnect(state: PhoneScreenState): Boolean =
    phoneStep(state) == PhoneStep.Halted && state.device.link != PhoneLink.Connected

/**
 * Identifies "a different page" for scrolling: when it changes, the screen returns to the top and
 * drops input focus, so a new step never opens mid-way through the previous form. Within Halted the
 * link counts too, so a successful reconnect brings the kept task and the continue entry into view.
 * Progress, countdowns and notices inside one step don't change it.
 */
internal fun phoneScrollAnchor(state: PhoneScreenState): String {
    val step = phoneStep(state)
    return if (step == PhoneStep.Halted) {
        step.name + ":" + (state.device.link == PhoneLink.Connected)
    } else {
        step.name
    }
}

/**
 * The overlay settings card sits below the step once phone control is on, but not while the agent
 * holds a lease: changing how a running task is followed mid-task would be confusing.
 */
internal fun showsOverlaySettings(step: PhoneStep): Boolean =
    step == PhoneStep.Connect || step == PhoneStep.Grant || step == PhoneStep.Halted || step == PhoneStep.Result

/**
 * Whether "continue with the agent" may be offered after a halt. Never while the stop is not
 * confirmed or a dispatched action may still apply (the person checks first), and never without
 * a link and a verified stop entry. Continuing always goes through a fresh grant.
 */
internal fun canOfferResume(state: PhoneScreenState): Boolean {
    val halted = state.control as? PhoneControl.Halted ?: return false
    return halted.confirmed &&
        halted.lastAction != PhoneLastAction.MayStillApply &&
        state.device.link == PhoneLink.Connected &&
        state.stopChannel == PhoneStopChannel.Verified &&
        phoneRuntimeGate(state) == null &&
        state.pending == null
}

/**
 * What the person filled in for a grant. Kept outside the step's composition (see
 * [PhoneGrantDraftHolder]) so leaving for Home to start or restart OpenChamber doesn't lose it.
 * In memory only; never saved to disk.
 */
internal data class PhoneGrantDraft(
    val packageName: String? = null,
    val summary: String = "",
    val minutes: Int = PhoneUiLimits.DEFAULT_DURATION_MIN,
    val actions: Set<PhoneActionScope> = DEFAULT_ACTIONS,
    /** The agent proposal this draft last took its text from. */
    val requestSummary: String? = null,
)

/**
 * Applies a new agent proposal once: its text replaces the summary and its app is preselected if
 * allowed. The same proposal seen again (e.g. after returning from Home) leaves the draft alone.
 */
internal fun PhoneGrantDraft.withRequest(state: PhoneScreenState): PhoneGrantDraft {
    val request = state.request
    if (request == null) {
        return if (packageName == null) copy(packageName = preselectedTarget(state)?.packageName) else this
    }
    if (request.summary == requestSummary) return this
    return copy(
        summary = request.summary,
        requestSummary = request.summary,
        packageName = preselectedTarget(state)?.packageName ?: packageName,
    )
}

/**
 * Maps the controller's halt flags to what the screen may say. Integration passes core
 * `PhoneState.pausing`, `PhoneState.inFlightMutation`, `blocked == UNKNOWN_OUTCOME`, and whether the
 * person acknowledged that unknown outcome.
 *
 * - Anything still in flight (or the pause still settling) is unconfirmed and may still apply.
 * - Settled with an unknown outcome: the stop is in effect, but the last action may still apply.
 * - Settled cleanly: only then may "no further steps" be said.
 */
internal fun phoneHaltFacts(
    pausing: Boolean,
    inFlightMutation: Boolean,
    outcomeUnknown: Boolean,
    acknowledged: Boolean = false,
): PhoneHaltFacts =
    when {
        pausing || inFlightMutation -> PhoneHaltFacts(confirmed = false, lastAction = PhoneLastAction.MayStillApply)
        outcomeUnknown && !acknowledged -> PhoneHaltFacts(confirmed = true, lastAction = PhoneLastAction.MayStillApply)
        outcomeUnknown -> PhoneHaltFacts(confirmed = true, lastAction = PhoneLastAction.UserChecked)
        else -> PhoneHaltFacts(confirmed = true, lastAction = PhoneLastAction.NoneInFlight)
    }

/**
 * Fixed technical tokens the integration may put in [PhoneDiagnostics.lastError]. The screen never
 * shows a token as the message; it maps known ones to fixed copy and keeps the raw token in Details.
 */
internal object PhoneDiagnosticTokens {
    /** Granted, but no chat route could be published: the tool isn't loaded in the running server. */
    const val GRANT_ROUTE_UNAVAILABLE = "grant_route_unavailable"

    /** Optional: a grant refused because the screen was locked. */
    const val SCREEN_LOCKED = "screen_locked"

    /** Optional: a grant refused because the chosen app isn't in the native list (any more). */
    const val TARGET_UNAVAILABLE = "target_unavailable"

    /** The local runtime runs, but this launch didn't load the phone tool: the person restarts it. */
    const val RUNTIME_RESTART_REQUIRED = "runtime_restart_required"

    /** The local runtime isn't running: the person starts it from Home first. */
    const val RUNTIME_NOT_RUNNING = "runtime_not_running"
}

/** The local runtime isn't ready for phone control. Persistent until the integration clears it. */
internal enum class PhoneRuntimeGate { RestartRequired, NotRunning }

internal fun phoneRuntimeGate(state: PhoneScreenState): PhoneRuntimeGate? =
    when (state.diagnostics.lastError) {
        PhoneDiagnosticTokens.RUNTIME_RESTART_REQUIRED -> PhoneRuntimeGate.RestartRequired
        PhoneDiagnosticTokens.RUNTIME_NOT_RUNNING -> PhoneRuntimeGate.NotRunning
        else -> null
    }

/** Whether the runtime notice is shown: on every working step, not on the off/unavailable pages. */
internal fun showsRuntimeGate(state: PhoneScreenState): Boolean =
    phoneRuntimeGate(state) != null &&
        when (phoneStep(state)) {
            PhoneStep.Connect, PhoneStep.Grant, PhoneStep.Running, PhoneStep.Halted -> true
            PhoneStep.Unavailable, PhoneStep.Off, PhoneStep.Result -> false
        }

/** A fixed, local explanation for a failed request outside the connect step. */
internal enum class PhoneFeedback {
    /** The grant was withdrawn because the phone tool isn't loaded; a server restart is needed. */
    ToolNotReady,
    ScreenLocked,
    TargetUnavailable,

    /** The grant didn't start, cause not known to the screen. Nothing was granted. */
    GrantNotStarted,

    /** A request during or after a task didn't complete. Says nothing about earlier actions. */
    RequestNotDone,
}

/**
 * What to explain next to the task cards. The connect step shows [PhoneDeviceState.problem] itself,
 * and connection problems during a task are already explained by the halt reason, so only generic
 * failures are mapped here. Null when there is nothing to explain.
 */
internal fun phoneFeedback(state: PhoneScreenState): PhoneFeedback? {
    if (state.device.problem != PhoneDeviceProblem.Other) return null
    // The runtime notice already explains it; never show both.
    if (phoneRuntimeGate(state) != null) return null
    // A new attempt is running; the old explanation would read as its result.
    if (state.pending != null) return null
    val step = phoneStep(state)
    if (step != PhoneStep.Grant && step != PhoneStep.Running && step != PhoneStep.Halted) return null
    return when (state.diagnostics.lastError) {
        PhoneDiagnosticTokens.GRANT_ROUTE_UNAVAILABLE -> PhoneFeedback.ToolNotReady
        PhoneDiagnosticTokens.SCREEN_LOCKED -> PhoneFeedback.ScreenLocked
        PhoneDiagnosticTokens.TARGET_UNAVAILABLE -> PhoneFeedback.TargetUnavailable
        else -> if (step == PhoneStep.Grant) PhoneFeedback.GrantNotStarted else PhoneFeedback.RequestNotDone
    }
}

/**
 * Reading the target screen needs a live agent lease. Returning to AndChamber revokes it, so from
 * this screen it is usually unavailable; the button stays disabled rather than reading anything
 * the person did not grant.
 */
internal fun canObserve(state: PhoneScreenState): Boolean {
    val agent = state.control as? PhoneControl.Agent ?: return false
    return !agent.actionInFlight && state.device.link == PhoneLink.Connected && state.pending == null
}

/** "Verified" only when every step was re-read and matched; any verified step makes it partial. */
internal fun overallResult(items: List<PhoneResultItem>): PhoneOverall =
    when {
        items.isEmpty() -> PhoneOverall.NeedsCheck
        items.all { it.check == PhoneCheck.Verified } -> PhoneOverall.Verified
        items.any { it.check == PhoneCheck.Verified } -> PhoneOverall.Partial
        else -> PhoneOverall.NeedsCheck
    }

// ---- connect without typing ports ----------------------------------------------------------------

/** The filled button of the connect card. Everything else there is secondary. */
internal enum class ConnectPrimary {
    /** Not known to be paired (or pairing again): pair first, through the notification. */
    PairWithNotification,

    /** Paired: find this phone's own connection port and connect, nothing to type. */
    AutoConnect,
}

/** Why a search for this phone's own service ended without one clear result. */
internal enum class DiscoveryProblem { NotFound, Unavailable, Ambiguous }

internal fun discoveryProblem(discovery: PhoneDiscovery): DiscoveryProblem? =
    when (discovery) {
        PhoneDiscovery.TimedOut -> DiscoveryProblem.NotFound
        PhoneDiscovery.Unavailable -> DiscoveryProblem.Unavailable
        PhoneDiscovery.Ambiguous -> DiscoveryProblem.Ambiguous
        PhoneDiscovery.Idle, PhoneDiscovery.Searching, PhoneDiscovery.Ready -> null
    }

/** What the pairing notice says while the notification waits. Null when it isn't waiting. */
internal sealed interface PairingSearch {
    /** Looking for the pairing dialog's service on this phone. */
    data object Searching : PairingSearch

    /** Found (or the native side doesn't report a search): the notification asks for the code. */
    data object EnterCode : PairingSearch

    data class Problem(val problem: DiscoveryProblem) : PairingSearch
}

/**
 * While the notification waits: searching, enter the code, or why the search failed. After the
 * native side ended the attempt (notice back to Idle), a failed search is still explained.
 */
internal fun pairingSearch(device: PhoneDeviceState): PairingSearch? {
    val problem = discoveryProblem(device.pairingDiscovery)
    return when (device.pairingNotice) {
        PhonePairingNotice.NotificationsBlocked -> null
        PhonePairingNotice.Idle -> problem?.let(PairingSearch::Problem)
        PhonePairingNotice.Waiting ->
            when {
                problem != null -> PairingSearch.Problem(problem)
                device.pairingDiscovery == PhoneDiscovery.Searching -> PairingSearch.Searching
                else -> PairingSearch.EnterCode
            }
    }
}

/** What the connect step says about the automatic path. Null: nothing to report. */
internal sealed interface AutoConnectStatus {
    data object Searching : AutoConnectStatus

    data object Connecting : AutoConnectStatus

    /** The found port didn't connect. The reason, if known, is the device problem shown above. */
    data object Failed : AutoConnectStatus

    data class Problem(val problem: DiscoveryProblem) : AutoConnectStatus
}

internal fun autoConnectStatus(device: PhoneDeviceState): AutoConnectStatus? {
    if (device.link == PhoneLink.Connected) return null
    if (device.autoConnect == PhoneAutoConnect.Running) return AutoConnectStatus.Connecting
    if (device.connectDiscovery == PhoneDiscovery.Searching) return AutoConnectStatus.Searching
    if (device.autoConnect == PhoneAutoConnect.Failed) return AutoConnectStatus.Failed
    return discoveryProblem(device.connectDiscovery)?.let(AutoConnectStatus::Problem)
}

/**
 * The connect card's layout. Port and address fields appear only after the person asked for the
 * manual path; the normal path needs no port at all.
 */
internal data class ConnectPlan(
    val primary: ConnectPrimary,
    /**
     * "Find and connect" is offered. Not while pairing is known to be missing: connecting would
     * only be refused (after a successful pairing the native side connects by itself).
     */
    val offersAutoConnect: Boolean,
    /** "Find and connect" can be pressed now. */
    val canAutoConnect: Boolean,
    val autoStatus: AutoConnectStatus?,
    /** Null while the pairing section is folded (paired) or the notification isn't waiting. */
    val pairingSearch: PairingSearch?,
    /** The pairing section (notification button / waiting notice) is open. */
    val showsPairing: Boolean,
    /** Manual pairing port + code fields (split screen only). */
    val showsManualPairing: Boolean,
    /** Manual connection port + other-address fields. */
    val showsManualConnect: Boolean,
)

internal fun connectPlan(
    state: PhoneScreenState,
    pairAgain: Boolean,
    manualPair: Boolean,
    manualConnect: Boolean,
): ConnectPlan {
    val device = state.device
    val paired = device.paired == true
    val showsPairing = !paired || pairAgain
    val status = autoConnectStatus(device)
    val offersAuto = device.paired != false
    val canAuto =
        offersAuto &&
            state.pending == null &&
            device.link != PhoneLink.Connecting &&
            device.link != PhoneLink.Connected &&
            status != AutoConnectStatus.Searching &&
            status != AutoConnectStatus.Connecting
    return ConnectPlan(
        primary = if (showsPairing) ConnectPrimary.PairWithNotification else ConnectPrimary.AutoConnect,
        offersAutoConnect = offersAuto,
        canAutoConnect = canAuto,
        autoStatus = status,
        pairingSearch = if (showsPairing) pairingSearch(device) else null,
        showsPairing = showsPairing,
        showsManualPairing = showsPairing && manualPair,
        showsManualConnect = manualConnect,
    )
}

/** Which search a "Search again" button restarts. */
internal enum class DiscoveryKind { Pairing, Connect }

/**
 * "Search again". Restarts only the local search: the pairing notification (the person still types
 * the code) or find-and-connect. It never grants, continues or resumes anything.
 */
internal fun retryDiscovery(
    kind: DiscoveryKind,
    actions: PhoneControlActions,
    pairingHost: String = PhoneUiLimits.DEFAULT_HOST,
) = when (kind) {
    DiscoveryKind.Pairing -> actions.onBeginPairingNotification(pairingHost)
    DiscoveryKind.Connect -> actions.onAutoConnect()
}

/**
 * The host the pairing notification is for: this phone, unless the person opened the manual
 * connection section and typed another address there.
 */
internal fun pairingHost(
    manualConnect: Boolean,
    customHost: Boolean,
    hostText: String,
): String? = if (manualConnect && customHost) parseHost(hostText) else PhoneUiLimits.DEFAULT_HOST

/** A TCP port typed by the person, or null. */
internal fun parsePort(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..65_535 }

/** Outcome of pressing Connect (the button or the keyboard's Go). */
internal sealed interface ConnectSubmit {
    /** Something is already running: ignore the press, show nothing new. */
    data object Busy : ConnectSubmit

    /** A field is missing or malformed: show the field errors. */
    data object Invalid : ConnectSubmit

    data class Ready(val request: PhoneConnectRequest) : ConnectSubmit
}

/** One validation for every way of submitting the connect form. Connecting never grants. */
internal fun connectRequest(
    portText: String,
    customHost: Boolean,
    hostText: String,
    busy: Boolean,
): ConnectSubmit {
    if (busy) return ConnectSubmit.Busy
    val port = parsePort(portText) ?: return ConnectSubmit.Invalid
    val host = (if (customHost) parseHost(hostText) else PhoneUiLimits.DEFAULT_HOST) ?: return ConnectSubmit.Invalid
    return ConnectSubmit.Ready(PhoneConnectRequest(host, port))
}

/** A host typed by the person (no scheme, port or spaces), or null. */
internal fun parseHost(text: String): String? {
    val host = text.trim()
    if (host.isEmpty() || host.length > PhoneUiLimits.MAX_HOST_CHARS) return null
    if (host.any { it.isWhitespace() || it == '/' || it == '@' || it == '?' || it == '#' }) return null
    if (host.contains("://")) return null
    // A colon is allowed only as part of an IPv6 literal.
    if (host.contains(':') && !(host.count { it == ':' } >= 2 && host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' })) return null
    return host
}

/** The pairing code from the system dialog: exactly six digits once spaces are removed. */
internal fun parsePairingCode(text: String): String? =
    text.filterNot { it.isWhitespace() }.takeIf { it.length == PhoneUiLimits.PAIRING_CODE_LENGTH && it.all(Char::isDigit) }

/** Keeps only digits up to the code length, so a pasted "482 916" becomes "482916". */
internal fun sanitizePairingCodeInput(text: String): String =
    text.filter(Char::isDigit).take(PhoneUiLimits.PAIRING_CODE_LENGTH)

/**
 * Milliseconds left before a finite deadline: never negative, saturating instead of wrapping.
 * Null for the explicit "no time limit" deadline, which has nothing to count down.
 */
internal fun remainingMs(
    expiresAtMs: Long,
    nowMs: Long,
): Long? {
    if (isPermanentTime(expiresAtMs)) return null
    if (expiresAtMs <= nowMs) return 0
    // Both finite: the difference may still exceed Long (e.g. a negative clock); saturate.
    val left = expiresAtMs - nowMs
    return if (left < 0) PhoneUiLimits.PERMANENT_MS - 1 else left
}

/** Whole units left, rounded up without `+ (unit - 1)` overflow, capped to Int. */
internal fun ceilUnits(
    leftMs: Long,
    unitMs: Long,
): Int {
    if (leftMs <= 0) return 0
    val units = leftMs / unitMs + if (leftMs % unitMs == 0L) 0 else 1
    return units.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** Whole minutes left, rounded up; 0 once expired; null for "no time limit". */
internal fun minutesLeft(
    expiresAtMs: Long,
    nowMs: Long,
): Int? = remainingMs(expiresAtMs, nowMs)?.let { ceilUnits(it, 60_000) }

/** Seconds left within the last minute, for a precise countdown; null above one minute or without a limit. */
internal fun secondsLeftInLastMinute(
    expiresAtMs: Long,
    nowMs: Long,
): Int? {
    val left = remainingMs(expiresAtMs, nowMs) ?: return null
    return if (left <= 60_000) ceilUnits(left, 1_000) else null
}

/** What a lease countdown shows. Decided from the real deadline only, never from the configuration. */
internal sealed interface TimeLeft {
    /** The deadline is the explicit "no time limit" value. */
    data object NoLimit : TimeLeft

    data class Minutes(val value: Int) : TimeLeft

    /** The last minute, to the second. */
    data class Seconds(val value: Int) : TimeLeft
}

/**
 * A configured per-task "Permanent" inside a 4 h scope still gets a finite grant ending with the
 * scope: that deadline is counted down here, not shown as "no time limit".
 */
internal fun timeLeft(
    expiresAtMs: Long,
    nowMs: Long,
): TimeLeft {
    val left = remainingMs(expiresAtMs, nowMs) ?: return TimeLeft.NoLimit
    return if (left <= 60_000) TimeLeft.Seconds(ceilUnits(left, 1_000)) else TimeLeft.Minutes(ceilUnits(left, 60_000))
}

/** The target to preselect: the agent's suggestion if it is in the list and allowed. */
internal fun preselectedTarget(state: PhoneScreenState): PhoneTarget? {
    val wanted = state.request?.packageName ?: return null
    return state.targets.firstOrNull { it.packageName == wanted && targetBlock(it, state.hostPackage) == null }
}

internal val DEFAULT_ACTIONS: Set<PhoneActionScope> = setOf(PhoneActionScope.Tap, PhoneActionScope.Swipe, PhoneActionScope.Back)

// ---- configure once, then use from chat ----------------------------------------------------------

/** Standing windows offered, in hours. Bounded on purpose; the person can revoke at any time. */
internal val STANDING_WINDOW_HOURS: List<Int> = listOf(1, 4, 8)
internal const val DEFAULT_STANDING_WINDOW_HOURS = 4

/** At most this many apps in one standing scope, so the scope stays reviewable. */
internal const val MAX_STANDING_APPS = 8

/** What the standing card says about whether chat can use the phone right now. */
internal enum class StandingReadiness {
    /** No standing scope: the per-task flow applies. */
    NotSet,

    /** In effect and the runtime loaded the tool: chat requests start directly. */
    Ready,

    /** In effect, but the running OpenChamber started without the tool. One confirmed restart. */
    NeedsRestart,

    Restarting,

    /** In effect, but the local OpenChamber isn't running. */
    RuntimeNotRunning,

    /** In effect, but whether the tool is loaded isn't known; not claimed as ready. */
    ToolUnknown,

    /** The scope is saved but phone control can't act: switch off or the device isn't connected. */
    NotConnected,

    Expired,
    Revoked,
}

internal fun standingReadiness(state: PhoneScreenState): StandingReadiness {
    val standing = state.standing ?: return StandingReadiness.NotSet
    return when (standing.status) {
        PhoneStandingStatus.NotSet -> StandingReadiness.NotSet
        PhoneStandingStatus.Expired -> StandingReadiness.Expired
        PhoneStandingStatus.Revoked -> StandingReadiness.Revoked
        is PhoneStandingStatus.Active ->
            when {
                !state.available || !state.enabled || state.device.link != PhoneLink.Connected -> StandingReadiness.NotConnected
                else ->
                    when (standing.runtime.tool) {
                        PhoneToolStatus.Ready -> StandingReadiness.Ready
                        PhoneToolStatus.NeedsRestart -> StandingReadiness.NeedsRestart
                        PhoneToolStatus.Restarting -> StandingReadiness.Restarting
                        PhoneToolStatus.RuntimeNotRunning -> StandingReadiness.RuntimeNotRunning
                        PhoneToolStatus.Unknown -> StandingReadiness.ToolUnknown
                    }
            }
    }
}

/**
 * The continue-delivery state to show on the standing card. The stopped card shows it itself, so
 * it appears here only when the control isn't Halted, and never for Idle.
 */
internal fun standingDeliveryNotice(state: PhoneScreenState): PhoneContinueDelivery? {
    val delivery = state.standing?.continuation?.delivery ?: return null
    if (delivery == PhoneContinueDelivery.Idle) return null
    if (state.control is PhoneControl.Halted) return null
    return delivery
}

/** Only this state may say "say it in chat now". */
internal fun chatCanUsePhone(state: PhoneScreenState): Boolean = standingReadiness(state) == StandingReadiness.Ready

/** The first reason a standing scope can't be saved. */
internal enum class StandingBlock { NotConnected, StopChannel, NoApps, TooManyApps, AppNotAllowed, NoActions, Busy }

internal fun standingBlock(
    state: PhoneScreenState,
    apps: List<PhoneTarget>,
    actions: Set<PhoneActionScope>,
): StandingBlock? =
    when {
        state.device.link != PhoneLink.Connected -> StandingBlock.NotConnected
        state.stopChannel != PhoneStopChannel.Verified -> StandingBlock.StopChannel
        apps.isEmpty() -> StandingBlock.NoApps
        apps.size > MAX_STANDING_APPS -> StandingBlock.TooManyApps
        apps.any { targetBlock(it, state.hostPackage) != null } -> StandingBlock.AppNotAllowed
        actions.isEmpty() -> StandingBlock.NoActions
        state.pending != null -> StandingBlock.Busy
        else -> null
    }

/**
 * "Continue beside the chat" after a stop: offered only inside an active standing scope with the
 * tool ready, once the stop is confirmed and nothing may still apply. It never resumes by itself.
 */
internal fun canContinueInChat(state: PhoneScreenState): Boolean {
    val halted = state.control as? PhoneControl.Halted ?: return false
    val standing = state.standing ?: return false
    return chatCanUsePhone(state) &&
        halted.confirmed &&
        halted.lastAction != PhoneLastAction.MayStillApply &&
        state.stopChannel == PhoneStopChannel.Verified &&
        state.pending == null &&
        standing.continuation.delivery != PhoneContinueDelivery.Sending &&
        halted.target() in standing.apps.map { it.packageName }
}

private fun PhoneControl.Halted.target(): String = lease.target.packageName

/**
 * In configure-once mode the per-task grant form isn't the step: the standing card is. The grant
 * form stays for per-task mode (no standing scope).
 */
internal fun usesStanding(state: PhoneScreenState): Boolean = state.standing != null

/** The standing card shows once the device is connected, and while stopped or after a result. */
internal fun showsStandingCard(state: PhoneScreenState): Boolean =
    usesStanding(state) &&
        when (phoneStep(state)) {
            PhoneStep.Grant, PhoneStep.Result, PhoneStep.Halted, PhoneStep.Running -> true
            PhoneStep.Unavailable, PhoneStep.Off, PhoneStep.Connect -> false
        }

/** Allowed apps only, in the order given, at most [MAX_STANDING_APPS]. */
internal fun sanitizeStandingApps(
    apps: List<PhoneTarget>,
    hostPackage: String,
): List<PhoneTarget> =
    apps.distinctBy { it.packageName }.filter { targetBlock(it, hostPackage) == null }.take(MAX_STANDING_APPS)

/** A standing window still running; minutes left rounded up, or null (also for a permanent one). */
internal fun standingMinutesLeft(
    status: PhoneStandingStatus,
    nowMs: Long,
): Int? = (status as? PhoneStandingStatus.Active)?.takeUnless { isPermanentTime(it.endsAtMs) }?.let { minutesLeft(it.endsAtMs, nowMs) }

// ---- "Permanent": no time limit, chosen explicitly ------------------------------------------------

/** The explicit "no time limit" value; an arbitrary large number is not permanent. */
internal fun isPermanentTime(value: Long): Boolean = value == PhoneUiLimits.PERMANENT_MS

/** The "Permanent" chip in a saved Int choice (hours or minutes). Never a default. */
internal const val PERMANENT_CHOICE = 0

/** Standing window chips: the bounded hours first, then "Permanent". */
internal val STANDING_WINDOW_CHOICES: List<Int> = STANDING_WINDOW_HOURS + PERMANENT_CHOICE

/** Per-task chips in the standing scope: the bounded minutes first, then "Permanent". */
internal val STANDING_PER_TASK_CHOICES: List<Int> = PhoneUiLimits.DURATION_CHOICES_MIN + PERMANENT_CHOICE

/**
 * Branches on "Permanent" before any unit multiplication. Only an offered chip is accepted; anything
 * else (a stale saved value, `Int.MAX_VALUE`) falls back to the bounded default, never to permanent.
 */
internal fun standingWindowMs(hours: Int): Long =
    when (hours) {
        PERMANENT_CHOICE -> PhoneUiLimits.PERMANENT_MS
        in STANDING_WINDOW_HOURS -> hours * 3_600_000L
        else -> DEFAULT_STANDING_WINDOW_HOURS * 3_600_000L
    }

internal fun standingPerTaskMs(minutes: Int): Long =
    when (minutes) {
        PERMANENT_CHOICE -> PhoneUiLimits.PERMANENT_MS
        in PhoneUiLimits.DURATION_CHOICES_MIN -> minutes * 60_000L
        else -> PhoneUiLimits.DEFAULT_DURATION_MIN * 60_000L
    }

/** How the standing scope's per-task limit reads. */
internal enum class PerTaskDisplay {
    /** A fixed number of minutes. */
    Minutes,

    /** Per-task "Permanent" inside a finite scope: each task ends with the scope. */
    UntilScopeEnds,

    /** Per-task "Permanent" and a permanent scope: no time limit at all. */
    NoLimit,
}

internal fun perTaskDisplay(
    perTaskPermanent: Boolean,
    windowPermanent: Boolean,
): PerTaskDisplay =
    when {
        !perTaskPermanent -> PerTaskDisplay.Minutes
        windowPermanent -> PerTaskDisplay.NoLimit
        else -> PerTaskDisplay.UntilScopeEnds
    }

/** What the confirm button sends. "Permanent" travels as [PhoneUiLimits.PERMANENT_MS], unchanged. */
internal fun standingRequest(
    apps: List<PhoneTarget>,
    actions: Set<PhoneActionScope>,
    hours: Int,
    minutes: Int,
): PhoneStandingRequest = PhoneStandingRequest(apps, actions, standingWindowMs(hours), standingPerTaskMs(minutes))

/**
 * Per-task "no time limit". [PhoneStanding.perTaskPermanent] is the field; `Int.MAX_VALUE` minutes
 * is the mapper's earlier stand-in and reads the same, so it is never shown as a minute count.
 */
internal fun perTaskPermanent(standing: PhoneStanding): Boolean = standing.perTaskPermanent || standing.perTaskMinutes == Int.MAX_VALUE

/**
 * The editor's starting window chip. Editing a saved permanent scope keeps "Permanent", so changing
 * only the apps doesn't quietly shorten it. Everything else (a new, expired or revoked scope, or a
 * finite one, whose original hours aren't known from its end time) starts at the bounded default;
 * a huge finite end time is never read as permanent.
 */
internal fun initialWindowChoice(status: PhoneStandingStatus): Int =
    if (standingWindowPermanent(status)) PERMANENT_CHOICE else DEFAULT_STANDING_WINDOW_HOURS

/** The editor's starting per-task chip: the saved one, "Permanent" only if that was saved. */
internal fun initialPerTaskChoice(standing: PhoneStanding): Int =
    when {
        perTaskPermanent(standing) -> PERMANENT_CHOICE
        standing.perTaskMinutes in PhoneUiLimits.DURATION_CHOICES_MIN -> standing.perTaskMinutes
        else -> PhoneUiLimits.DEFAULT_DURATION_MIN
    }

/** The standing scope has no end time. */
internal fun standingWindowPermanent(status: PhoneStandingStatus): Boolean =
    status is PhoneStandingStatus.Active && isPermanentTime(status.endsAtMs)

/** The task lease has no deadline: no countdown is shown. */
internal fun PhoneLease.permanent(): Boolean = isPermanentTime(expiresAtMs)
