package io.andchamber.app.runtime.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network

/**
 * Android [NetworkEvents]: the default network's availability, link-property (DNS) and loss events.
 * Registration failures propagate to the caller, which reports them and leaves nothing registered.
 */
class AndroidNetworkEvents(
    private val context: Context,
) : NetworkEvents {
    override fun register(onChange: () -> Unit): () -> Unit {
        val manager =
            context.getSystemService(ConnectivityManager::class.java)
                ?: throw IllegalStateException("ConnectivityManager unavailable")
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChange()

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) = onChange()

                override fun onLost(network: Network) = onChange()
            }
        manager.registerDefaultNetworkCallback(callback)
        return {
            runCatching { manager.unregisterNetworkCallback(callback) }
            Unit
        }
    }
}
