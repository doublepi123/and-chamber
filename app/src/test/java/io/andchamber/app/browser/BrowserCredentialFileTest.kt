package io.andchamber.app.browser

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class BrowserCredentialFileTest {
    @get:Rule val folder = TemporaryFolder()

    private val credential = BrowserCredential(
        endpoint = "http://127.0.0.1:9/v1/command",
        token = "ab".repeat(32),
        sessionId = "session",
        expiresAt = 1_700_000_000_000,
    )

    @Test
    fun `publish replaces atomically and keeps owner-only permissions`() {
        val target = folder.newFolder("state").resolve("browser-control.json")
        val store = BrowserCredentialFile(target)
        store.publish(credential)
        val parsed = Json.decodeFromString<BrowserCredential>(target.readText())
        assertEquals(1, parsed.version)
        assertEquals(credential.endpoint, parsed.endpoint)
        assertFalse(target.toPath().toString().contains(" "))
        val perms = Files.getPosixFilePermissions(target.toPath())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            perms,
        )
        val parentPerms = Files.getPosixFilePermissions(target.parentFile.toPath())
        assertTrue(PosixFilePermission.OTHERS_READ !in parentPerms)
        assertTrue(PosixFilePermission.GROUP_READ !in parentPerms)
    }

    @Test
    fun `a symlink parent is refused and not followed`() {
        val outside = folder.newFolder("outside")
        val linkParent = folder.root.toPath().resolve("linked")
        Files.createSymbolicLink(linkParent, outside.toPath())
        val target = linkParent.resolve("browser-control.json").toFile()
        assertThrows(IllegalStateException::class.java) {
            BrowserCredentialFile(target).publish(credential)
        }
        assertFalse(outside.resolve("browser-control.json").exists())
    }

    @Test
    fun `a symlink above the trusted root is ignored and one inside it is refused`() {
        val root = folder.newFolder("files")
        val parent = root.resolve(".local/state").also { it.mkdirs() }
        val link = parent.toPath().resolve("andchamber")
        val outside = folder.newFolder("outside")
        Files.createSymbolicLink(link, outside.toPath())
        val target = link.resolve("browser-control.json").toFile()
        assertThrows(IllegalStateException::class.java) {
            BrowserCredentialFile(target, trustedRoot = root).publish(credential)
        }
        assertFalse(outside.resolve("browser-control.json").exists())
    }

    @Test
    fun `directories created under the trusted root are owner only`() {
        val root = folder.newFolder("files")
        val target = root.resolve(".local/state/andchamber/browser-control.json")
        BrowserCredentialFile(target, trustedRoot = root).publish(credential)
        var current = target.parentFile
        while (current != null && current != root) {
            val perms = Files.getPosixFilePermissions(current.toPath())
            assertTrue(current.path, PosixFilePermission.GROUP_READ !in perms)
            assertTrue(current.path, PosixFilePermission.OTHERS_READ !in perms)
            current = current.parentFile
        }
    }
}
