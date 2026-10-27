package io.andchamber.app.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.andchamber.app.R
import io.andchamber.app.ui.components.ChamberCard
import io.andchamber.app.ui.theme.ChamberTextStyles
import io.andchamber.app.ui.theme.ChamberTheme

/** What the person is about to confirm; also used to continue after a pause. */
private data class GrantDraft(
    val target: PhoneTarget,
    val summary: String,
    val actions: Set<PhoneActionScope>,
)

/**
 * Connected, no lease: pick one app, describe the task, choose a short time and the actions, then
 * confirm natively. Each grant is new; nothing is renewed implicitly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GrantCard(
    state: PhoneScreenState,
    actions: PhoneControlActions,
) {
    // The draft outlives this card: a trip Home to start or restart OpenChamber must not cost the
    // person what they already filled in. A new agent proposal is applied once.
    LaunchedEffect(state.request, state.targets) {
        PhoneGrantDraftHolder.draft = PhoneGrantDraftHolder.draft.withRequest(state)
    }
    val draft = PhoneGrantDraftHolder.draft
    fun update(transform: (PhoneGrantDraft) -> PhoneGrantDraft) {
        PhoneGrantDraftHolder.draft = transform(PhoneGrantDraftHolder.draft)
    }
    val selectedPackage = draft.packageName
    val summary = draft.summary
    val minutes = draft.minutes
    val chosenActions = draft.actions
    var query by rememberSaveable { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    val target = state.targets.firstOrNull { it.packageName == selectedPackage }
    val block = grantBlock(state, target, summary, chosenActions)
    val runtimeReady = phoneRuntimeGate(state) == null

    ChamberCard {
        Column(modifier = Modifier.padding(PhoneCardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.phone_grant_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.request != null) {
                PhoneNotice(
                    tone = NoticeTone.Info,
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.phone_request_title),
                    body = stringResource(R.string.phone_request_body),
                )
            }

            // App
            StepHeading(1, stringResource(R.string.phone_grant_step_app), done = target != null && targetBlock(target, state.hostPackage) == null)
            BodyText(stringResource(R.string.phone_grant_app_body))
            TargetPicker(
                targets = state.targets,
                hostPackage = state.hostPackage,
                selected = selectedPackage,
                onSelect = { pkg -> update { it.copy(packageName = pkg) } },
                query = query,
                onQueryChange = { query = it },
            )

            HorizontalDivider()

            // Task
            StepHeading(2, stringResource(R.string.phone_grant_step_task), done = summary.isNotBlank())
            OutlinedTextField(
                value = summary,
                onValueChange = { text -> update { it.copy(summary = text.take(PhoneUiLimits.MAX_SUMMARY_CHARS + 20)) } },
                label = { Text(stringResource(R.string.phone_summary)) },
                supportingText = {
                    val tooLong = summary.trim().length > PhoneUiLimits.MAX_SUMMARY_CHARS
                    Text(
                        when {
                            tooLong -> stringResource(R.string.phone_summary_too_long, PhoneUiLimits.MAX_SUMMARY_CHARS)
                            showErrors && summary.isBlank() -> stringResource(R.string.phone_summary_required)
                            else -> stringResource(R.string.phone_summary_hint)
                        },
                    )
                },
                isError = summary.trim().length > PhoneUiLimits.MAX_SUMMARY_CHARS || (showErrors && summary.isBlank()),
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier.fillMaxWidth().testTag(PhoneTags.SUMMARY),
            )

            HorizontalDivider()

            // Limits
            StepHeading(3, stringResource(R.string.phone_grant_step_limits), done = chosenActions.isNotEmpty())
            Text(
                stringResource(R.string.phone_duration),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneUiLimits.DURATION_CHOICES_MIN.forEach { m ->
                    FilterChip(
                        selected = minutes == m,
                        onClick = { update { it.copy(minutes = m) } },
                        label = { Text(stringResource(R.string.phone_duration_minutes, m)) },
                        modifier = Modifier.heightIn(min = 40.dp),
                    )
                }
            }
            BodyText(stringResource(R.string.phone_duration_hint))
            Text(
                stringResource(R.string.phone_actions_allowed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PhoneActionScope.entries.forEach { action ->
                val checked = action in chosenActions
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { on -> update { it.copy(actions = if (on) it.actions + action else it.actions - action) } },
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(stringResource(action.label()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        if (action == PhoneActionScope.Input) {
                            Text(
                                stringResource(R.string.phone_action_input_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            BodyText(stringResource(R.string.phone_actions_observe_note))

            StopChannelGate(state.stopChannel, actions)

            if (showErrors && block != null && block != GrantBlock.StopChannel && block != GrantBlock.RuntimeNotReady) {
                Text(
                    stringResource(block.message()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!runtimeReady) {
                // The runtime notice at the top says what to do; keep the draft, just don't submit.
                BodyText(stringResource(R.string.phone_runtime_draft_kept))
            }
            PhonePrimaryButton(
                text = stringResource(R.string.phone_grant_review),
                // Disabled outright only for gates the person must fix outside this form.
                enabled = runtimeReady && state.stopChannel == PhoneStopChannel.Verified && state.pending == null,
                onClick = { if (block == null) confirming = true else showErrors = true },
                modifier = Modifier.testTag(PhoneTags.GRANT),
            )
        }
    }

    if (confirming && target != null) {
        GrantConfirmDialog(
            draft = GrantDraft(target, summary.trim(), chosenActions),
            minutes = minutes,
            resume = false,
            onConfirm = {
                confirming = false
                // Still re-check: the state may have changed while the dialog was open.
                // The draft is kept until the agent actually holds a lease (see PhoneControlScreen),
                // so a grant that fails because something wasn't ready doesn't cost the form.
                if (grantBlock(state, target, summary, chosenActions) == null) {
                    actions.onGrant(PhoneGrantRequest(target, summary.trim(), minutes * 60_000L, chosenActions))
                }
            },
            onDismiss = { confirming = false },
        )
    }
}

private fun GrantBlock.message(): Int =
    when (this) {
        GrantBlock.NotConnected -> R.string.phone_block_not_connected
        GrantBlock.RuntimeNotReady -> R.string.phone_runtime_draft_kept
        GrantBlock.StopChannel -> R.string.phone_stop_gate_title
        GrantBlock.NoTarget -> R.string.phone_block_no_target
        GrantBlock.TargetNotAllowed -> R.string.phone_block_target_not_allowed
        GrantBlock.NoSummary -> R.string.phone_summary_required
        GrantBlock.SummaryTooLong -> R.string.phone_block_summary_too_long
        GrantBlock.NoActions -> R.string.phone_block_no_actions
        GrantBlock.Busy -> R.string.phone_block_busy
    }

@Composable
private fun TargetPicker(
    targets: List<PhoneTarget>,
    hostPackage: String,
    selected: String?,
    onSelect: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    if (targets.isEmpty()) {
        BodyText(stringResource(R.string.phone_targets_empty))
        return
    }
    OutlinedTextField(
        value = query,
        onValueChange = { onQueryChange(it.take(80)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        label = { Text(stringResource(R.string.phone_target_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    // Compact on purpose: only choosable apps by default, blocked ones folded into one line, so
    // the task and limits below stay within a short scroll.
    val listing = targetListing(targets, hostPackage, query, selected)
    if (listing.rows.isEmpty()) {
        BodyText(
            stringResource(
                if (query.isBlank()) R.string.phone_targets_none_allowed else R.string.phone_targets_no_match,
            ),
        )
    }
    Column {
        listing.rows.forEach { (target, block) -> TargetRow(target, block, selected == target.packageName, onSelect) }
    }
    if (listing.moreAllowed > 0) {
        BodyText(stringResource(R.string.phone_targets_more, listing.moreAllowed))
    }
    if (listing.hiddenBlocked > 0) {
        BodyText(stringResource(R.string.phone_targets_blocked_hidden, listing.hiddenBlocked))
    }
}

@Composable
private fun TargetRow(
    target: PhoneTarget,
    block: TargetBlock?,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    val enabled = block == null
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onSelect(target.packageName) }
                .testTag(PhoneTags.TARGET + ":" + target.packageName)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected && enabled, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            val labelColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            Text(target.label, style = MaterialTheme.typography.bodyLarge, color = labelColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                target.packageName,
                style = ChamberTextStyles.codeSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (block != null) {
                Text(
                    stringResource(
                        when (block) {
                            TargetBlock.Host -> R.string.phone_target_block_host
                            TargetBlock.System -> R.string.phone_target_block_system
                            TargetBlock.Invalid -> R.string.phone_target_block_invalid
                            TargetBlock.Sensitive -> R.string.phone_target_block_sensitive
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The native confirmation. Worded neutrally: it records that the request was confirmed in this
 * app, and does not claim to prove who tapped it.
 */
