package io.andchamber.app.runtime

import io.andchamber.app.runtime.install.InstallReporter
import io.andchamber.app.runtime.install.InstalledRuntime
import io.andchamber.app.runtime.install.OpenChamberInstaller
import io.andchamber.app.runtime.install.PackageVersionSource
import io.andchamber.app.runtime.install.PackageVersions
import io.andchamber.app.runtime.install.RuntimeInstaller
import io.andchamber.app.runtime.process.HealthProbe
import io.andchamber.app.runtime.process.OpenChamberServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Hook for the foreground service: told whenever the state changes. */
fun interface RuntimeStateListener {
    fun onStateChanged(state: RuntimeState)
}

/** The operations that must run inside the foreground service's lifetime. */
enum class RuntimeOperation { Install, Start, Repair, Stop, Uninstall, Restart, Update }

/**
 * Gate between the UI and the controller. [request] must bring the foreground service up first and
 * only then run [RuntimeOperation] on the controller, so no network work starts in the background.
 */
fun interface ForegroundStarter {
    fun request(operation: RuntimeOperation)
}

/**
 * Owns the Linux runtime lifecycle: install, start, stop, repair, uninstall and crash recovery.
 *
 * Public actions queue behind [operationLock]; the watchdog's recovery takes the same lock. [busy]
 * is true from the moment an action is queued until it has finished, so the service can tell "work
 * in progress" from "resting" without guessing from intermediate states.
 *
 * Intent: [wantsRunning] records what the user asked for. Start, Install and Repair (which ends in a
 * start) set it; Stop and Uninstall clear it. Only a set intent allows automatic crash recovery.
 */
