package io.andchamber.app.phone.tool

import io.andchamber.app.phone.core.PhoneAgentAction
import io.andchamber.app.phone.core.PhoneAgentRequest
import io.andchamber.app.phone.core.PhoneCallResult
import io.andchamber.app.phone.core.PhoneCapture
import io.andchamber.app.phone.core.PhoneController
import io.andchamber.app.phone.core.PhoneDelivery
import io.andchamber.app.phone.core.PhoneErrorCode
import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhonePoint
import io.andchamber.app.phone.core.PhoneState
import io.andchamber.app.phone.core.PhoneTargetPolicy
import io.andchamber.app.phone.overlay.progress.PhoneMutationDelivery
import io.andchamber.app.phone.overlay.progress.PhoneNativeCallOutcome
import io.andchamber.app.phone.overlay.progress.PhoneTaskEvent
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventError
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventHandler
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventReply
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.standing.PhoneOpenAppResult
import io.andchamber.app.phone.standing.PhoneStandingRefusal
import io.andchamber.app.phone.standing.PhoneTaskStartResult
import io.andchamber.app.phone.standing.PhoneTaskStarter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import javax.net.ServerSocketFactory

/** A loopback server owned by one local launch. The launch owner drives start and stop. */
interface PhoneToolTransport {
    /** Binds 127.0.0.1 on an ephemeral port and returns it. */
    fun start(): Int

    fun stop()

    /**
     * Read-only. True only after this transport's current launch received the plugin's one bounded
     * setup handshake (`POST /v1/ready`). It is proof that the plugin `setup`/tool transform finished
     * and reached this native launch; it is not a grant, not a permission check and not proof that
     * the port is merely alive. Defaults to false (fail-closed) and is cleared on stop/new launch.
     */
    val pluginReady: Boolean get() = false

    /**
     * Native-only. Publishes the session route for a newly granted epoch. Returns false when this
     * transport cannot publish (the default, so an unmodified transport is fail-closed). A route is
     * routing, not authentication.
     */
    fun publishGrantRoute(
        epoch: Long,
        confirmedSessionLabel: String?,
    ): Boolean = false
}

/**
 * Loopback-only tool server for the phone plugin. Binds `127.0.0.1` on an ephemeral port and accepts
 * only `POST /v1/tool`, `/v1/cancel` or `/v1/ready` with `Authorization: Bearer <token>`, no Origin,
 * and Host exactly `127.0.0.1:<bound port>`.
 *
 * The token lives only in this object and the 0600 endpoint file. It is never logged and never
 * echoed in a response.
 *
 * The server is not an authority: before any real action it re-checks that this exact launch is
 * still ready and that the controller currently has an enabled connection and an unexpired native
 * grant. `status` never claims the session route; the first other action claims it for the grant
 * epoch, and another session is refused with SESSION_BUSY.
 */
