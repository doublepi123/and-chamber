package io.andchamber.app.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks for the fixed fixture-document JSON decoder/predicate; no device or WebView. */
class LocalProviderFixtureDocumentTest {
    private fun read(
        href: String = OpenChamberProviderFixtures.ORIGIN,
        readyState: String = "complete",
        witness: Boolean = true,
        witnessId: String = "0123456789abcdef0123456789abcdef",
        note: Boolean = true,
        apply: Boolean = true,
        framed: String = "framed:top",
    ) = LocalProviderRun.FixtureDocumentRead.fromJson(JSONObject()
        .put("href", href)
        .put("readyState", readyState)
        .put("witnessPresent", witness)
        .put("witnessId", witnessId)
        .put("notePresent", note)
        .put("applyPresent", apply)
        .put("framedText", framed))

    @Test
    fun exactFixedDocumentIsReady() {
        val doc = read()
        assertTrue(doc.hrefExact)
        assertTrue(doc.readyExact)
        assertTrue(doc.witnessExact)
        assertTrue(doc.domExact)
    }

    @Test
    fun registryStaleHrefOrOriginalTargetIsNotReady() {
        assertFalse(read(href = "https://example.com/").hrefExact)
        assertFalse(read(href = "https://example.com/openchamber-provider?x=1").hrefExact)
    }

    @Test
    fun missingOrInvalidWitnessIsNotReady() {
        assertFalse(read(witness = false).witnessExact)
        assertFalse(read(witnessId = "").witnessExact)
        assertFalse(read(witnessId = "not-a-witness").witnessExact)
    }

    @Test
    fun incompleteDocumentOrMissingFixtureDomIsNotReady() {
        assertFalse(read(readyState = "interactive").readyExact)
        assertFalse(read(note = false).domExact)
        assertFalse(read(apply = false).domExact)
        assertFalse(read(framed = "framed:nested").domExact)
    }

    @Test
    fun evidenceKeepsDiagnosticFieldsButNeverTheWitnessString() {
        val doc = read(witnessId = "0123456789abcdef0123456789abcdef")
        val evidence = doc.evidence("href-not-fixture")
        assertEquals("href-not-fixture", evidence.optString("why"))
        assertTrue(evidence.optBoolean("hrefExact"))
        assertTrue(evidence.optBoolean("witnessPresent"))
        assertEquals(32, evidence.optInt("witnessLength"))
        assertTrue(evidence.optBoolean("witnessExact"))
        assertFalse(evidence.toString().contains("0123456789abcdef0123456789abcdef"))
    }
}
