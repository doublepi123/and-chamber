package io.andchamber.app.ui.browser

import io.andchamber.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserFloatStatusTest {
    private fun status(
        pageError: String? = null,
        agentOn: Boolean = false,
        pageReady: Boolean = true,
        pageRetired: Boolean = false,
        mouseMode: Boolean = false,
        accessibilityEnabled: Boolean = false,
    ): Int? = browserFloatStatusResource(pageError, agentOn, pageReady, pageRetired, mouseMode, accessibilityEnabled)

    @Test fun `mouse mode with accessibility on shows the hover notice`() {
        assertEquals(R.string.browser_mouse_hover_accessibility, status(mouseMode = true, accessibilityEnabled = true))
    }

    @Test fun `the notice also replaces page loading, so the line does not flip while links load`() {
        assertEquals(R.string.browser_mouse_hover_accessibility,
            status(pageReady = false, mouseMode = true, accessibilityEnabled = true))
    }

    @Test fun `mouse mode off or accessibility off leaves the line exactly as before`() {
        for (ready in listOf(true, false)) for (agent in listOf(true, false)) {
            for ((mouse, a11y) in listOf(false to false, true to false, false to true)) {
                val expected = when {
                    agent -> R.string.browser_status_on
                    !ready -> R.string.browser_page_loading
                    else -> R.string.browser_float_status_manual
                }
                assertEquals("ready=$ready agent=$agent mouse=$mouse a11y=$a11y", expected,
                    status(agentOn = agent, pageReady = ready, mouseMode = mouse, accessibilityEnabled = a11y))
            }
        }
    }

    @Test fun `known internal errors win over the notice`() {
        mapOf(
            "mouse-gesture-interrupted" to R.string.browser_mouse_gesture_interrupted,
            "mode-reopen-required" to R.string.browser_mode_reopen_required,
            "reopen-required" to R.string.browser_reopen_required,
            "Re-open this page before allowing control" to R.string.browser_reopen_required,
            "mouse unsupported" to R.string.browser_mouse_unsupported,
        ).forEach { (error, resource) ->
            assertEquals(error, resource, status(pageError = error, mouseMode = true, accessibilityEnabled = true))
        }
    }

    @Test fun `a website's own error wins and is still shown unchanged`() {
        listOf("", "The certificate isn't trusted", "This website says mouse unsupported").forEach {
            assertNull(it, status(pageError = it, agentOn = true, mouseMode = true, accessibilityEnabled = true))
        }
    }

    @Test fun `agent control on wins over the notice`() {
        assertEquals(R.string.browser_status_on, status(agentOn = true, mouseMode = true, accessibilityEnabled = true))
    }

    @Test fun `a retired page never shows the notice`() {
        // The presenter reports a retired page with its own error; even without one the notice stays off.
        assertEquals(R.string.browser_mouse_gesture_interrupted, status(pageError = "mouse-gesture-interrupted",
            pageReady = false, pageRetired = true, mouseMode = true, accessibilityEnabled = true))
        assertEquals(R.string.browser_page_loading,
            status(pageReady = false, pageRetired = true, mouseMode = true, accessibilityEnabled = true))
    }
}