class PhoneToolServer(
    private val launchId: Long,
    private val token: String,
    private val controller: PhoneController,
    private val readyLaunch: () -> Long?,
    /** Native task-event bridge; the default fails closed and never fakes a stop acknowledgement. */
    private val taskEvents: PhoneTaskEventHandler = PhoneTaskEventHandler.Unavailable,
    /** Native standing starter; the default fails closed: no apps, no direct start, no launch. */
    private val taskStarter: PhoneTaskStarter = PhoneTaskStarter.Unavailable,
    /** Shared with the runtime binding; holds at most one native-confirmation proposal. */
    internal val pendingRequests: PhonePendingRequestStore = PhonePendingRequestStore(),
    private val bindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
    private val socketFactory: ServerSocketFactory = ServerSocketFactory.getDefault(),
    private val readTimeoutMillis: Int = 10_000,
    private val headerLimitBytes: Int = 16_384,
    internal val requestCapacity: Int = MAX_REQUESTS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : PhoneToolTransport {
    init {
        require(token.length == PhoneToolEndpoint.TOKEN_BYTES * 2) { "token must be 32 bytes" }
        require(token.all { it in '0'..'9' || it in 'a'..'f' }) { "token must be lowercase hex" }
        require(requestCapacity in 1..MAX_REQUESTS) { "request capacity" }
    }

    private var server: ServerSocket? = null
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())

    /** Set only by the current launch's exact `/v1/ready` handshake; cleared by [stop]. */
    @Volatile
    private var readyHandshake = false

    override val pluginReady: Boolean get() = readyHandshake

    /** Claims, cancel tombstones and in-flight jobs share one bounded per-launch state. */
    internal val requests = PhoneRequestTracker(requestCapacity)
    internal val router = PhoneSessionRouter()

    /**
     * Test-only seam: runs after admission (route, dedupe, frozen request) and before the controller
     * call. Production leaves it null. It exists so a regression can deterministically replace the
     * grant in that window.
     */
    internal var afterAdmissionForTest: (() -> Unit)? = null
    private val reads = java.util.concurrent.Semaphore(MAX_CONCURRENT_READS, true)

    private val json = Json { ignoreUnknownKeys = false }
    private val targetPolicy = PhoneTargetPolicy()

    val port: Int get() = server?.localPort ?: error("server is not listening")

    override fun start(): Int {
        check(server == null) { "already listening" }
        check(bindAddress.isLoopbackAddress && bindAddress.hostAddress == "127.0.0.1") {
            "refusing a non-loopback bind"
        }
        val socket = socketFactory.createServerSocket()
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(bindAddress, 0), 16)
        server = socket
        scope.launch {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                if (client.inetAddress?.hostAddress != "127.0.0.1" || !reads.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                client.soTimeout = readTimeoutMillis
                clients.add(client)
                scope.launch {
                    try {
                        handle(client)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A disconnected or malformed client must not escape this connection job.
                    } finally {
                        clients.remove(client)
                        reads.release()
                        runCatching { client.close() }
                    }
                }
            }
        }
        return socket.localPort
    }

    override fun stop() {
        // Cleared before and after teardown: a handshake racing the stop can never leave a stale
        // ready flag on a dead launch.
        readyHandshake = false
        requests.clear()
        router.clear()
        val open = synchronized(clients) { clients.toList() }
        open.forEach { runCatching { it.close() } }
        clients.removeAll(open.toSet())
        runCatching { server?.close() }
        server = null
        scope.cancel()
        readyHandshake = false
    }

    internal fun activeConnectionCount(): Int = synchronized(clients) { clients.size }

    /**
     * Native-only route publication for this launch. It never grants or changes authority; it only
     * records which session the person confirmed (or null for a manual first-action claim). A stale
     * or duplicate epoch returns false and leaves any newer route untouched.
     */
    override fun publishGrantRoute(
        epoch: Long,
        confirmedSessionLabel: String?,
    ): Boolean {
        if (readyLaunch() != launchId) return false
        val state = controller.state.value
        if (state.epoch != epoch || state.grant == null) return false
        if (confirmedSessionLabel != null && !validSessionLabel(confirmedSessionLabel)) return false
        return router.publish(epoch, confirmedSessionLabel)
    }

    private suspend fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = readTimeoutMillis
            val exchange = try {
                readExchange(socket)
            } catch (_: SocketTimeoutException) {
                writeJson(socket, PhoneToolProtocol.errorBody(PhoneToolCodes.BAD_REQUEST), 408)
                return
            } catch (rejected: Rejected) {
                writeJson(socket, PhoneToolProtocol.errorBody(rejected.code), rejected.status)
                return
            } catch (_: Exception) {
                writeJson(socket, PhoneToolProtocol.errorBody(PhoneToolCodes.BAD_REQUEST), 400)
                return
            }
            if (!tokenMatches(exchange.bearer)) {
                writeJson(socket, PhoneToolProtocol.errorBody(PhoneToolCodes.UNAUTHORIZED), 401)
                return
            }
            when (exchange.path) {
                PhoneToolEndpoint.TOOL_PATH -> handleTool(socket, exchange)
                PhoneToolEndpoint.CANCEL_PATH -> handleCancel(socket, exchange)
                PhoneToolProtocol.READY_PATH -> handleReady(socket, exchange)
                else -> writeJson(socket, PhoneToolProtocol.errorBody(PhoneToolCodes.NOT_FOUND), 404)
            }
        }
    }

    /**
     * The plugin's bounded setup handshake. It marks this transport ready only when the body is the
     * exact fixed document and this exact launch is still the current ready launch; an old token is
     * already refused by the Bearer check above, and a stale launch is refused here without touching
     * the flag. Nothing about authority, the grant, a session or a task is read or changed.
     */
    private fun handleReady(
        socket: Socket,
        exchange: Exchange,
    ) {
        if (server == null || readyLaunch() != launchId) {
            writeJson(socket, PhoneToolProtocol.errorBody(PhoneToolCodes.RUNTIME_NOT_READY), 409)
            return
        }
        if (!PhoneToolProtocol.isReadyBody(exchange.body)) {
            writeJson(socket, PhoneToolProtocol.errorBody(PhoneToolCodes.INVALID_REQUEST), 400)
            return
        }
        readyHandshake = true
        writeJson(socket, PhoneToolProtocol.readyAck(), 200)
    }

    private suspend fun handleTool(
        socket: Socket,
        exchange: Exchange,
    ) {
        val call = PhoneToolProtocol.parseBody(exchange.body)
        if (call == null) {
            writeJson(socket, PhoneToolProtocol.errorBody(PhoneToolCodes.INVALID_REQUEST), 400)
            return
        }
        val action = "phone." + call.action
        val state = controller.state.value
        if (call.action !in OBSERVATION_FREE_ACTIONS && call.input["observationId"] == null) {
            writeJson(
                socket,
                PhoneToolProtocol.envelope(
                    action,
                    ok = false,
                    epoch = state.epoch,
                    code = PhoneToolCodes.OBSERVATION_REQUIRED,
                ),
                200,
            )
            return
        }
        val parsed = PhoneToolProtocol.parseAction(call)
        if (parsed == null) {
            writeJson(
                socket,
                PhoneToolProtocol.envelope(
                    action,
                    ok = false,
                    epoch = state.epoch,
                    code = PhoneToolCodes.INVALID_REQUEST,
                ),
                400,
            )
            return
        }
        if (parsed is PhoneToolAction.Status) {
            writeJson(socket, PhoneToolProtocol.envelope(action, true, state.epoch, statusData(state)), 200)
            return
        }
        if (parsed is PhoneToolAction.Apps) {
            writeJson(socket, standingApps(action, state), 200)
            return
        }
        if (parsed is PhoneToolAction.OpenApp) {
            writeJson(socket, openStandingApp(action, parsed, call, state), 200)
            return
        }
        if (parsed is PhoneToolAction.Request) {
            writeJson(socket, startStandingTask(action, parsed, call, state), 200)
            return
        }
        if (parsed is PhoneToolAction.Progress ||
            parsed is PhoneToolAction.NeedUser ||
            parsed is PhoneToolAction.Finish
        ) {
            writeJson(socket, handleTaskEvent(action, parsed, call, state), 200)
            return
        }

        authorityRefusal(state)?.let { code ->
            writeJson(
                socket,
                PhoneToolProtocol.envelope(action, ok = false, epoch = state.epoch, code = code),
                200,
            )
            return
        }

        // Freeze the observation at admission. The controller re-checks this exact binding before it
        // dispatches, so a newer snapshot is never silently substituted for the one the model saw.
        val observation = observationBinding(state, parsed)
        if (parsed !is PhoneToolAction.Snapshot && observation == null) {
            writeJson(
                socket,
                PhoneToolProtocol.envelope(
                    action,
                    ok = false,
                    epoch = state.epoch,
                    code = PhoneToolCodes.STALE_OBSERVATION,
                ),
                200,
            )
            return
        }

        // Native task identity at admission. It is read once and used for both the route-claim
        // notification and the completed-call callback; a null key means no native progress exists.
        val nativeTaskKey = taskEvents.currentTask(state.epoch)
        // Routing is a label, not authentication and not a grant. It stops two chats from fighting
        // over one native grant; it can never create or extend authority. A new grant epoch has no
        // route until native code publishes one, so a first action that races the grant is refused.
        val sessionLabel = call.sessionId
        val routeClaim = router.claim(state.epoch, sessionLabel)
        when (routeClaim) {
            PhoneSessionRouter.Claim.ROUTE_NOT_PUBLISHED -> {
                writeJson(
                    socket,
                    PhoneToolProtocol.envelope(
                        action,
                        ok = false,
                        epoch = state.epoch,
                        code = PhoneToolCodes.ROUTE_NOT_PUBLISHED,
                    ),
                    200,
                )
                return
            }
            PhoneSessionRouter.Claim.SESSION_BUSY -> {
                writeJson(
                    socket,
                    PhoneToolProtocol.envelope(
                        action,
                        ok = false,
                        epoch = state.epoch,
                        code = PhoneToolCodes.SESSION_BUSY,
                    ),
                    200,
                )
                return
            }
            PhoneSessionRouter.Claim.CLAIMED,
            PhoneSessionRouter.Claim.CLAIMED_MANUAL,
            -> Unit
        }
        if (routeClaim == PhoneSessionRouter.Claim.CLAIMED_MANUAL && nativeTaskKey != null) {
            // The router just verified the first action of a native-published manual route. Bind
            // that exact V2 session synchronously; never use a model phase text as the owner.
            runCatching {
                taskEvents.onRouteClaimed(nativeTaskKey, state.epoch, sessionLabel)
            }
        }

        val requestId = PhoneToolProtocol.requestId(call.callId)
        // No epoch in the key: a call id handled or cancelled under an older grant is never replayed
        // under a newer one, and a late cancel can only match that same record, not a new grant's
        // request. A new request after a regrant uses a new call id.
        val key = PhoneToolRequestKey(sessionId = sessionLabel, requestId = requestId)
        when (val claim = requests.claim(key)) {
            PhoneRequestTracker.Claim.CANCELLED -> {
                writeJson(socket, cancelled(action, state.epoch), 200)
                return
            }
            PhoneRequestTracker.Claim.DUPLICATE -> {
                writeJson(socket, duplicate(action, state.epoch), 200)
                return
            }
            PhoneRequestTracker.Claim.CAPACITY -> {
                writeJson(socket, capacity(action, state.epoch), 200)
                return
            }
            PhoneRequestTracker.Claim.CLAIMED -> Unit
        }

        val job = currentCoroutineContext()[Job]
        if (job == null) {
            writeJson(
                socket,
                PhoneToolProtocol.envelope(
                    action,
                    ok = false,
                    epoch = state.epoch,
                    code = PhoneToolCodes.OUTCOME_UNKNOWN,
                ),
                200,
            )
            return
        }
        // A cancel that raced the claim is recorded before this registration and wins.
        if (requests.register(key, job) != PhoneRequestTracker.Registration.REGISTERED) {
            writeJson(socket, cancelled(action, state.epoch), 200)
            return
        }
        // Freeze the admitted epoch and binding. The controller must refuse this request if a
        // regrant happened after admission; it must never be retargeted at the newest epoch.
        val request = PhoneAgentRequest(
            requestId = requestId,
            epoch = state.epoch,
            sessionLabel = sessionLabel,
            action = agentAction(parsed),
            observation = observation,
        )
        afterAdmissionForTest?.invoke()
        val result = try {
            execute(action, request, nativeTaskKey)
        } finally {
            requests.complete(key, job)
        }
        writeJson(socket, result, 200)
    }

    /**
     * Handles a model progress/need_user/finish event. The request epoch is explicit and frozen at
     * admission; a regrant after that must be refused by the handler against this same epoch, not
     * silently adopted. The native handler is the only place that can halt/revoke and acknowledge.
     */
    private suspend fun handleTaskEvent(
        action: String,
        parsed: PhoneToolAction,
        call: PhoneToolCall,
        state: PhoneState,
    ): String {
        val epoch = when (parsed) {
            is PhoneToolAction.Progress -> parsed.epoch
            is PhoneToolAction.NeedUser -> parsed.epoch
            is PhoneToolAction.Finish -> parsed.epoch
            else -> return failure(action, state.epoch, PhoneToolCodes.INVALID_REQUEST)
        }
        if (!taskEvents.available) {
            return failure(action, epoch, PhoneToolCodes.TASK_EVENTS_UNAVAILABLE)
        }
        if (epoch != state.epoch) {
            return failure(action, epoch, PhoneToolCodes.EPOCH_CHANGED)
        }
        authorityRefusal(state)?.let { return failure(action, epoch, it) }
        // Task owner/key is fixed for this epoch. It comes from native state, never from the model.
        // Resolve it before route claim so a no-task call never takes a session route.
        val key = taskEvents.currentTask(epoch)
            ?: return failure(action, epoch, PhoneToolCodes.NO_TASK)
        val routeClaim = router.claim(epoch, call.sessionId)
        when (routeClaim) {
            PhoneSessionRouter.Claim.ROUTE_NOT_PUBLISHED ->
                return failure(action, epoch, PhoneToolCodes.ROUTE_NOT_PUBLISHED)
            PhoneSessionRouter.Claim.SESSION_BUSY ->
                return failure(action, epoch, PhoneToolCodes.SESSION_BUSY)
            PhoneSessionRouter.Claim.CLAIMED,
            PhoneSessionRouter.Claim.CLAIMED_MANUAL,
            -> Unit
        }
        if (routeClaim == PhoneSessionRouter.Claim.CLAIMED_MANUAL) {
            // First action of a native-published manual route. Bind the router-verified V2 session
            // with the frozen epoch; a model phase/message can never select this owner.
            runCatching {
                taskEvents.onRouteClaimed(key, epoch, call.sessionId)
            }
        }

        val requestId = PhoneToolProtocol.requestId(call.callId)
        val keyReq = PhoneToolRequestKey(sessionId = call.sessionId, requestId = requestId)
        when (requests.claim(keyReq)) {
            PhoneRequestTracker.Claim.CANCELLED -> return cancelled(action, epoch)
            PhoneRequestTracker.Claim.DUPLICATE -> return duplicate(action, epoch)
            PhoneRequestTracker.Claim.CAPACITY -> return capacity(action, epoch)
            PhoneRequestTracker.Claim.CLAIMED -> Unit
        }
        val job = currentCoroutineContext()[Job]
            ?: return failure(action, epoch, PhoneToolCodes.OUTCOME_UNKNOWN)
        if (requests.register(keyReq, job) != PhoneRequestTracker.Registration.REGISTERED) {
            return cancelled(action, epoch)
        }
        val event = when (parsed) {
            is PhoneToolAction.Progress -> PhoneTaskEvent.Progress(requestId, parsed.phase)
            is PhoneToolAction.NeedUser -> PhoneTaskEvent.NeedUser(requestId, parsed.reason)
            is PhoneToolAction.Finish -> PhoneTaskEvent.Finish(requestId, parsed.summary)
            else -> return failure(action, epoch, PhoneToolCodes.INVALID_REQUEST)
        }
        afterAdmissionForTest?.invoke()
        val reply = try {
            withTimeout(EVENT_HANDLE_TIMEOUT_MS) {
                taskEvents.handle(key, epoch, call.sessionId, event)
            }
        } catch (_: TimeoutCancellationException) {
            // A timeout is not a stop acknowledgement. waiting/done must not be claimed.
            return if (parsed is PhoneToolAction.Progress) {
                failure(action, epoch, PhoneToolCodes.DEADLINE)
            } else {
                failure(action, epoch, PhoneToolCodes.STOP_UNCONFIRMED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return if (parsed is PhoneToolAction.Progress) {
                failure(action, epoch, PhoneToolCodes.TASK_EVENTS_UNAVAILABLE)
            } else {
                failure(action, epoch, PhoneToolCodes.STOP_UNCONFIRMED)
            }
        } finally {
            requests.complete(keyReq, job)
        }
        return encodeTaskReply(action, epoch, reply)
    }

    private fun encodeTaskReply(
        action: String,
        epoch: Long,
        reply: PhoneTaskEventReply,
    ): String =
        when (reply) {
            is PhoneTaskEventReply.Recorded ->
                PhoneToolProtocol.envelope(
                    action,
                    ok = true,
                    epoch = epoch,
                    data = buildJsonObject {
                        put("state", "PROGRESS")
                        put("seq", reply.seq)
                        put("source", "MODEL")
                        put("verified", false)
                    },
                )
            is PhoneTaskEventReply.WaitingForUser ->
                PhoneToolProtocol.envelope(
                    action,
                    ok = true,
                    epoch = epoch,
                    data = buildJsonObject {
                        put("state", "WAITING_USER")
                        put("seq", reply.seq)
                        put("verified", false)
                        put("sentActionsMayStillApply", reply.stop.sentActionsMayStillApply)
                    },
                )
            is PhoneTaskEventReply.EndedPendingCheck ->
                PhoneToolProtocol.envelope(
                    action,
                    ok = true,
                    epoch = epoch,
                    data = buildJsonObject {
                        put("state", "ENDED_PENDING_CHECK")
                        put("seq", reply.seq)
                        put("verified", false)
                        put("sentActionsMayStillApply", reply.stop.sentActionsMayStillApply)
                    },
                )
            is PhoneTaskEventReply.HaltUnconfirmed ->
                failure(action, epoch, PhoneToolCodes.STOP_UNCONFIRMED)
            is PhoneTaskEventReply.Refused ->
                failure(action, epoch, taskErrorCode(reply.code))
        }

    private fun taskErrorCode(code: PhoneTaskEventError): String =
        when (code) {
            PhoneTaskEventError.UNAVAILABLE -> PhoneToolCodes.TASK_EVENTS_UNAVAILABLE
            PhoneTaskEventError.NO_TASK -> PhoneToolCodes.NO_TASK
            PhoneTaskEventError.STALE_TASK -> PhoneToolCodes.STALE_TASK
            PhoneTaskEventError.EPOCH_CHANGED -> PhoneToolCodes.EPOCH_CHANGED
            PhoneTaskEventError.SESSION_MISMATCH -> PhoneToolCodes.SESSION_BUSY
            PhoneTaskEventError.DUPLICATE -> PhoneToolCodes.DUPLICATE_CALL
            PhoneTaskEventError.CAPACITY -> PhoneToolCodes.CAPACITY
            PhoneTaskEventError.INVALID -> PhoneToolCodes.INVALID_REQUEST
            PhoneTaskEventError.TASK_NOT_ACTIVE -> PhoneToolCodes.TASK_NOT_ACTIVE
        }

    /** Read-only: only the person-preauthorized apps; never the installed-app catalogue. */
    private fun standingApps(
        action: String,
        state: PhoneState,
    ): String {
        if (readyLaunch() != launchId) return failure(action, state.epoch, PhoneToolCodes.RUNTIME_NOT_READY)
        val standing = taskStarter.takeIf { it.available }?.standing()
            ?: return failure(action, state.epoch, PhoneToolCodes.STANDING_UNAVAILABLE)
        val data =
            buildJsonObject {
                put(
                    "apps",
                    JsonArray(
                        standing.apps.map { app ->
                            buildJsonObject {
                                put("label", app.label)
                                put("packageName", app.packageName)
                            }
                        },
                    ),
                )
                put("actions", JsonArray(standing.actions.sortedBy { it.name }.map { JsonPrimitive(it.name) }))
            }
        return PhoneToolProtocol.envelope(action, true, state.epoch, data)
    }

    /**
     * Direct start inside the standing scope. The native starter creates the per-task grant and
     * launches the allowed app; this server only forwards the verified session + bounded summary and
     * maps the typed native result. There is no PENDING_CONFIRMATION reply.
     */
    private suspend fun startStandingTask(
        action: String,
        request: PhoneToolAction.Request,
        call: PhoneToolCall,
        state: PhoneState,
    ): String {
        if (readyLaunch() != launchId) return failure(action, state.epoch, PhoneToolCodes.RUNTIME_NOT_READY)
        val standing = taskStarter.takeIf { it.available }?.standing()
            ?: return failure(action, state.epoch, PhoneToolCodes.STANDING_UNAVAILABLE)
        if (standing.apps.none { it.packageName == request.packageName }) {
            return failure(action, state.epoch, PhoneToolCodes.TARGET_FORBIDDEN)
        }
        return when (val result = taskStarter.request(call.sessionId, request.summary, request.packageName)) {
            is PhoneTaskStartResult.Started -> {
                // A live lease was created natively. If this request may not use it, the exact new
                // epoch is rolled back instead of leaving an unreachable task running (and instead
                // of answering success with an epoch nobody can act on).
                if (result.epoch < 1L || result.epoch <= state.epoch) {
                    // A preauthorized request must create a fresh task epoch; never ride a stale one.
                    abortStarted(result.epoch, result.key)
                    return failure(action, state.epoch, PhoneToolCodes.EPOCH_CHANGED)
                }
                // Exactly one publish. A false result means this epoch has no route (an older or
                // unrelated route was already published): the chat would get a task it can never
                // use, so roll the exact epoch back and report a failure.
                if (!router.publish(result.epoch, call.sessionId)) {
                    abortStarted(result.epoch, result.key)
                    return failure(action, result.epoch, PhoneToolCodes.ROUTE_NOT_PUBLISHED)
                }
                val data =
                    buildJsonObject {
                        put("state", result.state.name)
                        result.message
                            ?.takeIf { it.isNotBlank() }
                            ?.let { put("message", it.take(PhoneToolProtocol.MAX_EVENT_TEXT_CHARS)) }
                    }
                PhoneToolProtocol.envelope(action, true, result.epoch, data)
            }
            is PhoneTaskStartResult.Refused ->
                failure(action, state.epoch, standingCode(result.refusal))
        }
    }

    /**
     * Rolls back exactly the task this adapter created and failed to keep. It is bounded to one
     * call, never touches an older/newer epoch, and never turns the failure reply into a success:
     * a native abort failure is swallowed because the caller must still receive a failure (there is
     * no verified success to report either way).
     */
    private suspend fun abortStarted(
        epoch: Long,
        key: PhoneTaskKey,
    ) {
        try {
            taskStarter.abortStarted(epoch, key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Nothing to report: the reply is already a failure, and a claim of success is banned.
        }
    }

    /** Open exactly one package from the preauthorized list; the native starter checks and launches. */
    private suspend fun openStandingApp(
        action: String,
        request: PhoneToolAction.OpenApp,
        call: PhoneToolCall,
        state: PhoneState,
    ): String {
        if (readyLaunch() != launchId) return failure(action, state.epoch, PhoneToolCodes.RUNTIME_NOT_READY)
        val standing = taskStarter.takeIf { it.available }?.standing()
            ?: return failure(action, state.epoch, PhoneToolCodes.STANDING_UNAVAILABLE)
        if (standing.apps.none { it.packageName == request.packageName }) {
            return failure(action, state.epoch, PhoneToolCodes.TARGET_FORBIDDEN)
        }
        return when (val result = taskStarter.openApp(call.sessionId, request.packageName)) {
            PhoneOpenAppResult.Opened ->
                PhoneToolProtocol.envelope(action, true, state.epoch, buildJsonObject { put("state", "OPENED") })
            is PhoneOpenAppResult.Refused ->
                failure(action, state.epoch, standingCode(result.refusal))
        }
    }

    private fun standingCode(refusal: PhoneStandingRefusal): String =
        when (refusal) {
            PhoneStandingRefusal.NOT_CONFIGURED -> PhoneToolCodes.STANDING_UNAVAILABLE
            PhoneStandingRefusal.INVALID -> PhoneToolCodes.INVALID_REQUEST
            PhoneStandingRefusal.EXPIRED -> PhoneToolCodes.STANDING_EXPIRED
            PhoneStandingRefusal.DISABLED -> PhoneToolCodes.DISABLED
            PhoneStandingRefusal.NOT_CONNECTED -> PhoneToolCodes.NOT_CONNECTED
            PhoneStandingRefusal.RUNTIME_CHANGED,
            PhoneStandingRefusal.STANDING_BLOCKED,
            -> PhoneToolCodes.STANDING_BLOCKED
            PhoneStandingRefusal.PAUSED -> PhoneToolCodes.PAUSED
            PhoneStandingRefusal.UNKNOWN_OUTCOME -> PhoneToolCodes.OUTCOME_UNKNOWN
            PhoneStandingRefusal.TARGET_NOT_ALLOWED -> PhoneToolCodes.TARGET_FORBIDDEN
            PhoneStandingRefusal.ACTION_NOT_ALLOWED -> PhoneToolCodes.ACTION_NOT_GRANTED
            PhoneStandingRefusal.SESSION_MISMATCH -> PhoneToolCodes.SESSION_BUSY
            PhoneStandingRefusal.TASK_ACTIVE -> PhoneToolCodes.BUSY
            PhoneStandingRefusal.START_FAILED -> PhoneToolCodes.START_FAILED
        }

    private fun failure(
        action: String,
        epoch: Long,
        code: String,
    ): String = PhoneToolProtocol.envelope(action, ok = false, epoch = epoch, code = code)

    private fun authorityRefusal(state: PhoneState): String? {
        if (readyLaunch() != launchId) return PhoneToolCodes.RUNTIME_NOT_READY
        if (!state.enabled) return PhoneToolCodes.DISABLED
        if (state.connection != io.andchamber.app.phone.core.PhoneConnectionState.CONNECTED) {
            return PhoneToolCodes.NOT_CONNECTED
        }
        val grant = state.grant ?: return PhoneToolCodes.NOT_GRANTED
        if (controller.nowMs() >= grant.expiresAtMs) return PhoneToolCodes.LEASE_EXPIRED
        return null
    }

    /**
     * Executes exactly the request admitted under [request]'s frozen epoch and binding. It never
     * re-reads the controller to pick a newer epoch: a regrant after admission must be refused with
     * EPOCH_CHANGED by the controller, not executed against the new grant or its unapproved screen.
     */
    private suspend fun execute(
        action: String,
        request: PhoneAgentRequest,
        nativeTaskKey: PhoneTaskKey?,
    ): String {
        val result = try {
            withTimeout(PhoneLimits.MAX_CALL_MS + SAFETY_MARGIN_MS) {
                // Window witnesses plus focus/text revalidation need the existing bounded maximum.
                // The controller still caps this by the live lease and stop/admission guards.
                controller.execute(request, PhoneLimits.MAX_CALL_MS)
            }
        } catch (_: TimeoutCancellationException) {
            return PhoneToolProtocol.envelope(
                action,
                ok = false,
                epoch = request.epoch,
                code = PhoneToolCodes.DEADLINE,
            )
        } catch (cancelled: CancellationException) {
            // The plugin aborted or the launch is stopping; there is no client left to answer.
            throw cancelled
        }
        if (nativeTaskKey != null) {
            // Native fact only: no observation/image/input payload leaves this call. The handler
            // writes it to the store using the admitted key/epoch; stale keys/epochs are rejected.
            runCatching {
                taskEvents.onNativeCallCompleted(
                    key = nativeTaskKey,
                    admittedEpoch = request.epoch,
                    requestId = request.requestId,
                    outcome = nativeCallOutcome(result),
                )
            }
        }
        return encodeResult(action, result)
    }

    private fun nativeCallOutcome(result: PhoneCallResult): PhoneNativeCallOutcome =
        when (result) {
            is PhoneCallResult.Observed -> PhoneNativeCallOutcome.Observed(captured = false)
            is PhoneCallResult.Captured -> PhoneNativeCallOutcome.Observed(captured = true)
            is PhoneCallResult.Dispatched ->
                PhoneNativeCallOutcome.Mutation(
                    when (result.delivery) {
                        PhoneDelivery.SENT -> PhoneMutationDelivery.SENT
                        PhoneDelivery.UNKNOWN -> PhoneMutationDelivery.UNKNOWN
                        PhoneDelivery.NOT_SENT -> PhoneMutationDelivery.NOT_SENT
                    },
                )
            is PhoneCallResult.Refused -> PhoneNativeCallOutcome.Refused
        }

    private fun observationBinding(
        state: PhoneState,
        action: PhoneToolAction,
    ): PhoneObservationBinding? {
        val id = when (action) {
            PhoneToolAction.Status,
            PhoneToolAction.Snapshot,
            PhoneToolAction.Apps,
            is PhoneToolAction.Request,
            is PhoneToolAction.OpenApp,
            is PhoneToolAction.Progress,
            is PhoneToolAction.NeedUser,
            is PhoneToolAction.Finish -> return null
            is PhoneToolAction.Capture -> action.observationId
            is PhoneToolAction.TapRef -> action.observationId
            is PhoneToolAction.TapPoint -> action.observationId
            is PhoneToolAction.Swipe -> action.observationId
            is PhoneToolAction.Input -> action.observationId
            is PhoneToolAction.Back -> action.observationId
        }
        val current = state.observation?.binding ?: return null
        return current.takeIf { it.observationId == id }
    }

    private fun agentAction(parsed: PhoneToolAction): PhoneAgentAction =
        when (parsed) {
            PhoneToolAction.Status,
            PhoneToolAction.Snapshot,
            PhoneToolAction.Apps,
            is PhoneToolAction.Request,
            is PhoneToolAction.OpenApp -> PhoneAgentAction.Observe
            is PhoneToolAction.Capture -> PhoneAgentAction.Capture
            is PhoneToolAction.TapRef -> PhoneAgentAction.TapRef(parsed.ref)
            is PhoneToolAction.TapPoint -> PhoneAgentAction.TapPoint(PhonePoint(parsed.x, parsed.y))
            is PhoneToolAction.Swipe ->
                PhoneAgentAction.Swipe(
                    from = PhonePoint(parsed.fromX, parsed.fromY),
                    to = PhonePoint(parsed.toX, parsed.toY),
                    durationMs = parsed.durationMs,
                )
            is PhoneToolAction.Input -> PhoneAgentAction.Input(parsed.text, parsed.ref)
            is PhoneToolAction.Back -> PhoneAgentAction.Back
            is PhoneToolAction.Progress,
            is PhoneToolAction.NeedUser,
            is PhoneToolAction.Finish -> error("task events are not driver actions")
        }

    private fun encodeResult(
        action: String,
        result: PhoneCallResult,
    ): String =
        when (result) {
            is PhoneCallResult.Observed -> PhoneToolProtocol.envelope(
                action,
                ok = true,
                epoch = result.epoch,
                data = snapshotData(result.observation),
            )
            is PhoneCallResult.Captured -> PhoneToolProtocol.envelope(
                action,
                ok = true,
                epoch = result.epoch,
                data = captureData(result.capture),
            )
            is PhoneCallResult.Dispatched -> {
                if (result.delivery == PhoneDelivery.SENT && result.error == null) {
                    PhoneToolProtocol.envelope(
                        action,
                        ok = true,
                        epoch = result.epoch,
                        data = buildJsonObject {
                            put("sent", true)
                            put("deviceAcknowledged", true)
                            put("action", result.business?.action?.name?.lowercase() ?: "")
                        },
                    )
                } else {
                    PhoneToolProtocol.envelope(
                        action,
                        ok = false,
                        epoch = result.epoch,
                        code = (result.error ?: PhoneErrorCode.OUTCOME_UNKNOWN).name,
                    )
                }
            }
            is PhoneCallResult.Refused -> PhoneToolProtocol.envelope(
                action,
                ok = false,
                epoch = result.epoch,
                code = result.error.name,
            )
        }

    private fun snapshotData(observation: io.andchamber.app.phone.core.PhoneObservation): JsonObject {
        val binding = observation.binding
        return buildJsonObject {
            put("observationId", binding.observationId)
            put("generation", binding.generation)
            put("packageName", binding.packageName)
            put("rotation", binding.rotation)
            put("displayWidth", binding.geometry.displayWidth)
            put("displayHeight", binding.geometry.displayHeight)
            put(
                "nodes",
                JsonArray(
                    observation.nodes.map { node ->
                        buildJsonObject {
                            put("ref", node.ref)
                            put("role", node.role)
                            put("label", node.label)
                            put("clickable", node.clickable)
                            put("editable", node.editable)
                            put("focused", node.focused)
                            put(
                                "bounds",
                                buildJsonObject {
                                    put("left", node.bounds.left)
                                    put("top", node.bounds.top)
                                    put("right", node.bounds.right)
                                    put("bottom", node.bounds.bottom)
                                },
                            )
                        }
                    },
                ),
            )
        }
    }

    private fun captureData(capture: PhoneCapture): JsonObject =
        buildJsonObject {
            put("observationId", capture.binding.observationId)
            put("generation", capture.binding.generation)
            put("mime", capture.image.mimeType)
            put("width", capture.image.width)
            put("height", capture.image.height)
            put("base64", Base64.getEncoder().encodeToString(capture.image.bytes))
        }

    /** Minimal, redacted status: no package list, window text, labels or token. */
    private fun statusData(state: PhoneState): JsonObject {
        val grant = state.grant
        val capabilities = state.capabilities
        val standing = taskStarter.takeIf { it.available }?.standing()
        return buildJsonObject {
            put("enabled", state.enabled)
            put("phase", state.phase.name)
            put("paused", state.paused)
            put("pausing", state.pausing)
            put("blocked", state.blocked?.name)
            put("connected", state.connection == io.andchamber.app.phone.core.PhoneConnectionState.CONNECTED)
            put("inFlight", state.inFlightMutation)
            put("leaseRemainingMs", grant?.let { (it.expiresAtMs - controller.nowMs()).coerceAtLeast(0L) })
            put("grantedActions", JsonArray(grant?.allowedActions.orEmpty().sortedBy { it.name }.map { JsonPrimitive(it.name) }))
            put("observationAvailable", state.observation != null)
            put("pendingConfirmation", pendingRequests.snapshot() != null)
            // Minimal: only whether a native task exists. Never its summary, target, key or events.
            put("taskPresent", taskEvents.available && taskEvents.currentTask(state.epoch) != null)
            // Native facts, separate from runtimeReady (the loopback endpoint's launch). This does not
            // claim that the plugin was loaded; it only says which native bridges are installed.
            put("nativeTaskEvents", taskEvents.available)
            put("standingConfigured", standing != null)
            put("standingBlocked", state.standingBlocked)
            put("standingBlockReason", state.standingBlockReason?.name)
            put("runtimeReady", readyLaunch() == launchId)
            put("runtimeEpoch", PhoneToolProtocol.serverEpoch(launchId))
            put("capabilities", capabilities?.let { caps ->
                buildJsonObject {
                    put("observe", caps.observe)
                    put("capture", caps.capture)
                    put("tap", caps.tap)
                    put("swipe", caps.swipe)
                    put("input", caps.input)
                    put("unicodeInput", caps.unicodeInput)
                    put("back", caps.back)
                }
            } ?: JsonNull)
        }
    }

    private fun handleCancel(
        socket: Socket,
        exchange: Exchange,
    ) {
        val parsed = runCatching { json.parseToJsonElement(exchange.body).jsonObject }.getOrNull()
        val version = parsed?.get("version")?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
        val sessionId = parsed?.get("sessionID")?.let { runCatching { it.jsonPrimitive }.getOrNull() }
            ?.takeIf { it.isString }?.content?.takeIf {
                it.length in 1..PhoneToolProtocol.MAX_SESSION_CHARS && it.none(Char::isISOControl)
            }
        val callId = parsed?.get("callID")?.let { runCatching { it.jsonPrimitive }.getOrNull() }
            ?.takeIf { it.isString }?.content?.takeIf { it.length in 1..PhoneToolProtocol.MAX_CALL_ID_CHARS }
        if (version != PhoneToolProtocol.VERSION || sessionId == null || callId == null) {
            val epoch = controller.state.value.epoch
            writeJson(socket, PhoneToolProtocol.errorBody(PhoneToolCodes.INVALID_REQUEST, epoch = epoch), 400)
            return
        }
        // Matched only by (session, request): a cancel for another session must never cancel this
        // session's same call id. Nothing here touches authority or the grant.
        val key = PhoneToolRequestKey(sessionId, PhoneToolProtocol.requestId(callId))
        when (val outcome = requests.cancel(key)) {
            PhoneRequestTracker.Cancel.CAPACITY -> {
                val epoch = controller.state.value.epoch
                writeJson(socket, PhoneToolProtocol.errorBody(PhoneToolCodes.CAPACITY, epoch = epoch), 429)
            }
            PhoneRequestTracker.Cancel.MATCHED, PhoneRequestTracker.Cancel.RECORDED -> {
                writeJson(
                    socket,
                    buildJsonObject {
                        put("version", PhoneToolProtocol.VERSION)
                        put("ok", true)
                        put("cancelled", outcome == PhoneRequestTracker.Cancel.MATCHED)
                    }.toString(),
                    200,
                )
            }
        }
    }

    private fun cancelled(
        action: String,
        epoch: Long,
    ): String = PhoneToolProtocol.envelope(action, ok = false, epoch = epoch, code = PhoneToolCodes.CANCELLED)

    private fun duplicate(
        action: String,
        epoch: Long,
    ): String = PhoneToolProtocol.envelope(action, ok = false, epoch = epoch, code = PhoneToolCodes.DUPLICATE_CALL)

    private fun capacity(
        action: String,
        epoch: Long,
    ): String = PhoneToolProtocol.envelope(action, ok = false, epoch = epoch, code = PhoneToolCodes.CAPACITY)

    private fun readExchange(socket: Socket): Exchange {
        val headerText = readHeaders(socket.getInputStream()).toString(Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: throw Rejected(400, PhoneToolCodes.BAD_REQUEST)
        if (requestLine.size != 3) throw Rejected(404, PhoneToolCodes.NOT_FOUND)
        val method = requestLine[0]
        val path = requestLine[1]
        if (method != "POST") throw Rejected(404, PhoneToolCodes.NOT_FOUND)
        if (path != PhoneToolEndpoint.TOOL_PATH &&
            path != PhoneToolEndpoint.CANCEL_PATH &&
            path != PhoneToolProtocol.READY_PATH
        ) {
            throw Rejected(404, PhoneToolCodes.NOT_FOUND)
        }
        if (requestLine[2] != "HTTP/1.1" && requestLine[2] != "HTTP/1.0") {
            throw Rejected(400, PhoneToolCodes.BAD_REQUEST)
        }
        val headers = parseHeaders(lines.drop(1))
        // No browser redirect target and no cross-origin caller exists for this private endpoint.
        if (headers.keys.any { it.equals("origin", ignoreCase = true) }) {
            throw Rejected(403, PhoneToolCodes.FORBIDDEN)
        }
        val bound = server?.localPort ?: throw Rejected(400, PhoneToolCodes.BAD_REQUEST)
        if (headers["host"] != "127.0.0.1:$bound") throw Rejected(403, PhoneToolCodes.FORBIDDEN)
        if (headers.containsKey("transfer-encoding")) throw Rejected(413, PhoneToolCodes.TOO_LARGE)
        if (headers.containsKey("content-encoding")) throw Rejected(413, PhoneToolCodes.TOO_LARGE)
        val lengthHeader = headers["content-length"] ?: throw Rejected(413, PhoneToolCodes.TOO_LARGE)
        if (lengthHeader.length > 1 && lengthHeader.startsWith("0")) {
            throw Rejected(400, PhoneToolCodes.BAD_REQUEST)
        }
        if (lengthHeader.any { !it.isDigit() }) throw Rejected(400, PhoneToolCodes.BAD_REQUEST)
        val length = lengthHeader.toIntOrNull() ?: throw Rejected(400, PhoneToolCodes.BAD_REQUEST)
        if (length !in 1..PhoneToolProtocol.MAX_BODY_BYTES) throw Rejected(413, PhoneToolCodes.TOO_LARGE)
        val bearer = bearer(headers["authorization"]) ?: throw Rejected(401, PhoneToolCodes.UNAUTHORIZED)
        val body = readBody(socket.getInputStream(), length)
        return Exchange(path, bearer, body.toString(Charsets.UTF_8))
    }

    private fun readHeaders(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var previous = 0
        while (out.size() <= headerLimitBytes) {
            val next = input.read()
            if (next < 0) throw Rejected(400, PhoneToolCodes.BAD_REQUEST)
            out.write(next)
            if (previous == '\r'.code && next == '\n'.code && out.toByteArray().endsWith(HEADER_END)) {
                return out.toByteArray()
            }
            previous = next
        }
        throw Rejected(431, PhoneToolCodes.BAD_REQUEST)
    }

    private fun readBody(
        input: InputStream,
        length: Int,
    ): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw Rejected(400, PhoneToolCodes.BAD_REQUEST)
            read += n
        }
        return body
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        for (line in lines) {
            if (line.isEmpty()) break
            if (line[0] == ' ' || line[0] == '\t') throw Rejected(400, PhoneToolCodes.BAD_REQUEST)
            val colon = line.indexOf(':')
            if (colon <= 0) throw Rejected(400, PhoneToolCodes.BAD_REQUEST)
            val name = line.substring(0, colon).lowercase()
            if (name != name.trim()) throw Rejected(400, PhoneToolCodes.BAD_REQUEST)
            if (name in headers) throw Rejected(400, PhoneToolCodes.BAD_REQUEST)
            headers[name] = line.substring(colon + 1).trim()
        }
        return headers
    }

    private fun bearer(header: String?): String? {
        if (header == null || !header.startsWith("Bearer ")) return null
        val value = header.removePrefix("Bearer ")
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null
        return value
    }

    /** Constant-time compare; lengths still differ, but no early exit on the first wrong byte. */
    private fun tokenMatches(candidate: String): Boolean {
        val expected = token.toByteArray(Charsets.UTF_8)
        val actual = candidate.toByteArray(Charsets.UTF_8)
        if (expected.size != actual.size) return false
        return MessageDigest.isEqual(expected, actual)
    }

    private fun writeJson(
        socket: Socket,
        body: String,
        status: Int,
    ) {
        writeRaw(socket, status, body.toByteArray(Charsets.UTF_8))
    }

    private fun writeRaw(
        socket: Socket,
        status: Int,
        payload: ByteArray,
    ) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            408 -> "Request Timeout"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        out.flush()
    }

    private data class Exchange(
        val path: String,
        val bearer: String,
        val body: String,
    )

    private class Rejected(
        val status: Int,
        val code: String,
    ) : Exception()

    companion object {
        const val MAX_CONCURRENT_READS = 4
        const val MAX_REQUESTS = 512
        private const val SAFETY_MARGIN_MS = 2_000L
        private val OBSERVATION_FREE_ACTIONS =
            setOf("status", "request", "apps", "open_app", "progress", "need_user", "finish", "snapshot")

        private const val EVENT_HANDLE_TIMEOUT_MS = 15_000L

        private fun validSessionLabel(label: String): Boolean =
            label.isNotBlank() &&
                label.length <= PhoneToolProtocol.MAX_SESSION_CHARS &&
                label.none(Char::isISOControl)
    }
}

