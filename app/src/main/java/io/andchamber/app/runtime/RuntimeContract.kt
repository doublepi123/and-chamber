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
        /** Technical detail text (untranslated), e.g. a package command; the step label is localised. */
        val detail: String?,
        /** Localised progress phrase for the current sub-step, when there is one. */
        val messageKey: MessageKey? = null,
    ) : RuntimeState

    data object Stopped : RuntimeState

    data class Starting(val detail: String?) : RuntimeState

    /** [url] is the loopback base URL of the OpenChamber web server, e.g. http://127.0.0.1:3000 */
    data class Running(val url: String, val port: Int, val sinceEpochMs: Long) : RuntimeState

    data class Stopping(val detail: String?) : RuntimeState

    /** [recoverable] true -> offer "Retry/Start", false -> offer "Repair (reinstall)". [phase] says what Retry should redo. */
    /**
     * [reason] is the English, technical text (logs, tests, fallbacks). [messageKey] names the
     * localised headline the UI should show instead; [detail] is raw technical output (logs, command
     * text) and is never translated.
     */
    data class Failed(
        val reason: String,
        val recoverable: Boolean,
        val phase: FailurePhase = FailurePhase.Runtime,
        val messageKey: MessageKey? = null,
        val detail: String? = null,
    ) : RuntimeState
}

/**
 * Keys for user-visible runtime messages. The UI resolves each to a string resource; new messages get
 * a key here and an entry in values/strings.xml and values-zh/strings.xml.
 */
enum class MessageKey {
    /** Network output seen during setup: the Alpine or npm mirrors were not reachable. */
    MirrorsUnreachable,

    /** Linux packages (apk) failed to install. */
    PackagesFailed,

    /** OpenChamber could not be installed from npm. */
    OpenChamberInstallFailed,

    /** The OpenChamber port is held by another app. */
    PortInUse,

    /** OpenCode reported an error while starting. The detail carries its message. */
    OpenCodeStartFailed,

    /** OpenChamber exited while starting. */
    ExitedDuringStart,

    /** OpenChamber did not start listening in time. */
    NotListeningInTime,

    /** OpenChamber did not become ready in time. */
    NotReadyInTime,

    /** OpenChamber stopped on its own and the restart budget is spent. */
    KeptStopping,

    /** OpenChamber stopped unexpectedly. */
    StoppedUnexpectedly,

    /** A restart attempt failed. */
    RestartFailed,

    /** The embedded PRoot launcher is missing from the app. */
    EmbeddedProotMissing,

    /** Generic failure with no more specific message. */
    Generic,

    /** The node-pty terminal module could not be built. */
    NativeBuildFailed,

    /** The rebuilt node-pty module does not work in the guest. */
    PtySmokeFailed,

    /** Install sub-step: unpacking the Linux base. */
    ProgressUnpacking,

    /** Install sub-step: installing system packages. */
    ProgressInstallingPackages,

    /** Install sub-step: installing OpenChamber and OpenCode. */
    ProgressInstallingOpenChamber,

    /** Install sub-step: building terminal support. */
    ProgressBuildingNative,

    /** Install sub-step: checking the installation. */
    ProgressVerifying,

    /** Install sub-step: activating the environment. */
    ProgressActivating,

    /** Install finished. */
    ProgressDone,
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

    /**
     * True while an operation (install, start, stop, repair, uninstall) or an automatic crash
     * recovery is queued or running. The foreground service uses it to decide when work has ended,
     * instead of inferring that from [state].
     */
    val busy: StateFlow<Boolean>

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

    /** Stop and start again as one queued operation (applies new storage bindings). */
    fun restart()

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
    /** Session cookies (if any) were stored in the target's own WebView profile, returned by
     *  [WebSessionCookies]; the UI may load [url]. */
    data class Ready(val url: String) : WebSessionResult

    /** Server reachable but no stored password / wrong password; UI may still load the page
     *  and let OpenChamber show its own login screen. */
    data class NeedsLogin(val url: String) : WebSessionResult

    /** Remote server is protected by an interactive Cloudflare Access gateway. */
    data class NeedsGatewayLogin(val url: String) : WebSessionResult

    data class Unreachable(val reason: String) : WebSessionResult
}

/** The profile a WebView is bound to: one server target at one canonical origin (see canonicalWebOrigin). */
data class WebSessionIdentity(
    val targetId: String,
    val origin: String,
)

/**
 * Cookie capability of one bound WebView profile. Only ever valid for [identity]: a different
 * origin gets no cookies back and nothing is written for it.
 */
interface WebSessionCookies {
    val identity: WebSessionIdentity

    /** The Cookie header the profile would send to [url], or null when [url] is not on [identity]'s origin. */
    suspend fun cookieHeader(url: String): String?

    /** Writes the Set-Cookie [headers] for [url] and returns only after the platform has finished
     *  writing them. Throws when a write fails or [url] is not on [identity]'s origin. */
    suspend fun storeCookies(
        url: String,
        headers: List<String>,
    )
}

interface WebSessionAuthenticator {
    /** Health-checks the target, logs in via POST /auth/session when a password is known, and stores
     *  the session cookies through [cookies] (the capability of the target's bound profile). */
    suspend fun prepare(
        target: ServerTarget,
        cookies: WebSessionCookies,
    ): WebSessionResult
}
