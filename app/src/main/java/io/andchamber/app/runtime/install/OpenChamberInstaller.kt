package io.andchamber.app.runtime.install

import android.system.Os
import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.manifest.RuntimeManifest
import io.andchamber.app.runtime.process.EmbeddedCommandSuite
import io.andchamber.app.runtime.network.ResolvConf
import io.andchamber.app.runtime.network.SystemDnsProvider
import io.andchamber.app.runtime.process.ProotCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the Alpine + Node + OpenChamber environment in staging and activates it. Each guest
 * command's output is streamed to [InstallReporter.log] and to `logs/install.log`.
 * Ported and trimmed from AndCode's LocalRuntimeInstaller (MIT).
 */
class OpenChamberInstaller(
    private val paths: RuntimePaths,
    private val abi: String,
    private val manifest: RuntimeManifest,
    private val downloader: VerifiedRuntimeDownloader,
    private val shimAsset: () -> String,
    private val dnsProvider: SystemDnsProvider = SystemDnsProvider { emptyList() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
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
            paths.installLog.writeText("")
            val staging = paths.staging.apply { deleteRecursively(); mkdirs() }
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

                report.step(InstallStep.ExtractRootfs, null, "Unpacking the Linux userland")
                val rootfs = File(staging, "rootfs").apply { mkdirs() }
                archive.inputStream().use { RuntimeArchive.extractTarGz(it, rootfs) }
                configureRootfs(rootfs)

                val env = ProotCommands.installEnvironment(proot.environment(), paths.prootTmp)
                val runner = GuestRunner(paths, proot, rootfs, env, report)

                report.step(InstallStep.InstallPackages, null, "apk add nodejs npm git ...")
                // set -e: any failing command fails the step, even inside a chain.
                runner.mustSucceed(
                    "set -e; export PATH=${ProotCommands.GUEST_PATH}; " +
                        "apk --cache-dir /var/cache/apk add $APK_PACKAGES; " +
                        "update-ca-certificates",
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
                    "Unable to install OpenChamber from npm.",
                    timeoutMinutes = NPM_TIMEOUT_MINUTES,
                )

                // The node-pty prebuild is glibc-linked and segfaults on musl; a source rebuild works.
                // Failure only costs the terminal feature, so it is logged and the install continues.
                report.step(InstallStep.BuildNativeModules, null, "Building node-pty for musl")
                val ptyExit =
                    runCatching {
                        runner.run(
                            "cd $OPENCHAMBER_MODULE_ROOT/node_modules/node-pty && npx --yes node-gyp rebuild",
                            timeoutMinutes = NATIVE_TIMEOUT_MINUTES,
                        )
                    }.getOrElse { error ->
                        report.log("node-pty rebuild error: ${error.message}")
                        -1
                    }
                if (ptyExit != 0) {
                    report.log("Warning: node-pty did not build; the terminal feature will be unavailable.")
                }

                report.step(InstallStep.Verify, null, "Checking node, opencode and the web bundle")
                val nodeVersion = runner.output("node --version").trim()
                val opencodeVersion = runner.output("opencode --version").trim()
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
                File(staging, RuntimePaths.METADATA_FILE_NAME).writeText(json.encodeToString(metadata))

                report.step(InstallStep.Verify, null, "Activating the environment")
                activateRuntimeEnvironment(
                    active = paths.environment,
                    staging = staging,
                    rollback = paths.rollback,
                    finalizeActivation = { activated ->
                        replaceFileAtomically(
                            source = File(activated, RuntimePaths.METADATA_FILE_NAME),
                            destination = paths.metadataFile,
                        )
                    },
                )
                report.step(InstallStep.Verify, 1f, "Done")
                InstalledRuntime(metadata = metadata, rootfs = paths.rootfs)
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }

    private fun configureRootfs(rootfs: File) {
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").apply {
            mkdirs()
            setWritable(true, false)
            setExecutable(true, false)
        }
        File(rootfs, "workspace").mkdirs()
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
    ) {
        /** Returns the exit code; throws when the command exceeds [timeoutMinutes]. */
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
                    extraBinds = listOf("${apkCache.absolutePath}:/var/cache/apk"),
                    script = script,
                )
            val process = start(command, redirectToLog = true)
            val reader =
                Thread({
                    process.inputStream.bufferedReader().forEachLine { line ->
                        report.log(line)
                        observe(line)
                        runCatching { paths.installLog.appendText(line + "\n") }
                    }
                }, "openchamber-install-output").apply { isDaemon = true }
            reader.start()
            val completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            if (!completed) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                error("Command timed out after $timeoutMinutes minutes: ${script.take(120)}")
            }
            reader.join(2_000)
            return process.exitValue()
        }

        /**
         * Like [run], but a non-zero exit fails the step. When the output shows the mirrors were
         * unreachable, the reason says so instead of the generic message, and stays recoverable.
         */
        fun mustSucceed(
            script: String,
            failure: String,
            timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES,
        ) {
            val lines = mutableListOf<String>()
            val exit = run(script, timeoutMinutes) { line -> lines += line }
            if (exit == 0) return
            val reason = if (lines.any(NetworkFailure::isNetworkOutput)) NetworkFailure.MESSAGE else failure
            error("$reason (exit $exit)")
        }

        /** Captures stdout of a short, quiet guest command. */
        fun output(script: String): String {
            val capture = File.createTempFile("verify-", ".out", paths.logs)
            try {
                val command = ProotCommands.guestShellCommand(proot.proot, rootfs, emptyList(), script)
                val process = start(command, redirectToLog = false, out = capture)
                require(process.waitFor(2, TimeUnit.MINUTES)) { "Verification timed out: $script" }
                require(process.exitValue() == 0) { "Verification failed: $script" }
                return capture.readText()
            } finally {
                capture.delete()
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

        const val OPENCHAMBER_MODULE_ROOT = "/usr/local/lib/node_modules/@openchamber/web"
        const val SHIM_GUEST_PATH = "/opt/andchamber/android-shim.cjs"
        const val MIN_OPENCODE_VERSION = "2.0.20"
        const val DEFAULT_TIMEOUT_MINUTES = 20L
        const val APK_TIMEOUT_MINUTES = 20L
        const val NPM_TIMEOUT_MINUTES = 30L
        const val NATIVE_TIMEOUT_MINUTES = 15L

        /** Dotted numeric comparison; non-numeric segments count as 0. */
        fun compareVersions(
            left: String,
            right: String,
        ): Int {
            val a = left.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val b = right.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (index in 0 until maxOf(a.size, b.size)) {
                val diff = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
