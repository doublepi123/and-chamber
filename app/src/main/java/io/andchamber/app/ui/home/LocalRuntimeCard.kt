package io.andchamber.app.ui.home

import io.andchamber.app.i18n.UserMessages
import androidx.compose.ui.res.stringResource
import io.andchamber.app.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.RuntimeInfo
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.RuntimeUpdate
import io.andchamber.app.runtime.RuntimeUpdateState
import io.andchamber.app.runtime.UpdateFailure
import io.andchamber.app.runtime.VersionChange
import io.andchamber.app.ui.components.ChamberCard
import io.andchamber.app.ui.components.ConfirmDialog
import io.andchamber.app.ui.components.DetailRow
import io.andchamber.app.ui.components.IconTile
import io.andchamber.app.ui.components.LogConsole
import io.andchamber.app.ui.components.StatusPill
import io.andchamber.app.ui.components.StatusTone
import io.andchamber.app.ui.components.color
import io.andchamber.app.ui.formatBytes
import io.andchamber.app.ui.rememberCopyAction
import io.andchamber.app.ui.rememberLastNonNull
import io.andchamber.app.ui.theme.ChamberTextStyles
import io.andchamber.app.ui.theme.ChamberTheme
import io.andchamber.app.ui.theme.MonoFamily
import io.andchamber.app.ui.DurationUnits
import io.andchamber.app.ui.uptimeLabel
import kotlinx.coroutines.delay
import kotlin.math.max

/** Coarse phase of [RuntimeState]; content transitions are keyed on this, not on every progress tick. */
internal enum class RuntimePhase { Checking, Unsupported, NotInstalled, Installing, Stopped, Starting, Running, Stopping, Failed }

internal val RuntimeState.phase: RuntimePhase
    get() =
        when (this) {
            RuntimeState.Detecting -> RuntimePhase.Checking
            RuntimeState.UnsupportedAbi -> RuntimePhase.Unsupported
            RuntimeState.NotInstalled -> RuntimePhase.NotInstalled
            is RuntimeState.Installing -> RuntimePhase.Installing
            RuntimeState.Stopped -> RuntimePhase.Stopped
            is RuntimeState.Starting -> RuntimePhase.Starting
            is RuntimeState.Running -> RuntimePhase.Running
            is RuntimeState.Stopping -> RuntimePhase.Stopping
            is RuntimeState.Failed -> RuntimePhase.Failed
        }

private val RuntimePhase.isBusy: Boolean
    get() = this == RuntimePhase.Installing || this == RuntimePhase.Starting || this == RuntimePhase.Stopping

@Composable
private fun RuntimePhase.statusLabel(): String =
    when (this) {
        RuntimePhase.Checking -> stringResource(R.string.status_checking)
        RuntimePhase.Unsupported -> stringResource(R.string.status_unsupported)
        RuntimePhase.NotInstalled -> stringResource(R.string.status_not_set_up)
        RuntimePhase.Installing -> stringResource(R.string.status_setting_up)
        RuntimePhase.Stopped -> stringResource(R.string.status_stopped)
        RuntimePhase.Starting -> stringResource(R.string.status_starting)
        RuntimePhase.Running -> stringResource(R.string.status_running)
        RuntimePhase.Stopping -> stringResource(R.string.status_stopping)
        RuntimePhase.Failed -> stringResource(R.string.status_problem)
    }

private fun RuntimePhase.tone(): StatusTone =
    when (this) {
        RuntimePhase.Running -> StatusTone.Live
        RuntimePhase.Installing, RuntimePhase.Starting, RuntimePhase.Stopping -> StatusTone.Busy
        RuntimePhase.Failed -> StatusTone.Error
        else -> StatusTone.Idle
    }

private enum class PendingConfirm { Repair, Uninstall, Update }

/** Recommended free space before setup, with headroom over the ~1.2 GB footprint. */
private const val LOW_SPACE_BYTES = 3L * 1024 * 1024 * 1024

/** How long a confirmed update hides the offer while the request reaches the runtime. */
private const val UPDATE_PICKUP_GRACE_MS = 15_000L

/** The first non-null [value] this composition saw, kept from then on (not a snapshot state). */
@Composable
private fun <T : Any> rememberFirstNonNull(value: T?): T? {
    val holder = remember { FirstValue<T>() }
    if (holder.value == null) holder.value = value
    return holder.value
}

