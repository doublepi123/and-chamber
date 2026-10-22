package io.andchamber.app.browser.tool

import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderExecutor
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.browser.provider.ProviderRegistry
import io.andchamber.app.browser.provider.ProviderResult
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Collections
import javax.net.ServerSocketFactory

/** A loopback server owned by one local launch. The host drives connection changes. */
interface BrowserToolTransport {
    /** Binds 127.0.0.1 on an ephemeral port and returns it. */
    fun start(): Int

    fun stop()

    /** The connection id current requests may use, or null while the host is not eligible. */
    fun setConnection(connectionId: String?)
}

/**
 * Loopback-only tool server for the OpenChamber plugin. Binds `127.0.0.1` on an ephemeral port and
 * accepts only `POST /v1/tool` or `/v1/cancel` with `Authorization: Bearer <token>`, no Origin, and
 * Host exactly `127.0.0.1:<bound port>`.
 *
 * The token lives only in this object and the 0600 endpoint file. It is never logged and never
 * echoed in a response.
 */
class BrowserToolServer(
    private val launchId: Long,
    private val token: String,
    private val executor: ProviderExecutor,
    private val registry: ProviderRegistry,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onCancel: (String) -> Unit = {},
    private val bindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
    private val socketFactory: ServerSocketFactory = ServerSocketFactory.getDefault(),
    private val readTimeoutMillis: Int = 10_000,
    private val headerLimitBytes: Int = 16_384,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BrowserToolTransport {
    init {
        require(token.length == BrowserToolEndpoint.TOKEN_BYTES * 2) { "token must be 32 bytes" }
        require(token.all { it in '0'..'9' || it in 'a'..'f' }) { "token must be lowercase hex" }
    }

    private var server: ServerSocket? = null
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())

    /** In-flight jobs and cancel tombstones share one atomic (session, request) state. */
    private val requests = RequestTracker()
    private val reads = java.util.concurrent.Semaphore(MAX_CONCURRENT_READS, true)

    @Volatile
    private var connectionId: String? = null

    private val json = Json { ignoreUnknownKeys = false }

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
        connectionId = null
        requests.clear()
        val open = synchronized(clients) { clients.toList() }
        open.forEach { runCatching { it.close() } }
        clients.removeAll(open.toSet())
        runCatching { server?.close() }
        server = null
        scope.cancel()
    }

    override fun setConnection(connectionId: String?) {
        this.connectionId = connectionId
        if (connectionId == null) {
            // A dropped connection also drops every job it authorized.
            requests.cancelAll()
        }
    }

    internal fun activeConnectionCount(): Int = synchronized(clients) { clients.size }

    private suspend fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = readTimeoutMillis
            val exchange = try {
                readExchange(socket)
            } catch (_: SocketTimeoutException) {
                writeJson(socket, BrowserToolProtocol.errorBody("BAD_REQUEST"), 408)
                return
            } catch (rejected: Rejected) {
                writeJson(socket, BrowserToolProtocol.errorBody(rejected.code), rejected.status)
                return
            } catch (_: Exception) {
                writeJson(socket, BrowserToolProtocol.errorBody("BAD_REQUEST"), 400)
                return
            }
            if (!tokenMatches(exchange.bearer)) {
                writeJson(socket, BrowserToolProtocol.errorBody("UNAUTHORIZED"), 401)
                return
            }
            when (exchange.path) {
                BrowserToolEndpoint.TOOL_PATH -> handleTool(socket, exchange)
                BrowserToolEndpoint.CANCEL_PATH -> handleCancel(socket, exchange)
                else -> writeJson(socket, BrowserToolProtocol.errorBody("NOT_FOUND"), 404)
            }
        }
    }

    private suspend fun handleTool(
        socket: Socket,
        exchange: Exchange,
    ) {
        val call = BrowserToolProtocol.parseToolBody(exchange.body) ?: run {
            writeJson(socket, BrowserToolProtocol.errorBody(ProviderCodes.INVALID_REQUEST), 400)
            return
        }
        val parameters = BrowserToolProtocol.parameters(call.input) ?: run {
            writeJson(socket, BrowserToolProtocol.errorBody(ProviderCodes.INVALID_REQUEST), 400)
            return
        }
        val connection = connectionId ?: run {
            writeJson(socket, unavailable(call), 200)
            return
        }
        val request = BrowserToolProtocol.toProviderRequest(launchId, connection, call, parameters)
        val key = inflightKey(call.sessionId, request.requestId)
        // Claim, cancel tombstone and in-flight job share one per-launch state, under one lock.
        // A cancel that arrived before this claim keeps the key refused for the whole launch.
        when (requests.claim(claimKey = request.requestId, cancelKey = key)) {
            RequestTracker.Claim.CANCELLED -> {
                writeJson(socket, cancelled(request.action), 200)
                return
            }
            RequestTracker.Claim.DUPLICATE -> {
                // Every tool envelope carries an action: the plugin rejects one without it.
                writeJson(
                    socket,
                    BrowserToolProtocol.encodeResult(
                        request.action,
                        ProviderResult(false, errorCode = DUPLICATE_CALL),
                    ),
                    200,
                )
                return
            }
            RequestTracker.Claim.CLAIMED -> Unit
        }
        val job = currentCoroutineContext()[Job]
        if (job == null) {
            writeJson(socket, BrowserToolProtocol.errorBody(ProviderCodes.OUTCOME_UNKNOWN), 200)
            return
        }
        // A cancel that raced the claim is recorded before this registration and wins.
        if (requests.register(key, job) == RequestTracker.Registration.CANCELLED) {
            writeJson(socket, cancelled(request.action), 200)
            return
        }
        val result = try {
            execute(request)
        } finally {
            requests.complete(key, job)
        }
        writeJson(socket, BrowserToolProtocol.encodeResult(request.action, result), 200)
    }

    private suspend fun execute(request: io.andchamber.app.browser.provider.ProviderRequest): ProviderResult {
        val deadline = clock() + request.timeoutMs
        return try {
            withTimeout(request.timeoutMs) {
                executor.execute(request, registry, ProviderMode.AGENT, null, deadline, clock)
            }
        } catch (_: TimeoutCancellationException) {
            ProviderResult(false, errorCode = ProviderCodes.DEADLINE)
        } catch (cancelled: CancellationException) {
            // The plugin aborted or the connection dropped; there is no client left to answer.
            throw cancelled
        } catch (_: Exception) {
            ProviderResult(false, errorCode = ProviderCodes.OUTCOME_UNKNOWN)
        }
    }

    private fun handleCancel(
        socket: Socket,
        exchange: Exchange,
    ) {
        val parsed = runCatching { json.parseToJsonElement(exchange.body).jsonObject }.getOrNull()
        val version = parsed?.get("version")?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
        val sessionId = parsed?.get("sessionID")?.let { runCatching { it.jsonPrimitive }.getOrNull() }
            ?.takeIf { it.isString }?.content
        val callId = parsed?.get("callID")?.let { runCatching { it.jsonPrimitive }.getOrNull() }
            ?.takeIf { it.isString }?.content
        if (version != BrowserToolProtocol.VERSION ||
            sessionId == null || sessionId.length !in 1..BrowserToolProtocol.MAX_SESSION_CHARS ||
            callId == null || callId.length !in 1..BrowserToolProtocol.MAX_CALL_ID_CHARS
        ) {
            writeJson(socket, BrowserToolProtocol.errorBody(ProviderCodes.INVALID_REQUEST), 400)
            return
        }
        val requestId = BrowserToolProtocol.requestId(callId)
        // Matched only by (session, request): a cancel for another session must never cancel this
        // session's same call id, and only a matched job may reach the presenter.
        val matched = requests.cancel(inflightKey(sessionId, requestId))
        if (matched) onCancel(requestId)
        writeJson(
            socket,
            """{"version":${BrowserToolProtocol.VERSION},"ok":true,"cancelled":$matched}""",
            200,
        )
    }

    private fun inflightKey(
        sessionId: String,
        requestId: String,
    ): String = "$sessionId:$requestId"

    private fun unavailable(call: ToolCall): String =
        BrowserToolProtocol.encodeResult(
            "browser." + call.action,
            ProviderResult(false, errorCode = ProviderCodes.BROWSER_PAGE_UNAVAILABLE),
        )

    private fun cancelled(action: String): String =
        BrowserToolProtocol.encodeResult(action, ProviderResult(false, errorCode = ProviderCodes.CANCELLED))

    private fun readExchange(socket: Socket): Exchange {
        val headerText = readHeaders(socket.getInputStream()).toString(Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: throw Rejected(400, "BAD_REQUEST")
        if (requestLine.size != 3) throw Rejected(404, "NOT_FOUND")
        val method = requestLine[0]
        val path = requestLine[1]
        if (method != "POST") throw Rejected(404, "NOT_FOUND")
        if (path != BrowserToolEndpoint.TOOL_PATH && path != BrowserToolEndpoint.CANCEL_PATH) {
            throw Rejected(404, "NOT_FOUND")
        }
        if (requestLine[2] != "HTTP/1.1" && requestLine[2] != "HTTP/1.0") throw Rejected(400, "BAD_REQUEST")
        val headers = parseHeaders(lines.drop(1))
        if (headers.keys.any { it.equals("origin", ignoreCase = true) }) throw Rejected(403, "FORBIDDEN")
        val bound = server?.localPort ?: throw Rejected(400, "BAD_REQUEST")
        if (headers["host"] != "127.0.0.1:$bound") throw Rejected(403, "FORBIDDEN")
        if (headers.containsKey("transfer-encoding")) throw Rejected(413, "TOO_LARGE")
        val lengthHeader = headers["content-length"] ?: throw Rejected(413, "TOO_LARGE")
        if (lengthHeader.length > 1 && lengthHeader.startsWith("0")) throw Rejected(400, "BAD_REQUEST")
        if (lengthHeader.any { !it.isDigit() }) throw Rejected(400, "BAD_REQUEST")
        val length = lengthHeader.toIntOrNull() ?: throw Rejected(400, "BAD_REQUEST")
        if (length !in 1..BrowserToolProtocol.MAX_BODY_BYTES) throw Rejected(413, "TOO_LARGE")
        val bearer = bearer(headers["authorization"]) ?: throw Rejected(401, "UNAUTHORIZED")
        val body = readBody(socket.getInputStream(), length)
        return Exchange(path, bearer, body.toString(Charsets.UTF_8))
    }

    private fun readHeaders(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var previous = 0
        while (out.size() <= headerLimitBytes) {
            val next = input.read()
            if (next < 0) throw Rejected(400, "BAD_REQUEST")
            out.write(next)
            if (previous == '\r'.code && next == '\n'.code && out.toByteArray().endsWith(HEADER_END)) return out.toByteArray()
            previous = next
        }
        throw Rejected(431, "BAD_REQUEST")
    }

    private fun readBody(
        input: InputStream,
        length: Int,
    ): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw Rejected(400, "BAD_REQUEST")
            read += n
        }
        return body
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        for (line in lines) {
            if (line.isEmpty()) break
            if (line[0] == ' ' || line[0] == '\t') throw Rejected(400, "BAD_REQUEST")
            val colon = line.indexOf(':')
            if (colon <= 0) throw Rejected(400, "BAD_REQUEST")
            val name = line.substring(0, colon).lowercase()
            if (name != name.trim()) throw Rejected(400, "BAD_REQUEST")
            if (name in headers) throw Rejected(400, "BAD_REQUEST")
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
            413 -> "Payload Too Large"
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
        const val MAX_CONCURRENT_READS = 8
        const val DUPLICATE_CALL = "DUPLICATE_CALL"
    }
}

