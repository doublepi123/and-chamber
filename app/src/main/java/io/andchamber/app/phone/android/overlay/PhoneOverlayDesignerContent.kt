package io.andchamber.app.phone.android.overlay

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import io.andchamber.app.R
import io.andchamber.app.phone.overlay.progress.PhoneProgressEvent
import io.andchamber.app.phone.overlay.progress.PhoneProgressKind
import io.andchamber.app.phone.overlay.progress.PhoneProgressReason
import io.andchamber.app.phone.overlay.progress.PhoneProgressSource
import io.andchamber.app.ui.phone.overlay.OverlayEvent
import io.andchamber.app.ui.phone.overlay.OverlayPhase as UiOverlayPhase
import io.andchamber.app.ui.phone.overlay.OverlayPrompt
import io.andchamber.app.ui.phone.overlay.OverlayPromptChoice
import io.andchamber.app.ui.phone.overlay.OverlayPromptDelivery
import io.andchamber.app.ui.phone.overlay.OverlaySessionNotice
import io.andchamber.app.ui.phone.overlay.OverlaySessionNoticeKind
import io.andchamber.app.ui.phone.overlay.OverlaySide
import io.andchamber.app.ui.phone.overlay.OverlaySource
import io.andchamber.app.ui.phone.overlay.PhoneOverlayActions
import io.andchamber.app.ui.phone.overlay.PhoneOverlayContent
import io.andchamber.app.ui.phone.overlay.PhoneOverlayState
import io.andchamber.app.ui.theme.AndChamberTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun overlayProgressMatchesSurface(displayEpoch: Long?, progressEpoch: Long?, demo: Boolean): Boolean =
    !demo && displayEpoch != null && displayEpoch == progressEpoch

/**
 * Binds the native surface state to the designer's immutable overlay composable.
 *
 * This adapter draws nothing itself: it maps fixed native state to `PhoneOverlayState`, routes the
 * designer's stop/return callbacks to the coordinator, and keeps only the purely visual panel state
 * (`expanded`, `endConfirm`) in composition. The lease clock is the same monotonic clock the native
 * screen and controller use; wall time is never mixed in.
 *
 * [progress] is the native-only task projection. Its events are sanitized facts (no page text, input,
 * nodes or pixels) plus the model's own bounded notes; [sentCount] counts accepted native SENT
 * facts only. The task summary stays the confirmed one and is folded by the panel.
 */
