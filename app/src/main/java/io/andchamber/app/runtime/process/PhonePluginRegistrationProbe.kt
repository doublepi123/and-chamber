package io.andchamber.app.runtime.process

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Native registration readiness for one launch.
 *
 * The old setup `/v1/ready` transport handshake is retained for compatibility, but production uses
 * an explicit RPC registration probe: the plugin registers a read-only `andchamber-phone/ready`
 * RPC only after its tool transform and `ctx.tool.list` confirm `andchamber_phone`. A server that is
 * merely listening, an endpoint file or an active launch is never enough.
 */
interface PhonePluginRegistrationWaiter {
    /** True when this waiter is the production RPC gate; false keeps the legacy transport path. */
    val available: Boolean get() = true

    /** Suspends on an IO dispatcher, bounded; never called from Main. */
    suspend fun awaitRegistered(launchId: Long): Boolean

    /** Tests/non-local runtimes: no RPC gate, so the legacy transport handshake may still decide. */
    object Unavailable : PhonePluginRegistrationWaiter {
        override val available: Boolean = false

        override suspend fun awaitRegistered(launchId: Long): Boolean = false
    }
}

/**
 * Bounded retry wrapper for the native RPC probe. The attempt is a pure suspend callback (DI wires
 * `LocalApiClient.send`); this class owns the deadline, delay and response-to-boolean boundary.
 * It never logs the request or response body and propagates cancellation immediately.
 */
internal class PhonePluginRegistrationProbe(
    private val attempt: suspend (launchId: Long) -> Boolean,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : PhonePluginRegistrationWaiter {
    override val available: Boolean = true

    override suspend fun awaitRegistered(launchId: Long): Boolean {
        val deadline = clock() + timeoutMs.coerceAtLeast(0L)
        while (true) {
            val registered =
                try {
                    attempt(launchId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            if (registered) return true
            val remaining = deadline - clock()
            if (remaining <= 0L) return false
            pause(minOf(retryDelayMs.coerceAtLeast(1L), remaining))
        }
    }

    private companion object {
        /** Sized to the local runtime's bounded start window, not a UI interaction timeout. */
        const val DEFAULT_TIMEOUT_MS = 90_000L
        const val DEFAULT_RETRY_DELAY_MS = 500L
    }
}

/**
 * Exact response gate for `POST /api/rpc/andchamber-phone/ready`:
 * 2xx and `{ "output": { "ok": true, "id": "andchamber_phone" } }`.
 */
internal fun phonePluginRegistrationAccepted(
    status: Int,
    body: String,
): Boolean {
    if (status !in 200..299) return false
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return false
    val output = root["output"] as? JsonObject ?: return false
    val okElement = output["ok"] as? JsonPrimitive ?: return false
    val idElement = output["id"] as? JsonPrimitive ?: return false
    // JSON booleans/strings only: a quoted "true" or numeric id is not the contract.
    if (okElement.isString || idElement.isString.not()) return false
    return okElement.booleanOrNull == true && idElement.contentOrNull == "andchamber_phone"
}
