package io.andchamber.app.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Frontend-facing projection of [NativeBrowserPresenter]. It does not own a second tab list.
 * Selecting an existing tab does not navigate. Closing uses the same registry.
 */
internal class BrowserHostSurface(
    private val presenter: NativeBrowserPresenter,
) {
    fun present(tabId: String) {
        presenter.select(tabId)
    }

    fun select(tabId: String) {
        presenter.select(tabId)
    }

    fun close(tabId: String) {
        presenter.close(tabId)
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
            append("{\"id\":").append(json(tab.id))
            append(",\"title\":").append(json(tab.title))
            append(",\"url\":").append(json(tab.url))
            append(",\"owner\":").append(json(tab.owner))
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
