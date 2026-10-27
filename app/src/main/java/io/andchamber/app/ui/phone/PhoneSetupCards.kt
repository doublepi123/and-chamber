package io.andchamber.app.ui.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.andchamber.app.R
import io.andchamber.app.ui.components.ChamberCard
import io.andchamber.app.ui.theme.ChamberTextStyles

/** The switch is off: what turning it on means, and an explicit acknowledgement. */
@Composable
internal fun EnableCard(
    state: PhoneScreenState,
    onEnable: (Boolean) -> Unit,
) {
    // Not saveable: after process death the person reads the risks again.
    var acknowledged by remember { mutableStateOf(false) }
    ChamberCard {
        Column(modifier = Modifier.padding(PhoneCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.phone_enable_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BodyText(stringResource(R.string.phone_enable_intro))
            PhoneNotice(
                tone = NoticeTone.Warning,
                icon = Icons.Rounded.WarningAmber,
                title = stringResource(R.string.phone_risk_title),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RiskLine(stringResource(R.string.phone_risk_runtime))
                    RiskLine(stringResource(R.string.phone_risk_adb))
                    RiskLine(stringResource(R.string.phone_risk_model))
                    RiskLine(stringResource(R.string.phone_risk_confirm))
                }
            }
            BodyText(stringResource(R.string.phone_enable_limits))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = acknowledged, role = Role.Checkbox, onValueChange = { acknowledged = it })
                        .testTag(PhoneTags.RISK_ACK)
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = acknowledged, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.phone_risk_ack),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            PhonePrimaryButton(
                text = stringResource(R.string.phone_enable),
                onClick = { onEnable(true) },
                enabled = acknowledged && state.pending == null,
                modifier = Modifier.testTag(PhoneTags.ENABLE),
            )
        }
    }
}

