package io.andchamber.app.browser.provider

/**
 * Shared selector shape between the presenter, the page adapter and the plugin tool server.
 * The SSE claim/result wire format is gone: the plugin tool server replaced it, so only the
 * ref-selector parsing every page action needs remains here.
 */
object ProviderProtocol {
    private val REF_SELECTOR = Regex("^\\[data-andchamber-ref=\"([A-Za-z0-9_-]{1,80})\"\\]$")

    fun selectorRef(selector: String?): String? = selector?.let { REF_SELECTOR.matchEntire(it)?.groupValues?.get(1) }
}
