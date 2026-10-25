package io.andchamber.app.runtime.process

import io.andchamber.app.browser.tool.TrustedPath
import io.andchamber.app.phone.tool.PhoneToolEndpoint
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
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Installs the OpenCode phone-tool plugin into the private guest home and builds its launch-only
 * environment.
 *
 * This is deliberately separate from [BrowserPluginAssets]: it has its own asset directory, guest
 * directory, endpoint file, environment variable and enable decision, and it never changes the
 * browser plugin or the browser's OpenChamber setting. The launcher merges the two overlays
 * serially; a failure here leaves the browser overlay exactly as it was (and the reverse).
 *
 * The plugin directory is replaced on every start, so an APK upgrade always ships the current
 * plugin. The endpoint file (port and token) is separate and is written only after the tool server
 * listens. No token is placed in the environment or the config overlay; the persisted user config
 * is never edited.
 */
object PhonePluginAssets {
    const val PACKAGE_ASSET = "phone-tool/package.json"
    const val INDEX_ASSET = "phone-tool/index.js"

    /** Guest directory the OpenCode config names in `plugins`. */
    const val GUEST_DIR = "/root/.local/share/andchamber/phone-tool"
    const val DIR_RELATIVE = ".local/share/andchamber/phone-tool"
    const val PACKAGE_RELATIVE = "$DIR_RELATIVE/package.json"
    const val INDEX_RELATIVE = "$DIR_RELATIVE/index.js"

    const val TOOL_ENV = "ANDCHAMBER_PHONE_TOOL_FILE"
    const val GUEST_ENDPOINT_FILE = PhoneToolEndpoint.GUEST_FILE
    const val CONFIG_ENV = "OPENCODE_CONFIG_CONTENT"

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
    fun endpointHostFile(home: File): File = PhoneToolEndpoint.hostFile(home)

    /**
     * Copies both plugin files into the private guest directory on every start. [trustedRoot] may
     * itself be a symlink and is not inspected; a symlink anywhere below it fails closed.
     */
    fun sync(
        home: File,
        assets: Assets,
        trustedRoot: File,
    ) {
        check(assets.packageJson.isNotBlank()) { "Refusing to sync an empty phone plugin package.json" }
        check(assets.indexJs.isNotBlank()) { "Refusing to sync an empty phone plugin index.js" }
        val homeSuffix = TrustedPath.suffixBelow(trustedRoot, home)
            ?: error("Phone plugin home is outside the trusted root")
        if (!TrustedPath.noSymlinkComponents(trustedRoot, homeSuffix)) {
            error("Refusing to use a symlinked phone plugin parent")
        }
        TrustedPath.ensureDirectories(trustedRoot, homeSuffix)
        privateDirectories.forEach { ensurePrivateDirectory(home, it) }
        writeFileReplacing(File(home, PACKAGE_RELATIVE), assets.packageJson)
        writeFileReplacing(File(home, INDEX_RELATIVE), assets.indexJs)
    }

    /**
     * Launch environment additions. [existingConfig] is the current `OPENCODE_CONFIG_CONTENT`, which
     * may already contain the browser overlay. Returns null when the phone plugin must be skipped
     * for this launch (invalid JSON or an unmergeable shape); the caller then injects no phone keys
     * and leaves the existing value, including a browser overlay, untouched.
     */
    fun launchEnvironment(existingConfig: String?): Map<String, String>? {
        val merged = runCatching { mergeConfig(existingConfig) }.getOrNull() ?: return null
        return mapOf(
            TOOL_ENV to GUEST_ENDPOINT_FILE,
            CONFIG_ENV to merged,
        )
    }

    /**
     * Merges only this plugin's directory into `plugins`, keeping every other key and every other
     * plugin path exactly once. The legacy singular `plugin` array is folded into `plugins` the same
     * way the browser overlay does it, so a serial browser-then-phone merge is stable. A non-object
     * document or a non-string plugin list fails closed instead of silently discarding user data.
     */
    fun mergeConfig(existingConfig: String?): String {
        val text = existingConfig?.trim().orEmpty()
        if (text.isEmpty()) {
            return buildJsonObject {
                put("plugins", JsonArray(listOf(JsonPrimitive(GUEST_DIR))))
            }.toString()
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

    private fun ensurePrivateDirectory(
        home: File,
        relative: String,
    ) {
        var cursor = home
        for (part in relative.split('/')) {
            cursor = File(cursor, part)
            val path = cursor.toPath()
            if (Files.isSymbolicLink(path)) error("Refusing to use a symlinked phone plugin directory")
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    error("Phone plugin parent is not a directory")
                }
                Files.createDirectory(path)
            }
            val current = runCatching { Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            val next = current?.let { (it - GROUP_AND_OTHER) + OWNER_RWX } ?: PosixFilePermissions.fromString(PRIVATE_DIR_MODE)
            Files.setPosixFilePermissions(path, next)
        }
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
