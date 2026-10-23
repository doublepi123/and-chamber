package io.andchamber.app.browser

import android.net.Uri
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val SAME_DOCUMENT_QUERY = "same doc & + é漢"

private fun sameDocString(data: JsonObject, name: String): String? =
    data[name]?.jsonPrimitive?.contentOrNull

private fun sameDocBoolean(data: JsonObject, name: String): Boolean? =
    data[name]?.jsonPrimitive?.booleanOrNull

private fun sameDocSelector(data: JsonObject, name: String): String? {
    val elements = data["elements"]?.jsonArray ?: return null
    for (element in elements) {
        val item = element.jsonObject
        if (item["name"]?.jsonPrimitive?.contentOrNull == name) {
            return item["selector"]?.jsonPrimitive?.contentOrNull
        }
    }
    return null
}

private fun sameDocEval(harness: BrowserAuthorityHarness, script: String): String? {
    val view = harness.currentWebView() ?: return null
    val raw = harness.eval(view, script) ?: return null
    return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
}

private fun sameDocUrl(harness: BrowserAuthorityHarness): String? =
    sameDocEval(harness, "String(location.href)")

private fun sameDocTimeOrigin(harness: BrowserAuthorityHarness): String? =
    sameDocEval(harness, "String(performance.timeOrigin)")

private fun sameDocMarker(harness: BrowserAuthorityHarness): String? =
    sameDocEval(harness, "document.getElementById('same-doc-result').textContent")

