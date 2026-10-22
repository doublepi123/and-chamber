package io.andchamber.app.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.andchamber.app.R
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** No WebView or page authority here. Only the explicit user button can request core reopening. */
@Composable
internal fun BrowserRetiredPage(onReopen: suspend () -> Unit) {
    val action = remember { RetiredReopenUiState() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Text(stringResource(R.string.browser_retired_page_details),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(
            onClick = { scope.launch(start = CoroutineStart.UNDISPATCHED) { action.request(onReopen) } },
            enabled = !action.busy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.browser_reopen_page)) }
    }
}

/** Duplicate-click lock only. No success state, auto-reopen, grant or failure rewriting. */
internal class RetiredReopenUiState {
    var busy by mutableStateOf(false)
        private set

    suspend fun request(reopen: suspend () -> Unit) {
        if (busy) return
        busy = true
        try { reopen() } finally { busy = false }
    }
}