private class FirstValue<T : Any> {
    var value: T? = null
}

/** What the update slot under the card body shows. */
private sealed interface UpdateNotice {
    val kind: Kind

    data class Offer(val update: RuntimeUpdate) : UpdateNotice {
        override val kind get() = Kind.Offer
    }

    data class Problem(val failure: UpdateFailure, val canRetry: Boolean) : UpdateNotice {
        override val kind get() = Kind.Problem
    }

    /** The slot's transitions key on this, so a new version number updates the row in place. */
    enum class Kind { Offer, Problem }
}

/** An installed runtime that isn't being changed: the update offer (or its failure) may show. */
private val RuntimePhase.showsUpdateNotice: Boolean
    get() =
        this == RuntimePhase.Stopped || this == RuntimePhase.Starting || this == RuntimePhase.Running ||
            this == RuntimePhase.Stopping || this == RuntimePhase.Failed

/** An update can begin now: not in the middle of a start or a stop. */
private val RuntimePhase.canUpdate: Boolean
    get() = this == RuntimePhase.Stopped || this == RuntimePhase.Running || this == RuntimePhase.Failed

@Composable
internal fun LocalRuntimeCard(
    state: RuntimeState,
    info: RuntimeInfo?,
    logLines: List<String>,
    onSetUp: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
    checking: Boolean = false,
    /** True when projects live in the shared folder, which uninstall leaves in place. */
    sharedFolderInUse: Boolean = false,
    /** Open-on-launch setting shown in Details; null hides it. */
    autoOpenLocal: Boolean? = null,
    onAutoOpenChange: (Boolean) -> Unit = {},
    /** Keep-screen-on setting shown in Details; null hides it. */
    keepScreenOn: Boolean? = null,
    onKeepScreenOnChange: (Boolean) -> Unit = {},
    /** OpenChamber / OpenCode update check and the outcome of the last update. */
    updates: RuntimeUpdateState = RuntimeUpdateState(),
    onUpdate: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onDismissUpdateFailure: () -> Unit = {},
) {
    val phase = if (checking || state == RuntimeState.Detecting) RuntimePhase.Checking else state.phase
    var logOpen by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }

    // A failure is easier to understand with the output right there.
    LaunchedEffect(phase) {
        if (phase == RuntimePhase.Failed && logLines.isNotEmpty()) logOpen = true
    }

    // The offer folds away as soon as the user confirms; the runtime picks the request up a moment
    // later (stopping the server first). If it never does, the offer comes back.
    var updateRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(updateRequested, phase) {
        if (!updateRequested) return@LaunchedEffect
        if (phase != RuntimePhase.Installing) delay(UPDATE_PICKUP_GRACE_MS)
        updateRequested = false
    }
    val requestUpdate = {
        updateRequested = true
        onUpdate()
    }
    val offer = updates.available?.takeIf { it.hasChanges }
    val failure = updates.lastFailure
    val notice: UpdateNotice? =
        when {
            info == null || !phase.showsUpdateNotice || updateRequested -> null
            failure != null -> UpdateNotice.Problem(failure, canRetry = offer != null)
            offer != null -> UpdateNotice.Offer(offer)
            else -> null
        }
    val updatingNow = (state as? RuntimeState.Installing)?.updating == true

    ChamberCard(
        modifier = modifier,
        accent = if (phase == RuntimePhase.Running) ChamberTheme.colors.success else null,
    ) {
        CardHeader(phase = phase, updating = updatingNow)

        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                    .using(SizeTransform(clip = false) { _, _ -> tween(260) })
            },
            label = "runtimeBody",
        ) { shown ->
            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                // Live state for this phase; while animating out, the last state it had.
                val live = rememberLastNonNull(state.takeIf { it.phase == shown })
                when (shown) {
                    RuntimePhase.Checking -> Spacer(Modifier.height(48.dp))
                    RuntimePhase.Unsupported -> UnsupportedBody()
                    RuntimePhase.NotInstalled -> NotInstalledBody(onSetUp = onSetUp)
                    RuntimePhase.Installing -> {
                        val installing = live as? RuntimeState.Installing
                        InstallingBody(
                            state = installing,
                            // The offer as it was when the update began: it is cleared once the update
                            // lands, and a check finishing meanwhile must not change what is shown.
                            update = rememberFirstNonNull(updates.available.takeIf { installing?.updating == true }),
                        )
                    }
                    RuntimePhase.Stopped -> StoppedBody(onStart = onStart)
                    RuntimePhase.Starting ->
                        TransitionBody(
                            title = stringResource(R.string.starting_title),
                            detail = (live as? RuntimeState.Starting)?.detail,
                        )
                    RuntimePhase.Running ->
                        (live as? RuntimeState.Running)?.let {
                            RunningBody(running = it, onOpen = onOpen, onStop = onStop)
                        } ?: Spacer(Modifier.height(96.dp))
                    RuntimePhase.Stopping ->
                        TransitionBody(
                            title = stringResource(R.string.stopping_title),
                            detail = (live as? RuntimeState.Stopping)?.detail,
                        )
                    RuntimePhase.Failed ->
                        (live as? RuntimeState.Failed)?.let {
                            FailedBody(
                                failed = it,
                                onRetry = onRetry,
                                onRepair = { confirm = PendingConfirm.Repair },
                            )
                        } ?: Spacer(Modifier.height(96.dp))
                }
            }
        }

        // Below the main actions: an update offer, or why the last update didn't finish. The last
        // notice stays while the slot folds away.
        val shownNotice = rememberLastNonNull(notice)
        AnimatedVisibility(
            visible = notice != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            AnimatedContent(
                targetState = shownNotice?.kind ?: UpdateNotice.Kind.Offer,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                        .using(SizeTransform(clip = false) { _, _ -> tween(260) })
                },
                label = "updateNotice",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) { kind ->
                // Live notice of this kind; while animating out, the last one it had.
                when (val live = rememberLastNonNull(shownNotice?.takeIf { it.kind == kind })) {
                    is UpdateNotice.Offer ->
                        UpdateOfferRow(
                            update = live.update,
                            enabled = phase.canUpdate,
                            onUpdate = { confirm = PendingConfirm.Update },
                        )
                    is UpdateNotice.Problem ->
                        UpdateProblemRow(
                            failure = live.failure,
                            canRetry = live.canRetry,
                            retryEnabled = phase.canUpdate,
                            // Asks again: the failure may be old news, and a retry stops the server too.
                            onRetry = { confirm = PendingConfirm.Update },
                            onDismiss = onDismissUpdateFailure,
                        )
                    null -> Unit
                }
            }
        }

        val showLogToggle =
            phase != RuntimePhase.Unsupported &&
                phase != RuntimePhase.Checking &&
                (phase != RuntimePhase.NotInstalled || logLines.isNotEmpty())
        // After a failed first setup there is no info yet, but Uninstall must still be reachable
        // so partial downloads can be removed.
        val showDetailsToggle =
            phase != RuntimePhase.NotInstalled &&
                phase != RuntimePhase.Unsupported &&
                phase != RuntimePhase.Checking &&
                (info != null || phase == RuntimePhase.Failed)
        if (showLogToggle || showDetailsToggle) {
            HorizontalDivider(color = ChamberTheme.colors.cardBorder)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showLogToggle) {
                    FooterToggle(
                        label = stringResource(R.string.show_log),
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                        expanded = logOpen,
                        onClick = { logOpen = !logOpen },
                    )
                }
                if (showDetailsToggle) {
                    FooterToggle(
                        label = stringResource(R.string.details),
                        icon = Icons.Rounded.Tune,
                        expanded = detailsOpen,
                        onClick = { detailsOpen = !detailsOpen },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showLogToggle && logOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            LogConsole(
                lines = logLines,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }

        AnimatedVisibility(
            visible = showDetailsToggle && detailsOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            DetailsSection(
                info = info,
                actionsEnabled = !phase.isBusy,
                autoOpen = autoOpenLocal,
                onAutoOpenChange = onAutoOpenChange,
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChange = onKeepScreenOnChange,
                updates = updates,
                // A check while the environment is being replaced would compare against old versions.
                checkEnabled = phase != RuntimePhase.Installing,
                onCheckForUpdates = onCheckForUpdates,
                onRepair = { confirm = PendingConfirm.Repair },
                onUninstall = { confirm = PendingConfirm.Uninstall },
            )
        }
    }

    when (confirm) {
        PendingConfirm.Update ->
            if (offer == null) {
                // Nothing left to install (a check or another update got there first).
                LaunchedEffect(Unit) { confirm = null }
            } else {
                // Only a running server is started again afterwards; a stopped one stays stopped.
                val serverUp = state is RuntimeState.Running || state is RuntimeState.Starting
                ConfirmDialog(
                    title = stringResource(R.string.update_dialog_title),
                    body = stringResource(if (serverUp) R.string.update_dialog_body_running else R.string.update_dialog_body),
                    confirmLabel = stringResource(R.string.update),
                    onConfirm = requestUpdate,
                    onDismiss = { confirm = null },
                )
            }
        PendingConfirm.Repair ->
            ConfirmDialog(
                title = stringResource(R.string.repair_dialog_title),
                body = stringResource(R.string.repair_dialog_body),
                confirmLabel = stringResource(R.string.repair),
                onConfirm = onRepair,
                onDismiss = { confirm = null },
            )
        PendingConfirm.Uninstall ->
            ConfirmDialog(
                title = stringResource(R.string.uninstall_dialog_title),
                body = if (sharedFolderInUse) stringResource(R.string.uninstall_dialog_body_shared) else stringResource(R.string.uninstall_dialog_body),
                confirmLabel = stringResource(R.string.uninstall),
                onConfirm = onUninstall,
                onDismiss = { confirm = null },
                destructive = true,
            )
        null -> Unit
    }
}

