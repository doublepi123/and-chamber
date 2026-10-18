package io.andchamber.app.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract between the runtime layer (PRoot + Alpine + OpenChamber server) and the UI layer.
 * The UI only depends on this file; implementations live in io.andchamber.app.runtime.*.
 */

/** Ordered steps of the first-time on-device setup. */
enum class InstallStep {
    DownloadRootfs,
    ExtractRootfs,
    InstallPackages,
    InstallOpenChamber,
    BuildNativeModules,
    Verify,
}

/** Which part of the lifecycle a failure came from; drives what Retry does. */
enum class FailurePhase {
    /** Download, extraction, packages, npm or verification failed. Retry reinstalls. */
    Install,

    /** The server did not come up. Retry starts it again. */
    Start,

    /** The server was running and then died. Retry starts it again. */
    Runtime,
}

sealed interface RuntimeState {
    /** Initial state, before the on-disk check has finished. */
    data object Detecting : RuntimeState

    /** Device ABI is not arm64-v8a / x86_64. */
    data object UnsupportedAbi : RuntimeState

    data object NotInstalled : RuntimeState

    /** [progress] is 0f..1f for the current step, or null when indeterminate. */
    data class Installing(
        val step: InstallStep,
        val progress: Float?,
        val detail: String?,
    ) : RuntimeState

    data object Stopped : RuntimeState

    data class Starting(val detail: String?) : RuntimeState

    /** [url] is the loopback base URL of the OpenChamber web server, e.g. http://127.0.0.1:3000 */
    data class Running(val url: String, val port: Int, val sinceEpochMs: Long) : RuntimeState

    data class Stopping(val detail: String?) : RuntimeState

    /** [recoverable] true -> offer "Retry/Start", false -> offer "Repair (reinstall)". [phase] says what Retry should redo. */
    data class Failed(
        val reason: String,
        val recoverable: Boolean,
        val phase: FailurePhase = FailurePhase.Runtime,
    ) : RuntimeState
}

data class RuntimeInfo(
    val abi: String,
    val alpineVersion: String,
    val openChamberVersion: String?,
    val openCodeVersion: String?,
    val nodeVersion: String?,
    val diskUsageBytes: Long?,
    val freeSpaceBytes: Long?,
)

interface LocalRuntimeController {
    val state: StateFlow<RuntimeState>

    /** Rolling tail of runtime/installer log lines (most recent last, bounded). */
    val logTail: StateFlow<List<String>>

    val info: StateFlow<RuntimeInfo?>

    fun install()

    fun start()

    fun stop()

    /** Wipe the environment and install again (keeps user workspace when possible). */
    fun repair()

    /** Remove the whole runtime directory. */
    fun uninstall()

    /**
     * Runs one operation inside the foreground service. Called by RuntimeService only, after it has
     * started in the foreground. UI code uses install/start/stop/repair/uninstall above.
     */
    fun perform(operation: RuntimeOperation)
}

/** A server the WebView can open. */
sealed interface ServerTarget {
    val id: String
    val displayName: String
    val baseUrl: String

    data class Local(override val baseUrl: String) : ServerTarget {
        override val id: String = LOCAL_ID
        override val displayName: String = "This device"
    }

    data class Remote(
        override val id: String,
        override val displayName: String,
        override val baseUrl: String,
        /** OpenChamber UI password, if the remote server requires one. Stored encrypted. */
        val uiPassword: String?,
        /**
         * Allow sending the password over plain http to a non-loopback host. Off by default; R3's
         * confirm UI sets it. Without it a password is never sent in cleartext.
         */
        val allowInsecureHttp: Boolean = false,
    ) : ServerTarget

    companion object {
        const val LOCAL_ID = "local"
    }
}

interface RemoteServerStore {
    val servers: StateFlow<List<ServerTarget.Remote>>

    fun upsert(server: ServerTarget.Remote)

    fun remove(id: String)
}

sealed interface WebSessionResult {
    /** Session cookies were placed into android.webkit.CookieManager; load [url]. */
    data class Ready(val url: String) : WebSessionResult

    /** Server reachable but no stored password / wrong password; UI may still load the page
     *  and let OpenChamber show its own login screen. */
    data class NeedsLogin(val url: String) : WebSessionResult

    data class Unreachable(val reason: String) : WebSessionResult
}

interface WebSessionAuthenticator {
    /** Health-checks the target, logs in via POST /auth/session when a password is known,
     *  and injects the session cookie into the WebView CookieManager. */
    suspend fun prepare(target: ServerTarget): WebSessionResult
}
