package io.andchamber.app.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.andchamber.app.runtime.FailurePhase
import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.MessageKey
import io.andchamber.app.runtime.RuntimeInfo
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.RuntimeUpdate
import io.andchamber.app.runtime.RuntimeUpdateState
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.SharedStorageState
import io.andchamber.app.runtime.SharedStorageStatus
import io.andchamber.app.runtime.UpdateFailure
import io.andchamber.app.runtime.VersionChange
import io.andchamber.app.ui.AndChamberApp
import io.andchamber.app.ui.LaunchCoverContent
import io.andchamber.app.ui.home.AutoOpenRow
import io.andchamber.app.ui.home.HomeActions
import io.andchamber.app.ui.home.HomeScreen
import io.andchamber.app.ui.home.LocalRuntimeCard
import io.andchamber.app.ui.home.ServerEditorDialog
import io.andchamber.app.ui.home.SharedStorageCard
import io.andchamber.app.ui.home.UpdateCheckRow
import io.andchamber.app.ui.theme.AndChamberTheme
import io.andchamber.app.ui.web.ConnectingPanel
import io.andchamber.app.ui.web.LocalServerPanel
import io.andchamber.app.ui.web.PanelAction
import io.andchamber.app.ui.web.StatusPanel

private val noActions =
    HomeActions(
        onSetUp = {},
        onStart = {},
        onRetry = {},
        onStop = {},
        onRepair = {},
        onUninstall = {},
        onOpenLocal = {},
        onOpenRemote = {},
        onSaveRemote = {},
        onRemoveRemote = {},
    )

@Composable
private fun HomePreview(
    state: RuntimeState,
    info: RuntimeInfo? = PreviewData.info,
    servers: List<ServerTarget.Remote> = PreviewData.servers,
    storage: SharedStorageState? = PreviewData.storage(SharedStorageStatus.Off),
    updates: RuntimeUpdateState = RuntimeUpdateState(),
) {
    AndChamberTheme {
        HomeScreen(
            state = state,
            info = info,
            logLines = PreviewData.log,
            servers = servers,
            actions = noActions,
            storage = storage,
            updates = updates,
        )
    }
}

/** Preview-only update data; versions match [PreviewData.info] (2.4.0 / 2.0.32). */
private object UpdatePreviewData {
    val available =
        RuntimeUpdate(
            openChamber = VersionChange(current = "2.4.0", target = "2.5.0"),
            openCode = VersionChange(current = "2.0.32", target = "2.1.0"),
        )

    val checkedAt = System.currentTimeMillis() - 42 * 60 * 1000L

    val offered = RuntimeUpdateState(available = available, lastCheckedAtMs = checkedAt)

    val failed =
        offered.copy(
            lastFailure =
                UpdateFailure(
                    reason = "Unable to install OpenChamber from npm. (exit 1)",
                    messageKey = MessageKey.OpenChamberInstallFailed,
                    detail =
                        "npm error code ETIMEDOUT\n" +
                            "npm error network request to https://registry.npmjs.org/@openchamber%2fweb failed\n" +
                            "npm error network This is a problem related to network connectivity.",
                ),
        )

    val updating =
        RuntimeState.Installing(
            step = InstallStep.BuildNativeModules,
            progress = null,
            detail = "node-pty for musl",
            updating = true,
        )
}

@Preview(name = "Home · update available", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeUpdateAvailablePreview() = HomePreview(PreviewData.running, updates = UpdatePreviewData.offered)

@Preview(name = "Home · update available (light, 360dp)", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360)
@Composable
private fun HomeUpdateAvailableLightPreview() =
    HomePreview(
        RuntimeState.Stopped,
        // Only OpenChamber changes: only its line shows.
        updates =
            UpdatePreviewData.offered.copy(
                available = UpdatePreviewData.available.copy(openCode = VersionChange(current = "2.0.32", target = "2.0.32")),
            ),
    )

@Preview(name = "Home · updating", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 900)
@Composable
private fun HomeUpdatingPreview() = HomePreview(UpdatePreviewData.updating, updates = UpdatePreviewData.offered)

@Preview(name = "Home · update failed", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 900)
@Composable
private fun HomeUpdateFailedPreview() = HomePreview(PreviewData.running, updates = UpdatePreviewData.failed)

@Preview(name = "Home · update failed (light)", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun HomeUpdateFailedLightPreview() =
    HomePreview(
        RuntimeState.Stopped,
        // No offer left to install, so no Try again: the reason behind Details, and Dismiss.
        updates =
            RuntimeUpdateState(
                lastFailure =
                    UpdateFailure(
                        reason = "Not enough free space to update: need 1400000000 bytes, have 900000000",
                        messageKey = MessageKey.UpdateNotEnoughSpace,
                    ),
            ),
    )

@Composable
private fun UpdateCheckPreview(updates: RuntimeUpdateState) {
    AndChamberTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            UpdateCheckRow(updates = updates, enabled = true, onCheck = {}, modifier = Modifier.padding(16.dp))
        }
    }
}

@Preview(name = "Details · checking for updates", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360)
@Composable
private fun UpdateCheckingPreview() = UpdateCheckPreview(RuntimeUpdateState(checking = true, lastCheckedAtMs = UpdatePreviewData.checkedAt))

@Preview(name = "Details · up to date", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360)
@Composable
private fun UpdateUpToDatePreview() = UpdateCheckPreview(RuntimeUpdateState(lastCheckedAtMs = UpdatePreviewData.checkedAt))

@Preview(name = "Details · update check failed (light)", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360)
@Composable
private fun UpdateCheckFailedPreview() =
    UpdateCheckPreview(RuntimeUpdateState(checkFailed = true, lastCheckedAtMs = UpdatePreviewData.checkedAt))