/**
 * Per-launch atomic claim, registration and cancellation state.
 *
 * Both the claim table and the cancel tombstones are bounded and never evict an old record: when a
 * table is full a new request (or tombstone) is refused instead of silently forgetting an earlier
 * decision. Records live for the whole launch and are released only by [clear].
 */
internal class PhoneRequestTracker(
    private val capacity: Int,
) {
    enum class Claim { CLAIMED, DUPLICATE, CANCELLED, CAPACITY }

    enum class Registration { REGISTERED, CANCELLED }

    enum class Cancel { MATCHED, RECORDED, CAPACITY }

    private val lock = Any()
    private val claimed = LinkedHashSet<PhoneToolRequestKey>()
    private val cancelled = LinkedHashSet<PhoneToolRequestKey>()
    private val jobs = HashMap<PhoneToolRequestKey, Job>()

    /**
     * Atomically records [key] and refuses it when its cancel tombstone already exists. A cancelled
     * key stays refused for the rest of the launch.
     */
    fun claim(key: PhoneToolRequestKey): Claim =
        synchronized(lock) {
            when {
                key in cancelled -> Claim.CANCELLED
                key in claimed -> Claim.DUPLICATE
                claimed.size >= capacity -> Claim.CAPACITY
                else -> {
                    claimed.add(key)
                    Claim.CLAIMED
                }
            }
        }

    /** Refuses registration when a cancel raced the claim; the job never runs. */
    fun register(
        key: PhoneToolRequestKey,
        job: Job,
    ): Registration =
        synchronized(lock) {
            if (key in cancelled) {
                Registration.CANCELLED
            } else {
                jobs[key] = job
                Registration.REGISTERED
            }
        }

    fun complete(
        key: PhoneToolRequestKey,
        job: Job,
    ) {
        synchronized(lock) { jobs.remove(key, job) }
    }

    /** Cancels a registered job; otherwise records a permanent tombstone, bounded by capacity. */
    fun cancel(key: PhoneToolRequestKey): Cancel {
        var matched: Job? = null
        var outcome: Cancel
        synchronized(lock) {
            val current = jobs.remove(key)
            outcome = when {
                current != null -> {
                    matched = current
                    Cancel.MATCHED
                }
                key in cancelled -> Cancel.RECORDED
                cancelled.size >= capacity -> Cancel.CAPACITY
                else -> {
                    cancelled.add(key)
                    Cancel.RECORDED
                }
            }
        }
        matched?.cancel()
        return outcome
    }

    /** Cancels every registered job but keeps claims and tombstones: the launch is still the same. */
    fun cancelAll() {
        val open = synchronized(lock) {
            jobs.values.toList().also { jobs.clear() }
        }
        open.forEach { it.cancel() }
    }

    /** Releases the whole launch state. Only the launch owner calls this. */
    fun clear() {
        val open = synchronized(lock) {
            jobs.values.toList().also {
                jobs.clear()
                claimed.clear()
                cancelled.clear()
            }
        }
        open.forEach { it.cancel() }
    }
}

