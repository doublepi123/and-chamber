package io.andchamber.app.runtime.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class GitConfigBlockTest {
    @get:Rule val folder = TemporaryFolder()

    private val block = GitConfigBlock.MANAGED_BLOCK + "\n"

    @Test
    fun `absent file yields just the managed block`() {
        assertEquals(block, GitConfigBlock.apply(null))
        assertEquals(block, GitConfigBlock.apply(""))
    }

    @Test
    fun `an existing user config without the block keeps its content and gets the block appended`() {
        val user = "[user]\n\tname = Ann\n\temail = ann@example.com\n"

        val result = GitConfigBlock.apply(user)

        assertEquals(user + block, result)
        assertTrue(result.startsWith(user))
    }

    @Test
    fun `a user file without a trailing newline gets a line break before the block`() {
        assertEquals("[user]\n\tname = Ann\n" + block, GitConfigBlock.apply("[user]\n\tname = Ann"))
    }

    @Test
    fun `an old managed block is replaced in place, not duplicated, and surrounding lines survive`() {
        val old =
            "[user]\n\tname = Ann\n" +
                "# >>> AndChamber (managed, do not edit) >>>\n" +
                "[safe]\n\tdirectory = /old/path\n" +
                "# <<< AndChamber <<<\n" +
                "[alias]\n\tst = status\n"

        val result = GitConfigBlock.apply(old)

        assertEquals("[user]\n\tname = Ann\n" + block + "[alias]\n\tst = status\n", result)
        assertEquals(1, Regex(Regex.escape(GitConfigBlock.BEGIN)).findAll(result).count())
        assertFalse(result.contains("/old/path"))
    }

    @Test
    fun `CRLF files keep CRLF line endings for the block and for the kept content`() {
        val user = "[user]\r\n\tname = Ann\r\n"

        val result = GitConfigBlock.apply(user)

        assertEquals(user + block.replace("\n", "\r\n"), result)
        // Every line break in the output is CRLF: no bare LF anywhere.
        assertEquals(0, Regex("(?<!\r)\n").findAll(result).count())
    }

    @Test
    fun `applying twice equals applying once`() {
        val user = "[user]\n\tname = Ann\n"
        val once = GitConfigBlock.apply(user)

        assertEquals(once, GitConfigBlock.apply(once))
    }

    @Test
    fun `replacing an old block with a CRLF file is idempotent too`() {
        val crlf = "[user]\r\n\tname = Ann\r\n" + block.replace("\n", "\r\n")

        assertEquals(crlf, GitConfigBlock.apply(crlf))
    }

    @Test
    fun `the managed block carries the gitdir include and the safe directory`() {
        assertTrue(block.contains("[includeIf \"gitdir:/root/projects/\"]"))
        assertTrue(block.contains("path = /root/.config/andchamber/git/shared-storage.gitconfig"))
        assertTrue(block.contains("[safe]\n    directory = /root/projects/*"))
    }

    // --- shared-storage file contents ---

    @Test
    fun `the shared storage gitconfig holds exactly the three core keys`() {
        assertEquals(
            "[core]\n    createObject = rename\n    fileMode = false\n    symlinks = false\n",
            GitConfigBlock.SHARED_STORAGE_GITCONFIG,
        )
    }

    // --- writer, against a temporary private home ---

    @Test
    fun `the writer creates both files in a fresh home`() {
        val home = folder.newFolder("home")

        GitConfigWriter.apply(home)

        assertEquals(
            GitConfigBlock.SHARED_STORAGE_GITCONFIG,
            File(home, ".config/andchamber/git/shared-storage.gitconfig").readText(),
        )
        assertEquals(block, File(home, ".gitconfig").readText())
    }

    @Test
    fun `the writer keeps the user's global config and is idempotent across starts`() {
        val home = folder.newFolder("home2")
        File(home, ".gitconfig").writeText("[user]\n\tname = Ann\n")

        GitConfigWriter.apply(home)
        val first = File(home, ".gitconfig").readText()
        GitConfigWriter.apply(home)

        assertEquals(first, File(home, ".gitconfig").readText())
        assertTrue(first.startsWith("[user]\n\tname = Ann\n"))
    }

    @Test
    fun `a symlinked gitconfig is replaced by a regular file and its target is left alone`() {
        val home = folder.newFolder("home3")
        val target = folder.newFile("outside").apply { writeText("not ours") }
        Files.createSymbolicLink(File(home, ".gitconfig").toPath(), target.toPath())

        GitConfigWriter.apply(home)

        assertTrue(Files.isRegularFile(File(home, ".gitconfig").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("not ours", target.readText())
    }
}
