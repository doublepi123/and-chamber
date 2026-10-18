package io.andchamber.app.runtime.process

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The single place that scans /proc for this app's runtime processes.
 *
 * A process is "ours" only when all of these hold:
 *  - its real uid (from /proc/<pid>/status) is this app's uid,
 *  - argv[0] is exactly our PRoot launcher path, and argv contains our runtime rootfs path,
 *  - it is not this process.
 * Kills are guarded by the process start time (/proc/<pid>/stat field 22), recorded at scan and
 * rechecked just before signalling, so a recycled pid is never signalled.
 */
data class OwnedProcess(
    val pid: Long,
    val parentPid: Long,
    val startTime: Long,
)

class ProcessScanner(
    private val procRoot: File = File("/proc"),
    private val myUid: Int,
    private val myPid: Long,
) {
    /** Owned PRoot roots (launcher processes) for this runtime, with their start times. */
    fun ownedRoots(prootPath: String, rootfsPath: String): List<OwnedProcess> =
        allProcesses()
            .filter { it.pid != myPid && it.uid == myUid }
            .filter { it.argv.firstOrNull() == prootPath && it.argv.any { arg -> arg.contains(rootfsPath) } }
            .map { OwnedProcess(it.pid, it.ppid, it.startTime) }
            .sortedBy { it.pid }

    /** Children of [parentPid] (any process, used to walk a tree we already own). */
    fun childrenOf(parentPid: Long): List<Long> =
        allProcesses().filter { it.ppid == parentPid }.map { it.pid }.sorted()

    /**
     * Post-order list of [root] and its descendants, children first. The root must still be the very
     * process that was recorded (same pid, start time and our uid); otherwise the pid was reused and
     * the whole tree is skipped. Every node must be ours (uid, not self) and is recorded with its
     * start time so [killOwned] can recheck it.
     */
    fun treePostOrder(root: OwnedProcess): List<OwnedProcess> {
        val all = allProcesses().associateBy { it.pid }
        val rootNow = all[root.pid] ?: return emptyList()
        if (rootNow.startTime != root.startTime || rootNow.uid != myUid || rootNow.pid == myPid) return emptyList()
        val childrenByParent = all.values.groupBy { it.ppid }
        val result = mutableListOf<OwnedProcess>()
        val visited = mutableSetOf<Long>()
        fun visit(pid: Long) {
            if (!visited.add(pid)) return
            childrenByParent[pid].orEmpty().map { it.pid }.sorted().forEach(::visit)
            val node = all[pid] ?: return
            // Children of a verified root are ours unless their uid says otherwise.
            if (node.uid == myUid && node.pid != myPid) result += OwnedProcess(node.pid, node.ppid, node.startTime)
        }
        visit(root.pid)
        return result
    }

    /**
     * Signals each owned process after confirming its start time is unchanged (not a recycled pid).
     * [signal] is injected: Android uses Process.killProcess, tests record the calls.
     */
    fun killOwned(
        processes: List<OwnedProcess>,
        signal: (Long) -> Unit,
    ) {
        processes.distinctBy { it.pid }.forEach { process ->
            if (process.pid == myPid) return@forEach
            // Recheck right before the signal: same pid, same start time, still our uid.
            val now = snapshot(process.pid) ?: return@forEach
            if (now.startTime == process.startTime && now.uid == myUid) runCatching { signal(process.pid) }
        }
    }

    private fun snapshot(pid: Long): ProcInfo? = readProc(File(procRoot, pid.toString()))

    private fun allProcesses(): List<ProcInfo> =
        procRoot.listFiles().orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .mapNotNull { dir -> dir.name.toLongOrNull()?.let { readProc(dir) } }
            .toList()

    private data class ProcInfo(
        val pid: Long,
        val ppid: Long,
        val uid: Int,
        val startTime: Long,
        val argv: List<String>,
    )

    private fun readProc(dir: File): ProcInfo? {
        val pid = dir.name.toLongOrNull() ?: return null
        val status = runCatching { File(dir, "status").readText() }.getOrNull() ?: return null
        val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return null
        val cmdline = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: return null
        val uid = parseRealUid(status) ?: return null
        val (ppid, startTime) = parseStat(stat) ?: return null
        val argv = cmdline.toString(Charsets.UTF_8).split('\u0000').filter(String::isNotEmpty)
        return ProcInfo(pid, ppid, uid, startTime, argv)
    }

    companion object {
        /** The real uid: first field of the `Uid:` line in /proc/<pid>/status. */
        internal fun parseRealUid(status: String): Int? =
            status.lineSequence()
                .firstOrNull { it.startsWith("Uid:") }
                ?.removePrefix("Uid:")
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.firstOrNull()
                ?.toIntOrNull()

        /**
         * ppid (field 4) and starttime (field 22) from /proc/<pid>/stat. The command name is in
         * parentheses and may contain spaces or ')', so parsing starts after the last ')'.
         */
        internal fun parseStat(stat: String): Pair<Long, Long>? {
            val fields = stat.substringAfterLast(')').trim().split(' ')
            // After ')': field 3 (state) is index 0, so ppid (4) is index 1, starttime (22) is index 19.
            val ppid = fields.getOrNull(1)?.toLongOrNull() ?: return null
            val start = fields.getOrNull(19)?.toLongOrNull() ?: return null
            return ppid to start
        }
    }
}

/** Kills [root]'s tree (children first) through the scanner, then waits for the launcher to exit. */
internal fun terminateProcessTree(
    process: Process?,
    scanner: ProcessScanner,
    roots: List<OwnedProcess>,
    signal: (Long) -> Unit,
) {
    val targets = roots.flatMap(scanner::treePostOrder).distinctBy { it.pid }
    if (process?.isAlive == true) {
        process.destroy()
        process.waitFor(750, TimeUnit.MILLISECONDS)
    }
    scanner.killOwned(targets, signal)
    if (process?.isAlive == true && !process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor(1, TimeUnit.SECONDS)
    }
}

/** Keeps the newest half of an oversized log, cut at a line boundary. */
internal fun truncateLogFile(
    logFile: File,
    maxBytes: Long,
) {
    if (!logFile.isFile) return
    if (logFile.length() <= maxBytes) return
    runCatching {
        val keepSize = maxBytes / 2
        val bytes = logFile.readBytes()
        val cutPoint = (bytes.size - keepSize.toInt()).coerceAtLeast(0)
        val lineBreak = (cutPoint until bytes.size).firstOrNull { bytes[it] == '\n'.code.toByte() } ?: -1
        val start = if (lineBreak >= 0 && lineBreak < bytes.size - 1) lineBreak + 1 else cutPoint
        logFile.writeBytes(bytes.copyOfRange(start, bytes.size))
    }
}
