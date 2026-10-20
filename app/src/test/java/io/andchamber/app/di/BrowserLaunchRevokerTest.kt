package io.andchamber.app.di

import io.andchamber.app.browser.BrowserController
import io.andchamber.app.browser.BrowserDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserLaunchRevokerTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `revoke forwards only the captured launch id`() = runTest {
        val browser = FakeBrowser()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val revoker = BrowserLaunchRevoker(browser, dispatcher, CoroutineScope(dispatcher))

        revoker.revoke(4L)
        revoker.revoke(9L)
        advanceUntilIdle()

        assertEquals(listOf(4L, 9L), browser.revoked)
    }

    private class FakeBrowser : BrowserController {
        val revoked = mutableListOf<Long>()
        override val state: StateFlow<io.andchamber.app.browser.BrowserState>
            get() = kotlinx.coroutines.flow.MutableStateFlow(io.andchamber.app.browser.BrowserState())

        override fun attach(driver: BrowserDriver) = Unit

        override fun detach() = Unit

        override fun setVisible(visible: Boolean) = Unit

        override suspend fun enableFromUi(): Boolean = true

        override suspend fun takeOver() = Unit

        override fun takeOverFromUi() = Unit

        override suspend fun disable() = Unit

        override fun revokeRuntimeLaunch(launchId: Long) {
            revoked += launchId
        }
    }
}