internal class PhoneOverlayDesignerContent(
    /** The controller's monotonic clock: lease countdowns must never mix wall time. */
    private val clock: () -> Long,
    private val progress: StateFlow<PhoneTaskProgressView?> = MutableStateFlow(null),
) : PhoneOverlayContentPort {
    override val available: Boolean = true

    @Composable
    override fun Content(
        state: PhoneOverlaySurfaceState,
        actions: PhoneOverlaySurfaceActions,
    ) {
        val task by progress.collectAsState()
        val currentTask = task?.takeIf { overlayProgressMatchesSurface(state.grantEpoch, it.snapshot.grantEpoch, state.demo) }
        val events = currentTask?.snapshot?.events.orEmpty().map { event -> overlayEvent(event) }
        val uiState =
            PhoneOverlayState(
                phase = state.phase.toUiPhase(),
                targetLabel = state.appLabel.orEmpty(),
                summary = state.summary,
                leaseEndsAtMs = state.leaseEndsAtMs,
                leasePermanent = state.leasePermanent,
                sentCount = currentTask?.sentCount,
                events = events,
                // Layout state is owned by the manager and arrives as an immutable value.
                expanded = state.expanded,
                endConfirm = state.endConfirm,
                side = if (state.side == PhoneOverlaySide.LEFT) OverlaySide.Left else OverlaySide.Right,
                stopReason = state.stopReason?.toUiStopReason(),
                retained = state.retained,
                canContinueInChat = state.canContinueInChat,
                demo = state.demo,
                prompt = state.sessionPrompt?.toOverlayPrompt(),
                sessionNotice = state.sessionNotice?.toOverlaySessionNotice(),
            )
        val uiActions =
            PhoneOverlayActions(
                onExpand = actions.onExpand,
                onCollapse = actions.onCollapse,
                onTakeOver = actions.onTakeOver,
                onPause = actions.onPause,
                onOpenNative = actions.onReturnToNative,
                onRequestEnd = actions.onRequestEnd,
                onConfirmEnd = actions.onConfirmEnd,
                onCancelEnd = actions.onCancelEnd,
                onCloseResult = actions.onCloseOverlay,
                onMoveLeft = actions.onMoveLeft,
                onMoveRight = actions.onMoveRight,
                onDragBy = { dx, dy -> actions.onDragBy(dx, dy) },
                onDragEnd = { actions.onDragEnd() },
                onOpenChat = actions.onOpenChat,
                onContinueInChat = actions.onContinueInChat,
                onAnswerPrompt = actions.onAnswerPrompt,
                onRefreshPrompt = actions.onRefreshPrompt,
            )
        AndChamberTheme {
            PhoneOverlayContent(
                state = uiState,
                actions = uiActions,
                clock = clock,
            )
        }
    }

    @Composable
    private fun overlayEvent(event: PhoneProgressEvent): OverlayEvent =
        OverlayEvent(
            // Native events carry typed facts only, never free text. Model events keep their own
            // own bounded words.
            text =
                event.text?.takeIf { it.isNotBlank() } ?: stringResource(
                    R.string.phone_overlay_progress_event,
                    stringResource(kindTitle(event.kind)),
                    stringResource(reasonTitle(event.reason)),
                ),
            source = event.overlaySource(),
            atMs = event.atMs,
        )

    private fun PhoneProgressEvent.overlaySource(): OverlaySource =
        when (source) {
            PhoneProgressSource.MODEL -> OverlaySource.AgentNote
            PhoneProgressSource.NATIVE ->
                when (kind) {
                    PhoneProgressKind.OBSERVED -> OverlaySource.NativeObserved
                    PhoneProgressKind.MUTATION_SENT -> OverlaySource.Sent
                    PhoneProgressKind.MUTATION_UNKNOWN -> OverlaySource.SentUnknown
                    else -> OverlaySource.NativeConfirmed
                }
        }

    @StringRes
    private fun kindTitle(kind: PhoneProgressKind): Int =
        when (kind) {
            PhoneProgressKind.TASK_STARTED -> R.string.phone_overlay_progress_kind_task_started
            PhoneProgressKind.TASK_EPOCH_UPDATED -> R.string.phone_overlay_progress_kind_task_epoch_updated
            PhoneProgressKind.OBSERVED -> R.string.phone_overlay_progress_kind_observed
            PhoneProgressKind.MUTATION_SENT -> R.string.phone_overlay_progress_kind_mutation_sent
            PhoneProgressKind.MUTATION_NOT_SENT -> R.string.phone_overlay_progress_kind_mutation_not_sent
            PhoneProgressKind.MUTATION_UNKNOWN -> R.string.phone_overlay_progress_kind_mutation_unknown
            PhoneProgressKind.BUSINESS_RESULT -> R.string.phone_overlay_progress_kind_business_result
            PhoneProgressKind.STOP_REQUESTED -> R.string.phone_overlay_progress_kind_stop_requested
            PhoneProgressKind.STOP_ACKNOWLEDGED -> R.string.phone_overlay_progress_kind_stop_acknowledged
            PhoneProgressKind.TASK_ENDED -> R.string.phone_overlay_progress_kind_task_ended
            PhoneProgressKind.MODEL_PROGRESS -> R.string.phone_overlay_progress_kind_model_progress
            PhoneProgressKind.MODEL_NEED_USER -> R.string.phone_overlay_progress_kind_model_need_user
            PhoneProgressKind.MODEL_FINISH -> R.string.phone_overlay_progress_kind_model_finish
        }

    @StringRes
    private fun reasonTitle(reason: PhoneProgressReason): Int =
        when (reason) {
            PhoneProgressReason.NONE -> R.string.phone_overlay_progress_reason_none
            PhoneProgressReason.USER_CONFIRMED -> R.string.phone_overlay_progress_reason_user_confirmed
            PhoneProgressReason.GRANT_UPDATED -> R.string.phone_overlay_progress_reason_grant_updated
            PhoneProgressReason.OBSERVED -> R.string.phone_overlay_progress_reason_observed
            PhoneProgressReason.USER_REQUESTED -> R.string.phone_overlay_progress_reason_user_requested
            PhoneProgressReason.TAKEOVER -> R.string.phone_overlay_progress_reason_takeover
            PhoneProgressReason.LEASE_EXPIRED -> R.string.phone_overlay_progress_reason_lease_expired
            PhoneProgressReason.HOST_RETURNED -> R.string.phone_overlay_progress_reason_host_returned
            PhoneProgressReason.CONNECTION_LOST -> R.string.phone_overlay_progress_reason_connection_lost
            PhoneProgressReason.DEVICE_LOCKED -> R.string.phone_overlay_progress_reason_device_locked
            PhoneProgressReason.RUNTIME_CHANGED -> R.string.phone_overlay_progress_reason_runtime_changed
            PhoneProgressReason.OUTCOME_UNKNOWN -> R.string.phone_overlay_progress_reason_outcome_unknown
            PhoneProgressReason.DELIVERY_SENT -> R.string.phone_overlay_progress_reason_delivery_sent
            PhoneProgressReason.DELIVERY_NOT_SENT -> R.string.phone_overlay_progress_reason_delivery_not_sent
            PhoneProgressReason.DELIVERY_UNKNOWN -> R.string.phone_overlay_progress_reason_delivery_unknown
            PhoneProgressReason.VERIFIED_DONE -> R.string.phone_overlay_progress_reason_verified_done
            PhoneProgressReason.VERIFIED_NOT_DONE -> R.string.phone_overlay_progress_reason_verified_not_done
            PhoneProgressReason.MODEL_REPORTED -> R.string.phone_overlay_progress_reason_model_reported
            PhoneProgressReason.NEED_USER -> R.string.phone_overlay_progress_reason_need_user
        }

    private fun PhoneOverlayPhase.toUiPhase(): UiOverlayPhase =
        when (this) {
            PhoneOverlayPhase.IDLE -> UiOverlayPhase.Unavailable
            PhoneOverlayPhase.RUNNING -> UiOverlayPhase.Running
            PhoneOverlayPhase.PAUSING -> UiOverlayPhase.Pausing
            PhoneOverlayPhase.PAUSED -> UiOverlayPhase.Paused
            PhoneOverlayPhase.STOP_PENDING -> UiOverlayPhase.StopUnconfirmed
            PhoneOverlayPhase.UNKNOWN -> UiOverlayPhase.Unknown
            PhoneOverlayPhase.TAKEN_OVER -> UiOverlayPhase.TakenOver
            PhoneOverlayPhase.WAITING_USER -> UiOverlayPhase.NeedUser
            PhoneOverlayPhase.NEEDS_CHECK, PhoneOverlayPhase.ENDED -> UiOverlayPhase.Ended
        }
}