@RunWith(AndroidJUnit4::class)
class BrowserSameDocumentSubmitInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun open(harness: BrowserAuthorityHarness, url: String, desktop: Boolean = false) {
        val opened = if (desktop) {
            harness.openViewport(url, "same-doc-open", "desktop", 1366, 768)
        } else {
            harness.openDefault(url, "same-doc-open")
        }
        assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
        harness.awaitPageReady()
        harness.awaitDefaultGrant()
    }

    private fun snapshotRef(harness: BrowserAuthorityHarness, requestId: String): Pair<String, JsonObject> {
        val snapshot = harness.snapshot(requestId)
        assertTrue("snapshot failed: ${snapshot.errorCode}", snapshot.ok)
        val ref = sameDocSelector(snapshot.data, "Same doc query")
            ?: throw AssertionError("Same doc query selector missing")
        return ref to snapshot.data
    }

    private fun assertSameDocumentResult(typed: ProviderResult, label: String) {
        assertTrue("$label must succeed: ${typed.errorCode} ${typed.message}", typed.ok)
        assertEquals("$label must report same-document", "same-document", sameDocString(typed.data, "navigation"))
        assertEquals("$label must report navigation-observed", "navigation-observed", sameDocString(typed.data, "phase"))
        assertEquals("$label requires a fresh snapshot", true, sameDocBoolean(typed.data, "requiresSnapshot"))
        assertEquals("$label must stay drivable", true, sameDocBoolean(typed.data, "drivable"))
    }

    private fun assertSameDocumentIdentity(
        harness: BrowserAuthorityHarness,
        view: WebView,
        tab: String?,
        timeOrigin: String?,
        epoch: Long?,
        documentEpoch: Long,
        arrivals: Int,
        label: String,
    ) {
        assertSame("$label must keep the same WebView", view, harness.currentWebView())
        assertEquals("$label must keep the same tab", tab, harness.state.selectedTabId)
        assertEquals("$label must keep the same document", timeOrigin, sameDocTimeOrigin(harness))
        assertEquals("$label must keep the document epoch", documentEpoch, harness.state.documentEpoch)
        assertEquals("$label must keep the grant epoch", epoch, harness.presenter.registry.currentGrant()?.grantEpoch)
        assertEquals("$label must not make a fixture GET", arrivals, OpenChamberProviderFixtures.sameDocumentArrivals)
    }

    @Test
    fun sameDocumentReplaceStateKeepsViewTabGrantAndDesktopSize() {
        val harness = BrowserAuthorityHarness(instrumentation, "same-doc-replace")
        try {
            open(harness, OpenChamberProviderFixtures.SAME_DOCUMENT_URL, desktop = true)
            val tab = harness.state.selectedTabId
            val view = harness.currentWebView() ?: throw AssertionError("view missing")
            val timeOrigin = sameDocTimeOrigin(harness)
            val epoch = harness.presenter.registry.currentGrant()?.grantEpoch
            assertNotNull("default grant epoch missing", epoch)
            val documentEpoch = harness.state.documentEpoch
            val arrivals = OpenChamberProviderFixtures.sameDocumentArrivals
            val (ref, _) = snapshotRef(harness, "same-doc-before")

            val typed = harness.type(ref, SAME_DOCUMENT_QUERY, submit = true, requestId = "same-doc-submit", timeoutMs = 5_000L)
            assertSameDocumentResult(typed, "replaceState submit")
            assertEquals("Enter must run exactly once", "1", sameDocEval(harness, "String(window.__enterCount)"))
            assertSameDocumentIdentity(harness, view, tab, timeOrigin, epoch, documentEpoch, arrivals, "replaceState submit")
            assertTrue("desktop mode must survive", harness.state.desktopMode)
            assertEquals(1366, harness.state.desktopWidth)
            assertEquals(768, harness.state.desktopHeight)
            harness.awaitDesktopViewport(1366, 768)
            val afterUrl = sameDocUrl(harness) ?: throw AssertionError("same-doc URL missing")
            assertEquals(SAME_DOCUMENT_QUERY, Uri.parse(afterUrl).getQueryParameter("q"))
            assertEquals("the DOM marker must be same-document", "same-doc:$SAME_DOCUMENT_QUERY", sameDocMarker(harness))

            val stale = harness.type(ref, "stale", submit = false, requestId = "same-doc-stale")
            assertFalse("old ref must be expired", stale.ok)
            assertEquals(ProviderCodes.REF_EXPIRED, stale.errorCode)
            val (fresh, afterData) = snapshotRef(harness, "same-doc-after")
            assertTrue("new snapshot must see the same-document result",
                (sameDocString(afterData, "text") ?: "").contains("same-doc:$SAME_DOCUMENT_QUERY"))
            assertTrue("fresh ref must be usable for the next snapshot", fresh.isNotBlank())
            assertTrue("fresh refs must still allow input", harness.type(fresh, "next-query", submit = false, requestId = "same-doc-fresh-type").ok)
            assertEquals("the next input must not replay Enter", "1", sameDocEval(harness, "String(window.__enterCount)"))
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun delayedPushStateWaitsForHistoryObservationWithoutClaimingBusinessCompletion() {
        val harness = BrowserAuthorityHarness(instrumentation, "same-doc-async")
        try {
            open(harness, OpenChamberProviderFixtures.SAME_DOCUMENT_ASYNC_URL)
            val view = harness.currentWebView() ?: throw AssertionError("view missing")
            val tab = harness.state.selectedTabId
            val timeOrigin = sameDocTimeOrigin(harness)
            val epoch = harness.presenter.registry.currentGrant()?.grantEpoch
            val documentEpoch = harness.state.documentEpoch
            val arrivals = OpenChamberProviderFixtures.sameDocumentArrivals
            val (ref, _) = snapshotRef(harness, "same-doc-async-before")

            val started = android.os.SystemClock.elapsedRealtime()
            val typed = harness.type(ref, SAME_DOCUMENT_QUERY, submit = true, requestId = "same-doc-async-submit", timeoutMs = 5_000L)
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            assertSameDocumentResult(typed, "async same-document submit")
            assertEquals("Enter must run exactly once", "1", sameDocEval(harness, "String(window.__enterCount)"))
            assertTrue("typed returned before the delayed history observation", elapsed >= 150L)
            assertTrue("the delayed history must have fired", sameDocEval(harness, "String(window.__historyFired === true)") == "true")
            assertSameDocumentIdentity(harness, view, tab, timeOrigin, epoch, documentEpoch, arrivals, "async same-document submit")
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun inputEventUrlChangeBeforeEnterIsNotASubmitSuccess() {
        val harness = BrowserAuthorityHarness(instrumentation, "same-doc-input")
        try {
            open(harness, OpenChamberProviderFixtures.SAME_DOCUMENT_INPUT_URL)
            val view = harness.currentWebView() ?: throw AssertionError("view missing")
            val beforeUrl = sameDocUrl(harness)
            val timeOrigin = sameDocTimeOrigin(harness)
            val arrivals = OpenChamberProviderFixtures.sameDocumentArrivals
            val (ref, _) = snapshotRef(harness, "same-doc-input-before")

            val typed = harness.type(ref, SAME_DOCUMENT_QUERY, submit = true, requestId = "same-doc-input-submit", timeoutMs = 2_000L)
            assertFalse("a pre-Enter input URL change must not be a submit success", typed.ok)
            assertEquals(ProviderCodes.OUTCOME_UNKNOWN, typed.errorCode)
            assertTrue("the failed same-document submit must stay observation-only", harness.state.actionOutcomeUnknown)
            assertTrue("the input event must have changed the URL", beforeUrl != sameDocUrl(harness))
            assertEquals("the failed submit must not make a fixture GET", arrivals, OpenChamberProviderFixtures.sameDocumentArrivals)
            assertSame("the failed submit must keep the same view", view, harness.currentWebView())
            assertEquals("the failed submit must keep the same document", timeOrigin, sameDocTimeOrigin(harness))
            assertTrue("the page must remain readable", harness.snapshot("same-doc-input-after").ok)
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun hideOrTakeoverAfterEnterRejectsDelayedSameDocumentHistory() {
        fun race(label: String, action: (BrowserAuthorityHarness) -> Unit, verify: (BrowserAuthorityHarness) -> Unit) {
            val harness = BrowserAuthorityHarness(instrumentation, "same-doc-race-$label")
            try {
                open(harness, OpenChamberProviderFixtures.SAME_DOCUMENT_ASYNC_URL)
                val view = harness.currentWebView() ?: throw AssertionError("view missing")
                val arrivals = OpenChamberProviderFixtures.sameDocumentArrivals
                val (ref, _) = snapshotRef(harness, "same-doc-race-$label-before")
                val job = harness.startType(ref, SAME_DOCUMENT_QUERY, submit = true, requestId = "same-doc-race-$label")
                harness.await("native Enter reached $label page", 5_000L) {
                    harness.eval(view, "window.__enterCount === 1") == "true"
                }
                action(harness)
                harness.await("delayed history after $label", 5_000L) {
                    harness.eval(view, "window.__historyFired === true") == "true"
                }
                val result = runCatching {
                    runBlocking { withTimeoutOrNull(5_000L) { job.await() } }
                }.getOrNull()
                if (result == null) {
                    job.cancel()
                    runBlocking { job.join() }
                }
                assertFalse("$label must not report a late same-document success", result?.ok == true)
                assertNull("$label must not leave a late grant", harness.presenter.registry.currentGrant())
                assertEquals("$label must not make a fixture GET", arrivals, OpenChamberProviderFixtures.sameDocumentArrivals)
                verify(harness)
            } finally {
                harness.dispose()
            }
        }

        race("hide", { it.hide() }) {
            assertTrue("hide must be recorded", it.state.hidden)
        }
        race("takeover", { it.takeover() }) {
            assertTrue("takeover must be recorded", it.state.explicitTakeover)
        }
    }
}
