package io.andchamber.app.browser.provider

/**
 * Native OpenChamber browser provider. Transport is SSE plus claim/result.
 * A connection is not authority. Grants bind one runtime launch, connection, session and visible tab.
 * Tokens are constructor input only and must never be copied into state, errors or logs.
 */
interface BrowserProvider {
    suspend fun start()
    suspend fun stop()
    fun grant(sessionId: String, tabId: String)
    fun revoke()
    fun setManual(manual: Boolean)
}

data class ProviderTab(
    val tabId: String,
    val instanceKey: String,
    val directory: String,
    val ownerSessionId: String,
    val url: String,
    val title: String = "",
)

data class ProviderGrant(
    val runtimeLaunchId: Long,
    val connectionId: String,
    val sessionId: String,
    val tabId: String,
    val grantEpoch: Long,
)

enum class ProviderMode {
    MANUAL,
    AGENT,
}

data class ProviderRequest(
    val serverEpoch: String,
    val connectionId: String,
    val requestId: String,
    val action: String,
    val parameters: Map<String, String>,
    val directory: String,
    val sessionId: String?,
    val timeoutMs: Long,
)

/** Page actions the POC may execute. Presenting a tab is not a grant. */
val PROVIDER_SUPPORTED_ACTIONS = listOf(
    "browser.open",
    "browser.snapshot",
    "browser.click",
    "browser.type",
    "browser.scroll",
    "browser.capture",
    "browser.resize",
)

data class ProviderResult(
    val ok: Boolean,
    val data: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    val errorCode: String? = null,
    val message: String? = null,
)

object ProviderCodes {
    const val AUTHORIZATION_REQUIRED = "AUTHORIZATION_REQUIRED"
    const val UNSUPPORTED_ACTION = "UNSUPPORTED_ACTION"
    const val UNSUPPORTED_PARAMETER = "UNSUPPORTED_PARAMETER"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val CLAIM_LOST = "CLAIM_LOST"
    const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
    const val DEADLINE = "DEADLINE"
    const val BUSY = "BUSY"
    const val CANCELLED = "CANCELLED"
    const val BROWSER_PAGE_UNAVAILABLE = "BROWSER_PAGE_UNAVAILABLE"
    const val REF_EXPIRED = "REF_EXPIRED"
}
