package io.andchamber.app.runtime.process

import io.andchamber.app.phone.tool.PhoneToolEndpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

class PhonePluginAssetsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = false }

    private fun plugins(config: String): List<String> =
        (json.parseToJsonElement(config).jsonObject["plugins"] as JsonArray)
            .map { it.jsonPrimitive.content }

    @Test
    fun `sync writes the plugin files into a private directory on every launch`() {
        val home = folder.newFolder("home-sync")
        PhonePluginAssets.sync(home, PhonePluginAssets.Assets("package-json", "index-js"), folder.root)

        assertEquals("package-json", File(home, PhonePluginAssets.PACKAGE_RELATIVE).readText())
        assertEquals("index-js", File(home, PhonePluginAssets.INDEX_RELATIVE).readText())
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(PhonePluginAssets.pluginDir(home).toPath(), LinkOption.NOFOLLOW_LINKS),
        )

        // An upgrade replaces the files, not only the directory.
        PhonePluginAssets.sync(home, PhonePluginAssets.Assets("package-json-2", "index-js-2"), folder.root)
        assertEquals("package-json-2", File(home, PhonePluginAssets.PACKAGE_RELATIVE).readText())
        assertEquals("index-js-2", File(home, PhonePluginAssets.INDEX_RELATIVE).readText())
    }

    @Test
    fun `empty assets are refused and a symlinked parent fails closed`() {
        val home = folder.newFolder("home-empty")
        assertTrue(
            runCatching {
                PhonePluginAssets.sync(home, PhonePluginAssets.Assets("", "index"), folder.root)
            }.isFailure,
        )
        assertFalse(Files.exists(File(home, PhonePluginAssets.INDEX_RELATIVE).toPath(), LinkOption.NOFOLLOW_LINKS))

        val linkedHome = folder.newFolder("home-linked")
        val outside = folder.newFolder("outside")
        Files.createSymbolicLink(File(linkedHome, ".local").toPath(), outside.toPath())
        assertTrue(
            runCatching {
                PhonePluginAssets.sync(linkedHome, PhonePluginAssets.Assets("package", "index"), folder.root)
            }.isFailure,
        )
        assertFalse(Files.exists(File(outside, "share").toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `merge keeps user keys and adds the phone directory exactly once`() {
        val empty = PhonePluginAssets.mergeConfig(null)
        assertEquals(listOf(PhonePluginAssets.GUEST_DIR), plugins(empty))

        val existing = """{"model":"m","plugins":["/one.js"],"plugin":["/legacy.js"],"permission":{"x":"y"}}"""
        val merged = PhonePluginAssets.mergeConfig(existing)
        assertEquals(
            listOf("/one.js", "/legacy.js", PhonePluginAssets.GUEST_DIR),
            plugins(merged),
        )
        val root = json.parseToJsonElement(merged).jsonObject
        assertEquals("m", root["model"]!!.jsonPrimitive.content)
        assertFalse(root.containsKey("plugin"))
        assertTrue(root.containsKey("permission"))

        // Merging the already merged config is stable.
        assertEquals(merged, PhonePluginAssets.mergeConfig(merged))
    }

    @Test
    fun `browser and phone overlays merge serially and independently`() {
        val existing = """{"model":"m","plugins":["/one.js"]}"""
        val browser = BrowserPluginAssets.launchEnvironment(existing)!!
        val phone = PhonePluginAssets.launchEnvironment(browser[BrowserPluginAssets.CONFIG_ENV])!!
        val serial = json.parseToJsonElement(phone[PhonePluginAssets.CONFIG_ENV]!!).jsonObject
        assertEquals("m", serial["model"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("/one.js", BrowserPluginAssets.GUEST_DIR, PhonePluginAssets.GUEST_DIR),
            plugins(phone[PhonePluginAssets.CONFIG_ENV]!!),
        )

        // Either overlay alone exposes only its own plugin directory.
        assertEquals(
            listOf("/one.js", BrowserPluginAssets.GUEST_DIR),
            plugins(browser[BrowserPluginAssets.CONFIG_ENV]!!),
        )
        val phoneOnly = PhonePluginAssets.launchEnvironment(existing)!!
        assertEquals(
            listOf("/one.js", PhonePluginAssets.GUEST_DIR),
            plugins(phoneOnly[PhonePluginAssets.CONFIG_ENV]!!),
        )

        // The endpoint environment variables are separate.
        assertNotEquals(
            browser[BrowserPluginAssets.TOOL_ENV],
            phone[PhonePluginAssets.TOOL_ENV],
        )
        assertEquals(PhoneToolEndpoint.GUEST_FILE, phone[PhonePluginAssets.TOOL_ENV])
    }

    @Test
    fun `invalid existing config skips only the phone overlay`() {
        assertNull(PhonePluginAssets.launchEnvironment("{not json"))
        assertNull(PhonePluginAssets.launchEnvironment("[]"))
        assertTrue(runCatching { PhonePluginAssets.mergeConfig("""{"plugins":"nope"}""") }.isFailure)
        assertTrue(runCatching { PhonePluginAssets.mergeConfig("""{"plugins":[1]}""") }.isFailure)
    }

    @Test
    fun `asset names, guest paths and endpoint stay distinct from the browser plugin`() {
        assertEquals(
            setOf("phone-tool/package.json", "phone-tool/index.js"),
            setOf(PhonePluginAssets.PACKAGE_ASSET, PhonePluginAssets.INDEX_ASSET),
        )
        assertNotEquals(BrowserPluginAssets.GUEST_DIR, PhonePluginAssets.GUEST_DIR)
        assertNotEquals(BrowserPluginAssets.DIR_RELATIVE, PhonePluginAssets.DIR_RELATIVE)
        assertNotEquals(BrowserPluginAssets.TOOL_ENV, PhonePluginAssets.TOOL_ENV)
        assertNotEquals(
            BrowserPluginAssets.GUEST_ENDPOINT_FILE,
            PhonePluginAssets.GUEST_ENDPOINT_FILE,
        )
        assertEquals(".local/state/andchamber/phone-tool.json", PhonePluginAssets.endpointHostFile(folder.root).relativeTo(folder.root).path)
        assertEquals(PhoneToolEndpoint.GUEST_FILE, PhonePluginAssets.GUEST_ENDPOINT_FILE)
    }
}
