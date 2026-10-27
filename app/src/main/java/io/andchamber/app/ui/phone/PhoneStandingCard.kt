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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.andchamber.app.R
import io.andchamber.app.ui.components.ChamberCard
import io.andchamber.app.ui.theme.ChamberTextStyles

/**
 * Configure once, then use from chat. Shows whether chat can use the phone right now (and why not),
 * the standing scope (apps, actions, window, per-task time), and lets the person change or revoke
 * it. Saving always goes through a native confirmation; nothing here is a per-task grant.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneStandingCard(
    state: PhoneScreenState,
    actions: PhoneControlActions,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val standing = state.standing ?: return
    val readiness = standingReadiness(state)
    var editing by rememberSaveable { mutableStateOf(false) }
    val editable = readiness == StandingReadiness.NotSet || readiness == StandingReadiness.Expired || readiness == StandingReadiness.Revoked

    ChamberCard(modifier = modifier.testTag(PhoneTags.STANDING)) {
        Column(modifier = Modifier.padding(PhoneCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.phone_standing_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            ReadinessNotice(readiness, standing, actions)
            if (readiness == StandingReadiness.Paused) StandingPauseBlock(state, actions)
            // Outside the stopped card (which shows it itself): e.g. the task runs again after a
            // continue whose chat wake-up failed. Read-only; "Open the chat" below stays available.
            standingDeliveryNotice(state)?.let { ContinueDeliveryNotice(it, running = state.control is PhoneControl.Agent) }

            if (!editable && !editing) {
                ScopeSummary(standing, now)
                PhoneButtonColumn {
                    if (readiness == StandingReadiness.Ready) {
                        PhonePrimaryButton(
                            text = stringResource(R.string.phone_standing_open_chat),
                            onClick = actions.onOpenChat,
                            icon = Icons.AutoMirrored.Rounded.Chat,
                            modifier = Modifier.testTag(PhoneTags.OPEN_CHAT),
                        )
                    }
                    PhoneSecondaryButton(
                        text = stringResource(R.string.phone_standing_edit),
                        onClick = { editing = true },
                        enabled = state.pending == null && state.control !is PhoneControl.Agent,
                    )
                    TextButton(
                        onClick = actions.onRevokeStanding,
                        enabled = state.pending == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(PhoneTags.REVOKE_STANDING),
                    ) { Text(stringResource(R.string.phone_standing_revoke), color = MaterialTheme.colorScheme.error) }
                }
            } else {
                StandingEditor(state, standing, actions, onDone = { editing = false }, cancellable = !editable)
            }
        }
    }
}

@Composable
private fun ReadinessNotice(
    readiness: StandingReadiness,
    standing: PhoneStanding,
    actions: PhoneControlActions,
) {
    var confirmRestart by remember { mutableStateOf(false) }
    val mod = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (readiness) {
        StandingReadiness.Ready ->
            PhoneNotice(
                NoticeTone.Info,
                Icons.Rounded.CheckCircle,
                stringResource(R.string.phone_standing_ready_title),
                body = stringResource(R.string.phone_standing_ready_body),
                modifier = mod.testTag(PhoneTags.STANDING_READY),
            )
        StandingReadiness.Paused -> {
            val pause = standing.pause
            PhoneNotice(
                NoticeTone.Warning,
                Icons.Rounded.WarningAmber,
                stringResource(R.string.phone_standing_paused_title),
                body =
                    listOfNotNull(
                        pause?.let { stringResource(it.reason.label()) },
                        stringResource(R.string.phone_standing_paused_body),
                    ).joinToString(" "),
                modifier = mod.testTag(PhoneTags.STANDING_PAUSED),
            )
        }
        StandingReadiness.NeedsRestart ->
            PhoneNotice(
                NoticeTone.Warning,
                Icons.Rounded.RestartAlt,
                stringResource(R.string.phone_standing_restart_title),
                body = stringResource(R.string.phone_standing_restart_body),
                modifier = mod,
            ) {
                PhonePrimaryButton(
                    text = stringResource(R.string.phone_standing_restart),
                    onClick = { confirmRestart = true },
                    modifier = Modifier.testTag(PhoneTags.RESTART_RUNTIME),
                )
            }
        StandingReadiness.Restarting ->
            PhoneNotice(NoticeTone.Info, Icons.Rounded.RestartAlt, stringResource(R.string.phone_standing_restarting_title), body = stringResource(R.string.phone_standing_restarting_body), modifier = mod)
        StandingReadiness.RuntimeNotRunning ->
            PhoneNotice(NoticeTone.Warning, Icons.Rounded.WarningAmber, stringResource(R.string.phone_standing_not_running_title), body = stringResource(R.string.phone_standing_not_running_body), modifier = mod) {
                TextButton(onClick = actions.onBack) { Text(stringResource(R.string.phone_runtime_go_home)) }
            }
        StandingReadiness.ToolUnknown ->
            PhoneNotice(NoticeTone.Info, Icons.Rounded.Info, stringResource(R.string.phone_standing_unknown_title), body = stringResource(R.string.phone_standing_unknown_body), modifier = mod) {
                TextButton(onClick = actions.onRecheckRuntime) { Text(stringResource(R.string.check_again)) }
            }
        StandingReadiness.NotConnected ->
            PhoneNotice(NoticeTone.Warning, Icons.Rounded.WarningAmber, stringResource(R.string.phone_standing_not_connected_title), body = stringResource(R.string.phone_standing_not_connected_body), modifier = mod)
        StandingReadiness.Expired ->
            PhoneNotice(NoticeTone.Info, Icons.Rounded.Info, stringResource(R.string.phone_standing_expired_title), body = stringResource(R.string.phone_standing_setup_body), modifier = mod)
        StandingReadiness.Revoked, StandingReadiness.NotSet ->
            BodyText(stringResource(R.string.phone_standing_setup_body))
    }
    // Paused but something else must be fixed first (that notice is shown above): say the pause
    // stays, so a reconnect or restart is never read as "ready again".
    if (readiness != StandingReadiness.Paused && standing.pause != null && standing.status is PhoneStandingStatus.Active) {
        BodyText(stringResource(R.string.phone_standing_paused_also), modifier = Modifier.testTag(PhoneTags.STANDING_PAUSED_ALSO))
    }
    standing.runtime.label?.let { BodyText(stringResource(R.string.phone_standing_runtime, it)) }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.phone_standing_restart_confirm_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.phone_standing_restart_confirm_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { confirmRestart = false; actions.onRestartRuntime() }, modifier = Modifier.testTag(PhoneTags.RESTART_CONFIRM)) {
                    Text(stringResource(R.string.phone_standing_restart), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The way out of a native pause when no task is stopped: a person taps "Resume chat use…", reads
 * what it does and confirms. Only that confirm asks the native side; nothing here fires on open,
 * reconnect or recomposition. A stopped task keeps its own continue entry instead, and an unchecked
 * uncertain action is never resumable from here.
 */
