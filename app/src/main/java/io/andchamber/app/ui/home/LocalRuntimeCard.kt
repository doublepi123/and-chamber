package io.andchamber.app.ui.home

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.RuntimeInfo
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.ui.Strings
import io.andchamber.app.ui.components.ChamberCard
import io.andchamber.app.ui.components.ConfirmDialog
import io.andchamber.app.ui.components.DetailRow
import io.andchamber.app.ui.components.IconTile
import io.andchamber.app.ui.components.LogConsole
import io.andchamber.app.ui.components.StatusPill
import io.andchamber.app.ui.components.StatusTone
import io.andchamber.app.ui.components.color
import io.andchamber.app.ui.formatBytes
import io.andchamber.app.ui.label
import io.andchamber.app.ui.rememberCopyAction
import io.andchamber.app.ui.rememberLastNonNull
import io.andchamber.app.ui.theme.ChamberTextStyles
import io.andchamber.app.ui.theme.ChamberTheme
import io.andchamber.app.ui.uptimeLabel
import kotlinx.coroutines.delay

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

private fun RuntimePhase.statusLabel(): String =
    when (this) {
        RuntimePhase.Checking -> Strings.statusChecking
        RuntimePhase.Unsupported -> Strings.statusUnsupported
        RuntimePhase.NotInstalled -> Strings.statusNotSetUp
        RuntimePhase.Installing -> Strings.statusSettingUp
        RuntimePhase.Stopped -> Strings.statusStopped
        RuntimePhase.Starting -> Strings.statusStarting
        RuntimePhase.Running -> Strings.statusRunning
        RuntimePhase.Stopping -> Strings.statusStopping
        RuntimePhase.Failed -> Strings.statusProblem
    }

private fun RuntimePhase.tone(): StatusTone =
    when (this) {
        RuntimePhase.Running -> StatusTone.Live
        RuntimePhase.Installing, RuntimePhase.Starting, RuntimePhase.Stopping -> StatusTone.Busy
        RuntimePhase.Failed -> StatusTone.Error
        else -> StatusTone.Idle
    }

private enum class PendingConfirm { Repair, Uninstall }

/** Recommended free space before setup, with headroom over the ~1.2 GB footprint. */
private const val LOW_SPACE_BYTES = 3L * 1024 * 1024 * 1024

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
) {
    val phase = if (checking || state == RuntimeState.Detecting) RuntimePhase.Checking else state.phase
    var logOpen by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }

    // A failure is easier to understand with the output right there.
    LaunchedEffect(phase) {
        if (phase == RuntimePhase.Failed && logLines.isNotEmpty()) logOpen = true
    }

    ChamberCard(
        modifier = modifier,
        accent = if (phase == RuntimePhase.Running) ChamberTheme.colors.success else null,
    ) {
        CardHeader(phase = phase)

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
                    RuntimePhase.Installing ->
                        InstallingBody(state = live as? RuntimeState.Installing)
                    RuntimePhase.Stopped -> StoppedBody(onStart = onStart)
                    RuntimePhase.Starting ->
                        TransitionBody(
                            title = Strings.startingTitle,
                            detail = (live as? RuntimeState.Starting)?.detail,
                        )
                    RuntimePhase.Running ->
                        (live as? RuntimeState.Running)?.let {
                            RunningBody(running = it, onOpen = onOpen, onStop = onStop)
                        } ?: Spacer(Modifier.height(96.dp))
                    RuntimePhase.Stopping ->
                        TransitionBody(
                            title = Strings.stoppingTitle,
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
                        label = Strings.showLog,
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                        expanded = logOpen,
                        onClick = { logOpen = !logOpen },
                    )
                }
                if (showDetailsToggle) {
                    FooterToggle(
                        label = Strings.details,
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
                onRepair = { confirm = PendingConfirm.Repair },
                onUninstall = { confirm = PendingConfirm.Uninstall },
            )
        }
    }

    when (confirm) {
        PendingConfirm.Repair ->
            ConfirmDialog(
                title = Strings.repairDialogTitle,
                body = Strings.repairDialogBody,
                confirmLabel = Strings.repair,
                onConfirm = onRepair,
                onDismiss = { confirm = null },
            )
        PendingConfirm.Uninstall ->
            ConfirmDialog(
                title = Strings.uninstallDialogTitle,
                body = if (sharedFolderInUse) Strings.uninstallDialogBodyShared else Strings.uninstallDialogBody,
                confirmLabel = Strings.uninstall,
                onConfirm = onUninstall,
                onDismiss = { confirm = null },
                destructive = true,
            )
        null -> Unit
    }
}

@Composable
private fun CardHeader(phase: RuntimePhase) {
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
                text = Strings.localTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = Strings.localSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(text = phase.statusLabel(), tone = tone)
    }
}

@Composable
private fun UnsupportedBody() {
    Text(
        text = Strings.unsupportedTitle,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = Strings.unsupportedBody,
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
        text = Strings.setupIntro,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FactRow(Icons.Rounded.Inventory2, Strings.setupFactContents)
        FactRow(Icons.Rounded.Download, Strings.setupFactSize)
        FactRow(Icons.Rounded.Wifi, Strings.setupFactWifi)
        FactRow(Icons.Rounded.Schedule, Strings.setupFactTime)
        freeBytes?.let { free ->
            val low = free < LOW_SPACE_BYTES
            FactRow(
                icon = Icons.Rounded.SdStorage,
                text = Strings.setupFactFree(formatBytes(free)) + if (low) ". " + Strings.setupLowSpace else "",
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
        Text(Strings.setUp)
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

@Composable
private fun InstallingBody(state: RuntimeState.Installing?) {
    val steps = InstallStep.entries
    val current = state?.step ?: steps.first()
    val currentIndex = steps.indexOf(current)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = Strings.stepOf(currentIndex + 1, steps.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
    }
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
        text = Strings.setupBackgroundNote,
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
                    text = step.label(),
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
        text = Strings.stoppedBody,
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
        Text(Strings.start)
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
                Text(
                    text = uptimeLabel(running.sinceEpochMs, now) + "  ·  " + Strings.port(running.port),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { copy(running.url) }) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = Strings.copyAddress,
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
            Text(Strings.open, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Text(Strings.stop)
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
                Text(Strings.failedTitle, style = MaterialTheme.typography.titleSmall)
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
                Text(Strings.tryAgain)
            }
            OutlinedButton(
                onClick = onRepair,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.medium,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(Strings.repair)
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
            Text(Strings.repair)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = Strings.repairExplain,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                    text = Strings.autoOpenLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = Strings.autoOpenHint,
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
        if (info != null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    DetailRow(Strings.detailOpenChamber, info.openChamberVersion ?: Strings.unknownValue)
                    DetailRow(Strings.detailOpenCode, info.openCodeVersion ?: Strings.unknownValue)
                    DetailRow(Strings.detailNode, info.nodeVersion ?: Strings.unknownValue)
                    DetailRow(Strings.detailAlpine, info.alpineVersion)
                    DetailRow(Strings.detailAbi, info.abi)
                    DetailRow(Strings.detailDiskUsed, info.diskUsageBytes?.let(::formatBytes) ?: Strings.unknownValue)
                    DetailRow(Strings.detailFreeSpace, info.freeSpaceBytes?.let(::formatBytes) ?: Strings.unknownValue)
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
                Text(Strings.repair)
            }
            TextButton(
                onClick = onUninstall,
                enabled = actionsEnabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(Strings.uninstall)
            }
        }
    }
}