@Preview(name = "Web · updating", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WebUpdatingPreview() {
    AndChamberTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            LocalServerPanel(state = UpdatePreviewData.updating, onStart = {}, onHome = {})
        }
    }
}

@Preview(name = "Home · running", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeRunningPreview() = HomePreview(PreviewData.running)

@Preview(name = "Home · shared folder on", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 1000)
@Composable
private fun HomeSharedActivePreview() =
    HomePreview(PreviewData.running, storage = PreviewData.storage(SharedStorageStatus.Active, restartRequired = true))

@Composable
private fun StoragePreview(
    state: SharedStorageState,
    serverRunning: Boolean = true,
) {
    AndChamberTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                SharedStorageCard(
                    state = state,
                    serverRunning = serverRunning,
                    onEnabledChange = {},
                    onAllowAccess = {},
                    onCheckAgain = {},
                    onRestart = {},
                )
            }
        }
    }
}

@Preview(name = "Storage · off", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StorageOffPreview() = StoragePreview(PreviewData.storage(SharedStorageStatus.Off))

@Preview(name = "Storage · needs access (light)", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun StorageNeedsAccessPreview() = StoragePreview(PreviewData.storage(SharedStorageStatus.NeedsPermission))

@Preview(name = "Storage · on", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StorageActivePreview() = StoragePreview(PreviewData.storage(SharedStorageStatus.Active))

@Preview(name = "Storage · on, restart needed (light)", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun StorageRestartPreview() =
    StoragePreview(PreviewData.storage(SharedStorageStatus.Active, restartRequired = true))

@Preview(name = "Home · running (light)", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun HomeRunningLightPreview() = HomePreview(PreviewData.running)

@Preview(name = "Home · first run", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeFirstRunPreview() = HomePreview(RuntimeState.NotInstalled, info = null, servers = emptyList())

@Preview(name = "Home · installing", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 900)
@Composable
private fun HomeInstallingPreview() = HomePreview(PreviewData.installing, info = null)

@Preview(name = "Home · stopped (light)", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun HomeStoppedPreview() = HomePreview(RuntimeState.Stopped)

@Composable
private fun CardPreview(state: RuntimeState) {
    AndChamberTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                LocalRuntimeCard(
                    state = state,
                    info = PreviewData.info,
                    logLines = PreviewData.log,
                    onSetUp = {},
                    onStart = {},
                    onStop = {},
                    onOpen = {},
                    onRetry = {},
                    onRepair = {},
                    onUninstall = {},
                )
            }
        }
    }
}

@Preview(name = "Card · failed (recoverable)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CardFailedPreview() =
    CardPreview(RuntimeState.Failed("OpenChamber stopped unexpectedly (exit code 1)", recoverable = true))

@Preview(name = "Card · failed (needs repair)", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun CardBrokenPreview() =
    CardPreview(RuntimeState.Failed("node: not found. The Linux system looks damaged.", recoverable = false))

@Preview(name = "Card · starting", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CardStartingPreview() = CardPreview(RuntimeState.Starting("Starting OpenChamber on port 3000"))

@Preview(name = "Card · unsupported", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CardUnsupportedPreview() = CardPreview(RuntimeState.UnsupportedAbi)

@Preview(name = "Server editor", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ServerEditorPreview() {
    AndChamberTheme {
        ServerEditorDialog(initial = PreviewData.servers.first(), onSave = {}, onDismiss = {})
    }
}

@Preview(name = "Web · connecting", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WebConnectingPreview() {
    AndChamberTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ConnectingPanel(name = "Workstation", address = "192.168.1.20:3000", onBack = {})
        }
    }
}

@Preview(name = "Web · unreachable", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WebUnreachablePreview() {
    AndChamberTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            StatusPanel(
                icon = Icons.Rounded.CloudOff,
                title = "Can't reach Workstation",
                body = "Make sure the server is running and this phone can reach it, for example on the same Wi-Fi.",
                detail = "No OpenChamber server answered at http://192.168.1.20:3000",
                primary = PanelAction("Try again") {},
                secondary = PanelAction("Back") {},
            )
        }
    }
}

@Preview(name = "Launch cover", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LaunchCoverPreview() {
    AndChamberTheme { LaunchCoverContent(markShown = true, slow = false) }
}

@Preview(name = "Launch cover · slow check (light)", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun LaunchCoverSlowPreview() {
    AndChamberTheme { LaunchCoverContent(markShown = true, slow = true) }
}

@Preview(name = "Web · start failed after launch", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WebStartFailedPreview() {
    AndChamberTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            LocalServerPanel(
                state =
                    RuntimeState.Failed(
                        reason = "OpenChamber did not answer on port 3000 within 90 s",
                        recoverable = true,
                        phase = FailurePhase.Start,
                    ),
                onStart = {},
                onHome = {},
            )
        }
    }
}

@Preview(name = "Details · open on launch", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AutoOpenRowPreview() {
    AndChamberTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            AutoOpenRow(checked = true, onCheckedChange = {}, modifier = Modifier.padding(16.dp))
        }
    }
}

@Preview(name = "Web · local stopped (light)", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun WebLocalStoppedPreview() {
    AndChamberTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            LocalServerPanel(state = RuntimeState.Stopped, onStart = {}, onHome = {})
        }
    }
}

@Preview(name = "App · fakes", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppPreview() {
    AndChamberApp(
        runtime = FakeRuntimeController(initial = PreviewData.running),
        remotes = FakeRemoteServerStore(),
        auth = FakeWebSessionAuthenticator(),
        storage = FakeSharedStorageController(),
    )
}
