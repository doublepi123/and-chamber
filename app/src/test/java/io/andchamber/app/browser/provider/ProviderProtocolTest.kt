package io.andchamber.app.browser.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProtocolTest {
    @Test
    fun `ready and request parse without putting a token in the url`() {
        val url = ProviderProtocol.eventsUrl("http://127.0.0.1:3000")
        assertFalse(url.contains("token"))
        assertTrue(url.endsWith("/api/openchamber/events?provider=android"))
        val ready = ProviderProtocol.parseReady(ready("e1", "c1"))
        assertEquals("e1", ready?.serverEpoch)
        assertEquals("c1", ready?.connectionId)
        val request = ProviderProtocol.parseRequest(request("e1", "c1", "r1"), "c1")
        assertEquals("s1", request?.sessionId)
        assertEquals("e1", request?.serverEpoch)
        assertNull(ProviderProtocol.parseRequest("""{"type":"openchamber:browser-control-request","properties":{}}""", "c1"))
        assertNull(ProviderProtocol.parseRequest(request("e1", "other", "r1"), "c1"))
        val cancel = ProviderProtocol.parseCancel(
            """{"type":"openchamber:browser-control-cancel","properties":{"protocolVersion":1,"serverEpoch":"e1","connectionId":"c1","requestId":"r1","reason":"timeout"}}""",
            "c1",
        )
        assertEquals("r1", cancel?.requestId)
        assertEquals("timeout", cancel?.reason)
        assertNull(ProviderProtocol.parseCancel(
            """{"type":"openchamber:browser-control-cancel","properties":{"protocolVersion":1,"serverEpoch":"e1","connectionId":"other","requestId":"r1","reason":"timeout"}}""",
            "c1",
        ))
        assertNull(ProviderProtocol.parseReady("""{"type":"openchamber:event-stream-ready","properties":{"protocolVersion":2,"serverEpoch":"e1","connectionId":"c1"}}"""))
    }

    @Test
    fun `line buffer rejects an oversized chunk and selector is exact`() {
        val buffer = SseLineBuffer(limit = 20)
        assertTrue(buffer.push("data: hi\n").isNotEmpty())
        assertTrue(buffer.push("x".repeat(30)).isEmpty())
        assertEquals("ref-1", ProviderProtocol.selectorRef("""[data-andchamber-ref="ref-1"]"""))
        assertNull(ProviderProtocol.selectorRef("button.apply"))
        assertNull(ProviderProtocol.selectorRef("""[data-andchamber-ref="ref-1"] div"""))
    }

    @Test
    fun `split lines assemble one event and an oversized event is dropped`() {
        val lines = SseLineBuffer(limit = 32)
        assertTrue(lines.push("data: {\"a\"").isEmpty())
        assertEquals(listOf("data: {\"a\":1}"), lines.push(":1}\n"))
        val parser = SseEventParser(maxEventChars = 24, maxFields = 4)
        assertNull(parser.push("data: {\"ok\":true}"))
        assertEquals("{\"ok\":true}", parser.push("")?.data)
        assertNull(parser.push("data: " + "x".repeat(20)))
        assertNull(parser.push(""))
        assertNull(parser.push("data: next"))
        assertEquals("next", parser.push("")?.data)
    }

    @Test
    fun `result body keeps nonce and a string error and never a token`() {
        val body = ProviderProtocol.resultBody(
            "r1",
            "c1",
            "nonce-1",
            ProviderResult(false, errorCode = ProviderCodes.DEADLINE, message = "secret-token"),
        )!!
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("nonce-1", json.getValue("claimNonce").jsonPrimitive.content)
        assertEquals("false", json.getValue("ok").jsonPrimitive.content)
        assertEquals(ProviderCodes.DEADLINE, json.getValue("error").jsonPrimitive.content)
        assertFalse(body.contains("secret-token"))
        assertFalse(body.contains("message"))
        val claim = ProviderProtocol.parseClaim("""{"granted":true,"claimNonce":"nonce-1","remainingMs":800}""")
        assertEquals(true, claim?.granted)
        assertEquals("nonce-1", claim?.nonce)
        assertEquals(false, ProviderProtocol.parseClaim("""{"granted":false}""")?.granted)
        assertEquals(false, ProviderProtocol.parseClaim("not-json")?.granted == true)
    }

    @Test
    fun `reconnect identity and dedupe do not replay`() {
        val registry = ProviderRegistry()
        assertTrue(registry.claimKey("e1", "r1"))
        assertFalse(registry.claimKey("e1", "r1"))
        assertTrue(registry.claimKey("e2", "r1"))
    }

    private fun ready(epoch: String, connection: String) =
        """{"type":"openchamber:event-stream-ready","properties":{"protocolVersion":1,"serverEpoch":"$epoch","connectionId":"$connection"}}"""

    private fun request(epoch: String, connection: String, id: String) =
        """{"type":"openchamber:browser-control-request","properties":{"protocolVersion":1,"serverEpoch":"$epoch","connectionId":"$connection","requestId":"$id","action":"browser.snapshot","parameters":{},"context":{"directory":"/root","sessionId":"s1"},"deadlineAtEpochMs":10,"timeoutMs":1000}}"""
}
