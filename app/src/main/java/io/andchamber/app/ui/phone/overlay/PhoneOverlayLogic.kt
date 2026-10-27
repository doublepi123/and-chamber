package io.andchamber.app.ui.phone.overlay

/*
 * Pure presentation rules for the overlay and its settings card. No Android or Compose imports.
 */

/** What the capsule's own button does. [Disabled] while a stop is waiting for confirmation. */
internal enum class OverlayPrimary { TakeOver, OpenNative, Look, Disabled }

internal fun overlayPrimary(phase: OverlayPhase): OverlayPrimary =
    when (phase) {
        // A pause in progress can still be escalated to a full take-over.
        OverlayPhase.Running, OverlayPhase.Pausing, OverlayPhase.Paused -> OverlayPrimary.TakeOver
        OverlayPhase.Taking, OverlayPhase.NeedUserPending, OverlayPhase.Ending -> OverlayPrimary.Disabled
        OverlayPhase.TakenOver, OverlayPhase.NeedUser, OverlayPhase.StopUnconfirmed, OverlayPhase.Unavailable ->
            OverlayPrimary.OpenNative
        OverlayPhase.Unknown, OverlayPhase.Ended -> OverlayPrimary.Look
    }

/** The panel's buttons, in order of prominence. */
internal enum class OverlayAction { TakeOver, Pause, OpenNative, End, Collapse, RetryTakeOver, CloseResult, OpenChat, ContinueInChat }

/**
 * Panel buttons for a full state. Adds "open chat" while the agent works and, in configure-once
 * mode, "continue beside the chat" after a confirmed handover. Both are requests: the first doesn't
 * revoke, the second opens a native confirmation. Neither grants from the overlay.
 */
internal fun panelActions(state: PhoneOverlayState): List<OverlayAction> {
    val base = panelActions(state.phase)
    if (state.demo) return base
    val extra = ArrayList<OverlayAction>(2)
    if (state.canContinueInChat && (state.phase == OverlayPhase.TakenOver || state.phase == OverlayPhase.NeedUser || state.phase == OverlayPhase.Paused)) {
        extra += OverlayAction.ContinueInChat
    }
    // Open chat (no revoke) while the agent works, and as a way back from a retained handover:
    // taken over, need you, or an unknown result. Never on an ended card.
    if (state.phase == OverlayPhase.Running || state.phase == OverlayPhase.Paused || isRetainedHandover(state)) {
        extra += OverlayAction.OpenChat
    }
    if (extra.isEmpty()) return base
    // Continue goes right after the primary; open chat sits before the end action.
    val out = base.toMutableList()
    if (OverlayAction.ContinueInChat in extra) out.add(minOf(1, out.size), OverlayAction.ContinueInChat)
    if (OverlayAction.OpenChat in extra) {
        val endAt = out.indexOf(OverlayAction.End).takeIf { it >= 0 } ?: out.size
        out.add(endAt, OverlayAction.OpenChat)
    }
    return out
}

/**
 * Splits panel buttons into full-width rows and one trailing row for the low-emphasis pair
 * (Collapse, End). The first full-width button stays the primary. When the primary itself is
 * Collapse/End (e.g. Unknown: "I'll go look" first), or there is no pair, nothing moves.
 */
internal fun panelButtonRows(buttons: List<OverlayAction>): Pair<List<OverlayAction>, List<OverlayAction>> {
    val low = setOf(OverlayAction.Collapse, OverlayAction.End)
    val footer = buttons.filter { it in low }
    val stacked = buttons.filter { it !in low }
    if (stacked.isEmpty() || footer.size < 2 || buttons.first() in low) return buttons to emptyList()
    return stacked to footer
}

/**
 * A stopped task kept for the person: taken over, need you, or an unknown result whose stop is
 * settled. Not a finished task, and not while a stop is still pending or unconfirmed.
 */
internal fun isRetainedHandover(state: PhoneOverlayState): Boolean =
    state.retained && state.phase in RETAINED_HANDOVER_PHASES

private val RETAINED_HANDOVER_PHASES = setOf(OverlayPhase.TakenOver, OverlayPhase.NeedUser, OverlayPhase.Unknown)

internal fun panelActions(phase: OverlayPhase): List<OverlayAction> =
    when (phase) {
        OverlayPhase.Running -> listOf(OverlayAction.TakeOver, OverlayAction.Pause, OverlayAction.OpenNative, OverlayAction.End)
        OverlayPhase.Pausing -> listOf(OverlayAction.TakeOver)
        OverlayPhase.Paused -> listOf(OverlayAction.TakeOver, OverlayAction.OpenNative, OverlayAction.End)
        OverlayPhase.TakenOver, OverlayPhase.NeedUser -> listOf(OverlayAction.OpenNative, OverlayAction.Collapse, OverlayAction.End)
        OverlayPhase.Unknown -> listOf(OverlayAction.Collapse, OverlayAction.OpenNative, OverlayAction.End)
        OverlayPhase.StopUnconfirmed -> listOf(OverlayAction.OpenNative, OverlayAction.RetryTakeOver)
        OverlayPhase.Ended -> listOf(OverlayAction.OpenNative, OverlayAction.CloseResult)
        OverlayPhase.Unavailable -> listOf(OverlayAction.OpenNative)
        OverlayPhase.Taking, OverlayPhase.NeedUserPending, OverlayPhase.Ending -> emptyList()
    }

