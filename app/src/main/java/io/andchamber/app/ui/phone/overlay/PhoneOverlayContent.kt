package io.andchamber.app.ui.phone.overlay

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.andchamber.app.R
import io.andchamber.app.ui.components.StatusDot
import io.andchamber.app.ui.components.StatusTone
import io.andchamber.app.ui.theme.ChamberTheme
import kotlinx.coroutines.delay

/**
 * The overlay's content: the capsule, or the task panel when [PhoneOverlayState.expanded] (or an
 * end confirmation) is set. Meant for a ComposeView in a non-focusable overlay window; the window
 * manager owns placement, snapping, insets and the end drop zone, and reports them through state.
 *
 * Wrap it in the app theme (`AndChamberTheme`). [clock] is the same clock as the lease times.
 */
@Composable
internal fun PhoneOverlayContent(
    state: PhoneOverlayState,
    actions: PhoneOverlayActions,
    modifier: Modifier = Modifier,
    clock: () -> Long = System::currentTimeMillis,
) {
    // Coarse ticks only: the visible "N min left / N s ago" text, never an announcement.
    val now by produceState(clock(), state.leaseEndsAtMs, state.events.size) {
        while (true) {
            value = clock()
            delay(15_000)
        }
    }
    val title = stringResource(R.string.phone_overlay_window_title)
    Box(modifier = modifier.semantics { paneTitle = title }) {
        if (showsPanel(state)) {
            OverlayPanel(state = state, actions = actions, now = now)
        } else {
            OverlayCapsule(state = state, actions = actions, now = now)
        }
    }
}

private val CapsuleShape = RoundedCornerShape(28.dp)

@Composable
private fun OverlayCapsule(
    state: PhoneOverlayState,
    actions: PhoneOverlayActions,
    now: Long,
) {
    val lines = capsuleLines(state, now)
    val moveLeft = stringResource(R.string.phone_overlay_move_left)
    val moveRight = stringResource(R.string.phone_overlay_move_right)
    val expandCd = stringResource(R.string.phone_overlay_expand_cd)
    val stateLine = lines.first
    Surface(
        shape = CapsuleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, ChamberTheme.colors.cardBorder),
        shadowElevation = 6.dp,
        modifier =
            Modifier
                .widthIn(max = 280.dp)
                .testTag(OverlayTags.CAPSULE)
                .semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(moveLeft) { actions.onMoveLeft(); true },
                            CustomAccessibilityAction(moveRight) { actions.onMoveRight(); true },
                        )
                },
    ) {
        Row(modifier = Modifier.heightIn(min = 56.dp).padding(start = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            DragHandle(actions)
            Row(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = expandCd) { actions.onExpand() }
                        // Only the phase is exposed and announced; line 2 (the minute countdown and
                        // count) is left out so it is never read out on each tick. The panel has it.
                        .clearAndSetSemantics {
                            contentDescription = stateLine
                            liveRegion = LiveRegionMode.Polite
                            onClick(label = expandCd) { actions.onExpand(); true }
                        }
                        .testTag(OverlayTags.CAPSULE_BODY)
                        .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(tone = phaseTone(state.phase), size = 8.dp)
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            lines.first,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (state.demo) DemoTag()
                    }
                    Text(
                        lines.second,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            CapsulePrimary(state, actions)
        }
    }
}

/** A plain drag handle. The window manager turns the deltas into window moves and snapping. */
@Composable
private fun DragHandle(actions: PhoneOverlayActions) {
    val latest by rememberUpdatedState(actions)
    val cd = stringResource(R.string.phone_overlay_drag_cd)
    Box(
        modifier =
            Modifier
                .size(width = 28.dp, height = 48.dp)
                .semantics { contentDescription = cd }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { latest.onDragEnd() },
                        onDragCancel = { latest.onDragEnd() },
                    ) { change, drag ->
                        change.consume()
                        latest.onDragBy(drag.x, drag.y)
                    }
                }
                .testTag(OverlayTags.DRAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.DragIndicator, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CapsulePrimary(
    state: PhoneOverlayState,
    actions: PhoneOverlayActions,
) {
    when (overlayPrimary(state.phase)) {
        OverlayPrimary.TakeOver -> {
            val cd = stringResource(R.string.phone_overlay_take_over_cd)
            Button(
                onClick = actions.onTakeOver,
                shape = RoundedCornerShape(24.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = cd }.testTag(OverlayTags.PRIMARY),
            ) {
                Icon(Icons.Rounded.PanTool, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.phone_overlay_take_over), fontWeight = FontWeight.SemiBold)
            }
        }
        OverlayPrimary.OpenNative ->
            CapsuleIconButton(Icons.AutoMirrored.Rounded.KeyboardReturn, stringResource(R.string.phone_overlay_open_native), actions.onOpenNative)
        OverlayPrimary.Look ->
            CapsuleIconButton(Icons.Rounded.Search, stringResource(R.string.phone_overlay_look), actions.onExpand)
        OverlayPrimary.Disabled ->
            CapsuleIconButton(Icons.Rounded.HourglassTop, stringResource(R.string.phone_overlay_waiting_confirm), null)
    }
}

