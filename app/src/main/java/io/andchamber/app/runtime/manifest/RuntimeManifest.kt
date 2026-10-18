package io.andchamber.app.runtime.manifest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `assets/runtime-manifest.json`: which Alpine rootfs and OpenChamber/OpenCode versions to install. */
@Serializable
data class RuntimeManifest(
    val schemaVersion: Int,
    val runtimeVersion: String,
    val alpineVersion: String,
    val openChamberVersion: String,
    val openCodeVersion: String,
    val port: Int,
    val architectures: Map<String, RuntimeArchitecture>,
) {
    fun architecture(abi: String): RuntimeArchitecture =
        requireNotNull(architectures[abi]) { "Linux runtime does not support ABI $abi" }

    fun validate() {
        require(schemaVersion == 1) { "Unsupported runtime manifest schema: $schemaVersion" }
        require(runtimeVersion.isNotBlank()) { "Runtime version is missing" }
        require(alpineVersion.isNotBlank()) { "Alpine version is missing" }
        require(openChamberVersion.isNotBlank()) { "OpenChamber version is missing" }
        require(openCodeVersion.isNotBlank()) { "OpenCode version is missing" }
        require(port in 1024..65535) { "Invalid OpenChamber port: $port" }
        require(architectures.isNotEmpty()) { "Runtime manifest has no architectures" }
        architectures.forEach { (abi, item) -> item.validate(abi) }
    }
}

@Serializable
data class RuntimeArchitecture(
    val alpineUrl: String,
    val alpineSha256: String,
) {
    fun validate(abi: String) {
        require(alpineUrl.startsWith("https://")) { "Alpine URL for $abi must use HTTPS" }
        require(SHA256.matches(alpineSha256)) { "Invalid Alpine SHA-256 for $abi" }
    }

    private companion object {
        val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}

class RuntimeManifestReader(
    private val readAsset: () -> String,
) {
    fun read(): RuntimeManifest {
        val payload = readAsset()
        return JSON.decodeFromString<RuntimeManifest>(payload).also { it.validate() }
    }

    companion object {
        const val ASSET_NAME = "runtime-manifest.json"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
