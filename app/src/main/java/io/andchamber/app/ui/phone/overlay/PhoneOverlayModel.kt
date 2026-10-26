package io.andchamber.app.ui.phone.overlay

import androidx.compose.runtime.Immutable

/*
 * The system overlay's own model: immutable, independent of `phone/core` and of the window
 * manager. The integration maps controller/task facts into these types and wires the callbacks.
 * Nothing here is a grant. The overlay can stop or hand back control, never give it.
 */

/** Where the task stands, as far as the native side has confirmed it. */
internal enum class OverlayPhase {
    /** The agent holds a lease and may observe and act. */
    Running,

    /** Pause was requested; the executor hasn't confirmed. Take over is still offered. */
    Pausing,

    /** Confirmed: no new actions are accepted; the agent may still observe. */
    Paused,

    /** Take over was requested (revoke the lease); the executor hasn't confirmed. */
    Taking,

    /** Confirmed: the lease is revoked; new observation and actions are refused. */
    TakenOver,

    /** The agent asked for a person; the lease is being revoked, not yet confirmed. */
    NeedUserPending,

    /** Confirmed revoked after an agent request: the person handles this step. */
    NeedUser,

    /** A dispatched action's result is unknown; nothing new is accepted. */
    Unknown,

    /** End was confirmed by the person; revocation hasn't been confirmed. */
    Ending,

    /** Revocation confirmed; the result waits for the person to check it. */
    Ended,

    /** A stop request (pause, take over, end, occlusion) wasn't confirmed in time. */
    StopUnconfirmed,

    /** The overlay can't show live control (backend not ready, or not trusted right now). */
    Unavailable,
}

/** Where an event came from. Shown as a label on every event. */
internal enum class OverlaySource {
    /** Seen by the native side (e.g. the foreground app). Bounded to what was actually observed. */
    NativeObserved,

    /** A native confirmation of a control change (pause, take over, end). */
    NativeConfirmed,

    /** Dispatched to the device. Doesn't mean the step worked. */
    Sent,

    /** Dispatched, but whether it took effect is unknown. */
    SentUnknown,

    /** The agent's own words or request. Not verified. */
    AgentNote,
}

/**
 * One event. [text] is supplied by the integration: fixed native wording, or the agent's own words
 * for [OverlaySource.AgentNote]. Never page text, typed input, codes or passwords.
 */
@Immutable
internal data class OverlayEvent(
    val text: String,
    val source: OverlaySource,
    val atMs: Long,
)

/** Which edge the capsule is snapped to, so the panel grows towards the screen. */
internal enum class OverlaySide { Left, Right }

/** Everything the overlay shows. A new value per change; geometry stays with the window manager. */
@Immutable
internal data class PhoneOverlayState(
    val phase: OverlayPhase = OverlayPhase.Unavailable,
    /** The chosen app's label. */
    val targetLabel: String = "",
    /** The task summary the person confirmed natively. Collapsed by default; null hides the row. */
    val summary: String? = null,
    /** Lease end on the same clock as the screen's `clock`; null when there is no live lease. */
    val leaseEndsAtMs: Long? = null,
    /** Dispatch receipts so far; null when unknown. A count of sent actions, not of finished steps. */
    val sentCount: Int? = null,
    /** Newest first. The panel shows the latest three; the result card groups all by source. */
    val events: List<OverlayEvent> = emptyList(),
    /** The panel is open. Owned outside so the window can resize with it. */
    val expanded: Boolean = false,
    /** The end confirmation is showing (from the panel button or a drop on the end zone). */
    val endConfirm: Boolean = false,
    val side: OverlaySide = OverlaySide.Right,
    /** The test capsule from the settings card: marked as a demo, controls nothing real. */
    val demo: Boolean = false,
)

/**
 * Requests from the overlay. Each one is a request; the overlay shows the outcome only through the
 * next [PhoneOverlayState]. None of them grants, resumes or extends anything.
 */
@Immutable
internal class PhoneOverlayActions(
    val onExpand: () -> Unit = {},
    val onCollapse: () -> Unit = {},
    /** Revoke the lease: refuse new observation and actions. The person stays on the current page. */
    val onTakeOver: () -> Unit = {},
    /** Refuse new actions only; observation continues. */
    val onPause: () -> Unit = {},
    /** Revoke first, then open the native page. Never resumes from here. */
    val onOpenNative: () -> Unit = {},
    /** Show the end confirmation. */
    val onRequestEnd: () -> Unit = {},
    val onConfirmEnd: () -> Unit = {},
    val onCancelEnd: () -> Unit = {},
    /** Close the result card. Only offered once the task has ended and revocation is confirmed. */
    val onCloseResult: () -> Unit = {},
    /** Accessibility: move the capsule to the other edge. */
    val onMoveLeft: () -> Unit = {},
    val onMoveRight: () -> Unit = {},
    /** Drag on the capsule's handle, in px. The window manager moves the window and snaps it. */
    val onDragBy: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    /** The drag ended; the manager snaps, or calls [onRequestEnd] for a drop on its end zone. */
    val onDragEnd: () -> Unit = {},
)

// ---- settings card ---------------------------------------------------------------------------

/** How a task with phone control is followed. Notifications are always kept. */
internal enum class OverlayMode { NotificationOnly, OverlayAndNotification }

internal enum class OverlayPermission {
    Unknown,
    Granted,

    /** Checked after the person came back from settings: still not allowed. */
    NotGranted,

    /** This device or build can't use overlays at all. */
    Unsupported,
}

/** The visibility test. Permission alone isn't evidence the person can see or reach the overlay. */
internal enum class OverlayTest {
    NotRun,

    /** The demo capsule was requested; waiting for the person to tap its take-over button. */
    Showing,

    /** The demo take-over reached the native side; the person still has to say they saw it. */
    TapReceived,

    /** The person confirmed they saw and tapped it. Valid for that moment only. */
    Seen,

    /** The person said they didn't see it, or adding the window failed. */
    NotSeen,

    /** No tap reached the native side in time. */
    TimedOut,
}

@Immutable
internal data class PhoneOverlaySettingsState(
    /** False until the integration can actually host an overlay; the card then explains and stays off. */
    val available: Boolean = false,
    val mode: OverlayMode = OverlayMode.NotificationOnly,
    val permission: OverlayPermission = OverlayPermission.Unknown,
    val test: OverlayTest = OverlayTest.NotRun,
    /** A request is in progress (checking, adding the test window…). */
    val pending: Boolean = false,
)

@Immutable
internal class PhoneOverlaySettingsActions(
    /** Opens the system "display over other apps" screen. Only ever on the person's tap. */
    val onOpenSystemSettings: () -> Unit = {},
    /** Re-reads the permission (also call it on resume). */
    val onRecheck: () -> Unit = {},
    /** Shows the demo capsule. Must not grant any real control. */
    val onStartTest: () -> Unit = {},
    /** The person's answer after the demo tap: true = they saw and reached it. */
    val onConfirmSeen: (Boolean) -> Unit = {},
    val onChooseMode: (OverlayMode) -> Unit = {},
)