/**
 * One session label per grant epoch. The label routes a request to the chat that started it; it is
 * not authentication and never creates authority. `status` does not call this.
 *
 * A new grant epoch starts with no route and refuses every real action until native code publishes
 * one. Publishing with a session label pins that session even before its first action; publishing
 * with null is the explicit manual case where the first action may claim the epoch. A proposal's
 * label is not authentication: the trusted runtime supplies labels, and this router only stops an
 * ordinary concurrent session from taking a task that was confirmed for another chat.
 */
internal class PhoneSessionRouter {
    enum class Mode { UNPUBLISHED, PINNED, MANUAL }

    enum class Claim { CLAIMED, CLAIMED_MANUAL, SESSION_BUSY, ROUTE_NOT_PUBLISHED }

    private val lock = Any()
    private var publishedEpoch: Long? = null
    private var mode: Mode = Mode.UNPUBLISHED
    private var pinnedSession: String? = null
    private var owner: String? = null

    /**
     * Native-only route publication for [epoch]. A label pins that session; null explicitly allows
     * the first real action to claim. A stale or duplicate epoch never changes an existing route:
     * only a strictly newer epoch replaces it.
     */
    fun publish(
        epoch: Long,
        sessionLabel: String?,
    ): Boolean =
        synchronized(lock) {
            val current = publishedEpoch
            if (current != null && epoch <= current) return@synchronized false
            publishedEpoch = epoch
            mode = if (sessionLabel == null) Mode.MANUAL else Mode.PINNED
            pinnedSession = sessionLabel
            owner = sessionLabel
            true
        }

