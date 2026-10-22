package io.andchamber.app.runtime.process

import io.andchamber.app.browser.tool.BrowserToolEndpoint
import java.io.File

/**
 * Owns the loopback tool server for one local launch: binds it, writes the endpoint file, follows
 * presenter transport eligibility, and tears all of it down when the launch ends.
 */
interface BrowserToolHost {
    /**
     * Starts the server for [launchId], writes [endpointFile] atomically once listening, and follows
     * the presenter's eligibility. Returns false when the server or endpoint could not be set up;
     * the launch itself must still proceed.
     */
    fun start(
        launchId: Long,
        home: File,
        trustedRoot: File,
    ): Boolean

    /** Stops the server for [launchId] (if it is still the current one). Idempotent. */
    fun stop(launchId: Long)

    /**
     * Removes the endpoint file only when no installed launch owns it, e.g. one left by an earlier
     * process. The binding is the file's single owner, so this never deletes a launch's live file.
     */
    fun clearStaleEndpoint(
        home: File,
        trustedRoot: File,
    ): Boolean

    /** Used by launches that do not run a local browser tool (tests, non-local runtime). */
    object None : BrowserToolHost {
        override fun start(
            launchId: Long,
            home: File,
            trustedRoot: File,
        ): Boolean = false

        override fun stop(launchId: Long) = Unit

        override fun clearStaleEndpoint(
            home: File,
            trustedRoot: File,
        ): Boolean = BrowserToolEndpoint.delete(BrowserToolEndpoint.hostFile(home), trustedRoot)
    }
}
