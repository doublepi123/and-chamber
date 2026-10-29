package io.andchamber.app.phone.tool

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneConnection
import io.andchamber.app.phone.core.PhoneController
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriver
import io.andchamber.app.phone.core.PhoneDriverAction
import io.andchamber.app.phone.core.PhoneDriverException
import io.andchamber.app.phone.core.PhoneDriverFailure
import io.andchamber.app.phone.core.PhoneGeometry
import io.andchamber.app.phone.core.PhoneGrantResult
import io.andchamber.app.phone.core.PhoneImage
import io.andchamber.app.phone.core.PhoneNode
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneRect
import io.andchamber.app.phone.core.PhoneSnapshot
import io.andchamber.app.phone.core.PhoneTargetPolicy
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.overlay.progress.PhoneTaskState
import io.andchamber.app.phone.standing.PhoneOpenAppResult
import io.andchamber.app.phone.standing.PhoneStandingApp
import io.andchamber.app.phone.standing.PhoneStandingGrantResult
import io.andchamber.app.phone.standing.PhoneStandingRefusal
import io.andchamber.app.phone.standing.PhoneStandingScope
import io.andchamber.app.phone.standing.PhoneStandingView
import io.andchamber.app.phone.standing.PhoneTaskContinuationStatus
import io.andchamber.app.phone.standing.PhoneTaskStartResult
import io.andchamber.app.phone.standing.PhoneTaskStartState
import io.andchamber.app.phone.standing.PhoneTaskStarter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal const val TARGET = "com.example.target"
internal const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

internal fun phoneSnapshot(packageName: String = TARGET): PhoneSnapshot =
    PhoneSnapshot(
        packageName = packageName,
        windowId = "window-1",
        geometry = PhoneGeometry(displayWidth = 1080, displayHeight = 2400, rotation = 0),
        nodes = listOf(
            PhoneNode(
                ref = "n1",
                role = "button",
                label = "Continue",
                bounds = PhoneRect(100, 200, 300, 260),
                clickable = true,
            ),
            PhoneNode(
                ref = "n2",
                role = "edit",
                label = "Search",
                bounds = PhoneRect(100, 400, 900, 480),
                editable = true,
            ),
        ),
        refs = mapOf(
            "n1" to PhoneRect(100, 200, 300, 260),
            "n2" to PhoneRect(100, 400, 900, 480),
        ),
    )

internal fun allCapabilities(unicodeInput: Boolean = false): PhoneCapabilities =
    PhoneCapabilities(
        observe = true,
        capture = true,
        tap = true,
        swipe = true,
        input = true,
        unicodeInput = unicodeInput,
        back = true,
    )

/**
 * Driver fixture with deterministic dispatch latches. The server tests use real controller,
 * loopback socket and HTTP parsing; only the platform driver is replaced.
 */
internal class FakePhoneDriver : PhoneDriver {
    @Volatile var capabilities: PhoneCapabilities = allCapabilities()
    @Volatile var runtimeId: String = "runtime-1"
    @Volatile var snapshot: PhoneSnapshot = phoneSnapshot()
    @Volatile var image: PhoneImage = PhoneImage(PNG, "image/png", 1080, 2400)
    @Volatile var dispatchReceipt: PhoneDispatchReceipt = PhoneDispatchReceipt.Sent
    @Volatile var observeFailure: PhoneDriverFailure? = null
    @Volatile var dispatchFailure: PhoneDriverFailure? = null
    val observeCalls = AtomicInteger(0)
    val captureCalls = AtomicInteger(0)
    val dispatched = mutableListOf<PhoneDispatchRequest>()
    val dispatchStarted = CountDownLatch(1)
    @Volatile var releaseDispatch: CountDownLatch? = null

    override fun capabilities(): PhoneCapabilities = capabilities

    override suspend fun connect(deadlineMs: Long): PhoneConnection =
        PhoneConnection("conn-1", runtimeId)

