package io.andchamber.app.browser.tool

import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool envelope is what the model reads. An OUTCOME_UNKNOWN must never be described as a
 * page needing a reopen: the same page stays observable and only the person's explicit handback
 * may clear the uncertainty.
 */
class BrowserToolProtocolTest {
    @Test
    fun `outcome unknown tells the model to observe first and never asks for a reopen`() {
        val message = BrowserToolProtocol.fixedMessage(ProviderCodes.OUTCOME_UNKNOWN)
        assertFalse("the old reopen instruction must be gone", message.contains("Re-open this page", ignoreCase = true))
        assertTrue("the action is never replayed", message.contains("Do not repeat", ignoreCase = true))
        assertTrue("observation stays available", message.contains("snapshot or capture", ignoreCase = true))
        assertTrue("the person verifies the page", message.contains("ask the person to check", ignoreCase = true))
        assertTrue("Resume is the handback path", message.contains("resuming", ignoreCase = true))
        assertTrue("Reopen is conditional on the browser state", message.contains("Reopen only if the browser requires it"))
    }

    @Test
    fun `encodeResult uses the fixed outcome unknown message and never page text`() {
        val encoded = Json.parseToJsonElement(
            BrowserToolProtocol.encodeResult(
                "browser.click",
                ProviderResult(false, errorCode = ProviderCodes.OUTCOME_UNKNOWN, message = "page said secret"),
            ),
        ).jsonObject
        assertFalse(encoded.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("browser.click", encoded.getValue("action").jsonPrimitive.content)
        val error = encoded.getValue("error").jsonObject
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, error.getValue("code").jsonPrimitive.content)
        val message = error.getValue("message").jsonPrimitive.content
        assertEquals(BrowserToolProtocol.fixedMessage(ProviderCodes.OUTCOME_UNKNOWN), message)
        assertFalse("page text is never echoed", message.contains("secret"))
        assertFalse(message.contains("Re-open this page", ignoreCase = true))
    }

    @Test
    fun `errorBody carries the same fixed outcome unknown explanation`() {
        val body = Json.parseToJsonElement(BrowserToolProtocol.errorBody(ProviderCodes.OUTCOME_UNKNOWN)).jsonObject
        val message = body.getValue("error").jsonObject.getValue("message").jsonPrimitive.content
        assertEquals(BrowserToolProtocol.fixedMessage(ProviderCodes.OUTCOME_UNKNOWN), message)
        assertFalse(message.contains("Re-open this page", ignoreCase = true))
    }
}
