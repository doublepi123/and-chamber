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

/** Localised short duration forms: minutes; hours and minutes; days and hours. */
internal class DurationUnits(
    val minutes: (Long) -> String,
    val hoursMinutes: (Long, Long) -> String,
    val daysHours: (Long, Long) -> String,
)

/**
 * The uptime text. Pure: the caller passes the localised "just started", "Up %1$s" and duration texts,
 * so this is unit-tested without a Context.
 */
internal fun uptimeLabel(
    sinceEpochMs: Long,
    nowEpochMs: Long,
    justStarted: String,
    upTemplate: (String) -> String,
    units: DurationUnits,
): String {
    val minutes = (nowEpochMs - sinceEpochMs).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1 -> justStarted
        minutes < 60 -> upTemplate(units.minutes(minutes))
        minutes < 60 * 24 -> upTemplate(units.hoursMinutes(minutes / 60, minutes % 60))
        else -> upTemplate(units.daysHours(minutes / (60 * 24), (minutes / 60) % 24))
    }
}

/**
 * "http://127.0.0.1:3000/" -> "127.0.0.1:3000" for compact display. A saved address that still
 * carries userinfo (older versions accepted one) shows its host only, so a stored password is
 * never rendered again; the same applies when the address can't be parsed at all.
 */
internal fun displayAddress(url: String): String {
    val schemeEnd = url.indexOf("://")
    val rest = if (schemeEnd >= 0) url.substring(schemeEnd + 3) else url
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (authorityEnd >= 0) rest.substring(0, authorityEnd) else rest
    val userInfoEnd = authority.lastIndexOf('@')
    return if (userInfoEnd >= 0) rest.substring(userInfoEnd + 1).trimEnd('/') else rest.trimEnd('/')
}

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
    // Credentials are never part of a server address: the strict host parse can fail on them and
    // the loose fallback would then hide a stored password inside a "valid" URL.
    if (uri.rawUserInfo != null || authority.contains('@')) {
        return AddressCheck.Invalid(R.string.address_no_userinfo)
    }
    // java.net.URI rejects some valid names (e.g. with "_"); fall back to the raw authority.
    val host =
        uri.host?.takeIf { it.isNotBlank() }
            ?: authority.substringBefore(':').takeIf { it.isNotBlank() }
            ?: return AddressCheck.Invalid(R.string.address_no_host)
    val path = uri.rawPath.orEmpty().trimEnd('/')
    return AddressCheck.Valid(url = "$scheme://$authority$path", host = host)
}
