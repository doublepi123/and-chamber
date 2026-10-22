package io.andchamber.app.runtime.process

import io.andchamber.app.browser.tool.BrowserToolEndpoint
import io.andchamber.app.browser.tool.TrustedPath
import io.andchamber.app.runtime.fs.writeFileReplacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Installs the OpenCode browser-tool plugin into the private guest home, builds the launch-only
 * environment, and keeps the OpenChamber "agent web tool" default off unless the user chose it.
 *
 * The plugin directory is replaced on every start, so an APK upgrade always ships the current
 * plugin. The endpoint file (port and token) is separate and is written only after the tool server
 * listens. No token is placed in the environment or the config overlay; the persisted user config
 * is never edited.
 */
object BrowserPluginAssets {
    const val PACKAGE_ASSET = "browser-tool/package.json"
    const val INDEX_ASSET = "browser-tool/index.js"

    /** Guest directory the OpenCode config names in `plugins`. */
    const val GUEST_DIR = "/root/.local/share/andchamber/browser-tool"
    const val DIR_RELATIVE = ".local/share/andchamber/browser-tool"
    const val PACKAGE_RELATIVE = "$DIR_RELATIVE/package.json"
    const val INDEX_RELATIVE = "$DIR_RELATIVE/index.js"

    const val TOOL_ENV = "ANDCHAMBER_BROWSER_TOOL_FILE"
    const val GUEST_ENDPOINT_FILE = BrowserToolEndpoint.GUEST_FILE
    const val CONFIG_ENV = "OPENCODE_CONFIG_CONTENT"

    // Stock OpenChamber (packages/web/server/index.js: OPENCHAMBER_DATA_DIR or
    // ~/.config/openchamber) keeps settings.json in that data dir. The guest env sets HOME=/root and
    // XDG_CONFIG_HOME=/root/.config and never sets OPENCHAMBER_DATA_DIR.
    const val SETTINGS_RELATIVE = ".config/openchamber/settings.json"
    const val AGENT_WEB_TOOL_KEY = "agentWebToolEnabled"

    /** The two files the APK ships; production reads them from assets, tests pass fixtures. */
    data class Assets(
        val packageJson: String,
        val indexJs: String,
    )

    private const val PRIVATE_DIR_MODE = "rwx------"
    private val privateDirectories =
        listOf(
            ".local",
            ".local/share",
            ".local/share/andchamber",
            DIR_RELATIVE,
            ".local/state",
            ".local/state/andchamber",
        )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Host path of the plugin directory, relative to [home] (`runtime/home`, guest `/root`). */
    fun pluginDir(home: File): File = File(home, DIR_RELATIVE)

    /** Host path of the endpoint file. */
    fun endpointHostFile(home: File): File = BrowserToolEndpoint.hostFile(home)

    /** Host path of the OpenChamber instance settings file. */
    fun settingsFile(home: File): File = File(home, SETTINGS_RELATIVE)

    /**
     * Copies both plugin files into the private guest directory on every start. [trustedRoot] may
     * itself be a symlink and is not inspected; a symlink anywhere below it fails closed.
     */
    fun sync(
        home: File,
        assets: Assets,
        trustedRoot: File,
    ) {
        check(assets.packageJson.isNotBlank()) { "Refusing to sync an empty plugin package.json" }
        check(assets.indexJs.isNotBlank()) { "Refusing to sync an empty plugin index.js" }
        val homeSuffix = TrustedPath.suffixBelow(trustedRoot, home)
            ?: error("Browser plugin home is outside the trusted root")
        if (!TrustedPath.noSymlinkComponents(trustedRoot, homeSuffix)) {
            error("Refusing to use a symlinked browser plugin parent")
        }
        TrustedPath.ensureDirectories(trustedRoot, homeSuffix)
        privateDirectories.forEach { ensurePrivateDirectory(home, it) }
        writeFileReplacing(File(home, PACKAGE_RELATIVE), assets.packageJson)
        writeFileReplacing(File(home, INDEX_RELATIVE), assets.indexJs)
    }

    /**
     * Launch environment additions. [existingConfig] is the current `OPENCODE_CONFIG_CONTENT`, if
     * any. Returns null when the plugin must be skipped for this launch: invalid JSON, or a shape we
     * cannot merge without guessing. The caller then injects nothing and leaves the user's value
     * unchanged.
     */
    fun launchEnvironment(existingConfig: String?): Map<String, String>? {
        val merged = runCatching { mergeConfig(existingConfig) }.getOrNull() ?: return null
        return mapOf(
            TOOL_ENV to GUEST_ENDPOINT_FILE,
            CONFIG_ENV to merged,
        )
    }

    /**
     * Merges the plugin directory into `plugins`. The legacy singular `plugin` array is folded into
     * `plugins` and removed. Exact string duplicates are dropped, so the path appears once.
     *
     * Only `plugin`/`plugins` are touched: `mcp`, `permissions`, `permission`, `tools` and every
     * other user key are kept unchanged. A non-object document or a non-array plugin list fails
     * closed instead of silently discarding user data.
     */
    fun mergeConfig(existingConfig: String?): String {
        val text = existingConfig?.trim().orEmpty()
        if (text.isEmpty()) {
            return encode(emptyMap(), plugins = listOf(GUEST_DIR))
        }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("OPENCODE_CONFIG_CONTENT is not a JSON object")
        val plugins = linkedSetOf<String>()
        readStringArray(root["plugins"], "plugins")?.let { plugins += it }
        readStringArray(root["plugin"], "plugin")?.let { plugins += it }
        plugins += GUEST_DIR
        val merged = JsonObject(root - "plugins" - "plugin" + ("plugins" to JsonArray(plugins.map(::JsonPrimitive))))
        return json.encodeToString(JsonObject.serializer(), merged)
    }