/** End can be requested only while the task is live or handed over, never while a stop is pending. */
internal fun canRequestEnd(phase: OverlayPhase): Boolean = OverlayAction.End in panelActions(phase)

/** A stop is waiting for the executor; the overlay must not say it has stopped. */
internal fun isPending(phase: OverlayPhase): Boolean =
    phase == OverlayPhase.Pausing || phase == OverlayPhase.Taking ||
        phase == OverlayPhase.NeedUserPending || phase == OverlayPhase.Ending

/** The panel shows when opened, or when an end confirmation is up (e.g. after a drop on the end zone). */
internal fun showsPanel(state: PhoneOverlayState): Boolean =
    state.expanded || (state.endConfirm && canRequestEnd(state.phase))

/** The latest three events, newest first. */
internal fun recentEvents(events: List<OverlayEvent>): List<OverlayEvent> =
    events.sortedByDescending { it.atMs }.take(3)

/** Groups for the result card: native facts, sent actions, agent words. Each keeps newest first. */
internal data class ResultGroups(
    val native: List<OverlayEvent>,
    val sent: List<OverlayEvent>,
    val agent: List<OverlayEvent>,
)

internal fun resultGroups(events: List<OverlayEvent>): ResultGroups {
    val sorted = events.sortedByDescending { it.atMs }
    return ResultGroups(
        native = sorted.filter { it.source == OverlaySource.NativeObserved || it.source == OverlaySource.NativeConfirmed },
        sent = sorted.filter { it.source == OverlaySource.Sent || it.source == OverlaySource.SentUnknown },
        agent = sorted.filter { it.source == OverlaySource.AgentNote },
    )
}

/** Whole minutes left, rounded up; null without a lease, 0 once expired. */
internal fun overlayMinutesLeft(
    leaseEndsAtMs: Long?,
    nowMs: Long,
): Int? {
    val end = leaseEndsAtMs ?: return null
    // The explicit "no time limit" deadline never becomes a minute count; no `+ 59_999` overflow.
    return io.andchamber.app.ui.phone.minutesLeft(end, nowMs)
}

/** How long ago, coarse on purpose: seconds below a minute, then minutes. */
internal sealed interface Ago {
    data object JustNow : Ago

    data class Seconds(val value: Int) : Ago

    data class Minutes(val value: Int) : Ago
}

