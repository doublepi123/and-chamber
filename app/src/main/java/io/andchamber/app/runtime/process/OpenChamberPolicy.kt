package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.fs.writeFileReplacing
import java.io.File

/**
 * OpenChamber's machine policy, written into the guest at `/etc/openchamber/policy.json`.
 *
 * With `opencodeBinary` pinned, OpenChamber reports its OpenCode upgrade as unavailable (manager
 * `administrator`, reason `policy`), so the in-app "update OpenCode" action is hidden and the upgrade
 * routes refuse. OpenCode is then launched from that pinned path. AndChamber owns OpenCode updates, and
 * an in-place `opencode upgrade` would break the PRoot link2symlink layout. Verified against OpenChamber
 * 2.5.0: no other policy flag is changed by this file alone.
 *
 * Invalid JSON there turns enterprise mode on (fail-closed), so the file is always written whole and
 * atomically, through [writeFileReplacing].
 */
object OpenChamberPolicy {
    /** Guest path of the policy file, relative to the rootfs. */
    const val RELATIVE_PATH = "etc/openchamber/policy.json"

    /** The exact content. Built by hand so the bytes do not depend on a JSON library's formatting. */
    val CONTENT: String = "{\"opencodeBinary\":\"${ProotCommands.GUEST_OPENCODE}\"}\n"

    /**
     * Writes the policy into [rootfs]. Creates the parent directories and replaces whatever is at the
     * target (a symlink is unlinked, its destination is not written). Skips the write when the file already
     * has exactly [CONTENT], so the modification time stays put. Throws on I/O failure.
     */
    fun write(rootfs: File) {
        val target = File(rootfs, RELATIVE_PATH)
        if (target.isFile && !java.nio.file.Files.isSymbolicLink(target.toPath()) && target.readText() == CONTENT) return
        writeFileReplacing(target, CONTENT)
    }
}