class DefaultLocalRuntimeController(
    private val paths: RuntimePaths,
    private val abi: String,
    private val installer: RuntimeInstaller,
    private val server: OpenChamberServer,
    private val health: HealthProbe = HealthProbe.Loopback,
    private val uiPassword: (RuntimePaths) -> String = RuntimeSecrets::uiPassword,
    private val listener: RuntimeStateListener = RuntimeStateListener {},
    private val foregroundStarter: ForegroundStarter = ForegroundStarter {},
    /** Removes every owned runtime process (Stop, Repair, Uninstall run this before touching files). */
    private val killOwned: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val restartPolicy: RestartPolicy = RestartPolicy(),
    /** Interval between watchdog probes while Running. */
    private val watchdogPollMillis: Long = 5_000L,
    /**
     * The ABIs that run the on-device runtime. x86_64 is enabled because PRoot 5.1.107.98 rewrites the
     * guest's fork(2) (syscall 57, which Android's app seccomp policy rejects on x86_64) to clone(2) with
     * SIGCHLD, and it restores the child's registers (see docs/testing-x86-runtime.md for the probe gates).
     * Other ABIs are UnsupportedAbi (remote servers still work).
     */
    private val supportedAbis: Set<String> = setOf("arm64-v8a", "x86_64"),
    /** Registry lookup for newer versions. Null disables the registry check; the bundled pins still apply. */
    private val versionSource: PackageVersionSource? = null,
    /**
     * The versions bundled in the app's manifest. They are the update floor and what a fresh install
     * takes. Null means there are no bundled versions (tests only).
     */
    private val pinnedVersions: PackageVersions? = null,
    /** Minimum time between automatic registry checks. Manual checks ignore it. */
    private val updateCheckIntervalMillis: Long = UPDATE_CHECK_INTERVAL_MILLIS,
) : LocalRuntimeController {
    private val operationLock = Mutex()

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Detecting)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    override val busy: StateFlow<Boolean> get() = work.busy

    private val mutableLog = MutableStateFlow<List<String>>(emptyList())
    override val logTail: StateFlow<List<String>> = mutableLog.asStateFlow()

    private val mutableInfo = MutableStateFlow<RuntimeInfo?>(null)
    override val info: StateFlow<RuntimeInfo?> = mutableInfo.asStateFlow()

    private val mutableUpdates = MutableStateFlow(RuntimeUpdateState())
    override val updates: StateFlow<RuntimeUpdateState> = mutableUpdates.asStateFlow()

    /** Operations and recovery jobs queued or running; count and [busy] change together (see WorkCounter). */
    private val work = WorkCounter()


    private fun beginWork() = work.begin()

    private fun endWork() = work.end()

    // Guarded by operationLock, except cancelRequested which Stop sets without the lock.
    @Volatile private var cancelRequested = false
    private var watchdog: Job? = null
    private var restartJob: Job? = null
    private var wantsRunning = false
    private var currentPhase: FailurePhase = FailurePhase.Runtime

    /**
     * Installed versions the update check compares against. Written under operationLock; the registry
     * check reads it without the lock, so it is volatile.
     */
    @Volatile private var installedVersions: PackageVersions? = null

    /** Newest registry versions from the last successful check. Written by the check job only. */
    @Volatile private var latestKnown: PackageVersions? = null

    /** Registry check in flight. Not under operationLock: the network must not block the runtime. */
    @Volatile private var checkJob: Job? = null

    /** Decides the starting state from disk, after recovering any interrupted swap. */
    fun initialize() {
        launch(FailurePhase.Runtime) { detectLocked() }
    }

    // --- Public actions. Each one first asks the foreground service to start (item 3). ---

    override fun install() = foregroundStarter.request(RuntimeOperation.Install)

    override fun start() = foregroundStarter.request(RuntimeOperation.Start)

    override fun stop() {
        // Stop is not queued behind a start: flag it, then request the service op so it can cancel.
        cancelRequested = true
        foregroundStarter.request(RuntimeOperation.Stop)
    }

    override fun repair() = foregroundStarter.request(RuntimeOperation.Repair)

    override fun uninstall() = foregroundStarter.request(RuntimeOperation.Uninstall)

    override fun restart() = foregroundStarter.request(RuntimeOperation.Restart)

    /**
     * Checks the registry now. Runs outside operationLock, so it can finish while an operation is
     * queued. Ignored while a check is already running, and when the runtime is not installed.
     */
    override fun checkForUpdates() {
        if (versionSource == null || installedVersions == null) return
        if (checkJob?.isActive == true) return
        checkJob = scope.launch { runUpdateCheck() }
    }

    override fun update() = foregroundStarter.request(RuntimeOperation.Update)

    override fun dismissUpdateFailure() {
        mutableUpdates.update { it.copy(lastFailure = null) }
    }

    /**
     * Runs one [RuntimeOperation] on the controller. Called by the foreground service after it has
     * started in the foreground; never by the UI directly.
     */
    override fun perform(operation: RuntimeOperation) {
        when (operation) {
            RuntimeOperation.Install -> launch(FailurePhase.Install) { installLocked(thenStart = true) }
            RuntimeOperation.Update -> launch(FailurePhase.Runtime) { updateLocked() }
            RuntimeOperation.Start -> {
                cancelRequested = false
                launch(FailurePhase.Start) { startLocked(manual = true) }
            }
            RuntimeOperation.Stop -> {
                // Cancel a pending backoff/restart BEFORE queueing behind the lock: the restart holds
                // the lock while it waits, so waiting for the lock would let it run to completion.
                cancelRequested = true
                wantsRunning = false
                restartJob?.cancel()
                launch(FailurePhase.Runtime) { stopLocked() }
            }
            RuntimeOperation.Repair ->
                launch(FailurePhase.Runtime) {
                    // Repair never deletes the live environment first: stop what runs, then install
                    // into staging and swap atomically. A failed repair leaves the old runtime intact.
                    stopLocked()
                    currentPhase = FailurePhase.Install
                    installLocked(thenStart = true, repair = true)
                }
            RuntimeOperation.Restart -> {
                // One queued op, so stop and start can't be reordered by the lock (two separate
                // launches race for it). Used to apply new storage bindings.
                wantsRunning = false
                restartJob?.cancel()
                cancelRequested = false
                launch(FailurePhase.Start) {
                    stopLocked()
                    cancelRequested = false
                    startLocked(manual = true)
                }
            }
            RuntimeOperation.Uninstall ->
                launch(FailurePhase.Runtime) {
                    stopLocked()
                    withContext(ioDispatcher) { installer.removeAll() }
                    mutableInfo.value = null
                    installedVersions = null
                    mutableUpdates.value = RuntimeUpdateState()
                    publish(RuntimeState.NotInstalled)
                }
        }
    }

    /** Suspends until queued operations have drained. Used by tests. */
    suspend fun awaitIdle() {
        operationLock.withLock { }
    }

    private fun launch(
        phase: FailurePhase,
        block: suspend () -> Unit,
    ) {
        beginWork()
        // invokeOnCompletion, not finally: a job cancelled before it first runs never enters its body,
        // but its completion handler always fires, so the counter cannot stay high.
        scope.launch {
            operationLock.withLock {
                currentPhase = phase
                try {
                    block()
                } catch (error: CancellationException) {
                    publishTerminalAfterCancel()
                    throw error
                } catch (error: Throwable) {
                    // currentPhase, not the launch argument: install hands over to Start mid-operation.
                    failed(error.message ?: "Runtime operation failed", currentPhase, error)
                }
            }
        }.invokeOnCompletion { endWork() }
    }

    // --- All helpers below run with operationLock held. ---

    private suspend fun detectLocked() {
        if (abi !in supportedAbis) {
            publish(RuntimeState.UnsupportedAbi)
            return
        }
        // Finish or roll back an interrupted environment swap before reading what is installed.
        withContext(ioDispatcher) { installer.recover() }
        val existing = withContext(ioDispatcher) { installer.installedRuntime() }
        if (existing == null) {
            publish(RuntimeState.NotInstalled)
        } else {
            refreshInfo(existing)
            publish(RuntimeState.Stopped)
            // The bundled pins may already be newer than what is installed: offer that without network.
            recomputeAvailable(existing)
            maybeCheckForUpdates()
        }
    }

    /**
     * Install, then optionally start (Install and Repair both end running, as the user asked for it).
     * A repair ([repair] = true) installs at least the installed versions, so it never downgrades
     * OpenCode after an update.
     */
    private suspend fun installLocked(
        thenStart: Boolean,
        repair: Boolean = false,
    ) {
        if (abi !in supportedAbis) {
            publish(RuntimeState.UnsupportedAbi)
            return
        }
        cancelRequested = false
        publish(RuntimeState.Installing(InstallStep.DownloadRootfs, 0f, null))
        val versions = if (repair) repairVersions() else null
        val installed = installer.install(installReporter(updating = false), versions)
        refreshInfo(installed)
        recomputeAvailable(installed)
        maybeCheckForUpdates()
        // Intermediate Stopped: the service keeps running because busy is still true here.
        publish(RuntimeState.Stopped)
        if (!thenStart) return
        currentPhase = FailurePhase.Start
        startLocked(manual = true)
    }

    private fun installReporter(updating: Boolean): InstallReporter =
        object : InstallReporter {
            override fun step(
                step: InstallStep,
                progress: Float?,
                detail: String?,
                messageKey: MessageKey?,
            ) {
                publish(RuntimeState.Installing(step, progress, detail, messageKey, updating = updating))
            }

            override fun log(line: String) = appendLog(line)
        }

    /** Per package, the newer of the installed and the bundled version. Null when nothing is known. */
    private fun repairVersions(): PackageVersions? {
        val installed = installedVersions
        val pinned = pinnedVersions
        if (installed == null) return null
        if (pinned == null) return installed
        return PackageVersions(
            openChamber = newerOf(installed.openChamber, pinned.openChamber),
            openCode = newerOf(installed.openCode, pinned.openCode),
        )
    }

    private fun newerOf(
        a: String,
        b: String,
    ): String = if (OpenChamberInstaller.compareVersions(a, b) >= 0) a else b

    /**
     * Updates the installed packages to the available target. The server is stopped first. A failure
     * keeps the previous environment (which the installer never touches before the swap): it sets
     * [RuntimeUpdateState.lastFailure] and does not publish [RuntimeState.Failed]. Afterwards the
     * server is restarted when it was running, otherwise the state rests at Stopped.
     */
    private suspend fun updateLocked() {
        val target = mutableUpdates.value.available
        if (target == null || !target.hasChanges) return
        val runtime = withContext(ioDispatcher) { installer.installedRuntime() } ?: return
        val wasRunning = mutableState.value.let { it is RuntimeState.Running || it is RuntimeState.Starting } || wantsRunning

        cancelRequested = false
        stopLocked()
        mutableUpdates.update { it.copy(lastFailure = null) }
        try {
            val versions =
                PackageVersions(
                    openChamber = target.openChamber.target,
                    openCode = target.openCode.target,
                )
            publish(RuntimeState.Installing(InstallStep.CopyEnvironment, null, null, MessageKey.ProgressCopyingEnvironment, updating = true))
            val updated = installer.update(installReporter(updating = true), versions)
            refreshInfo(updated)
            recomputeAvailable(updated)
            mutableUpdates.update { it.copy(lastFailure = null) }
        } catch (error: CancellationException) {
            // Like install: leave the transient Installing state. The old environment is intact.
            publish(RuntimeState.Stopped)
            throw error
        } catch (error: Throwable) {
            // Not Failed: the previous environment is still installed and usable. The reason is shown
            // on the update card instead, so a failed update never blocks the runtime.
            val typed = error as? UserFacingException
            appendLog("ERROR: update failed: ${error.message}")
            mutableUpdates.update {
                it.copy(
                    lastFailure =
                        UpdateFailure(
                            reason = error.message ?: "Update failed",
                            messageKey = typed?.key ?: MessageKey.Generic,
                            detail = typed?.detail,
                        ),
                )
            }
            // The failed attempt left the old environment in place: show its versions again.
            refreshInfo(runtime)
        }
        recomputeAvailable(installer.installedRuntime() ?: runtime)
        if (!wasRunning) {
            publish(RuntimeState.Stopped)
            return
        }
        currentPhase = FailurePhase.Start
        startLocked(manual = true)
    }

    /**
     * Recomputes [RuntimeUpdateState.available] from the installed versions and the newest known
     * versions: the registry's (when fetched) and the bundled pins. Never offers a downgrade.
     */
    private fun recomputeAvailable(runtime: InstalledRuntime) {
        val installed =
            PackageVersions(
                openChamber = runtime.metadata.openChamberVersion,
                openCode = runtime.metadata.openCodeVersion,
            )
        installedVersions = installed
        mutableUpdates.update { it.copy(available = availableFor(installed, latestKnown)) }
    }

    private fun availableFor(
        installed: PackageVersions,
        registry: PackageVersions?,
    ): RuntimeUpdate? {
        val target = newestOf(registry, pinnedVersions) ?: return null
        val openChamber = VersionChange(current = installed.openChamber, target = newerOf(target.openChamber, installed.openChamber))
        val openCode = VersionChange(current = installed.openCode, target = newerOf(target.openCode, installed.openCode))
        val update = RuntimeUpdate(openChamber = openChamber, openCode = openCode)
        return update.takeIf { it.hasChanges }
    }

    /** Per package, the higher of the registry and the bundled version. Either may be null. */
    private fun newestOf(
        registry: PackageVersions?,
        pinned: PackageVersions?,
    ): PackageVersions? {
        if (registry == null) return pinned
        if (pinned == null) return registry
        return PackageVersions(
            openChamber = newerOf(registry.openChamber, pinned.openChamber),
            openCode = newerOf(registry.openCode, pinned.openCode),
        )
    }

    /** An automatic check, throttled by [updateCheckIntervalMillis] since the last one. */
    private fun maybeCheckForUpdates() {
        if (versionSource == null) return
        val last = mutableUpdates.value.lastCheckedAtMs
        if (last != null && nowMillis() - last < updateCheckIntervalMillis) return
        if (checkJob?.isActive == true) return
        checkJob = scope.launch { runUpdateCheck() }
    }

    /**
     * Asks the registry for newer versions, with no operationLock: the network must not hold up the
     * runtime. Cancellation propagates. A failure keeps the bundled pins and sets [RuntimeUpdateState.checkFailed].
     */
    private suspend fun runUpdateCheck() {
        val source = versionSource ?: return
        val pinned = pinnedVersions
        val installed = installedVersions ?: return
        mutableUpdates.update { it.copy(checking = true) }
        try {
            val latest: PackageVersions? =
                try {
                    source.latest(pinned ?: installed)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    appendLog("Update check failed: ${error.message}")
                    null
                }
            latestKnown = latest ?: latestKnown
            mutableUpdates.update {
                it.copy(
                    checking = false,
                    checkFailed = latest == null,
                    lastCheckedAtMs = nowMillis(),
                    // Re-read: an update may have finished while the request was in flight.
                    available = availableFor(installedVersions ?: installed, latestKnown),
                )
            }
        } finally {
            mutableUpdates.update { if (it.checking) it.copy(checking = false) else it }
        }
    }

    private suspend fun startLocked(manual: Boolean) {
        val runtime = withContext(ioDispatcher) { installer.installedRuntime() }
        if (runtime == null) {
            publish(RuntimeState.NotInstalled)
            return
        }
        if (manual) {
            wantsRunning = true
            restartPolicy.reset()
        }
        // A second Start queued behind the first (UI launch and a notification or test racing through
        // the service) must not tear down the server that just came up: start() stops the old child.
        if (mutableState.value is RuntimeState.Running && runCatching { server.isAlive() }.getOrDefault(false)) {
            appendLog("Start ignored: OpenChamber is already running")
            return
        }
        val port = runtime.metadata.port
        publish(RuntimeState.Starting("Starting OpenChamber on port $port"))
        val password = withContext(ioDispatcher) { uiPassword(paths) }
        withContext(ioDispatcher) {
            server.start(runtime, password, ::appendLog) { cancelRequested }
        }
        if (cancelRequested) throw CancellationException("Start cancelled by Stop")
        publish(RuntimeState.Running("http://127.0.0.1:$port", port, nowMillis()))
        refreshInfo(runtime)
        scheduleWatchdog()
    }

    private suspend fun stopLocked() {
        cancelRequested = true
        wantsRunning = false
        // Cancel a pending backoff/restart first: it holds no lock while waiting, so this is immediate.
        restartJob?.cancel()
        restartJob = null
        watchdog?.cancel()
        watchdog = null
        val current = mutableState.value
        if (current !is RuntimeState.Stopped && current !is RuntimeState.NotInstalled) {
            publish(RuntimeState.Stopping("Stopping OpenChamber"))
        }
        // Always run: never trust the UI's view of what is alive.
        withContext(ioDispatcher) {
            server.stop()
            killOwned()
        }
        cancelRequested = false
        val installed = withContext(ioDispatcher) { installer.installedRuntime() }
        publish(if (installed != null) RuntimeState.Stopped else RuntimeState.NotInstalled)
    }

    /** A cancelled start lands in Stopped (or NotInstalled), never in Starting. */
    private fun publishTerminalAfterCancel() {
        if (mutableState.value is RuntimeState.Starting || mutableState.value is RuntimeState.Stopping) {
            publish(RuntimeState.Stopped)
        }
    }

    private fun failed(
        reason: String,
        phase: FailurePhase,
        error: Throwable?,
    ) {
        appendLog("ERROR: $reason")
        if (error != null) appendLog(error.stackTraceToString().lineSequence().take(3).joinToString("\n"))
        wantsRunning = false
        // A typed failure carries its own localised headline; anything else is generic.
        val typed = error as? UserFacingException
        publish(
            RuntimeState.Failed(
                reason = reason,
                recoverable = true,
                phase = phase,
                messageKey = typed?.key ?: MessageKey.Generic,
                detail = typed?.detail,
            ),
        )
    }

    /**
     * Probes while Running. A dead or unhealthy server starts a recovery job that owns the restart
     * budget. Probe errors are logged and count as unhealthy; they never escape the scope.
     */
    private fun scheduleWatchdog() {
        watchdog?.cancel()
        watchdog =
            scope.launch {
                var missedProbes = 0
                while (true) {
                    delay(watchdogPollMillis)
                    val running = mutableState.value as? RuntimeState.Running ?: return@launch
                    // A dead child is certain; a slow /health is not. Opening the web UI fires a burst of
                    // requests and a phone can take seconds to answer, so only a dead process or
                    // several consecutive missed probes count as a crash (seen on device).
                    val alive = runCatching { server.isAlive() }.getOrDefault(false)
                    val answered =
                        alive &&
                            runCatching { withContext(ioDispatcher) { health.read(running.port)?.serverOk == true } }
                                .getOrElse { error ->
                                    if (error is CancellationException) throw error
                                    appendLog("Watchdog probe failed: ${error.message}")
                                    false
                                }
                    if (answered) {
                        missedProbes = 0
                        restartPolicy.onHealthy(nowMillis())
                        continue
                    }
                    if (alive && ++missedProbes < WATCHDOG_MISSES_BEFORE_RESTART) {
                        appendLog("Server did not answer a health check ($missedProbes/$WATCHDOG_MISSES_BEFORE_RESTART)")
                        continue
                    }
                    startRecovery()
                    return@launch
                }
            }
    }

    /** One recovery job for one crash. Counted in [busy] so the service stays up while it runs. */
    private fun startRecovery() {
        beginWork()
        restartJob =
            scope.launch {
                try {
                    operationLock.withLock {
                        if (!wantsRunning || mutableState.value !is RuntimeState.Running) return@withLock
                        restartAfterCrash()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failed(error.message ?: "Recovery failed", FailurePhase.Runtime, error)
                }
            }.also { job ->
                // Fires even when the job is cancelled before it starts; busy never consults isActive.
                job.invokeOnCompletion { endWork() }
            }
    }

    /**
     * Automatic recovery. Each attempt consumes one budget step: a failed attempt goes around again
     * (after its own backoff) until the budget runs out, and only then is the state Failed(Runtime).
     * A successful attempt returns to the watchdog, which refills the budget after a healthy window.
     */
    private suspend fun restartAfterCrash() {
        publish(RuntimeState.Failed("OpenChamber stopped unexpectedly", recoverable = true, phase = FailurePhase.Runtime))
        while (true) {
            val delayMillis = restartPolicy.nextDelayMillis()
            if (delayMillis == null) {
                wantsRunning = false
                // The budget is spent: the child may still be alive (health misses, not a death), so
                // clean it up before the terminal Failed lets the service finish. Cancellation rethrows
                // and an error is logged; either way the terminal state is still published.
                stopAfterExhaustion()
                publish(
                    RuntimeState.Failed(
                        "OpenChamber kept stopping; tap Start to try again",
                        recoverable = true,
                        phase = FailurePhase.Runtime,
                    ),
                )
                return
            }
            publish(RuntimeState.Starting("OpenChamber stopped; restarting in ${delayMillis / 1000}s"))
            appendLog("Restarting OpenChamber in ${delayMillis / 1000}s")
            delay(delayMillis)
            if (!wantsRunning) return
            try {
                withContext(ioDispatcher) { server.stop() }
                startLocked(manual = false)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // This attempt failed: log it and loop, so the remaining budget is actually used.
                appendLog("Restart attempt failed: ${error.message}")
                publish(RuntimeState.Failed("Restart failed: ${error.message}", recoverable = true, phase = FailurePhase.Start))
            }
        }
    }

    /**
     * Stops the server and every owned process before the terminal failure is published. Both steps
     * run in [NonCancellable] and are independent: a throw from [server].stop must not skip [killOwned].
     * Each error is logged; the terminal state is published by the caller either way. A cancellation
     * that arrives during cleanup is still honoured after both steps have been attempted.
     */
    private suspend fun stopAfterExhaustion() {
        var cancelled: CancellationException? = null
        withContext(NonCancellable) {
            withContext(ioDispatcher) {
                try {
                    server.stop()
                } catch (error: CancellationException) {
                    cancelled = error
                } catch (error: Throwable) {
                    appendLog("Server stop after restart budget failed: ${error.message}")
                }
                try {
                    killOwned()
                } catch (error: CancellationException) {
                    cancelled = cancelled ?: error
                } catch (error: Throwable) {
                    appendLog("Owned process cleanup after restart budget failed: ${error.message}")
                }
            }
        }
        cancelled?.let { throw it }
    }

    private fun publish(next: RuntimeState) {
        mutableState.value = next
        listener.onStateChanged(next)
    }

    private fun appendLog(line: String) {
        mutableLog.value = (mutableLog.value + line).takeLast(MAX_LOG_LINES)
    }

    private fun refreshInfo(runtime: InstalledRuntime) {
        mutableInfo.value =
            RuntimeInfo(
                abi = abi,
                alpineVersion = runtime.metadata.alpineVersion,
                openChamberVersion = runtime.metadata.openChamberVersion,
                openCodeVersion = runtime.metadata.openCodeVersion,
                nodeVersion = runtime.metadata.nodeVersion,
                diskUsageBytes = directorySize(paths.root),
                freeSpaceBytes = paths.root.usableSpace,
            )
    }

    /** Symlink-safe: guest links may point anywhere, so never follow them while sizing. */
    private fun directorySize(file: File): Long = runCatching { io.andchamber.app.runtime.fs.treeSize(file) }.getOrDefault(0L)

    private companion object {
        const val MAX_LOG_LINES = 400
        const val WATCHDOG_MISSES_BEFORE_RESTART = 3

        /** Automatic registry checks run at most this often (manual checks ignore it). */
        const val UPDATE_CHECK_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
    }
}
