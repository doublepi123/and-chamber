package io.andchamber.app.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Frontend-facing projection of [NativeBrowserPresenter]. It does not own a second tab list.
 * Selecting an existing tab does not navigate. Closing uses the same registry.
 */
internal class BrowserHostSurface(
    private val presenter: NativeBrowserPresenter,
) {
    var opener: (suspend (String, String) -> Unit)? = null

    /** Frontend present creates a tab. The owner is recorded, not granted. */
    fun present(url: String, ownerSessionId: String) {
        if (url.isBlank()) return
        val open = opener ?: return
        CoroutineScope(Dispatchers.Main.immediate).launch {
            open(url, ownerSessionId)
        }
    }

    fun select(tabId: String) {
        presenter.select(tabId)
    }

    fun close(tabId: String) {
        presenter.close(tabId)
    }

    /** A different chat session drops the current grant. It does not grant the new session. */
    fun context(sessionId: String) {
        val state = presenter.state.value
        val owner = state.tabs.firstOrNull { it.id == state.selectedTabId }?.owner
        if (state.agentOn && !owner.isNullOrBlank() && owner != sessionId) {
            presenter.revokeAll("session-changed")
        }
    }

    @Composable
    fun tabsJson(): String {
        val state by presenter.state.collectAsStateWithLifecycle()
        return BrowserHostEvents.tabsJson(state)
    }
}

internal object BrowserHostEvents {
    const val READY = "andchamber-browser-host-ready"
    const val STATE = "andchamber-browser-host-state"

    fun tabsJson(state: NativeBrowserState): String = buildString {
        append('[')
        state.tabs.forEachIndexed { index, tab ->
            if (index > 0) append(',')
            append("{\"tabId\":").append(json(tab.id))
            append(",\"title\":").append(json(tab.title))
            append(",\"url\":").append(json(tab.url))
            append(",\"ownerSessionId\":").append(json(tab.owner))
            append(",\"active\":").append(tab.active)
            append('}')
        }
        append(']')
    }

    fun readyScript(): String =
        "window.dispatchEvent(new Event('${READY}'));"

    fun stateScript(state: NativeBrowserState): String =
        "window.dispatchEvent(new CustomEvent('${STATE}', {detail: ${tabsJson(state)}}));"

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}

@Composable
internal fun PublishHostState(presenter: NativeBrowserPresenter, onScript: (String) -> Unit) {
    val state by presenter.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.tabs, state.selectedTabId, state.shown, state.agentOn) {
        onScript(BrowserHostEvents.stateScript(state))
    }
}
