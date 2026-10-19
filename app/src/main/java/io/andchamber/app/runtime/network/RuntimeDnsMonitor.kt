package io.andchamber.app.runtime.network

import java.io.File

/**
 * Keeps the guest resolv.conf in step with the network while the server runs.
 * [start] begins watching [rootfs] (and refreshes once). [stop] ends watching; once it returns, no
 * write for an earlier [start] may land. Implementations must be safe to call from any thread.
 */
interface RuntimeDnsMonitor {
    fun start(rootfs: File)

    fun stop()

    /** The default: no live refresh. The pre-start refresh still runs in the server. */
    object None : RuntimeDnsMonitor {
        override fun start(rootfs: File) = Unit

        override fun stop() = Unit
    }
}

/** Adapts [DnsRefresher] to the monitor contract. A start failure is reported, not thrown. */
class DnsRefresherMonitor(
    private val refresher: DnsRefresher,
) : RuntimeDnsMonitor {
    override fun start(rootfs: File) {
        refresher.start(rootfs)
    }

    override fun stop() = refresher.stop()
}
