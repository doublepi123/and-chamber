package io.andchamber.app.runtime.process

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

class BrowserToolAssetsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `endpoint host file is the private home path the UI writes`() {
        val home = File("/data/runtime/home")

        assertEquals(
            File(home, ".local/state/andchamber/browser-control.json"),
            BrowserToolAssets.endpointHostFile(home),
        )
        assertEquals("/root/.local/state/andchamber/browser-control.json", BrowserToolAssets.GUEST_ENDPOINT_FILE)
        assertEquals(
            "/root/.local/share/andchamber/browser-control/browser-mcp.cjs",
            BrowserToolAssets.GUEST_SCRIPT,
        )
    }

    @Test
    fun `sync writes the latest asset every time and makes parents owner-only`() {
        val home = folder.newFolder("home")
        val script = File(home, BrowserToolAssets.SCRIPT_RELATIVE)

        BrowserToolAssets.sync(home, "old-adapter", folder.root)
        BrowserToolAssets.sync(home, "new-adapter", folder.root)

        assertEquals("new-adapter", script.readText())
        assertTrue(Files.isRegularFile(script.toPath(), LinkOption.NOFOLLOW_LINKS))
        listOf(
            ".local",
            ".local/share",
            ".local/share/andchamber",
            ".local/share/andchamber/browser-control",
            ".local/state",
            ".local/state/andchamber",
        ).forEach { relative ->
            val permissions = Files.getPosixFilePermissions(File(home, relative).toPath(), LinkOption.NOFOLLOW_LINKS)
            assertEquals(PosixFilePermissions.fromString("rwx------"), permissions)
        }
    }

    @Test
    fun `sync replaces a symlinked script and leaves the link target alone`() {
        val home = folder.newFolder("home-link")
        BrowserToolAssets.sync(home, "first", folder.root)
        val outside = folder.newFile("outside.cjs").apply { writeText("do-not-touch") }
        val script = File(home, BrowserToolAssets.SCRIPT_RELATIVE)
        script.delete()
        Files.createSymbolicLink(script.toPath(), outside.toPath())

        BrowserToolAssets.sync(home, "replaced", folder.root)

        assertTrue(Files.isRegularFile(script.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("replaced", script.readText())
        assertEquals("do-not-touch", outside.readText())
    }

    @Test
    fun `sync refuses a symlink anywhere in the home ancestor chain`() {
        val outside = folder.newFolder("outside-home")
        val linkParent = folder.newFolder("link-parent")
        val home = File(linkParent, "home")
        Files.createSymbolicLink(home.toPath(), outside.toPath())
        val before = Files.getPosixFilePermissions(outside.toPath())

        val error = runCatching { BrowserToolAssets.sync(home, "asset", linkParent) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(File(outside, BrowserToolAssets.SCRIPT_RELATIVE).exists())
        assertEquals(before, Files.getPosixFilePermissions(outside.toPath()))
    }

    @Test
    fun `sync refuses an empty asset and does not chmod the home`() {
        val home = folder.newFolder("home-empty")
        val before = Files.getPosixFilePermissions(home.toPath())

        val error = runCatching { BrowserToolAssets.sync(home, "", folder.root) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(before, Files.getPosixFilePermissions(home.toPath()))
        assertFalse(File(home, BrowserToolAssets.SCRIPT_RELATIVE).exists())
    }

    @Test
    fun `invalidate refuses a symlinked parent and leaves the outside credential`() {
        val outside = folder.newFolder("outside-state")
        val home = folder.newFolder("home-state-link")
        File(home, ".local/state").mkdirs()
        Files.createSymbolicLink(File(home, ".local/state/andchamber").toPath(), outside.toPath())
        val leaked = File(outside, "browser-control.json").apply { writeText("""{"token":"outside"}""") }

        assertFalse(BrowserToolAssets.invalidateEndpoint(home, folder.root))
        assertEquals("""{"token":"outside"}""", leaked.readText())
    }

    @Test
    fun `invalidate refuses a symlinked home ancestor and leaves the outside credential`() {
        val realHome = folder.newFolder("real-home")
        val credential = BrowserToolAssets.endpointHostFile(realHome)
        credential.parentFile.mkdirs()
        credential.writeText("""{"token":"outside-home"}""")
        val linkParent = folder.newFolder("ancestor-links")
        val home = File(linkParent, "home")
        Files.createSymbolicLink(home.toPath(), realHome.toPath())

        assertFalse(BrowserToolAssets.invalidateEndpoint(home, linkParent))
        assertEquals("""{"token":"outside-home"}""", credential.readText())
    }

    @Test
    fun `a symlinked trusted root is allowed and only the suffix below it is checked`() {
        val realRoot = folder.newFolder("files")
        val linkParent = folder.newFolder("data-user")
        val trustedRoot = File(linkParent, "0")
        Files.createSymbolicLink(trustedRoot.toPath(), realRoot.toPath())
        val home = File(trustedRoot, "runtime/home")

        BrowserToolAssets.sync(home, "adapter", trustedRoot)

        assertEquals("adapter", File(realRoot, "runtime/home/${BrowserToolAssets.SCRIPT_RELATIVE}").readText())
        val credential = BrowserToolAssets.endpointHostFile(File(realRoot, "runtime/home"))
        credential.parentFile.mkdirs()
        credential.writeText("old")
        assertTrue(BrowserToolAssets.invalidateEndpoint(home, trustedRoot))
        assertFalse(credential.exists())
    }

    @Test
    fun `sync refuses a symlinked private parent`() {
        val home = folder.newFolder("home-parent-link")
        val outside = folder.newFolder("outside-share")
        File(home, ".local").mkdirs()
        Files.createSymbolicLink(File(home, ".local/share").toPath(), outside.toPath())

        val error = runCatching { BrowserToolAssets.sync(home, "asset", folder.root) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(File(outside, "andchamber/browser-control/browser-mcp.cjs").exists())
    }

    @Test
    fun `sync tightens a group-readable parent without dropping owner bits`() {
        val home = folder.newFolder("home-mode")
        val parent = File(home, ".local/share/andchamber/browser-control")
        parent.mkdirs()
        Files.setPosixFilePermissions(
            parent.toPath(),
            PosixFilePermissions.fromString("rwxr-x---") + PosixFilePermission.OWNER_READ,
        )

        BrowserToolAssets.sync(home, "asset", folder.root)

        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(parent.toPath(), LinkOption.NOFOLLOW_LINKS),
        )
    }

    @Test
    fun `invalidate removes a regular credential and a symlink without touching its target`() {
        val home = folder.newFolder("home-cred")
        val credential = BrowserToolAssets.endpointHostFile(home)
        credential.parentFile.mkdirs()
        credential.writeText("""{"token":"lease"}""")

        assertTrue(BrowserToolAssets.invalidateEndpoint(home, folder.root))
        assertFalse(credential.exists())

        val outside = folder.newFile("lease.json").apply { writeText("still-here") }
        Files.createSymbolicLink(credential.toPath(), outside.toPath())
        assertTrue(BrowserToolAssets.invalidateEndpoint(home, folder.root))
        assertFalse(Files.exists(credential.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("still-here", outside.readText())
        assertEquals("still-here", outside.readText())
        assertTrue(BrowserToolAssets.invalidateEndpoint(home, folder.root))
    }

    @Test
    fun `launch environment names the endpoint file and the local mcp server without a token`() {
        val env = BrowserToolAssets.launchEnvironment()
        val config = Json.parseToJsonElement(env.getValue(BrowserToolAssets.CONFIG_ENV)).jsonObject
        val server = config.getValue("mcp").jsonObject.getValue("servers").jsonObject
            .getValue(BrowserToolAssets.MCP_SERVER_NAME).jsonObject

        assertEquals(BrowserToolAssets.GUEST_ENDPOINT_FILE, env[BrowserToolAssets.ENDPOINT_ENV])
        assertEquals("local", server.getValue("type").jsonPrimitive.content)
        assertEquals(
            listOf("node", BrowserToolAssets.GUEST_SCRIPT),
            server.getValue("command").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(server.containsKey("environment"))
        assertFalse(env.values.any { it.contains("secret-lease") })
    }

    @Test
    fun `merge keeps user fields and other mcp servers and preserves a strict server environment`() {
        val existing =
            """
            {
              "${"\$"}schema": "https://opencode.ai/config.json",
              "model": "provider/model",
              "mcp": {
                "servers": {
                  "user_tool": {"type": "local", "command": ["echo", "keep"]},
                  "andchamber_browser": {
                    "type": "remote",
                    "command": ["old"],
                    "environment": {"KEEP": "yes", "ANDCHAMBER_BROWSER_ENDPOINT_FILE": "must-not-be-added-by-us"}
                  }
                },
                "timeout": 5
              }
            }
            """.trimIndent()

        val merged = Json.parseToJsonElement(BrowserToolAssets.mergeConfig(existing)).jsonObject
        val mcp = merged.getValue("mcp").jsonObject
        val servers = mcp.getValue("servers").jsonObject
        val browser = servers.getValue("andchamber_browser").jsonObject

        assertEquals("https://opencode.ai/config.json", merged.getValue("\$schema").jsonPrimitive.content)
        assertEquals("provider/model", merged.getValue("model").jsonPrimitive.content)
        assertEquals("5", mcp.getValue("timeout").jsonPrimitive.content)
        assertEquals(
            listOf("echo", "keep"),
            servers.getValue("user_tool").jsonObject.getValue("command").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("local", browser.getValue("type").jsonPrimitive.content)
        assertEquals(
            listOf("node", BrowserToolAssets.GUEST_SCRIPT),
            browser.getValue("command").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("yes", browser.getValue("environment").jsonObject.getValue("KEEP").jsonPrimitive.content)
        assertFalse(browser.getValue("environment").jsonObject.containsKey(BrowserToolAssets.ENDPOINT_ENV))
        assertFalse(merged.toString().contains("secret-lease"))
    }

    @Test
    fun `merge uses the overlay for a blank config and fails closed on non-empty invalid json`() {
        val blank = Json.parseToJsonElement(BrowserToolAssets.mergeConfig("  ")).jsonObject
        assertEquals(setOf("mcp"), blank.keys)

        listOf("{not json", "[1]", "\"text\"").forEach { existing ->
            val error = runCatching { BrowserToolAssets.mergeConfig(existing) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }

    @Test
    fun `merge does not treat a bare mcp name as the v2 servers map`() {
        val existing = """{"mcp":{"andchamber_browser":{"type":"remote","command":["old"]}}}"""

        val servers = Json.parseToJsonElement(BrowserToolAssets.mergeConfig(existing))
            .jsonObject.getValue("mcp").jsonObject.getValue("servers").jsonObject

        assertEquals(setOf("andchamber_browser"), servers.keys)
        assertEquals("local", servers.getValue("andchamber_browser").jsonObject.getValue("type").jsonPrimitive.content)
    }
}
