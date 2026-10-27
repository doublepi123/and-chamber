package io.andchamber.app.ui.phone

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.andchamber.app.R
import io.andchamber.app.ui.theme.ChamberTheme

/** Card padding used by every section, so the screen reads as one column. */
internal val PhoneCardPadding = 16.dp

/** Tone of a [PhoneNotice]: neutral information, a warning, or a hard stop. */
internal enum class NoticeTone { Info, Warning, Error }

/** A tinted block with an icon, a title and an optional body; used for gates, problems and halts. */
@Composable
internal fun PhoneNotice(
    tone: NoticeTone,
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val (container, onContainer) =
        when (tone) {
            NoticeTone.Info -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
            NoticeTone.Warning -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            NoticeTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        }
    Surface(shape = MaterialTheme.shapes.medium, color = container, modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = onContainer, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = onContainer)
                if (!body.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = onContainer)
                }
                if (content != null) {
                    Spacer(Modifier.height(8.dp))
                    content()
                }
            }
        }
    }
}

/** Full-width primary action, at least 48 dp tall so it stays easy to hit with large text. */
@Composable
internal fun PhonePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors =
            if (destructive) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
internal fun PhoneSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
private fun ButtonContent(
    text: String,
    icon: ImageVector?,
) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text)
}

/** Stacked buttons with consistent spacing. */
@Composable
internal fun PhoneButtonColumn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** A numbered setup step heading: "2. Pair (first time only)". */
@Composable
internal fun StepHeading(
    number: Int,
    title: String,
    done: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (done) ChamberTheme.colors.successContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(28.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (done) ChamberTheme.colors.onSuccessContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier)
}

@StringRes
internal fun PhoneActionScope.label(): Int =
    when (this) {
        PhoneActionScope.Tap -> R.string.phone_action_tap
        PhoneActionScope.Swipe -> R.string.phone_action_swipe
        PhoneActionScope.Input -> R.string.phone_action_input
        PhoneActionScope.Back -> R.string.phone_action_back
    }

/** "Tap, Swipe and scroll, Go back" in the screen's language, in a fixed order. */
@Composable
@ReadOnlyComposable
internal fun actionList(actions: Set<PhoneActionScope>): String {
    val separator = stringResource(R.string.phone_list_separator)
    val labels = ArrayList<String>(actions.size)
    for (action in PhoneActionScope.entries) {
        if (action in actions) labels += stringResource(action.label())
    }
    return labels.joinToString(separator)
}

@StringRes
internal fun PhoneHaltReason.label(): Int =
    when (this) {
        PhoneHaltReason.Paused -> R.string.phone_halt_paused
        PhoneHaltReason.TakenOver -> R.string.phone_halt_taken_over
        PhoneHaltReason.Expired -> R.string.phone_halt_expired
        PhoneHaltReason.HostReturned -> R.string.phone_halt_host_returned
        PhoneHaltReason.TargetLeft -> R.string.phone_halt_target_left
        PhoneHaltReason.ScreenLocked -> R.string.phone_halt_screen_locked
        PhoneHaltReason.ConnectionLost -> R.string.phone_halt_connection_lost
        PhoneHaltReason.StopChannelLost -> R.string.phone_halt_stop_channel_lost
        PhoneHaltReason.ScreenUncertain -> R.string.phone_halt_screen_uncertain
        PhoneHaltReason.OutcomeUnknown -> R.string.phone_halt_outcome_unknown
        PhoneHaltReason.RuntimeChanged -> R.string.phone_halt_runtime_changed
    }

@StringRes
internal fun PhonePending.label(): Int =
    when (this) {
        PhonePending.Enabling -> R.string.phone_pending_enabling
        PhonePending.Pairing -> R.string.phone_pending_pairing
        PhonePending.Connecting -> R.string.phone_pending_connecting
        PhonePending.Disconnecting -> R.string.phone_pending_disconnecting
        PhonePending.Granting -> R.string.phone_pending_granting
        PhonePending.Pausing -> R.string.phone_pending_pausing
        PhonePending.Finishing -> R.string.phone_pending_finishing
        PhonePending.Observing -> R.string.phone_pending_observing
        PhonePending.Resuming -> R.string.phone_pending_resuming
    }

@StringRes
internal fun PhoneStandingPauseReason.label(): Int =
    when (this) {
        PhoneStandingPauseReason.NeedUser -> R.string.phone_standing_pause_reason_need_user
        PhoneStandingPauseReason.TakenOver -> R.string.phone_standing_pause_reason_taken_over
        PhoneStandingPauseReason.OutcomeUnknown -> R.string.phone_standing_pause_reason_outcome_unknown
        PhoneStandingPauseReason.ConnectionLost -> R.string.phone_standing_pause_reason_connection_lost
        PhoneStandingPauseReason.ScreenLocked -> R.string.phone_standing_pause_reason_screen_locked
        PhoneStandingPauseReason.RuntimeChanged -> R.string.phone_standing_pause_reason_runtime_changed
    }
