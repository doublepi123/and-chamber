package io.andchamber.app.browser

internal object BrowserLimits {
    const val LEASE_MS: Long = 10 * 60 * 1000L
    const val TOKEN_BYTES: Int = 32
    const val DEFAULT_TIMEOUT_MS: Long = 5_000L
    const val NAVIGATE_TIMEOUT_MS: Long = 15_000L
    const val MAX_TIMEOUT_MS: Long = 30_000L

    const val HEADER_LIMIT_BYTES: Int = 16 * 1024
    const val BODY_LIMIT_BYTES: Int = 64 * 1024
    const val RESPONSE_LIMIT_BYTES: Int = 4 * 1024 * 1024
    const val READ_TIMEOUT_MS: Int = 5_000
    const val MAX_CONCURRENT_READS: Int = 4

    const val COMMAND_PATH: String = "/v1/command"
    const val CREDENTIAL_VERSION: Int = 1
    const val MAX_ID_CHARS: Int = 128
    const val MAX_PARAM_ENTRIES: Int = 32
    const val MAX_PARAM_CHARS: Int = 8 * 1024
    const val MAX_SEEN_IDS: Int = 256

    fun timeoutFor(method: BrowserMethod, requested: Long?): Long {
        val fallback = if (method == BrowserMethod.NAVIGATE) NAVIGATE_TIMEOUT_MS else DEFAULT_TIMEOUT_MS
        val asked = requested ?: fallback
        if (asked <= 0L) return fallback
        return minOf(asked, MAX_TIMEOUT_MS)
    }

    fun hasSideEffect(method: BrowserMethod): Boolean =
        method == BrowserMethod.NAVIGATE ||
            method == BrowserMethod.CLICK ||
            method == BrowserMethod.TYPE ||
            method == BrowserMethod.SCROLL
}
