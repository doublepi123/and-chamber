package io.andchamber.app.runtime.install

import android.system.Os
import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.MessageKey
import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.UserFacingException
import io.andchamber.app.runtime.fs.copyTreeNoFollow
import io.andchamber.app.runtime.fs.deleteTree
import io.andchamber.app.runtime.fs.deleteTreeOrThrow
import io.andchamber.app.runtime.manifest.RuntimeManifest
import io.andchamber.app.runtime.network.ResolvConf
import io.andchamber.app.runtime.network.SystemDnsProvider
import io.andchamber.app.runtime.process.EmbeddedCommandSuite
import io.andchamber.app.runtime.process.ProcessScanner
import io.andchamber.app.runtime.process.ProotCommands
import io.andchamber.app.runtime.process.terminateProcessTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/** Bounded wait for a guest command's output reader after its stream is closed. */
internal const val OUTPUT_JOIN_MILLIS = 2_000L

/**
 * Builds the Alpine + Node + OpenChamber environment in staging and activates it. Each guest
 * command's output is streamed to [InstallReporter.log] and to `logs/install.log`.
 * Ported and trimmed from AndCode's LocalRuntimeInstaller (MIT).
 *
 * The active environment is never touched until the staged one is complete and verified: the
 * swap in [activateRuntimeEnvironment] is the only mutation of it. The guest `/root` lives outside
 * the environment (see [RuntimePaths.home]), so a rebuild does not delete user data.
 */