internal fun ago(
    atMs: Long,
    nowMs: Long,
): Ago {
    val d = (nowMs - atMs).coerceAtLeast(0)
    return when {
        d < 5_000 -> Ago.JustNow
        d < 60_000 -> Ago.Seconds((d / 1_000).toInt())
        else -> Ago.Minutes((d / 60_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}

// ---- a question from the chat --------------------------------------------------------------

/** The prominent line: the question itself, else the notification body. */
internal fun promptQuestion(prompt: OverlayPrompt): String? =
    prompt.questionDescription?.takeIf { it.isNotBlank() } ?: prompt.noticeBody?.takeIf { it.isNotBlank() }

/** The small label above it: the form's title, else the notification title. */
internal fun promptLabel(prompt: OverlayPrompt): String? =
    prompt.questionTitle?.takeIf { it.isNotBlank() } ?: prompt.noticeTitle?.takeIf { it.isNotBlank() }

/** Choices can be picked and sent only while the question is open and has choices. */
internal fun promptCanSubmit(prompt: OverlayPrompt): Boolean = prompt.delivery == OverlayPromptDelivery.Ready && prompt.choices.isNotEmpty()

/** What the question block offers besides the choices. */
internal enum class PromptAction {
    /** Send the picked choice. Enabled only with a pick, in Ready, and not already sent. */
    Send,

    /** Re-read the question. For Unknown: never a re-send. */
    CheckAgain,

    /** Answer it in the chat instead. */
    OpenChat,
}

internal fun promptActions(prompt: OverlayPrompt): List<PromptAction> =
    when (prompt.delivery) {
        OverlayPromptDelivery.Ready ->
            if (prompt.choices.isEmpty()) listOf(PromptAction.OpenChat) else listOf(PromptAction.Send, PromptAction.OpenChat)
        // Read-only: look at the status again or go to the chat. Never another send.
        OverlayPromptDelivery.Unknown, OverlayPromptDelivery.Blocked -> listOf(PromptAction.CheckAgain, PromptAction.OpenChat)
        OverlayPromptDelivery.Refused,
        OverlayPromptDelivery.Unsupported,
        -> listOf(PromptAction.OpenChat)
        OverlayPromptDelivery.Checking,
        OverlayPromptDelivery.Sending,
        OverlayPromptDelivery.Accepted,
        OverlayPromptDelivery.Superseded,
        -> emptyList()
    }

/** The identity of one shown question; a send is allowed once per identity. */
internal data class PromptKey(val formId: String, val generation: Long)

internal fun OverlayPrompt.key(): PromptKey = PromptKey(formId, generation)

/**
 * The one answer the Send button may hand over, or null. Needs a picked value that is one of the
 * shown choices, an open question, and no earlier send for this exact question (a second tap while
 * the native side hasn't reported Sending yet is ignored).
 */
internal fun promptSubmission(
    prompt: OverlayPrompt,
    picked: String?,
    sentFor: PromptKey?,
): String? {
    if (!promptCanSubmit(prompt)) return null
    if (sentFor == prompt.key()) return null
    val value = picked ?: return null
    return value.takeIf { v -> prompt.choices.any { it.value == v } }
}

// ---- settings card ---------------------------------------------------------------------------

/** The one thing the settings card asks for now. */
internal enum class OverlaySetupStep {
    /** The integration can't host an overlay yet; notifications only. */
    Unavailable,

    /** Permission isn't granted (or unknown): explain, offer the system screen. */
    Permission,

    /** Permission granted, visibility not yet tested (or the last test failed). */
    Test,

    /** The demo tap arrived; ask the person whether they saw it. */
    ConfirmSeen,

    /** Tested: the person picks the mode. */
    ChooseMode,

    /** Overlay mode is on. */
    On,
}

internal fun overlaySetupStep(state: PhoneOverlaySettingsState): OverlaySetupStep =
    when {
        !state.available || state.permission == OverlayPermission.Unsupported -> OverlaySetupStep.Unavailable
        // Permission revoked later: overlay mode can't be in effect, whatever was chosen before.
        state.permission != OverlayPermission.Granted -> OverlaySetupStep.Permission
        state.test == OverlayTest.TapReceived -> OverlaySetupStep.ConfirmSeen
        state.test != OverlayTest.Seen -> OverlaySetupStep.Test
        state.mode == OverlayMode.OverlayAndNotification -> OverlaySetupStep.On
        else -> OverlaySetupStep.ChooseMode
    }

/**
 * The card's status line. Reports the mode the person asked for together with what is true now,
 * never a mode they didn't choose: a saved overlay choice whose visibility wasn't re-tested, or
 * whose permission is gone, is still "overlay selected", with the reason it isn't in effect.
 */
internal enum class OverlayHeader {
    NotificationOnly,

    /** Overlay chosen, permission in place, visibility tested this time. */
    OverlayOn,

    /** Overlay chosen and permitted, but visibility wasn't (re)tested yet, or the last test failed. */
    OverlayNotRechecked,

    /** Overlay chosen, but it can't be shown: permission gone, unsupported, or not hostable now. */
    OverlayBlocked,
}

internal fun overlayHeader(state: PhoneOverlaySettingsState): OverlayHeader =
    when {
        state.mode == OverlayMode.NotificationOnly -> OverlayHeader.NotificationOnly
        !state.available || state.permission != OverlayPermission.Granted -> OverlayHeader.OverlayBlocked
        state.test == OverlayTest.Seen -> OverlayHeader.OverlayOn
        else -> OverlayHeader.OverlayNotRechecked
    }

/**
 * The mode rows show on every step where the card is usable, so "Notifications only" can always be
 * chosen (no permission or test needed). Also shown when overlay is the saved choice but the card
 * isn't otherwise usable, so that choice can still be switched off.
 */
internal fun showsModeChooser(state: PhoneOverlaySettingsState): Boolean =
    overlaySetupStep(state) != OverlaySetupStep.Unavailable || state.mode == OverlayMode.OverlayAndNotification

/** "Notifications only" never depends on the permission or the demo; only a running request blocks it. */
internal fun canChooseNotificationOnly(state: PhoneOverlaySettingsState): Boolean = !state.pending

/**
 * A short report of what happened to the overlay during the last task, so it never just vanished:
 * moved to the notification (and why), or nothing to report. Notifications-only by choice is not
 * reported here (the header already says so).
 */
internal fun lastSurfaceReport(state: PhoneOverlaySettingsState): OverlaySurfaceMode? =
    state.lastSurface?.takeIf { it == OverlaySurfaceMode.MovedToNotification }

/** The last stop reason shows on its own, also when no recovery surface was reported. */
internal fun lastStopReasonShown(state: PhoneOverlaySettingsState): OverlayStopReason? = state.lastStopReason

/** Overlay mode may be chosen only after a passed visibility test with the permission in place. */
internal fun canChooseOverlay(state: PhoneOverlaySettingsState): Boolean =
    state.available && state.permission == OverlayPermission.Granted && state.test == OverlayTest.Seen && !state.pending
