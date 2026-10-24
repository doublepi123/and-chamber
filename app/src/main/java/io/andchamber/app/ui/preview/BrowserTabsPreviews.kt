package io.andchamber.app.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.andchamber.app.ui.browser.BrowserFloatWindow
import io.andchamber.app.ui.browser.BrowserTabEntry
import io.andchamber.app.ui.browser.BrowserWindowUiState
import io.andchamber.app.ui.theme.AndChamberTheme

/** Placeholder pages only: example.com hosts, no real site, no WebView. */
private val previewTabs = listOf(
    BrowserTabEntry("t1", "Example Domain", "example.com", current = false),
    BrowserTabEntry("t2", "Docs · Getting started with a fairly long page title", "docs.example.com", current = true),
    BrowserTabEntry("t3", "example.org", "example.org", current = false),
)

@Composable
private fun BrowserTabsPreview(tabs: List<BrowserTabEntry>, full: Boolean) {
    AndChamberTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            val ui = remember { BrowserWindowUiState().also { it.fullRequested = full } }
            BrowserFloatWindow(
                ui = ui,
                title = tabs.firstOrNull { it.current }?.title.orEmpty(),
                address = "https://docs.example.com/start?from=preview",
                status = "Agent control",
                agentOn = true,
                canResume = false,
                loading = false,
                desktopOn = false,
                mouseOn = false,
                onResume = {},
                onTakeOver = {},
                onHide = {},
                onClosePage = {},
                onNavigate = {},
                onReload = {},
                onPresentationBlocked = {},
                tabs = tabs,
                onNewTab = {},
            ) { _, _, _ ->
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) { Text("page", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Preview(name = "Browser · three pages, small window", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 760)
@Composable
private fun BrowserThreePagesPreview() = BrowserTabsPreview(previewTabs, full = false)

@Preview(name = "Browser · three pages, full screen (light)", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360, heightDp = 760)
@Composable
private fun BrowserThreePagesFullPreview() = BrowserTabsPreview(previewTabs, full = true)

@Preview(name = "Browser · one page, no counter", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 760)
@Composable
private fun BrowserOnePagePreview() =
    BrowserTabsPreview(listOf(previewTabs[1]), full = false)
