package io.andchamber.app.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ServerSocketFactory

/**
 * Loopback-only command server. Binds 127.0.0.1 on an ephemeral port.
 *
 * Accepts only `POST /v1/command` with `Authorization: Bearer <token>`, no Origin header, and Host
 * exactly `127.0.0.1:<bound port>`. Chunked bodies and ambiguous lengths are rejected. The lease is
 * checked again inside [BrowserControllerImpl.submit] immediately before the driver runs.
 *
 * The token is never written to a URL, a log, or the response.
 */
class BrowserRpcServer(
    private val controller: BrowserControllerImpl,
    private val bindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
    private val socketFactory: ServerSocketFactory = ServerSocketFactory.getDefault(),
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var server: ServerSocket? = null
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val reads = java.util.concurrent.Semaphore(BrowserLimits.MAX_CONCURRENT_READS, true)

    val port: Int get() = server?.localPort ?: error("server is not listening")

    internal fun activeConnectionCount(): Int = synchronized(clients) { clients.size }

    /** DI calls this once after both objects exist. Construction does not bind a port. */
    fun registerWithController() {
        controller.attachListener(ControllerListener())
    }

    fun start() {
        start { controller.bindEndpoint(it) }
    }

    private fun start(onBound: (String) -> Unit) {
        check(server == null) { "already listening" }
        check(bindAddress.isLoopbackAddress && bindAddress.hostAddress == "127.0.0.1") {
            "refusing a non-loopback bind"
        }
        val socket = socketFactory.createServerSocket()
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(bindAddress, 0), 16)
        server = socket
        val endpoint = "http://127.0.0.1:${socket.localPort}${BrowserLimits.COMMAND_PATH}"
        controller.bindEndpoint(endpoint)
        onBound(endpoint)
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
                client.soTimeout = BrowserLimits.READ_TIMEOUT_MS
                clients.add(client)
                launch {
                    try {
                        handle(client)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
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
    }

    suspend fun stop() {
        withContext(NonCancellable) {
            controller.bindEndpoint(null)
            val open = synchronized(clients) { clients.toList() }
            open.forEach { runCatching { it.close() } }
            clients.removeAll(open.toSet())
            server?.close()
            server = null
        }
    }

    private suspend fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = BrowserLimits.READ_TIMEOUT_MS
            val exchange = try {
                readExchange(socket)
            } catch (_: SocketTimeoutException) {
                writeJson(socket, errorBody(null, "TIMEOUT", "read timed out"), 408)
                return
            } catch (rejected: Rejected) {
                writeJson(socket, errorBody(rejected.id, rejected.code, rejected.detail), rejected.status)
                return
            } catch (_: Exception) {
                writeJson(socket, errorBody(null, "BAD_REQUEST", "malformed request"), 400)
                return
            }
            if (!controller.tokenMatches(exchange.token)) {
                writeJson(socket, errorBody(exchange.id, "UNAUTHORIZED", "unauthorized"), 401)
                return
            }
            val activeSession = controller.currentSessionId()
            if (activeSession == null || !controller.tokenMatches(exchange.token)) {
                writeJson(socket, errorBody(exchange.id, "UNAUTHORIZED", "unauthorized"), 401)
                return
            }
            if (exchange.sessionId != activeSession) {
                writeJson(socket, errorBody(exchange.id, "UNAUTHORIZED", "unauthorized"), 401)
                return
            }
            val reply = controller.submit(exchange.request, exchange.sessionId)
            try {
                writeJson(socket, replyBody(exchange.id, reply), if (reply.ok) 200 else 409)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return
            }
        }
    }

    private fun readExchange(socket: Socket): Exchange {
        val headerText = readHeaders(socket.getInputStream()).toString(Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: throw Rejected(400, "BAD_REQUEST", "malformed request")
        if (requestLine.size != 3 || requestLine[0] != "POST" || requestLine[1] != BrowserLimits.COMMAND_PATH) {
            throw Rejected(404, "NOT_FOUND", "not found")
        }
        if (requestLine[2] != "HTTP/1.1" && requestLine[2] != "HTTP/1.0") {
            throw Rejected(400, "BAD_REQUEST", "unsupported version")
        }
        val headers = parseHeaders(lines.drop(1))
        if (headers.keys.any { it.equals("origin", ignoreCase = true) }) {
            throw Rejected(403, "FORBIDDEN", "origin not allowed")
        }
        val bound = server?.localPort ?: throw Rejected(400, "BAD_REQUEST", "not listening")
        if (headers["host"] != "127.0.0.1:$bound") throw Rejected(403, "FORBIDDEN", "bad host")
        if (headers.containsKey("transfer-encoding")) throw Rejected(400, "BAD_REQUEST", "chunked body refused")
        val lengthHeader = headers["content-length"] ?: throw Rejected(411, "LENGTH_REQUIRED", "length required")
        if (lengthHeader.length > 1 && lengthHeader.startsWith("0")) {
            throw Rejected(400, "BAD_REQUEST", "ambiguous length")
        }
        if (lengthHeader.any { !it.isDigit() }) throw Rejected(400, "BAD_REQUEST", "ambiguous length")
        val length = lengthHeader.toIntOrNull() ?: throw Rejected(400, "BAD_REQUEST", "ambiguous length")
        if (length !in 1..BrowserLimits.BODY_LIMIT_BYTES) throw Rejected(413, "TOO_LARGE", "body too large")
        val media = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
        if (media != "application/json") throw Rejected(415, "UNSUPPORTED_MEDIA", "json required")
        val token = bearer(headers["authorization"]) ?: throw Rejected(401, "UNAUTHORIZED", "unauthorized")
        val body = readBody(socket.getInputStream(), length)
        val parsed = try {
            json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            throw Rejected(400, "BAD_REQUEST", "malformed json")
        }
        val id = parsed.string("id") ?: throw Rejected(400, "BAD_REQUEST", "missing id")
        val sessionId = parsed.string("sessionId") ?: throw Rejected(400, "BAD_REQUEST", "missing session", id)
        val methodName = parsed.string("method") ?: throw Rejected(400, "BAD_REQUEST", "unknown method", id)
        val method = runCatching { BrowserMethod.valueOf(methodName) }.getOrNull()
            ?: throw Rejected(400, "BAD_REQUEST", "unknown method", id)
        val timeout = parsed["timeoutMs"]?.jsonPrimitive?.longOrNull
        val params = readParams(parsed["params"] as? JsonObject)
        return Exchange(id, token, sessionId, BrowserRequest(id, method, params, timeout))
    }

    private fun readHeaders(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var previous = 0
        while (out.size() <= BrowserLimits.HEADER_LIMIT_BYTES) {
            val next = input.read()
            if (next < 0) throw Rejected(400, "BAD_REQUEST", "truncated headers")
            out.write(next)
            if (previous == '\r'.code && next == '\n'.code && out.toByteArray().endsWith(HEADER_END)) return out.toByteArray()
            previous = next
        }
        throw Rejected(431, "HEADERS_TOO_LARGE", "headers too large")
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw Rejected(400, "BAD_REQUEST", "truncated body")
            read += n
        }
        return body
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        for (line in lines) {
            if (line.isEmpty()) break
            if (line[0] == ' ' || line[0] == '\t') throw Rejected(400, "BAD_REQUEST", "folded header refused")
            val colon = line.indexOf(':')
            if (colon <= 0) throw Rejected(400, "BAD_REQUEST", "malformed header")
            val name = line.substring(0, colon).lowercase()
            if (name != name.trim()) throw Rejected(400, "BAD_REQUEST", "malformed header")
            if (name in headers) throw Rejected(400, "BAD_REQUEST", "duplicate header")
            headers[name] = line.substring(colon + 1).trim()
        }
        return headers
    }

    private fun bearer(header: String?): String? {
        if (header == null || !header.startsWith("Bearer ")) return null
        val token = header.removePrefix("Bearer ")
        if (token.isBlank() || token.any { it.isWhitespace() }) return null
        return token
    }

    private fun readParams(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        if (obj.size > BrowserLimits.MAX_PARAM_ENTRIES) throw Rejected(400, "BAD_REQUEST", "too many params")
        return obj.mapValues { (_, value) ->
            val primitive = value.jsonPrimitive
            if (!primitive.isString) throw Rejected(400, "BAD_REQUEST", "params must be strings")
            val text = primitive.content
            if (text.length > BrowserLimits.MAX_PARAM_CHARS) throw Rejected(400, "BAD_REQUEST", "param too large")
            text
        }
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun writeJson(socket: Socket, body: String, status: Int) {
        val payload = body.toByteArray(Charsets.UTF_8)
        if (payload.size > BrowserLimits.RESPONSE_LIMIT_BYTES) {
            writeRaw(socket, 500, errorBody(null, "RESPONSE_TOO_LARGE", "response too large").toByteArray(Charsets.UTF_8))
            return
        }
        writeRaw(socket, status, payload)
    }

    private fun writeRaw(socket: Socket, status: Int, payload: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            408 -> "Request Timeout"
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            431 -> "Request Header Fields Too Large"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        out.flush()
    }

    private fun replyBody(id: String, reply: BrowserReply): String =
        if (reply.ok) {
            buildJsonObject {
                put("id", id)
                put("ok", true)
                put("result", JsonObject(reply.result.mapValues { JsonPrimitive(it.value) }))
            }.toString()
        } else {
            val error = reply.error ?: BrowserError(BrowserErrorCode.INTERNAL, "internal")
            errorBody(id, error.code.name, error.message)
        }

    private fun errorBody(id: String?, code: String, message: String): String =
        buildJsonObject {
            if (id != null) put("id", id)
            put("ok", false)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }.toString()

    private inner class ControllerListener : BrowserListener {
        override fun start(onBound: (endpoint: String) -> Unit) = this@BrowserRpcServer.start(onBound)

        override suspend fun stop() = this@BrowserRpcServer.stop()
    }

    private data class Exchange(
        val id: String,
        val token: String,
        val sessionId: String,
        val request: BrowserRequest,
    )

    private class Rejected(
        val status: Int,
        val code: String,
        val detail: String,
        val id: String? = null,
    ) : Exception()
}

private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

private fun ByteArray.endsWith(suffix: ByteArray): Boolean {
    if (size < suffix.size) return false
    for (i in suffix.indices) {
        if (this[size - suffix.size + i] != suffix[i]) return false
    }
    return true
}
