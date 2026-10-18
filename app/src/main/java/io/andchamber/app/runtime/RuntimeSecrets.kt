package io.andchamber.app.runtime

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * The UI password that protects the on-device OpenChamber server.
 *
 * OpenChamber listens on loopback, which any app with INTERNET can reach. The password is
 * persisted in the app-private runtime directory (see the and-code LocalServerAuth) so the server
 * and the WebView login agree across restarts. It is passed to the server via OPENCHAMBER_UI_PASSWORD.
 */
object RuntimeSecrets {
    private const val SECRET_BYTES = 32

    /** Returns the persisted password, generating one on first use. */
    fun uiPassword(paths: RuntimePaths): String {
        existingUiPassword(paths)?.let { return it }
        return synchronized(this) {
            existingUiPassword(paths) ?: generate().also { secret ->
                paths.uiPasswordFile.parentFile?.mkdirs()
                paths.uiPasswordFile.writeText(secret)
            }
        }
    }

    /** The persisted password, or null when none has been generated yet. */
    fun existingUiPassword(paths: RuntimePaths): String? =
        runCatching { paths.uiPasswordFile.takeIf(File::isFile)?.readText()?.trim() }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)

    internal fun generate(random: SecureRandom = SecureRandom()): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(SECRET_BYTES).also(random::nextBytes),
        )
}