@Composable
private fun CardHeader(
    phase: RuntimePhase,
    updating: Boolean,
) {
    val tone = phase.tone()
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            tint = if (phase == RuntimePhase.Running) tone.color() else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.local_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.local_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(
            text =
                if (phase == RuntimePhase.Installing && updating) {
                    stringResource(R.string.status_updating)
                } else {
                    phase.statusLabel()
                },
            tone = tone,
        )
    }
}

@Composable
private fun UnsupportedBody() {
    Text(
        text = stringResource(R.string.unsupported_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.unsupported_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NotInstalledBody(onSetUp: () -> Unit) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val freeBytes =
        remember(context) {
            if (inspection) null else runCatching { context.filesDir.usableSpace }.getOrNull()
        }

    Text(
        text = stringResource(R.string.setup_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FactRow(Icons.Rounded.Inventory2, stringResource(R.string.setup_fact_contents))
        FactRow(Icons.Rounded.Download, stringResource(R.string.setup_fact_size))
        FactRow(Icons.Rounded.Wifi, stringResource(R.string.setup_fact_wifi))
        FactRow(Icons.Rounded.Schedule, stringResource(R.string.setup_fact_time))
        freeBytes?.let { free ->
            val low = free < LOW_SPACE_BYTES
            FactRow(
                icon = Icons.Rounded.SdStorage,
                text = stringResource(R.string.setup_fact_free, formatBytes(free)) + if (low) ". " + stringResource(R.string.setup_low_space) else "",
                warn = low,
            )
        }
    }
    Spacer(Modifier.height(18.dp))
    Button(
        onClick = onSetUp,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.set_up))
    }
}

@Composable
private fun FactRow(
    icon: ImageVector,
    text: String,
    warn: Boolean = false,
) {
    val tint = if (warn) ChamberTheme.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warn) ChamberTheme.colors.warning else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Setup, repair or update progress. An update has its own, shorter step list and says what it moves
 * to ([update], the offer being installed; null when unknown).
 */
@Composable
private fun InstallingBody(
    state: RuntimeState.Installing?,
    update: RuntimeUpdate?,
) {
    val updating = state?.updating == true
    val steps = if (updating) InstallStep.updateSteps else InstallStep.setupSteps
    val current = state?.step ?: steps.first()
    // A step outside this list would read as "step 0"; show it as the first one instead.
    val currentIndex = steps.indexOf(current).coerceAtLeast(0)

    if (updating) {
        Text(
            text = stringResource(R.string.updating_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (update != null) {
            Spacer(Modifier.height(4.dp))
            VersionChanges(
                update = update,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                emphasis = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(14.dp))
    }
    Text(
        text = stringResource(R.string.step_of, currentIndex + 1, steps.size),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(12.dp))
    Column {
        steps.forEachIndexed { index, step ->
            InstallStepRow(
                step = step,
                status =
                    when {
                        index < currentIndex -> StepStatus.Done
                        index == currentIndex -> StepStatus.Active
                        else -> StepStatus.Pending
                    },
                progress = if (index == currentIndex) state?.progress else null,
                detail = if (index == currentIndex) state?.detail else null,
                isLast = index == steps.lastIndex,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(if (updating) R.string.update_background_note else R.string.setup_background_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private enum class StepStatus { Done, Active, Pending }

@Composable
private fun InstallStepRow(
    step: InstallStep,
    status: StepStatus,
    progress: Float?,
    detail: String?,
    isLast: Boolean,
) {
    val idle = ChamberTheme.colors.idle
    val success = ChamberTheme.colors.success
    val primary = MaterialTheme.colorScheme.primary
    val railColor = if (status == StepStatus.Done) success.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Thin rail from this step's marker down to the next one.
                    if (!isLast) {
                        val x = 12.dp.toPx()
                        drawLine(
                            color = railColor,
                            start = Offset(x, 25.dp.toPx()),
                            end = Offset(x, size.height + 1.dp.toPx()),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                },
    ) {
        Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.TopCenter) {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                when (status) {
                    StepStatus.Done ->
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = success,
                            modifier = Modifier.size(18.dp),
                        )
                    StepStatus.Active ->
                        if (progress != null) {
                            val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "stepRing")
                            CircularProgressIndicator(
                                progress = { animated },
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                trackColor = primary.copy(alpha = 0.2f),
                                strokeCap = StrokeCap.Round,
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                strokeCap = StrokeCap.Round,
                            )
                        }
                    StepStatus.Pending ->
                        Canvas(modifier = Modifier.size(12.dp)) {
                            drawCircle(color = idle.copy(alpha = 0.7f), style = Stroke(width = 1.5.dp.toPx()))
                        }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(top = 2.dp, bottom = if (isLast) 0.dp else 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(UserMessages.stepLabel(step)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (status == StepStatus.Active) FontWeight.SemiBold else FontWeight.Normal,
                    color =
                        when (status) {
                            StepStatus.Active -> MaterialTheme.colorScheme.onSurface
                            StepStatus.Done -> MaterialTheme.colorScheme.onSurfaceVariant
                            StepStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                    modifier = Modifier.weight(1f),
                )
                if (status == StepStatus.Active && progress != null) {
                    Text(
                        text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = primary,
                    )
                }
            }
            if (status == StepStatus.Active) {
                Spacer(Modifier.height(8.dp))
                StepProgressBar(progress = progress)
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detail,
                        style = ChamberTextStyles.codeSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepProgressBar(progress: Float?) {
    val modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    if (progress != null) {
        val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "stepBar")
        LinearProgressIndicator(
            progress = { animated },
            modifier = modifier,
            trackColor = track,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier,
            trackColor = track,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    }
}

@Composable
private fun StoppedBody(onStart: () -> Unit) {
    Text(
        text = stringResource(R.string.stopped_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onStart,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.start))
    }
}

@Composable
private fun TransitionBody(
    title: String,
    detail: String?,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(10.dp))
    StepProgressBar(progress = null)
    if (!detail.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = detail,
            style = ChamberTextStyles.codeSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RunningBody(
    running: RuntimeState.Running,
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    val copy = rememberCopyAction()
    val now by produceState(initialValue = System.currentTimeMillis(), running.sinceEpochMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = running.url,
                    style = ChamberTextStyles.code,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Resolve the localised pieces in composition; the pure uptime formatter gets plain strings.
                val justStarted = stringResource(R.string.just_started)
                val upTemplate = stringResource(R.string.uptime, "%1\$s")
                val portText = stringResource(R.string.port, running.port)
                val resources = LocalContext.current.resources
                val units =
                    remember(resources) {
                        DurationUnits(
                            minutes = { m -> resources.getString(R.string.duration_minutes, m) },
                            hoursMinutes = { h, m -> resources.getString(R.string.duration_hours_minutes, h, m) },
                            daysHours = { d, h -> resources.getString(R.string.duration_days_hours, d, h) },
                        )
                    }
                Text(
                    text =
                        uptimeLabel(
                            sinceEpochMs = running.sinceEpochMs,
                            nowEpochMs = now,
                            justStarted = justStarted,
                            upTemplate = { value -> upTemplate.replace("%1\$s", value) },
                            units = units,
                        ) + "  ·  " + portText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { copy(running.url) }) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.copy_address),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onOpen,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.open), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.stop))
        }
    }
}

@Composable
private fun FailedBody(
    failed: RuntimeState.Failed,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(stringResource(R.string.failed_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(failed.reason, style = ChamberTextStyles.codeSmall)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    if (failed.recoverable) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.try_again))
            }
            OutlinedButton(
                onClick = onRepair,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.medium,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.repair))
            }
        }
    } else {
        Button(
            onClick = onRepair,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.repair))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.repair_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Update available": a warm notice under the main actions with what changes and an Update button
 * (which asks first). [enabled] is false while the server is starting or stopping.
 */
@Composable
private fun UpdateOfferRow(
    update: RuntimeUpdate,
    enabled: Boolean,
    onUpdate: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TitleWithAction(
            // No top padding: the button's touch height already leaves ~14 dp above the title.
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, bottom = 12.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Upgrade, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.update_available), style = MaterialTheme.typography.titleSmall)
                }
            },
            action = {
                TextButton(
                    onClick = onUpdate,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) {
                    Text(stringResource(R.string.update))
                }
            },
            body = {
                VersionChanges(
                    update = update,
                    color = LocalContentColor.current.copy(alpha = 0.78f),
                    emphasis = LocalContentColor.current,
                    modifier = Modifier.padding(start = 28.dp, end = 8.dp),
                )
            },
        )
    }
}

