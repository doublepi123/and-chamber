package io.andchamber.app.runtime

import io.andchamber.app.runtime.install.InstallReporter
import io.andchamber.app.runtime.install.InstalledRuntime
import io.andchamber.app.runtime.install.RuntimeInstaller
import io.andchamber.app.runtime.process.HealthProbe
import io.andchamber.app.runtime.process.OpenChamberServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
enum class RuntimeOperation { Install, Start, Repair, Stop, Uninstall }

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
 * Public actions queue behind [operationLock]; the watchdog and internal helpers take the same lock.
 * [Running] means OpenCode is ready (see [Readiness]), not just the web listener. Stop never waits
 * behind a start: it sets [cancelRequested], which the readiness wait checks between polls.
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
     * Only arm64-v8a runs the on-device runtime. x86_64 is UnsupportedAbi (remote servers still work):
     * Android's x86_64 seccomp filter blocks SYS_fork, which PRoot does not translate, so the guest
     * cannot start. Do not add x86_64 here without verifying that path on hardware.
     */
    private val supportedAbis: Set<String> = setOf("arm64-v8a"),
) : LocalRuntimeController {
    private val operationLock = Mutex()

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Detecting)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    private val mutableLog = MutableStateFlow<List<String>>(emptyList())
    override val logTail: StateFlow<List<String>> = mutableLog.asStateFlow()

    private val mutableInfo = MutableStateFlow<RuntimeInfo?>(null)
    override val info: StateFlow<RuntimeInfo?> = mutableInfo.asStateFlow()

    // Guarded by operationLock, except cancelRequested which Stop sets without the lock.
    @Volatile private var cancelRequested = false
    private var watchdog: Job? = null
    private var restartJob: Job? = null
    private var wantsRunning = false
    private var currentPhase: FailurePhase = FailurePhase.Runtime

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

    /**
     * Runs one [RuntimeOperation] on the controller. Called by the foreground service after it has
     * started in the foreground; never by the UI directly.
     */
    override fun perform(operation: RuntimeOperation) {
        when (operation) {
            RuntimeOperation.Install -> launch(FailurePhase.Install) { installLocked() }
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
                    stopLocked()
                    // A failure from here on is an install failure: Retry must reinstall.
                    currentPhase = FailurePhase.Install
                    withContext(ioDispatcher) { installer.discardEnvironment() }
                    installLocked()
                }
            RuntimeOperation.Uninstall ->
                launch(FailurePhase.Runtime) {
                    stopLocked()
                    withContext(ioDispatcher) { installer.removeAll() }
                    mutableInfo.value = null
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
        }
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
        }
    }

    private suspend fun installLocked() {
        if (abi !in supportedAbis) {
            publish(RuntimeState.UnsupportedAbi)
            return
        }
        cancelRequested = false
        publish(RuntimeState.Installing(InstallStep.DownloadRootfs, 0f, null))
        val reporter =
            object : InstallReporter {
                override fun step(
                    step: InstallStep,
                    progress: Float?,
                    detail: String?,
                ) {
                    publish(RuntimeState.Installing(step, progress, detail))
                }

                override fun log(line: String) = appendLog(line)
            }
        val installed = installer.install(reporter)
        refreshInfo(installed)
        // Intermediate Stopped: the service must not stop here, the start follows immediately.
        publish(RuntimeState.Stopped)
        currentPhase = FailurePhase.Start
        startLocked(manual = false)
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
        val port = runtime.metadata.port
        publish(RuntimeState.Starting("Starting OpenChamber on port $port"))
        val password = withContext(ioDispatcher) { uiPassword(paths) }
        withContext(ioDispatcher) {
            server.start(runtime, password, ::appendLog, isCancelled = { cancelRequested })
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
        publish(RuntimeState.Failed(reason, recoverable = true, phase = phase))
    }

    /**
     * Probes while Running and restarts a dead server with backoff. Every iteration is inside one
     * error boundary: an exception is logged and treated as a failed probe, never escapes the scope.
     */
    private fun scheduleWatchdog() {
        watchdog?.cancel()
        watchdog =
            scope.launch {
                while (true) {
                    delay(watchdogPollMillis)
                    val running = mutableState.value as? RuntimeState.Running ?: return@launch
                    val healthy =
                        runCatching { server.isAlive() && health.read(running.port)?.serverOk == true }
                            .getOrElse { error ->
                                if (error is CancellationException) throw error
                                appendLog("Watchdog probe failed: ${error.message}")
                                false
                            }
                    if (healthy) {
                        restartPolicy.onHealthy(nowMillis())
                        continue
                    }
                    // The crash is handled in its own job: its backoff must not hold the lock, so a
                    // Stop arriving during the wait can cancel it and proceed immediately.
                    restartJob = scope.launch {
                        operationLock.withLock {
                            if (!wantsRunning || mutableState.value !is RuntimeState.Running) return@withLock
                            restartAfterCrash()
                        }
                    }
                    return@launch
                }
            }
    }

    /**
     * One crash recovery: wait the backoff, restart, and repeat through the watchdog on success. The
     * whole attempt is in one boundary, so a failing start becomes a Failed state, not a crash.
     */
    private suspend fun restartAfterCrash() {
        publish(RuntimeState.Failed("OpenChamber stopped unexpectedly", recoverable = true, phase = FailurePhase.Runtime))
        val delayMillis = restartPolicy.nextDelayMillis()
        if (delayMillis == null) {
            wantsRunning = false
            publish(RuntimeState.Failed("OpenChamber kept stopping; tap Start to try again", recoverable = true, phase = FailurePhase.Runtime))
            return
        }
        publish(RuntimeState.Starting("OpenChamber stopped; restarting in ${delayMillis / 1000}s"))
        appendLog("Restarting OpenChamber in ${delayMillis / 1000}s")
        delay(delayMillis)
        if (!wantsRunning) return
        try {
            withContext(ioDispatcher) { server.stop() }
            startLocked(manual = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A failed automatic restart feeds the same budget; the next attempt is a new watchdog cycle.
            appendLog("Restart failed: ${error.message}")
            wantsRunning = false
            publish(RuntimeState.Failed(error.message ?: "Restart failed", recoverable = true, phase = FailurePhase.Start))
        }
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
    }
}
