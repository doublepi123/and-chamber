package io.andchamber.app.ui.home

import androidx.compose.ui.res.stringResource
import io.andchamber.app.R
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.andchamber.app.runtime.SharedStorageState
import io.andchamber.app.runtime.SharedStorageStatus
import io.andchamber.app.ui.components.ChamberCard
import io.andchamber.app.ui.components.ConfirmDialog
import io.andchamber.app.ui.components.IconTile
import io.andchamber.app.ui.hasStorageAccess
import io.andchamber.app.ui.rememberCopyAction
import io.andchamber.app.ui.theme.ChamberTextStyles
import io.andchamber.app.ui.theme.ChamberTheme
import io.andchamber.app.ui.theme.MonoFamily
import kotlinx.coroutines.delay

/** How far the folder tree's rail reaches up toward the root folder icon (stops just below it). */
private val RailReach = 6.dp

/** Time the controller gets to pick up newly granted access before the card blames the folder. */
private const val STATUS_SETTLE_MS = 900L

/**
 * "Projects and settings folder": keeps ~/projects and OpenCode's settings in AndChamber/ on
 * shared storage. The switch is the setting; the body explains the current status, and a notice
 * offers a restart when the running server still uses the old folders.
 */
@Composable
internal fun SharedStorageCard(
    state: SharedStorageState,
    serverRunning: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onAllowAccess: () -> Unit,
    onCheckAgain: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.status != SharedStorageStatus.Off
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    val toggle: (Boolean) -> Unit = { on ->
        when {
            on -> onEnabledChange(true)
            // Turning off makes OpenChamber stop seeing the files there, so say so first.
            state.status == SharedStorageStatus.Active -> confirmOff = true
            else -> onEnabledChange(false)
        }
    }

    ChamberCard(modifier = modifier) {
        StorageHeader(status = state.status, enabled = enabled, onToggle = toggle)

        AnimatedVisibility(
            visible = state.restartRequired && serverRunning,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            RestartNotice(
                onRestart = onRestart,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }

        AnimatedContent(
            targetState = state.status,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                    .using(SizeTransform(clip = false) { _, _ -> tween(260) })
            },
            label = "storageBody",
        ) { status ->
            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                when (status) {
                    SharedStorageStatus.Off -> OffBody()
                    SharedStorageStatus.NeedsPermission ->
                        NeedsPermissionBody(onAllowAccess = onAllowAccess, onCheckAgain = onCheckAgain)
                    SharedStorageStatus.Active -> ActiveBody(hostRoot = state.hostRoot)
                }
            }
        }
    }

    if (confirmOff) {
        ConfirmDialog(
            title = stringResource(R.string.storage_off_dialog_title),
            body = stringResource(R.string.storage_off_dialog_body),
            confirmLabel = stringResource(R.string.turn_off),
            onConfirm = { onEnabledChange(false) },
            onDismiss = { confirmOff = false },
        )
    }
}

