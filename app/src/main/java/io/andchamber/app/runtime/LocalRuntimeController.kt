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

/** Starts the foreground service that keeps network work alive. Called on the UI thread. */
fun interface ForegroundStarter {
    fun startForeground()
}

/**
 * Owns the Linux runtime lifecycle: install, start, stop, repair, uninstall and watchdog restarts.
 *
 * Every public action is queued behind [operationLock] and runs on [Dispatchers.IO]. Internal
 * helpers assume the lock is held and never acquire it, so there is no re-entry. States:
 * NotInstalled -> Installing -> Stopped -> Starting -> Running -> Stopping -> Stopped, with Failed
 * from any step. An unexpected death while Running is retried with backoff, then gives up.
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val restartPolicy: RestartPolicy = RestartPolicy(),
    private val watchdogPollMillis: Long = 5_000L,
    /** Off in unit tests that drive the state machine directly; the watchdog never goes idle. */
    private val watchdogEnabled: Boolean = true,
    private val supportedAbis: Set<String> = setOf("arm64-v8a", "x86_64"),
) : LocalRuntimeController {
    private val operationLock = Mutex()

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Detecting)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    private val mutableLog = MutableStateFlow<List<String>>(emptyList())
    override val logTail: StateFlow<List<String>> = mutableLog.asStateFlow()

    private val mutableInfo = MutableStateFlow<RuntimeInfo?>(null)
    override val info: StateFlow<RuntimeInfo?> = mutableInfo.asStateFlow()

    // Guarded by operationLock.
    private var watchdog: Job? = null
    private var wantsRunning = false

    /** Decides the starting state from what is on disk. Call once at app start. */
    fun initialize() {
        launch { detectLocked() }
    }

    /**
     * Android cuts network access for an app in the background, so installer and server processes
     * must only run while a foreground service is up. The service is requested here, before the
     * work is queued, so it is in the foreground by the time the first command starts.
     */
    override fun install() {
        foregroundStarter.startForeground()
        launch(FailurePhase.Install) { installLocked() }
    }

    override fun start() {
        foregroundStarter.startForeground()
        launch(FailurePhase.Start) { startLocked(automatic = false) }
    }

    override fun stop() = launch { stopLocked() }

    override fun repair() =
        launch {
            stopLocked()
            withContext(ioDispatcher) { installer.discardEnvironment() }
            installLocked()
        }

    override fun uninstall() =
        launch {
            stopLocked()
            withContext(ioDispatcher) { installer.removeAll() }
            mutableInfo.value = null
            publish(RuntimeState.NotInstalled)
        }

    /** Suspends until queued operations have drained. Used by tests. */
    suspend fun awaitIdle() {
        operationLock.withLock { }
    }

    /** Which lifecycle part the running operation belongs to; read when it fails. Lock-guarded. */
    private var currentPhase: FailurePhase = FailurePhase.Runtime

    private fun launch(
        phase: FailurePhase = FailurePhase.Runtime,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            operationLock.withLock {
                currentPhase = phase
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    appendLog("ERROR: ${error.message ?: error::class.java.simpleName}")
                    publish(
                        RuntimeState.Failed(
                            reason = error.message ?: "Runtime operation failed",
                            recoverable = true,
                            phase = currentPhase,
                        ),
                    )
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
        publish(RuntimeState.Stopped)
        currentPhase = FailurePhase.Start
        startLocked(automatic = false)
    }

    private suspend fun startLocked(automatic: Boolean) {
        val runtime = withContext(ioDispatcher) { installer.installedRuntime() }
        if (runtime == null) {
            publish(RuntimeState.NotInstalled)
            return
        }
        if (!automatic) {
            wantsRunning = true
        }
        val port = runtime.metadata.port
        publish(RuntimeState.Starting("Starting OpenChamber on port $port"))
        val password = withContext(ioDispatcher) { uiPassword(paths) }
        withContext(ioDispatcher) { server.start(runtime, password, ::appendLog) }
        publish(RuntimeState.Running("http://127.0.0.1:$port", port, nowMillis()))
        refreshInfo(runtime)
        scheduleWatchdog()
    }

    private suspend fun stopLocked() {
        wantsRunning = false
        watchdog?.cancel()
        watchdog = null
        val current = mutableState.value
        if (current is RuntimeState.Running || current is RuntimeState.Starting) {
            publish(RuntimeState.Stopping("Stopping OpenChamber"))
            withContext(ioDispatcher) { server.stop() }
        }
        val installed = withContext(ioDispatcher) { installer.installedRuntime() }
        publish(if (installed != null) RuntimeState.Stopped else RuntimeState.NotInstalled)
    }

    /**
     * Polls while Running. A dead or unhealthy server is restarted by a fresh [startLocked] after
     * a backoff, taking the lock itself. Gives up after the last backoff step.
     */
    private fun scheduleWatchdog() {
        if (!watchdogEnabled) return
        watchdog?.cancel()
        watchdog =
            scope.launch {
                while (true) {
                    delay(watchdogPollMillis)
                    val running = mutableState.value as? RuntimeState.Running ?: return@launch
                    if (server.isAlive() && health.isHealthy(running.port)) {
                        continue
                    }
                    operationLock.withLock {
                        val stillRunning = mutableState.value is RuntimeState.Running
                        if (!wantsRunning || !stillRunning) return@withLock
                        restartAfterCrash()
                    }
                    return@launch
                }
            }
    }

    private suspend fun restartAfterCrash() {
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
        withContext(ioDispatcher) { server.stop() }
        startLocked(automatic = true)
        // The restart came up, so the next crash starts a fresh backoff rather than inheriting this one.
        restartPolicy.reset()
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

    private fun directorySize(file: File): Long =
        runCatching { file.walkTopDown().filter(File::isFile).sumOf(File::length) }.getOrDefault(0L)

    private companion object {
        const val MAX_LOG_LINES = 400
    }
}