/**
 * A [title] line with [action] at its end, and [body] right under the title at the full width: on
 * a 360 dp phone the version lines would wrap if they shared their width with the button. The title
 * is centred on the action; the body starts below the title, inside the button's spare touch height
 * when the title is shorter than it, and below a title that wraps at large font sizes.
 */
@Composable
private fun TitleWithAction(
    title: @Composable () -> Unit,
    action: @Composable () -> Unit,
    body: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(title, action, body), modifier = modifier) { (titleSlot, actionSlot, bodySlot), constraints ->
        val gap = 2.dp.roundToPx()
        val actionPlaceable = actionSlot.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width =
            if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth.coerceAtLeast(actionPlaceable.width)
        val titlePlaceable =
            titleSlot.single().measure(Constraints(maxWidth = (width - actionPlaceable.width).coerceAtLeast(0)))
        val bodyPlaceable = bodySlot.single().measure(Constraints(maxWidth = width))
        val headHeight = max(titlePlaceable.height, actionPlaceable.height)
        val titleTop = (headHeight - titlePlaceable.height) / 2
        val bodyTop = titleTop + titlePlaceable.height + gap
        layout(width, max(headHeight, bodyTop + bodyPlaceable.height)) {
            titlePlaceable.placeRelative(0, titleTop)
            actionPlaceable.placeRelative(width - actionPlaceable.width, (headHeight - actionPlaceable.height) / 2)
            bodyPlaceable.placeRelative(0, bodyTop)
        }
    }
}

