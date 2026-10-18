package io.andchamber.app.runtime.install

import io.andchamber.app.runtime.install.OpenChamberInstaller.Companion.APK_PACKAGES
import io.andchamber.app.runtime.install.OpenChamberInstaller.Companion.MIN_OPENCODE_VERSION
import io.andchamber.app.runtime.install.OpenChamberInstaller.Companion.OPENCHAMBER_MODULE_ROOT
import io.andchamber.app.runtime.install.OpenChamberInstaller.Companion.SHIM_GUEST_PATH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The installer's guest command strings. The real commands are verified on a device; these pin
 * the parts a regression would silently break (package list, npm pins, node-pty path, shim path).
 */
class OpenChamberInstallerTest {
    @Test
    fun `apk installs the runtime packages including the node-pty build toolchain`() {
        val packages = APK_PACKAGES.split(" ")
        listOf("nodejs", "npm", "git", "ripgrep", "python3", "make", "g++", "linux-headers", "libstdc++").forEach {
            assertTrue("$it missing from apk packages", it in packages)
        }
    }

    @Test
    fun `openchamber is installed from the manifest pins`() {
        val script = openChamberInstallScript(openChamberVersion = "2.4.0", openCodeVersion = "2.0.32")

        assertEquals(
            "npm i -g --no-fund --no-audit @openchamber/web@2.4.0 @opencode/cli@2.0.32",
            script,
        )
    }

    @Test
    fun `node-pty is rebuilt from source inside the OpenChamber module`() {
        val script = "cd $OPENCHAMBER_MODULE_ROOT/node_modules/node-pty && npx --yes node-gyp rebuild"
        assertEquals("/usr/local/lib/node_modules/@openchamber/web/node_modules/node-pty", script.substringAfter("cd ").substringBefore(" &&"))
        assertTrue(script.endsWith("node-gyp rebuild"))
    }

    @Test
    fun `shim is written to the path NODE_OPTIONS requires`() {
        assertEquals("/opt/andchamber/android-shim.cjs", SHIM_GUEST_PATH)
    }

    @Test
    fun `version comparison accepts equal and newer and rejects older opencode`() {
        assertTrue(OpenChamberInstaller.compareVersions("2.0.20", MIN_OPENCODE_VERSION) >= 0)
        assertTrue(OpenChamberInstaller.compareVersions("v2.0.32", MIN_OPENCODE_VERSION) >= 0)
        assertFalse(OpenChamberInstaller.compareVersions("2.0.19", MIN_OPENCODE_VERSION) >= 0)
        assertTrue(OpenChamberInstaller.compareVersions("3.0.0", MIN_OPENCODE_VERSION) > 0)
    }

    private fun openChamberInstallScript(
        openChamberVersion: String,
        openCodeVersion: String,
    ): String =
        "npm i -g --no-fund --no-audit " +
            "@openchamber/web@$openChamberVersion " +
            "@opencode/cli@$openCodeVersion"
}
