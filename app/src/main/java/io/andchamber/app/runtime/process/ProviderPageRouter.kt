package io.andchamber.app.runtime.process

import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderExecutor
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.browser.provider.ProviderRegistry
import io.andchamber.app.browser.provider.ProviderRequest
import io.andchamber.app.browser.provider.ProviderResult
import io.andchamber.app.ui.browser.NativeBrowserPresenter
import kotlinx.serialization.json.JsonObject

/**
 * The provider executor. It does not own a second registry or a second navigation path.
 * Every claimed request goes to the process-level presenter. Until that presenter has a trusted
 * foreground host, the result is unavailable and no invisible page is created.
 */
class ProviderPageRouter(
    private val presenter: NativeBrowserPresenter = NativeBrowserPresenter(),
) : ProviderExecutor {
    /**
     * Compatibility no-op. The floating window must not attach a second driver or load a URL.
     * The presenter owns the only session.
     */
    fun attach(driver: io.andchamber.app.browser.BrowserDriver, present: suspend (String) -> Boolean) = Unit

    fun detach(driver: io.andchamber.app.browser.BrowserDriver) = Unit
    override suspend fun execute(
        request: ProviderRequest,
        registry: ProviderRegistry,
        mode: ProviderMode,
        visibleTabId: String?,
        deadlineElapsed: Long,
        clock: () -> Long,
    ): ProviderResult {
        if (registry !== presenter.registry) {
            return ProviderResult(
                ok = false,
                data = JsonObject(emptyMap()),
                errorCode = ProviderCodes.INVALID_REQUEST,
                message = "registry mismatch",
            )
        }
        return presenter.execute(request, registry, mode, visibleTabId, deadlineElapsed, clock)
    }
}