@Composable
private fun RiskLine(text: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * Wireless debugging setup on this phone: turn it on, pair once with a code typed into a
 * notification, then connect. On the normal path nobody types a port: the native side looks for
 * this phone's own pairing and connection services. Port and address fields stay folded away as a
 * manual fallback the person opens themselves.
 *
 * The pairing code is held only in plain [remember] state (never saved instance state), cleared
 * when it is submitted, when a pairing attempt fails, and whenever the app goes to the background.
 */
@Composable
internal fun ConnectCard(
    state: PhoneScreenState,
    actions: PhoneControlActions,
) {
    val device = state.device
    val busy = state.pending != null
    var host by rememberSaveable { mutableStateOf(device.hints.host) }
    var customHost by rememberSaveable { mutableStateOf(device.hints.host != PhoneUiLimits.DEFAULT_HOST) }
    var pairPort by rememberSaveable(device.hints.pairingPort) { mutableStateOf(device.hints.pairingPort?.toString().orEmpty()) }
    var connectPort by rememberSaveable(device.hints.connectPort) { mutableStateOf(device.hints.connectPort?.toString().orEmpty()) }
    var code by remember { mutableStateOf("") }
    var manualPair by rememberSaveable { mutableStateOf(false) }
    // Another address only makes sense through the manual section, so it starts open for one.
    var manualConnect by rememberSaveable { mutableStateOf(device.hints.host != PhoneUiLimits.DEFAULT_HOST) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var pairAgain by rememberSaveable { mutableStateOf(false) }
    val plan = connectPlan(state, pairAgain = pairAgain, manualPair = manualPair, manualConnect = manualConnect)

    // A pairing code is valid only while the system dialog is open: never keep it around.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { code = "" }
    LaunchedEffect(device.problem) { if (device.problem != null) code = "" }

    val effectiveHost = if (customHost) parseHost(host) else PhoneUiLimits.DEFAULT_HOST
    val notificationHost = pairingHost(manualConnect, customHost, host)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val connecting = device.link == PhoneLink.Connecting
    // One submit for the button and the keyboard's Go: same validation, same busy guard. It only
    // asks to connect; connecting never grants anything.
    val submitConnect: () -> Unit = submit@{
        when (val request = connectRequest(connectPort, customHost, host, busy || connecting)) {
            ConnectSubmit.Busy -> Unit
            ConnectSubmit.Invalid -> showErrors = true
            is ConnectSubmit.Ready -> {
                showErrors = false
                focus.clearFocus(force = true)
                keyboard?.hide()
                actions.onConnect(request.request)
            }
        }
    }
    val openManualConnect = { manualConnect = true }
    val autoConnect = {
        focus.clearFocus(force = true)
        keyboard?.hide()
        actions.onAutoConnect()
    }

    ChamberCard {
        Column(modifier = Modifier.padding(PhoneCardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.phone_connect_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            device.problem?.let { ProblemNotice(it, actions) }

            // 1. Wireless debugging
            StepHeading(1, stringResource(R.string.phone_step_wireless))
            BodyText(stringResource(R.string.phone_step_wireless_body))
            PhoneSecondaryButton(
                text = stringResource(R.string.phone_open_wireless_settings),
                onClick = actions.onOpenWirelessSettings,
                icon = Icons.Rounded.Settings,
            )

            HorizontalDivider()

            // 2. Pairing (first time)
            StepHeading(2, stringResource(R.string.phone_step_pair), done = device.paired == true)
            if (!plan.showsPairing) {
                BodyText(stringResource(R.string.phone_paired_before))
                TextButton(onClick = { pairAgain = true }, modifier = Modifier.testTag(PhoneTags.PAIR_AGAIN)) {
                    Text(stringResource(R.string.phone_pair_again))
                }
            } else {
                BodyText(stringResource(R.string.phone_pair_how))
                PairingNoticeBlock(
                    notice = device.pairingNotice,
                    search = plan.pairingSearch,
                    enabled = !busy && notificationHost != null,
                    onBegin = { notificationHost?.let(actions.onBeginPairingNotification) },
                    onRetry = { notificationHost?.let { retryDiscovery(DiscoveryKind.Pairing, actions, it) } },
                    onManual = { manualPair = true },
                    onCancel = actions.onCancelPairingNotification,
                    onOpenNotificationSettings = actions.onOpenNotificationSettings,
                )
                TextButton(onClick = { manualPair = !manualPair }, modifier = Modifier.testTag(PhoneTags.PAIR_MANUAL_TOGGLE)) {
                    Text(stringResource(if (manualPair) R.string.phone_pair_manual_hide else R.string.phone_pair_manual_show))
                }
                AnimatedVisibility(visible = plan.showsManualPairing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BodyText(stringResource(R.string.phone_pair_manual_body))
                        PortField(
                            value = pairPort,
                            onValueChange = { pairPort = it },
                            label = stringResource(R.string.phone_pairing_port),
                            hint = stringResource(R.string.phone_pairing_port_hint),
                            error = showErrors && parsePort(pairPort) == null,
                            tag = PhoneTags.PAIR_PORT,
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = sanitizePairingCodeInput(it) },
                            label = { Text(stringResource(R.string.phone_pairing_code)) },
                            supportingText = { Text(stringResource(R.string.phone_pairing_code_hint)) },
                            isError = showErrors && parsePairingCode(code) == null,
                            singleLine = true,
                            textStyle = ChamberTextStyles.code.copy(color = MaterialTheme.colorScheme.onSurface),
                            // NumberPassword keeps keyboards from learning or suggesting the code.
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth().testTag(PhoneTags.PAIR_CODE),
                        )
                        PhoneSecondaryButton(
                            text = stringResource(R.string.phone_pair),
                            enabled = !busy,
                            onClick = {
                                val port = parsePort(pairPort)
                                val parsed = parsePairingCode(code)
                                val h = effectiveHost
                                if (port == null || parsed == null || h == null) {
                                    showErrors = true
                                } else {
                                    showErrors = false
                                    // Hand the code over and forget it here at once.
                                    code = ""
                                    actions.onPair(PhonePairRequest(h, port, parsed))
                                }
                            },
                            modifier = Modifier.testTag(PhoneTags.PAIR_SUBMIT),
                        )
                    }
                }
            }

            HorizontalDivider()

            // 3. Connect: found automatically on this phone; typing a port is the folded fallback.
            StepHeading(3, stringResource(R.string.phone_step_connect))
            if (plan.offersAutoConnect) {
                BodyText(stringResource(R.string.phone_connect_auto_body))
                AutoConnectStatusBlock(
                    status = plan.autoStatus,
                    onRetry = { retryDiscovery(DiscoveryKind.Connect, actions) },
                    retryEnabled = plan.canAutoConnect,
                    onManual = openManualConnect,
                )
                val autoText = stringResource(R.string.phone_connect_auto)
                if (plan.primary == ConnectPrimary.AutoConnect) {
                    PhonePrimaryButton(
                        text = autoText,
                        enabled = plan.canAutoConnect,
                        onClick = autoConnect,
                        modifier = Modifier.testTag(PhoneTags.AUTO_CONNECT),
                    )
                } else {
                    PhoneSecondaryButton(
                        text = autoText,
                        enabled = plan.canAutoConnect,
                        onClick = autoConnect,
                        modifier = Modifier.testTag(PhoneTags.AUTO_CONNECT),
                    )
                }
            } else {
                BodyText(stringResource(R.string.phone_connect_after_pair))
            }
            TextButton(onClick = { manualConnect = !manualConnect }, modifier = Modifier.testTag(PhoneTags.CONNECT_MANUAL_TOGGLE)) {
                Text(stringResource(if (manualConnect) R.string.phone_connect_manual_hide else R.string.phone_connect_manual_show))
            }
            AnimatedVisibility(visible = plan.showsManualConnect, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BodyText(stringResource(R.string.phone_connect_port_body))
                    PortField(
                        value = connectPort,
                        onValueChange = { connectPort = it },
                        label = stringResource(R.string.phone_connect_port),
                        hint = stringResource(R.string.phone_connect_port_hint),
                        error = showErrors && parsePort(connectPort) == null,
                        tag = PhoneTags.CONNECT_PORT,
                        // Default path: the port is the last thing typed, so the keyboard can connect.
                        // With another address, Next moves on to the address field, which connects.
                        onGo = if (customHost) null else submitConnect,
                    )
                    HostChooser(
                        custom = customHost,
                        onCustomChange = { customHost = it; if (!it) host = PhoneUiLimits.DEFAULT_HOST },
                        host = host,
                        onHostChange = { host = it },
                        error = customHost && showErrors && parseHost(host) == null,
                        onGo = submitConnect,
                    )
                    PhoneSecondaryButton(
                        text = stringResource(R.string.phone_connect),
                        enabled = !busy && !connecting,
                        onClick = submitConnect,
                        modifier = Modifier.testTag(PhoneTags.CONNECT),
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingNoticeBlock(
    notice: PhonePairingNotice,
    search: PairingSearch?,
    enabled: Boolean,
    onBegin: () -> Unit,
    onRetry: () -> Unit,
    onManual: () -> Unit,
    onCancel: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(PhoneTags.PAIR_SEARCH)
    when (notice) {
        PhonePairingNotice.Idle -> {
            // The last search ended without a clear result: say why, above the button that retries.
            (search as? PairingSearch.Problem)?.let { DiscoveryProblemNotice(it.problem, pairing = true, modifier = live) }
            PhonePrimaryButton(
                text = stringResource(R.string.phone_pair_with_notification),
                onClick = onBegin,
                enabled = enabled,
                icon = Icons.Rounded.Notifications,
                modifier = Modifier.testTag(PhoneTags.PAIR_NOTIFICATION),
            )
        }
        PhonePairingNotice.Waiting ->
            when (search) {
                PairingSearch.Searching ->
                    PhoneNotice(
                        tone = NoticeTone.Info,
                        icon = Icons.Rounded.Search,
                        title = stringResource(R.string.phone_pair_searching_title),
                        body = stringResource(R.string.phone_pair_searching_body),
                        modifier = live,
                    ) {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    }
                is PairingSearch.Problem ->
                    DiscoveryProblemNotice(search.problem, pairing = true, modifier = live) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.testTag(PhoneTags.PAIR_SEARCH_RETRY)) {
                                Text(stringResource(R.string.phone_discovery_retry))
                            }
                            TextButton(onClick = onManual) { Text(stringResource(R.string.phone_discovery_manual)) }
                            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                PairingSearch.EnterCode, null ->
                    PhoneNotice(
                        tone = NoticeTone.Info,
                        icon = Icons.Rounded.Notifications,
                        title = stringResource(R.string.phone_pair_waiting_title),
                        body = stringResource(R.string.phone_pair_waiting_body),
                        modifier = live,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BodyText(stringResource(R.string.phone_pair_waiting_xiaomi))
                            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                        }
                    }
            }
        PhonePairingNotice.NotificationsBlocked ->
            PhoneNotice(
                tone = NoticeTone.Warning,
                icon = Icons.Rounded.NotificationsOff,
                title = stringResource(R.string.phone_pair_blocked_title),
                body = stringResource(R.string.phone_pair_blocked_body),
            ) {
                TextButton(onClick = onOpenNotificationSettings) { Text(stringResource(R.string.phone_open_notification_settings)) }
            }
    }
}

/** The automatic connect's real state: searching, connecting, or why it didn't work. */
@Composable
private fun AutoConnectStatusBlock(
    status: AutoConnectStatus?,
    onRetry: () -> Unit,
    retryEnabled: Boolean,
    onManual: () -> Unit,
) {
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(PhoneTags.AUTO_CONNECT_STATUS)
    val fallback: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRetry, enabled = retryEnabled, modifier = Modifier.testTag(PhoneTags.CONNECT_SEARCH_RETRY)) {
                Text(stringResource(R.string.phone_discovery_retry))
            }
            TextButton(onClick = onManual) { Text(stringResource(R.string.phone_discovery_manual)) }
        }
    }
    when (status) {
        null -> Unit
        AutoConnectStatus.Searching ->
            PhoneNotice(NoticeTone.Info, Icons.Rounded.Search, stringResource(R.string.phone_connect_searching), modifier = live)
        AutoConnectStatus.Connecting ->
            PhoneNotice(NoticeTone.Info, Icons.Rounded.HourglassTop, stringResource(R.string.phone_connect_auto_running), modifier = live)
        AutoConnectStatus.Failed ->
            PhoneNotice(
                NoticeTone.Warning,
                Icons.Rounded.WarningAmber,
                stringResource(R.string.phone_connect_auto_failed),
                body = stringResource(R.string.phone_connect_auto_failed_body),
                modifier = live,
                content = fallback,
            )
        is AutoConnectStatus.Problem -> DiscoveryProblemNotice(status.problem, pairing = false, modifier = live, content = fallback)
    }
}

@Composable
private fun DiscoveryProblemNotice(
    problem: DiscoveryProblem,
    pairing: Boolean,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    val (title, body) =
        when (problem) {
            DiscoveryProblem.NotFound ->
                R.string.phone_discovery_not_found to
                    if (pairing) R.string.phone_discovery_not_found_pair_body else R.string.phone_discovery_not_found_connect_body
            DiscoveryProblem.Unavailable -> R.string.phone_discovery_unavailable to R.string.phone_discovery_unavailable_body
            DiscoveryProblem.Ambiguous -> R.string.phone_discovery_ambiguous to R.string.phone_discovery_ambiguous_body
        }
    PhoneNotice(
        tone = NoticeTone.Warning,
        icon = Icons.Rounded.WarningAmber,
        title = stringResource(title),
        body = stringResource(body),
        modifier = modifier,
        content = content,
    )
}

/**
 * A port field. [onGo] null: the keyboard offers Next (move on to the following field). Non-null:
 * the keyboard offers Go and runs it, for the last field before the step's own button.
 */
@Composable
private fun PortField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    error: Boolean,
    tag: String,
    onGo: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        supportingText = { Text(if (error) stringResource(R.string.phone_port_invalid) else hint) },
        isError = error,
        singleLine = true,
        textStyle = ChamberTextStyles.code.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = if (onGo != null) ImeAction.Go else ImeAction.Next,
                autoCorrectEnabled = false,
            ),
        keyboardActions = KeyboardActions(onGo = { onGo?.invoke() }),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** This phone by default; another address is an explicit manual fallback. */
