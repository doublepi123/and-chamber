package io.andchamber.app.ui.browser

import io.andchamber.app.browser.provider.ProviderMode

/**
 * What the floating window may render. This is presentation only: no token, no claim nonce, and
 * no page command. [viewGeneration] advances whenever the owned WebView is replaced.
 */
data class NativeBrowserState(
    val tabs: List<NativeBrowserTab> = emptyList(),
    val selectedTabId: String? = null,
    val shown: Boolean = false,
    val mode: ProviderMode = ProviderMode.MANUAL,
    val agentOn: Boolean = false,
    val pageError: String? = null,
    val pageReady: Boolean = false,
    val viewHandle: Int = 0,
    val viewGeneration: Long = 0L,
    val hostAttached: Boolean = false,
    val documentEpoch: Long = 0L,
    val paused: Boolean = false,
    val eligible: Boolean = false,
    /** A provider connection is live. Only a flag: the connection id itself is not exposed. */
    val connected: Boolean = false,
)

data class NativeBrowserTab(
    val id: String,
    val title: String,
    val url: String,
    val owner: String,
    val active: Boolean,
)
