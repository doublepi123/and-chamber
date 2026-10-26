package io.andchamber.app.ui.phone.overlay

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.andchamber.app.R
import io.andchamber.app.ui.components.ChamberCard
import io.andchamber.app.ui.components.IconTile

/**
 * Native settings for the overlay. Off by default; one step at a time: permission (only on the
 * person's tap, never in a loop), a demo visibility test, then the mode. Declining keeps
 * notifications. Nothing here grants device control; the demo capsule controls nothing.
 */
@Composable
internal fun PhoneOverlaySettingsCard(
    state: PhoneOverlaySettingsState,
    actions: PhoneOverlaySettingsActions,
    modifier: Modifier = Modifier,
) {
    // Coming back from the system screen: read the permission again (no automatic re-launch).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (state.available) actions.onRecheck() }
    val step = overlaySetupStep(state)
    val header = overlayHeader(state)
    ChamberCard(modifier = modifier.testTag(OverlayTags.SETTINGS)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile { Icon(Icons.Rounded.Layers, contentDescription = null, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.phone_overlay_settings_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        stringResource(header.text()),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (header == OverlayHeader.OverlayBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(SETTINGS_HEADER_TAG),
                    )
                }
            }
            Body(stringResource(R.string.phone_overlay_settings_intro))
            Body(stringResource(R.string.phone_overlay_settings_stop_kinds))
            Body(stringResource(R.string.phone_overlay_settings_data))
            header.hint()?.let { Body(stringResource(it), if (header == OverlayHeader.OverlayBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            // The overlay didn't just vanish: say it moved to the notification, and why if known.
            // The reason shows whenever native gave one, also when the notification itself wasn't
            // available (lastSurface null). The "moved to the notification" line only when native said
            // so; it means the recovery entry was offered, not that the person saw it.
            if (lastSurfaceReport(state) != null) {
                Body(stringResource(R.string.phone_overlay_settings_moved_to_notification), MaterialTheme.colorScheme.error)
            }
            lastStopReasonShown(state)?.let {
                Body(stringResource(R.string.phone_overlay_stop_reason, stringResource(stopReasonText(it))))
            }

            when (step) {
                OverlaySetupStep.Unavailable -> Body(stringResource(R.string.phone_overlay_settings_unavailable))
                OverlaySetupStep.Permission -> {
                    Body(stringResource(R.string.phone_overlay_settings_permission))
                    if (state.permission == OverlayPermission.NotGranted) {
                        Body(stringResource(R.string.phone_overlay_settings_not_granted), MaterialTheme.colorScheme.error)
                    }
                    Primary(R.string.phone_overlay_settings_open_system, enabled = !state.pending, onClick = actions.onOpenSystemSettings)
                    Secondary(R.string.phone_overlay_settings_recheck, enabled = !state.pending, onClick = actions.onRecheck)
                }
                OverlaySetupStep.Test -> {
                    Body(stringResource(R.string.phone_overlay_settings_test_body))
                    when (state.test) {
                        OverlayTest.Showing -> Body(stringResource(R.string.phone_overlay_settings_test_showing))
                        OverlayTest.NotSeen, OverlayTest.TimedOut -> Body(stringResource(R.string.phone_overlay_settings_test_failed), MaterialTheme.colorScheme.error)
                        else -> Unit
                    }
                    Primary(
                        R.string.phone_overlay_settings_test_start,
                        enabled = !state.pending && state.test != OverlayTest.Showing,
                        onClick = actions.onStartTest,
                    )
                }
                OverlaySetupStep.ConfirmSeen -> {
                    Body(stringResource(R.string.phone_overlay_settings_confirm_q))
                    Primary(R.string.phone_overlay_settings_confirm_yes, enabled = !state.pending) { actions.onConfirmSeen(true) }
                    Secondary(R.string.phone_overlay_settings_confirm_no, enabled = !state.pending) { actions.onConfirmSeen(false) }
                }
                OverlaySetupStep.ChooseMode, OverlaySetupStep.On -> Unit
            }

            // Once, on every usable step: "Notifications only" never waits for the permission or
            // the demo. The overlay row stays disabled until both are in place.
            if (showsModeChooser(state)) ModeChooser(state, actions)
            if (step == OverlaySetupStep.ChooseMode || step == OverlaySetupStep.On) {
                Body(stringResource(R.string.phone_overlay_settings_test_note))
            }
        }
    }
}

internal const val SETTINGS_HEADER_TAG = "overlay.settingsHeader"

/** The status line for each header state. */
@StringRes
internal fun OverlayHeader.text(): Int =
    when (this) {
        OverlayHeader.NotificationOnly -> R.string.phone_overlay_settings_off
        OverlayHeader.OverlayOn -> R.string.phone_overlay_settings_on
        OverlayHeader.OverlayNotRechecked -> R.string.phone_overlay_settings_selected_unchecked
        OverlayHeader.OverlayBlocked -> R.string.phone_overlay_settings_selected_blocked
    }

/** A short explanation when the chosen overlay mode isn't in effect. */
@StringRes
internal fun OverlayHeader.hint(): Int? =
    when (this) {
        OverlayHeader.OverlayNotRechecked -> R.string.phone_overlay_settings_selected_unchecked_hint
        OverlayHeader.OverlayBlocked -> R.string.phone_overlay_settings_selected_blocked_hint
        OverlayHeader.NotificationOnly, OverlayHeader.OverlayOn -> null
    }

@Composable
private fun ModeChooser(
    state: PhoneOverlaySettingsState,
    actions: PhoneOverlaySettingsActions,
) {
    Text(stringResource(R.string.phone_overlay_settings_mode), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    Column(modifier = Modifier.selectableGroup()) {
        ModeRow(R.string.phone_overlay_settings_mode_overlay, state.mode == OverlayMode.OverlayAndNotification, canChooseOverlay(state)) {
            actions.onChooseMode(OverlayMode.OverlayAndNotification)
        }
        // Notifications only is always available: declining the overlay never blocks phone control.
        ModeRow(R.string.phone_overlay_settings_mode_notification, state.mode == OverlayMode.NotificationOnly, canChooseNotificationOnly(state)) {
            actions.onChooseMode(OverlayMode.NotificationOnly)
        }
    }
}

@Composable
private fun ModeRow(
    label: Int,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Body(
    text: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun Primary(
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(label))
    }
}

@Composable
private fun Secondary(
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(label))
    }
}
