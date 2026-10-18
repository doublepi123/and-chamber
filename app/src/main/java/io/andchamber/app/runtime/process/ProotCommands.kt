package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.storage.GuestBinds
import io.andchamber.app.runtime.storage.SharedStorageLayout
import java.io.File

/**
 * Pure builders for the PRoot command lines and guest environments. Kept free of Android APIs so
 * the exact argv and env are unit-tested.
 */
object ProotCommands {
    const val GUEST_HOME = "/root"
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    const val GUEST_NODE = "/usr/bin/node"
    const val GUEST_OPENCODE = "/usr/local/bin/opencode"
    const val OPENCHAMBER_CLI = "/usr/local/lib/node_modules/@openchamber/web/bin/cli.js"
    const val SHIM_PATH = "/opt/andchamber/android-shim.cjs"

    /** Shared PRoot prefix: kill-on-exit, link2symlink, fake root, rootfs and the Android mounts. */
    fun prootArguments(
        proot: File,
        rootfs: File,
        extraArgs: List<String>,
    ): List<String> =
        buildList {
            add(proot.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            listOf("/dev", "/proc", "/sys", "/system").forEach { mount ->
                add("-b")
                add(mount)
            }
            addAll(extraArgs)
        }

    /** `-b host:guest` pairs for each spec. */
    fun bindArguments(specs: List<String>): List<String> = specs.flatMap { listOf("-b", it) }

    /**
     * Bind spec that maps the persistent [home] (outside the environment) over the guest /root.
     * Applied to every guest command, so config, sessions and credentials survive a rebuild.
     */
    fun homeBindSpec(home: File): String = "${home.absolutePath}:$GUEST_HOME"

    /** A non-interactive `/bin/sh -lc <script>` inside the rootfs, used by the installer. */
    fun guestShellCommand(
        proot: File,
        rootfs: File,
        home: File,
        extraBinds: List<String>,
        script: String,
    ): List<String> =
        prootArguments(
            proot,
            rootfs,
            bindArguments(listOf(homeBindSpec(home)) + extraBinds) + listOf("-w", GUEST_HOME),
        ) +
            listOf("/bin/sh", "-lc", script)

    /** The OpenChamber server, bound to loopback and running in the foreground. Private-only binds. */
    fun serverCommand(
        proot: File,
        rootfs: File,
        home: File,
        workspace: File,
        storageArguments: List<String>,
        port: Int,
    ): List<String> =
        serverCommand(
            proot = proot,
            rootfs = rootfs,
            binds = GuestBinds.privateOnly(home = home, privateWorkspace = workspace),
            extraBinds = storageArguments,
            port = port,
        )

    /**
     * The OpenChamber server with an explicit bind set. The working directory is /workspace, so the
     * shared projects folder is the default project when shared storage is active.
     */
    fun serverCommand(
        proot: File,
        rootfs: File,
        binds: GuestBinds,
        extraBinds: List<String>,
        port: Int,
    ): List<String> =
        prootArguments(
            proot,
            rootfs,
            bindArguments(binds.specs() + extraBinds) + listOf("-w", SharedStorageLayout.GUEST_WORKSPACE),
        ) +
            listOf(GUEST_NODE, OPENCHAMBER_CLI, "serve", "--foreground", "--port", port.toString())

    /** Environment for the long-running server: cleared by the caller, then set to exactly this. */
    fun serverEnvironment(
        suiteEnvironment: Map<String, String>,
        prootTmp: File,
        uiPassword: String,
    ): Map<String, String> =
        buildMap {
            putAll(suiteEnvironment)
            put("PROOT_TMP_DIR", prootTmp.absolutePath)
            putAll(guestBaseEnvironment())
            put("OPENCHAMBER_HOST", "127.0.0.1")
            put("OPENCHAMBER_UI_PASSWORD", uiPassword)
            // The relay phones home to a hosted service; this app never uses it.
            put("OPENCHAMBER_RELAY_HOST", "off")
            // Native notifications: the server writes one `[OpenChamberDesktopNotify] {json}` line per event.
            put("OPENCHAMBER_DESKTOP_NOTIFY", "true")
            put("OPENCODE_DISABLE_AUTOUPDATE", "true")
            put("OPENCHAMBER_OPENCODE_BIN", GUEST_OPENCODE)
            // First boot can be slow (cold node + opencode); the 10 s default timed out while Dozing.
            put("OPENCHAMBER_OPENCODE_HEALTH_TIMEOUT_MS", "15000")
            // Android's SELinux makes os.networkInterfaces() throw; the shim supplies a loopback list.
            put("NODE_OPTIONS", "--require $SHIM_PATH")
        }

    /** Environment for installer steps (apk, npm): no server secrets, no shim. */
    fun installEnvironment(
        suiteEnvironment: Map<String, String>,
        prootTmp: File,
    ): Map<String, String> =
        buildMap {
            putAll(suiteEnvironment)
            put("PROOT_TMP_DIR", prootTmp.absolutePath)
            putAll(guestBaseEnvironment())
            put("npm_config_update_notifier", "false")
        }

    private fun guestBaseEnvironment(): Map<String, String> =
        mapOf(
            "HOME" to GUEST_HOME,
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "PATH" to GUEST_PATH,
            "TMPDIR" to "/tmp",
            "LANG" to "C.UTF-8",
            "XDG_CONFIG_HOME" to "/root/.config",
            "XDG_CACHE_HOME" to "/root/.cache",
            "XDG_DATA_HOME" to "/root/.local/share",
            "XDG_STATE_HOME" to "/root/.local/state",
        )
}
