package io.andchamber.app.runtime.process

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions

class BrowserPluginAssetsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val json = Json

    @Test
    fun `empty config becomes only the plugin list`() {
        val merged = BrowserPluginAssets.mergeConfig(null)
        val root = json.parseToJsonElement(merged).jsonObject

        assertEquals(
            listOf(BrowserPluginAssets.GUEST_DIR),
            root.getValue("plugins").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(setOf("plugins"), root.keys)
    }

    @Test
    fun `existing keys and other plugins are preserved and the tool path is appended once`() {
        val existing =
            """{"model":"keep-me","plugins":["/one.js"],"permissions":{"bash":"ask"},"mcp":{"servers":{"x":{}}}}"""
        val root = json.parseToJsonElement(BrowserPluginAssets.mergeConfig(existing)).jsonObject

        assertEquals("keep-me", root.getValue("model").jsonPrimitive.content)
        assertTrue(root.containsKey("permissions"))
        assertTrue(root.containsKey("mcp"))
        assertEquals(
            listOf("/one.js", BrowserPluginAssets.GUEST_DIR),
            root.getValue("plugins").jsonArray.map { it.jsonPrimitive.content },
        )

        val again = json.parseToJsonElement(BrowserPluginAssets.mergeConfig(BrowserPluginAssets.mergeConfig(existing))).jsonObject
        assertEquals(
            listOf("/one.js", BrowserPluginAssets.GUEST_DIR),
            again.getValue("plugins").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `legacy singular plugin array is folded into plugins and removed`() {
        val existing = """{"plugin":["/legacy.js","/one.js"],"plugins":["/one.js"]}"""
        val root = json.parseToJsonElement(BrowserPluginAssets.mergeConfig(existing)).jsonObject

        assertFalse(root.containsKey("plugin"))
        assertEquals(
            listOf("/one.js", "/legacy.js", BrowserPluginAssets.GUEST_DIR),
            root.getValue("plugins").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `invalid json fails closed and launch environment is skipped`() {
        assertTrue(runCatching { BrowserPluginAssets.mergeConfig("{not json") }.isFailure)
        assertTrue(runCatching { BrowserPluginAssets.mergeConfig("[]") }.isFailure)
        assertNull(BrowserPluginAssets.launchEnvironment("{not json"))
    }

    @Test
    fun `launch environment names the endpoint file and never a token`() {
        val env = BrowserPluginAssets.launchEnvironment("""{"model":"m"}""")!!
        val root = json.parseToJsonElement(env.getValue(BrowserPluginAssets.CONFIG_ENV)).jsonObject

        assertEquals(BrowserPluginAssets.GUEST_ENDPOINT_FILE, env[BrowserPluginAssets.TOOL_ENV])
        assertEquals("m", root.getValue("model").jsonPrimitive.content)
        assertFalse(env.containsKey("OPENCODE_CONFIG"))
        assertFalse(env.containsKey("OPENCHAMBER_BROWSER_PROVIDER"))
        assertFalse(env.containsKey("ANDCHAMBER_BROWSER_PROVIDER_TOKEN"))
        assertFalse(env.values.any { it.contains("token", ignoreCase = true) })
        assertFalse(root.containsKey("mcp"))
        assertFalse(root.containsKey("permissions"))
        assertFalse(root.containsKey("tools"))
    }

    @Test
    fun `settings default is false only when the key is absent`() {
        val home = folder.newFolder("home")
        assertNull(BrowserPluginAssets.ensureDefaultAgentWebTool(home, folder.root))
        val first = BrowserPluginAssets.settingsFile(home)
        assertEquals(
            false,
            json.parseToJsonElement(first.readText()).jsonObject.getValue("agentWebToolEnabled").jsonPrimitive.content.toBoolean(),
        )
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(first.toPath(), LinkOption.NOFOLLOW_LINKS)),
        )

        // A present value is respected, and other keys survive an insertion.
        first.writeText("""{"agentWebToolEnabled":true,"theme":"dark"}""")
        assertNull(BrowserPluginAssets.ensureDefaultAgentWebTool(home, folder.root))
        assertEquals("""{"agentWebToolEnabled":true,"theme":"dark"}""", first.readText())

        first.writeText("""{"theme":"dark"}""")
        assertNull(BrowserPluginAssets.ensureDefaultAgentWebTool(home, folder.root))
        val merged = json.parseToJsonElement(first.readText()).jsonObject
        assertEquals("dark", merged.getValue("theme").jsonPrimitive.content)
        assertEquals(false, merged.getValue("agentWebToolEnabled").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `invalid settings are left untouched`() {
        val home = folder.newFolder("home-invalid")
        val settings = BrowserPluginAssets.settingsFile(home)
        settings.parentFile.mkdirs()
        settings.writeText("{not json")

        assertEquals("settings-invalid-json", BrowserPluginAssets.ensureDefaultAgentWebTool(home, folder.root))
        assertEquals("{not json", settings.readText())
    }

    @Test
    fun `a symlinked settings file is refused and its target untouched`() {
        val home = folder.newFolder("home-link")
        val outside = folder.newFile("outside-settings.json").apply { writeText("""{"keep":1}""") }
        val settings = BrowserPluginAssets.settingsFile(home)
        settings.parentFile.mkdirs()
        Files.createSymbolicLink(settings.toPath(), outside.toPath())

        assertEquals("settings-symlink", BrowserPluginAssets.ensureDefaultAgentWebTool(home, folder.root))
        assertEquals("""{"keep":1}""", outside.readText())
        assertTrue(Files.isSymbolicLink(settings.toPath()))
    }

    @Test
    fun `cleanup removes only the legacy browser-control artefacts`() {
        val home = folder.newFolder("home-cleanup")
        val legacyScript = File(home, ".local/share/andchamber/browser-control/browser-mcp.cjs")
        legacyScript.parentFile.mkdirs()
        legacyScript.writeText("legacy")
        val legacyState = File(home, ".local/state/andchamber/browser-control.json")
        legacyState.parentFile.mkdirs()
        legacyState.writeText("legacy")
        val userConfig = File(home, ".config/opencode/opencode.json").apply {
            parentFile.mkdirs()
            writeText("user")
        }
        val pluginDir = BrowserPluginAssets.pluginDir(home).apply { mkdirs() }
        val pluginFile = File(pluginDir, "index.js").apply { writeText("plugin") }

        BrowserPluginAssets.cleanupLegacy(home, folder.root)

        assertFalse(legacyScript.exists())
        assertFalse(legacyScript.parentFile.exists())
        assertFalse(legacyState.exists())
        assertEquals("user", userConfig.readText())
        assertEquals("plugin", pluginFile.readText())
    }

    @Test
    fun `cleanup does not follow a symlinked legacy parent`() {
        val home = folder.newFolder("home-cleanup-link")
        val outside = folder.newFolder("outside-control")
        val victim = File(outside, "browser-mcp.cjs").apply { writeText("keep") }
        val parent = File(home, ".local/share/andchamber")
        parent.mkdirs()
        Files.createSymbolicLink(File(parent, "browser-control").toPath(), outside.toPath())

        BrowserPluginAssets.cleanupLegacy(home, folder.root)

        assertEquals("keep", victim.readText())
        assertTrue(Files.isSymbolicLink(File(parent, "browser-control").toPath()))
    }

    @Test
    fun `sync writes both plugin files owner-only`() {
        val home = folder.newFolder("home-sync")
        val assets = BrowserPluginAssets.Assets("package-json", "index-js")

        BrowserPluginAssets.sync(home, assets, folder.root)

        assertEquals("package-json", File(home, BrowserPluginAssets.PACKAGE_RELATIVE).readText())
        assertEquals("index-js", File(home, BrowserPluginAssets.INDEX_RELATIVE).readText())
        assertEquals(
            "rwx------",
            PosixFilePermissions.toString(
                Files.getPosixFilePermissions(BrowserPluginAssets.pluginDir(home).toPath(), LinkOption.NOFOLLOW_LINKS),
            ),
        )
        assertEquals(BrowserPluginAssets.PACKAGE_ASSET, "browser-tool/package.json")
        assertEquals(BrowserPluginAssets.INDEX_ASSET, "browser-tool/index.js")
        assertNotEquals(BrowserPluginAssets.GUEST_DIR, BrowserPluginAssets.DIR_RELATIVE)
    }
}
