package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.notify.NotificationPayload
import io.andchamber.app.runtime.install.InstalledRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.ArrayDeque

/** Runs and stops the OpenChamber server process. */
interface OpenChamberServer {
    /**
     * Launches the server and waits until it is ready (see [Readiness]). Throws on any failure and
     * whenever [isCancelled] returns true, so a Stop never queues behind the readiness wait.
     * Returns the launch id of the child that became ready.
     */
    fun start(
        runtime: InstalledRuntime,
        uiPassword: String,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): Long

    /** Stops every process of this server, owned or from our handle. Idempotent. */
    fun stop()

    fun isAlive(): Boolean

    /**
     * The launch id, but only while that launch is trustworthy right now: our child printed the listen
     * line, the child process is alive, and nothing has stopped it. Null otherwise. A login may only
     * proceed while this returns the same non-null id before and right before the POST.
     */
    fun readyLaunchId(): Long?
}

/** Thrown when the port is held by something other than our server. Never launch/login then. */
class PortInUseException(
    port: Int,
) : IllegalStateException("Port $port is in use by another app")

/**
 * Launches `node cli.js serve` inside PRoot. Ported from AndCode's LocalRuntimeProcessLauncher (MIT),
 * reduced to the single OpenChamber server.
 */
class ProotOpenChamberServer(
    private val paths: RuntimePaths,
    private val suite: () -> EmbeddedCommandSuite.Paths,
    private val health: HealthProbe,
    private val scanner: ProcessScanner,
    private val signal: (Long) -> Unit = { pid -> android.os.Process.killProcess(pid.toInt()) },
    private val readyTimeoutMillis: Long = READY_TIMEOUT_MILLIS,
    private val pollMillis: Long = POLL_MILLIS,
    private val maxLogBytes: Long = 1_048_576L,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val now: () -> Long = System::nanoTime,
    /** Rewrites the guest resolv.conf from the current network; networks change between runs. */
    private val refreshDns: (InstalledRuntime) -> Unit = {},
    /** Live DNS refresh while the server runs: started once it is ready, stopped before any teardown. */
    private val dnsMonitor: io.andchamber.app.runtime.network.RuntimeDnsMonitor =
        io.andchamber.app.runtime.network.RuntimeDnsMonitor.None,
    /** Pre-start cleanup; returns true when the port is free afterwards. */
    private val cleanBeforeStart: (port: Int, guestRunDir: File, prootPath: String) -> Boolean = { _, _, _ -> true },
    /** The bind set for this launch (shared storage decision). Defaults to private-only. */
    private val bindsForStart: () -> io.andchamber.app.runtime.storage.GuestBinds = {
        io.andchamber.app.runtime.storage.GuestBinds.privateOnly(paths.home, paths.workspace)
    },
    /** Told the binds a launch actually started with, so restartRequired can compare against it. */
    private val onStarted: (io.andchamber.app.runtime.storage.GuestBinds) -> Unit = {},
    /** Told when the server stops, so nothing is marked as needing a restart. */
    private val onStopped: () -> Unit = {},
    /** Receives raw `[OpenChamberDesktopNotify] ` lines, which carry user text and never reach the log. */
    private val notificationSink: (line: String) -> Unit = {},
    /**
     * Reads the two packaged plugin files. Production passes the asset reader; tests pass fixtures.
     * A missing or blank asset degrades this launch: no endpoint env and no config overlay. It does
     * not fail the OpenChamber start.
     */
    private val browserPluginAssets: () -> BrowserPluginAssets.Assets = { error("browser-tool assets unavailable") },
    /**
     * App-private boundary. Ancestors of this directory may be symlinks; only the suffix below it
     * is checked without following links. Production passes `context.filesDir`.
     */
    private val browserTrustedRoot: File = paths.root.parentFile ?: paths.root,
    /** Receives a short, non-sensitive reason when browser control is skipped for this launch. */
    private val onBrowserUnavailable: (String) -> Unit = {},
    /**
     * The plugin tool server for local launches. It listens only after this server is ready, follows
     * presenter transport eligibility, and is stopped with the launch.
     */
    private val browserTool: BrowserToolHost = BrowserToolHost.None,
    /**
     * Phone control is off by default. DI passes true together with the asset reader and host; when
     * false this launch injects no phone plugin, endpoint or environment at all. The browser entry
     * is unaffected either way.
     */
    private val phonePluginEnabled: Boolean = false,
    /**
     * Sampled exactly once at the start of every launch; the constructor Boolean is only its
     * default. One long-lived server can follow a mutable enable switch without being rebuilt.
     * A change affects only the next start, never a start that is already in progress.
     */
    private val phonePluginEnabledNow: () -> Boolean = { phonePluginEnabled },
    /**
     * Reads the two packaged phone plugin files. A missing or blank asset degrades only the phone
     * entry for this launch; it never fails the OpenChamber start and never disables the browser.
     */
    private val phonePluginAssets: () -> PhonePluginAssets.Assets = { error("phone-tool assets unavailable") },
    /** The loopback phone tool server for this launch. Off by default. */
    private val phoneTool: PhoneToolHost = PhoneToolHost.None,
    /** Receives a short, non-sensitive reason when phone control is skipped for this launch. */
    private val onPhoneUnavailable: (String) -> Unit = {},
    /** Spawns the guest command. Injected so tests can stand in a fake child process. */
    private val spawn: (command: List<String>, env: Map<String, String>, dir: File) -> Process = { command, env, dir ->
        ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(env)
            }
            .start()
    },
) : OpenChamberServer {
    private val lock = Any()
    private var process: Process? = null
    private var launchId = 0L

    /** The current launch's listen signal: completed by the output pump when our child prints the line. */
    private var listenSignal: CompletableDeferred<Unit> = CompletableDeferred()

    /** The last [DIAGNOSTIC_LINES] lines of our child's output, for error messages. Bounded. */
    private val recentOutput = ArrayDeque<String>()

    /** Set when our child for the current launch is known to be dead or stopped. Guarded by [lock]. */
    private var readyId: Long? = null

    /** Launch whose browser lease was already revoked. Stops a death callback from revoking the next one. */
    private var browserRevokedLaunch = 0L

    /** Launch whose phone grant was already dropped. Independent from the browser watermark. */
    private var phoneRevokedLaunch = 0L

    override fun readyLaunchId(): Long? =
        synchronized(lock) {
            val id = readyId ?: return@synchronized null
            if (process?.isAlive == true) id else null
        }

    override fun isAlive(): Boolean = synchronized(lock) { process?.isAlive == true }

    override fun start(
        runtime: InstalledRuntime,
        uiPassword: String,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        // One sample per launch. Asset sync, env and the listener all use this exact value, so a
        // switch flipped mid-start can never produce a half-phone launch.
        val phoneEnabled = runCatching { phonePluginEnabledNow() }.getOrDefault(false)
        stop()
        val port = runtime.metadata.port
        // OpenChamber's own data dir inside the guest. home is bind-mounted to /root (see GuestBinds).
        val guestRunDir = File(paths.home, ".config/openchamber/run")
        // Port ownership is decided before anything is spawned: a foreign listener that survives our
        // cleanup means we must not launch, and must never send the password to it.
        val prootPath = runCatching { suite().proot.absolutePath }.getOrNull()
            ?: error("Embedded PRoot is unavailable")
        if (!cleanBeforeStart(port, guestRunDir, prootPath)) throw PortInUseException(port)
        // readyLaunchId stays null from stop() until this child is ready, so the controller refuses
        // a new enable for the whole prepare. Revoke first; a failed file delete must not skip it.
        // Delete only while this start still owns the slot. A newer start may already have revoked.
        revokeBrowserLease(synchronized(lock) { launchId })
        revokePhoneLease(synchronized(lock) { launchId })
        // The binding owns the shared endpoint file. Revoking the previous launch above already
        // removed its file; this only clears a stale file that no installed launch owns, so a
        // concurrent newer launch's endpoint is never deleted here.
        val launchSlotEmpty = synchronized(lock) { process == null && readyId == null }
        val endpointCleared = launchSlotEmpty &&
            browserTool.clearStaleEndpoint(paths.home, browserTrustedRoot)
        val phoneEndpointCleared = phoneEnabled && launchSlotEmpty &&
            phoneTool.clearStaleEndpoint(paths.home, browserTrustedRoot)
        runCatching { refreshDns(runtime) }
        // Best effort: an install from before the policy existed gets it here, and a failure must not block
        // the start. Without it OpenChamber offers its own OpenCode/OpenChamber update paths.
        runCatching { OpenChamberPolicy.write(runtime.rootfs) }
            .onFailure { onLog("Could not write the OpenChamber policy file: ${it.message}") }
        val resolved = suite()
        // Upgrade cleanup runs once per start and only touches the old browser-control artefacts.
        runCatching { BrowserPluginAssets.cleanupLegacy(paths.home, browserTrustedRoot) }
        openChamberSettingsDefault()
        val pluginMode = prepareBrowserPlugin(endpointCleared)
        val phoneMode = phoneEnabled && preparePhonePlugin(phoneEndpointCleared)

        paths.logs.mkdirs()
        paths.workspace.mkdirs()
        paths.prootTmp.mkdirs()
        truncateLogFile(paths.serverLog, maxLogBytes)

        val binds = bindsForStart()
        val command =
            ProotCommands.serverCommand(
                proot = resolved.proot,
                rootfs = runtime.rootfs,
                binds = binds,
                extraBinds = emptyList(),
                port = port,
            )
        val suiteEnvironment = resolved.environment()
        val pluginEnvironment =
            if (pluginMode == BrowserControlMode.PLUGIN) {
                val environment = BrowserPluginAssets.launchEnvironment(suiteEnvironment[BrowserPluginAssets.CONFIG_ENV])
                if (environment == null) {
                    onBrowserUnavailable("browser-tool skipped: invalid existing OPENCODE_CONFIG_CONTENT")
                    emptyMap()
                } else {
                    environment
                }
            } else {
                emptyMap()
            }
        // Serial merge, independent failure: the phone overlay starts from the browser-merged config
        // (or the user's original when the browser entry was skipped), so both plugin directories
        // survive. A phone failure leaves the browser overlay exactly as it was.
        val phoneEnvironment =
            if (phoneMode) {
                val existingConfig = pluginEnvironment[PhonePluginAssets.CONFIG_ENV]
                    ?: suiteEnvironment[PhonePluginAssets.CONFIG_ENV]
                val environment = PhonePluginAssets.launchEnvironment(existingConfig)
                if (environment == null) {
                    onPhoneUnavailable("phone-tool skipped: invalid existing OPENCODE_CONFIG_CONTENT")
                    emptyMap()
                } else {
                    environment
                }
            } else {
                emptyMap()
            }
        val env = ProotCommands.serverEnvironment(
            suiteEnvironment,
            paths.prootTmp,
            uiPassword,
            browserTool = pluginEnvironment,
            phoneTool = phoneEnvironment,
        )
        val signal = CompletableDeferred<Unit>()
        val started = spawn(command, env, paths.root)
        val id =
            synchronized(lock) {
                process = started
                recentOutput.clear()
                readyId = null
                listenSignal = signal
                ++launchId
            }
        val pump = pumpOutput(started, id, port, signal, onLog)
        try {
            waitUntilReady(started, port, isCancelled, signal, pump)
        } catch (error: Throwable) {
            stop()
            throw error
        }
        val stillCurrent = synchronized(lock) {
            // Only the launch that is still the current child becomes trusted.
            if (process === started) {
                readyId = id
                true
            } else {
                false
            }
        }
        if (stillCurrent && pluginMode == BrowserControlMode.PLUGIN && pluginEnvironment.isNotEmpty()) {
            val running =
                runCatching { browserTool.start(id, paths.home, browserTrustedRoot) }.getOrDefault(false)
            if (!running) onBrowserUnavailable("browser-tool server did not start")
        }
        if (stillCurrent && phoneMode && phoneEnvironment.isNotEmpty()) {
            val running =
                runCatching { phoneTool.start(id, paths.home, browserTrustedRoot) }.getOrDefault(false)
            if (!running) onPhoneUnavailable("phone-tool server did not start")
        }
        // Watch the network only for a server that is ready. A failure here is reported by the monitor
        // itself; it must not fail a start that already succeeded.
        runCatching { dnsMonitor.start(runtime.rootfs) }
        try {
            onStarted(binds)
        } catch (error: Throwable) {
            // A failed post-start hook must not leave the network watch registered for a dead start.
            stop()
            throw error
        }
        return id
    }

    override fun stop() {
        val current =
            synchronized(lock) {
                readyId = null
                process.also { process = null }
            }
        // Outside the lock: the callback may hop to Main and must not deadlock a UI caller.
        val stoppedLaunch = synchronized(lock) { launchId }
        revokeBrowserLease(stoppedLaunch)
        revokePhoneLease(stoppedLaunch)
        // Stop the DNS monitor first, so no network refresh can write after the server is torn down.
        // It is idempotent, and a failed start (waitUntilReady throws) also comes through here.
        runCatching { dnsMonitor.stop() }
        // Owned-only: our handle's tree plus every runtime PRoot tree we can prove is ours. Uses the
        // proot path from the suite when available; stop must still work if the suite is missing.
        val proot = runCatching { suite().proot.absolutePath }.getOrNull()
        val roots =
            proot?.let { scanner.ownedRoots(prootPath = it, rootfsPath = paths.rootfs.absolutePath) }.orEmpty()
        terminateProcessTree(current, scanner, roots, signal)
        onStopped()
    }

    /**
     * Reads our child's output. The listen line completes [signal]; every line goes to the bounded
     * diagnostic tail and the log. Nothing else is retained, so memory does not grow with uptime.
     */
    /**
     * Revokes [launch] at most once, and never while [lock] is held. A late pump from an older launch
     * is a no-op once that launch was revoked by stop. A newer launch is not consumed by that call.
     */
    private fun revokeBrowserLease(launch: Long) {
        val should = synchronized(lock) {
            if (launch <= browserRevokedLaunch) {
                false
            } else {
                browserRevokedLaunch = launch
                true
            }
        }
        if (should) {
            // The binding deletes the endpoint only when this launch still owns it; the routed
            // stale clear only removes a file no installed launch owns.
            runCatching { browserTool.stop(launch) }
            runCatching { browserTool.clearStaleEndpoint(paths.home, browserTrustedRoot) }
        }
    }

    /**
     * Writes the OpenChamber "agent web tool" default before start. A present value is respected; a
     * missing key becomes false. Any skip is logged with a fixed reason and never fails the start.
     */
    private fun openChamberSettingsDefault() {
        val skipped = runCatching {
            BrowserPluginAssets.ensureDefaultAgentWebTool(paths.home, browserTrustedRoot)
        }.getOrElse { error ->
            onBrowserUnavailable("openchamber settings skipped: ${error.javaClass.simpleName}")
            return
        }
        if (skipped != null) onBrowserUnavailable("openchamber settings skipped: $skipped")
    }

    private fun prepareBrowserPlugin(endpointCleared: Boolean): BrowserControlMode =
        runCatching {
            check(endpointCleared) { "browser-tool endpoint was not cleared" }
            val assets = browserPluginAssets()
            BrowserPluginAssets.sync(paths.home, assets, browserTrustedRoot)
        }.fold(
            onSuccess = { BrowserControlMode.PLUGIN },
            onFailure = { error ->
                onBrowserUnavailable("browser-tool skipped: ${error.javaClass.simpleName}")
                BrowserControlMode.NONE
            },
        )

    /**
     * Revokes the phone grant for [launch] at most once and stops its server. A late pump from an
     * older launch is a no-op once that launch was revoked by stop; a newer launch is not consumed.
     */
    private fun revokePhoneLease(launch: Long) {
        val should = synchronized(lock) {
            if (launch <= phoneRevokedLaunch) {
                false
            } else {
                phoneRevokedLaunch = launch
                true
            }
        }
        if (should) {
            // The binding deletes the endpoint only when this launch still owns it; the routed
            // stale clear only removes a file no installed launch owns. Its stop also revokes the
            // controller grant synchronously.
            runCatching { phoneTool.stop(launch) }
            runCatching { phoneTool.clearStaleEndpoint(paths.home, browserTrustedRoot) }
        }
    }

    private fun preparePhonePlugin(endpointCleared: Boolean): Boolean =
        runCatching {
            check(endpointCleared) { "phone-tool endpoint was not cleared" }
            val assets = phonePluginAssets()
            PhonePluginAssets.sync(paths.home, assets, browserTrustedRoot)
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                onPhoneUnavailable("phone-tool skipped: ${error.javaClass.simpleName}")
                false
            },
        )

    private fun pumpOutput(
        process: Process,
        launch: Long,
        port: Int,
        signal: CompletableDeferred<Unit>,
        onLog: (String) -> Unit,
    ): Thread =
        Thread({
            val writer = BoundedLogWriter(paths.serverLog, maxLogBytes)
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    // Desktop-notify lines carry user text: hand them to the sink, keep them out of the
                    // log, the diagnostic tail and the readiness signal.
                    if (line.startsWith(NotificationPayload.PREFIX)) {
                        runCatching { notificationSink(line) }
                        return@forEachLine
                    }
                    synchronized(lock) {
                        recentOutput.addLast(line)
                        while (recentOutput.size > DIAGNOSTIC_LINES) recentOutput.removeFirst()
                    }
                    if (line.contains(Readiness.listenMarker(port))) signal.complete(Unit)
                    writer.appendLine(line)
                    onLog(line)
                }
            } catch (_: java.io.IOException) {
                // Stop/destroy closes the pipe under us (InterruptedIOException on Android): normal end.
            } catch (error: Throwable) {
                // An uncaught throwable on this thread kills the whole app; log and end the pump instead.
                runCatching { onLog("Output reader stopped: ${error.message}") }
            } finally {
                // Only this launch's own death drops trust. A previous pump must not revoke the next launch.
                val current = synchronized(lock) {
                    if (this.process === process && launchId == launch) {
                        readyId = null
                        true
                    } else {
                        false
                    }
                }
                // readyLaunchId() may already have revoked this death. Do not revoke again, and never
                // revoke a launch that replaced this process.
                if (current) {
                    revokeBrowserLease(launch)
                    revokePhoneLease(launch)
                }
            }
        }, "openchamber-output").apply { isDaemon = true; start() }

    /**
     * Readiness in trust order: the child printed the listen line (signal), the child is alive, then
     * /health is believed (ok, OpenCode ready, serverId stable). A child that exits before listening
     * because the port was taken fails as port in use.
     */
    private fun waitUntilReady(
        started: Process,
        port: Int,
        isCancelled: () -> Boolean,
        signal: CompletableDeferred<Unit>,
        pump: Thread,
    ) {
        val deadline = now() + readyTimeoutMillis * 1_000_000L
        val serverIds = mutableListOf<String?>()
        var listening = false
        while (true) {
            if (isCancelled()) throw CancellationException("Start cancelled")
            if (!started.isAlive) {
                // Let the pump drain the exited child's last lines before deciding.
                pump.join(2_000)
                val output = synchronized(lock) { recentOutput.toList() }
                if (!listening && output.any { it.contains("EADDRINUSE") }) throw PortInUseException(port)
                error("OpenChamber exited during startup (exit ${started.exitValue()}): ${tail()}")
            }
            if (!listening) listening = signal.isCompleted
            if (!listening) {
                if (now() >= deadline) error("OpenChamber did not start listening on port $port: ${tail()}")
                sleep(pollMillis)
                continue
            }
            val report = health.read(port)
            serverIds += report?.serverId
            check(Readiness.identityStable(serverIds)) {
                "Another OpenChamber answered on port $port during startup; refusing to continue"
            }
            when (val verdict = Readiness.verdict(report)) {
                Readiness.Verdict.Ready -> return
                is Readiness.Verdict.OpenCodeFailed ->
                    error("OpenCode backend failed to start: ${verdict.error}")
                Readiness.Verdict.Waiting -> Unit
            }
            if (now() >= deadline) {
                error("OpenChamber did not become ready on port $port within ${readyTimeoutMillis / 1000}s: ${tail()}")
            }
            sleep(pollMillis)
        }
    }

    /** The last lines of our child's output, for error messages. */
    private fun tail(): String =
        synchronized(lock) { recentOutput.toList() }.takeLast(20).joinToString("\n")
            .ifEmpty { "No server output was produced" }

    companion object {
        const val READY_TIMEOUT_MILLIS = 90_000L
        const val POLL_MILLIS = 500L
        const val DIAGNOSTIC_LINES = 200
    }
}
