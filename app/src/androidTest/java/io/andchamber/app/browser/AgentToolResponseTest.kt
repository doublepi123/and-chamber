package io.andchamber.app.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decoder checks for the real agent-tool failure envelope; no device or runtime. */
class AgentToolResponseTest {
    private fun failure(
        message: Any? = "AUTHORIZATION_REQUIRED",
        kind: Any? = "runtime",
        code: Any? = null,
        status: Int? = 200,
        data: JSONObject? = null,
    ): String {
        val error = JSONObject().put("message", message ?: JSONObject.NULL)
            .put("kind", kind ?: JSONObject.NULL)
        if (code != null) error.put("code", code)
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("ok", false)
            .put("action", "browser.snapshot")
            .put("error", error)
        if (data != null) root.put("data", data)
        return root.toString()
    }

    @Test
    fun exactUpstreamRuntimeFailureGetsCanonicalCode() {
        val raw = """{"schemaVersion":1,"ok":false,"action":"browser.snapshot","error":{"message":"AUTHORIZATION_REQUIRED","kind":"runtime"}}"""
        val decoded = AgentToolResponse.decode(raw, 200)
        assertFalse(decoded.ok)
        assertEquals("AUTHORIZATION_REQUIRED", decoded.errorCode)
        assertEquals("runtime", decoded.errorKind)
        assertTrue(decoded.errorText.contains("AUTHORIZATION_REQUIRED"))
        assertEquals("browser.snapshot", decoded.action)
    }

    @Test
    fun contentAndOutputWrappersKeepTheExactFailure() {
        val envelope = """{"schemaVersion":1,"ok":false,"action":"browser.snapshot","error":{"message":"AUTHORIZATION_REQUIRED","kind":"runtime"}}"""
        val content = JSONObject().put("content", envelope).toString()
        val output = JSONObject().put("output", envelope).toString()
        assertEquals("AUTHORIZATION_REQUIRED", AgentToolResponse.decode(content, 200).errorCode)
        assertEquals("AUTHORIZATION_REQUIRED", AgentToolResponse.decode(output, 200).errorCode)
    }

    @Test
    fun explicitErrorCodeWinsOverCanonicalMessageAndDataCode() {
        val errorCode = AgentToolResponse.decode(
            failure(code = "EXPLICIT_CODE", data = JSONObject().put("code", "DATA_CODE")),
            200,
        )
        assertEquals("EXPLICIT_CODE", errorCode.errorCode)

        val dataCode = AgentToolResponse.decode(
            failure(data = JSONObject().put("code", "DATA_CODE")),
            200,
        )
        assertEquals("DATA_CODE", dataCode.errorCode)

        val unknown = AgentToolResponse.decode(failure(code = "SOMETHING_ELSE"), 200)
        assertEquals("SOMETHING_ELSE", unknown.errorCode)
    }

    @Test
    fun nonStringCodeMessageAndKindAreNeverStringified() {
        val nonStringCode = AgentToolResponse.decode(failure(code = 123), 200)
        assertEquals("AUTHORIZATION_REQUIRED", nonStringCode.errorCode)
        val nonStringMessage = AgentToolResponse.decode(failure(message = 42), 200)
        assertEquals("", nonStringMessage.errorCode)
        val nonStringKind = AgentToolResponse.decode(failure(kind = 7), 200)
        assertEquals("", nonStringKind.errorCode)
    }

    @Test
    fun canonicalCodeNeedsExactMessageRuntimeKindAndHttp200() {
        val negatives = listOf(
            AgentToolResponse.decode(failure(message = "AUTHORIZATION_REQUIRED: page is not granted"), 200),
            AgentToolResponse.decode(failure(message = "authorization_required"), 200),
            AgentToolResponse.decode(failure(message = " AUTHORIZATION_REQUIRED "), 200),
            AgentToolResponse.decode(failure(message = "AUTHORIZATION_REQUIRED", kind = "usage"), 200),
            AgentToolResponse.decode(failure(message = "AUTHORIZATION_REQUIRED", kind = "transport"), 200),
            AgentToolResponse.decode(failure(message = "AUTHORIZATION_REQUIRED"), 401),
            AgentToolResponse.decode(failure(message = "AUTHORIZATION_REQUIRED"), 500),
            AgentToolResponse.decode(failure(message = "AUTHORIZATION_REQUIRED"), null),
            AgentToolResponse.decode(failure(message = "ECONNRESET"), 200),
            AgentToolResponse.decode(failure(message = "No Android browser provider"), 200),
            AgentToolResponse.decode(failure(message = "AUTHORIZATION_REQUIRED", kind = "runtime", code = ""), 401),
        )
        for (decoded in negatives) {
            assertFalse(decoded.ok)
            assertEquals("", decoded.errorCode)
        }
    }

    @Test
    fun refExpiredAndBrowserPageUnavailableAreMaintainedProviderCodes() {
        assertEquals("REF_EXPIRED", AgentToolResponse.decode(failure(message = "REF_EXPIRED"), 200).errorCode)
        assertEquals(
            "BROWSER_PAGE_UNAVAILABLE",
            AgentToolResponse.decode(failure(message = "BROWSER_PAGE_UNAVAILABLE"), 200).errorCode,
        )
    }

    @Test
    fun successEnvelopeWithErrorTextStaysSuccessAndDoesNotBecomeFailure() {
        val raw = JSONObject()
            .put("schemaVersion", 1)
            .put("ok", true)
            .put("action", "browser.snapshot")
            .put("data", JSONObject().put("value", 1))
            .put("error", JSONObject().put("message", "AUTHORIZATION_REQUIRED").put("kind", "runtime"))
            .toString()
        val decoded = AgentToolResponse.decode(raw, 200)
        assertTrue(decoded.ok)
        assertEquals("", decoded.errorCode)
        assertEquals(1, decoded.data?.optInt("value"))
    }

    @Test
    fun malformedAndMissingEnvelopeAreFailuresWithoutACode() {
        for (raw in listOf("", "not json", """{"ok":"false"}""", """{"data":{}}""")) {
            val decoded = AgentToolResponse.decode(raw, 200)
            assertFalse(decoded.ok)
            assertEquals("", decoded.errorCode)
        }
    }
}
