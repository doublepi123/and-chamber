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
     */
    fun start(
        runtime: InstalledRuntime,
        uiPassword: String,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean = { false },
    )

    /** Stops every process of this server, owned or from our handle. Idempotent. */
    fun stop()

    fun isAlive(): Boolean
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
) : OpenChamberServer {
    private val lock = Any()
    private var process: Process? = null

    override fun start(
        runtime: InstalledRuntime,
        uiPassword: String,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        stop()
        val port = runtime.metadata.port
        // OpenChamber's own data dir inside the guest: /root/.config/openchamber (cli-paths.js).
        val guestRunDir = File(runtime.rootfs, "root/.config/openchamber/run")
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
        val builder =
            ProcessBuilder(command)
                .directory(paths.root)
                .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            putAll(ProotCommands.serverEnvironment(resolved.environment(), paths.prootTmp, uiPassword))
        }
        val started = builder.start()
        synchronized(lock) { process = started }
        pumpOutput(started, onLog)
        try {
            waitUntilReady(started, port, isCancelled)
        } catch (error: Throwable) {
            stop()
            throw error
        }
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
    ) {
        Thread({
            val writer = runCatching { FileOutputStream(paths.serverLog, true).bufferedWriter() }.getOrNull()
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
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
        }, "openchamber-output").apply { isDaemon = true }.start()
    }

    /**
     * Readiness: our child is alive, /health reports ok and OpenCode ready, and the serverId stays
     * the same across polls. Cancellation is checked between polls so Stop is never blocked.
     */
    private fun waitUntilReady(
        started: Process,
        port: Int,
        isCancelled: () -> Boolean,
    ) {
        val deadline = now() + readyTimeoutMillis * 1_000_000L
        val serverIds = mutableListOf<String?>()
        while (true) {
            if (isCancelled()) throw CancellationException("Start cancelled")
            if (!started.isAlive) {
                error("OpenChamber exited during startup (exit ${started.exitValue()}): ${tail()}")
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
