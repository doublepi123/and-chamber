package io.andchamber.app.ui.browser

import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.andchamber.app.R
import kotlinx.coroutines.launch

/**
 * Projection of [NativeBrowserPresenter]. It does not create a page, a registry, or a URL load.
 * The presenter owns the WebView; releasing the AndroidView only detaches it.
 */
@Composable
internal fun BrowserFloatPage(presenter: NativeBrowserPresenter) {
    val state by presenter.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (!state.shown) return
    val tab = state.tabs.firstOrNull { it.id == state.selectedTabId }
    val status = when {
        state.pageError != null -> state.pageError.orEmpty()
        state.agentOn -> stringResource(R.string.browser_status_on)
        else -> stringResource(R.string.browser_float_status_manual)
    }
    BrowserFloatWindow(
        title = tab?.title.orEmpty(),
        status = status,
        agentOn = state.agentOn,
        canAllow = state.hostAttached && tab != null && tab.owner.isNotBlank(),
        onAllow = { scope.launch { presenter.allow() } },
        onTakeOver = { presenter.takeover() },
        onClose = { presenter.close(state.selectedTabId.orEmpty()) },
        onHidden = { presenter.background() },
    ) {
        key(state.viewGeneration) {
            val view = presenter.currentView()
            if (view == null) {
                androidx.compose.material3.Text(stringResource(R.string.browser_unavailable_body))
            } else {
                AndroidView(
                    factory = {
                        view.setOnTouchListener { _, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) presenter.takeover()
                            false
                        }
                        view
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { released ->
                        released.setOnTouchListener(null)
                        (released.parent as? android.view.ViewGroup)?.removeView(released)
                    },
                )
            }
        }
    }
    LaunchedEffect(state.shown) {
        if (!state.shown) presenter.background()
    }
}
