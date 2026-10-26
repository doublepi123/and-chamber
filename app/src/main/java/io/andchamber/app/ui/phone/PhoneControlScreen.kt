package io.andchamber.app.ui.phone

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.andchamber.app.R
import io.andchamber.app.ui.components.ChamberCard
import io.andchamber.app.ui.components.DetailRow
import io.andchamber.app.ui.components.SectionHeader
import io.andchamber.app.ui.components.StatusDot
import io.andchamber.app.ui.components.StatusTone
import io.andchamber.app.ui.theme.ChamberTextStyles
import kotlinx.coroutines.delay

/**
 * Native phone-control screen (experimental). One column: the header, a three-line status strip
 * (device, control, task), the one step that needs attention now, the stop controls whenever a task
 * exists, and collapsed details.
 *
 * Pure presentation: [state] comes from the integration layer and every button is a request in
 * [actions]. [clock] is the wall clock used for the lease countdown (tests pass a fixed one).
 */
@Composable
internal fun PhoneControlScreen(
    state: PhoneScreenState,
    actions: PhoneControlActions,
    modifier: Modifier = Modifier,
    clock: () -> Long = System::currentTimeMillis,
    /** Overlay settings; null (the default) hides the card. Supplied by the integration. */
    overlaySettings: io.andchamber.app.ui.phone.overlay.PhoneOverlaySettingsState? = null,
    overlaySettingsActions: io.andchamber.app.ui.phone.overlay.PhoneOverlaySettingsActions =
        io.andchamber.app.ui.phone.overlay.PhoneOverlaySettingsActions(),
) {
    val step = phoneStep(state)
    val now = rememberNow(state.control, clock)
    // The grant form's draft is dropped only once it served its purpose or can't be used any more.
    val leased = state.control is PhoneControl.Agent
    LaunchedEffect(leased, state.enabled) {
        if (leased || !state.enabled) PhoneGrantDraftHolder.clear()
    }

    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // A new page starts at its top with no stale field focused (and the keyboard down). Only this
    // screen's own window is touched. Saved, so a rotation on the same page keeps its position.
    val anchor = phoneScrollAnchor(state)
    var shownAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(anchor) {
        if (shownAnchor != null && shownAnchor != anchor) {
            focus.clearFocus(force = true)
            keyboard?.hide()
            scroll.scrollTo(0)
        }
        shownAnchor = anchor
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        // The status bar area and the top bar stay outside the scrolling part, so content never
        // slides under the system icons and Back is always reachable.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                PhoneTopBar(onBack = actions.onBack)
                PendingBar(state.pending)
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Bottom of safeDrawing is the navigation bar or, while it is open, the
                        // keyboard. Applied outside the scroll, so the viewport itself ends above
                        // the keyboard and a focused field can be brought into view. Top and sides
                        // were consumed by the root, so nothing is padded twice; the top bar above
                        // never moves.
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .verticalScroll(scroll)
                        .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                if (step != PhoneStep.Unavailable && step != PhoneStep.Off) {
                    StatusStrip(state = state, now = now, modifier = Modifier.padding(top = 8.dp))
                    BusyNotice(state.busy, modifier = Modifier.padding(top = 8.dp))
                }

                // Stop controls sit above the step, so they never scroll out of reach behind a form.
                if (step == PhoneStep.Running || step == PhoneStep.Halted) {
                    StopControls(state = state, actions = actions, modifier = Modifier.padding(top = 12.dp))
                }

                // Persistent until the integration reports the runtime ready; the way out is Home.
                RuntimeGateNotice(
                    gate = phoneRuntimeGate(state).takeIf { showsRuntimeGate(state) },
                    onHome = actions.onBack,
                    modifier = Modifier.padding(top = 12.dp),
                )

                // A failed request explained right above the card it came from, in fixed words.
                FeedbackNotice(phoneFeedback(state), modifier = Modifier.padding(top = 12.dp))

                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                    label = "phoneStep",
                    modifier = Modifier.padding(top = 12.dp),
                ) { shown ->
                    when (shown) {
                        PhoneStep.Unavailable -> UnavailableCard()
                        PhoneStep.Off -> EnableCard(state = state, onEnable = actions.onEnable)
                        PhoneStep.Connect -> ConnectCard(state = state, actions = actions)
                        PhoneStep.Grant -> GrantCard(state = state, actions = actions)
                        PhoneStep.Running -> RunningCard(state = state, now = now, actions = actions)
                        PhoneStep.Halted ->
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                HaltedCard(state = state, actions = actions)
                                // Link down: the same connect form, below the halt facts. It only
                                // restores the connection; continuing is still confirmed above.
                                if (showsReconnect(state)) ConnectCard(state = state, actions = actions)
                            }
                        PhoneStep.Result -> ResultCard(state = state, actions = actions)
                    }
                }

                // Below the current step, outside it: setting up the overlay is never the step that
                // needs attention, and it is not offered while the switch is off.
                if (overlaySettings != null && showsOverlaySettings(step)) {
                    io.andchamber.app.ui.phone.overlay.PhoneOverlaySettingsCard(
                        state = overlaySettings,
                        actions = overlaySettingsActions,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }

                if (step != PhoneStep.Unavailable && step != PhoneStep.Off) {
                    DetailsSection(state = state, actions = actions, modifier = Modifier.padding(top = 20.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
            }
        }
    }
}

