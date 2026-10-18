package io.andchamber.app.runtime.storage

/**
 * Git configuration for repositories under `~/projects` (shared storage).
 *
 * Android's FUSE mount has no hard links, no symlinks, no exec bits, and files owned by the media
 * uid. Git's default `core.createObject=link` fails there, so the server's private `~/.gitconfig`
 * carries one AndChamber-managed block that includes a shared-storage file and adds safe.directory.
 *
 * Pure: the caller does the file I/O.
 */
object GitConfigBlock {
    const val BEGIN = "# >>> AndChamber (managed, do not edit) >>>"
    const val END = "# <<< AndChamber <<<"

    /** The guest path of the shared-storage config, included from the global block. */
    const val SHARED_CONFIG_PATH = "/root/.config/andchamber/git/shared-storage.gitconfig"

    /** Repositories at any depth under the shared projects folder. */
    const val PROJECTS_GITDIR = "gitdir:/root/projects/"

    /** The contents of [SHARED_CONFIG_PATH]: exactly these keys. */
    val SHARED_STORAGE_GITCONFIG: String =
        listOf(
            "[core]",
            "    createObject = rename",
            "    fileMode = false",
            "    symlinks = false",
        ).joinToString("\n", postfix = "\n")

    /** The managed block, without a trailing newline. `safe.directory` is global: includeIf can't carry it. */
    val MANAGED_BLOCK: String =
        listOf(
            BEGIN,
            "[includeIf \"$PROJECTS_GITDIR\"]",
            "    path = $SHARED_CONFIG_PATH",
            "[safe]",
            "    directory = /root/projects/*",
            END,
        ).joinToString("\n")

    /**
     * Returns [existing] with exactly one managed block. Absent input or an empty file yields just the
     * block. An existing block is replaced in place; otherwise the block is appended. Everything outside
     * the markers is kept byte-for-byte (line endings included). Applying twice equals applying once.
     */
    fun apply(existing: String?): String {
        val text = existing.orEmpty()
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val block = MANAGED_BLOCK.replace("\n", eol)
        val begin = text.indexOf(BEGIN)
        val end = if (begin >= 0) text.indexOf(END, begin + BEGIN.length) else -1
        if (begin >= 0 && end >= 0) {
            // The managed region runs from the start of the BEGIN line through the end of the END line.
            val regionStart = text.lastIndexOf('\n', begin - 1).let { if (it < 0) 0 else it + 1 }
            val endNewline = text.indexOf('\n', end)
            val regionEnd = if (endNewline < 0) text.length else endNewline + 1
            val before = text.substring(0, regionStart)
            val after = text.substring(regionEnd)
            // The block is written with its own line ending; a region that ended without a newline stays so.
            val blockWithEnd = if (endNewline < 0) block else block + eol
            return before + blockWithEnd + after
        }
        if (text.isEmpty()) return block + eol
        val separator = if (text.endsWith("\n")) "" else eol
        return text + separator + block + eol
    }
}
