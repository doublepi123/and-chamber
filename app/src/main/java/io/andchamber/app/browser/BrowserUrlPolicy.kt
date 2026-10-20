package io.andchamber.app.browser

import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Navigation allow-list for the embedded control surface.
 *
 * [checkSyntax] is synchronous and safe to call from UI callbacks. [preflight] may do DNS and must
 * not be called on the main thread; a passed preflight is still not a complete WebView outbound
 * firewall. Subresource, redirect, and WebView-internal loads are outside this contract.
 */
class BrowserUrlPolicy(
    forbiddenOrigins: Set<String> = emptySet(),
    private val resolver: DnsResolver = DnsResolver { host -> InetAddress.getAllByName(host).toList() },
) {
    private val forbidden = forbiddenOrigins.mapNotNull { canonicalOrigin(it)?.lowercase() }.toSet()

    fun checkSyntax(raw: String): UrlDecision {
        if (raw.any { it.code > 0x7F }) return UrlDecision.Deny(UrlDenyReason.HOST)
        val parsed = parseHttps(raw) ?: return UrlDecision.Deny(UrlDenyReason.NOT_PUBLIC_HTTPS)
        if (parsed.userInfo != null) return UrlDecision.Deny(UrlDenyReason.USERINFO)
        val literal = parsed.host?.let { literalAddress(it) }
        if (literal != null && !isPublic(literal)) return UrlDecision.Deny(UrlDenyReason.NON_PUBLIC_ADDRESS)
        val host = parsed.host ?: return UrlDecision.Deny(UrlDenyReason.HOST)
        if (!hostAllowed(host)) return UrlDecision.Deny(UrlDenyReason.HOST)
        val origin = originOf(parsed, host)
        if (origin.lowercase() in forbidden) return UrlDecision.Deny(UrlDenyReason.FORBIDDEN_ORIGIN)
        val address = literalAddress(host)
        if (address != null && !isPublic(address)) return UrlDecision.Deny(UrlDenyReason.NON_PUBLIC_ADDRESS)
        return UrlDecision.Allow(origin)
    }

    /**
     * Syntax check plus an optional DNS preflight. Does not follow redirects and does not inspect
     * anything the WebView may load afterwards. Call off the main thread.
     */
    suspend fun preflight(raw: String): UrlDecision {
        val syntax = checkSyntax(raw)
        if (syntax is UrlDecision.Deny) return syntax
        val host = parseHttps(raw)?.host ?: return UrlDecision.Deny(UrlDenyReason.HOST)
        if (literalAddress(host) != null) return syntax
        val answers = try {
            resolver.resolve(decodeHost(host))
        } catch (_: Exception) {
            return UrlDecision.Deny(UrlDenyReason.DNS)
        }
        if (answers.isEmpty()) return UrlDecision.Deny(UrlDenyReason.DNS)
        if (answers.any { !isPublic(it) }) return UrlDecision.Deny(UrlDenyReason.NON_PUBLIC_ADDRESS)
        return syntax
    }

    private fun hostAllowed(host: String): Boolean {
        if (host.length > 253 || host.any { it.isISOControl() || it.isWhitespace() }) return false
        val lower = host.lowercase()
        if (lower.endsWith(".local") || lower.endsWith(".localhost") || lower == "localhost") return false
        if (lower.endsWith(".")) return false
        if (looksConfusable(host)) return false
        return try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).isNotEmpty()
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun looksConfusable(host: String): Boolean {
        if (host.any { it.code > 0x7F }) return false
        val lower = host.lowercase()
        if ("xn--" in lower) return true
        return lower.any { it !in "abcdefghijklmnopqrstuvwxyz0123456789-." }
    }

    private fun originOf(uri: URI, host: String): String {
        val port = if (uri.port == -1) 443 else uri.port
        val rendered = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "https://$rendered:$port"
    }

    companion object {
        fun canonicalOrigin(raw: String): String? {
            val parsed = parseHttps(raw) ?: return null
            if (parsed.userInfo != null) return null
            val host = parsed.host ?: return null
            val port = if (parsed.port == -1) 443 else parsed.port
            val rendered = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return "https://$rendered:$port"
        }

        internal fun parseHttps(raw: String): URI? {
            if (raw.isBlank() || raw.any { it.isISOControl() || it.isWhitespace() }) return null
            if (!raw.startsWith("https://")) return null
            val uri = try {
                URI(raw)
            } catch (_: Exception) {
                return null
            }
            if (uri.scheme != "https" || uri.rawSchemeSpecificPart.startsWith("//").not()) return null
            if (uri.host == null) return null
            return uri
        }

        internal fun literalAddress(host: String): InetAddress? {
            val bare = host.removePrefix("[").removeSuffix("]")
            if (bare.contains(':') && bare.contains('.')) {
                return runCatching { InetAddress.getByName(bare) }.getOrNull()
            }
            if (!bare.looksNumeric()) return null
            return try {
                val parsed = InetAddress.getByName(bare)
                if (bare.contains(':')) return parsed
                val rendered = parsed.hostAddress ?: return null
                if (!sameLiteral(bare, rendered)) null else parsed
            } catch (_: Exception) {
                null
            }
        }

        internal fun isPublic(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) {
                return false
            }
            return when (address) {
                is Inet4Address -> !isPrivateV4(address)
                is Inet6Address -> !isPrivateV6(address)
                else -> false
            }
        }

        private fun isPrivateV4(address: Inet4Address): Boolean {
            val b = address.address
            val a = b[0].toInt() and 0xff
            val c = b[1].toInt() and 0xff
            if (a == 0 || a == 10 || a == 127) return true
            if (a == 100 && c in 64..127) return true
            if (a == 169 && c == 254) return true
            if (a == 172 && c in 16..31) return true
            if (a == 192 && c == 168) return true
            if (a == 192 && c == 0) return true
            if (a == 198 && (c == 18 || c == 19)) return true
            if (a == 198 && c == 51) return true
            if (a == 203 && c == 0) return true
            if (a >= 224) return true
            return false
        }

        private fun isPrivateV6(address: Inet6Address): Boolean {
            val b = address.address
            if (b.size != 16) return true
            if (isV4Mapped(b)) {
                val v4 = InetAddress.getByAddress(b.copyOfRange(12, 16)) as Inet4Address
                return !isPublic(v4)
            }
            val first = b[0].toInt() and 0xff
            if (first == 0xfc || first == 0xfd) return true
            if (first == 0xfe && (b[1].toInt() and 0xc0) == 0x80) return true
            if (first == 0xff) return true
            if (b.all { it.toInt() == 0 }) return true
            return address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress
        }

        private fun isV4Mapped(bytes: ByteArray): Boolean {
            if (bytes.size != 16) return false
            for (i in 0 until 10) if (bytes[i].toInt() != 0) return false
            return bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        }

        private fun sameLiteral(raw: String, rendered: String): Boolean {
            val left = raw.lowercase().removePrefix("[").removeSuffix("]")
            val right = rendered.lowercase()
            if (left == right) return true
            return left.contains(':') && compress(left) == compress(right)
        }

        private fun compress(ipv6: String): String =
            ipv6.split(':').joinToString(":") { part ->
                if (part.isEmpty()) "" else part.trimStart('0').ifEmpty { "0" }
            }

        private fun decodeHost(host: String): String =
            IDN.toASCII(host.removePrefix("[").removeSuffix("]"), IDN.USE_STD3_ASCII_RULES)

        private fun String.looksNumeric(): Boolean {
            val bare = removePrefix("[").removeSuffix("]")
            if (bare.count { it == '.' } == 3 && bare.all { it.isDigit() || it == '.' }) return true
            return bare.contains(':')
        }
    }
}

fun interface DnsResolver {
    suspend fun resolve(host: String): List<InetAddress>
}

sealed class UrlDecision {
    data class Allow(val origin: String) : UrlDecision()

    data class Deny(val reason: UrlDenyReason) : UrlDecision()
}

enum class UrlDenyReason {
    NOT_PUBLIC_HTTPS,
    USERINFO,
    HOST,
    NON_PUBLIC_ADDRESS,
    FORBIDDEN_ORIGIN,
    DNS,
}
