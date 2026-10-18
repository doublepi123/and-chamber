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
import io.andchamber.app.runtime.RuntimeInfo
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.ui.AndChamberApp
import io.andchamber.app.ui.home.HomeActions
import io.andchamber.app.ui.home.HomeScreen
import io.andchamber.app.ui.home.LocalRuntimeCard
import io.andchamber.app.ui.home.ServerEditorDialog
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
) {
    AndChamberTheme {
        HomeScreen(
            state = state,
            info = info,
            logLines = PreviewData.log,
            servers = servers,
            actions = noActions,
        )
    }
}

@Preview(name = "Home · running", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeRunningPreview() = HomePreview(PreviewData.running)

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
    )
}
