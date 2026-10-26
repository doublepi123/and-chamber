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
internal enum class OverlayAction { TakeOver, Pause, OpenNative, End, Collapse, RetryTakeOver, CloseResult }

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
    val left = end - nowMs
    if (left <= 0) return 0
    return ((left + 59_999) / 60_000).toInt()
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
        else -> Ago.Minutes((d / 60_000).toInt())
    }
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

/** Overlay mode may be chosen only after a passed visibility test with the permission in place. */
internal fun canChooseOverlay(state: PhoneOverlaySettingsState): Boolean =
    state.available && state.permission == OverlayPermission.Granted && state.test == OverlayTest.Seen && !state.pending
