package io.andchamber.app.browser

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * androidTest-only TCP relay: listens on 127.0.0.1:[port] inside the app process and forwards each
 * connection to the emulator host alias 10.0.2.2:[port]. Production keeps its loopback-only origin;
 * this replaces the shell `nc -L` relay, which dropped about one connection in sixty.
 */
internal class LoopbackRelay(private val relayPort: Int, private val upstreamHost: String = "10.0.2.2") {
    // Every field is declared before `init` starts the accept thread, so that thread never reads a
    // field that is still being initialised.
    private val live = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    private val running = AtomicBoolean(true)
    private val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "loopback-relay").apply { isDaemon = true } }
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), relayPort), 64)
    }

    init {
        pool.execute {
            while (running.get()) {
                val client = runCatching { server.accept() }.getOrNull() ?: continue
                pool.execute { bridge(client) }
            }
        }
    }

    private fun bridge(client: Socket) {
        // Not `apply { connect(..., port) }`: inside apply, `port` would be Socket.port (0).
        val upstream = runCatching { Socket().also { it.connect(InetSocketAddress(upstreamHost, relayPort), 5_000) } }.getOrElse {
            android.util.Log.w("OcProviderTest", "relay upstream failed ${it.javaClass.simpleName}: ${it.message}")
            runCatching { client.close() }
            return
        }
        client.tcpNoDelay = true
        upstream.tcpNoDelay = true
        live += client
        live += upstream
        val done = AtomicBoolean(false)
        val finished = java.util.concurrent.atomic.AtomicInteger(0)
        fun close() {
            if (done.compareAndSet(false, true)) {
                runCatching { client.close() }
                runCatching { upstream.close() }
                live -= client
                live -= upstream
            }
        }
        // Each direction ends independently; the pair closes when both have ended (or on any error).
        fun ended() { if (finished.incrementAndGet() == 2) close() }
        pool.execute { pump(client.getInputStream(), upstream.getOutputStream(), upstream, ::close); ended() }
        pump(upstream.getInputStream(), client.getOutputStream(), client, ::close)
        ended()
    }


    /** Copies until EOF, then half-closes the far side so a request body end is seen as end-of-input. */
    private fun pump(from: InputStream, to: OutputStream, target: Socket, close: () -> Unit) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
                to.flush()
            }
            runCatching { target.shutdownOutput() }
        } catch (_: Exception) {
            close()
        }
    }

    /** Drops every open connection and stops listening, as a crashed relay would. */
    fun stop() {
        running.set(false)
        runCatching { server.close() }
        live.toList().forEach { runCatching { it.close() } }
        live.clear()
        pool.shutdownNow()
    }
}