@Composable
private fun HostChooser(
    custom: Boolean,
    onCustomChange: (Boolean) -> Unit,
    host: String,
    onHostChange: (String) -> Unit,
    error: Boolean,
    onGo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = custom, role = Role.Checkbox, onValueChange = onCustomChange)
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = custom, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    stringResource(R.string.phone_host_custom),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.phone_host_default, PhoneUiLimits.DEFAULT_HOST),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = custom) {
            OutlinedTextField(
                value = host,
                onValueChange = { onHostChange(it.trim().take(PhoneUiLimits.MAX_HOST_CHARS)) },
                label = { Text(stringResource(R.string.phone_host)) },
                supportingText = { Text(stringResource(if (error) R.string.phone_host_invalid else R.string.phone_host_hint)) },
                isError = error,
                singleLine = true,
                textStyle = ChamberTextStyles.code.copy(color = MaterialTheme.colorScheme.onSurface),
                // The address comes after the port on this path, so it is the last field: Go connects.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
                modifier = Modifier.fillMaxWidth().testTag(PhoneTags.HOST),
            )
        }
    }
}

@Composable
private fun ProblemNotice(
    problem: PhoneDeviceProblem,
    actions: PhoneControlActions,
) {
    val (title, body) =
        when (problem) {
            PhoneDeviceProblem.WirelessDebuggingOff -> R.string.phone_problem_wireless_off to R.string.phone_problem_wireless_off_body
            PhoneDeviceProblem.NoWifi -> R.string.phone_problem_no_wifi to R.string.phone_problem_no_wifi_body
            PhoneDeviceProblem.PairingCodeRejected -> R.string.phone_problem_code_rejected to R.string.phone_problem_code_rejected_body
            PhoneDeviceProblem.PairingPortUnreachable -> R.string.phone_problem_pair_port to R.string.phone_problem_pair_port_body
            PhoneDeviceProblem.ConnectRefused -> R.string.phone_problem_connect_refused to R.string.phone_problem_connect_refused_body
            PhoneDeviceProblem.NotAuthorized -> R.string.phone_problem_not_authorized to R.string.phone_problem_not_authorized_body
            PhoneDeviceProblem.ConnectionLost -> R.string.phone_problem_lost to R.string.phone_problem_lost_body
            PhoneDeviceProblem.Other -> R.string.phone_problem_other to R.string.phone_problem_other_body
        }
    PhoneNotice(
        tone = NoticeTone.Error,
        icon = Icons.Rounded.ErrorOutline,
        title = stringResource(title),
        body = stringResource(body),
    ) {
        if (problem == PhoneDeviceProblem.WirelessDebuggingOff || problem == PhoneDeviceProblem.NoWifi) {
            TextButton(onClick = actions.onOpenWirelessSettings) { Text(stringResource(R.string.phone_open_wireless_settings)) }
        }
    }
}

/**
 * The hard gate before any grant: without a verified stop entry the person can reach from another
 * app, nothing may start. Says exactly what to do next.
 */
@Composable
internal fun StopChannelGate(
    channel: PhoneStopChannel,
    actions: PhoneControlActions,
    modifier: Modifier = Modifier,
) {
    if (channel == PhoneStopChannel.Verified) return
    val body =
        when (channel) {
            PhoneStopChannel.NotificationsDisabled -> R.string.phone_stop_gate_notifications
            PhoneStopChannel.ChannelDisabled -> R.string.phone_stop_gate_channel
            PhoneStopChannel.Unknown, PhoneStopChannel.Verified -> R.string.phone_stop_gate_unknown
        }
    PhoneNotice(
        tone = NoticeTone.Error,
        icon = Icons.Rounded.NotificationsOff,
        title = stringResource(R.string.phone_stop_gate_title),
        body = stringResource(body),
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (channel != PhoneStopChannel.Unknown) {
                TextButton(onClick = actions.onOpenNotificationSettings) { Text(stringResource(R.string.phone_open_notification_settings)) }
            }
            TextButton(onClick = actions.onRecheckStopChannel) { Text(stringResource(R.string.check_again)) }
        }
    }
}