@Composable
private fun StorageHeader(
    status: SharedStorageStatus,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val target =
        when (status) {
            SharedStorageStatus.Off -> MaterialTheme.colorScheme.onSurfaceVariant
            SharedStorageStatus.NeedsPermission -> ChamberTheme.colors.warning
            SharedStorageStatus.Active -> ChamberTheme.colors.success
        }
    val tone by animateColorAsState(target, label = "storageTone")
    Row(
        // The whole header toggles, so the switch has a large touch target and one label.
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(tint = if (status == SharedStorageStatus.Off) MaterialTheme.colorScheme.onSurfaceVariant else tone) {
            Icon(
                if (status == SharedStorageStatus.Active) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.storage_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    when (status) {
                        SharedStorageStatus.Off -> stringResource(R.string.storage_off)
                        SharedStorageStatus.NeedsPermission -> stringResource(R.string.storage_needs_access)
                        SharedStorageStatus.Active -> stringResource(R.string.storage_on)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = tone,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Composable
private fun RestartNotice(
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One tap is enough; the notice goes away once the server stops.
    var requested by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.restart_to_apply),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            TextButton(
                onClick = {
                    requested = true
                    onRestart()
                },
                enabled = !requested,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) {
                Text(stringResource(R.string.restart))
            }
        }
    }
}

@Composable
private fun OffBody() {
    Text(
        text = codeSpans(stringResource(R.string.storage_intro), stringResource(R.string.storage_folder_name)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Worth knowing before turning it on, not only after.
    Spacer(Modifier.height(10.dp))
    NoteRow(
        icon = Icons.Rounded.Info,
        text = codeSpans(stringResource(R.string.storage_caveat), stringResource(R.string.guest_private_projects), "node_modules"),
    )
}

@Composable
private fun NeedsPermissionBody(
    onAllowAccess: () -> Unit,
    onCheckAgain: () -> Unit,
) {
    // Access can be granted while the folder still can't be created (storage unavailable). Asking
    // for access again wouldn't help then, so say what is actually wrong. Re-checked on resume.
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    val unavailable by produceState(initialValue = false, checks) {
        if (inspection || !hasStorageAccess(context)) {
            value = false
            return@produceState
        }
        // Right after access is granted the status is still catching up (the controller refreshes
        // on resume too), so only conclude the folder is the problem once it has had a moment.
        if (!value) {
            delay(STATUS_SETTLE_MS)
            value = true
        }
    }
    if (unavailable) {
        Text(
            text = codeSpans(stringResource(R.string.storage_unavailable), stringResource(R.string.storage_folder_name)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        FilledTonalButton(
            onClick = onCheckAgain,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.heightIn(min = 44.dp),
        ) {
            Text(stringResource(R.string.check_again))
        }
        return
    }

    // Android 11+ grants "All files access" in system settings; older versions show a dialog.
    val allFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    Text(
        text = if (allFiles) stringResource(R.string.storage_needs_all_files) else stringResource(R.string.storage_needs_legacy),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    FilledTonalButton(
        onClick = onAllowAccess,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Text(stringResource(R.string.allow_access))
        if (allFiles) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
    NoteRow(
        icon = Icons.Rounded.Shield,
        text = AnnotatedString(if (allFiles) stringResource(R.string.storage_others_all_files) else stringResource(R.string.storage_others_legacy)),
    )
}

@Composable
private fun ActiveBody(hostRoot: String) {
    FolderLayout(hostRoot = hostRoot)
    Spacer(Modifier.height(12.dp))
    NoteRow(icon = Icons.Rounded.Lock, text = codeSpans(stringResource(R.string.storage_private), stringResource(R.string.guest_private_projects)))
    Spacer(Modifier.height(6.dp))
    NoteRow(
        icon = Icons.Rounded.Info,
        text = codeSpans(stringResource(R.string.storage_caveat), stringResource(R.string.guest_private_projects), "node_modules"),
    )
}

/** The shared folder and what each part becomes inside OpenChamber, drawn as a small tree. */
@Composable
private fun FolderLayout(hostRoot: String) {
    val copy = rememberCopyAction()
    val rail = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                // Small mono so /storage/emulated/0/AndChamber fits one line on a 360dp phone.
                Text(
                    text = hostRoot,
                    style = ChamberTextStyles.codeSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { copy(hostRoot) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.copy_folder_path),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            FolderRow(name = stringResource(R.string.storage_projects_dir), railColor = rail, first = true, last = false) {
                Text(
                    text = stringResource(R.string.guest_projects),
                    style = ChamberTextStyles.code,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FolderRow(name = stringResource(R.string.storage_opencode_dir), railColor = rail, first = false, last = true) {
                Text(
                    text = stringResource(R.string.storage_opencode_target),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    name: String,
    railColor: Color,
    first: Boolean,
    last: Boolean,
    target: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 30.dp)
                .drawBehind {
                    // Same thin rail as the setup steps: a trunk from the root icon and a short branch.
                    val stroke = 1.5.dp.toPx()
                    val trunk = 9.dp.toPx()
                    val branch = 19.dp.toPx()
                    val (x, branchEnd) =
                        if (layoutDirection == LayoutDirection.Ltr) {
                            trunk to branch
                        } else {
                            (size.width - trunk) to (size.width - branch)
                        }
                    val mid = size.height / 2f
                    val top = if (first) -RailReach.toPx() else 0f
                    drawLine(railColor, Offset(x, top), Offset(x, if (last) mid else size.height), stroke, StrokeCap.Round)
                    drawLine(railColor, Offset(x, mid), Offset(branchEnd, mid), stroke, StrokeCap.Round)
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(26.dp))
        Text(text = name, style = ChamberTextStyles.code, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(10.dp))
        target()
    }
}

@Composable
private fun NoteRow(
    icon: ImageVector,
    text: AnnotatedString,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.5.dp).size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [text] with every occurrence of [tokens] set in the monospace face (paths, folder names). */
private fun codeSpans(
    text: String,
    vararg tokens: String,
): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (token in tokens) {
            var start = text.indexOf(token)
            while (start >= 0) {
                addStyle(SpanStyle(fontFamily = MonoFamily), start, start + token.length)
                start = text.indexOf(token, start + token.length)
            }
        }
    }
