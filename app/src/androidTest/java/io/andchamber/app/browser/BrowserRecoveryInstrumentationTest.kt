package io.andchamber.app.browser

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.R
import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderResult
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val RECOVERY_SPA_VALUE = "spa-value"

private fun recoverySelectorFor(data: JsonObject, name: String): String? {
    val elements = data["elements"]?.jsonArray ?: return null
    for (element in elements) {
        val item = element.jsonObject
        if (item["name"]?.jsonPrimitive?.contentOrNull == name) {
            return item["selector"]?.jsonPrimitive?.contentOrNull
        }
    }
    return null
}

private fun JsonObject.recoveryString(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun recoveryEvalString(harness: BrowserAuthorityHarness, script: String): String? {
    val view = harness.currentWebView() ?: return null
    val raw = harness.eval(view, script) ?: return null
    return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
}

private fun recoveryTimeOrigin(harness: BrowserAuthorityHarness): String? =
    recoveryEvalString(harness, "String(performance.timeOrigin)")

private fun recoveryUrl(harness: BrowserAuthorityHarness): String? =
    recoveryEvalString(harness, "String(location.href)")

private fun recoveryText(harness: BrowserAuthorityHarness): String? {
    val view = harness.currentWebView() ?: return null
    return recoveryEvalString(harness, "document.body ? document.body.innerText : ''")
}

private fun recoveryEnterCount(harness: BrowserAuthorityHarness): String? =
    recoveryEvalString(harness, "String(window.__enterCount || 0)")

private fun recoveryClickCount(harness: BrowserAuthorityHarness): String? =
    recoveryEvalString(harness, "String(window.__clickCount || 0)")

private fun recoveryInputValue(harness: BrowserAuthorityHarness): String? =
    recoveryEvalString(harness, "(document.getElementById('spa-query') || {}).value || ''")

private fun assertReadDeniedByPermission(result: ProviderResult) {
    assertFalse("a permission change must deny the read", result.ok)
    assertTrue(
        "expected AUTHORIZATION_REQUIRED or BROWSER_PAGE_UNAVAILABLE, got ${result.errorCode}",
        result.errorCode == ProviderCodes.AUTHORIZATION_REQUIRED ||
            result.errorCode == ProviderCodes.BROWSER_PAGE_UNAVAILABLE,
    )
}

private fun assertMutationRefused(result: ProviderResult) {
    assertFalse("a new mutation must be refused while the effect is uncertain", result.ok)
    assertEquals("uncertainty is not a missing grant", ProviderCodes.OUTCOME_UNKNOWN, result.errorCode)
}

private fun assertObservationOnly(result: ProviderResult) {
    assertEquals(false, result.data["drivable"]?.jsonPrimitive?.booleanOrNull)
    assertEquals(true, result.data["observationOnly"]?.jsonPrimitive?.booleanOrNull)
    assertEquals(true, result.data["actionOutcomeUnknown"]?.jsonPrimitive?.booleanOrNull)
}

private data class UncertainSpa(
    val query: String,
    val apply: String,
    val view: WebView,
    val timeOrigin: String,
    val url: String,
    val elapsedMs: Long,
)

/**
 * Real recovery behaviour for the v17 report: the page says "reopen" and Resume does nothing after
 * a SPA Enter whose outcome is uncertain. The fixture consumes native Enter without navigating;
 * the grant stays for read-only observation, mutations stop, and explicit Resume re-probes the same
 * document. A separate extra main-frame start proves a genuinely invalid document requires Reopen
 * and a fresh page. Uses only the production presenter and the debug page decorator.
 */
@RunWith(AndroidJUnit4::class)
class BrowserRecoveryInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun openSpa(harness: BrowserAuthorityHarness, requestId: String) {
        val opened = harness.openDefault(OpenChamberProviderFixtures.SPA_ENTER_URL, requestId)
        assertTrue("SPA fixture open failed: ${opened.errorCode} ${opened.message}", opened.ok)
        harness.awaitPageReady()
        harness.awaitDefaultGrant()
    }

    private fun enterUncertainSpa(harness: BrowserAuthorityHarness, prefix: String): UncertainSpa {
        openSpa(harness, "$prefix-open")
        val initial = harness.snapshot("$prefix-initial")
        assertTrue("initial SPA snapshot failed: ${initial.errorCode}", initial.ok)
        val query = recoverySelectorFor(initial.data, "SPA query")
            ?: throw AssertionError("SPA query selector missing")
        val view = harness.currentWebView() ?: throw AssertionError("SPA view missing")
        val timeOrigin = recoveryTimeOrigin(harness)
            ?: throw AssertionError("SPA timeOrigin missing")
        val url = recoveryUrl(harness) ?: throw AssertionError("SPA URL missing")

        val started = android.os.SystemClock.elapsedRealtime()
        val typed = harness.type(query, RECOVERY_SPA_VALUE, submit = true, requestId = "$prefix-enter", timeoutMs = 1_500L)
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - started
        assertFalse("a submit whose SPA effect is uncertain must not report success", typed.ok)
        assertEquals(
            "the submit must report a typed OUTCOME_UNKNOWN, not a page/navigation success",
            ProviderCodes.OUTCOME_UNKNOWN,
            typed.errorCode,
        )
        assertTrue("the uncertain submit timeout must be short, got ${elapsedMs}ms", elapsedMs < 5_000L)

        // Same document: the fixture's native Enter handler consumed the event and edited the DOM.
        assertTrue("the uncertain submit must not replace the WebView", view === harness.currentWebView())
        assertEquals(
            "the uncertain submit must keep the same document",
            timeOrigin,
            recoveryTimeOrigin(harness) ?: throw AssertionError("SPA timeOrigin missing after Enter"),
        )
        assertEquals(
            "the uncertain submit must not navigate",
            url,
            recoveryUrl(harness) ?: throw AssertionError("SPA URL missing after Enter"),
        )
        assertEquals("the native Enter must run exactly once", "1", recoveryEnterCount(harness))
        assertNotNull(
            "the existing grant must stay for read-only observation",
            harness.presenter.registry.currentGrant(),
        )
        assertFalse(
            "an uncertain effect is not a document-invalid reopen",
            harness.state.requiresReopen,
        )

        val after = harness.snapshot("$prefix-after")
        assertTrue(
            "snapshot must stay allowed while the effect is uncertain: ${after.errorCode}",
            after.ok,
        )
        assertObservationOnly(after)
        val afterText = after.data.recoveryString("text").orEmpty()
        assertTrue(
            "the same-document SPA result must be readable after the uncertain submit",
            afterText.contains("spa-result:$RECOVERY_SPA_VALUE"),
        )
        assertTrue("the DOM must show one Enter", afterText.contains("enter-count:1"))
        val afterQuery = recoverySelectorFor(after.data, "SPA query")
            ?: throw AssertionError("SPA query selector missing after uncertain submit")
        val afterApply = recoverySelectorFor(after.data, "Apply SPA")
            ?: throw AssertionError("SPA Apply selector missing after uncertain submit")
        return UncertainSpa(afterQuery, afterApply, view, timeOrigin, url, elapsedMs)
    }

    @Test
    fun spaEnterIsOutcomeUnknownAndOnlyExplicitResumeRestoresMutation() {
        val harness = BrowserAuthorityHarness(instrumentation, "recovery-spa")
        try {
            val spa = enterUncertainSpa(harness, "spa")

            // Capture is a read and must remain available for the same page/witness.
            val captured = harness.awaitCapture("spa-uncertain")
            assertTrue("capture must stay allowed while the effect is uncertain", captured.ok)
            assertObservationOnly(captured)

            // Every new mutation is refused as an uncertain outcome, never replayed.
            val blockedType = harness.type(
                spa.query,
                "second-value",
                submit = false,
                requestId = "spa-blocked-type",
            )
            assertMutationRefused(blockedType)
            val blockedClick = harness.click(spa.apply, "spa-blocked-click")
            assertMutationRefused(blockedClick)
            assertEquals("the refused type must not change the value", RECOVERY_SPA_VALUE, recoveryInputValue(harness))
            assertEquals("the refused click must not run", "0", recoveryClickCount(harness))
            assertEquals("no mutation may replay Enter", "1", recoveryEnterCount(harness))
            assertTrue(
                "the SPA result must survive the refused mutations",
                recoveryText(harness).orEmpty().contains("spa-result:$RECOVERY_SPA_VALUE"),
            )

            // The person's explicit Resume re-probes this same document and restores control.
            assertTrue("explicit Resume must clear the uncertain effect for a valid same document", harness.resumeAgent())
            harness.awaitDefaultGrant()
            val resumed = harness.snapshot("spa-resumed")
            assertTrue("snapshot after explicit Resume failed: ${resumed.errorCode}", resumed.ok)
            val apply = recoverySelectorFor(resumed.data, "Apply SPA")
                ?: throw AssertionError("SPA Apply selector missing after Resume")
            val clicked = harness.click(apply, "spa-resumed-click")
            assertTrue("a fresh ref after Resume must click: ${clicked.errorCode}", clicked.ok)
            assertEquals("the resumed click must run exactly once", "1", recoveryClickCount(harness))
            assertEquals("Resume must not replay the old Enter", "1", recoveryEnterCount(harness))
            assertEquals("Resume must keep the same document", spa.timeOrigin, recoveryTimeOrigin(harness))
            assertTrue("Resume must keep the same view", spa.view === harness.currentWebView())
            assertTrue("Resume must not navigate", spa.url == recoveryUrl(harness))
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun takeoverOrPausedHostStillDeniesReadOnlyObservation() {
        val takeoverHarness = BrowserAuthorityHarness(instrumentation, "recovery-takeover")
        try {
            enterUncertainSpa(takeoverHarness, "takeover")
            takeoverHarness.takeover()
            assertTrue("takeover must be recorded", takeoverHarness.state.explicitTakeover)
            assertReadDeniedByPermission(takeoverHarness.snapshot("takeover-snapshot"))
        } finally {
            takeoverHarness.dispose()
        }

        val pausedHarness = BrowserAuthorityHarness(instrumentation, "recovery-paused")
        try {
            enterUncertainSpa(pausedHarness, "paused")
            pausedHarness.pauseHost()
            assertTrue("host pause must be recorded", pausedHarness.state.paused)
            assertReadDeniedByPermission(pausedHarness.snapshot("paused-snapshot"))
        } finally {
            pausedHarness.dispose()
        }
    }

    @Test
    fun documentInvalidRequiresExplicitReopenAndKeepsTabAndDesktopSize() {
        val harness = BrowserAuthorityHarness(instrumentation, "recovery-document")
        try {
            val opened = harness.openViewport(
                OpenChamberProviderFixtures.SPA_ENTER_URL,
                "document-open",
                "desktop",
                1366,
                768,
            )
            assertTrue("desktop SPA open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            val tabBefore = harness.state.selectedTabId
            val viewBefore = harness.currentWebView() ?: throw AssertionError("document view missing")
            assertNotNull(tabBefore)

            // One extra real main-frame start on the production WebViewClient, with no navigation
            // or renderer replacement: this is the document-invalid path, not a page text guess.
            harness.triggerExtraPageStarted()
            harness.await("document invalid requires reopen", 10_000L) {
                harness.state.requiresReopen
            }
            assertTrue("a real extra document start must require reopen", harness.state.requiresReopen)
            assertFalse(
                "Resume must not clear a document-invalid page",
                harness.resumeAgent(),
            )
            assertTrue("Resume failure must keep the Reopen requirement", harness.state.requiresReopen)

            // Render the real production BrowserFloatPage over this same presenter. The Reopen
            // control must be the actual UI path, not a direct presenter call.
            harness.showProductionBrowserUi()
            val reopenLabel = harness.activity.getString(R.string.browser_reopen_page)
            val resumeLabel = harness.activity.getString(R.string.browser_turn_on)
            harness.awaitUiLabel(reopenLabel)
            assertTrue(
                "Resume agent must be absent or disabled when a document requires reopen",
                harness.uiLabelEnabled(resumeLabel) != true,
            )
            assertTrue(
                "the real Reopen control must accept one ACTION_CLICK",
                harness.clickUiLabel(reopenLabel),
            )
            harness.await("Reopen button creates a fresh same-tab page", 20_000L) {
                !harness.state.requiresReopen && harness.state.pageReady && harness.state.agentOn
            }
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            assertFalse("the fresh page must not require reopen", harness.state.requiresReopen)
            assertEquals("Reopen must keep the same tab", tabBefore, harness.state.selectedTabId)
            val viewAfter = harness.currentWebView() ?: throw AssertionError("reopened view missing")
            assertTrue("Reopen must replace the invalid WebView", viewAfter !== viewBefore)
            assertTrue("Reopen must keep desktop mode", harness.state.desktopMode)
            assertEquals("Reopen must keep the desktop width", 1366, harness.state.desktopWidth)
            assertEquals("Reopen must keep the desktop height", 768, harness.state.desktopHeight)
            harness.awaitDesktopViewport(1366, 768)
            assertNotNull("the default grant must return after Reopen", harness.presenter.registry.currentGrant())
            val snapshot = harness.snapshot("document-reopened")
            assertTrue("snapshot after Reopen failed: ${snapshot.errorCode}", snapshot.ok)
            assertEquals(
                "the reopened page must be the same controlled URL",
                OpenChamberProviderFixtures.SPA_ENTER_URL,
                snapshot.data.recoveryString("url"),
            )
        } finally {
            harness.dispose()
        }
    }
}