    /**
     * Before start, if `agentWebToolEnabled` is absent from the OpenChamber instance settings, write
     * it as false. A present value (any type) is left exactly as it is. Returns a short reason when
     * the file was skipped, or null when it was created, updated, or already present.
     */
    fun ensureDefaultAgentWebTool(
        home: File,
        trustedRoot: File,
    ): String? {
        val file = settingsFile(home)
        val fileSuffix = TrustedPath.suffixBelow(trustedRoot, file) ?: return "settings-outside-root"
        val parentSuffix = TrustedPath.suffixBelow(trustedRoot, file.parentFile) ?: return "settings-outside-root"
        if (!TrustedPath.noSymlinkComponents(trustedRoot, parentSuffix)) return "settings-symlink"
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) return "settings-symlink"
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return "settings-not-a-file"
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            TrustedPath.ensureDirectories(trustedRoot, parentSuffix)
            writeAtomically(file, encode(mapOf(AGENT_WEB_TOOL_KEY to false), plugins = null))
            return null
        }
        val root = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?: return "settings-invalid-json"
        if (root.containsKey(AGENT_WEB_TOOL_KEY)) return null
        writeAtomically(file, json.encodeToString(JsonObject.serializer(), JsonObject(root + (AGENT_WEB_TOOL_KEY to JsonPrimitive(false)))))
        return null
    }

    /**
     * Upgrade cleanup, once per start: removes the two legacy browser-control artefacts this app
     * used to create, and nothing else. Symlinks below [trustedRoot] fail closed; user files,
     * `opencode.json(c)`, shared storage and the WebView profile are never touched.
     */
    fun cleanupLegacy(
        home: File,
        trustedRoot: File,
    ) {
        val legacyScript = File(home, ".local/share/andchamber/browser-control/browser-mcp.cjs")
        val legacyDir = legacyScript.parentFile
        val legacyState = File(home, ".local/state/andchamber/browser-control.json")
        listOf(legacyScript, legacyState).forEach { deleteNoFollow(it, trustedRoot) }
        val dirSuffix = TrustedPath.suffixBelow(trustedRoot, legacyDir) ?: return
        if (!TrustedPath.noSymlinkComponents(trustedRoot, dirSuffix)) return
        runCatching {
            if (Files.isDirectory(legacyDir.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.delete(legacyDir.toPath())
        }
    }

    private fun deleteNoFollow(
        file: File,
        trustedRoot: File,
    ) {
        val parentSuffix = TrustedPath.suffixBelow(trustedRoot, file.parentFile) ?: return
        if (!TrustedPath.noSymlinkComponents(trustedRoot, parentSuffix)) return
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return
        runCatching { Files.deleteIfExists(path) }
    }

    /** Reads an optional array of strings. Null means absent; a non-array or non-string entry throws. */
    private fun readStringArray(
        value: JsonElement?,
        name: String,
    ): List<String>? {
        if (value == null) return null
        val array = value as? JsonArray ?: throw IllegalArgumentException("$name is not an array")
        return array.map { entry ->
            val primitive = entry as? JsonPrimitive
            if (primitive == null || !primitive.isString) throw IllegalArgumentException("$name is not a string array")
            primitive.content
        }
    }

    private fun encode(
        fields: Map<String, Any?>,
        plugins: List<String>?,
    ): String =
        buildJsonObject {
            fields.forEach { (key, value) ->
                when (value) {
                    is Boolean -> put(key, value)
                    is String -> put(key, value)
                }
            }
            if (plugins != null) put("plugins", JsonArray(plugins.map(::JsonPrimitive)))
        }.toString()

    private fun writeAtomically(
        file: File,
        content: String,
    ) {
        val path = file.toPath()
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            error("Refusing to replace a directory with the settings file")
        }
        val temp = Files.createTempFile(file.parentFile.toPath(), ".settings", ".tmp")
        try {
            Files.write(temp, content.toByteArray(Charsets.UTF_8))
            Files.setPosixFilePermissions(temp, FILE_MODE)
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun ensurePrivateDirectory(
        home: File,
        relative: String,
    ) {
        var cursor = home
        for (part in relative.split('/')) {
            cursor = File(cursor, part)
            val path = cursor.toPath()
            if (Files.isSymbolicLink(path)) error("Refusing to use a symlinked browser plugin directory")
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    error("Browser plugin parent is not a directory")
                }
                Files.createDirectory(path)
            }
            val current = runCatching { Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            val next = current?.let { (it - GROUP_AND_OTHER) + OWNER_RWX } ?: PosixFilePermissions.fromString(PRIVATE_DIR_MODE)
            Files.setPosixFilePermissions(path, next)
        }
    }

    private val FILE_MODE = PosixFilePermissions.fromString("rw-------")

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
