package io.andchamber.app.runtime.network

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/** Supplies the DNS servers of the device's active network. */
fun interface SystemDnsProvider {
    fun dnsServers(): List<String>
}

/** Android implementation: DNS of the active network (Wi-Fi, mobile data or VPN). */
class AndroidSystemDnsProvider(
    private val context: Context,
) : SystemDnsProvider {
    override fun dnsServers(): List<String> =
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager.activeNetwork ?: return@runCatching emptyList<String>()
            manager.getLinkProperties(network)?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        }.getOrDefault(emptyList())
}

/**
 * The guest's /etc/resolv.conf. Rendering is pure so the policy is unit-tested.
 *
 * musl reads at most three nameservers, so the list is capped at three. System servers come first
 * because they are the ones that work on this network; public resolvers fill the remaining slots,
 * or are used alone when the system reports none. Public DNS is blocked on some networks, so it is
 * never the only choice when the system has servers.
 */
object ResolvConf {
    const val MAX_NAMESERVERS = 3
    val PUBLIC_FALLBACK = listOf("1.1.1.1", "8.8.8.8")

    fun servers(system: List<String>): List<String> {
        val usable = system.map(::normalize).filter(::isUsable).distinct().take(MAX_NAMESERVERS)
        if (usable.isEmpty()) return PUBLIC_FALLBACK
        return (usable + PUBLIC_FALLBACK).distinct().take(MAX_NAMESERVERS)
    }

    fun render(system: List<String>): String =
        servers(system).joinToString(separator = "") { "nameserver $it\n" }

    /** Removes IPv6 scope/zone ids (`fe80::1%wlan0`) and brackets, which musl cannot parse. */
    fun normalize(raw: String): String = raw.trim().substringBefore('%').removeSurrounding("[", "]")

    private fun isUsable(address: String): Boolean =
        address.isNotEmpty() && address.none(Char::isWhitespace) && address != "0.0.0.0" && address != "::"

    /** Writes the resolv.conf for [rootfs] from the given system servers. */
    fun write(
        rootfs: File,
        system: List<String>,
    ) {
        File(rootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText(render(system))
        }
    }
}
