package io.andchamber.app.runtime.process

/**
 * Which browser entry a local launch exposes. There is only one plugin transport now; a launch that
 * could not install or configure it exposes nothing. The legacy MCP overlay is never injected.
 */
enum class BrowserControlMode {
    /** Plugin directory synced and its launch environment built. */
    PLUGIN,

    /** Plugin preparation failed; no browser entry is exposed for this launch. */
    NONE,
}