/** One line per package that changes: "OpenChamber 2.4.0 → 2.5.0", versions in mono. */
@Composable
private fun VersionChanges(
    update: RuntimeUpdate,
    color: Color,
    emphasis: Color = color,
    modifier: Modifier = Modifier,
) {
    val changes =
        listOfNotNull(
            update.openChamber.takeIf { it.changed }?.let { stringResource(R.string.detail_open_chamber) to it },
            update.openCode.takeIf { it.changed }?.let { stringResource(R.string.detail_open_code) to it },
        )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        changes.forEach { (name, change) ->
            Text(
                text = versionChangeText(name, change, emphasis),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

private fun versionChangeText(
    name: String,
    change: VersionChange,
    emphasis: Color,
) = buildAnnotatedString {
    append(name)
    append("  ")
    withStyle(SpanStyle(fontFamily = MonoFamily)) {
        // An unknown installed version shows only where it goes.
        change.current?.let {
            append(it)
            append(" → ")
        }
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = emphasis)) { append(change.target) }
    }
}

/**
 * The last update didn't finish. The old version is still installed, so this is a dismissible note
 * in the card rather than a failed state: the localised reason, a reassurance, the raw output
 * behind Details, and Try again when there is still something to install.
 */
@Composable
private fun UpdateProblemRow(
    failure: UpdateFailure,
    canRetry: Boolean,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showOutput by rememberSaveable { mutableStateOf(false) }
    val headline =
        failure.messageKey?.let { stringResource(UserMessages.headline(it)) }
            ?: failure.reason.ifBlank { stringResource(R.string.msg_generic) }
    // English reason first (e.g. the failing command and exit code), then the raw output.
    val output =
        listOfNotNull(failure.reason.takeIf { it.isNotBlank() && failure.messageKey != null }, failure.detail?.takeIf { it.isNotBlank() })
            .joinToString("\n\n")
            .takeIf { it.isNotEmpty() }
    val hasActions = output != null || canRetry
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 6.dp).size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f).padding(top = 6.dp, bottom = if (hasActions) 0.dp else 8.dp)) {
                    Text(stringResource(R.string.update_failed_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(headline, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.update_failed_kept),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.78f),
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.dismiss),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (hasActions) {
                Row(
                    // Lines the first button's label up with the text above (icon 20 + gap 10,
                    // less the button's own 12 dp of padding).
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (output != null) {
                        val rotation by animateFloatAsState(if (showOutput) 180f else 0f, label = "updateOutputChevron")
                        TextButton(
                            onClick = { showOutput = !showOutput },
                            colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current.copy(alpha = 0.78f)),
                        ) {
                            Text(stringResource(R.string.details), style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(2.dp))
                            Icon(
                                Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp).rotate(rotation),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (canRetry) {
                        TextButton(
                            onClick = onRetry,
                            enabled = retryEnabled,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        ) {
                            Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.try_again))
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = showOutput && output != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                rememberLastNonNull(output)?.let { text ->
                    RawOutput(text = text, modifier = Modifier.padding(end = 6.dp, top = 2.dp, bottom = 8.dp))
                }
            }
        }
    }
}

