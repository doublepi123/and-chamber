package io.andchamber.app.data

import io.andchamber.app.runtime.WebSessionIdentity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/**
 * Canonical origin ("scheme://host:port", effective port always present) shared by profiles, auth,
 * WebView and downloads. Path, query and fragment are dropped. URLs with userinfo are refused, so a
 * credential can never become part of an identity or an external open. Null for anything that is not
 * a plain http(s) URL. IPv6 hosts keep their brackets.
 */
fun canonicalWebOrigin(url: String): String? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http" && parsed.scheme != "https") return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    val host = parsed.host
    val hostPart = if (host.contains(':')) "[$host]" else host
    return "${parsed.scheme}://$hostPart:${parsed.port}"
}

/**
 * Stable WebView profile name for one identity. Hashes a length-prefixed encoding, so targetId and
 * origin cannot be confused with each other; the password and any path never take part. Never "Default".
 *
 * This is pure key derivation. It does not prove that the Android provider isolates anything (that needs
 * a device test).
 */
internal fun profileNameFor(identity: WebSessionIdentity): String {
    val material = "${identity.targetId.length}:${identity.targetId}|${identity.origin.length}:${identity.origin}"
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    return "andchamber_v1_" + digest.joinToString("") { "%02x".format(it) }
}
