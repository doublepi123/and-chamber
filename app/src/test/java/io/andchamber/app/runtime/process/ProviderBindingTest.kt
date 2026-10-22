package io.andchamber.app.runtime.process

import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.browser.provider.ProviderRegistry
import io.andchamber.app.browser.provider.ProviderRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderBindingTest {
    @Test
    fun `an unattached page is unavailable and does not use a legacy controller`() = runTest {
        val presenter = io.andchamber.app.ui.browser.NativeBrowserPresenter(registry = ProviderRegistry())
        val router = ProviderPageRouter(presenter)
        val result = router.execute(request(), presenter.registry, ProviderMode.AGENT, null, 1L) { 0L }

        assertEquals("BROWSER_PAGE_UNAVAILABLE", result.errorCode)
    }

    private fun request() = ProviderRequest("epoch", "connection", "request", "browser.open", emptyMap(), "/root", "session", 1L)
}
