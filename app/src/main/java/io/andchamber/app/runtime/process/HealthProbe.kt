package io.andchamber.app.runtime.process

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL

/** What `GET /health` told us. [serverId] lets readiness polls confirm they reach the same instance. */
data class HealthReport(
    val status: String?,
    val isOpenCodeReady: Boolean,
    val openCodeRunning: Boolean,
    val lastOpenCodeError: String?,
    val serverId: String?,
) {
    /** The web server answers and reports itself ok. */
    val serverOk: Boolean get() = status == "ok"
}

/** Reads OpenChamber's `/health` on loopback. */
fun interface HealthProbe {
    /** Null when nothing answered or the body was not JSON. */
    fun read(port: Int): HealthReport?

    /** Convenience: true only when the web server reports ok. */
    fun isHealthy(port: Int): Boolean = read(port)?.serverOk == true

    companion object {
        /** Parses a `/health` body. Pure, so the readiness rules are unit-tested. */
        fun parse(body: String): HealthReport? =
            runCatching {
                val json = Json.parseToJsonElement(body).jsonObject
                fun text(key: String): String? = (json[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
                fun flag(key: String): Boolean = (json[key] as? JsonPrimitive)?.booleanOrNull == true
                HealthReport(
                    status = text("status"),
                    isOpenCodeReady = flag("isOpenCodeReady"),
                    openCodeRunning = flag("openCodeRunning"),
                    lastOpenCodeError = text("lastOpenCodeError")?.takeIf { it.isNotBlank() },
                    serverId = text("serverId")?.takeIf { it.isNotBlank() },
                )
            }.getOrNull()

        /** GET http://127.0.0.1:<port>/health. */
        val Loopback: HealthProbe =
            HealthProbe { port ->
                val connection = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 2_000
                    // Node on a phone can stall for seconds under the web UI's initial request burst.
                    connection.readTimeout = 5_000
                    if (connection.responseCode !in 200..299) return@HealthProbe null
                    parse(connection.inputStream.bufferedReader().use { it.readText() })
                } catch (_: Exception) {
                    null
                } finally {
                    connection.disconnect()
                }
            }

        /** True when nothing is listening on loopback [port] (connect is refused). */
        fun isPortFree(port: Int): Boolean =
            runCatching {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                    false
                }
            }.getOrDefault(true)
    }
}

/**
 * Readiness rules, pure. Trust order matters:
 *  1. Our own child printed the listen line (the pipe is private to us, so no foreign process can
 *     print it on our behalf). Node prints it only after a successful listen; EADDRINUSE exits.
 *  2. Only then is /health believed: web server ok AND OpenCode ready.
 *  3. A concrete OpenCode error while not running fails the start.
 */
object Readiness {
    /** The line OpenChamber prints after listening, e.g. `OpenChamber server listening on 127.0.0.1:3000`. */
    fun listenMarker(port: Int): String = "OpenChamber server listening on 127.0.0.1:$port"

    /** True when our child's own output contains the listen line for [port]. */
    fun childListening(output: List<String>, port: Int): Boolean =
        output.any { it.contains(listenMarker(port)) }

    sealed interface Verdict {
        data object Waiting : Verdict

        data object Ready : Verdict

        data class OpenCodeFailed(val error: String) : Verdict
    }

    /**
     * [reports] is the sequence of readings so far, newest last. [previousServerId] pins identity:
     * a serverId that changes between polls means another instance answered.
     */
    fun verdict(latest: HealthReport?): Verdict {
        if (latest == null || !latest.serverOk) return Verdict.Waiting
        if (latest.isOpenCodeReady) return Verdict.Ready
        val error = latest.lastOpenCodeError
        if (error != null && !latest.openCodeRunning) return Verdict.OpenCodeFailed(error)
        return Verdict.Waiting
    }

    /** True when the serverId stayed the same across readings (or none was reported). */
    fun identityStable(serverIds: List<String?>): Boolean =
        serverIds.filterNotNull().distinct().size <= 1
}