/** Native prompt data to the designer's immutable model. No visual decision lives here. */
internal fun PhoneOverlayPromptView.toOverlayPrompt(): OverlayPrompt =
    OverlayPrompt(
        formId = formId,
        generation = generation,
        noticeTitle = noticeTitle,
        noticeBody = noticeBody,
        questionTitle = questionTitle,
        questionDescription = questionDescription,
        choices = choices.map { OverlayPromptChoice(value = it.value, label = it.label) },
        delivery = delivery.toOverlayPromptDelivery(),
    )

/** Exhaustive mapping; a future native delivery state fails compilation instead of at runtime. */
internal fun PhoneOverlayPromptDelivery.toOverlayPromptDelivery(): OverlayPromptDelivery =
    when (this) {
        PhoneOverlayPromptDelivery.Checking -> OverlayPromptDelivery.Checking
        PhoneOverlayPromptDelivery.Ready -> OverlayPromptDelivery.Ready
        PhoneOverlayPromptDelivery.Sending -> OverlayPromptDelivery.Sending
        PhoneOverlayPromptDelivery.Accepted -> OverlayPromptDelivery.Accepted
        PhoneOverlayPromptDelivery.Unknown -> OverlayPromptDelivery.Unknown
        PhoneOverlayPromptDelivery.Blocked -> OverlayPromptDelivery.Blocked
        PhoneOverlayPromptDelivery.Refused -> OverlayPromptDelivery.Refused
        PhoneOverlayPromptDelivery.Unsupported -> OverlayPromptDelivery.Unsupported
        PhoneOverlayPromptDelivery.Superseded -> OverlayPromptDelivery.Superseded
    }

/** Plain notice data to the designer's immutable model. It carries no answer identity. */
internal fun PhoneOverlayNoticeView.toOverlaySessionNotice(): OverlaySessionNotice =
    OverlaySessionNotice(
        kind = kind.toOverlaySessionNoticeKind(),
        title = title,
        body = body,
    )

/** Exhaustive mapping; a future native notice kind fails compilation instead of at runtime. */
internal fun PhoneOverlayNoticeKind.toOverlaySessionNoticeKind(): OverlaySessionNoticeKind =
    when (this) {
        PhoneOverlayNoticeKind.Ready -> OverlaySessionNoticeKind.Ready
        PhoneOverlayNoticeKind.Error -> OverlaySessionNoticeKind.Error
        PhoneOverlayNoticeKind.Goal -> OverlaySessionNoticeKind.Goal
        PhoneOverlayNoticeKind.RestartInterrupted -> OverlaySessionNoticeKind.RestartInterrupted
    }
