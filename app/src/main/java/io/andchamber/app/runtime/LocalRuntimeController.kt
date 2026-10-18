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
enum class RuntimeOperation { Install, Start, Repair, Stop, Uninstall, Restart }

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
     * Only arm64-v8a runs the on-device runtime. x86_64 is UnsupportedAbi (remote servers still work):
     * Android's x86_64 seccomp filter blocks SYS_fork, which PRoot does not translate, so the guest
     * cannot start. Do not add x86_64 here without verifying that path on hardware.
     */
    private val supportedAbis: Set<String> = setOf("arm64-v8a"),
) : LocalRuntimeController {
    private val operationLock = Mutex()

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Detecting)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    override val busy: StateFlow<Boolean> get() = work.busy

    private val mutableLog = MutableStateFlow<List<String>>(emptyList())
    override val logTail: StateFlow<List<String>> = mutableLog.asStateFlow()

    private val mutableInfo = MutableStateFlow<RuntimeInfo?>(null)
    override val info: StateFlow<RuntimeInfo?> = mutableInfo.asStateFlow()

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
     * Runs one [RuntimeOperation] on the controller. Called by the foreground service after it has
     * started in the foreground; never by the UI directly.
     */
    override fun perform(operation: RuntimeOperation) {
        when (operation) {
            RuntimeOperation.Install -> launch(FailurePhase.Install) { installLocked(thenStart = true) }
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
                    installLocked(thenStart = true)
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
        }
    }

    /** Install, then optionally start (Install and Repair both end running, as the user asked for it). */
    private suspend fun installLocked(thenStart: Boolean) {
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
        // Intermediate Stopped: the service keeps running because busy is still true here.
        publish(RuntimeState.Stopped)
        if (!thenStart) return
        currentPhase = FailurePhase.Start
        startLocked(manual = true)
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
        publish(RuntimeState.Failed(reason, recoverable = true, phase = phase))
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
    }
}
