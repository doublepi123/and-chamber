package io.andchamber.app.runtime.process

import java.net.HttpURLConnection
import java.net.URL

/** Checks whether an OpenChamber server answers on loopback. */
fun interface HealthProbe {
    fun isHealthy(port: Int): Boolean

    companion object {
        /** True when nothing is listening on loopback [port] (a connect attempt is refused). */
        fun isPortFree(port: Int): Boolean =
            runCatching {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                    false
                }
            }.getOrDefault(true)

        /** GET http://127.0.0.1:<port>/health and expect JSON `{"status":"ok"}`. */
        val Loopback: HealthProbe =
            HealthProbe { port ->
                val connection = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 1_500
                    connection.readTimeout = 2_000
                    if (connection.responseCode !in 200..299) return@HealthProbe false
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    Regex("\"status\"\\s*:\\s*\"ok\"").containsMatchIn(body)
                } catch (_: Exception) {
                    false
                } finally {
                    connection.disconnect()
                }
            }
    }
}
