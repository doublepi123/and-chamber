package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.install.InstalledRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.FileOutputStream
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

    override fun readyLaunchId(): Long? =
        synchronized(lock) {
            val id = readyId ?: return@synchronized null
            if (process?.isAlive != true) {
                readyId = null
                return@synchronized null
            }
            id
        }

    override fun isAlive(): Boolean = synchronized(lock) { process?.isAlive == true }

    override fun start(
        runtime: InstalledRuntime,
        uiPassword: String,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        stop()
        val port = runtime.metadata.port
        // OpenChamber's own data dir inside the guest. home is bind-mounted to /root (see ProotCommands).
        val guestRunDir = File(paths.home, ".config/openchamber/run")
        // Port ownership is decided before anything is spawned: a foreign listener that survives our
        // cleanup means we must not launch, and must never send the password to it.
        val prootPath = runCatching { suite().proot.absolutePath }.getOrNull()
            ?: error("Embedded PRoot is unavailable")
        if (!cleanBeforeStart(port, guestRunDir, prootPath)) throw PortInUseException(port)
        runCatching { refreshDns(runtime) }
        val resolved = suite()

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
        val env = ProotCommands.serverEnvironment(resolved.environment(), paths.prootTmp, uiPassword)
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
        val pump = pumpOutput(started, port, signal, onLog)
        try {
            waitUntilReady(started, port, isCancelled, signal, pump)
        } catch (error: Throwable) {
            stop()
            throw error
        }
        synchronized(lock) {
            // Only the launch that is still the current child becomes trusted.
            if (process === started) readyId = id
        }
        onStarted(binds)
        return id
    }

    override fun stop() {
        val current =
            synchronized(lock) {
                readyId = null
                process.also { process = null }
            }
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
    private fun pumpOutput(
        process: Process,
        port: Int,
        signal: CompletableDeferred<Unit>,
        onLog: (String) -> Unit,
    ): Thread =
        Thread({
            val writer = runCatching { FileOutputStream(paths.serverLog, true).bufferedWriter() }.getOrNull()
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(lock) {
                        recentOutput.addLast(line)
                        while (recentOutput.size > DIAGNOSTIC_LINES) recentOutput.removeFirst()
                    }
                    if (line.contains(Readiness.listenMarker(port))) signal.complete(Unit)
                    runCatching {
                        writer?.write(line)
                        writer?.newLine()
                        writer?.flush()
                    }
                    onLog(line)
                }
            } catch (_: java.io.IOException) {
                // Stop/destroy closes the pipe under us (InterruptedIOException on Android): normal end.
            } catch (error: Throwable) {
                // An uncaught throwable on this thread kills the whole app; log and end the pump instead.
                runCatching { onLog("Output reader stopped: ${error.message}") }
            } finally {
                runCatching { writer?.close() }
                // The child's output ended: it has exited. Forget trust immediately.
                synchronized(lock) { if (this.process === process) readyId = null }
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
