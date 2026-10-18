package io.andchamber.app.runtime.storage

import io.andchamber.app.runtime.fs.writeFileReplacing
import java.io.File

/**
 * Writes the git configuration for shared storage into the private guest home (`<runtime>/home`, the
 * guest's /root) before each server start. Both files are AndChamber-owned:
 *  - `.config/andchamber/git/shared-storage.gitconfig` is overwritten every start;
 *  - the managed block in `.gitconfig` is replaced in place, and the user's other lines are kept.
 */
object GitConfigWriter {
    private const val SHARED_RELATIVE = ".config/andchamber/git/shared-storage.gitconfig"
    private const val GLOBAL_RELATIVE = ".gitconfig"

    /** Writes both files under [home]. Idempotent. */
    fun apply(home: File) {
        writeFileReplacing(File(home, SHARED_RELATIVE), GitConfigBlock.SHARED_STORAGE_GITCONFIG)
        val global = File(home, GLOBAL_RELATIVE)
        writeFileReplacing(global, GitConfigBlock.apply(readGlobal(global)))
    }

    /**
     * Reads the existing `.gitconfig` without following a link: a symlink there is replaced rather than
     * read, so its target is never touched. Returns null when there is no regular file to keep.
     */
    private fun readGlobal(global: File): String? =
        if (java.nio.file.Files.isRegularFile(global.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            global.readText(Charsets.UTF_8)
        } else {
            null
        }
}
