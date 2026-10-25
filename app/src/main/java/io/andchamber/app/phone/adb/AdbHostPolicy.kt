// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.UnknownHostException

/** Why an ADB host was refused. Fixed values; no host text is carried in state or errors. */
enum class AdbHostRefusal {
    INVALID,
    DNS_FORBIDDEN,
    REMOTE_FORBIDDEN,
}

/**
 * First-version host policy: only the loopback address or one of this device's own interface
 * addresses, and only as a numeric address literal.
 *
 * Host names are refused without a resolver call, so neither DNS nor an `adb`-style host/port
 * fallback can point the app at a remote phone. This is a policy gate, not a general firewall.
 */
class AdbHostPolicy(
    private val localAddresses: () -> Collection<InetAddress> = { deviceAddresses() },
) {
    fun refusal(host: String): AdbHostRefusal? {
        if (host.isEmpty() || host.length > MAX_HOST_CHARS || host != host.trim()) {
            return AdbHostRefusal.INVALID
        }
        val address = parseNumericAddress(host) ?: return AdbHostRefusal.DNS_FORBIDDEN
        if (address.isLoopbackAddress) return null
        if (address.isAnyLocalAddress || address.isMulticastAddress) {
            return AdbHostRefusal.REMOTE_FORBIDDEN
        }
        val local = localAddresses()
        if (local.any { it.address.contentEquals(address.address) }) return null
        return AdbHostRefusal.REMOTE_FORBIDDEN
    }

    fun isAllowed(host: String): Boolean = refusal(host) == null

    companion object {
        private const val MAX_HOST_CHARS = 64

        /**
         * Parses an IPv4 or IPv6 literal without ever asking a DNS server. A host name containing a
         * colon cannot exist, so the IPv6 branch is not a resolver path.
         */
        fun parseNumericAddress(host: String): InetAddress? {
            if (host.isEmpty() || host.length > MAX_HOST_CHARS || host.contains('%')) return null
            if (host.contains(':')) {
                if (!IPV6_LITERAL.matches(host) || host.contains(":::")) {
                    return null
                }
                return try {
                    InetAddress.getByName(host)
                } catch (_: UnknownHostException) {
                    null
                }
            }
            val parts = host.split('.')
            if (parts.size != 4) return null
            val octets = ByteArray(4)
            for ((index, part) in parts.withIndex()) {
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
                if (part.length > 1 && part.startsWith('0')) return null
                val value = part.toIntOrNull() ?: return null
                if (value !in 0..255) return null
                octets[index] = value.toByte()
            }
            return try {
                InetAddress.getByAddress(octets)
            } catch (_: UnknownHostException) {
                null
            }
        }

        private fun deviceAddresses(): Collection<InetAddress> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
        } catch (_: Exception) {
            emptyList()
        }

        private val IPV6_LITERAL = Regex("^[0-9A-Fa-f:.]+$")
    }
}