@Composable
private fun CapsuleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(48.dp).testTag(OverlayTags.PRIMARY),
    ) {
        IconButton(onClick = { onClick?.invoke() }, enabled = onClick != null) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun DemoTag() {
    Spacer(Modifier.width(6.dp))
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            stringResource(R.string.phone_overlay_demo_tag),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

private fun phaseTone(phase: OverlayPhase): StatusTone =
    when (phase) {
        OverlayPhase.Running, OverlayPhase.Pausing, OverlayPhase.Taking, OverlayPhase.Ending, OverlayPhase.NeedUserPending -> StatusTone.Busy
        OverlayPhase.Paused, OverlayPhase.TakenOver, OverlayPhase.NeedUser, OverlayPhase.Unavailable -> StatusTone.Idle
        OverlayPhase.Unknown, OverlayPhase.StopUnconfirmed -> StatusTone.Error
        OverlayPhase.Ended -> StatusTone.Live
    }

@StringRes
private fun phaseTitle(phase: OverlayPhase): Int =
    when (phase) {
        OverlayPhase.Running -> R.string.phone_overlay_running
        OverlayPhase.Pausing -> R.string.phone_overlay_pausing
        OverlayPhase.Paused -> R.string.phone_overlay_paused
        OverlayPhase.Taking -> R.string.phone_overlay_taking
        OverlayPhase.TakenOver -> R.string.phone_overlay_taken
        OverlayPhase.NeedUserPending -> R.string.phone_overlay_need_user_pending
        OverlayPhase.NeedUser -> R.string.phone_overlay_need_user
        OverlayPhase.Unknown -> R.string.phone_overlay_unknown
        OverlayPhase.Ending -> R.string.phone_overlay_ending
        OverlayPhase.Ended -> R.string.phone_overlay_ended
        OverlayPhase.StopUnconfirmed -> R.string.phone_overlay_unconfirmed
        OverlayPhase.Unavailable -> R.string.phone_overlay_unavailable
    }

@StringRes
private fun phaseSub(phase: OverlayPhase): Int? =
    when (phase) {
        OverlayPhase.Running -> null
        OverlayPhase.Pausing -> R.string.phone_overlay_pausing_sub
        OverlayPhase.Paused -> R.string.phone_overlay_paused_sub
        OverlayPhase.Taking -> R.string.phone_overlay_waiting_confirm
        OverlayPhase.TakenOver -> R.string.phone_overlay_taken_sub
        OverlayPhase.NeedUserPending -> R.string.phone_overlay_need_user_pending_sub
        OverlayPhase.NeedUser -> R.string.phone_overlay_need_user_sub
        OverlayPhase.Unknown -> R.string.phone_overlay_unknown_sub
        OverlayPhase.Ending -> R.string.phone_overlay_ending_sub
        OverlayPhase.Ended -> R.string.phone_overlay_ended_sub
        OverlayPhase.StopUnconfirmed -> R.string.phone_overlay_unconfirmed_sub
        OverlayPhase.Unavailable -> R.string.phone_overlay_unavailable_sub
    }

/** Line 1 is the phase; line 2 is a real count/time for Running, a fixed sub-line otherwise. */
@Composable
private fun capsuleLines(
    state: PhoneOverlayState,
    now: Long,
): Pair<String, String> {
    val title = stringResource(phaseTitle(state.phase))
    val sub = phaseSub(state.phase)
    if (sub != null) return title to stringResource(sub)
    val parts = ArrayList<String>(2)
    state.sentCount?.let { parts += stringResource(R.string.phone_overlay_sent_count, it) }
    overlayMinutesLeft(state.leaseEndsAtMs, now)?.let { parts += stringResource(R.string.phone_overlay_minutes_left, it) }
    return title to (parts.joinToString(" · ").ifEmpty { stringResource(R.string.phone_overlay_waiting_update_none) })
}

// ---- panel -----------------------------------------------------------------------------------

@Composable
private fun OverlayPanel(
    state: PhoneOverlayState,
    actions: PhoneOverlayActions,
    now: Long,
) {
    val collapseCd = stringResource(R.string.phone_overlay_collapse_cd)
    BoxWithConstraints {
        // Never taller than the window allows; content scrolls inside (large text, landscape, IME).
        val maxH = if (constraints.hasBoundedHeight) maxHeight else 560.dp
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, ChamberTheme.colors.cardBorder),
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(min = 240.dp, max = 360.dp).heightIn(max = maxH).testTag(OverlayTags.PANEL),
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(tone = phaseTone(state.phase), size = 8.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(phaseTitle(state.phase)),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f, fill = false).semantics {
                                    heading()
                                    liveRegion = LiveRegionMode.Polite
                                },
                            )
                            if (state.demo) DemoTag()
                        }
                        ControlLine(state, now)
                    }
                    if (!state.endConfirm) {
                        IconButton(onClick = actions.onCollapse, modifier = Modifier.size(48.dp).testTag(OverlayTags.COLLAPSE)) {
                            Icon(Icons.Rounded.ExpandMore, contentDescription = collapseCd)
                        }
                    }
                }

                if (state.endConfirm && canRequestEnd(state.phase)) {
                    EndConfirm(actions)
                } else {
                    PanelBody(state, actions, now)
                }
            }
        }
    }
}

