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
internal enum class TargetBlock { Host, System, Invalid }

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

/** Whole minutes left, rounded up; 0 once expired. */
internal fun minutesLeft(
    expiresAtMs: Long,
    nowMs: Long,
): Int {
    val left = expiresAtMs - nowMs
    if (left <= 0) return 0
    return ((left + 59_999) / 60_000).toInt()
}

/** Seconds left within the last minute, for a precise countdown; null above one minute. */
internal fun secondsLeftInLastMinute(
    expiresAtMs: Long,
    nowMs: Long,
): Int? {
    val left = expiresAtMs - nowMs
    if (left <= 0) return 0
    return if (left <= 60_000) ((left + 999) / 1_000).toInt() else null
}

/** The target to preselect: the agent's suggestion if it is in the list and allowed. */
internal fun preselectedTarget(state: PhoneScreenState): PhoneTarget? {
    val wanted = state.request?.packageName ?: return null
    return state.targets.firstOrNull { it.packageName == wanted && targetBlock(it, state.hostPackage) == null }
}

internal val DEFAULT_ACTIONS: Set<PhoneActionScope> = setOf(PhoneActionScope.Tap, PhoneActionScope.Swipe, PhoneActionScope.Back)
