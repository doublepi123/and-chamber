package io.andchamber.app.browser

internal object BrowserLimits {
    /** Timeout for one page action. The tool transport applies its own tool-level deadline. */
    const val DEFAULT_TIMEOUT_MS: Long = 5_000L
}
