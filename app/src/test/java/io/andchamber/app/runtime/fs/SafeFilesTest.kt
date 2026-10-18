package io.andchamber.app.runtime.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

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