    override suspend fun disconnect(deadlineMs: Long) = Unit

    override suspend fun observe(deadlineMs: Long): PhoneSnapshot {
        observeCalls.incrementAndGet()
        observeFailure?.let { throw PhoneDriverException(it) }
        return snapshot
    }

    override suspend fun capture(
        binding: PhoneObservationBinding,
        deadlineMs: Long,
    ): PhoneImage {
        captureCalls.incrementAndGet()
        return image
    }

    override suspend fun dispatch(
        request: PhoneDispatchRequest,
        deadlineMs: Long,
    ): PhoneDispatchReceipt {
        synchronized(dispatched) { dispatched += request }
        dispatchStarted.countDown()
        releaseDispatch?.await(5, TimeUnit.SECONDS)
        dispatchFailure?.let { throw PhoneDriverException(it) }
        return dispatchReceipt
    }

    fun dispatchedActions(): List<PhoneDriverAction> = synchronized(dispatched) { dispatched.map { it.action } }

    companion object {
        /** 1x1 transparent PNG. */
        val PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )
    }
}

internal fun toolBody(
    session: String = "ses-a",
    callId: String = "call-1",
    input: JsonObject,
): String =
    buildJsonObject {
        put("version", 1)
        put("sessionID", session)
        put("callID", callId)
        put("messageID", "msg-1")
        put("agent", "build")
        put("input", input)
    }.toString()