@Composable
private fun StandingPauseBlock(
    state: PhoneScreenState,
    actions: PhoneControlActions,
) {
    var confirming by remember { mutableStateOf(false) }
    val offered = canResumeStanding(state)
    val block = standingResumeBlock(state)
    if (block == StandingResumeBlock.StoppedTask) {
        BodyText(stringResource(R.string.phone_standing_resume_use_task))
        return
    }
    // While a resume runs, the top progress bar says so and the button stays disabled (pending).
    PhonePrimaryButton(
        text = stringResource(R.string.phone_standing_resume),
        onClick = { confirming = true },
        enabled = offered,
        icon = Icons.Rounded.PlayArrow,
        modifier = Modifier.testTag(PhoneTags.RESUME_STANDING),
    )
    if (!offered && block != null) BodyText(stringResource(block.message()))

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.phone_standing_resume_confirm_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.phone_standing_resume_confirm_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        confirmResumeStanding(state, actions.onResumeStanding)
                    },
                    enabled = offered,
                    modifier = Modifier.testTag(PhoneTags.RESUME_STANDING_CONFIRM),
                ) { Text(stringResource(R.string.phone_standing_resume_confirm), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun StandingResumeBlock.message(): Int =
    when (this) {
        StandingResumeBlock.StoppedTask -> R.string.phone_standing_resume_use_task
        StandingResumeBlock.TaskRunning -> R.string.phone_standing_resume_blocked_task
        StandingResumeBlock.OutcomeUnchecked -> R.string.phone_standing_resume_blocked_unchecked
        StandingResumeBlock.NotReady -> R.string.phone_continue_blocked_not_ready
        StandingResumeBlock.StopChannel -> R.string.phone_stop_gate_title
        StandingResumeBlock.Busy -> R.string.phone_block_busy
    }

@Composable
private fun ScopeSummary(
    standing: PhoneStanding,
    now: Long,
) {
    HorizontalDivider()
    Text(stringResource(R.string.phone_standing_scope), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    standing.apps.forEach { app ->
        Column {
            Text(app.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = ChamberTextStyles.codeSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    BodyText(stringResource(R.string.phone_grant_confirm_actions, actionList(standing.actions)))
    // A permanent window or per-task limit shows a fixed word, never a huge remaining number.
    val windowLine =
        when {
            standingWindowPermanent(standing.status) -> stringResource(R.string.phone_standing_window_line_permanent)
            else -> standingMinutesLeft(standing.status, now)?.let { stringResource(R.string.phone_standing_window_line_left, it) }
        }
    if (windowLine != null) {
        val perTaskLine =
            when (perTaskDisplay(perTaskPermanent(standing), standingWindowPermanent(standing.status))) {
                PerTaskDisplay.NoLimit -> stringResource(R.string.phone_standing_per_task_line_permanent)
                PerTaskDisplay.UntilScopeEnds -> stringResource(R.string.phone_standing_per_task_line_scope)
                PerTaskDisplay.Minutes -> stringResource(R.string.phone_standing_per_task_line, standing.perTaskMinutes)
            }
        BodyText(windowLine + " · " + perTaskLine, modifier = Modifier.testTag(PhoneTags.STANDING_TIME))
    }
    if (standingWindowPermanent(standing.status) || perTaskPermanent(standing)) {
        BodyText(stringResource(R.string.phone_standing_permanent_note))
    }
    BodyText(stringResource(R.string.phone_standing_limits_note))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StandingEditor(
    state: PhoneScreenState,
    standing: PhoneStanding,
    actions: PhoneControlActions,
    onDone: () -> Unit,
    cancellable: Boolean,
) {
    var chosen by rememberSaveable { mutableStateOf(standing.apps.map { it.packageName }.toSet()) }
    var scope by rememberSaveable { mutableStateOf((standing.actions.ifEmpty { DEFAULT_ACTIONS }).map { it.name }.toSet()) }
    var hours by rememberSaveable { mutableIntStateOf(initialWindowChoice(standing.status)) }
    // "Permanent" is a choice, never the default: new scopes start at the bounded defaults.
    var perTask by rememberSaveable { mutableIntStateOf(initialPerTaskChoice(standing)) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    val apps = sanitizeStandingApps(state.targets.filter { it.packageName in chosen }, state.hostPackage)
    val chosenActions = scope.mapNotNull { n -> PhoneActionScope.entries.firstOrNull { it.name == n } }.toSet()
    val block = standingBlock(state, state.targets.filter { it.packageName in chosen }, chosenActions)

    HorizontalDivider()
    StepHeading(1, stringResource(R.string.phone_standing_step_apps), done = apps.isNotEmpty())
    BodyText(stringResource(R.string.phone_standing_apps_body, MAX_STANDING_APPS))
    androidx.compose.material3.OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(80) },
        label = { Text(stringResource(R.string.phone_target_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val listing = targetListing(state.targets, state.hostPackage, query, null)
    // Chosen apps stay listed even when they fall outside the compact slice.
    val rows = (state.targets.filter { it.packageName in chosen }.map { it to targetBlock(it, state.hostPackage) } + listing.rows).distinctBy { it.first.packageName }
    rows.forEach { (target, blockReason) ->
        val checked = target.packageName in chosen
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .toggleable(
                        value = checked,
                        enabled = blockReason == null && (checked || chosen.size < MAX_STANDING_APPS),
                        role = Role.Checkbox,
                        onValueChange = { on -> chosen = if (on) chosen + target.packageName else chosen - target.packageName },
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked && blockReason == null, onCheckedChange = null, enabled = blockReason == null)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(target.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(target.packageName, style = ChamberTextStyles.codeSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (blockReason != null) {
                    Text(stringResource(blockReason.message()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (listing.moreAllowed > 0) BodyText(stringResource(R.string.phone_targets_more, listing.moreAllowed))
    if (listing.hiddenBlocked > 0) BodyText(stringResource(R.string.phone_standing_blocked_hidden, listing.hiddenBlocked))

    HorizontalDivider()
    StepHeading(2, stringResource(R.string.phone_grant_step_limits), done = chosenActions.isNotEmpty())
    PhoneActionScope.entries.forEach { action ->
        val checked = action.name in scope
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { on -> scope = if (on) scope + action.name else scope - action.name }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(stringResource(action.label()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (action == PhoneActionScope.Input) {
                    Text(stringResource(R.string.phone_action_input_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Text(stringResource(R.string.phone_standing_window), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        STANDING_WINDOW_CHOICES.forEach { h ->
            FilterChip(
                selected = hours == h,
                onClick = { hours = h },
                label = { Text(if (h == PERMANENT_CHOICE) stringResource(R.string.phone_standing_permanent) else stringResource(R.string.phone_standing_hours, h)) },
                modifier = Modifier.heightIn(min = 40.dp).testTag(PhoneTags.STANDING_WINDOW_CHOICE + ":" + h),
            )
        }
    }
    Text(stringResource(R.string.phone_standing_per_task), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        STANDING_PER_TASK_CHOICES.forEach { m ->
            FilterChip(
                selected = perTask == m,
                onClick = { perTask = m },
                label = { Text(if (m == PERMANENT_CHOICE) stringResource(R.string.phone_standing_permanent) else stringResource(R.string.phone_duration_minutes, m)) },
                modifier = Modifier.heightIn(min = 40.dp).testTag(PhoneTags.STANDING_PER_TASK_CHOICE + ":" + m),
            )
        }
    }
    if (hours == PERMANENT_CHOICE || perTask == PERMANENT_CHOICE) {
        BodyText(stringResource(R.string.phone_standing_permanent_note))
    }
    BodyText(stringResource(R.string.phone_standing_trust))
    StopChannelGate(state.stopChannel, actions)
    if (showErrors && block != null && block != StandingBlock.StopChannel) {
        Text(stringResource(block.message()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    PhoneButtonColumn {
        PhonePrimaryButton(
            text = stringResource(R.string.phone_standing_review),
            enabled = state.stopChannel == PhoneStopChannel.Verified && state.pending == null,
            onClick = { if (block == null) confirming = true else showErrors = true },
            modifier = Modifier.testTag(PhoneTags.SAVE_STANDING),
        )
        if (cancellable) PhoneSecondaryButton(text = stringResource(R.string.cancel), onClick = onDone)
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.phone_standing_confirm_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(apps.joinToString(stringResource(R.string.phone_list_separator)) { it.label }, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.phone_grant_confirm_actions, actionList(chosenActions)), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (hours == PERMANENT_CHOICE) stringResource(R.string.phone_standing_confirm_window_permanent) else stringResource(R.string.phone_standing_confirm_window_hours, hours),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        when (perTaskDisplay(perTask == PERMANENT_CHOICE, hours == PERMANENT_CHOICE)) {
                            PerTaskDisplay.NoLimit -> stringResource(R.string.phone_standing_confirm_per_task_permanent)
                            PerTaskDisplay.UntilScopeEnds -> stringResource(R.string.phone_standing_confirm_per_task_scope)
                            PerTaskDisplay.Minutes -> stringResource(R.string.phone_standing_confirm_per_task_minutes, perTask)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (hours == PERMANENT_CHOICE || perTask == PERMANENT_CHOICE) {
                        Text(stringResource(R.string.phone_standing_permanent_note), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(PhoneTags.STANDING_PERMANENT_NOTE))
                    }
                    Text(stringResource(R.string.phone_standing_confirm_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.phone_grant_confirm_screen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        if (standingBlock(state, apps, chosenActions) == null) {
                            actions.onSaveStanding(standingRequest(apps, chosenActions, hours, perTask))
                            onDone()
                        }
                    },
                    modifier = Modifier.testTag(PhoneTags.SAVE_STANDING_CONFIRM),
                ) { Text(stringResource(R.string.phone_grant_allow), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun TargetBlock.message(): Int =
    when (this) {
        TargetBlock.Host -> R.string.phone_target_block_host
        TargetBlock.System -> R.string.phone_target_block_system
        TargetBlock.Invalid -> R.string.phone_target_block_invalid
        TargetBlock.Sensitive -> R.string.phone_target_block_sensitive
    }

private fun StandingBlock.message(): Int =
    when (this) {
        StandingBlock.NotConnected -> R.string.phone_block_not_connected
        StandingBlock.StopChannel -> R.string.phone_stop_gate_title
        StandingBlock.NoApps -> R.string.phone_block_no_target
        StandingBlock.TooManyApps -> R.string.phone_standing_too_many
        StandingBlock.AppNotAllowed -> R.string.phone_block_target_not_allowed
        StandingBlock.NoActions -> R.string.phone_block_no_actions
        StandingBlock.Busy -> R.string.phone_block_busy
    }

/**
 * After take over / need you / unknown inside a standing scope: why it is waiting, and a native
 * confirmation to continue beside the chat. Never resumes by itself and never re-sends anything.
 */
@Composable
internal fun ContinueInChatBlock(
    state: PhoneScreenState,
    actions: PhoneControlActions,
) {
    val standing = state.standing ?: return
    val halted = state.control as? PhoneControl.Halted ?: return
    val continuation = standing.continuation
    var confirming by remember { mutableStateOf(false) }
    HorizontalDivider()
    if (continuation.needUser) {
        PhoneNotice(
            NoticeTone.Info,
            Icons.Rounded.Info,
            stringResource(R.string.phone_continue_need_user_title),
            body = stringResource(R.string.phone_continue_need_user_body),
        ) {
            continuation.agentNote?.let { note ->
                Text(stringResource(R.string.phone_continue_agent_note, note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    ContinueDeliveryNotice(continuation.delivery, running = false)
    val offered = canContinueInChat(state)
    PhonePrimaryButton(
        text = stringResource(R.string.phone_continue_in_chat),
        onClick = { confirming = true },
        enabled = offered,
        icon = Icons.AutoMirrored.Rounded.Chat,
        modifier = Modifier.testTag(PhoneTags.CONTINUE_IN_CHAT),
    )
    if (!offered) BodyText(stringResource(continueBlockedReason(state)))

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.phone_continue_confirm_title, halted.lease.target.label), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.phone_continue_confirm_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    if (canContinueInChat(state)) actions.onContinueInChat()
                }) { Text(stringResource(R.string.phone_resume), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Read-only state of the "tell the chat to continue" request. No buttons: no retry, no grant.
 * [running]: the lease is back (the task runs again), so a failure says the agent may act while the
 * chat wasn't told, instead of the stopped-card wording.
 */
@Composable
internal fun ContinueDeliveryNotice(
    delivery: PhoneContinueDelivery,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val mod = modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(PhoneTags.CONTINUE_DELIVERY)
    when (delivery) {
        PhoneContinueDelivery.Delivered ->
            PhoneNotice(NoticeTone.Info, Icons.Rounded.CheckCircle, stringResource(R.string.phone_continue_delivered_title), body = stringResource(R.string.phone_continue_delivered_body), modifier = mod)
        PhoneContinueDelivery.Failed ->
            PhoneNotice(
                NoticeTone.Error,
                Icons.Rounded.ErrorOutline,
                stringResource(R.string.phone_continue_failed_title),
                body = stringResource(if (running) R.string.phone_continue_failed_running_body else R.string.phone_continue_failed_body),
                modifier = mod,
            )
        // Still in flight: never shown as delivered.
        PhoneContinueDelivery.Sending -> BodyText(stringResource(R.string.phone_continue_sending), modifier = mod)
        PhoneContinueDelivery.Idle -> Unit
    }
}

/** Why continuing beside the chat isn't offered right now, in the order the person should fix it. */
internal fun continueBlockedReason(state: PhoneScreenState): Int {
    val halted = state.control as? PhoneControl.Halted
    return when {
        halted == null -> R.string.phone_continue_blocked_generic
        !halted.confirmed -> R.string.phone_continue_blocked_settling
        halted.lastAction == PhoneLastAction.MayStillApply -> R.string.phone_continue_blocked_check
        standingBaseReadiness(state) != StandingReadiness.Ready -> R.string.phone_continue_blocked_not_ready
        state.standing?.apps?.none { it.packageName == halted.lease.target.packageName } == true -> R.string.phone_continue_blocked_scope
        else -> R.string.phone_continue_blocked_generic
    }
}