/** Untranslated command output in the terminal colors of the log, selectable and copyable. */
@Composable
private fun RawOutput(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = ChamberTheme.colors
    val copy = rememberCopyAction()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colors.terminalBackground,
        contentColor = colors.terminalForeground,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box {
            SelectionContainer {
                Text(
                    text = text,
                    style = ChamberTextStyles.log,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                            // Room on the right for the copy button.
                            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 36.dp),
                )
            }
            IconButton(
                onClick = { copy(text) },
                modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
            ) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.copy_details),
                    tint = colors.terminalMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * "Check for updates" in Details, a whole-row action like [AutoOpenRow]: the summary says where
 * things stand (checking, up to date, update available, or the check failed) and when it last ran.
 */
@Composable
internal fun UpdateCheckRow(
    updates: RuntimeUpdateState,
    enabled: Boolean,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clickable = enabled && !updates.checking
    val hasUpdate = updates.available?.hasChanges == true
    val checkedAgo = updates.lastCheckedAtMs?.let { checkedAgoLabel(it) }
    val warning = ChamberTheme.colors.warning
    val (statusIcon, statusTint) =
        when {
            updates.checking -> null to MaterialTheme.colorScheme.onSurfaceVariant
            updates.checkFailed -> Icons.Rounded.CloudOff to warning
            hasUpdate -> Icons.Rounded.Upgrade to MaterialTheme.colorScheme.primary
            updates.lastCheckedAtMs != null -> Icons.Rounded.CheckCircle to ChamberTheme.colors.success
            else -> null to MaterialTheme.colorScheme.onSurfaceVariant
        }
    val status =
        when {
            updates.checking -> stringResource(R.string.update_checking)
            updates.checkFailed -> stringResource(R.string.update_check_failed)
            hasUpdate && checkedAgo != null -> stringResource(R.string.update_status_checked, stringResource(R.string.update_available), checkedAgo)
            hasUpdate -> stringResource(R.string.update_available)
            checkedAgo != null -> stringResource(R.string.update_status_checked, stringResource(R.string.update_up_to_date), checkedAgo)
            else -> stringResource(R.string.update_not_checked)
        }
    val dim = if (enabled) 1f else 0.38f

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .clickable(enabled = clickable, role = Role.Button, onClick = onCheck)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_check),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Top) {
                    if (statusIcon != null) {
                        Icon(
                            statusIcon,
                            contentDescription = null,
                            tint = statusTint.copy(alpha = statusTint.alpha * dim),
                            modifier = Modifier.padding(top = 1.5.dp).size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = (if (updates.checkFailed) warning else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = dim),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (updates.checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        strokeCap = StrokeCap.Round,
                    )
                } else {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * "just now", "42 minutes ago", "3 hours ago", "2 days ago". From the app's own resources, so it is
 * always in the same language as the sentence around it. Ticks every 30 s while shown.
 */
@Composable
private fun checkedAgoLabel(atMs: Long): String {
    val now by produceState(initialValue = System.currentTimeMillis(), atMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val minutes = ((now - atMs).coerceAtLeast(0L) / 60_000L).toInt()
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> stringResource(R.string.checked_just_now)
        hours < 1 -> pluralStringResource(R.plurals.checked_minutes_ago, minutes, minutes)
        days < 1 -> pluralStringResource(R.plurals.checked_hours_ago, hours, hours)
        else -> pluralStringResource(R.plurals.checked_days_ago, days, days)
    }
}

@Composable
private fun FooterToggle(
    label: String,
    icon: ImageVector,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "toggleChevron")
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(18.dp).rotate(rotation),
        )
    }
}

