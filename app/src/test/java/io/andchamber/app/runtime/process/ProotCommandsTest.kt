package io.andchamber.app.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotCommandsTest {
    private val proot = File("/native/libandchamber_proot.so")
    private val rootfs = File("/data/runtime/environment/rootfs")
    private val workspace = File("/data/runtime/workspace")
    private val prootTmp = File("/data/runtime/proot-tmp")
    private val home = File("/data/runtime/home")

    @Test
    fun `guest home is bind-mounted from outside the environment for server and installer`() {
        val server =
            ProotCommands.serverCommand(
                proot = proot,
                rootfs = rootfs,
                home = home,
                workspace = workspace,
                storageArguments = emptyList(),
                port = 3000,
            )
        val installer = ProotCommands.guestShellCommand(proot, rootfs, home, emptyList(), "true")

        assertTrue(server.containsPair("-b", "/data/runtime/home:/root"))
        assertTrue(installer.containsPair("-b", "/data/runtime/home:/root"))
        assertFalse(server.any { it.startsWith(rootfs.absolutePath + "/root") })
    }

    @Test
    fun `server command line binds the rootfs and runs cli serve in the foreground`() {
        val command =
            ProotCommands.serverCommand(
                proot = proot,
                rootfs = rootfs,
                home = home,
                workspace = workspace,
                storageArguments = listOf("-b", "/storage/emulated/0:/sdcard"),
                port = 3000,
            )

        assertEquals(proot.absolutePath, command.first())
        assertTrue("--kill-on-exit" in command)
        assertTrue("--link2symlink" in command)
        assertEquals("-0", command[command.indexOf("-0")])
        assertEquals(rootfs.absolutePath, command[command.indexOf("-r") + 1])
        assertTrue(command.containsPair("-b", "/dev"))
        assertTrue(command.containsPair("-b", "/proc"))
        assertTrue(command.containsPair("-b", "/sys"))
        assertTrue(command.containsPair("-b", "/system"))
        assertTrue(command.containsPair("-b", "${workspace.absolutePath}:/root/projects"))
        assertTrue(command.containsPair("-b", "/storage/emulated/0:/sdcard"))
        assertFalse(command.any { it.endsWith(":/workspace") || it.endsWith(":/workspace-private") })
        assertEquals("/root", command[command.indexOf("-w") + 1])

        val guest = command.subList(command.size - 6, command.size)
        assertEquals(
            listOf(
                "/usr/bin/node",
                "/usr/local/lib/node_modules/@openchamber/web/bin/cli.js",
                "serve",
                "--foreground",
                "--port",
                "3000",
            ),
            guest,
        )
    }

    @Test
    fun `server environment is exactly the guest contract and carries the UI password`() {
        val env =
            ProotCommands.serverEnvironment(
                suiteEnvironment = mapOf("LD_LIBRARY_PATH" to "/native", "PROOT_LOADER" to "/native/loader"),
                prootTmp = prootTmp,
                uiPassword = "secret-password",
            )

        assertEquals("/root", env["HOME"])
        assertEquals("root", env["USER"])
        assertEquals("root", env["LOGNAME"])
        assertEquals("/bin/bash", env["SHELL"])
        assertEquals("/tmp", env["TMPDIR"])
        assertEquals("C.UTF-8", env["LANG"])
        assertEquals(ProotCommands.GUEST_PATH, env["PATH"])
        assertEquals("/root/.config", env["XDG_CONFIG_HOME"])
        assertEquals("/root/.local/share", env["XDG_DATA_HOME"])
        assertEquals(prootTmp.absolutePath, env["PROOT_TMP_DIR"])
        assertEquals("/native", env["LD_LIBRARY_PATH"])
        assertEquals("/native/loader", env["PROOT_LOADER"])

        assertEquals("127.0.0.1", env["OPENCHAMBER_HOST"])
        assertEquals("secret-password", env["OPENCHAMBER_UI_PASSWORD"])
        assertEquals("off", env["OPENCHAMBER_RELAY_HOST"])
        // Native notifications: the server writes its one-line desktop-notify protocol to stdout.
        assertEquals("true", env["OPENCHAMBER_DESKTOP_NOTIFY"])
        assertEquals("true", env["OPENCODE_DISABLE_AUTOUPDATE"])
        assertEquals("/usr/local/bin/opencode", env["OPENCHAMBER_OPENCODE_BIN"])
        assertEquals("--require /opt/andchamber/android-shim.cjs", env["NODE_OPTIONS"])
    }

    @Test
    fun `server environment never enables the relay`() {
        val env = ProotCommands.serverEnvironment(emptyMap(), prootTmp, "pw")
        assertEquals("off", env["OPENCHAMBER_RELAY_HOST"])
    }

    @Test
    fun `install environment has no server secrets and no node shim`() {
        val env = ProotCommands.installEnvironment(emptyMap(), prootTmp)

        assertFalse(env.containsKey("OPENCHAMBER_UI_PASSWORD"))
        assertFalse(env.containsKey("NODE_OPTIONS"))
        assertEquals("/root", env["HOME"])
    }

    @Test
    fun `guest shell command runs the script through sh -lc inside the rootfs`() {
        val command =
            ProotCommands.guestShellCommand(
                proot = proot,
                rootfs = rootfs,
                home = home,
                extraBinds = listOf("/cache/apk:/var/cache/apk"),
                script = "npm --version",
            )

        assertTrue(command.containsPair("-b", "/cache/apk:/var/cache/apk"))
        assertEquals(listOf("/bin/sh", "-lc", "npm --version"), command.takeLast(3))
        assertEquals("/root", command[command.indexOf("-w") + 1])
    }

    @Test
    fun `bind arguments are emitted as -b pairs`() {
        assertEquals(listOf("-b", "/a:/b", "-b", "/c:/d"), ProotCommands.bindArguments(listOf("/a:/b", "/c:/d")))
    }

    private fun List<String>.containsPair(
        flag: String,
        value: String,
    ): Boolean = zipWithNext().any { (a, b) -> a == flag && b == value }
}
