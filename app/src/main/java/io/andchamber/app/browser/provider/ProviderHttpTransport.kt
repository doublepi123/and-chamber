package io.andchamber.app.browser.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * One cancellable loopback SSE connection plus claim/result posts.
 *
 * Ready and later events come from the same stream. The token is a request header only:
 * it is never placed in the URL, never logged, and never copied into an error message.
 * Redirects are not followed. Origin is not sent. Disconnect cancels that connection's reader.
 * A later ready is a new connection id; this transport does not replay old requests.
 */
class ProviderHttpTransport(
    private val origin: String,
    private val token: String,
    private val opener: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val retryDelayMs: Long = 500L,
    private val maxRetries: Int = ProviderProtocol.MAX_RECONNECTS,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 0,
    // Claims and results are single shots; a slow reply must not be mistaken for a lost one. The
    // server's own request deadline (up to 45 s) bounds how long a result can still matter.
    private val postTimeoutMs: Int = 20_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ProviderTransport {
    @Volatile
    var currentConnectionId: String? = null
        private set

    @Volatile
    private var active: HttpURLConnection? = null

    init {
        require(ProviderProtocol.isLoopbackOrigin(origin)) { "origin refused" }
        require(token.isNotEmpty() && token.indexOf('\r') < 0 && token.indexOf('\n') < 0) { "token refused" }
    }

    override suspend fun open(url: String, headers: Map<String, String>): ProviderProtocol.Ready =
        error("use events(); ready is read from the same stream")

    override fun events(): Flow<String> = channelFlow {
        var attempt = 0
        while (currentCoroutineContext().isActive && attempt <= maxRetries) {
            var sawReady = false
            // A refused or unreachable socket is a failed attempt, never a crash of the app process.
            val connection = try {
                connect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                attempt += 1
                if (attempt <= maxRetries) delay(retryDelayMs)
                continue
            }
            active = connection
            try {
                if (connection.responseCode !in 200..299) error("events refused")
                readEvents(connection.inputStream) { event ->
                    currentCoroutineContext().ensureActive()
                    if (!sawReady) {
                        val ready = ProviderProtocol.parseReady(event.data) ?: return@readEvents
                        currentConnectionId = ready.connectionId
                        sawReady = true
                        send(event.data)
                    } else if (event.event == null || event.event == "message") {
                        send(event.data)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                if (!sawReady) attempt += 1
            } finally {
                if (active === connection) active = null
                disconnect(connection)
                currentConnectionId = null
                if (sawReady && currentCoroutineContext().isActive) send(ProviderProtocol.disconnectEvent())
            }
            if (!currentCoroutineContext().isActive) break
            if (sawReady) attempt += 1
            if (attempt <= maxRetries) delay(retryDelayMs)
        }
    }

    override suspend fun claim(requestId: String, connectionId: String): ProviderClaim? {
        val body = ProviderProtocol.claimBody(requestId, connectionId) ?: return null
        val response = post(origin.trimEnd('/') + ProviderProtocol.CLAIM_PATH, body) ?: return null
        return ProviderProtocol.parseClaim(response)
    }

    override suspend fun result(requestId: String, connectionId: String, nonce: String, result: ProviderResult) {
        val body = ProviderProtocol.resultBody(requestId, connectionId, nonce, result)
        if (body == null) {
            android.util.Log.i("AndChamberProvider", "result not encodable ok=${result.ok}")
            return
        }
        val reply = post(origin.trimEnd('/') + ProviderProtocol.RESULT_PATH, body)
        android.util.Log.i("AndChamberProvider", "result posted bytes=${body.length} matched=${reply?.contains("\"matched\":true")}")
    }

    /** Closes the live SSE socket. Claim/result calls already in flight close themselves. */
    fun stop() {
        active?.let(::disconnect)
        active = null
    }

    private suspend fun connect(): HttpURLConnection = withContext(io) {
        val connection = opener(origin.trimEnd('/') + ProviderProtocol.EVENTS_PATH)
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        if (readTimeoutMs > 0) connection.readTimeout = readTimeoutMs
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty(ProviderProtocol.TOKEN_HEADER, token)
        connection.connect()
        connection
    }

    private suspend fun post(url: String, body: String): String? = withContext(io) {
        val connection = opener(url)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = postTimeoutMs
            connection.readTimeout = postTimeoutMs
            connection.requestMethod = "POST"
            // One fresh socket per claim/result. A pooled keep-alive socket the server has already
            // closed would swallow the post until the timeout, and a lost result is never retried.
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty(ProviderProtocol.TOKEN_HEADER, token)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                android.util.Log.i("AndChamberProvider", "post refused path=${java.net.URL(url).path} http=$code")
                return@withContext null
            }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.i("AndChamberProvider", "post failed path=${java.net.URL(url).path} class=${error.javaClass.simpleName}")
            null
        } finally {
            disconnect(connection)
        }
    }

    private suspend fun readEvents(stream: InputStream, onEvent: suspend (SseEvent) -> Unit) {
        val lines = SseLineBuffer(limit = ProviderProtocol.MAX_SSE_EVENT_CHARS)
        val parser = SseEventParser()
        val chars = CharArray(2_048)
        stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (currentCoroutineContext().isActive) {
                val read = withContext(io) { reader.read(chars) }
                if (read < 0) break
                if (read == 0) continue
                for (line in lines.push(String(chars, 0, read))) {
                    parser.push(line)?.let { onEvent(it) }
                }
            }
        }
    }

    private fun disconnect(connection: HttpURLConnection) {
        runCatching { connection.inputStream?.close() }
        runCatching { connection.errorStream?.close() }
        runCatching { connection.disconnect() }
    }
}
