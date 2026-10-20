package io.andchamber.app.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Lease and admission control for one visible WebView. Mutable control state is owned by
 * [mainContext]. [takeOverFromUi] is the only synchronous entry and must be called there.
 * Listener bind, credential publish, and teardown share [resources], so an old stop cannot close
 * a listener started by a newer enable.
 */
class BrowserControllerImpl internal constructor(
    credentialFile: File,
    private val clock: BrowserClock = BrowserClock.system(),
    private val policy: BrowserUrlPolicy = BrowserUrlPolicy(),
    private val mainContext: CoroutineContext = EmptyCoroutineContext,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + mainContext),
    private val random: SecureRandom = SecureRandom(),
    private val credentials: CredentialPublisher,
    private val sessionIdFactory: () -> String = { newToken(random) },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val currentRuntimeLaunchId: () -> Long? = { null },
) : BrowserController {
    constructor(
        credentialFile: File,
        clock: BrowserClock = BrowserClock.system(),
        policy: BrowserUrlPolicy = BrowserUrlPolicy(),
        mainContext: CoroutineContext = EmptyCoroutineContext,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + mainContext),
        random: SecureRandom = SecureRandom(),
        sessionIdFactory: () -> String = { newToken(random) },
        sleeper: suspend (Long) -> Unit = { delay(it) },
        currentRuntimeLaunchId: () -> Long? = { null },
        trustedRoot: File? = null,
    ) : this(
        credentialFile = credentialFile,
        clock = clock,
        policy = policy,
        mainContext = mainContext,
        scope = scope,
        random = random,
        credentials = FileCredentialPublisher(credentialFile, trustedRoot),
        sessionIdFactory = sessionIdFactory,
        sleeper = sleeper,
        currentRuntimeLaunchId = currentRuntimeLaunchId,
    )

    private val stateFlow = MutableStateFlow(BrowserState())
    override val state: StateFlow<BrowserState> = stateFlow.asStateFlow()
    private val resources = Mutex()
    private val queue = Channel<Pending>(capacity = 1)

    private var driver: BrowserDriver? = null
    private var listener: BrowserListener? = null

    @Volatile private var lease: Lease? = null
    private var generation: Long = 0
    private var resourceGeneration: Long = 0
    private var busyId: String? = null
    private var seenIds: MutableSet<String> = linkedSetOf()
    private var boundEndpoint: String? = null
    private var viewInvalidated = false
    private var worker: Job? = null
    private var activePending: Pending? = null

    override fun attach(driver: BrowserDriver) {
        this.driver = driver
        viewInvalidated = false
        revoke(BrowserPhase.DISABLED, sideEffectUnknown = false)
        publish()
    }

    override fun detach() {
        driver = null
        revoke(BrowserPhase.DISABLED, sideEffectUnknown = false)
        publish()
        scheduleClose()
    }

    override fun setVisible(visible: Boolean) {
        if (!visible && (lease != null || boundEndpoint != null)) {
            revoke(BrowserPhase.DISABLED, sideEffectUnknown = false)
            scheduleClose()
        }
        publish(visible = visible)
    }

    override suspend fun enableFromUi(): Boolean = resources.withLock {
        val epoch = withContext(mainContext) {
            if (!canEnable()) null else generation
        } ?: return false
        val activeListener = listener ?: return false
        val owned = withContext(mainContext) { resourceGeneration }
        if (boundEndpoint == null) {
            val bound = CompletableDeferred<String?>()
            val started = runCatching {
                activeListener.start { endpoint ->
                    bound.complete(endpoint.takeIf { it.matches(ENDPOINT_PATTERN) })
                }
            }
            if (started.isFailure || bound.await() == null || !stillEnabling(epoch, owned)) {
                if (resourceGeneration == owned) runCatching { activeListener.stop() }
                return false
            }
        }
        val issued = withContext(mainContext) {
            if (!stillEnabling(epoch, owned)) return@withContext null
            val launchId = currentRuntimeLaunchId() ?: return@withContext null
            Lease(
                token = newToken(random),
                sessionId = sessionIdFactory(),
                expiresAtElapsed = clock.elapsedRealtimeMs() + BrowserLimits.LEASE_MS,
                expiresAtWall = clock.wallEpochMs() + BrowserLimits.LEASE_MS,
                generation = generation + 1,
                runtimeLaunchId = launchId,
                resourceGeneration = owned,
            ).also { lease = it }
        } ?: return false
        val published = try {
            publishCredential(issued)
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        withContext(mainContext) {
            if (!published || lease !== issued || !sameEnable(owned)) {
                if (lease?.generation == issued.generation) revoke(BrowserPhase.DISABLED, sideEffectUnknown = false)
                publish()
                false
            } else {
                generation = issued.generation
                seenIds = linkedSetOf()
                busyId = null
                publish(BrowserPhase.ENABLED, expiresAt = issued.expiresAtWall)
                armLeaseWatch(issued)
                true
            }
        }.also { committed ->
            if (!committed && resourceGeneration == owned) {
                runCatching { listener?.stop() }
                boundEndpoint = null
                runCatching { credentials.delete() }
            }
        }
    }

    override suspend fun takeOver() = withContext(mainContext) { takeOverFromUi() }

    override fun takeOverFromUi() {
        revoke(BrowserPhase.TAKEN_OVER, sideEffectUnknown = activePending?.dispatched == true &&
            BrowserLimits.hasSideEffect(activePending?.request?.method ?: BrowserMethod.SNAPSHOT))
        publish()
        scheduleClose()
    }

    override fun revokeRuntimeLaunch(launchId: Long) {
        val active = lease ?: return
        if (active.runtimeLaunchId != launchId) return
        revoke(BrowserPhase.DISABLED, sideEffectUnknown = false)
        publish()
        scheduleClose()
    }

    override suspend fun disable() = withContext(mainContext) {
        revoke(BrowserPhase.DISABLED, sideEffectUnknown = false)
        publish()
        scheduleClose()
    }

    fun attachListener(listener: BrowserListener) {
        check(this.listener == null) { "listener already attached" }
        this.listener = listener
    }

    suspend fun submit(request: BrowserRequest, sessionId: String): BrowserReply = withContext(mainContext) {
        val pending = admit(request, sessionId) ?: return@withContext revokedReply()
        pending.result.await()
    }

    fun tokenMatches(presented: String): Boolean = constantTimeEquals(presented, lease?.token)

    fun currentSessionId(): String? = lease?.sessionId

    internal fun bindEndpoint(endpoint: String?) {
        boundEndpoint = endpoint
    }

    private fun canEnable(): Boolean =
        listener != null && driver != null && stateFlow.value.visible && !viewInvalidated &&
            currentRuntimeLaunchId() != null

    private fun stillEnabling(epoch: Long, owned: Long): Boolean =
        generation == epoch && sameEnable(owned)

    private fun sameEnable(owned: Long): Boolean = resourceGeneration == owned && canEnable()

    private fun admit(request: BrowserRequest, sessionId: String): Pending? {
        val now = clock.elapsedRealtimeMs()
        val active = lease
        if (active == null || active.sessionId != sessionId || now >= active.expiresAtElapsed ||
            currentRuntimeLaunchId() != active.runtimeLaunchId
        ) {
            if (active != null) revoke(BrowserPhase.DISABLED, sideEffectUnknown = false)
            publish()
            return null
        }
        if (!stateFlow.value.visible || driver == null || viewInvalidated) return null
        if (!request.id.matches(ID_PATTERN) || request.id in seenIds || request.id == busyId) return null
        if (seenIds.size >= BrowserLimits.MAX_SEEN_IDS || busyId != null) return null
        if (navigationDecision(request) is UrlDecision.Deny) return null
        val deadline = now + BrowserLimits.timeoutFor(request.method, request.timeoutMs)
        if (deadline <= now) return null
        val pending = Pending(
            request = request,
            generation = active.generation,
            deadlineElapsedMs = deadline,
            result = CompletableDeferred(),
        )
        busyId = request.id
        seenIds.add(request.id)
        activePending = pending
        armDeadline(pending)
        publish()
        ensureWorker()
        if (!queue.trySend(pending).isSuccess) {
            clearPending(pending)
            return null
        }
        return pending
    }

    private fun navigationDecision(request: BrowserRequest): UrlDecision? {
        if (request.method != BrowserMethod.NAVIGATE) return null
        val url = request.params["url"] ?: return UrlDecision.Deny(UrlDenyReason.NOT_PUBLIC_HTTPS)
        return policy.checkSyntax(url)
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch(mainContext) {
            for (pending in queue) dispatch(pending)
        }
    }

    private suspend fun dispatch(pending: Pending) {
        val active = lease
        if (active == null || active.generation != pending.generation ||
            clock.elapsedRealtimeMs() >= active.expiresAtElapsed ||
            currentRuntimeLaunchId() != active.runtimeLaunchId
        ) {
            if (active != null) revoke(BrowserPhase.DISABLED, sideEffectUnknown = false)
            finish(pending, revokedReply())
            return
        }
        pending.dispatched = true
        val started = pending.generation
        if (clock.elapsedRealtimeMs() >= pending.deadlineElapsedMs) {
            expire(pending)
            return
        }
        val execution: Deferred<BrowserReply?> = scope.async(mainContext) {
            try {
                driver?.execute(pending.request) ?: revokedReply()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (!pending.result.isCompleted && BrowserLimits.hasSideEffect(pending.request.method)) {
                    viewInvalidated = true
                }
                null
            } catch (_: Exception) {
                BrowserReply(ok = false, error = BrowserError(BrowserErrorCode.DRIVER, "driver failed"))
            }
        }
        pending.execution = execution
        val reply = try {
            execution.await()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (pending.result.isCompleted) return
            throw cancelled
        }
        pending.timer?.cancel()
        if (reply == null || pending.result.isCompleted) return
        if (clock.elapsedRealtimeMs() >= pending.deadlineElapsedMs) {
            expire(pending)
            return
        }
        if (generation != started || lease?.generation != started) {
            finish(pending, revokedReply())
        } else {
            finish(pending, reply)
        }
    }

    private fun finish(pending: Pending, reply: BrowserReply) {
        if (activePending === pending) activePending = null
        if (busyId == pending.request.id && generation == pending.generation) busyId = null
        if (!pending.result.isCompleted) pending.result.complete(reply)
        publish()
    }

    private fun clearPending(pending: Pending) {
        if (activePending === pending) activePending = null
        if (busyId == pending.request.id) busyId = null
        publish()
    }

    private fun revoke(phase: BrowserPhase, sideEffectUnknown: Boolean) {
        val pending = activePending
        if (sideEffectUnknown && pending?.dispatched == true && BrowserLimits.hasSideEffect(pending.request.method)) {
            viewInvalidated = true
        }
        if (pending?.dispatched == true && BrowserLimits.hasSideEffect(pending.request.method)) {
            viewInvalidated = true
        }
        generation += 1
        resourceGeneration += 1
        lease = null
        busyId = null
        seenIds = linkedSetOf()
        activePending = null
        pending?.timer?.cancel()
        pending?.result?.complete(
            BrowserReply(
                ok = false,
                error = BrowserError(
                    if (pending.dispatched && BrowserLimits.hasSideEffect(pending.request.method)) {
                        BrowserErrorCode.OUTCOME_UNKNOWN
                    } else {
                        BrowserErrorCode.REVOKED
                    },
                    "lease revoked",
                ),
            ),
        )
        pending?.execution?.cancel()
        while (true) {
            val dropped = queue.tryReceive().getOrNull() ?: break
            if (!dropped.result.isCompleted) dropped.result.complete(revokedReply())
        }
        publish(phase = phase, expiresAt = null, busy = false)
    }

    private fun scheduleClose() {
        val owned = resourceGeneration
        scope.launch {
            if (owned != resourceGeneration || lease != null) return@launch
            resources.withLock {
                if (owned != resourceGeneration || lease != null) return@withLock
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { listener?.stop() }
                    if (owned == resourceGeneration && lease == null) {
                        boundEndpoint = null
                        credentials.delete()
                    }
                }
            }
        }
    }

    private fun armDeadline(pending: Pending) {
        pending.timer = scope.launch {
            val remaining = pending.deadlineElapsedMs - clock.elapsedRealtimeMs()
            if (remaining > 0L) delay(remaining)
            withContext(mainContext) {
                if (pending.result.isCompleted || activePending !== pending) return@withContext
                expire(pending)
            }
        }
    }

    private fun expire(pending: Pending) {
        if (pending.result.isCompleted) return
        val sideEffect = pending.dispatched && BrowserLimits.hasSideEffect(pending.request.method)
        val reply = if (sideEffect) {
            viewInvalidated = true
            BrowserReply(ok = false, error = BrowserError(BrowserErrorCode.OUTCOME_UNKNOWN, "deadline exceeded"))
        } else {
            BrowserReply(ok = false, error = BrowserError(BrowserErrorCode.TIMEOUT, "deadline exceeded"))
        }
        finish(pending, reply)
        pending.execution?.cancel()
        if (sideEffect) {
            revoke(BrowserPhase.DISABLED, sideEffectUnknown = true)
            scheduleClose()
        }
    }

    private fun armLeaseWatch(issued: Lease) {
        scope.launch {
            val remaining = issued.expiresAtElapsed - clock.elapsedRealtimeMs()
            if (remaining > 0L) sleeper(remaining)
            withContext(mainContext) {
                if (lease?.generation == issued.generation && clock.elapsedRealtimeMs() >= issued.expiresAtElapsed) {
                    revoke(BrowserPhase.DISABLED, sideEffectUnknown = false)
                    publish()
                    scheduleClose()
                }
            }
        }
    }

    private suspend fun publishCredential(issued: Lease) {
        val endpoint = boundEndpoint ?: error("listener is not bound")
        credentials.publish(
            BrowserCredential(
                endpoint = endpoint,
                token = issued.token,
                sessionId = issued.sessionId,
                expiresAt = issued.expiresAtWall,
            ),
        )
    }

    private fun publish(
        phase: BrowserPhase = stateFlow.value.phase,
        visible: Boolean = stateFlow.value.visible,
        expiresAt: Long? = lease?.expiresAtWall,
        busy: Boolean = busyId != null,
    ) {
        stateFlow.value = BrowserState(
            phase = when {
                lease != null && phase == BrowserPhase.ENABLED -> BrowserPhase.ENABLED
                phase == BrowserPhase.TAKEN_OVER && lease == null -> BrowserPhase.TAKEN_OVER
                else -> BrowserPhase.DISABLED
            },
            visible = visible,
            attached = driver != null,
            busy = busy && lease != null,
            leaseExpiresAtEpochMs = lease?.expiresAtWall,
            generation = generation,
            viewInvalidated = viewInvalidated,
        )
    }

    private fun revokedReply() =
        BrowserReply(ok = false, error = BrowserError(BrowserErrorCode.REVOKED, "lease revoked"))

    private data class Lease(
        val token: String,
        val sessionId: String,
        val expiresAtElapsed: Long,
        val expiresAtWall: Long,
        val generation: Long,
        val runtimeLaunchId: Long,
        val resourceGeneration: Long,
    )

    private class Pending(
        val request: BrowserRequest,
        val generation: Long,
        val deadlineElapsedMs: Long,
        val result: CompletableDeferred<BrowserReply>,
        var dispatched: Boolean = false,
        var execution: Deferred<BrowserReply?>? = null,
        var timer: Job? = null,
    )

    companion object {
        private val ID_PATTERN = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        private val ENDPOINT_PATTERN = Regex("^http://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/v1/command$")

        internal fun newToken(random: SecureRandom): String {
            val bytes = ByteArray(BrowserLimits.TOKEN_BYTES)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        internal fun constantTimeEquals(presented: String, expected: String?): Boolean {
            val left = presented.encodeToByteArray()
            val right = (expected ?: "").encodeToByteArray()
            var diff = left.size xor right.size
            val n = maxOf(left.size, right.size, 1)
            for (i in 0 until n) {
                val a = if (i < left.size) left[i].toInt() and 0xff else 0
                val b = if (i < right.size) right[i].toInt() and 0xff else 0
                diff = diff or (a xor b)
            }
            return expected != null && diff == 0
        }
    }
}

internal interface CredentialPublisher {
    suspend fun publish(credential: BrowserCredential)
    suspend fun delete()
}

internal class FileCredentialPublisher(
    file: File,
    trustedRoot: File? = null,
) : CredentialPublisher {
    private val store = BrowserCredentialFile(file, trustedRoot)
    override suspend fun publish(credential: BrowserCredential) = store.publish(credential)
    override suspend fun delete() = store.delete()
}