class OpenChamberInstaller(
    private val paths: RuntimePaths,
    private val abi: String,
    private val manifest: RuntimeManifest,
    private val downloader: VerifiedRuntimeDownloader,
    private val shimAsset: () -> String,
    private val dnsProvider: SystemDnsProvider = SystemDnsProvider { emptyList() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val killPid: (Long) -> Unit = { pid -> android.os.Process.killProcess(pid.toInt()) },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Full install: download, extract, packages, OpenChamber, native modules, verify, activate. */
    suspend fun install(
        report: InstallReporter,
        proot: EmbeddedCommandSuite.Paths,
    ): InstalledRuntime =
        withContext(Dispatchers.IO) {
            val architecture = manifest.architecture(abi)
            paths.cache.mkdirs()
            paths.logs.mkdirs()
            paths.prootTmp.mkdirs()
            paths.home.mkdirs()
            paths.installLog.writeText("")
            deleteTreeOrThrow(paths.staging)
            val staging = paths.staging.apply { mkdirs() }
            try {
                val archive = File(paths.cache, "alpine-${manifest.alpineVersion}-$abi.tar.gz")
                report.step(InstallStep.DownloadRootfs, 0f, "Alpine ${manifest.alpineVersion}")
                downloader.download(
                    url = architecture.alpineUrl,
                    destination = archive,
                    expectedSha256 = architecture.alpineSha256,
                ) { fraction ->
                    report.step(InstallStep.DownloadRootfs, fraction, "Alpine ${manifest.alpineVersion}")
                }

                report.step(InstallStep.ExtractRootfs, null, null, MessageKey.ProgressUnpacking)
                val rootfs = File(staging, "rootfs").apply { mkdirs() }
                archive.inputStream().use { RuntimeArchive.extractTarGz(it, rootfs) }
                configureRootfs(rootfs)
                seedHome(rootfs)

                val env = ProotCommands.installEnvironment(proot.environment(), paths.prootTmp)
                val runner = GuestRunner(paths, proot, rootfs, env, report, killPid = killPid)

                report.step(InstallStep.InstallPackages, null, "apk add nodejs npm git ...", MessageKey.ProgressInstallingPackages)
                // set -e: any failing command fails the step, even inside a chain.
                runner.mustSucceed(
                    "set -e; export PATH=${ProotCommands.GUEST_PATH}; " +
                        "apk --cache-dir /var/cache/apk add $APK_PACKAGES; " +
                        "update-ca-certificates",
                    MessageKey.PackagesFailed,
                    "Unable to install Linux packages. Check the network and retry.",
                    timeoutMinutes = APK_TIMEOUT_MINUTES,
                )

                // Shim before npm: install-time scripts also run under node and benefit from it.
                File(rootfs, SHIM_GUEST_PATH.removePrefix("/")).apply {
                    parentFile?.mkdirs()
                    writeText(shimAsset())
                }

                report.step(InstallStep.InstallOpenChamber, null, "npm i @openchamber/web@${manifest.openChamberVersion}")
                runner.mustSucceed(
                    "set -e; npm i -g --no-fund --no-audit " +
                        "@openchamber/web@${manifest.openChamberVersion} " +
                        "@opencode/cli@${manifest.openCodeVersion}",
                    MessageKey.OpenChamberInstallFailed,
                    "Unable to install OpenChamber from npm.",
                    timeoutMinutes = NPM_TIMEOUT_MINUTES,
                )

                // The node-pty prebuild is glibc-linked and segfaults on musl, so a source rebuild is
                // required. A build failure fails the install (recoverable; the old environment stays).
                report.step(InstallStep.BuildNativeModules, null, "node-pty for musl", MessageKey.ProgressBuildingNative)
                runner.mustSucceed(
                    "set -e; cd $OPENCHAMBER_MODULE_ROOT/node_modules/node-pty && npx --yes node-gyp rebuild",
                    MessageKey.NativeBuildFailed,
                    "Unable to build node-pty for the terminal feature. Check the network and retry.",
                    timeoutMinutes = NATIVE_TIMEOUT_MINUTES,
                )
                File(rootfs, PTY_SMOKE_GUEST_PATH.removePrefix("/")).apply {
                    parentFile?.mkdirs()
                    writeText(PTY_SMOKE_SCRIPT)
                }
                runner.mustSucceed(
                    "set -e; node $PTY_SMOKE_GUEST_PATH",
                    MessageKey.PtySmokeFailed,
                    "The rebuilt node-pty does not work in the guest. The install is incomplete.",
                    timeoutMinutes = PTY_SMOKE_TIMEOUT_MINUTES,
                )

                report.step(InstallStep.Verify, null, "node, opencode, web bundle", MessageKey.ProgressVerifying)
                val nodeVersion = extractVersion(runner.output("node --version"))
                // Prints `opencode v2.0.32`: parse the number, never the raw line.
                val opencodeVersion = extractVersion(runner.output("opencode --version"))
                require(compareVersions(opencodeVersion, MIN_OPENCODE_VERSION) >= 0) {
                    "OpenCode $opencodeVersion is older than the required $MIN_OPENCODE_VERSION"
                }
                require(File(rootfs, "$OPENCHAMBER_MODULE_ROOT/dist/index.html").isFile) {
                    "OpenChamber web bundle is missing after install"
                }

                val metadata =
                    RuntimeMetadata(
                        runtimeVersion = manifest.runtimeVersion,
                        alpineVersion = manifest.alpineVersion,
                        abi = abi,
                        port = manifest.port,
                        openChamberVersion = manifest.openChamberVersion,
                        openCodeVersion = opencodeVersion.removePrefix("v"),
                        nodeVersion = nodeVersion.removePrefix("v"),
                        installedAt = nowMillis(),
                    )
                // Metadata is written inside the environment, so the directory rename commits it too.
                File(staging, RuntimePaths.METADATA_FILE_NAME).writeText(json.encodeToString(metadata))

                report.step(InstallStep.Verify, null, null, MessageKey.ProgressActivating)
                activateRuntimeEnvironment(
                    active = paths.environment,
                    staging = staging,
                    rollback = paths.rollback,
                )
                report.step(InstallStep.Verify, 1f, null, MessageKey.ProgressDone)
                InstalledRuntime(metadata = metadata, rootfs = paths.rootfs)
            } catch (error: Throwable) {
                // Best effort: a leftover staging tree is removed before the next install anyway.
                deleteTree(staging)
                throw error
            }
        }

    /**
     * Copies the previous /root (legacy layout, inside the environment) into the persistent home,
     * or the fresh Alpine skeleton, the first time. Runs before activation, while the old
     * environment is still in place. Never overwrites a home that already has content.
     */
    private fun seedHome(rootfs: File) {
        if (paths.home.list()?.isNotEmpty() == true) return
        val legacy = File(paths.rootfs, "root")
        val skeleton = File(rootfs, "root")
        val source = listOf(legacy, skeleton).firstOrNull { it.isDirectory } ?: return
        copyTreeNoFollow(source, paths.home)
    }

    private fun configureRootfs(rootfs: File) {
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").apply {
            mkdirs()
            setWritable(true, false)
            setExecutable(true, false)
        }
        ResolvConf.write(rootfs, dnsProvider.dnsServers())
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        // The compact Alpine archive omits a SONAME symlink that apk loads by name.
        val libApk = File(rootfs, "usr/lib/libapk.so.3")
        if (!libApk.exists() && File(rootfs, "usr/lib/libapk.so.3.0.0").isFile) {
            Os.symlink("libapk.so.3.0.0", libApk.absolutePath)
        }
    }

    /** Runs `/bin/sh -lc` commands inside one rootfs, streaming output to the install log. */
    internal class GuestRunner(
        private val paths: RuntimePaths,
        private val proot: EmbeddedCommandSuite.Paths,
        private val rootfs: File,
        private val environment: Map<String, String>,
        private val report: InstallReporter,
        private val procRoot: File = File("/proc"),
        private val killPid: (Long) -> Unit = { pid -> android.os.Process.killProcess(pid.toInt()) },
    ) {
        /**
         * Returns the exit code. When [timeoutMinutes] passes, or the calling thread is interrupted,
         * the whole PRoot tree is killed and the call throws only after the process has exited.
         */
        fun run(
            script: String,
            timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES,
            observe: (String) -> Unit = {},
        ): Int {
            val apkCache = File(paths.cache, "apk").apply { mkdirs() }
            File(rootfs, "var/cache/apk").mkdirs()
            val command =
                ProotCommands.guestShellCommand(
                    proot = proot.proot,
                    rootfs = rootfs,
                    home = paths.home,
                    extraBinds = listOf("${apkCache.absolutePath}:/var/cache/apk"),
                    script = script,
                )
            val process = start(command, redirectToLog = true)
            val pump =
                GuestOutputPump(
                    input = process.inputStream,
                    childAlive = { process.isAlive },
                    onLine = { line ->
                        report.log(line)
                        observe(line)
                        runCatching { paths.installLog.appendText(line + "\n") }
                    },
                )
            pump.start()
            // The teardown deadline starts inside awaitAndTearDown, after the command has finished.
            pump.awaitAndTearDown(
                awaitCommand = { awaitExit(process, timeoutMinutes, TimeUnit.MINUTES) },
                teardownBudgetNanos = OUTPUT_JOIN_MILLIS * 1_000_000L,
                timeoutFailure = {
                    IllegalStateException("Command timed out after $timeoutMinutes minutes: ${script.take(120)}")
                },
                readerFailure = { cause ->
                    IllegalStateException("Output reader failed: ${cause.message}", cause)
                },
            )
            return process.exitValue()
        }

        /**
         * Like [run], but a non-zero exit fails the step. When the output shows the mirrors were
         * unreachable, the reason says so instead of the generic message, and stays recoverable.
         */
        fun mustSucceed(
            script: String,
            failure: MessageKey,
            failureText: String,
            timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES,
        ) {
            // Lines arrive on the output reader thread. The reader may still be running (abandoned on a
            // deadline), so the consumer works on a thread-safe snapshot, not the live list.
            val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
            val exit = run(script, timeoutMinutes) { line -> lines += line }
            if (exit == 0) return
            val snapshot = synchronized(lines) { lines.toList() }
            // Mirror problems are the common cause on a phone; name them instead of a generic failure.
            val unreachable = snapshot.any(NetworkFailure::isNetworkOutput)
            val key = if (unreachable) MessageKey.MirrorsUnreachable else failure
            val text = if (unreachable) NetworkFailure.MESSAGE else failureText
            throw UserFacingException(key, "$text (exit $exit)", detail = snapshot.takeLast(MAX_DETAIL_LINES).joinToString("\n"))
        }

        /** Captures stdout of a short, quiet guest command. Timeouts kill the tree, like [run]. */
        fun output(script: String): String {
            val capture = File.createTempFile("verify-", ".out", paths.logs)
            try {
                val command =
                    ProotCommands.guestShellCommand(proot.proot, rootfs, paths.home, emptyList(), script)
                val process = start(command, redirectToLog = false, out = capture)
                require(awaitExit(process, VERIFY_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    "Verification timed out: $script"
                }
                require(process.exitValue() == 0) { "Verification failed: $script" }
                return capture.readText()
            } finally {
                capture.delete()
            }
        }

        /**
         * Waits for [process]. If it does not exit in time, or waiting is interrupted, kills its whole
         * tree first, so no guest process is left writing into a directory the caller then deletes.
         */
        private fun awaitExit(
            process: Process,
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            var exited = false
            try {
                exited = process.waitFor(timeout, unit)
                return exited
            } finally {
                if (!exited) {
                    // Owned launchers for this rootfs (uid + argv guarded by the scanner). The start time
                    // comes from /proc, so a recycled pid is never signalled. terminateProcessTree waits
                    // for the kills to land before returning.
                    val scanner =
                        ProcessScanner(
                            procRoot = procRoot,
                            myUid = android.os.Process.myUid(),
                            myPid = android.os.Process.myPid().toLong(),
                        )
                    val roots = scanner.ownedRoots(proot.proot.absolutePath, rootfs.absolutePath)
                    terminateProcessTree(process, scanner, roots, killPid)
                }
            }
        }

        private fun start(
            command: List<String>,
            redirectToLog: Boolean,
            out: File? = null,
        ): Process =
            ProcessBuilder(command)
                .directory(paths.root)
                .redirectErrorStream(true)
                .apply {
                    if (out != null) redirectOutput(out)
                    else if (!redirectToLog) redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))

                    environment().clear()
                    environment().putAll(this@GuestRunner.environment)
                }
                .start()
    }

    companion object {
        /** Alpine packages. The build toolchain is needed to rebuild node-pty for musl. */
        val APK_PACKAGES =
            listOf(
                "nodejs", "npm", "bash", "git", "curl", "ripgrep", "openssh-client",
                "ca-certificates", "libstdc++", "python3", "make", "g++", "linux-headers",
            ).joinToString(" ")

        /** How many trailing output lines travel with a failure, for the technical detail. */
        const val MAX_DETAIL_LINES = 40

        const val OPENCHAMBER_MODULE_ROOT = "/usr/local/lib/node_modules/@openchamber/web"
        const val SHIM_GUEST_PATH = "/opt/andchamber/android-shim.cjs"
        const val PTY_SMOKE_GUEST_PATH = "/opt/andchamber/pty-smoke.cjs"
        const val MIN_OPENCODE_VERSION = "2.0.20"
        const val DEFAULT_TIMEOUT_MINUTES = 20L
        const val APK_TIMEOUT_MINUTES = 20L
        const val NPM_TIMEOUT_MINUTES = 30L
        const val NATIVE_TIMEOUT_MINUTES = 15L
        const val PTY_SMOKE_TIMEOUT_MINUTES = 2L
        const val VERIFY_TIMEOUT_MINUTES = 2L

        /**
         * Spawns `/bin/sh -c 'echo PTYOK'` through the rebuilt node-pty and requires the output and
         * exit 0. Its own timer bounds the run so a hung PTY cannot stall the install.
         */
        val PTY_SMOKE_SCRIPT =
            """
            const pty = require('$OPENCHAMBER_MODULE_ROOT/node_modules/node-pty');
            let out = '';
            const timer = setTimeout(() => { console.error('PTY smoke timed out: ' + out); process.exit(1); }, 20000);
            const term = pty.spawn('/bin/sh', ['-c', 'echo PTYOK'], { cols: 80, rows: 24, cwd: '/root', env: process.env });
            term.onData((data) => { out += data; });
            term.onExit(({ exitCode }) => {
              clearTimeout(timer);
              if (exitCode === 0 && out.includes('PTYOK')) { console.log('PTYOK'); process.exit(0); }
              console.error('PTY smoke failed: exit ' + exitCode + ' output ' + out);
              process.exit(1);
            });
            """.trimIndent() + "\n"

        private val VERSION_PATTERN = Regex("""\d+(?:\.\d+)+""")

        /** First dotted version in a tool's output: `opencode v2.0.32` -> `2.0.32`, `v24.18.1` -> `24.18.1`. */
        fun extractVersion(output: String): String = VERSION_PATTERN.find(output)?.value ?: output.trim().removePrefix("v")

        /** Dotted numeric comparison of the versions found in each string; missing segments count as 0. */
        fun compareVersions(
            left: String,
            right: String,
        ): Int {
            val a = extractVersion(left).split('.').map { it.toIntOrNull() ?: 0 }
            val b = extractVersion(right).split('.').map { it.toIntOrNull() ?: 0 }
            for (index in 0 until maxOf(a.size, b.size)) {
                val diff = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
