package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.install.InstalledRuntime
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream

/** Runs and stops the OpenChamber server process. */
interface OpenChamberServer {
    /**
     * Launches the server and waits until it is ready (see [Readiness]). Throws on any failure and
     * whenever [isCancelled] returns true, so a Stop never queues behind the readiness wait.
     * Returns the launch id of the child that became ready; the authenticator checks it before POST.
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

    /** Incremented for every launch. A login must be tied to the launch id it was checked against. */
    fun currentLaunchId(): Long
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

    /** Our child's own output, in order. Readiness trusts only this, never the network alone. */
    private val childOutput = mutableListOf<String>()

    override fun currentLaunchId(): Long = synchronized(lock) { launchId }

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

        val command =
            ProotCommands.serverCommand(
                proot = resolved.proot,
                rootfs = runtime.rootfs,
                home = paths.home,
                workspace = paths.workspace,
                // No device-storage binds: MANAGE_EXTERNAL_STORAGE was removed (YAGNI for v1).
                storageArguments = emptyList(),
                port = port,
            )
        val started = spawn(command, ProotCommands.serverEnvironment(resolved.environment(), paths.prootTmp, uiPassword), paths.root)
        val id =
            synchronized(lock) {
                process = started
                childOutput.clear()
                ++launchId
            }
        val pump = pumpOutput(started, onLog)
        try {
            waitUntilReady(started, port, isCancelled, pump)
        } catch (error: Throwable) {
            stop()
            throw error
        }
        return id
    }

    override fun stop() {
        val current = synchronized(lock) { process.also { process = null } }
        // Owned-only: our handle's tree plus every runtime PRoot tree we can prove is ours. Uses the
        // proot path from the suite when available; stop must still work if the suite is missing.
        val proot = runCatching { suite().proot.absolutePath }.getOrNull()
        val roots =
            proot?.let { scanner.ownedRoots(prootPath = it, rootfsPath = paths.rootfs.absolutePath) }.orEmpty()
        terminateProcessTree(current, scanner, roots, signal)
    }

    override fun isAlive(): Boolean = synchronized(lock) { process?.isAlive == true }

    private fun pumpOutput(
        process: Process,
        onLog: (String) -> Unit,
    ): Thread =
        Thread({
            val writer = runCatching { FileOutputStream(paths.serverLog, true).bufferedWriter() }.getOrNull()
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(lock) { if (this.process === process) childOutput += line }
                    runCatching {
                        writer?.write(line)
                        writer?.newLine()
                        writer?.flush()
                    }
                    onLog(line)
                }
            } finally {
                runCatching { writer?.close() }
            }
        }, "openchamber-output").apply { isDaemon = true; start() }

    /**
     * Readiness in trust order: our child is alive; our child printed the listen line for [port];
     * only then /health is believed (ok, OpenCode ready, serverId stable across polls). A child that
     * exits (EADDRINUSE when a foreign process won the port after our precheck) fails as port in use.
     */
    private fun waitUntilReady(
        started: Process,
        port: Int,
        isCancelled: () -> Boolean,
        pump: Thread,
    ) {
        val deadline = now() + readyTimeoutMillis * 1_000_000L
        val serverIds = mutableListOf<String?>()
        var listening = false
        while (true) {
            if (isCancelled()) throw CancellationException("Start cancelled")
            if (!started.isAlive) {
                // Node exits on EADDRINUSE: someone took the port after our precheck. That is a port
                // conflict, not a server failure, and is reported as such (no password is ever sent).
                // Let the output pump drain the exited child's remaining output before deciding.
                pump.join(2_000)
                val output = synchronized(lock) { childOutput.toList() }
                if (!listening && output.any { it.contains("EADDRINUSE") }) throw PortInUseException(port)
                error("OpenChamber exited during startup (exit ${started.exitValue()}): ${tail()}")
            }
            if (!listening) {
                listening = synchronized(lock) { Readiness.childListening(childOutput.toList(), port) }
            }
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

    private fun tail(): String =
        runCatching { paths.serverLog.readLines().takeLast(20).joinToString("\n") }
            .getOrDefault("No server log was produced")

    companion object {
        const val READY_TIMEOUT_MILLIS = 90_000L
        const val POLL_MILLIS = 500L
    }
}
