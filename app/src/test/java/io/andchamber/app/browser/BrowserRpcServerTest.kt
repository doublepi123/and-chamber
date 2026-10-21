package io.andchamber.app.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class BrowserRpcServerTest {
    private val json = Json

    @Test
    fun `loopback command accepts a bearer and refuses origin host method and chunked bodies`() = runBlocking {
        val published = PublishedCredential()
        val controller = BrowserControllerImpl(
            credentialFile = File("unused.json"),
            credentials = published,
            sessionIdFactory = { "session-1" },
            currentRuntimeLaunchId = { 1L },
        )
        val server = BrowserRpcServer(controller)
        server.registerWithController()
        controller.attach(BrowserDriver { BrowserReply(ok = true, result = mapOf("title" to "page")) })
        controller.setVisible(true)
        server.start()
        try {
            assertTrue(controller.enableFromUi())
            val token = published.token
            val session = published.sessionId
            val ok = exchange(server.port, token, session, "snap-1")
            assertEquals(200, ok.status)
            assertEquals("true", ok.json["ok"]?.jsonPrimitive?.content)
            assertFalse(ok.raw.contains(token))

            val origin = exchange(server.port, token, session, "snap-2", extraHeaders = "Origin: https://evil.example\r\n")
            assertEquals(403, origin.status)
            val host = exchange(server.port, token, session, "snap-3", host = "localhost:${server.port}")
            assertEquals(403, host.status)
            val get = exchange(server.port, token, session, "snap-4", method = "GET", path = "/v1/command")
            assertEquals(404, get.status)
            val other = exchange(server.port, token, session, "snap-5", path = "/v1/other")
            assertEquals(404, other.status)
            val chunked = exchange(
                server.port,
                token,
                session,
                "snap-6",
                extraHeaders = "Transfer-Encoding: chunked\r\n",
            )
            assertEquals(400, chunked.status)
            val invalidToken = token.dropLast(1) + if (token.endsWith("0")) "1" else "0"
            assertFalse("the invalid token must differ from the real credential", invalidToken == token)
            val badToken = exchange(server.port, invalidToken, session, "snap-7")
            assertEquals(401, badToken.status)
            assertFalse(badToken.raw.contains(token))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `connections beyond the cap are closed immediately and stop closes accepted sockets`() = runBlocking {
        val published = PublishedCredential()
        val controller = BrowserControllerImpl(
            credentialFile = File("unused.json"),
            credentials = published,
            sessionIdFactory = { "session-1" },
            currentRuntimeLaunchId = { 1L },
        )
        val server = BrowserRpcServer(controller)
        server.registerWithController()
        controller.attach(BrowserDriver { BrowserReply(ok = true) })
        controller.setVisible(true)
        server.start()
        try {
            controller.enableFromUi()
            val held = mutableListOf<Socket>()
            try {
                repeat(BrowserLimits.MAX_CONCURRENT_READS) {
                    val socket = Socket(InetAddress.getByName("127.0.0.1"), server.port)
                    held += socket
                    socket.soTimeout = 1_000
                    socket.getOutputStream().write("POST /v1/command HTTP/1.1\r\n".toByteArray())
                    socket.getOutputStream().flush()
                }
                val filled = System.currentTimeMillis() + 1_000
                while (server.activeConnectionCount() < BrowserLimits.MAX_CONCURRENT_READS && System.currentTimeMillis() < filled) {
                    Thread.sleep(10)
                }
                assertEquals(BrowserLimits.MAX_CONCURRENT_READS, server.activeConnectionCount())
                val extra = Socket(InetAddress.getByName("127.0.0.1"), server.port)
                try {
                    extra.soTimeout = 500
                    assertClosed(extra)
                } finally {
                    extra.close()
                }
                server.stop()
                held.forEach { assertClosed(it) }
            } finally {
                held.forEach { it.close() }
            }
        } finally {
            server.stop()
        }
    }

    private fun assertClosed(socket: Socket) {
        try {
            val value = socket.getInputStream().read()
            assertEquals(-1, value)
        } catch (_: java.net.SocketTimeoutException) {
            throw AssertionError("socket stayed open until read timeout")
        } catch (_: java.net.SocketException) {
            return
        }
    }

    @Test
    fun `ambiguous length is refused`() = runBlocking {
        val published = PublishedCredential()
        val controller = BrowserControllerImpl(
            credentialFile = File("unused.json"),
            credentials = published,
            sessionIdFactory = { "session-1" },
            currentRuntimeLaunchId = { 1L },
        )
        val server = BrowserRpcServer(controller)
        server.registerWithController()
        controller.attach(BrowserDriver { BrowserReply(ok = true) })
        controller.setVisible(true)
        server.start()
        try {
            controller.enableFromUi()
            val response = exchange(
                server.port,
                published.token,
                published.sessionId,
                "id-1",
                contentLength = "02",
            )
            assertEquals(400, response.status)
        } finally {
            server.stop()
        }
    }

    private fun exchange(
        port: Int,
        token: String,
        sessionId: String,
        id: String,
        method: String = "POST",
        path: String = "/v1/command",
        host: String? = null,
        extraHeaders: String = "",
        contentLength: String? = null,
    ): RawResponse {
        val body = """{"id":"$id","sessionId":"$sessionId","method":"SNAPSHOT","params":{},"timeoutMs":1000}"""
        val request = command(port, token, body, method, path, host, extraHeaders, contentLength)
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().write(request)
            val raw = socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
            val status = raw.substringAfter("HTTP/1.1 ").substringBefore(' ').toInt()
            val payload = raw.substringAfter("\r\n\r\n")
            val parsed = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrElse { kotlinx.serialization.json.buildJsonObject {} }
            return RawResponse(status, raw, parsed)
        }
    }

    private fun command(
        port: Int,
        token: String,
        body: String,
        method: String = "POST",
        path: String = "/v1/command",
        host: String? = null,
        extraHeaders: String = "",
        contentLength: String? = null,
    ): ByteArray {
        val length = contentLength ?: body.toByteArray(StandardCharsets.UTF_8).size.toString()
        val request = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: ${host ?: "127.0.0.1:$port"}\r\n")
            append("Authorization: Bearer $token\r\n")
            append("Content-Type: application/json\r\n")
            append(extraHeaders)
            append("Content-Length: $length\r\n\r\n")
            append(body)
        }
        return request.toByteArray(StandardCharsets.ISO_8859_1)
    }

    private class PublishedCredential : CredentialPublisher {
        var token: String = ""
        var sessionId: String = ""

        override suspend fun publish(credential: BrowserCredential) {
            token = credential.token
            sessionId = credential.sessionId
        }

        override suspend fun delete() {
            token = ""
        }
    }

    private data class RawResponse(
        val status: Int,
        val raw: String,
        val json: kotlinx.serialization.json.JsonObject,
    )
}