    /** Returns true when this session may act, claiming a manual epoch on its first real action. */
    fun claim(
        grantEpoch: Long,
        sessionLabel: String,
    ): Claim =
        synchronized(lock) {
            if (publishedEpoch != grantEpoch || mode == Mode.UNPUBLISHED) {
                return@synchronized Claim.ROUTE_NOT_PUBLISHED
            }
            when (mode) {
                Mode.PINNED ->
                    if (sessionLabel == pinnedSession) Claim.CLAIMED else Claim.SESSION_BUSY
                Mode.MANUAL ->
                    when {
                        owner == null -> {
                            owner = sessionLabel
                            Claim.CLAIMED_MANUAL
                        }
                        owner == sessionLabel -> Claim.CLAIMED
                        else -> Claim.SESSION_BUSY
                    }
                Mode.UNPUBLISHED -> Claim.ROUTE_NOT_PUBLISHED
            }
        }

    fun currentOwner(): String? = synchronized(lock) { owner }

    fun clear() {
        synchronized(lock) {
            publishedEpoch = null
            mode = Mode.UNPUBLISHED
            pinnedSession = null
            owner = null
        }
    }
}

/** (session, request) identity for dedupe and cancel. A label is never authentication. */
internal data class PhoneToolRequestKey(
    val sessionId: String,
    val requestId: String,
)

private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

private fun ByteArray.endsWith(suffix: ByteArray): Boolean {
    if (size < suffix.size) return false
    for (i in suffix.indices) {
        if (this[size - suffix.size + i] != suffix[i]) return false
    }
    return true
}
