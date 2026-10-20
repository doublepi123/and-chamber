package io.andchamber.app.runtime.process

/**
 * Which browser entry a local launch may expose. The two tool sets are mutually exclusive.
 * A verified patched OpenChamber artifact selects the native provider. Anything else keeps the
 * existing MCP adapter. There is no mode that registers both.
 */
enum class BrowserControlMode {
    /** Existing `andchamber_browser` MCP overlay. No provider token is injected. */
    LEGACY_MCP,

    /** Native provider env only. The legacy MCP overlay is not injected. */
    NATIVE_PROVIDER,

    /** Browser preparation failed. Neither entry is exposed. */
    NONE,
}

object BrowserControlSelection {
    fun select(patchedArtifactVerified: Boolean, legacyPrepared: Boolean): BrowserControlMode =
        when {
            patchedArtifactVerified -> BrowserControlMode.NATIVE_PROVIDER
            legacyPrepared -> BrowserControlMode.LEGACY_MCP
            else -> BrowserControlMode.NONE
        }
}
