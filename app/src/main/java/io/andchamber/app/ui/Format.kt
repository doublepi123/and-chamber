package io.andchamber.app.ui

import io.andchamber.app.R
import java.net.URI
import java.util.Locale

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    val pattern = if (value >= 100 || index == 0) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[index])
}

/**
 * The uptime text. Pure: the caller passes the localised "just started" and "Up %1$s" texts, so this is
 * unit-tested without a Context. Duration units (m, h, d) are kept as the short English form.
 */
internal fun uptimeLabel(
    sinceEpochMs: Long,
    nowEpochMs: Long,
    justStarted: String,
    upTemplate: (String) -> String,
): String {
    val minutes = (nowEpochMs - sinceEpochMs).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1 -> justStarted
        minutes < 60 -> upTemplate("${minutes}m")
        minutes < 60 * 24 -> upTemplate("${minutes / 60}h ${minutes % 60}m")
        else -> upTemplate("${minutes / (60 * 24)}d ${(minutes / 60) % 24}h")
    }
}

/** "http://127.0.0.1:3000/" -> "127.0.0.1:3000" for compact display. */
internal fun displayAddress(url: String): String = url.substringAfter("://").trimEnd('/')

internal sealed interface AddressCheck {
    data object Empty : AddressCheck

    data class Valid(val url: String, val host: String) : AddressCheck

    /** [messageRes] is the string resource that explains the problem; the UI resolves it. */
    data class Invalid(@androidx.annotation.StringRes val messageRes: Int) : AddressCheck
}

private val schemePrefix = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
private val ipv4Loopback = Regex("127(\\.\\d{1,3}){3}")

/** True for localhost, 127.0.0.0/8 and ::1 (with or without brackets). */
internal fun isLoopbackHost(host: String): Boolean {
    val h = host.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
    return h == "localhost" || h == "::1" || ipv4Loopback.matches(h)
}

/**
 * Normalizes what the user typed into a base URL: trims, adds http:// when no scheme is given,
 * keeps host, port and path, drops query/fragment and a trailing slash.
 */
internal fun checkServerAddress(raw: String): AddressCheck {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return AddressCheck.Empty
    if (trimmed.any { it.isWhitespace() }) return AddressCheck.Invalid(R.string.address_no_spaces)
    val withScheme = if (schemePrefix.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return AddressCheck.Invalid(R.string.address_invalid)
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return AddressCheck.Invalid(R.string.address_scheme)
    val authority = uri.rawAuthority ?: return AddressCheck.Invalid(R.string.address_no_host)
    // java.net.URI rejects some valid names (e.g. with "_"); fall back to the raw authority.
    val host =
        uri.host?.takeIf { it.isNotBlank() }
            ?: authority.substringAfterLast('@').substringBefore(':').takeIf { it.isNotBlank() }
            ?: return AddressCheck.Invalid(R.string.address_no_host)
    val path = uri.rawPath.orEmpty().trimEnd('/')
    return AddressCheck.Valid(url = "$scheme://$authority$path", host = host)
}