/**
 * Per-launch atomic claim, registration and cancellation state.
 *
 * Claim keys are deduped per launch; cancel keys are bound to `(session, request)`. Both sets live
 * in this object for the whole launch and are released only by [clear] when the launch ends: a
 * cancelled key is never forgotten, so a late request cannot be re-admitted by capacity pressure.
 * Jobs and tombstones share one lock, so claim/register/cancel races have exactly one outcome.
 */
internal class RequestTracker {
    enum class Claim { CLAIMED, DUPLICATE, CANCELLED }

    enum class Registration { REGISTERED, CANCELLED }

    private val lock = Any()
    private val claimed = HashSet<String>()
    private val cancelled = HashSet<String>()
    private val jobs = HashMap<String, Job>()

    /**
     * Atomically dedupes [claimKey] and refuses it when [cancelKey] was already cancelled. A
     * cancelled key stays refused for the rest of the launch.
     */
    fun claim(
        claimKey: String,
        cancelKey: String,
    ): Claim =
        synchronized(lock) {
            when {
                cancelKey in cancelled -> Claim.CANCELLED
                !claimed.add(claimKey) -> Claim.DUPLICATE
                else -> Claim.CLAIMED
            }
        }

    /** Refuses registration when a cancel raced the claim; the job never runs. */
    fun register(
        cancelKey: String,
        job: Job,
    ): Registration =
        synchronized(lock) {
            if (cancelKey in cancelled) {
                Registration.CANCELLED
            } else {
                jobs[cancelKey] = job
                Registration.REGISTERED
            }
        }

    fun complete(
        cancelKey: String,
        job: Job,
    ) {
        synchronized(lock) { jobs.remove(cancelKey, job) }
    }

    /** Cancels a registered job and reports true; otherwise records a permanent tombstone. */
    fun cancel(cancelKey: String): Boolean {
        var matched: Job? = null
        synchronized(lock) {
            val current = jobs.remove(cancelKey)
            if (current != null) {
                matched = current
            } else {
                cancelled.add(cancelKey)
            }
        }
        matched?.cancel()
        return matched != null
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

private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

private fun ByteArray.endsWith(suffix: ByteArray): Boolean {
    if (size < suffix.size) return false
    for (i in suffix.indices) {
        if (this[size - suffix.size + i] != suffix[i]) return false
    }
    return true
}
