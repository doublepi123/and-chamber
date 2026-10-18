package io.andchamber.app.ui

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

/** "Up 12m", "Up 2h 5m", "Up 3d 4h"; [Strings.justStarted] under a minute. */
internal fun uptimeLabel(
    sinceEpochMs: Long,
    nowEpochMs: Long,
): String {
    val minutes = (nowEpochMs - sinceEpochMs).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1 -> Strings.justStarted
        minutes < 60 -> Strings.uptime("${minutes}m")
        minutes < 60 * 24 -> Strings.uptime("${minutes / 60}h ${minutes % 60}m")
        else -> Strings.uptime("${minutes / (60 * 24)}d ${(minutes / 60) % 24}h")
    }
}

/** "http://127.0.0.1:3000/" -> "127.0.0.1:3000" for compact display. */
internal fun displayAddress(url: String): String = url.substringAfter("://").trimEnd('/')

internal sealed interface AddressCheck {
    data object Empty : AddressCheck

    data class Valid(val url: String, val host: String) : AddressCheck

    data class Invalid(val message: String) : AddressCheck
}

private val schemePrefix = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/**
 * Normalizes what the user typed into a base URL: trims, adds http:// when no scheme is given,
 * keeps host, port and path, drops query/fragment and a trailing slash.
 */
internal fun checkServerAddress(raw: String): AddressCheck {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return AddressCheck.Empty
    if (trimmed.any { it.isWhitespace() }) return AddressCheck.Invalid(Strings.addressNoSpaces)
    val withScheme = if (schemePrefix.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return AddressCheck.Invalid(Strings.addressInvalid)
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return AddressCheck.Invalid(Strings.addressScheme)
    val authority = uri.rawAuthority ?: return AddressCheck.Invalid(Strings.addressNoHost)
    // java.net.URI rejects some valid names (e.g. with "_"); fall back to the raw authority.
    val host =
        uri.host?.takeIf { it.isNotBlank() }
            ?: authority.substringAfterLast('@').substringBefore(':').takeIf { it.isNotBlank() }
            ?: return AddressCheck.Invalid(Strings.addressNoHost)
    val path = uri.rawPath.orEmpty().trimEnd('/')
    return AddressCheck.Valid(url = "$scheme://$authority$path", host = host)
}
