package io.andchamber.app.runtime.process

import io.andchamber.app.browser.tool.BrowserToolEndpoint
import io.andchamber.app.phone.tool.PhoneToolEndpoint
import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.install.InstalledRuntime
import io.andchamber.app.runtime.install.RuntimeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * One long-lived `ProotOpenChamberServer` follows a mutable phone enable switch without being
 * rebuilt. A first off launch, then an on launch on the same instance, keeps launch ids increasing
 * (so browser/phone watermarks stay monotonic), and the switch is sampled once per start: the same
 * launch never mixes on/off settings for assets, env or the listener.
 */
class ProotOpenChamberServerPhoneFlagTest {
    @get:Rule val folder = TemporaryFolder()

    private val port = 3000

    private fun runtime() =
        InstalledRuntime(
            metadata =
                RuntimeMetadata(
                    runtimeVersion = "r",
                    alpineVersion = "3.24.1",
                    abi = "arm64-v8a",
                    port = port,
                    openChamberVersion = "2.4.0",
                    openCodeVersion = "2.0.32",
                    installedAt = 1L,
                ),
            rootfs = File(folder.root, "rootfs"),
        )

    /** Child whose stdout stays open until [destroy], so the server can become ready and stop. */
    private class OpenOutputChild(output: String) : Process() {
        private val pipe = java.io.PipedInputStream()
        private val writer = java.io.PipedOutputStream(pipe)
        private val alive = java.util.concurrent.atomic.AtomicBoolean(true)

        init {
            writer.write(output.toByteArray())
            writer.flush()
        }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = pipe

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            while (isAlive) Thread.sleep(5)
            return 0
        }

        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("still running")
            return 0
        }

        override fun destroy() {
            alive.set(false)
            writer.close()
        }

        override fun isAlive(): Boolean = alive.get()
    }

    private class ReadyHealth : HealthProbe {
        override fun read(port: Int): HealthReport =
            HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "server-1")
    }

    private class RecordingBrowserToolHost : BrowserToolHost {
        val started = mutableListOf<Long>()
        val stopped = mutableListOf<Long>()

        override fun start(
            launchId: Long,
            home: File,
            trustedRoot: File,
        ): Boolean {
            started += launchId
            return true
        }

        override fun stop(launchId: Long) {
            stopped += launchId
        }

        override fun clearStaleEndpoint(
            home: File,
            trustedRoot: File,
        ): Boolean = BrowserToolEndpoint.delete(BrowserToolEndpoint.hostFile(home), trustedRoot)
    }

    private class RecordingPhoneToolHost : PhoneToolHost {
        val started = mutableListOf<Long>()
        val stopped = mutableListOf<Long>()

        override fun start(
            launchId: Long,
            home: File,
            trustedRoot: File,
        ): Boolean {
            started += launchId
            return true
        }

        override fun stop(launchId: Long) {
            stopped += launchId
        }

        override fun clearStaleEndpoint(
            home: File,
            trustedRoot: File,
        ): Boolean = PhoneToolEndpoint.delete(PhoneToolEndpoint.hostFile(home), trustedRoot)
    }

    @Test
    fun `one instance samples the flag per launch and keeps launch ids monotonic`() {
        var samples = 0
        var phoneAssetReads = 0
        val envs = mutableListOf<Map<String, String>>()
        val browserTool = RecordingBrowserToolHost()
        val phoneTool = RecordingPhoneToolHost()

        val server =
            ProotOpenChamberServer(
                paths = RuntimePaths(folder.root),
                suite = {
                    EmbeddedCommandSuite.Paths(
                        home = folder.root,
                        tmp = folder.root,
                        nativeLibraryDirectory = folder.root,
                        proot = File(folder.root, "libandchamber_android_proot.so"),
                        loader = folder.root,
                        loader32 = folder.root,
                    )
                },
                health = ReadyHealth(),
                scanner = ProcessScanner(folder.newFolder("proc"), myUid = 1, myPid = 2L),
                cleanBeforeStart = { _, _, _ -> true },
                readyTimeoutMillis = 2_000L,
                pollMillis = 1L,
                browserPluginAssets = { BrowserPluginAssets.Assets("browser-package", "browser-index") },
                browserTrustedRoot = folder.root,
                // The lambda, not this constructor Boolean, decides each launch: first sample is
                // off even though the legacy value is on. A later re-sample would return false,
                // so a mixed second launch would fail the assertions below.
                phonePluginEnabled = true,
                phonePluginEnabledNow = {
                    samples += 1
                    samples == 2
                },
                phonePluginAssets = {
                    phoneAssetReads += 1
                    PhonePluginAssets.Assets("phone-package", "phone-index")
                },
                phoneTool = phoneTool,
                browserTool = browserTool,
                sleep = { Thread.sleep(1) },
                spawn = { _, env, _ ->
                    envs += env
                    OpenOutputChild("OpenChamber server listening on 127.0.0.1:$port\n")
                },
            )

        try {
            // Launch 1: the sampled flag is off.
            assertEquals(1L, server.start(runtime(), "pw", {}, { false }))
            assertEquals("the flag is sampled exactly once per start", 1, samples)
            assertEquals("phone assets are not read for an off launch", 0, phoneAssetReads)
            assertEquals(listOf(1L), browserTool.started)
            assertTrue("the phone listener is not started for an off launch", phoneTool.started.isEmpty())
            assertFalse(envs[0].containsKey(PhonePluginAssets.TOOL_ENV))
            val firstConfig = envs[0].getValue(BrowserPluginAssets.CONFIG_ENV)
            assertFalse(firstConfig.contains(PhonePluginAssets.GUEST_DIR))
            assertTrue(firstConfig.contains(BrowserPluginAssets.GUEST_DIR))

            server.stop()

            // Launch 2 on the same instance: the sampled flag is on and the launch id grew. Both
            // bindings must accept it; a reset id would be rejected by the old stop watermarks.
            assertEquals(2L, server.start(runtime(), "pw", {}, { false }))
            assertEquals(2, samples)
            assertEquals("phone assets sync for the on launch", 1, phoneAssetReads)
            assertEquals("the browser binding accepts the incremented launch", listOf(1L, 2L), browserTool.started)
            assertEquals("the phone listener accepts the incremented launch", listOf(2L), phoneTool.started)
            assertTrue(envs[1].containsKey(PhonePluginAssets.TOOL_ENV))
            val secondConfig = envs[1].getValue(PhonePluginAssets.CONFIG_ENV)
            assertTrue(secondConfig.contains(PhonePluginAssets.GUEST_DIR))
            assertTrue(secondConfig.contains(BrowserPluginAssets.GUEST_DIR))
        } finally {
            server.stop()
        }
    }
}