internal fun input(
    action: String,
    observationId: String? = null,
    extra: Map<String, Any> = emptyMap(),
): JsonObject =
    buildJsonObject {
        put("action", action)
        observationId?.let { put("observationId", it) }
        extra.forEach { (key, value) ->
            when (value) {
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Boolean -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

internal fun cancelBody(
    session: String = "ses-a",
    callId: String = "call-1",
): String =
    buildJsonObject {
        put("version", 1)
        put("sessionID", session)
        put("callID", callId)
    }.toString()

internal data class RawResponse(
    val status: Int,
    val body: String,
) {
    val json: JsonObject
        get() = kotlinx.serialization.json.Json.parseToJsonElement(body) as JsonObject
}

internal fun httpPost(
    port: Int,
    body: String,
    path: String = "/v1/tool",
    token: String? = TOKEN,
    method: String = "POST",
    host: String = "127.0.0.1:$port",
    extraHeaders: List<Pair<String, String>> = emptyList(),
    omitContentLength: Boolean = false,
    transferEncoding: String? = null,
    contentLengthOverride: Int? = null,
): RawResponse =
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
        head.append("$method $path HTTP/1.1\r\n")
        head.append("Host: $host\r\n")
        if (token != null) head.append("Authorization: Bearer $token\r\n")
        head.append("Content-Type: application/json\r\n")
        extraHeaders.forEach { (name, value) -> head.append("$name: $value\r\n") }
        transferEncoding?.let { head.append("Transfer-Encoding: $it\r\n") }
        if (!omitContentLength) {
            head.append("Content-Length: ${contentLengthOverride ?: bytes.size}\r\n")
        }
        head.append("Connection: close\r\n\r\n")
        val out = socket.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
        readResponse(socket.getInputStream())
    }

private fun readResponse(input: InputStream): RawResponse {
    val header = ByteArrayOutputStream()
    var previous = 0
    while (true) {
        val next = input.read()
        if (next < 0) break
        header.write(next)
        if (previous == '\r'.code && next == '\n'.code) {
            val text = header.toString(Charsets.ISO_8859_1.name())
            if (text.endsWith("\r\n\r\n")) break
        }
        previous = next
    }
    val headerText = header.toString(Charsets.ISO_8859_1.name())
    if (headerText.isEmpty()) return RawResponse(-1, "")
    val lines = headerText.split("\r\n")
    val status = lines.first().split(" ").getOrNull(1)?.toIntOrNull() ?: -1
    val length = lines.drop(1)
        .firstOrNull { it.lowercase().startsWith("content-length:") }
        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
    val body = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = input.read(body, read, length - read)
        if (n < 0) break
        read += n
    }
    return RawResponse(status, String(body, 0, read, Charsets.UTF_8))
}

/** Per-test loopback server; call [close] in a finally block. */
internal class FakeTaskStarter(
    private val controller: PhoneController,
) : PhoneTaskStarter {
    var availableFlag = true
    var apps: List<PhoneStandingApp> = listOf(PhoneStandingApp("Fixture", TARGET))
    var actions: Set<PhoneActionKind> = PhoneActionKind.values().toSet()
    var windowEndsMs: Long = 10_000_000L
    var perTaskMs: Long = 60_000L
    var startState: PhoneTaskStartState = PhoneTaskStartState.RUNNING

    /** All-apps projection: no list, a bounded launchable catalogue, and its truncation fact. */
    var allApps: Boolean = false
    var launchableApps: List<PhoneStandingApp> = emptyList()
    var catalogueTruncated: Boolean = false

    /** The target of the most recent request, for the one-target-per-task open_app rule. */
    var lastRequested: String? = null

    /** When set, [request] returns it verbatim (used to exercise stale/odd native results). */
    var startOverride: PhoneTaskStartResult? = null

    /** Read-only continuation projection; null by default like an older backend. */
    var continuationStatus: PhoneTaskContinuationStatus? = null
    val requests = mutableListOf<Triple<String, String, String>>()
    val opened = mutableListOf<String>()
    val aborted = mutableListOf<Pair<Long, PhoneTaskKey>>()
    var abortResult: Boolean = true
    var abortFailure: Exception? = null

    override val available: Boolean get() = availableFlag

    override fun standing(): PhoneStandingView? =
        if (!availableFlag) {
            null
        } else {
            PhoneStandingView(
                apps = apps,
                actions = actions,
                windowEndsAtMs = windowEndsMs,
                allApps = allApps,
                launchableApps = if (allApps) launchableApps else emptyList(),
                catalogueTruncated = allApps && catalogueTruncated,
            )
        }

    override fun continuation(): PhoneTaskContinuationStatus? =
        if (!availableFlag) null else continuationStatus

    override suspend fun request(
        sessionId: String,
        summary: String,
        packageName: String,
    ): PhoneTaskStartResult {
        requests += Triple(sessionId, summary, packageName)
        startOverride?.let { return it }
        if (!availableFlag) return PhoneTaskStartResult.Refused(PhoneStandingRefusal.NOT_CONFIGURED)
        if (!allApps && apps.none { it.packageName == packageName }) {
            return PhoneTaskStartResult.Refused(PhoneStandingRefusal.TARGET_NOT_ALLOWED)
        }
        if (allApps && PhoneTargetPolicy().refusal(packageName, allApps = true) != null) {
            return PhoneTaskStartResult.Refused(PhoneStandingRefusal.TARGET_NOT_ALLOWED)
        }
        lastRequested = packageName
        val runtimeId = controller.state.value.runtimeId
            ?: return PhoneTaskStartResult.Refused(PhoneStandingRefusal.NOT_CONNECTED)
        val scope =
            PhoneStandingScope(
                runtimeId = runtimeId,
                sessionLabel = sessionId,
                allowedPackages = if (allApps) emptyList() else apps.map { it.packageName },
                allowedActions = actions,
                windowEndsMs = windowEndsMs,
                perTaskMs = perTaskMs,
                allApps = allApps,
            )
        return when (val result = controller.grantFromPreauthorization(scope, packageName)) {
            is PhoneStandingGrantResult.Granted ->
                PhoneTaskStartResult.Started(
                    epoch = result.grant.epoch,
                    key = PhoneTaskKey(taskId = "task-${result.grant.epoch}", runtimeId = runtimeId),
                    state = startState,
                )
            is PhoneStandingGrantResult.Refused ->
                PhoneTaskStartResult.Refused(result.refusal)
        }
    }

    override suspend fun openApp(
        sessionId: String,
        packageName: String,
    ): PhoneOpenAppResult {
        opened += packageName
        if (!availableFlag) return PhoneOpenAppResult.Refused(PhoneStandingRefusal.NOT_CONFIGURED)
        // Explicit list: the package must be listed. All-apps: only this task's own target may be
        // reopened (one target per task), exactly like the native launcher rule.
        val allowed = if (allApps) packageName == lastRequested else apps.any { it.packageName == packageName }
        return if (allowed) {
            PhoneOpenAppResult.Opened
        } else {
            PhoneOpenAppResult.Refused(PhoneStandingRefusal.TARGET_NOT_ALLOWED)
        }
    }

    override suspend fun abortStarted(
        epoch: Long,
        key: PhoneTaskKey,
    ): Boolean {
        aborted += epoch to key
        abortFailure?.let { throw it }
        return abortResult
    }

    /** Native READ_TIMEOUT re-arm count; the tool must ask exactly once per timed-out call. */
    var readTimeouts: Int = 0

    override suspend fun onReadTimeout() {
        readTimeouts += 1
    }
}

internal class PhoneServerFixture(
    val launchId: Long = 7L,
    requestCapacity: Int = PhoneToolServer.MAX_REQUESTS,
    val taskEvents: io.andchamber.app.phone.overlay.progress.PhoneTaskEventHandler =
        io.andchamber.app.phone.overlay.progress.PhoneTaskEventHandler.Unavailable,
) {
    var now: Long = 1_000L
        @Synchronized get
        @Synchronized set
    @Volatile var ready: Long? = launchId
    val driver = FakePhoneDriver()
    private val observations = AtomicInteger(0)
    val controller =
        io.andchamber.app.phone.core.PhoneController(
            driver = driver,
            clock = { now },
            newObservationId = { "obs-" + observations.incrementAndGet() },
        )
    val taskStarter = FakeTaskStarter(controller)
    val server =
        PhoneToolServer(
            launchId = launchId,
            token = TOKEN,
            controller = controller,
            readyLaunch = { ready },
            taskEvents = taskEvents,
            taskStarter = taskStarter,
            requestCapacity = requestCapacity,
        )
    val port: Int = server.start()

    /** The exact ready handshake body; anything else must be refused. */
    val readyBody: String =
        """{"version":1,"plugin":"andchamber-phone","tool":"andchamber_phone"}"""

    fun postReady(
        body: String = readyBody,
        token: String? = TOKEN,
        path: String = PhoneToolProtocol.READY_PATH,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): RawResponse = httpPost(port, body, path = path, token = token, extraHeaders = extraHeaders)

    suspend fun standingReady() {
        controller.enable()
        check(controller.connect() is io.andchamber.app.phone.core.PhoneConnectionResult.Connected)
    }

    suspend fun granted(
        actions: Set<io.andchamber.app.phone.core.PhoneActionKind> =
            io.andchamber.app.phone.core.PhoneActionKind.values().toSet(),
        ttlMs: Long = 60_000L,
        pinnedSession: String? = null,
        publishRoute: Boolean = true,
    ) {
        controller.enable()
        check(controller.connect() is io.andchamber.app.phone.core.PhoneConnectionResult.Connected)
        val result = controller.grant(TARGET, actions, ttlMs)
        check(result is io.andchamber.app.phone.core.PhoneGrantResult.Granted)
        if (publishRoute) {
            check(server.publishGrantRoute(result.grant.epoch, pinnedSession)) {
                "native grant route was not published"
            }
        }
    }

    fun publishRoute(sessionLabel: String? = null): Boolean =
        server.publishGrantRoute(controller.state.value.epoch, sessionLabel)

    fun close() {
        server.stop()
    }
}