/** Wall clock, ticking every second in the last minute of a lease and every 15 s otherwise. */
@Composable
private fun rememberNow(
    control: PhoneControl,
    clock: () -> Long,
): Long {
    val expires = (control as? PhoneControl.Agent)?.lease?.expiresAtMs
    val now by produceState(clock(), expires) {
        value = clock()
        while (expires != null) {
            val left = expires - value
            delay(if (left <= 61_000) 1_000 else 15_000)
            value = clock()
            if (value >= expires) break
        }
    }
    return now
}

@Composable
private fun PhoneTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(PhoneTags.BACK)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Text(
            text = stringResource(R.string.phone_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = stringResource(R.string.phone_experimental),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun PendingBar(pending: PhonePending?) {
    AnimatedVisibility(visible = pending != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            pending?.let {
                Text(
                    text = stringResource(it.label()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * A refused-as-busy request. Passive on purpose: no button, and the copy says not to press again,
 * since the running operation must settle first and nothing is queued.
 */
@Composable
private fun BusyNotice(
    busy: PhoneBusy?,
    modifier: Modifier = Modifier,
) {
    val shown = io.andchamber.app.ui.rememberLastNonNull(busy)
    AnimatedVisibility(visible = busy != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(), modifier = modifier) {
        val (title, body) =
            when (shown) {
                PhoneBusy.AgentActionRefused -> R.string.phone_busy_agent_title to R.string.phone_busy_agent_body
                PhoneBusy.RequestIgnored, null -> R.string.phone_busy_request_title to R.string.phone_busy_request_body
            }
        PhoneNotice(
            tone = NoticeTone.Info,
            icon = Icons.Rounded.HourglassTop,
            title = stringResource(title),
            body = stringResource(body),
            modifier = Modifier.testTag(PhoneTags.BUSY),
        )
    }
}

/**
 * The local OpenChamber isn't ready for phone control. Stays until the integration clears the
 * token. The only action leads Home, where the person starts or restarts it; nothing here starts,
 * stops or retries anything by itself.
 */
@Composable
private fun RuntimeGateNotice(
    gate: PhoneRuntimeGate?,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = io.andchamber.app.ui.rememberLastNonNull(gate)
    AnimatedVisibility(visible = gate != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(), modifier = modifier) {
        val (title, body) =
            when (shown) {
                PhoneRuntimeGate.RestartRequired, null -> R.string.phone_runtime_restart_title to R.string.phone_runtime_restart_body
                PhoneRuntimeGate.NotRunning -> R.string.phone_runtime_not_running_title to R.string.phone_runtime_not_running_body
            }
        PhoneNotice(
            tone = NoticeTone.Warning,
            icon = Icons.Rounded.PowerSettingsNew,
            title = stringResource(title),
            body = stringResource(body),
            modifier = Modifier.testTag(PhoneTags.RUNTIME_GATE),
        ) {
            TextButton(onClick = onHome, modifier = Modifier.testTag(PhoneTags.RUNTIME_GATE_HOME)) {
                Text(stringResource(R.string.phone_runtime_go_home))
            }
        }
    }
}

/**
 * Why the last request didn't go through. No button: the person decides what to do next, and the
 * raw technical token stays in Details.
 */
@Composable
private fun FeedbackNotice(
    feedback: PhoneFeedback?,
    modifier: Modifier = Modifier,
) {
    val shown = io.andchamber.app.ui.rememberLastNonNull(feedback)
    AnimatedVisibility(visible = feedback != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(), modifier = modifier) {
        val (title, body) =
            when (shown) {
                PhoneFeedback.ToolNotReady -> R.string.phone_feedback_tool_title to R.string.phone_feedback_tool_body
                PhoneFeedback.ScreenLocked -> R.string.phone_feedback_locked_title to R.string.phone_feedback_locked_body
                PhoneFeedback.TargetUnavailable -> R.string.phone_feedback_target_title to R.string.phone_feedback_target_body
                PhoneFeedback.GrantNotStarted, null -> R.string.phone_feedback_grant_title to R.string.phone_feedback_grant_body
                PhoneFeedback.RequestNotDone -> R.string.phone_feedback_request_title to R.string.phone_feedback_request_body
            }
        PhoneNotice(
            tone = NoticeTone.Error,
            icon = Icons.Rounded.ErrorOutline,
            title = stringResource(title),
            body = stringResource(body),
            modifier = Modifier.testTag(PhoneTags.FEEDBACK),
        )
    }
}

/** Three separate facts: is the device linked, who is in control, and what task exists. */
@Composable
private fun StatusStrip(
    state: PhoneScreenState,
    now: Long,
    modifier: Modifier = Modifier,
) {
    ChamberCard(modifier = modifier.testTag(PhoneTags.STATUS)) {
        Column(modifier = Modifier.padding(horizontal = PhoneCardPadding, vertical = 10.dp)) {
            val (deviceTone, deviceText) =
                when (state.device.link) {
                    PhoneLink.Disconnected -> StatusTone.Idle to stringResource(R.string.phone_device_disconnected)
                    PhoneLink.Connecting -> StatusTone.Busy to stringResource(R.string.phone_device_connecting)
                    PhoneLink.Connected -> StatusTone.Live to stringResource(R.string.phone_device_connected)
                    PhoneLink.Lost -> StatusTone.Error to stringResource(R.string.phone_device_lost)
                }
            StatusLine(stringResource(R.string.phone_status_device), deviceText, deviceTone)

            val (controlTone, controlText) =
                when (val control = state.control) {
                    PhoneControl.None -> StatusTone.Idle to stringResource(R.string.phone_control_none)
                    is PhoneControl.Agent ->
                        StatusTone.Busy to
                            stringResource(
                                R.string.phone_control_agent,
                                control.lease.target.label,
                                timeLeftText(control.lease.expiresAtMs, now),
                            )
                    is PhoneControl.Halted ->
                        if (control.confirmed) {
                            StatusTone.Idle to stringResource(R.string.phone_control_paused)
                        } else {
                            StatusTone.Busy to stringResource(R.string.phone_control_pausing)
                        }
                }
            StatusLine(stringResource(R.string.phone_status_control), controlText, controlTone)

            val (taskTone, taskText) =
                when (val task = state.task) {
                    PhoneTask.None -> StatusTone.Idle to stringResource(R.string.phone_task_none)
                    is PhoneTask.Active -> StatusTone.Live to task.summary
                    is PhoneTask.Ended -> StatusTone.Idle to stringResource(R.string.phone_task_ended)
                }
            StatusLine(stringResource(R.string.phone_status_task), taskText, taskTone)
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    tone: StatusTone,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 64.dp, max = 120.dp),
        )
        Spacer(Modifier.width(8.dp))
        StatusDot(tone = tone, size = 7.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** "4 min" or, in the last minute, "42 s". */
@Composable
internal fun timeLeftText(
    expiresAtMs: Long,
    now: Long,
): String {
    val seconds = secondsLeftInLastMinute(expiresAtMs, now)
    return if (seconds != null) {
        stringResource(R.string.phone_seconds_left, seconds)
    } else {
        stringResource(R.string.phone_minutes_left, minutesLeft(expiresAtMs, now))
    }
}

/**
 * Pause / I'll take over / End task. Shown while the agent holds or held a lease. Pause is the
 * first and largest; it is replaced by a passive label once the agent is already stopped.
 */
@Composable
private fun StopControls(
    state: PhoneScreenState,
    actions: PhoneControlActions,
    modifier: Modifier = Modifier,
) {
    val agent = state.control is PhoneControl.Agent
    val settling = (state.control as? PhoneControl.Halted)?.confirmed == false
    val busy = state.pending == PhonePending.Pausing || state.pending == PhonePending.Finishing
    PhoneButtonColumn(modifier = modifier.testTag(PhoneTags.STOP_CONTROLS)) {
        if (settling) {
            // Pause was already accepted; a second press would change nothing. Say so instead of
            // offering the button again.
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(PhoneTags.PAUSING),
            ) {
                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(tone = StatusTone.Busy, size = 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.phone_pausing_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (agent) {
            PhonePrimaryButton(
                text = stringResource(R.string.phone_pause),
                onClick = actions.onPause,
                enabled = !busy,
                icon = Icons.Rounded.Pause,
                modifier = Modifier.testTag(PhoneTags.PAUSE),
            )
        }
        PhoneSecondaryButton(
            text = stringResource(R.string.phone_take_over),
            onClick = actions.onTakeOver,
            enabled = !busy,
            icon = Icons.Rounded.PanTool,
            modifier = Modifier.testTag(PhoneTags.TAKE_OVER),
        )
        PhoneSecondaryButton(
            text = stringResource(R.string.phone_finish),
            onClick = actions.onFinish,
            enabled = state.pending != PhonePending.Finishing,
            icon = Icons.Rounded.Stop,
            modifier = Modifier.testTag(PhoneTags.FINISH),
        )
    }
}

@Composable
private fun UnavailableCard() {
    ChamberCard {
        Column(modifier = Modifier.padding(PhoneCardPadding)) {
            Text(
                stringResource(R.string.phone_unavailable_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            BodyText(stringResource(R.string.phone_unavailable_body))
        }
    }
}

/** Collapsed by default: the switch to turn the experiment off, disconnect, and raw diagnostics. */
@Composable
private fun DetailsSection(
    state: PhoneScreenState,
    actions: PhoneControlActions,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { open = !open }
                    .testTag(PhoneTags.DETAILS_TOGGLE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(text = stringResource(R.string.phone_details), modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            ChamberCard {
                Column(modifier = Modifier.padding(PhoneCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .toggleable(value = state.enabled, role = Role.Switch, onValueChange = { if (!it) actions.onEnable(false) })
                                .testTag(PhoneTags.DISABLE_SWITCH),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.phone_enable_switch),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                stringResource(R.string.phone_disable_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = state.enabled, onCheckedChange = null)
                    }
                    if (state.device.link == PhoneLink.Connected) {
                        PhoneSecondaryButton(
                            text = stringResource(R.string.phone_disconnect),
                            onClick = actions.onDisconnect,
                            enabled = state.pending == null,
                        )
                    }
                    if (state.task is PhoneTask.Active) {
                        val observable = canObserve(state)
                        PhoneSecondaryButton(
                            text = stringResource(R.string.phone_observe),
                            onClick = { if (canObserve(state)) actions.onObserve() },
                            enabled = observable,
                            modifier = Modifier.testTag(PhoneTags.OBSERVE),
                        )
                        if (!observable) {
                            Text(
                                stringResource(R.string.phone_observe_needs_lease),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                    val unknown = stringResource(R.string.unknown_value)
                    val d = state.diagnostics
                    DetailRow(stringResource(R.string.phone_detail_runtime), d.runtime ?: unknown)
                    DetailRow(stringResource(R.string.phone_detail_connection), d.connection ?: unknown)
                    DetailRow(stringResource(R.string.phone_detail_observed_nodes), d.observedNodes?.toString() ?: unknown)
                    d.lastError?.let {
                        Text(
                            stringResource(R.string.phone_detail_last_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Text(
                                it,
                                style = ChamberTextStyles.codeSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Stable test tags; the integration lane and instrumentation tests may rely on them. */
internal object PhoneTags {
    const val BACK = "phone.back"
    const val STATUS = "phone.status"
    const val STOP_CONTROLS = "phone.stop"
    const val PAUSE = "phone.pause"
    const val TAKE_OVER = "phone.takeOver"
    const val FINISH = "phone.finish"
    const val DETAILS_TOGGLE = "phone.details"
    const val DISABLE_SWITCH = "phone.disable"
    const val RISK_ACK = "phone.riskAck"
    const val ENABLE = "phone.enable"
    const val PAIR_NOTIFICATION = "phone.pairNotification"
    const val PAIR_CODE = "phone.pairCode"
    const val PAIR_PORT = "phone.pairPort"
    const val PAIR_SUBMIT = "phone.pairSubmit"
    const val HOST = "phone.host"
    const val CONNECT_PORT = "phone.connectPort"
    const val CONNECT = "phone.connect"
    const val TARGET = "phone.target"
    const val SUMMARY = "phone.summary"
    const val GRANT = "phone.grant"
    const val GRANT_CONFIRM = "phone.grantConfirm"
    const val OPEN_TARGET = "phone.openTarget"
    const val ACK_CHECKED = "phone.ackChecked"
    const val RESUME = "phone.resume"
    const val RESULT = "phone.result"
    const val BUSY = "phone.busy"
    const val ENTRY = "phone.entry"
    const val OBSERVE = "phone.observe"
    const val FEEDBACK = "phone.feedback"
    const val RUNTIME_GATE = "phone.runtimeGate"
    const val RUNTIME_GATE_HOME = "phone.runtimeGateHome"
    const val PAUSING = "phone.pausing"
}
