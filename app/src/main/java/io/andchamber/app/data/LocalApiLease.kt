package io.andchamber.app.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One trusted local launch: the launch id reported by [LocalReadiness] together with the canonical
 * loopback origin the base URL pointed at when the launch was observed. Attached to every outgoing
 * request as a tag; a request whose lease is no longer current is never sent.
 */
internal data class LocalApiLease(
    val launchId: Long,
    val origin: HttpUrl,
)

/**
 * scheme://host:port of [raw] when it is a plain loopback http URL with no credentials, path, query
 * or fragment; otherwise null.
 */
internal fun canonicalLoopbackOrigin(raw: String?): HttpUrl? {
    val url = raw?.trim()?.toHttpUrlOrNull() ?: return null
    val loopback = url.host == "127.0.0.1" || url.host == "localhost" || url.host == "::1"
    if (url.scheme != "http" || !loopback) return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.encodedPath != "/" || url.encodedQuery != null || url.encodedFragment != null) return null
    return HttpUrl.Builder().scheme("http").host(url.host).port(url.port).build()
}

/** True when both URLs share scheme, host and port. */
internal fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

/**
 * Resolves an API [path] against this origin, or null when the path would leave it. Protocol-relative
 * (`//host`), absolute URLs and backslash tricks are refused before anything is built.
 */
internal fun HttpUrl.resolveSameOrigin(path: String): HttpUrl? {
    if (!path.startsWith("/") || path.startsWith("//") || path.contains('\\')) return null
    val resolved = resolve(path) ?: return null
    return resolved.takeIf { it.sameOrigin(this) }
}
