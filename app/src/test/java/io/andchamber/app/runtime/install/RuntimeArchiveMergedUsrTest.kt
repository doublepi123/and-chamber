package io.andchamber.app.runtime.install

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Debian 12's merged-/usr layout, which the Antigravity rootfs is built on.
 *
 * The archive lists `/bin -> usr/bin` before the `usr/bin/sh -> dash` link it contains. Resolving
 * the links in that order copied `/bin` while `sh` did not exist yet, so the guest had no `/bin/sh`
 * and `proot ... /bin/sh -c` failed with "'/bin/sh' not found" - the error the Antigravity rootfs
 * install showed on device. The directory copy also dropped every executable bit.
 */
class RuntimeArchiveMergedUsrTest {
    @get:Rule val folder = TemporaryFolder()

    private fun mergedUsrTarGz(): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(bytes)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

            fun dir(name: String) = tar.putArchiveEntry(TarArchiveEntry("$name/")).also { tar.closeArchiveEntry() }

            fun link(
                name: String,
                target: String,
            ) {
                tar.putArchiveEntry(
                    TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK).apply { linkName = target },
                )
                tar.closeArchiveEntry()
            }

            fun exe(
                name: String,
                body: String,
            ) {
                val payload = body.toByteArray()
                tar.putArchiveEntry(
                    TarArchiveEntry(name).apply {
                        size = payload.size.toLong()
                        mode = 0b111_101_101
                    },
                )
                tar.write(payload)
                tar.closeArchiveEntry()
            }

            dir("usr")
            dir("usr/bin")
            // The link to the directory comes first, exactly as it does in the real image.
            link("bin", "usr/bin")
            exe("usr/bin/dash", "dash")
            link("usr/bin/sh", "dash")
        }
        return bytes.toByteArray()
    }

    @Test
    fun `a link to a directory waits for the links inside it`() {
        val root = folder.newFolder("rootfs")
        mergedUsrTarGz().inputStream().use { RuntimeArchive.extractTarGz(it, root) }

        assertTrue("/usr/bin/sh is missing", File(root, "usr/bin/sh").isFile)
        assertTrue("/bin/sh is missing", File(root, "bin/sh").isFile)
        assertEquals("dash", File(root, "bin/sh").readText())
    }

    @Test
    fun `a copied directory keeps its executable bits`() {
        val root = folder.newFolder("rootfs")
        mergedUsrTarGz().inputStream().use { RuntimeArchive.extractTarGz(it, root) }

        assertTrue("/usr/bin/dash is not executable", File(root, "usr/bin/dash").canExecute())
        assertTrue("/bin/dash is not executable", File(root, "bin/dash").canExecute())
        assertTrue("/bin/sh is not executable", File(root, "bin/sh").canExecute())
    }
}
