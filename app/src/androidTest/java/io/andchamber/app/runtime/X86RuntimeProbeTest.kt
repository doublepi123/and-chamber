package io.andchamber.app.runtime

import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.andchamber.app.runtime.install.RuntimeArchive
import io.andchamber.app.runtime.process.EmbeddedCommandSuite
import io.andchamber.app.runtime.process.ProotCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * REPRODUCTION PROBE for the x86_64 local runtime. Not part of the production gate: it runs the real
 * PRoot launcher from the app's native library directory against an official Alpine x86_64 rootfs, with
 * the exact argv and environment that ProotCommands builds. Each guest command is time-bounded and its
 * PID, exit status and output are logged under the tag [TAG]. A blocked step fails with the exact reason.
 *
 * GATES (PRoot 5.1.107.98, x86_64 emulator, independent probe application id). All must pass:
 *  - [deviceAbiAndKernel], [prootLauncherPresentAndExecutable]: environment.
 *  - [guestTrueBounded], [guestShellPrintf], [guestVforkProbeDirect], [guestSyscallProbeForkFamily]:
 *    guest exec, shell, vfork and glibc fork/clone.
 *  - [guestShellForksExternalChild]: busybox /bin/sh forks an external command (was blocked on 5.1.107.97
 *    by Android's app seccomp policy on SYS_fork; fixed by 5.1.107.98's fork -> clone rewrite).
 *  - [guestRawForkRegisters]: raw SYS_fork (57) with register preservation on both sides.
 *
 * DIAGNOSTICS (never gates; they only record output): [guestDiagnosticListRoot],
 * [guestDiagnosticShellForkTrace], [guestDiagnosticProotTraceExecProbe], [guestDiagnosticAppNamespacePaths].
 *
 * Inputs are NOT verified by this test: the Alpine tarball and the probe binaries are checked by
 * scripts/provision_x86_probe.py (SHA-256 against runtime-manifest.json and the built outputs). The test only
 * checks that the files exist and runs them.
 *
 * Inputs are provisioned by scripts/provision_x86_probe.py and pushed by scripts/push_x86_probe.sh; see
 * docs/testing-x86-runtime.md. The test never downloads and never runs npm.
 */
