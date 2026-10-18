package io.andchamber.app.runtime.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions


class SafeFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `deleteTree unlinks a directory symlink without deleting the outside directory`() {
        val root = temporaryFolder.newFolder("runtime")
        val sentinel = temporaryFolder.newFolder("outside").apply {
            resolve("keep.txt").writeText("precious")
            resolve("nested").mkdirs()
            resolve("nested/deep.txt").writeText("deeper")
        }
        val tree = root.resolve("environment").apply { mkdirs() }
        tree.resolve("rootfs/usr").mkdirs()
        tree.resolve("rootfs/usr/data.txt").writeText("inside")
        Files.createSymbolicLink(tree.resolve("rootfs/escape").toPath(), sentinel.toPath().toAbsolutePath())
        Files.createSymbolicLink(tree.resolve("rootfs/escape-file").toPath(), sentinel.resolve("keep.txt").toPath().toAbsolutePath())

        assertTrue(deleteTree(tree))

        assertFalse(tree.exists())
        assertEquals("precious", sentinel.resolve("keep.txt").readText())
        assertEquals("deeper", sentinel.resolve("nested/deep.txt").readText())
    }

    @Test
    fun `deleteTree unlinks a symlink that is the root argument and leaves its target`() {
        val outside = temporaryFolder.newFolder("target").apply { resolve("keep.txt").writeText("x") }
        val link = temporaryFolder.root.resolve("link").toPath()
        Files.createSymbolicLink(link, outside.toPath().toAbsolutePath())

        assertTrue(deleteTree(link.toFile()))

        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("x", outside.resolve("keep.txt").readText())
    }

    @Test
    fun `deleteTree terminates on a symlink loop and removes the loop`() {
        val tree = temporaryFolder.newFolder("loop").apply { resolve("a").mkdirs() }
        Files.createSymbolicLink(tree.resolve("a/back").toPath(), tree.toPath().toAbsolutePath())

        assertTrue(deleteTree(tree))

        assertFalse(tree.exists())
    }

    /** Sets a POSIX mode string like "r-x------". Tests that need it are skipped on non-POSIX hosts. */
    private fun setPosix(
        file: java.io.File,
        mode: String,
    ) {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(mode))
    }

    @Test
    fun `deleteTree returns true for a missing path`() {
        assertTrue(deleteTree(temporaryFolder.root.resolve("never-existed")))
    }

    @Test
    fun `treeSize counts regular files and ignores symlinks, without following them`() {
        val outside = temporaryFolder.newFolder("big").apply { resolve("big.bin").writeBytes(ByteArray(10_000)) }
        val tree = temporaryFolder.newFolder("sized").apply {
            resolve("a.bin").writeBytes(ByteArray(100))
            resolve("dir").mkdirs()
            resolve("dir/b.bin").writeBytes(ByteArray(23))
            Files.createSymbolicLink(resolve("link").toPath(), outside.toPath().toAbsolutePath())
            Files.createSymbolicLink(resolve("dir/loop").toPath(), toPath().toAbsolutePath())
        }

        assertEquals(123L, treeSize(tree))
    }

    @Test
    fun `deleteTree removes a nested mode 000 directory tree`() {
        val tree = temporaryFolder.newFolder("mode000-nested")
        val locked = tree.resolve("a/b").apply { mkdirs(); resolve("f.txt").writeText("x") }
        setPosix(tree.resolve("a/b"), "---------")
        setPosix(tree.resolve("a"), "---------")
        assumeTrue(!locked.canRead() && !locked.canExecute())

        assertTrue(deleteTree(tree))
        assertFalse(tree.exists())
    }

    @Test
    fun `deleteTree removes a mode 000 directory and leaves an outside symlink target intact`() {
        val sentinel = temporaryFolder.newFolder("sentinel-mode000").apply {
            resolve("keep.txt").writeText("precious")
        }
        val tree = temporaryFolder.newFolder("mode000-link")
        val locked = tree.resolve("locked").apply { mkdirs() }
        Files.createSymbolicLink(locked.resolve("escape").toPath(), sentinel.toPath().toAbsolutePath())
        setPosix(locked, "---------")
        assumeTrue(!locked.canRead() && !locked.canExecute())

        assertTrue(deleteTree(tree))
        assertFalse(tree.exists())
        assertEquals("precious", sentinel.resolve("keep.txt").readText())
    }

    @Test
    fun `deleteTree removes a read-only 0500 directory full of files`() {
        val tree = temporaryFolder.newFolder("readonly")
        val dir = tree.resolve("ro").apply { mkdirs() }
        dir.resolve("one.txt").writeText("1")
        dir.resolve("two.txt").writeText("2")
        dir.resolve("sub").mkdirs()
        dir.resolve("sub/three.txt").writeText("3")
        setPosix(dir.resolve("sub"), "r-x------")
        setPosix(dir, "r-x------")
        assumeTrue(!dir.canWrite())

        assertTrue(deleteTree(tree))
        assertFalse(tree.exists())
    }

    @Test
    fun `deleteTree adds only owner bits to a parent and never adds group or other bits`() {
        val tree = temporaryFolder.newFolder("parent-mode")
        val dir = tree.resolve("victim").apply { mkdirs(); resolve("f").writeText("x") }
        setPosix(tree, "r-xr-x---")
        setPosix(dir, "r-x------")
        assumeTrue(!dir.canWrite())
        val parentBefore = Files.getPosixFilePermissions(tree.toPath())

        assertTrue(deleteTree(dir))

        val parentAfter = Files.getPosixFilePermissions(tree.toPath())
        assertTrue(parentAfter.containsAll(parentBefore))
        assertFalse(parentAfter.any { it.name.startsWith("GROUP") && it !in parentBefore })
        assertFalse(parentAfter.any { it.name.startsWith("OTHERS") && it !in parentBefore })
    }

    @Test
    fun `treeSize of a missing path is zero`() {
        assertEquals(0L, treeSize(temporaryFolder.root.resolve("missing")))
    }

    @Test
    fun `copyTreeNoFollow copies files and recreates links as links`() {
        val source = temporaryFolder.newFolder("skeleton").apply {
            resolve(".bashrc").writeText("export A=1")
            resolve("sub").mkdirs()
            resolve("sub/file").writeText("f")
            Files.createSymbolicLink(resolve("sub/link").toPath(), resolve(".bashrc").toPath().toAbsolutePath())
        }
        val destination = temporaryFolder.root.resolve("home")

        copyTreeNoFollow(source, destination)

        assertEquals("export A=1", destination.resolve(".bashrc").readText())
        assertEquals("f", destination.resolve("sub/file").readText())
        assertTrue(Files.isSymbolicLink(destination.resolve("sub/link").toPath()))
    }
}
