package io.andchamber.app.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.browser.provider.ProviderCodes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

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

private fun JsonObject.dataInt(name: String): Int? =
    this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

/**
 * Real desktop/mobile viewport contract on the production presenter + BrowserSession +
 * FixedViewportContainer. The container is configured from the real state pair, then the test reads
 * actual JS innerWidth/innerHeight; no constant is reported as a fake DOM measurement.
 */
@RunWith(AndroidJUnit4::class)
class BrowserDesktopViewportInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun viewportReport(harness: BrowserAuthorityHarness): JSONObject? {
        val view = harness.currentWebView() ?: return null
        val raw = harness.eval(view, "window.__viewportReport()") ?: return null
        val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /**
     * The same real page proves all viewport evidence: inner/visual size, a 100vw/100vh ruler, a
     * wide overflow child that must not shrink the layout, and a content mutation of the meta width
     * that must not steal the engine's desktop width. DPR is read, never invented.
     */
    private fun assertDesktopLayout(harness: BrowserAuthorityHarness, width: Int, height: Int) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 25_000L
        var lastReport: JSONObject? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val candidate = viewportReport(harness)
            lastReport = candidate
            if (candidate != null &&
                abs(candidate.optInt("innerWidth") - width) <= 2 &&
                abs(candidate.optInt("innerHeight") - height) <= 2 &&
                abs(candidate.optDouble("rulerWidth") - width) <= 2.0 &&
                abs(candidate.optDouble("rulerHeight") - height) <= 2.0 &&
                abs(candidate.optDouble("visualWidth") - width) <= 2.0 &&
                abs(candidate.optDouble("visualHeight") - height) <= 2.0 &&
                candidate.optBoolean("metaAttempted")
            ) {
                break
            }
            android.os.SystemClock.sleep(50L)
        }
        val report = lastReport ?: throw AssertionError(
            "desktop viewport report missing for ${width}x$height; ${harness.desktopDiagnostics()}",
        )
        assertTrue(
            "desktop report did not settle to ${width}x$height: $report; ${harness.desktopDiagnostics()}",
            abs(report.optInt("innerWidth") - width) <= 2 &&
                abs(report.optInt("innerHeight") - height) <= 2 &&
                abs(report.optDouble("rulerWidth") - width) <= 2.0 &&
                abs(report.optDouble("rulerHeight") - height) <= 2.0 &&
                abs(report.optDouble("visualWidth") - width) <= 2.0 &&
                abs(report.optDouble("visualHeight") - height) <= 2.0 &&
                report.optBoolean("metaAttempted"),
        )
        val dpr = report.optDouble("dpr")
        val scale = report.optDouble("visualScale")
        assertTrue("real DPR missing", dpr > 0.0)
        assertTrue("visual viewport scale missing", scale > 0.0)
        assertTrue("DPR * visualScale must be ~1, got ${dpr * scale}", abs(dpr * scale - 1.0) <= 0.03)
        assertTrue(
            "wide content must overflow without changing the layout width",
            report.optInt("scrollWidth") >= 3000,
        )
        assertTrue("desktop state must remain configured", harness.state.desktopMode)
    }

    @Test
    fun defaultAndCustomDesktopViewportsAreRealJsLayouts() {
        val harness = BrowserAuthorityHarness(instrumentation, "desktop-sizes")
        try {
            val opened = harness.openViewport(
                OpenChamberProviderFixtures.DESKTOP_VIEWPORT_URL,
                "open-desktop-default",
                "desktop",
            )
            assertTrue("desktop open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            assertEquals(1920, harness.state.desktopWidth)
            assertEquals(1080, harness.state.desktopHeight)
            assertDesktopLayout(harness, 1920, 1080)

            val custom = harness.openViewport(
                OpenChamberProviderFixtures.DESKTOP_VIEWPORT_URL,
                "open-desktop-custom",
                "desktop",
                1366,
                768,
            )
            assertTrue("custom desktop open failed: ${custom.errorCode} ${custom.message}", custom.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            assertEquals(1366, harness.state.desktopWidth)
            assertEquals(768, harness.state.desktopHeight)
            assertDesktopLayout(harness, 1366, 768)
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun agentDesktopResizeKeepsTabAndControlAtNewRealViewport() {
        val harness = BrowserAuthorityHarness(instrumentation, "desktop-resize")
        try {
            val opened = harness.openViewport(
                OpenChamberProviderFixtures.ORIGIN,
                "open-before-desktop-resize",
                "desktop",
            )
            assertTrue("desktop open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            harness.awaitDesktopViewport(1920, 1080)
            val tab = harness.state.selectedTabId
            assertNotNull(tab)

            val resized = harness.resizeDesktop(1280, 720, "resize-desktop")
            assertTrue("desktop resize failed: ${resized.errorCode} ${resized.message}", resized.ok)
            assertEquals(1280, resized.data.dataInt("desktopWidth"))
            assertEquals(720, resized.data.dataInt("desktopHeight"))
            assertEquals("resize must keep the original tab", tab, harness.state.selectedTabId)
            assertEquals(1280, harness.state.desktopWidth)
            assertEquals(720, harness.state.desktopHeight)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            harness.awaitDesktopViewport(1280, 720)

            val snapshot = harness.snapshot("snapshot-after-desktop-resize")
            assertTrue("snapshot after desktop resize failed: ${snapshot.errorCode}", snapshot.ok)
            val apply = selectorFor(snapshot.data, "Apply")
                ?: throw AssertionError("Apply missing after desktop resize")
            val clicked = harness.click(apply, "click-after-desktop-resize")
            assertTrue("click after desktop resize failed: ${clicked.errorCode}", clicked.ok)
            val view = harness.currentWebView() ?: throw AssertionError("desktop view missing")
            assertEquals(
                "\"click-count:1\"",
                harness.eval(view, "document.getElementById('count').textContent"),
            )
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun desktopLayoutAndGrantStayFixedAcrossHostPresentationResize() {
        val harness = BrowserAuthorityHarness(instrumentation, "desktop-presentation")
        try {
            val opened = harness.openViewport(
                OpenChamberProviderFixtures.ORIGIN,
                "open-desktop-presentation",
                "desktop",
                1366,
                768,
            )
            assertTrue("desktop open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            harness.awaitDesktopViewport(1366, 768)
            val desktopView = harness.currentWebView() ?: throw AssertionError("desktop view missing")
            val documentBefore = harness.eval(desktopView, "String(performance.timeOrigin)")
            assertNotNull("desktop document timeOrigin missing", documentBefore)
            val fixedSize = harness.viewSize()
            val (hostWidth, hostHeight) = harness.containerSize()
            assertTrue("host has no size", hostWidth > 1 && hostHeight > 1)

            harness.resizeHost(maxOf(240, (hostWidth * 3) / 4), maxOf(240, (hostHeight * 3) / 4))
            harness.await("desktop host resized") {
                val (width, height) = harness.containerSize()
                width in 1..(maxOf(240, (hostWidth * 3) / 4) + 1) &&
                    height in 1..(maxOf(240, (hostHeight * 3) / 4) + 1)
            }
            harness.awaitDesktopViewport(1366, 768)
            assertEquals("window presentation must not resize the raw desktop page", fixedSize, harness.viewSize())
            assertTrue("window presentation must keep the same WebView", harness.currentWebView() === desktopView)
            assertEquals(
                "window presentation must not replace the document",
                documentBefore,
                harness.eval(desktopView, "String(performance.timeOrigin)"),
            )
            assertTrue("window presentation must keep the grant", harness.state.agentOn)
            val smallSnapshot = harness.snapshot("snapshot-desktop-small-window")
            assertTrue("snapshot after small-window present failed: ${smallSnapshot.errorCode}", smallSnapshot.ok)

            harness.resizeHost(hostWidth, hostHeight)
            harness.await("desktop host restored") {
                val (width, height) = harness.containerSize()
                width >= hostWidth - 1 && height >= hostHeight - 1
            }
            harness.awaitDesktopViewport(1366, 768)
            assertEquals(fixedSize, harness.viewSize())
            assertTrue("window restore must keep the same WebView", harness.currentWebView() === desktopView)
            assertEquals(
                "window restore must keep the same document",
                documentBefore,
                harness.eval(desktopView, "String(performance.timeOrigin)"),
            )
            assertTrue(harness.state.agentOn)
            harness.awaitDefaultGrant()
            val fullSnapshot = harness.snapshot("snapshot-desktop-full-window")
            assertTrue("snapshot after full-window present failed: ${fullSnapshot.errorCode}", fullSnapshot.ok)
            assertEquals(1366, harness.state.desktopWidth)
            assertEquals(768, harness.state.desktopHeight)
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun mobileRejectsCustomDimensionsAndKeepsThePhoneViewport() {
        val harness = BrowserAuthorityHarness(instrumentation, "mobile-viewport")
        try {
            val refusedOpen = harness.openViewport(
                OpenChamberProviderFixtures.ORIGIN,
                "open-mobile-custom-dims",
                "mobile",
                1366,
                768,
            )
            assertFalse("mobile must not accept custom desktop dimensions", refusedOpen.ok)
            assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, refusedOpen.errorCode)
            assertTrue("refused mobile open must not create a tab", harness.state.tabs.isEmpty())

            val opened = harness.openViewport(OpenChamberProviderFixtures.ORIGIN, "open-mobile", "mobile")
            assertTrue("mobile open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            assertFalse("mobile page must stay mobile", harness.state.desktopMode)
            val view = harness.currentWebView() ?: throw AssertionError("mobile view missing")
            val innerWidthBefore = harness.eval(view, "String(innerWidth)")
            val innerHeightBefore = harness.eval(view, "String(innerHeight)")
            val urlBefore = harness.eval(view, "String(location.href)")
            assertNotNull(innerWidthBefore)
            assertNotNull(innerHeightBefore)
            val width = innerWidthBefore!!.trim('"').toIntOrNull()
                ?: throw AssertionError("mobile innerWidth missing")
            val height = innerHeightBefore!!.trim('"').toIntOrNull()
                ?: throw AssertionError("mobile innerHeight missing")
            assertTrue("a mobile page must keep the phone width", width != 1920 && width != 1366)
            assertTrue("a mobile page must keep the phone height", height != 1080 && height != 768)

            val refusedResize = harness.resizeViewport("mobile", 1366, 768, "resize-mobile-custom-dims")
            assertFalse("mobile resize must not accept custom desktop dimensions", refusedResize.ok)
            assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, refusedResize.errorCode)
            assertEquals("rejected mobile resize must not change the phone viewport", innerWidthBefore, harness.eval(view, "String(innerWidth)"))
            assertEquals(innerHeightBefore, harness.eval(view, "String(innerHeight)"))
            assertEquals(urlBefore, harness.eval(view, "String(location.href)"))
            assertFalse(harness.state.desktopMode)
        } finally {
            harness.dispose()
        }
    }
}
