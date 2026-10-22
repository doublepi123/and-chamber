package io.andchamber.app.browser.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions

class BrowserToolEndpointTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val token = "a1".repeat(32)

    @Test
    fun `content names both loopback paths and the token`() {
        val body = Json.parseToJsonElement(BrowserToolEndpoint.content(41234, token)).jsonObject
        assertEquals(1, body.getValue("version").jsonPrimitive.content.toInt())
        assertEquals("http://127.0.0.1:41234/v1/tool", body.getValue("url").jsonPrimitive.content)
        assertEquals("http://127.0.0.1:41234/v1/cancel", body.getValue("cancelUrl").jsonPrimitive.content)
        assertEquals(token, body.getValue("token").jsonPrimitive.content)
    }

    @Test
    fun `a token is 32 random bytes of lowercase hex`() {
        val first = BrowserToolEndpoint.newToken()
        val second = BrowserToolEndpoint.newToken()
        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("^[0-9a-f]{64}$")))
        assertFalse(first == second)
    }

    @Test
    fun `write is atomic owner-only and under owner-only parents`() {
        val home = folder.newFolder("home")
        val file = BrowserToolEndpoint.hostFile(home)

        BrowserToolEndpoint.write(file, home, folder.root, 41234, token)

        assertTrue(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS)))
        listOf(".local", ".local/state", ".local/state/andchamber").forEach { relative ->
            assertEquals(
                "rwx------",
                PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(File(home, relative).toPath(), LinkOption.NOFOLLOW_LINKS),
                ),
            )
        }
        val body = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(token, body.getValue("token").jsonPrimitive.content)

        // Replacing rotates the token without leaving a temp file behind.
        val rotated = "b2".repeat(32)
        BrowserToolEndpoint.write(file, home, folder.root, 41235, rotated)
        assertEquals(rotated, Json.parseToJsonElement(file.readText()).jsonObject.getValue("token").jsonPrimitive.content)
        assertEquals(0, file.parentFile.listFiles().orEmpty().count { it.name.startsWith(".browser-tool") })
    }

    @Test
    fun `write refuses a symlinked parent chain`() {
        val home = folder.newFolder("home-link")
        val outside = folder.newFolder("outside-state")
        File(home, ".local/state").mkdirs()
        Files.createSymbolicLink(File(home, ".local/state/andchamber").toPath(), outside.toPath())

        val error =
            runCatching {
                BrowserToolEndpoint.write(BrowserToolEndpoint.hostFile(home), home, folder.root, 41234, token)
            }.exceptionOrNull()

        assertTrue(error != null)
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `delete removes a file and refuses a symlinked parent`() {
        val home = folder.newFolder("home-delete")
        val file = BrowserToolEndpoint.hostFile(home)
        BrowserToolEndpoint.write(file, home, folder.root, 41234, token)
        assertTrue(BrowserToolEndpoint.delete(file, folder.root))
        assertFalse(file.exists())
        assertTrue("a missing file is success", BrowserToolEndpoint.delete(file, folder.root))

        val linked = folder.newFolder("home-delete-link")
        val outside = folder.newFolder("outside-delete")
        val victim = File(outside, "browser-tool.json").apply { writeText("keep") }
        File(linked, ".local/state").mkdirs()
        Files.createSymbolicLink(File(linked, ".local/state/andchamber").toPath(), outside.toPath())
        assertFalse(BrowserToolEndpoint.delete(BrowserToolEndpoint.hostFile(linked), folder.root))
        assertEquals("keep", victim.readText())
    }

    @Test
    fun `delete unlinks a symlink leaf without following it`() {
        val home = folder.newFolder("home-leaf")
        val file = BrowserToolEndpoint.hostFile(home)
        file.parentFile.mkdirs()
        val outside = folder.newFile("outside.json").apply { writeText("keep") }
        Files.createSymbolicLink(file.toPath(), outside.toPath())

        assertTrue(BrowserToolEndpoint.delete(file, folder.root))
        assertFalse(Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", outside.readText())
    }

    @Test
    fun `a token or port outside the contract is refused`() {
        assertTrue(runCatching { BrowserToolEndpoint.content(0, token) }.isFailure)
        assertTrue(runCatching { BrowserToolEndpoint.content(41234, "not-hex") }.isFailure)
        assertNull(runCatching { BrowserToolEndpoint.content(41234, token.uppercase()) }.getOrNull())
    }
}