@Composable
private fun ControlLine(
    state: PhoneOverlayState,
    now: Long,
) {
    val agentHolds = state.phase == OverlayPhase.Running || state.phase == OverlayPhase.Pausing || state.phase == OverlayPhase.Paused
    val text =
        if (agentHolds) {
            val minutes = overlayMinutesLeft(state.leaseEndsAtMs, now)
            if (minutes != null) {
                stringResource(R.string.phone_overlay_control_agent, state.targetLabel, stringResource(R.string.phone_overlay_minutes_left, minutes))
            } else {
                stringResource(R.string.phone_overlay_control_agent_no_time, state.targetLabel)
            }
        } else {
            stringResource(R.string.phone_overlay_control_person, state.targetLabel)
        }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PanelBody(
    state: PhoneOverlayState,
    actions: PhoneOverlayActions,
    now: Long,
) {
    val phase = state.phase
    notice(phase)?.let { (tone, text) -> Notice(tone, stringResource(text)) }

    if (phase == OverlayPhase.Running || phase == OverlayPhase.Paused) SummarySection(state.summary)

    if (phase == OverlayPhase.Ended) {
        ResultSections(state.events, now)
        Section(stringResource(R.string.phone_overlay_section_you)) { Body(stringResource(R.string.phone_overlay_ended_you)) }
    } else {
        Section(stringResource(R.string.phone_overlay_section_recent)) {
            val recent = recentEvents(state.events)
            if (recent.isEmpty()) Body(stringResource(R.string.phone_overlay_no_events))
            recent.forEach { EventRow(it, now) }
        }
        if (phase == OverlayPhase.Running) {
            Section(stringResource(R.string.phone_overlay_section_waiting)) {
                val last = state.events.maxOfOrNull { it.atMs }
                Body(
                    if (last == null) {
                        stringResource(R.string.phone_overlay_waiting_update_none)
                    } else {
                        stringResource(R.string.phone_overlay_waiting_update, agoText(last, now))
                    },
                )
            }
            Section(stringResource(R.string.phone_overlay_section_you)) { Body(stringResource(R.string.phone_overlay_running_you)) }
        }
    }

    val buttons = panelActions(phase)
    if (buttons.isNotEmpty()) {
        Spacer(Modifier.size(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            buttons.forEachIndexed { index, action -> PanelButton(action, primary = index == 0, actions = actions) }
        }
    }
    foot(phase)?.let { Foot(stringResource(it)) }
}

private enum class NoticeTone { Warn, Error, Info, Ok }

private fun notice(phase: OverlayPhase): Pair<NoticeTone, Int>? =
    when (phase) {
        OverlayPhase.Running -> null
        OverlayPhase.Pausing -> NoticeTone.Warn to R.string.phone_overlay_pausing_notice
        OverlayPhase.Paused -> NoticeTone.Warn to R.string.phone_overlay_paused_notice
        OverlayPhase.Taking -> NoticeTone.Warn to R.string.phone_overlay_taking_notice
        OverlayPhase.TakenOver -> NoticeTone.Ok to R.string.phone_overlay_taken_notice
        OverlayPhase.NeedUserPending -> NoticeTone.Info to R.string.phone_overlay_need_user_pending_notice
        OverlayPhase.NeedUser -> NoticeTone.Info to R.string.phone_overlay_need_user_notice
        OverlayPhase.Unknown -> NoticeTone.Error to R.string.phone_overlay_unknown_notice
        OverlayPhase.StopUnconfirmed -> NoticeTone.Error to R.string.phone_overlay_unconfirmed_notice
        OverlayPhase.Ending -> NoticeTone.Warn to R.string.phone_overlay_ending_notice
        OverlayPhase.Ended -> null
        OverlayPhase.Unavailable -> NoticeTone.Info to R.string.phone_overlay_unavailable_notice
    }

private fun foot(phase: OverlayPhase): Int? =
    when (phase) {
        OverlayPhase.Running -> R.string.phone_overlay_running_foot
        OverlayPhase.Paused, OverlayPhase.TakenOver -> R.string.phone_overlay_continue_foot
        OverlayPhase.NeedUser, OverlayPhase.NeedUserPending -> R.string.phone_overlay_need_user_foot
        OverlayPhase.Unknown -> R.string.phone_overlay_unknown_foot
        OverlayPhase.StopUnconfirmed -> R.string.phone_overlay_unconfirmed_foot
        else -> null
    }

@Composable
private fun Notice(
    tone: NoticeTone,
    text: String,
) {
    val (bg, fg) =
        when (tone) {
            NoticeTone.Warn -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            NoticeTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            NoticeTone.Info -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            NoticeTone.Ok -> ChamberTheme.colors.successContainer to ChamberTheme.colors.onSuccessContainer
        }
    Spacer(Modifier.size(10.dp))
    Surface(shape = MaterialTheme.shapes.medium, color = bg, modifier = Modifier.fillMaxWidth().testTag(OverlayTags.NOTICE)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.size(10.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.size(4.dp))
    content()
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Foot(text: String) {
    Spacer(Modifier.size(8.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Collapsed by default: the summary may be personal. Only the text the person wrote natively. */
@Composable
private fun SummarySection(summary: String?) {
    if (summary.isNullOrBlank()) return
    var open by remember { mutableStateOf(false) }
    Section(stringResource(R.string.phone_overlay_section_task)) {
        TextButton(
            onClick = { open = !open },
            modifier = Modifier.heightIn(min = 48.dp).testTag(OverlayTags.SUMMARY_TOGGLE),
        ) {
            Text(stringResource(if (open) R.string.phone_overlay_summary_hide else R.string.phone_overlay_summary_show))
        }
        if (open) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.phone_overlay_summary_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EventRow(
    event: OverlayEvent,
    now: Long,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(event.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            SourceLabel(event.source)
        }
        Spacer(Modifier.width(8.dp))
        Text(agoText(event.atMs, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceLabel(source: OverlaySource) {
    val (text, bg, fg) =
        when (source) {
            OverlaySource.NativeObserved ->
                Triple(R.string.phone_overlay_src_observed, ChamberTheme.colors.successContainer, ChamberTheme.colors.onSuccessContainer)
            OverlaySource.NativeConfirmed ->
                Triple(R.string.phone_overlay_src_confirmed, ChamberTheme.colors.successContainer, ChamberTheme.colors.onSuccessContainer)
            OverlaySource.Sent ->
                Triple(R.string.phone_overlay_src_sent, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface)
            OverlaySource.SentUnknown ->
                Triple(R.string.phone_overlay_src_sent_unknown, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            OverlaySource.AgentNote ->
                Triple(R.string.phone_overlay_src_agent, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        }
    Spacer(Modifier.size(2.dp))
    Surface(shape = MaterialTheme.shapes.extraSmall, color = bg) {
        Text(stringResource(text), style = MaterialTheme.typography.labelSmall, color = fg, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

@Composable
private fun ResultSections(
    events: List<OverlayEvent>,
    now: Long,
) {
    val groups = resultGroups(events)
    if (groups.native.isNotEmpty()) Section(stringResource(R.string.phone_overlay_result_native)) { groups.native.forEach { EventRow(it, now) } }
    if (groups.sent.isNotEmpty()) Section(stringResource(R.string.phone_overlay_result_sent)) { groups.sent.forEach { EventRow(it, now) } }
    if (groups.agent.isNotEmpty()) Section(stringResource(R.string.phone_overlay_result_agent)) { groups.agent.forEach { EventRow(it, now) } }
    if (events.isEmpty()) Section(stringResource(R.string.phone_overlay_section_recent)) { Body(stringResource(R.string.phone_overlay_no_events)) }
}

@Composable
private fun agoText(
    atMs: Long,
    now: Long,
): String =
    when (val a = ago(atMs, now)) {
        Ago.JustNow -> stringResource(R.string.phone_overlay_just_now)
        is Ago.Seconds -> stringResource(R.string.phone_overlay_seconds_ago, a.value)
        is Ago.Minutes -> stringResource(R.string.phone_overlay_minutes_ago, a.value)
    }

@Composable
private fun PanelButton(
    action: OverlayAction,
    primary: Boolean,
    actions: PhoneOverlayActions,
) {
    val (label, onClick) =
        when (action) {
            OverlayAction.TakeOver -> R.string.phone_overlay_btn_take_over to actions.onTakeOver
            OverlayAction.Pause -> R.string.phone_overlay_btn_pause to actions.onPause
            OverlayAction.OpenNative -> R.string.phone_overlay_btn_open_native to actions.onOpenNative
            OverlayAction.End -> R.string.phone_overlay_btn_end to actions.onRequestEnd
            OverlayAction.Collapse -> R.string.phone_overlay_btn_collapse to actions.onCollapse
            OverlayAction.RetryTakeOver -> R.string.phone_overlay_btn_retry_take_over to actions.onTakeOver
            OverlayAction.CloseResult -> R.string.phone_overlay_btn_close_result to actions.onCloseResult
        }
    val mod = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(OverlayTags.button(action))
    when {
        primary -> Button(onClick = onClick, shape = MaterialTheme.shapes.medium, modifier = mod) { Text(stringResource(label)) }
        action == OverlayAction.End ->
            TextButton(
                onClick = onClick,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = mod,
            ) { Text(stringResource(label)) }
        action == OverlayAction.Collapse || action == OverlayAction.CloseResult ->
            TextButton(onClick = onClick, modifier = mod) { Text(stringResource(label)) }
        else -> OutlinedButton(onClick = onClick, shape = MaterialTheme.shapes.medium, modifier = mod) { Text(stringResource(label)) }
    }
}

@Composable
private fun EndConfirm(actions: PhoneOverlayActions) {
    Spacer(Modifier.size(10.dp))
    Text(
        stringResource(R.string.phone_overlay_end_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.size(4.dp))
    Body(stringResource(R.string.phone_overlay_end_body))
    Spacer(Modifier.size(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = actions.onCancelEnd,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(OverlayTags.END_CANCEL),
        ) { Text(stringResource(R.string.phone_overlay_end_cancel)) }
        Button(
            onClick = actions.onConfirmEnd,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(OverlayTags.END_CONFIRM),
        ) { Text(stringResource(R.string.phone_overlay_end_confirm)) }
    }
}

/** Stable tags for instrumentation and the window manager. */
internal object OverlayTags {
    const val CAPSULE = "overlay.capsule"
    const val CAPSULE_BODY = "overlay.capsuleBody"
    const val DRAG = "overlay.drag"
    const val PRIMARY = "overlay.primary"
    const val PANEL = "overlay.panel"
    const val COLLAPSE = "overlay.collapse"
    const val NOTICE = "overlay.notice"
    const val SUMMARY_TOGGLE = "overlay.summary"
    const val END_CANCEL = "overlay.endCancel"
    const val END_CONFIRM = "overlay.endConfirm"
    const val SETTINGS = "overlay.settings"

    fun button(action: OverlayAction): String = "overlay.btn." + action.name
}
