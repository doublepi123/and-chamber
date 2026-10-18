package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.install.InstalledRuntime
import io.andchamber.app.runtime.storage.DeviceStorage
import java.io.File
import java.io.FileOutputStream

/** Runs and stops the OpenChamber server process. */
interface OpenChamberServer {
    /** Blocks until the server answers its health check. Throws if it exits or never becomes ready. */
    fun start(
        runtime: InstalledRuntime,
        uiPassword: String,
        onLog: (String) -> Unit,
    )

    fun stop()

    fun isAlive(): Boolean
}

/**
 * Launches `node cli.js serve` inside PRoot. Ported from AndCode's LocalRuntimeProcessLauncher (MIT),
 * reduced to the single OpenChamber server.
 */
class ProotOpenChamberServer(
    private val paths: RuntimePaths,
    private val suite: () -> EmbeddedCommandSuite.Paths,
    private val health: HealthProbe,
    private val readyTimeoutMillis: Long = READY_TIMEOUT_MILLIS,
    private val processSignal: (Long) -> Unit = { pid -> android.os.Process.killProcess(pid.toInt()) },
    private val procRoot: File = File("/proc"),
    private val maxLogBytes: Long = 1_048_576L,
    /** Rewrites the guest resolv.conf from the current network; networks change between runs. */
    private val refreshDns: (InstalledRuntime) -> Unit = {},
    /** Clears leftovers of a crashed run before launch (see [ProotStaleServerCleaner]). */
    private val staleCleaner: (port: Int, guestRunDir: File, prootLibraryPath: String) -> Unit = { _, _, _ -> },
) : OpenChamberServer {
    private val lock = Any()
    private var process: Process? = null

    override fun start(
        runtime: InstalledRuntime,
        uiPassword: String,
        onLog: (String) -> Unit,
    ) {
        stop()
        runCatching { refreshDns(runtime) }
        val resolved = suite()
        // OpenChamber's own data dir inside the guest: /root/.config/openchamber (see cli-paths.js).
        val guestRunDir = File(runtime.rootfs, "root/.config/openchamber/run")
        staleCleaner(runtime.metadata.port, guestRunDir, resolved.proot.absolutePath)
        val port = runtime.metadata.port
        paths.logs.mkdirs()
        paths.workspace.mkdirs()
        paths.prootTmp.mkdirs()
        truncateLogFile(paths.serverLog, maxLogBytes)

        val command =
            ProotCommands.serverCommand(
                proot = resolved.proot,
                rootfs = runtime.rootfs,
                workspace = paths.workspace,
                storageArguments = DeviceStorage.bindArguments(),
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
        waitUntilReady(started, port)
    }

    override fun stop() {
        val current = synchronized(lock) { process.also { process = null } }
        current?.let { terminateProcessTree(it, emptyList(), procRoot, processSignal) }
        // Also reap PRoot trees left by an earlier app process that we no longer hold a handle to.
        // Matching on the runtime directory keeps this away from any other PRoot on the device.
        findManagedRuntimeRootPids(paths.root, procRoot)
            .flatMap { root -> processTreePostOrder(root) { pid -> readDirectChildPids(pid, procRoot) } }
            .distinct()
            .forEach { pid -> runCatching { processSignal(pid) } }
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

    private fun waitUntilReady(
        started: Process,
        port: Int,
    ) {
        val deadline = System.nanoTime() + readyTimeoutMillis * 1_000_000L
        while (true) {
            if (!started.isAlive) {
                error("OpenChamber exited during startup (exit ${started.exitValue()}): ${tail()}")
            }
            if (health.isHealthy(port)) return
            if (System.nanoTime() >= deadline) {
                stop()
                error("OpenChamber did not become ready on port $port within ${readyTimeoutMillis / 1000}s: ${tail()}")
            }
            Thread.sleep(500)
        }
    }

    private fun tail(): String =
        runCatching { paths.serverLog.readLines().takeLast(20).joinToString("\n") }
            .getOrDefault("No server log was produced")

    companion object {
        const val READY_TIMEOUT_MILLIS = 90_000L
    }
}
