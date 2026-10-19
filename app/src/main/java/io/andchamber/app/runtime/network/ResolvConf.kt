package io.andchamber.app.runtime.network

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
 * musl reads at most three nameservers, so the list is capped at three. When the system reports
 * usable servers, only those are used: public DNS may be blocked or inappropriate for this network.
 * Public resolvers are used only when no usable system server is available.
 */
object ResolvConf {
    const val MAX_NAMESERVERS = 3
    val PUBLIC_FALLBACK = listOf("1.1.1.1", "8.8.8.8")

    fun servers(system: List<String>): List<String> {
        val usable = system.map(::normalize).filter(::isUsable).distinct().take(MAX_NAMESERVERS)
        if (usable.isEmpty()) return PUBLIC_FALLBACK
        return usable
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
        writeAtomically(File(rootfs, "etc/resolv.conf"), render(system))
    }

    /**
     * Replaces [target] with [content] in one step: the bytes go to a temp file in the same
     * directory, then an atomic move renames it over the target. A reader sees the old file or the
     * new file, never a truncated mix. If the write fails, the temp file is removed and the original
     * is left untouched.
     */
    fun writeAtomically(
        target: File,
        content: String,
    ) {
        val dir = target.parentFile ?: error("resolv.conf target has no parent: $target")
        dir.mkdirs()
        val temp = File.createTempFile(".${target.name}.", ".tmp", dir)
        try {
            temp.writeText(content)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
