package io.andchamber.app.browser

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.browser.provider.ProviderCodes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val SUBMIT_QUERY = "hello search & plus+é 漢"

private fun jsString(value: String): String = "\"" + value + "\""

private fun JsonObject.dataBoolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.dataString(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun selectorFor(data: JsonObject, name: String): String? {
    val elements = data["elements"]?.jsonArray ?: return null
    for (element in elements) {
        val item = element.jsonObject
        if (item["name"]?.jsonPrimitive?.contentOrNull == name) {
            return item["selector"]?.jsonPrimitive?.contentOrNull
        }
    }
    return null
}

/**
 * Real form-submit regression for browser.type submit=true. It uses the production presenter,
 * BrowserSession, FixedViewportContainer and the debug page decorator; the fixture is served
 * entirely from androidTest memory. No test grant, no URL concatenation as a fake submit.
 *
 * The GET case proves navigation and one arrival; the SPA Enter case in
 * BrowserRecoveryInstrumentationTest proves the short OUTCOME_UNKNOWN path without a reload.
 */
@RunWith(AndroidJUnit4::class)
class BrowserSubmitInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun marker(harness: BrowserAuthorityHarness): String? {
        val view = harness.currentWebView() ?: return null
        return harness.eval(view, "document.getElementById('marker') && document.getElementById('marker').textContent")
    }

    private fun currentUrl(harness: BrowserAuthorityHarness): String? {
        val view = harness.currentWebView() ?: return null
        val raw = harness.eval(view, "String(location.href)") ?: return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    private fun submitState(harness: BrowserAuthorityHarness): String {
        val state = harness.state
        return "state(agentOn=${state.agentOn},mode=${state.mode},selected=${state.selectedTabId}," +
            "pageReady=${state.pageReady},documentEpoch=${state.documentEpoch}," +
            "pageError=${state.pageError},hidden=${state.hidden},paused=${state.paused}); " +
            "grant=${harness.presenter.registry.currentGrant()}"
    }

    @Test
    fun getFormSubmitNavigatesOnceWithQueryAndKeepsControl() {
        verifyGetSubmit("mobile", null, null, "mobile")
        verifyGetSubmit("desktop", 1366, 768, "desktop")
    }

    private fun verifyGetSubmit(viewport: String, width: Int?, height: Int?, key: String) {
        val harness = BrowserAuthorityHarness(instrumentation, "submit-get-$key")
        try {
            val opened = harness.openViewport(
                OpenChamberProviderFixtures.SEARCH_URL,
                "open-search",
                viewport,
                width,
                height,
            )
            assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            val search = harness.snapshot("snapshot-search")
            assertTrue("search snapshot failed: ${search.errorCode}; ${submitState(harness)}", search.ok)
            val query = selectorFor(search.data, "Search query")
                ?: throw AssertionError("search field q missing")
            val arrivalsBefore = OpenChamberProviderFixtures.searchGetArrivals
            val tabBefore = harness.state.selectedTabId
            val viewBefore = harness.currentWebView() ?: throw AssertionError("search view missing")
            val timeOriginBefore = harness.eval(viewBefore, "String(performance.timeOrigin)")
            assertNotNull(tabBefore)

            // This is the real Enter/GET submit path, not an open() of a prebuilt URL.
            val typed = harness.type(query, SUBMIT_QUERY, submit = true, requestId = "type-submit")
            assertTrue(
                "GET form submit must be accepted: ${typed.errorCode} ${typed.message}",
                typed.ok,
            )

            harness.await("search results page", 30_000L) {
                val view = harness.currentWebView() ?: return@await false
                val url = currentUrl(harness) ?: return@await false
                val uri = Uri.parse(url)
                harness.eval(view, "document.readyState") == jsString("complete") &&
                    uri.getQueryParameter("q") == SUBMIT_QUERY &&
                    uri.getQueryParameter("enterCount") == "1" &&
                    uri.getQueryParameter("nativeTrusted") == "true" &&
                    harness.eval(
                        view,
                        "document.getElementById('marker') && document.getElementById('marker').textContent",
                    ) == jsString("results:$SUBMIT_QUERY")
            }
            assertEquals(
                "the submit must cause exactly one GET arrival",
                arrivalsBefore + 1,
                OpenChamberProviderFixtures.searchGetArrivals,
            )
            assertTrue("the submit navigation must keep the real grant", harness.state.agentOn)
            harness.awaitDefaultGrant()

            if (viewport == "desktop") {
                assertTrue("the submit result must stay desktop", harness.state.desktopMode)
                assertEquals("the custom desktop width must survive submit", width, harness.state.desktopWidth)
                assertEquals("the custom desktop height must survive submit", height, harness.state.desktopHeight)
                harness.awaitDesktopViewport(width!!, height!!)
            } else {
                assertFalse("a mobile submit result must stay mobile", harness.state.desktopMode)
            }

            val resultView = harness.currentWebView() ?: throw AssertionError("results view missing")
            val resultUrl = currentUrl(harness) ?: throw AssertionError("results URL missing")
            val resultUri = Uri.parse(resultUrl)
            assertEquals("the original q must survive URL encoding", SUBMIT_QUERY, resultUri.getQueryParameter("q"))
            assertEquals("exactly one native Enter", "1", resultUri.getQueryParameter("enterCount"))
            assertEquals("the Enter must be native/trusted", "true", resultUri.getQueryParameter("nativeTrusted"))
            assertEquals("the submit must stay on the same tab", tabBefore, harness.state.selectedTabId)
            assertTrue("the form submit must replace the WebView instance", resultView !== viewBefore)
            val timeOriginAfter = harness.eval(resultView, "String(performance.timeOrigin)")
            assertNotNull(timeOriginAfter)
            assertTrue("the result document must be a new document", timeOriginAfter != timeOriginBefore)

            val results = harness.snapshot("snapshot-results")
            assertTrue("snapshot after submit failed: ${results.errorCode}; ${submitState(harness)}", results.ok)
            assertEquals("the result page must be drivable", true, results.data.dataBoolean("drivable"))
            assertEquals("snapshot URL must equal the actual native URL", resultUrl, results.data.dataString("url"))
            val stale = harness.type(query, "stale-value", submit = false, requestId = "type-stale-after-submit")
            assertFalse("the old search ref must be refused on the result page", stale.ok)
            assertEquals(ProviderCodes.REF_EXPIRED, stale.errorCode)
            val next = selectorFor(results.data, "Next result")
                ?: throw AssertionError("Next result selector missing after submit")
            val clicked = harness.click(next, "click-results")
            assertTrue("click after submit failed: ${clicked.errorCode}", clicked.ok)
            assertEquals(
                jsString("click:1"),
                harness.eval(resultView, "document.getElementById('clicked').textContent"),
            )
            assertEquals(
                "the follow-up click must not submit or reload",
                arrivalsBefore + 1,
                OpenChamberProviderFixtures.searchGetArrivals,
            )
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun postFormSubmitIsRefusedWithoutGetReplay() {
        val harness = BrowserAuthorityHarness(instrumentation, "submit-post")
        try {
            val opened = harness.openDefault(OpenChamberProviderFixtures.SEARCH_POST_URL, "open-post-form")
            assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            val search = harness.snapshot("snapshot-post")
            assertTrue("post form snapshot failed: ${search.errorCode}; ${submitState(harness)}", search.ok)
            val query = selectorFor(search.data, "Post query")
                ?: throw AssertionError("post form field Post query missing")
            val postBefore = OpenChamberProviderFixtures.searchPostArrivals
            val getReplayBefore = OpenChamberProviderFixtures.searchPostGetArrivals

            val typed = harness.type(query, "post-value", submit = true, requestId = "type-post-submit")
            assertFalse("a POST form must not accept submit", typed.ok)
            assertTrue(
                "POST refusal must be UNSUPPORTED_PARAMETER or INVALID_REQUEST: ${typed.errorCode}",
                typed.errorCode == ProviderCodes.UNSUPPORTED_PARAMETER ||
                    typed.errorCode == ProviderCodes.INVALID_REQUEST,
            )
            assertEquals("POST must not reach the POST action", postBefore, OpenChamberProviderFixtures.searchPostArrivals)
            assertEquals("POST must not be silently replayed as GET", getReplayBefore, OpenChamberProviderFixtures.searchPostGetArrivals)
            assertEquals(jsString("post-form"), marker(harness))
            val url = currentUrl(harness)
            assertNotNull(url)
            assertFalse("POST refusal must not navigate", url!!.contains("search-results"))
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun sensitiveFieldSubmitIsRefusedWithoutNavigation() {
        val harness = BrowserAuthorityHarness(instrumentation, "submit-sensitive")
        try {
            val opened = harness.openDefault(OpenChamberProviderFixtures.SEARCH_SENSITIVE_URL, "open-sensitive-form")
            assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            val search = harness.snapshot("snapshot-sensitive")
            assertTrue("sensitive form snapshot failed: ${search.errorCode}; ${submitState(harness)}", search.ok)
            // Take a ref while the field is an ordinary text input, then make that same node
            // sensitive. The stale ref must still be refused; do not rely on a ref never existing.
            val loginField = selectorFor(search.data, "Login field")
                ?: throw AssertionError("ordinary login field missing")
            val view = harness.currentWebView() ?: throw AssertionError("sensitive form view missing")
            assertEquals(
                jsString("ok"),
                harness.eval(
                    view,
                    "var el=document.getElementById('login-field');" +
                        "if(!el){'missing'}else{" +
                        "el.setAttribute('name','password');" +
                        "el.setAttribute('autocomplete','current-password');'ok'}",
                ),
            )
            val arrivalsBefore = OpenChamberProviderFixtures.searchGetArrivals

            val typed = harness.type(loginField, "do-not-submit", submit = true, requestId = "type-sensitive-submit")
            assertFalse("a sensitive field must not accept submit", typed.ok)
            assertTrue(
                "sensitive refusal must be UNSUPPORTED_PARAMETER or INVALID_REQUEST: ${typed.errorCode}",
                typed.errorCode == ProviderCodes.UNSUPPORTED_PARAMETER ||
                    typed.errorCode == ProviderCodes.INVALID_REQUEST,
            )
            assertEquals("sensitive refusal must not submit", arrivalsBefore, OpenChamberProviderFixtures.searchGetArrivals)
            assertEquals(jsString("sensitive-form"), marker(harness))
            assertEquals(
                jsString(""),
                harness.eval(view, "document.getElementById('login-field') && document.getElementById('login-field').value"),
            )
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun hideAfterNativeEnterCancelsThePageDelayedSubmit() {
        val harness = BrowserAuthorityHarness(instrumentation, "submit-race")
        try {
            val opened = harness.openDefault(OpenChamberProviderFixtures.SEARCH_RACE_URL, "open-race")
            assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            val search = harness.snapshot("snapshot-race")
            assertTrue("race snapshot failed: ${search.errorCode}; ${submitState(harness)}", search.ok)
            val query = selectorFor(search.data, "Race query")
                ?: throw AssertionError("race field Race query missing")
            val view = harness.currentWebView() ?: throw AssertionError("race view missing")
            val arrivalsBefore = OpenChamberProviderFixtures.searchRaceGetArrivals

            // Start the real type(submit=true) without blocking. Its native Enter reaches the page's
            // independent handler; only then hide, so this is a race, not a hide-then-call test.
            val typeJob = harness.startType(query, "race-value", submit = true, requestId = "type-race")
            harness.await("native Enter reached the race handler", 8_000L) {
                harness.eval(view, "window.__handlerFired === true") == "true" &&
                    harness.eval(view, "String(window.__enterCount)") == jsString("1")
            }
            harness.hide()
            assertTrue("the race page must be hidden", harness.state.hidden)
            assertFalse("hide must revoke the grant", harness.state.agentOn)
            assertNull("hide must leave no grant", harness.presenter.registry.currentGrant())

            val completed = runCatching {
                runBlocking { withTimeoutOrNull(5_000L) { typeJob.await() } }
            }.getOrNull()
            if (completed == null) typeJob.cancel()
            runBlocking { typeJob.join() }
            assertFalse("the original type call must end or be cancelled", typeJob.isActive)

            // Wait for the handler's own delayed GET attempt, not a fixed sleep. After hide only
            // state, counters and debug eval are read; no snapshot/type, which could wake the window.
            harness.await("delayed GET attempt", 5_000L) {
                harness.eval(view, "window.__delayedGetAttempted === true") == "true"
            }
            assertEquals(
                "hide must prevent the delayed fixture GET",
                arrivalsBefore,
                OpenChamberProviderFixtures.searchRaceGetArrivals,
            )
            assertTrue("hide must not replace the page", harness.currentWebView() === view)
            assertFalse("a late JS GET must not restore the grant", harness.state.agentOn)
            assertNull("a late JS GET must not create a grant", harness.presenter.registry.currentGrant())
            assertTrue("the page must stay hidden", harness.state.hidden)
            assertEquals(
                "the handler must not fire/re-press again",
                jsString("1"),
                harness.eval(view, "String(window.__enterCount)"),
            )
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun takeoverHiddenAndBackgroundNeverLateSubmit() {
        val harness = BrowserAuthorityHarness(instrumentation, "submit-late")
        try {
            val opened = harness.openDefault(OpenChamberProviderFixtures.SEARCH_URL, "open-late-search")
            assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            val search = harness.snapshot("snapshot-late-search")
            assertTrue("search snapshot failed: ${search.errorCode}; ${submitState(harness)}", search.ok)
            val query = selectorFor(search.data, "Search query")
                ?: throw AssertionError("late submit field missing")
            val arrivalsBefore = OpenChamberProviderFixtures.searchGetArrivals

            harness.takeover()
            val afterTakeover = harness.type(query, "late-takeover", submit = true, requestId = "type-late-takeover")
            assertFalse("takeover must refuse a submit", afterTakeover.ok)
            assertEquals("takeover must not submit", arrivalsBefore, OpenChamberProviderFixtures.searchGetArrivals)
            assertEquals(jsString("search-form"), marker(harness))

            harness.hide()
            assertTrue("hide after takeover must be recorded", harness.state.hidden)
            val afterHide = harness.type(query, "late-hidden", submit = true, requestId = "type-late-hidden")
            assertFalse("a takeover-hidden page must refuse a submit", afterHide.ok)
            assertEquals("hidden must not submit", arrivalsBefore, OpenChamberProviderFixtures.searchGetArrivals)
            assertEquals(jsString("search-form"), marker(harness))

            // Explicit user handback, then app background: the request must be refused, not queued.
            harness.show()
            harness.resumeAgent()
            harness.awaitDefaultGrant()
            harness.pauseHost()
            assertTrue("pause must be recorded", harness.state.paused)
            val afterPause = harness.type(query, "late-background", submit = true, requestId = "type-late-background")
            assertFalse("background must refuse a submit", afterPause.ok)
            assertEquals("background must not submit", arrivalsBefore, OpenChamberProviderFixtures.searchGetArrivals)
            assertEquals(jsString("search-form"), marker(harness))
        } finally {
            harness.dispose()
        }
    }
}