@Composable
private fun GrantConfirmDialog(
    draft: GrantDraft,
    minutes: Int,
    resume: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                stringResource(if (resume) R.string.phone_resume_confirm_title else R.string.phone_grant_confirm_title, draft.target.label),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(draft.target.packageName, style = ChamberTextStyles.codeSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(draft.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(10.dp))
                }
                Text(stringResource(R.string.phone_grant_confirm_actions, actionList(draft.actions)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.phone_grant_confirm_time, minutes), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.phone_grant_confirm_stops), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.phone_grant_confirm_screen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(PhoneTags.GRANT_CONFIRM)) {
                Text(stringResource(if (resume) R.string.phone_resume else R.string.phone_grant_allow), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The agent holds a lease: what it may do, where, and until when. */
@Composable
internal fun RunningCard(
    state: PhoneScreenState,
    now: Long,
    actions: PhoneControlActions,
) {
    val agent = state.control as? PhoneControl.Agent ?: return
    val lease = agent.lease
    ChamberCard(accent = MaterialTheme.colorScheme.primary) {
        Column(modifier = Modifier.padding(PhoneCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.phone_running_title, lease.target.label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(lease.target.packageName, style = ChamberTextStyles.codeSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                Text(lease.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(10.dp))
            }
            BodyText(
                if (lease.permanent()) {
                    stringResource(R.string.phone_running_limits_permanent, actionList(lease.actions))
                } else {
                    stringResource(R.string.phone_running_limits, actionList(lease.actions), timeLeftText(lease.expiresAtMs, now))
                },
            )
            if (agent.actionInFlight) {
                BodyText(stringResource(R.string.phone_running_in_flight), color = MaterialTheme.colorScheme.onSurface)
            }
            BodyText(stringResource(if (lease.permanent()) R.string.phone_running_auto_pause_permanent else R.string.phone_running_auto_pause))
            BodyText(stringResource(R.string.phone_running_no_renew))
            PhoneSecondaryButton(
                text = stringResource(R.string.phone_open_target, lease.target.label),
                onClick = { actions.onOpenTarget(lease.target.packageName) },
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                modifier = Modifier.testTag(PhoneTags.OPEN_TARGET),
            )
        }
    }
}

/**
 * The agent was stopped. The wording depends on what the executor confirmed: "no further steps"
 * only when nothing was in flight; otherwise the last action may still apply and must be checked.
 * Never offers an automatic retry; continuing is a fresh native confirmation.
 */
@Composable
internal fun HaltedCard(
    state: PhoneScreenState,
    actions: PhoneControlActions,
) {
    val halted = state.control as? PhoneControl.Halted ?: return
    val lease = halted.lease
    val reason = stringResource(halted.reason.label())
    var confirming by remember { mutableStateOf(false) }
    var minutes by rememberSaveable { mutableIntStateOf(PhoneUiLimits.DEFAULT_DURATION_MIN) }

    ChamberCard {
        Column(modifier = Modifier.padding(PhoneCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val mayApply = halted.lastAction == PhoneLastAction.MayStillApply
            val continueFirst = haltContinueFirst(state)
            val notResumed = continueFirst && state.standing?.continuation?.delivery == PhoneContinueDelivery.NotResumed
            when {
                // A confirmed continue didn't resume: lead with that (task kept + the fixed technical
                // reason), not with the original "do this step yourself" again.
                notResumed -> {
                    val continuation = state.standing!!.continuation
                    ContinueDeliveryNotice(continuation.delivery, running = false, failure = continuation.failure)
                }
                !halted.confirmed ->
                    PhoneNotice(
                        tone = NoticeTone.Warning,
                        icon = Icons.Rounded.WarningAmber,
                        title = stringResource(R.string.phone_halt_pending_title, reason),
                        body = stringResource(R.string.phone_halt_pending_body),
                    )
                mayApply ->
                    PhoneNotice(
                        tone = NoticeTone.Warning,
                        icon = Icons.Rounded.WarningAmber,
                        title = stringResource(R.string.phone_halt_title, reason),
                        body = stringResource(R.string.phone_halt_may_apply, lease.target.label),
                    )
                // The agent asked for the person: say it is waiting for them and why, not
                // "you took over". Only after a confirmed stop with nothing left to check.
                haltWaitsForPerson(state) ->
                    PhoneNotice(
                        tone = NoticeTone.Warning,
                        icon = Icons.Rounded.PauseCircle,
                        title = stringResource(R.string.phone_halt_need_user_title),
                        body = stringResource(R.string.phone_halt_need_user_body, lease.target.label),
                        modifier = Modifier.testTag(PhoneTags.HALT_NEED_USER),
                    )
                halted.lastAction == PhoneLastAction.UserChecked ->
                    PhoneNotice(
                        tone = NoticeTone.Info,
                        icon = Icons.Rounded.PauseCircle,
                        title = stringResource(R.string.phone_halt_title, reason),
                        body = stringResource(R.string.phone_halt_user_checked),
                    )
                else ->
                    PhoneNotice(
                        tone = NoticeTone.Info,
                        icon = Icons.Rounded.PauseCircle,
                        title = stringResource(R.string.phone_halt_title, reason),
                        body = stringResource(R.string.phone_halt_none_in_flight),
                    )
            }
            // Continue right below the reason; the task details and the agent's note follow.
            if (continueFirst) ContinueInChatBlock(state, actions, lead = true)
            Text(
                stringResource(R.string.phone_halt_task, lease.target.label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                Text(lease.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(10.dp))
            }
            PhoneSecondaryButton(
                text = stringResource(if (mayApply) R.string.phone_open_target_check else R.string.phone_open_target, lease.target.label),
                onClick = { actions.onOpenTarget(lease.target.packageName) },
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                modifier = Modifier.testTag(PhoneTags.OPEN_TARGET),
            )
            if (mayApply && halted.confirmed) {
                PhoneSecondaryButton(
                    text = stringResource(R.string.phone_ack_checked),
                    onClick = actions.onAcknowledgeChecked,
                    enabled = state.pending == null,
                    modifier = Modifier.testTag(PhoneTags.ACK_CHECKED),
                )
            }

            if (continueFirst) {
                // Already offered above; only the agent's own (unverified) note stays here.
                ContinueAgentNote(state)
            } else if (usesStanding(state)) {
                // Configure-once mode: continue beside the chat after a native confirmation; the
                // reason it waits is shown there. No per-task grant form here.
                ContinueInChatBlock(state, actions)
            } else if (halted.confirmed && !mayApply) {
                HorizontalDivider()
                StopChannelGate(state.stopChannel, actions)
                if (state.device.link != PhoneLink.Connected) {
                    BodyText(stringResource(R.string.phone_resume_needs_connection))
                }
                Text(stringResource(R.string.phone_resume_duration), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                DurationChips(minutes) { minutes = it }
                PhonePrimaryButton(
                    text = stringResource(R.string.phone_resume_review),
                    onClick = { confirming = true },
                    enabled = canOfferResume(state),
                    modifier = Modifier.testTag(PhoneTags.RESUME),
                )
                BodyText(stringResource(R.string.phone_resume_note))
            }
        }
    }

    if (confirming) {
        GrantConfirmDialog(
            draft = GrantDraft(lease.target, lease.summary, lease.actions),
            minutes = minutes,
            resume = true,
            onConfirm = {
                confirming = false
                if (canOfferResume(state)) {
                    actions.onGrant(PhoneGrantRequest(lease.target, lease.summary, minutes * 60_000L, lease.actions))
                }
            },
            onDismiss = { confirming = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationChips(
    minutes: Int,
    onChange: (Int) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhoneUiLimits.DURATION_CHOICES_MIN.forEach { m ->
            FilterChip(
                selected = minutes == m,
                onClick = { onChange(m) },
                label = { Text(stringResource(R.string.phone_duration_minutes, m)) },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
    }
}

/** The task ended. Status per step; "done" only for steps re-read from the target app. */
@Composable
internal fun ResultCard(
    state: PhoneScreenState,
    actions: PhoneControlActions,
) {
    val result = (state.task as? PhoneTask.Ended)?.result ?: return
    val overall = overallResult(result.items)
    val (title, body) =
        when (overall) {
            PhoneOverall.Verified -> R.string.phone_result_verified to R.string.phone_result_verified_body
            PhoneOverall.Partial -> R.string.phone_result_partial to R.string.phone_result_partial_body
            PhoneOverall.NeedsCheck -> R.string.phone_result_needs_check to R.string.phone_result_needs_check_body
        }
    ChamberCard(modifier = Modifier.testTag(PhoneTags.RESULT)) {
        Column(modifier = Modifier.padding(PhoneCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            BodyText(stringResource(body))
            if (result.summary.isNotBlank()) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(result.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(10.dp))
                }
            }
            if (result.items.isEmpty()) {
                BodyText(stringResource(R.string.phone_result_no_steps))
            }
            result.items.forEach { ResultRow(it) }
            if (overall != PhoneOverall.Verified && result.target != null) {
                PhonePrimaryButton(
                    text = stringResource(R.string.phone_open_target_check, result.target.label),
                    onClick = { actions.onOpenTarget(result.target.packageName) },
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    modifier = Modifier.testTag(PhoneTags.OPEN_TARGET),
                )
            }
            PhoneSecondaryButton(text = stringResource(R.string.phone_result_done), onClick = actions.onDismissResult)
        }
    }
}

@Composable
private fun ResultRow(item: PhoneResultItem) {
    val (icon, tint, label) =
        when (item.check) {
            PhoneCheck.Verified -> Triple(Icons.Rounded.CheckCircle, ChamberTheme.colors.success, R.string.phone_check_verified)
            PhoneCheck.AgentReported -> Triple(Icons.Rounded.Info, ChamberTheme.colors.warning, R.string.phone_check_reported)
            PhoneCheck.Unknown -> Triple(Icons.Rounded.HelpOutline, MaterialTheme.colorScheme.error, R.string.phone_check_unknown)
        }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = tint.takeOrElse())
        }
    }
}

@Composable
private fun Color.takeOrElse(): Color = if (this == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else this