/**
 * "Open OpenChamber when the app starts": a quiet switch row in the same box as the details
 * table below it; the whole row toggles.
 */
@Composable
internal fun AutoOpenRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) = SettingSwitchRow(
    label = stringResource(R.string.auto_open_label),
    hint = stringResource(R.string.auto_open_hint),
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier,
)

/**
 * "Keep screen on while the local server runs", the same quiet switch row as [AutoOpenRow]. The
 * hint says it only applies while the app is in front.
 */
@Composable
internal fun ScreenAwakeRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) = SettingSwitchRow(
    label = stringResource(R.string.screen_awake_label),
    hint = stringResource(R.string.screen_awake_hint),
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier,
)

@Composable
private fun SettingSwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun DetailsSection(
    info: RuntimeInfo?,
    actionsEnabled: Boolean,
    autoOpen: Boolean?,
    onAutoOpenChange: (Boolean) -> Unit,
    keepScreenOn: Boolean?,
    onKeepScreenOnChange: (Boolean) -> Unit,
    updates: RuntimeUpdateState,
    checkEnabled: Boolean,
    onCheckForUpdates: () -> Unit,
    onRepair: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        // Only meaningful once something is installed to open.
        if (autoOpen != null && info != null) {
            AutoOpenRow(
                checked = autoOpen,
                onCheckedChange = onAutoOpenChange,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        // Next to the other launch-and-use setting; there is a server to run once installed.
        if (keepScreenOn != null && info != null) {
            ScreenAwakeRow(
                checked = keepScreenOn,
                onCheckedChange = onKeepScreenOnChange,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (info != null) {
            // Right above the versions it is about.
            UpdateCheckRow(
                updates = updates,
                enabled = checkEnabled,
                onCheck = onCheckForUpdates,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (info != null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    val unknown = stringResource(R.string.unknown_value)
                    DetailRow(stringResource(R.string.detail_open_chamber), info.openChamberVersion ?: unknown)
                    DetailRow(stringResource(R.string.detail_open_code), info.openCodeVersion ?: unknown)
                    DetailRow(stringResource(R.string.detail_node), info.nodeVersion ?: unknown)
                    DetailRow(stringResource(R.string.detail_alpine), info.alpineVersion)
                    DetailRow(stringResource(R.string.detail_abi), info.abi)
                    DetailRow(stringResource(R.string.detail_disk_used), info.diskUsageBytes?.let(::formatBytes) ?: unknown)
                    DetailRow(stringResource(R.string.detail_free_space), info.freeSpaceBytes?.let(::formatBytes) ?: unknown)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onRepair, enabled = actionsEnabled) {
                Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.repair))
            }
            TextButton(
                onClick = onUninstall,
                enabled = actionsEnabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.uninstall))
            }
        }
    }
}

