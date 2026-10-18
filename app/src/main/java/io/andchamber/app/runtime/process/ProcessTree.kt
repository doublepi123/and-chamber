package io.andchamber.app.runtime.process

import java.io.File
import java.util.concurrent.TimeUnit

/** Process bookkeeping for PRoot children, ported from AndCode's LocalRuntimeProcessLauncher (MIT). */

internal fun processTreePostOrder(
    rootPid: Long,
    childrenReader: (Long) -> List<Long>,
): List<Long> {
    val visited = mutableSetOf<Long>()
    val result = mutableListOf<Long>()

    fun visit(pid: Long) {
        if (!visited.add(pid)) return
        childrenReader(pid).forEach(::visit)
        result += pid
    }

    visit(rootPid)
    return result
}

/** Root pids of PRoot processes whose command line mentions [runtimeDirectory]. */
internal fun findManagedRuntimeRootPids(
    runtimeDirectory: File,
    procRoot: File = File("/proc"),
    prootMarker: String = EmbeddedCommandSuite.PROOT_LIBRARY_NAME,
): List<Long> {
    val runtimeMarker = runtimeDirectory.absolutePath
    return procRoot.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .mapNotNull { directory ->
            val pid = directory.name.toLongOrNull() ?: return@mapNotNull null
            val commandLine =
                runCatching {
                    File(directory, "cmdline")
                        .readBytes()
                        .toString(Charsets.UTF_8)
                        .replace('\u0000', ' ')
                }.getOrNull() ?: return@mapNotNull null
            pid.takeIf { commandLine.contains(prootMarker) && commandLine.contains(runtimeMarker) }
        }
        .sorted()
        .toList()
}

/** Direct children of [parentPid], read from `/proc/<pid>/stat` (field 4 is the ppid). */
internal fun readDirectChildPids(
    parentPid: Long,
    procRoot: File = File("/proc"),
): List<Long> =
    procRoot.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .mapNotNull { directory ->
            val pid = directory.name.toLongOrNull() ?: return@mapNotNull null
            pid.takeIf { parentPidOf(File(directory, "stat")) == parentPid }
        }
        .sorted()
        .toList()

/** The parent pid from a `stat` line. The command name is parenthesised and may contain spaces. */
internal fun parentPidOf(statFile: File): Long? =
    runCatching {
        statFile.readText()
            .substringAfterLast(')')
            .trim()
            .split(' ')
            .getOrNull(1)
            ?.toLongOrNull()
    }.getOrNull()

internal fun processId(process: Process): Long? {
    val methodValue =
        runCatching {
            process.javaClass.methods
                .firstOrNull { it.name == "pid" && it.parameterCount == 0 }
                ?.invoke(process)
        }.getOrNull()
    when (methodValue) {
        is Long -> return methodValue
        is Int -> return methodValue.toLong()
    }

    return sequenceOf("pid", "id")
        .mapNotNull { fieldName ->
            runCatching {
                process.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(process)
            }.getOrNull()
        }
        .mapNotNull { value ->
            when (value) {
                is Long -> value
                is Int -> value.toLong()
                else -> null
            }
        }
        .firstOrNull()
}

/**
 * Kills [process] and its descendants, children first. [signal] delivers the kill to a pid; on
 * Android that is `Process.killProcess`, which is why the default is injected rather than hard-wired.
 */
internal fun terminateProcessTree(
    process: Process,
    extraRootPids: List<Long>,
    procRoot: File,
    signal: (Long) -> Unit,
) {
    val roots =
        linkedSetOf<Long>().apply {
            processId(process)?.let(::add)
            addAll(extraRootPids)
        }
    val order =
        roots
            .flatMap { root -> processTreePostOrder(root) { pid -> readDirectChildPids(pid, procRoot) } }
            .distinct()

    if (process.isAlive) {
        process.destroy()
        process.waitFor(750, TimeUnit.MILLISECONDS)
    }
    order.forEach { pid -> runCatching { signal(pid) } }
    if (process.isAlive && !process.waitFor(2, TimeUnit.SECONDS)) {
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
