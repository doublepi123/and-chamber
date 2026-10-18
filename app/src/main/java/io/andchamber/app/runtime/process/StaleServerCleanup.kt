package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.RuntimePaths
import java.io.File

/**
 * Clears what a crashed earlier run left behind before a new server start.
 *
 * OpenChamber refuses to start while `<data>/run/openchamber-<port>.pid` names a live instance, so
 * a leftover server (or a stale pid file) blocks every restart. Only processes that belong to this
 * app are touched: a PRoot launcher whose command line mentions this app's proot library or runtime
 * rootfs. Other apps' processes never carry those paths.
 */
object StaleServerCleanup {
    /** OpenChamber's pid-file name for [port]: `openchamber-<port>.pid`. */
    fun pidFileName(port: Int): String = "openchamber-$port.pid"

    /** Lock/pid files under the guest run dir that can name a dead instance on [port]. */
    fun staleFiles(
        runDir: File,
        port: Int,
    ): List<File> = listOf(File(runDir, pidFileName(port))).filter(File::exists)

    /**
     * True when [commandLine] (NUL-separated, as in /proc/<pid>/cmdline) belongs to this app's
     * runtime: it runs the proot library we shipped, or it works inside our runtime root.
     */
    fun belongsToRuntime(
        commandLine: String,
        prootLibraryPath: String,
        runtimeRoot: String,
    ): Boolean {
        val text = commandLine.replace('\u0000', ' ')
        return text.contains(prootLibraryPath) || text.contains(runtimeRoot)
    }

    /**
     * Pids of this app's processes found under [procRoot]. Scans only the caller's own processes,
     * because /proc entries of other uids are unreadable anyway.
     */
    fun ownRuntimePids(
        procRoot: File,
        prootLibraryPath: String,
        runtimeRoot: String,
    ): List<Long> =
        procRoot.listFiles().orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .mapNotNull { dir ->
                val pid = dir.name.toLongOrNull() ?: return@mapNotNull null
                val cmdline = runCatching { File(dir, "cmdline").readBytes().toString(Charsets.UTF_8) }.getOrNull()
                    ?: return@mapNotNull null
                pid.takeIf { belongsToRuntime(cmdline, prootLibraryPath, runtimeRoot) }
            }
            .filter { it != android.os.Process.myPid().toLong() }
            .sorted()
            .toList()

    /**
     * Polls [isPortFree] every 250 ms for up to [timeoutMillis]. Returns true as soon as the port is
     * free. Time is injected so the wait logic is unit-tested without sleeping.
     */
    fun awaitPortFree(
        port: Int,
        timeoutMillis: Long = PORT_WAIT_MILLIS,
        isPortFree: (Int) -> Boolean,
        sleep: (Long) -> Unit = Thread::sleep,
        now: () -> Long = System::currentTimeMillis,
    ): Boolean {
        val deadline = now() + timeoutMillis
        while (true) {
            if (isPortFree(port)) return true
            if (now() >= deadline) return false
            sleep(POLL_MILLIS)
        }
    }

    const val PORT_WAIT_MILLIS = 5_000L
    const val POLL_MILLIS = 250L
}

/** Performs the cleanup for one start. Kept thin so the parts above carry the logic. */
class ProotStaleServerCleaner(
    private val paths: RuntimePaths,
    private val procRoot: File = File("/proc"),
    private val processSignal: (Long) -> Unit = { pid -> android.os.Process.killProcess(pid.toInt()) },
    private val isPortFree: (Int) -> Boolean,
) {
    /** Kills leftovers, removes stale pid files, and waits for [port] to free. Returns true when free. */
    fun cleanBeforeStart(
        port: Int,
        guestRunDir: File,
        prootLibraryPath: String,
    ): Boolean {
        StaleServerCleanup.ownRuntimePids(procRoot, prootLibraryPath, paths.root.absolutePath)
            .flatMap { root -> processTreePostOrder(root) { pid -> readDirectChildPids(pid, procRoot) } }
            .distinct()
            .forEach { pid -> runCatching { processSignal(pid) } }
        StaleServerCleanup.staleFiles(guestRunDir, port).forEach { runCatching { it.delete() } }
        return StaleServerCleanup.awaitPortFree(port, isPortFree = isPortFree)
    }
}
