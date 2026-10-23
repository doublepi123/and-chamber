package io.andchamber.app.browser

/**
 * Stable contract between the single visible WebView (UI driver) and the agent tool transport that
 * drives it. This package does not own a WebView, a page bridge, or an outbound firewall. Document
 * refs stay in the UI driver; this contract never stores them.
 */
fun interface BrowserDriver {
    suspend fun execute(request: BrowserRequest): BrowserReply
}

enum class BrowserMethod {
    NAVIGATE,
    SNAPSHOT,
    CLICK,
    TYPE,
    SCROLL,
    CAPTURE,
    RESIZE,
}

/**
 * One command. [id] is the caller's idempotency key: a duplicate of an in-flight or completed id
 * is refused and is never retried.
 */
data class BrowserRequest(
    val id: String,
    val method: BrowserMethod,
    val params: Map<String, String> = emptyMap(),
    val timeoutMs: Long? = null,
)

data class BrowserReply(
    val ok: Boolean,
    val result: Map<String, String> = emptyMap(),
    val error: BrowserError? = null,
)

enum class BrowserErrorCode {
    DISABLED,
    NOT_VISIBLE,
    TAKEN_OVER,
    REVOKED,
    BUSY,
    DUPLICATE,
    TIMEOUT,
    OUTCOME_UNKNOWN,
    INVALID_REQUEST,
    UNSUPPORTED_PARAMETER,
    POLICY_DENIED,
    DRIVER,
    INTERNAL,
}

data class BrowserError(
    val code: BrowserErrorCode,
    val message: String,
)
