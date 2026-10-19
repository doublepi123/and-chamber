package io.andchamber.app.data

import okhttp3.HttpUrl
import java.io.IOException

/** A download that must stop: bad Location, redirect loop, downgrade, or an invalid URL. */
internal class HttpDownloadRejected(message: String) : IOException(message)

internal const val MAX_DOWNLOAD_REDIRECTS = 5

internal val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

private const val MAX_FILE_NAME_CHARS = 120

/**
 * Redirect and cookie rules for one download chain. Pure Kotlin so it can be tested without Android.
 *
 *  - Cookies are attached only when the initial URL's canonical origin equals the session binding
 *    origin. Any move to another origin clears [cookiesAllowed] for the rest of the chain, including
 *    a later return to the binding origin.
 *  - Once the chain has reached https it never goes back to http.
 *  - Location must resolve to an http(s) URL without userinfo. Anything else is rejected.
 */
internal class DownloadRedirectPolicy(
    bindingOrigin: String,
    initial: HttpUrl,
    private val maxRedirects: Int = MAX_DOWNLOAD_REDIRECTS,
) {
    var url: HttpUrl = initial
        private set

    /** True while the chain is still on the binding origin and no hop has left it. */
    var cookiesAllowed: Boolean = false
        private set

    var redirects: Int = 0
        private set

    private var origin: String =
        canonicalWebOrigin(initial.toString())
            ?: throw HttpDownloadRejected("Download URL is not a plain http(s) origin")
    private var httpsSeen: Boolean = initial.isHttps

    init {
        // Compare canonical forms on both sides, so "HTTPS://Host:443" and "https://host" match.
        val binding = canonicalWebOrigin(bindingOrigin)
        cookiesAllowed = binding != null && origin == binding
    }

    /** Validates a redirect target and advances to it. Throws [HttpDownloadRejected] when refused. */
    fun nextLocation(location: String?): HttpUrl {
        if (redirects >= maxRedirects) throw HttpDownloadRejected("Too many redirects")
        val raw = location?.trim().orEmpty()
        if (raw.isEmpty()) throw HttpDownloadRejected("Redirect without a Location header")
        val next = url.resolve(raw) ?: throw HttpDownloadRejected("Invalid redirect Location")
        if (next.scheme != "http" && next.scheme != "https") {
            throw HttpDownloadRejected("Redirect to a non-http(s) scheme")
        }
        if (next.username.isNotEmpty() || next.password.isNotEmpty()) {
            throw HttpDownloadRejected("Redirect Location carries credentials")
        }
        if (httpsSeen && !next.isHttps) throw HttpDownloadRejected("Refusing https to http downgrade")
        val nextOrigin =
            canonicalWebOrigin(next.toString()) ?: throw HttpDownloadRejected("Invalid redirect origin")

        if (nextOrigin != origin) cookiesAllowed = false
        origin = nextOrigin
        httpsSeen = httpsSeen || next.isHttps
        redirects++
        url = next
        return next
    }
}

/**
 * Makes a server-supplied name safe as a single file name: path separators, reserved characters and
 * control characters become "_", leading and trailing dots are dropped, length is capped.
 */
internal fun sanitizeDownloadFileName(raw: String?): String =
    (raw ?: "")
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .trim('.')
        .take(MAX_FILE_NAME_CHARS)
        .trim('.', ' ')
        .ifEmpty { "download" }

/** "name (n).ext" for the n-th alternative of [name]; keeps the extension. */
internal fun numberedName(
    name: String,
    n: Int,
): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
}
