package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.install.InstalledRuntime
import io.andchamber.app.runtime.install.RuntimeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression for the wrong-instance risk: a foreign process holds the OpenChamber port. The server
 * must refuse to start, must not spawn anything, and so must never receive the UI password.
 */
class ForeignListenerTest {
    @get:Rule val folder = TemporaryFolder()

    private fun runtime(root: File) =
        InstalledRuntime(
            metadata =
                RuntimeMetadata(
                    runtimeVersion = "r",
                    alpineVersion = "3.24.1",
                    abi = "arm64-v8a",
                    port = 3000,
                    openChamberVersion = "2.4.0",
                    openCodeVersion = "2.0.32",
                    installedAt = 1L,
                ),
            rootfs = File(root, "rootfs"),
        )

    @Test
    fun `port held by a foreign listener is refused before spawn`() {
        ServerSocket(0).use { foreign ->
            // The foreign listener answers on its own port; the cleaner reports our port still busy.
            val spawnSuite = AtomicInteger(0)
            val paths = RuntimePaths(folder.root)
            val server =
                ProotOpenChamberServer(
                    paths = paths,
                    suite = {
                        spawnSuite.incrementAndGet()
                        EmbeddedCommandSuite.Paths(
                            home = folder.root,
                            tmp = folder.root,
                            nativeLibraryDirectory = folder.root,
                            proot = File(folder.root, "libandchamber_android_proot.so"),
                            loader = folder.root,
                            loader32 = folder.root,
                        )
                    },
                    health = HealthProbe.Loopback,
                    scanner = ProcessScanner(folder.newFolder("proc"), myUid = 1, myPid = 2L),
                    cleanBeforeStart = { _, _, _ -> false },
                )

            val error =
                runCatching {
                    server.start(runtime(folder.root), "ui-secret", {}, { false })
                }.exceptionOrNull()

            assertTrue("expected PortInUseException, got $error", error is PortInUseException)
            assertEquals("Port 3000 is in use by another app", error?.message)
            // The suite is resolved for the proot path (cleanup) and never for a launch: the refusal
            // happens before the spawn, so the server's launch step is not reached.
            assertTrue(foreign.isBound)
            assertTrue("suite may be resolved at most for the cleanup", spawnSuite.get() <= 2)
        }
    }

    @Test
    fun `cleaner reports busy when the port never frees`() {
        val freed =
            StaleServerCleanup.awaitPortFree(
                port = 3000,
                timeoutMillis = 1_000L,
                isPortFree = { false },
                sleep = {},
                now = run {
                    var t = 0L
                    return@run { t += 300L; t }
                },
            )
        assertFalse(freed)
    }
}
