package io.andchamber.app.browser.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Claim-before-execute client. Inject [transport] in tests. A lost claim never calls [executor].
 * Result posts are not retried. The token is passed only as a header by [transport].
 *
 * One connection's pending jobs die with that connection. A new ready starts a new connection
 * and does not replay the old request. stop closes the socket, cancels the consumer, and
 * cancels in-flight execute.
 */
class BrowserProviderClient(
    origin: String,
    token: String,
    private val runtimeLaunchId: Long,
    private val instanceKey: String,
    private val clock: () -> Long,
    private val transport: ProviderTransport,
    private val executor: ProviderExecutor,
    private val scope: CoroutineScope,
    private val registry: ProviderRegistry = ProviderRegistry(),
    private val onConnection: (String?) -> Unit = {},
    private val onCancel: (String) -> Unit = {},
) : BrowserProvider {
    init {
        require(ProviderProtocol.isLoopbackOrigin(origin)) { "origin refused" }
        require(token.isNotEmpty()) { "token refused" }
    }

    private val gate = Mutex()
    private var generation = 0
    private var connectionId: String? = null
    private var serverEpoch: String? = null
    private var mode = ProviderMode.AGENT
    private var visibleTabId: String? = null
    private var consumer: Job? = null
    private val inFlight = linkedMapOf<String, Job>()
    private val cancelled = mutableSetOf<String>()

    override suspend fun start() {
        val previous = gate.withLock {
            val current = consumer
            consumer = null
            current
        }
        previous?.cancel()
        previous?.join()
        gate.withLock {
            consumer = scope.launch { connect() }
        }
    }

    override suspend fun stop() {
        val running = gate.withLock {
            val jobs = inFlight.values.toList()
            inFlight.clear()
            val current = consumer
            consumer = null
            connectionId = null
            serverEpoch = null
            registry.revoke()
            jobs + listOfNotNull(current)
        }
        onConnection(null)
        running.forEach { it.cancel() }
        (transport as? ProviderHttpTransport)?.stop()
    }

    override fun grant(sessionId: String, tabId: String) {
        val connection = connectionId ?: return
        registry.grant(runtimeLaunchId, connection, sessionId, tabId)
    }

    override fun revoke() = registry.revoke()

    override fun setManual(manual: Boolean) {
        mode = if (manual) ProviderMode.MANUAL else ProviderMode.AGENT
        if (manual) registry.revoke()
    }

    fun setVisibleTab(tabId: String?) {
        if (visibleTabId != tabId) registry.revoke()
        visibleTabId = tabId
    }

    private suspend fun connect() {
        val mine = gate.withLock { ++generation }
        try {
            transport.events().collect { event ->
                val ready = ProviderProtocol.parseReady(event)
                if (ready != null) {
                    adopt(ready)
                    return@collect
                }
                val connection = gate.withLock { connectionId } ?: return@collect
                val cancel = ProviderProtocol.parseCancel(event, connection)
                if (cancel != null) {
                    if (cancel.serverEpoch == serverEpoch) cancelRequest(cancel)
                    return@collect
                }
                val request = ProviderProtocol.parseRequest(event, connection) ?: return@collect
                if (request.serverEpoch != serverEpoch) return@collect
                launchRequest(request)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Transport failure ends this connection; the finally block revokes. Never crash the app.
        } finally {
            val stale = gate.withLock {
                if (generation != mine) return@withLock emptyList()
                val jobs = inFlight.values.toList()
                inFlight.clear()
                connectionId = null
                serverEpoch = null
                registry.revoke()
                jobs
            }
            if (stale.isNotEmpty() || gate.withLock { generation == mine && connectionId == null }) onConnection(null)
            stale.forEach { it.cancel() }
        }
    }

    private suspend fun adopt(ready: ProviderProtocol.Ready) {
        val stale = gate.withLock {
            val jobs = inFlight.values.toList()
            inFlight.clear()
            connectionId = ready.connectionId
            serverEpoch = ready.serverEpoch
            registry.revoke()
            jobs
        }
        onConnection(ready.connectionId)
        stale.forEach { it.cancel() }
    }

    /** Cancels the matching in-flight job. A cancel for another connection or request is ignored. */
    fun cancel(requestId: String, connectionId: String) {
        scope.launch {
            val epoch = gate.withLock { serverEpoch }
            cancelRequest(ProviderCancel(epoch.orEmpty(), connectionId, requestId, "cancelled"))
        }
    }

    fun registry(): ProviderRegistry = registry

    private suspend fun cancelRequest(cancel: ProviderCancel) {
        val running = gate.withLock {
            if (connectionId != cancel.connectionId || serverEpoch != cancel.serverEpoch) null
            else {
                cancelled += "${cancel.serverEpoch}:${cancel.requestId}"
                inFlight.remove("${cancel.serverEpoch}:${cancel.requestId}")
            }
        }
        running?.cancel()
        if (running != null) onCancel(cancel.requestId)
    }

    private suspend fun launchRequest(request: ProviderRequest) {
        val key = "${request.serverEpoch}:${request.requestId}"
        gate.withLock {
            if (connectionId != request.connectionId) return
            if (key in inFlight) return
            if (consumer == null) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    handle(request)
                } finally {
                    gate.withLock { inFlight.remove(key, coroutineContext[Job]) }
                }
            }
            inFlight[key] = job
            job.start()
        }
    }

    private suspend fun handle(request: ProviderRequest) {
        if (request.sessionId.isNullOrBlank()) return
        if (!registry.claimKey(request.serverEpoch, request.requestId)) return
        val claim = try {
            transport.claim(request.requestId, request.connectionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
        if (!claim.granted || claim.nonce.isEmpty()) return
        val deadline = ProviderProtocol.remainingDeadline(clock(), claim.remainingMs)
        val budget = deadline - clock()
        if (claim.remainingMs <= ProviderProtocol.NETWORK_MARGIN_MS || budget <= 0L) {
            post(request, ProviderResult(false, errorCode = ProviderCodes.DEADLINE), claim.nonce)
            return
        }
        val result = try {
            withTimeout(budget) {
                executor.execute(request, registry, mode, visibleTabId, deadline, clock)
            }
        } catch (cancelled: CancellationException) {
            val still = gate.withLock { connectionId }
            val key = "${request.serverEpoch}:${request.requestId}"
            val dropped = gate.withLock { this@BrowserProviderClient.cancelled.contains(key) }
            if (!dropped && cancelled !is kotlinx.coroutines.TimeoutCancellationException && still == request.connectionId) {
                post(request, ProviderResult(false, errorCode = ProviderCodes.OUTCOME_UNKNOWN), claim.nonce)
            }
            throw cancelled
        } catch (error: Exception) {
            // Class and action only: messages may echo page content.
            android.util.Log.w("AndChamberProvider", "${request.action} failed: ${error.javaClass.name}", error)
            ProviderResult(false, errorCode = ProviderCodes.OUTCOME_UNKNOWN)
        }
        post(request, result, claim.nonce)
    }

    private suspend fun post(request: ProviderRequest, result: ProviderResult, nonce: String) {
        val current = gate.withLock { connectionId }
        if (current != request.connectionId) return
        if (nonce.isEmpty()) return
        withContext(NonCancellable) {
            val still = gate.withLock { connectionId }
            if (still != request.connectionId) return@withContext
            try {
                transport.result(request.requestId, request.connectionId, nonce, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A result is posted once. A transport failure is not a retry.
            }
        }
    }
}

data class ProviderClaim(val granted: Boolean, val nonce: String, val remainingMs: Long)

interface ProviderTransport {
    suspend fun open(url: String, headers: Map<String, String>): ProviderProtocol.Ready
    fun events(): kotlinx.coroutines.flow.Flow<String>
    suspend fun claim(requestId: String, connectionId: String): ProviderClaim?
    suspend fun result(requestId: String, connectionId: String, nonce: String, result: ProviderResult)
}

fun interface ProviderExecutor {
    suspend fun execute(
        request: ProviderRequest,
        registry: ProviderRegistry,
        mode: ProviderMode,
        visibleTabId: String?,
        deadlineElapsed: Long,
        clock: () -> Long,
    ): ProviderResult
}

internal fun newTabId(): String = "tab-" + UUID.randomUUID().toString().take(8)
