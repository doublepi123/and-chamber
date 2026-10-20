package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.fs.writeFileReplacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Syncs the embedded browser MCP adapter into the private guest home and builds the launch-only
 * OpenCode config overlay. The credential file is created by the UI controller when the user enables
 * the tool; this package never writes a token, and it never edits the persisted user config.
 */
object BrowserToolAssets {
    const val ASSET_NAME = "browser-control/browser-mcp.cjs"
    const val GUEST_SCRIPT = "/root/.local/share/andchamber/browser-control/browser-mcp.cjs"
    const val GUEST_ENDPOINT_FILE = "/root/.local/state/andchamber/browser-control.json"
    const val ENDPOINT_ENV = "ANDCHAMBER_BROWSER_ENDPOINT_FILE"
    const val CONFIG_ENV = "OPENCODE_CONFIG_CONTENT"
    const val MCP_SERVER_NAME = "andchamber_browser"

    /** Guest path of the script, relative to the private home bound over `/root`. */
    const val SCRIPT_RELATIVE = ".local/share/andchamber/browser-control/browser-mcp.cjs"

    /** Guest path of the credential file, relative to the private home bound over `/root`. */
    const val ENDPOINT_RELATIVE = ".local/state/andchamber/browser-control.json"

    private const val PRIVATE_DIR_MODE = "rwx------"
    private val privateDirectories =
        listOf(
            ".local",
            ".local/share",
            ".local/share/andchamber",
            ".local/share/andchamber/browser-control",
            ".local/state",
            ".local/state/andchamber",
        )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Host path of the credential file under [home] (`runtime/home`, the guest `/root`). The UI writes
     * it when the user enables the tool. Runtime only names the path; it never creates the file.
     */
    fun endpointHostFile(home: File): File = File(home, ENDPOINT_RELATIVE)

    /**
     * Copies [asset] to the private guest script on every call, so an APK upgrade replaces a script
     * left by an already-installed runtime. Created parents are owner-only. [trustedRoot] is the
     * app-private boundary (`context.filesDir`): it may itself be a symlink, and it is not walked or
     * rejected. Only the suffix below it is checked, and a symlink there is never followed. [home]
     * itself is not chmod'd.
     */
    fun sync(home: File, asset: String, trustedRoot: File) {
        if (asset.isEmpty()) error("Refusing to sync an empty browser-control adapter")
        val suffix = suffixBelowTrustedRoot(trustedRoot, home)
            ?: error("Browser-control home is outside the trusted root")
        if (!suffixIsUnlinked(trustedRoot, suffix)) {
            error("Refusing to use a symlinked browser-control parent")
        }
        ensureSuffixDirectories(trustedRoot, suffix)
        privateDirectories.forEach { relative -> ensurePrivateDirectory(home, relative) }
        writeFileReplacing(File(home, SCRIPT_RELATIVE), asset)
    }

    /**
     * Removes a leftover credential file before a new launch. A missing file is success. [trustedRoot]
     * may be a symlink and is not inspected; a symlink in a parent below it fails closed and deletes
     * nothing. The credential file itself is not part of that parent check: a symlink leaf is unlinked
     * without following it.
     *
     * Call this only while no new lease can be issued. A credential written after this returns is
     * not covered.
     */
    fun invalidateEndpoint(home: File, trustedRoot: File): Boolean {
        val credential = endpointHostFile(home)
        val parentSuffix = suffixBelowTrustedRoot(trustedRoot, credential.parentFile) ?: return false
        if (!suffixIsUnlinked(trustedRoot, parentSuffix)) return false
        val path = credential.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(path)) {
            return unlinkIfStillHere(path)
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
        return unlinkIfStillHere(path)
    }

    private fun unlinkIfStillHere(path: java.nio.file.Path): Boolean =
        try {
            Files.deleteIfExists(path)
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        } catch (_: java.io.IOException) {
            false
        }

    /**
     * Launch environment additions: the endpoint-file path (never its contents) and an OpenCode
     * config overlay that registers the local MCP server. [existingConfig] is merged when present.
     * The overlay names no token.
     */
    fun launchEnvironment(existingConfig: String? = null): Map<String, String> =
        mapOf(
            ENDPOINT_ENV to GUEST_ENDPOINT_FILE,
            CONFIG_ENV to mergeConfig(existingConfig),
        )

    /**
     * Merges the browser MCP server into an existing `OPENCODE_CONFIG_CONTENT` document. User
     * top-level fields and other MCP servers are kept. A missing or blank document becomes the
     * overlay. A non-empty document that is not a JSON object fails closed: the original text is
     * not replaced and not returned as a half-merged document. [serverEnvironment] is preserved and
     * must already be strict: this merge never adds the endpoint file or a token to it.
     */
    fun mergeConfig(existingConfig: String?): String {
        val root = parseObject(existingConfig)
        if (root == null) {
            if (existingConfig.isNullOrBlank()) return overlayConfig()
            throw IllegalArgumentException("OPENCODE_CONFIG_CONTENT is not a JSON object")
        }
        val mcp = root["mcp"]?.jsonObjectOrNull()
        val servers = mcp?.get("servers")?.jsonObjectOrNull() ?: JsonObject(emptyMap())
        val mergedServers = JsonObject(servers + (MCP_SERVER_NAME to serverEntry(servers[MCP_SERVER_NAME])))
        val mergedMcp = JsonObject((mcp ?: JsonObject(emptyMap())) + ("servers" to mergedServers))
        return json.encodeToString(JsonObject.serializer(), JsonObject(root + ("mcp" to mergedMcp)))
    }

