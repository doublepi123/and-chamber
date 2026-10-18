package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.RuntimePaths
import java.io.File

/**
 * Clears what a crashed earlier run left behind before a new server start.
 *
 * OpenChamber refuses to start while `<data>/run/openchamber-<port>.pid` names a live instance, so
 * leftovers block every restart. Process ownership is decided by [ProcessScanner]; this object only
 * handles the pid file and the port wait.
 */
object StaleServerCleanup {
    /** OpenChamber's pid-file name for [port]: `openchamber-<port>.pid` (cli-lifecycle.js). */
    fun pidFileName(port: Int): String = "openchamber-$port.pid"

    /** Pid files under the guest run dir that name [port]. */
    fun staleFiles(
        runDir: File,
        port: Int,
    ): List<File> = listOf(File(runDir, pidFileName(port))).filter(File::exists)

    /**
     * Polls [isPortFree] every [POLL_MILLIS] for up to [timeoutMillis]. Returns true as soon as the
     * port is free. Time is injected so the wait is unit-tested without sleeping.
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

/**
 * Performs one pre-start cleanup: kills this app's owned runtime processes, removes the stale pid
 * file, and waits for the port. Returns whether the port is free afterwards; the caller must refuse
 * to launch when it is not.
 */
class ProotStaleServerCleaner(
    private val paths: RuntimePaths,
    private val scanner: ProcessScanner,
    private val signal: (Long) -> Unit,
    private val isPortFree: (Int) -> Boolean,
) {
    fun cleanBeforeStart(
        port: Int,
        guestRunDir: File,
        prootPath: String,
    ): Boolean {
        killOwnedRuntime(scanner, prootPath, paths.rootfs.absolutePath, signal)
        StaleServerCleanup.staleFiles(guestRunDir, port).forEach { runCatching { it.delete() } }
        return StaleServerCleanup.awaitPortFree(port, isPortFree = isPortFree)
    }
}

/** Kills every owned runtime PRoot tree (children first). Used by Stop, Repair and Uninstall too. */
fun killOwnedRuntime(
    scanner: ProcessScanner,
    prootPath: String,
    rootfsPath: String,
    signal: (Long) -> Unit,
) {
    val roots = scanner.ownedRoots(prootPath, rootfsPath)
    val targets = roots.flatMap(scanner::treePostOrder).distinctBy { it.pid }
    scanner.killOwned(targets, signal)
}