@RunWith(AndroidJUnit4::class)
class X86RuntimeProbeTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val paths = RuntimePaths.under(context.filesDir)

    private data class Outcome(
        val label: String,
        val pid: Long,
        val exit: Int?,
        val timedOut: Boolean,
        val output: String,
    )

    /** Starts [command] with [environment], waits at most [timeoutSeconds], logs everything. */
    private fun runBounded(
        label: String,
        command: List<String>,
        guestEnvironment: Map<String, String>,
        workDir: File,
        timeoutSeconds: Long = 20L,
    ): Outcome {
        val process =
            ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(guestEnvironment)
                }
                .start()
        val pid = processPid(process)
        Log.i(TAG, "[$label] pid=$pid argv=${command.joinToString(" ")}")
        // Drain stdout concurrently: a verbose PRoot trace fills the pipe, and waiting before reading would
        // block the child on write. The reader is bounded by the same deadline as the wait.
        val buffer = StringBuilder()
        val reader =
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { synchronized(buffer) { buffer.append(it).append('\n') } }
                    }
                }
            }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            Log.e(TAG, "[$label] pid=$pid TIMEOUT after ${timeoutSeconds}s; destroying own child")
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        // Closing the stream unblocks the reader after an interrupted child; a bounded join never hangs the test.
        if (!finished) runCatching { process.inputStream.close() }
        reader.join(5_000)
        val output = synchronized(buffer) { buffer.toString() }
        val exit = if (finished) process.exitValue() else null
        Log.i(TAG, "[$label] pid=$pid exit=${exit ?: "timeout"} output<<<$output>>>")
        return Outcome(label, pid, exit, !finished, output)
    }

    private fun processPid(process: Process): Long =
        runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getLong(process)
        }.getOrDefault(-1L)

    /** The exact PRoot environment of the installer (no server secrets), built from the app's own suite. */
    private fun suite(): EmbeddedCommandSuite.Paths {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        return EmbeddedCommandSuite(nativeDir, paths).resolve()
    }

    /**
     * Extracts the Alpine tarball once into the app's own runtime root. The host pushes the tarball to
     * [TARBALL_IN_APP] (the app's cache dir, via run-as), so the app UID can read it. The extracted tree
     * is reused on later runs.
     */
    private fun prepareRootfs(): File {
        ensureRuntimeDirs()
        val target = File(paths.root, "x86probe-rootfs")
        if (!File(target, "bin/sh").exists()) {
            target.deleteRecursively()
            target.mkdirs()
            val tarball = File(context.cacheDir, ALPINE_TARBALL)
            check(tarball.isFile) { "Alpine tarball missing at ${tarball.absolutePath}; scripts/push_x86_probe.sh pushes it there" }
            tarball.inputStream().use { RuntimeArchive.extractTarGz(it, target) }
        }
        // Always refreshed, so a re-provisioned probe binary is never shadowed by an older copy in the rootfs.
        installProbeBinaries(target)
        return target
    }

    /** Copies the pushed static probes into the rootfs at the paths the tests run them from. */
    private fun installProbeBinaries(rootfs: File) {
        val source = File(context.cacheDir, "x86-probe")
        val bin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val map = mapOf("sysprobe" to "sysprobe", "vforkasm" to "vforkprobe", "fork_regs" to "forkregs")
        for ((pushed, installed) in map) {
            val from = File(source, pushed)
            check(from.isFile) { "probe binary $pushed missing at ${from.absolutePath}; run scripts/push_x86_probe.sh" }
            val to = File(bin, installed)
            from.copyTo(to, overwrite = true)
            check(to.setExecutable(true, false)) { "cannot mark $installed executable" }
        }
    }

    /** The directories the production installer creates before any guest command (PRoot needs them). */
    private fun ensureRuntimeDirs() {
        paths.home.mkdirs()
        paths.prootTmp.mkdirs()
        paths.logs.mkdirs()
        paths.cache.mkdirs()
    }

    @Test
    fun deviceAbiAndKernel() {
        Log.i(TAG, "abi=${Build.SUPPORTED_ABIS.joinToString(",")} kernel=${System.getProperty("os.version")}")
        assertTrue("probe must run on x86_64, got ${Build.SUPPORTED_ABIS.toList()}", Build.SUPPORTED_ABIS.contains("x86_64"))
    }

    @Test
    fun prootLauncherPresentAndExecutable() {
        val resolved = suite()
        Log.i(TAG, "proot=${resolved.proot.absolutePath} loader=${resolved.loader.absolutePath} loader32=${resolved.loader32.absolutePath}")
        assertTrue(resolved.proot.canExecute())
    }

    @Test
    fun guestTrueBounded() {
        val resolved = suite()
        val rootfs = prepareRootfs()
        val env = ProotCommands.installEnvironment(resolved.environment(), paths.prootTmp)
        val out =
            runBounded(
                "guest-true",
                ProotCommands.prootArguments(resolved.proot, rootfs, emptyList()) + listOf("/bin/true"),
                env,
                paths.root,
            )
        assertEquals("guest /bin/true exit (pid=${out.pid})", 0, out.exit)
    }

    @Test
    fun guestShellPrintf() {
        val resolved = suite()
        val rootfs = prepareRootfs()
        val env = ProotCommands.installEnvironment(resolved.environment(), paths.prootTmp)
        val out =
            runBounded(
                "guest-shell-printf",
                ProotCommands.guestShellCommand(resolved.proot, rootfs, paths.home, emptyList(), "printf 'SHELL_OK\\n'"),
                env,
                paths.root,
            )
        assertEquals("guest shell exit (pid=${out.pid})", 0, out.exit)
        assertTrue("guest shell output: ${out.output}", out.output.contains("SHELL_OK"))
    }

    /**
     * KNOWN BLOCKED (baseline). The step the real server needs: /bin/sh forks an external child. Expected
     * failure on PRoot 5.1.107.97 under Android's app seccomp policy: `can't fork: Function not implemented`
     * (exit 2), with `seccomp SIGSYS: fork ... = 0x39` in PRoot's trace. Do not weaken this assertion.
     */
    @Test
    fun guestShellForksExternalChild() {
        val resolved = suite()
        val rootfs = prepareRootfs()
        val env = ProotCommands.installEnvironment(resolved.environment(), paths.prootTmp)
        val out =
            runBounded(
                "guest-shell-fork",
                ProotCommands.guestShellCommand(resolved.proot, rootfs, paths.home, emptyList(), "/bin/true; echo FORK_OK"),
                env,
                paths.root,
            )
        assertEquals("guest shell fork exit (pid=${out.pid})", 0, out.exit)
        assertTrue("guest fork output: ${out.output}", out.output.contains("FORK_OK"))
    }

    @Test
    fun guestDiagnosticListRoot() {
        val resolved = suite()
        val rootfs = prepareRootfs()
        val env = ProotCommands.installEnvironment(resolved.environment(), paths.prootTmp)
        val out =
            runBounded(
                "guest-ls-root",
                ProotCommands.guestShellCommand(resolved.proot, rootfs, paths.home, emptyList(), "ls -la /root; echo ---; ls -la /root/sysprobe; echo ---; uname -m"),
                env,
                paths.root,
            )
        Log.i(TAG, "guest-ls-root report<<<${out.output}>>> exit=${out.exit}")
    }

    /**
     * Runs the inline-asm vfork probe (`/usr/local/bin/vforkprobe`) directly through PRoot. Busybox /bin/sh
     * forks with vfork, so this isolates that syscall from the shell.
     */
    @Test
    fun guestVforkProbeDirect() {
        val resolved = suite()
        val rootfs = prepareRootfs()
        val env = ProotCommands.installEnvironment(resolved.environment(), paths.prootTmp)
        val probe = File(rootfs, "usr/local/bin/vforkprobe")
        check(probe.isFile) { "vfork probe missing at ${probe.absolutePath}" }
        val out =
            runBounded(
                "guest-vfork-direct",
                ProotCommands.prootArguments(resolved.proot, rootfs, ProotCommands.bindArguments(listOf(ProotCommands.homeBindSpec(paths.home))) + listOf("-w", ProotCommands.GUEST_HOME)) +
                    listOf("/usr/local/bin/vforkprobe"),
                env,
                paths.root,
            )
        Log.i(TAG, "guest-vfork-direct report<<<${out.output}>>>")
        assertEquals("guest vfork probe exit (pid=${out.pid})", 0, out.exit)
        assertTrue("guest vfork probe output: ${out.output}", out.output.contains("vfork child=") && out.output.contains("exit=42"))
    }

    /**
     * Diagnostic: PRoot's trace (-v 9) for `/bin/sh -c '/bin/true; echo FORK_OK'`, the failing server step.
     * Only the syscalls that returned an error are logged, so the first ENOSYS is visible.
     */
    @Test
    fun guestDiagnosticShellForkTrace() {
        val resolved = suite()
        val rootfs = prepareRootfs()
        val env = ProotCommands.installEnvironment(resolved.environment(), paths.prootTmp)
        val command =
            ProotCommands.guestShellCommand(resolved.proot, rootfs, paths.home, emptyList(), "/bin/true; echo FORK_OK")
                .let { it.dropLast(3) + listOf("-v", "9") + it.takeLast(3) }
        val out = runBounded("guest-shell-fork-trace", command, env, paths.root, timeoutSeconds = 60L)
        Log.i(TAG, "guest-shell-fork-trace report<<<${out.output}>>> exit=${out.exit}")
    }

    /** Diagnostic only: PRoot's verbose trace (-v 9) on the probe binary, with a 60 s bound. */
    @Test
    fun guestDiagnosticProotTraceExecProbe() {
        val resolved = suite()
        val rootfs = prepareRootfs()
        val env = ProotCommands.installEnvironment(resolved.environment(), paths.prootTmp)
        val command =
            ProotCommands.prootArguments(resolved.proot, rootfs, listOf("-v", "9")) + listOf(PROBE_GUEST_PATH)
        val out = runBounded("guest-proot-trace-probe", command, env, paths.root, timeoutSeconds = 60L)
        Log.i(TAG, "guest-proot-trace-probe report<<<${out.output}>>> exit=${out.exit}")
    }

    /**
     * WHAT THIS TESTS: the static glibc probe calls `fork()`, which glibc implements as `clone(SIGCHLD)`
     * (not `SYS_fork` 57). It proves guest fork/clone work, but NOT that a raw `SYS_fork` 57 works; that
     * is [guestRawForkRegisters]. Runs the syscall probe (a tiny x86_64 ELF pushed to the guest by the
     * host script) under the exact ProotCommands argv. Its per-call result tells which fork-family syscall
     * the guest gets ENOSYS for. Output is logged, and the assertion requires every call to succeed.
     */
    @Test
    fun guestSyscallProbeForkFamily() {
        val resolved = suite()
        val rootfs = prepareRootfs()
        val probe = File(rootfs, PROBE_GUEST_PATH.removePrefix("/"))
        check(probe.isFile) { "syscall probe missing at ${probe.absolutePath}; host script copies it there" }
        val env = ProotCommands.installEnvironment(resolved.environment(), paths.prootTmp)
        val out =
            runBounded(
                "guest-sysprobe",
                // Exec the probe directly through PRoot, not via /bin/sh: the shell itself needs fork for external
                // commands, so it cannot be the thing under test here.
                ProotCommands.prootArguments(resolved.proot, rootfs, ProotCommands.bindArguments(listOf(ProotCommands.homeBindSpec(paths.home))) + listOf("-w", ProotCommands.GUEST_HOME)) +
                    listOf(PROBE_GUEST_PATH),
                env,
                paths.root,
            )
        Log.i(TAG, "guest-sysprobe report<<<${out.output}>>>")
        assertEquals("guest syscall probe exit (pid=${out.pid})", 0, out.exit)
        // The C vfork line is excluded: C code cannot safely run the vfork child. guestVforkProbeDirect covers vfork.
        val lines = out.output.lines()
        assertTrue("guest getppid failed: ${out.output}", lines.any { it.startsWith("getppid") && !it.contains("rc=-1") })
        assertTrue("guest fork failed: ${out.output}", lines.any { it.startsWith("fork") && it.contains("exit=7") })
        assertTrue("guest clone failed: ${out.output}", lines.any { it.startsWith("clone") && it.contains("exit=9") })
    }

    /**
     * Raw SYS_fork (57) through PRoot, with the upstream register-preservation check (`fork_regs`, raw mode):
     * the parent and the child must both see rdi..r9 unchanged across the syscall. Exit 0 = both preserved.
     * This is the case that tells whether a raw fork reaches the guest at all; the static glibc probe above
     * cannot, because glibc never issues syscall 57.
     */
    @Test
    fun guestRawForkRegisters() {
        val resolved = suite()
        val rootfs = prepareRootfs()
        val probe = File(rootfs, "usr/local/bin/forkregs")
        check(probe.isFile) { "raw fork probe missing at ${probe.absolutePath}; see docs/testing-x86-runtime.md" }
        val env = ProotCommands.installEnvironment(resolved.environment(), paths.prootTmp)
        val out =
            runBounded(
                "guest-raw-fork-regs",
                ProotCommands.prootArguments(resolved.proot, rootfs, ProotCommands.bindArguments(listOf(ProotCommands.homeBindSpec(paths.home))) + listOf("-w", ProotCommands.GUEST_HOME)) +
                    listOf("/usr/local/bin/forkregs"),
                env,
                paths.root,
            )
        assertEquals("guest raw fork register check exit (pid=${out.pid}): ${out.output}", 0, out.exit)
    }

    /**
     * Diagnostic (not a gate): the real canonical path of the environment as seen by the app process, and the
     * symlink direction of /data/data and /data/user/0 in that process's mount namespace. Read-only; written to
     * logcat under [TAG] and to the app's files dir as namespace.txt for the host to collect.
     */
    @Test
    fun guestDiagnosticAppNamespacePaths() {
        val env = paths.environment
        val lines = mutableListOf<String>()
        lines += "absolutePath=${env.absolutePath}"
        lines += "canonicalPath=${env.canonicalPath}"
        lines += "rootfs.canonicalPath=${paths.rootfs.canonicalPath}"
        lines += "filesDir=${context.filesDir.absolutePath} canonical=${context.filesDir.canonicalPath}"
        for (link in listOf("/data/data", "/data/user/0", "/proc/self/root/data/data", "/proc/self/root/data/user/0")) {
            val f = File(link)
            val target = runCatching { java.nio.file.Files.readSymbolicLink(f.toPath()).toString() }.getOrNull()
            lines += "link $link -> ${target ?: "(not a symlink or unreadable)"} exists=${f.exists()} canonical=${runCatching { f.canonicalPath }.getOrDefault("?")}"
        }
        val report = lines.joinToString("\n")
        Log.i(TAG, "namespace report:\n$report")
        runCatching { File(context.filesDir, "namespace.txt").writeText(report + "\n") }
    }

    private companion object {
        const val TAG = "X86RuntimeProbe"
        const val ALPINE_TARBALL = "alpine-x86_64.tar.gz"
        /** Probe location inside the rootfs (no bind mount involved). */
        const val PROBE_GUEST_PATH = "/usr/local/bin/sysprobe"
    }
}
