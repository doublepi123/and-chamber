package io.andchamber.app.runtime.process

import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserReply
import io.andchamber.app.browser.BrowserRequest
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.browser.provider.ProviderRegistry
import io.andchamber.app.browser.provider.ProviderRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderBindingTest {
    @Test
    fun `an unattached page is unavailable and does not use a legacy controller`() = runTest {
        val presenter = io.andchamber.app.ui.browser.NativeBrowserPresenter(registry = ProviderRegistry())
        val router = ProviderPageRouter(presenter)
        val result = router.execute(request(), presenter.registry, ProviderMode.AGENT, null, 1L) { 0L }

        assertEquals("BROWSER_PAGE_UNAVAILABLE", result.errorCode)
    }

    @Test
    fun `remote origin is ignored and a stopped launch does not keep the token`() {
        val seen = mutableListOf<String>()
        val session = BrowserProviderSession(sink = object : BrowserProviderSink {
            override fun onLocalLaunch(origin: String, token: String, launchId: Long) {
                seen += "$origin:$launchId"
            }

            override fun onLocalStop(launchId: Long) {
                seen += "stop:$launchId"
            }
        })

        val issued = session.rotate(4L, "http://127.0.0.1:3000")
        session.revoke(4L)

        assertEquals(listOf("http://127.0.0.1:3000:4", "stop:4"), seen)
        assertNull(session.currentToken())
        assertEquals(64, issued.token.length)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `binding refuses a non-loopback origin`() = runTest {
        var created = 0
        val binding = LocalBrowserProviderBinding(
            presenter = io.andchamber.app.ui.browser.NativeBrowserPresenter(),
            scope = this,
            clientFactory = { _, _, _ ->
                created += 1
                error("remote provider must not be constructed")
            },
        )

        binding.onLocalLaunch("https://example.com", "token", 1L)

        assertEquals(0, created)
    }

    private fun request() = ProviderRequest("epoch", "connection", "request", "browser.open", emptyMap(), "/root", "session", 1L)

    private class UnusedDriver : BrowserDriver {
        override suspend fun execute(request: BrowserRequest): BrowserReply = error("not used")
    }
}