    /**
     * Official config v2 is `mcp.servers.<name>`, not a bare name under `mcp`. `command` is an array.
     * `environment` is optional and inherited by the adapter; the endpoint file env is not copied here.
     */
    private fun serverEntry(existing: JsonElement?): JsonObject {
        val command =
            buildJsonArray {
                add(JsonPrimitive("node"))
                add(JsonPrimitive(GUEST_SCRIPT))
            }
        val current = existing?.jsonObjectOrNull()
        val kept = current?.filterKeys { it != "type" && it != "command" && it != "environment" }.orEmpty()
        val environment = strictServerEnvironment(current?.get("environment"))
        return JsonObject(
            kept + mapOf("type" to JsonPrimitive("local"), "command" to command) +
                (environment?.let { mapOf("environment" to it) }.orEmpty()),
        )
    }

    /**
     * Keeps a caller-supplied strict `environment` object and drops only the endpoint-file key.
     * A non-object environment is omitted. This never inserts the endpoint path or a token.
     */
    private fun strictServerEnvironment(existing: JsonElement?): JsonObject? {
        val objectValue = existing?.jsonObjectOrNull() ?: return null
        val kept = objectValue.filterKeys { it != ENDPOINT_ENV }
        return kept.takeIf { it.isNotEmpty() }?.let { JsonObject(it) }
    }

    private fun overlayConfig(): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put(
                    "mcp",
                    buildJsonObject {
                        put(
                            "servers",
                            buildJsonObject {
                                put(
                                    MCP_SERVER_NAME,
                                    buildJsonObject {
                                        put("type", "local")
                                        put(
                                            "command",
                                            buildJsonArray {
                                                add(JsonPrimitive("node"))
                                                add(JsonPrimitive(GUEST_SCRIPT))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

    private fun parseObject(raw: String?): JsonObject? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { json.parseToJsonElement(text).jsonObjectOrNull() }.getOrNull()
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

    /**
     * Path of [path] relative to [trustedRoot], using only lexical normalization. The trusted root is
     * not canonicalized, so a symlink at the root stays the boundary instead of being followed into
     * a check of `/data/user` or `/`. Returns null when [path] escapes the root.
     */
    internal fun suffixBelowTrustedRoot(trustedRoot: File, path: File): String? {
        val rootPath = trustedRoot.absoluteFile.normalize().path
        val childPath = path.absoluteFile.normalize().path
        if (childPath == rootPath) return ""
        val prefix = rootPath + File.separator
        if (!childPath.startsWith(prefix)) return null
        val suffix = childPath.removePrefix(prefix)
        val parts = suffix.split(File.separator)
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return suffix
    }

    /**
     * True when no existing component of [suffix] below [trustedRoot] is a symlink. Missing
     * components are allowed. The trusted root itself is not inspected.
     */
    private fun suffixIsUnlinked(trustedRoot: File, suffix: String): Boolean {
        if (suffix.isEmpty()) return true
        val parts = suffix.split(File.separator)
        var cursor = trustedRoot
        for ((index, part) in parts.withIndex()) {
            cursor = File(cursor, part)
            val node = cursor.toPath()
            if (Files.isSymbolicLink(node)) return false
            if (!Files.exists(node, LinkOption.NOFOLLOW_LINKS)) return true
            val last = index == parts.lastIndex
            if (!last && !Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS)) return false
        }
        return true
    }

    /**
     * Creates missing real directories for [suffix] below [trustedRoot]. Does not follow a symlink
     * and does not chmod the trusted root. A missing `runtime/home` is a normal first-launch suffix,
     * not a reason to refuse the adapter copy.
     */
    private fun ensureSuffixDirectories(trustedRoot: File, suffix: String) {
        if (suffix.isEmpty()) return
        var cursor = trustedRoot
        for (part in suffix.split(File.separator)) {
            cursor = File(cursor, part)
            val path = cursor.toPath()
            if (Files.isSymbolicLink(path)) error("Refusing to use a symlinked browser-control parent")
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
        }
    }

    /**
     * Creates [relative] under [home] as owner-only (`0700`) when missing. An existing directory has
     * owner rwx added and group/other bits cleared. A symlink is never followed or chmod'd. [home]
     * itself is not modified.
     */
    private fun ensurePrivateDirectory(home: File, relative: String) {
        var cursor = home
        for (part in relative.split('/')) {
            cursor = File(cursor, part)
            val path = cursor.toPath()
            if (Files.isSymbolicLink(path)) {
                error("Refusing to use a symlinked browser-control directory: ${cursor.name}")
            }
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    error("Browser-control parent is not a directory: ${cursor.name}")
                }
                Files.createDirectory(path)
            }
            tightenOwnerOnly(path)
        }
    }

    private fun tightenOwnerOnly(path: java.nio.file.Path) {
        val ownerOnly = PosixFilePermissions.fromString(PRIVATE_DIR_MODE)
        val current =
            runCatching { Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
        val next =
            if (current == null) {
                ownerOnly
            } else {
                (current - GROUP_AND_OTHER) + OWNER_RWX
            }
        Files.setPosixFilePermissions(path, next)
    }

    private val OWNER_RWX =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )

    private val GROUP_AND_OTHER =
        setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
}
