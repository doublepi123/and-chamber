package io.andchamber.app.browser

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.MainActivity
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.ui.browser.BrowserPresentationSnapshot
import io.andchamber.app.ui.browser.BrowserViewportHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The fixed desktop canvas must be enlarged when the presentation container is larger than it.
 *
 * This uses the production [BrowserAuthorityHarness], presenter and page, and the in-memory
 * example.com fixture. It never changes the fixed canvas, CSS viewport, document or refs. The
 * presentation goes small -> large on the same WebView; the agent grant survives, snapshot refs
 * stay usable, one agent DOM/ref click reaches the page exactly once, and the production PixelCopy
 * capture is a readable PNG whose dimensions equal the enlarged presentation. After that, a person
 * takes over and one real screen-coordinate DOWN/UP adds exactly one native click without reloading
 * the document. The agent click and the native touch are separate paths and are asserted separately.
 *
 * The CSS canvas is 640x360 by default. A small test screen can pass a smaller fixed canvas:
 *
 *   -e viewportWidth 480 -e viewportHeight 270
 *
 * To mirror the landscape Pad evidence (3200x2136 physical screen, 1920x1080 desktop canvas), run
 * the same test with:
 *
 *   -e viewportWidth 1920 -e viewportHeight 1080
 *
 * MULTI_PROFILE / DOCUMENT_START_SCRIPT support is required and fails the test when absent; there
 * is no skip path. Capture is never bypassed: [BrowserAuthorityHarness.awaitCapture] uses the
 * production capture and fails when it cannot commit a valid frame.
 */
@RunWith(AndroidJUnit4::class)
class BrowserViewportUpscaleInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()

    private fun positiveArg(name: String, default: Int): Int {
        val raw = args.getString(name) ?: return default
        return raw.trim().toIntOrNull()?.takeIf { it > 0 }
            ?: throw AssertionError("-$name must be a positive integer")
    }

    private data class ApplyGeometry(
        val tag: String,
        val type: String,
        val left: Double,
        val top: Double,
        val width: Double,
        val height: Double,
    )

    private data class ViewportMetrics(
        val innerWidth: Double,
        val innerHeight: Double,
        val visualWidth: Double,
        val visualHeight: Double,
        val scale: Double,
        val offsetLeft: Double,
        val offsetTop: Double,
        val dpr: Double,
    )

    @Test
    fun largerPresentationUpscalesCanvasWithoutReloadingOrLosingControl() {
        val viewportWidth = positiveArg("viewportWidth", 640)
        val viewportHeight = positiveArg("viewportHeight", 360)
        val harness = BrowserAuthorityHarness(instrumentation, "viewport-upscale")
        try {
            val opened = harness.openViewport(
                OpenChamberProviderFixtures.ORIGIN,
                "upscale-open",
                "desktop",
                viewportWidth,
                viewportHeight,
            )
            assertTrue("desktop open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            // Readiness and the fixed native canvas are the precondition. The desktop page's CSS
            // layout is recorded below, not compared to the native size: Blink rounds it (on the
            // reported 2.75 DPR device: native 1920x1080, innerWidth 1923/innerHeight 1081).
            harness.await("desktop mode is active", 10_000L) { harness.state.desktopMode }
            harness.await("the fixed ${viewportWidth}x$viewportHeight native canvas", 10_000L) {
                harness.viewSize() == (viewportWidth to viewportHeight)
            }

            val view = harness.currentWebView()
                ?: throw AssertionError("browser view missing; ${harness.desktopDiagnostics()}")
            val (fixedWidth, fixedHeight) = harness.viewSize()
            assertTrue("the fixed canvas must exist: ${fixedWidth}x$fixedHeight", fixedWidth > 1 && fixedHeight > 1)
            assertEquals("the fixed canvas must keep the requested desktop width", viewportWidth, fixedWidth)
            assertEquals("the fixed canvas must keep the requested desktop height", viewportHeight, fixedHeight)

            val metricsBefore = readMetrics(harness, view)
            assertTrue("CSS layout width must be positive: $metricsBefore", metricsBefore.innerWidth > 0.0 && metricsBefore.innerWidth.isFinite())
            assertTrue("CSS layout height must be positive: $metricsBefore", metricsBefore.innerHeight > 0.0 && metricsBefore.innerHeight.isFinite())
            assertTrue("visual viewport width must be positive: $metricsBefore", metricsBefore.visualWidth > 0.0 && metricsBefore.visualWidth.isFinite())
            assertTrue("visual viewport height must be positive: $metricsBefore", metricsBefore.visualHeight > 0.0 && metricsBefore.visualHeight.isFinite())
            assertTrue("visual viewport scale must be positive: $metricsBefore", metricsBefore.scale > 0.0 && metricsBefore.scale.isFinite())
            assertTrue("devicePixelRatio must be positive: $metricsBefore", metricsBefore.dpr > 0.0 && metricsBefore.dpr.isFinite())
            assertTrue(
                "visual viewport offsets must be finite: $metricsBefore",
                metricsBefore.offsetLeft.isFinite() && metricsBefore.offsetTop.isFinite(),
            )
            // The desktop document-start script sets initial-scale=1/DPR; this is the CSS -> native
            // mapping used for the native touch. It is not an integer 1:1 assumption.
            assertTrue(
                "desktop visual scale * dpr must be near 1: $metricsBefore",
                abs(metricsBefore.scale * metricsBefore.dpr - 1.0) <= 0.01,
            )
            val cssWidth = metricsBefore.innerWidth
            val cssHeight = metricsBefore.innerHeight
            android.util.Log.i(
                "AndChamberUpscale",
                "css=${metricsBefore.innerWidth}x${metricsBefore.innerHeight} " +
                    "visual=${metricsBefore.visualWidth}x${metricsBefore.visualHeight} " +
                    "scale=${metricsBefore.scale} dpr=${metricsBefore.dpr} " +
                    "offsets=${metricsBefore.offsetLeft},${metricsBefore.offsetTop} " +
                    "native=${fixedWidth}x$fixedHeight",
            )
            val timeOrigin = evalString(harness, view, "String(performance.timeOrigin)")
            val viewHandleBefore = harness.state.viewHandle
            val grantBefore = harness.presenter.registry.currentGrant()
            assertNotNull("the explicit open must carry a real grant", grantBefore)
            assertFalse("the fresh page must not require reopen", harness.state.requiresReopen)

            val geometryBefore = applyGeometry(harness, view)
            assertEquals("Apply must be a real button", "BUTTON", geometryBefore.tag)
            assertEquals("Apply must keep its button type", "button", geometryBefore.type)
            assertTrue("Apply must have a positive size", geometryBefore.width > 0.0 && geometryBefore.height > 0.0)
            assertTrue("Apply must start inside the CSS viewport", geometryBefore.left >= 0.0 && geometryBefore.top >= 0.0)
            assertTrue(
                "Apply must finish inside the CSS viewport: ${geometryBefore.left}+${geometryBefore.width} vs $cssWidth",
                geometryBefore.left + geometryBefore.width <= cssWidth + 1.0,
            )
            assertTrue(
                "Apply must finish inside the CSS viewport: ${geometryBefore.top}+${geometryBefore.height} vs $cssHeight",
                geometryBefore.top + geometryBefore.height <= cssHeight + 1.0,
            )

            fun assertPresentationStable(label: String) {
                assertTrue("$label must keep agent control", harness.state.agentOn)
                assertNotNull("$label must keep the grant", harness.presenter.registry.currentGrant())
                assertEquals("$label must keep the same grant", grantBefore, harness.presenter.registry.currentGrant())
                assertFalse("$label must not require reopen", harness.state.requiresReopen)
                assertEquals("$label must keep the presented view", viewHandleBefore, harness.state.viewHandle)
                assertSame("$label must keep the same WebView", view, harness.currentWebView())
                assertEquals(
                    "$label must keep the same document",
                    timeOrigin,
                    evalString(harness, view, "String(performance.timeOrigin)"),
                )
                assertEquals("$label must keep the fixed native canvas", fixedWidth to fixedHeight, harness.viewSize())
                val metricsNow = readMetrics(harness, view)
                assertEquals(
                    "$label must keep the exact CSS layout/visual viewport/dpr; before=$metricsBefore now=$metricsNow",
                    metricsBefore,
                    metricsNow,
                )
            }

            fun assertGeometryStable(label: String) {
                val now = applyGeometry(harness, view)
                assertEquals("$label must keep the button tag", geometryBefore.tag, now.tag)
                assertEquals("$label must keep the button type", geometryBefore.type, now.type)
                assertEquals("$label must keep the button left edge", geometryBefore.left, now.left, 0.01)
                assertEquals("$label must keep the button top edge", geometryBefore.top, now.top, 0.01)
                assertEquals("$label must keep the button width", geometryBefore.width, now.width, 0.01)
                assertEquals("$label must keep the button height", geometryBefore.height, now.height, 0.01)
            }

            val (contentWidth, contentHeight) = contentSize(harness.activity)
            assertTrue(
                "the large container ${contentWidth}x$contentHeight must exceed the ${viewportWidth}x$viewportHeight " +
                    "CSS canvas; pass a smaller -e viewportWidth/-e viewportHeight on a small screen",
                contentWidth > viewportWidth && contentHeight > viewportHeight,
            )

            // Start from a container smaller than the fixed canvas, then enlarge it beyond the canvas.
            val smallWidth = maxOf(1, viewportWidth / 2)
            val smallHeight = maxOf(1, viewportHeight / 2)
            harness.resizeHost(smallWidth, smallHeight)
            harness.await("the small presentation container", 10_000L) {
                val (width, height) = harness.containerSize()
                width in 1..(smallWidth + 1) && height in 1..(smallHeight + 1)
            }
            harness.await("the downscaled presentation", 10_000L) { harness.containerScale() < 0.75f }
            assertTrue(
                "the small presentation must actually be downscaled; scale=${harness.containerScale()}",
                harness.containerScale() < 1f,
            )
            assertPresentationStable("small presentation")
            assertGeometryStable("small presentation")

            harness.resizeHost(contentWidth, contentHeight)
            harness.await("the large presentation container", 10_000L) {
                val (width, height) = harness.containerSize()
                width >= contentWidth - 1 && height >= contentHeight - 1
            }
            harness.await("the enlarged presentation", 10_000L) { harness.containerScale() > 1f }
            val scale = harness.containerScale()
            assertTrue(
                "the large container must actually enlarge the fixed canvas; " +
                    "scale=$scale container=${harness.containerSize()} canvas=${fixedWidth}x$fixedHeight",
                scale > 1f,
            )
            assertPresentationStable("large presentation")
            assertGeometryStable("large presentation")

            val captured = harness.awaitCapture("upscale-large")
            // The captured frame is the committed presentation, not getGlobalVisibleRect's outward
            // integer bbox. Sample committed after the capture and require it to equal live.
            val presentation = stableCapturePresentation(harness, view)
            val displayed = presentation.width to presentation.height
            assertTrue(
                "the displayed page must exceed the native canvas width: displayed=${displayed.first} canvas=$fixedWidth",
                displayed.first > fixedWidth,
            )
            assertTrue(
                "the displayed page must exceed the native canvas height: displayed=${displayed.second} canvas=$fixedHeight",
                displayed.second > fixedHeight,
            )
            // Production capture, including its base64-size limit: a failed or oversized image is a
            // test failure here, never a skip and never a direct PixelCopy/CaptureImage bypass.
            assertEquals(
                "capture must be a PNG",
                "image/png",
                captured.data["mime"]?.jsonPrimitive?.contentOrNull,
            )
            val base64 = captured.data["base64"]?.jsonPrimitive?.contentOrNull.orEmpty()
            assertTrue("capture must contain PNG bytes", base64.isNotEmpty())
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw AssertionError("capture is not a readable image; ${harness.desktopDiagnostics()}")
            try {
                assertTrue("captured PNG must not be empty", bitmap.width > 0 && bitmap.height > 0)
                assertTrue(
                    "captured PNG must be the enlarged page, not the native canvas: " +
                        "png=${bitmap.width}x${bitmap.height} canvas=${fixedWidth}x$fixedHeight",
                    bitmap.width > fixedWidth && bitmap.height > fixedHeight,
                )
                assertEquals(
                    "captured PNG width must equal the committed page rect: png=${bitmap.width} page=${displayed.first}",
                    displayed.first,
                    bitmap.width,
                )
                assertEquals(
                    "captured PNG height must equal the committed page rect: png=${bitmap.height} page=${displayed.second}",
                    displayed.second,
                    bitmap.height,
                )
            } finally {
                bitmap.recycle()
            }

            // A fresh snapshot on the enlarged presentation, then one click through the real ref.
            val snapshot = harness.snapshot("upscale-snapshot")
            assertTrue("snapshot on the enlarged page failed: ${snapshot.errorCode}", snapshot.ok)
            val apply = snapshotElement(snapshot.data, "Apply")
                ?: throw AssertionError("Apply is missing from the fresh snapshot; ${harness.desktopDiagnostics()}")
            assertEquals("the fresh snapshot must describe a button", "button", apply["role"]?.jsonPrimitive?.contentOrNull)
            assertEquals("the fresh snapshot must name Apply", "Apply", apply["name"]?.jsonPrimitive?.contentOrNull)
            val selector = apply["selector"]?.jsonPrimitive?.contentOrNull
                ?: throw AssertionError("Apply has no fresh snapshot selector")

            val click = harness.click(selector, "upscale-click")
            assertTrue("click on the enlarged page failed: ${click.errorCode}", click.ok)
            assertEquals(
                "the click must target the fresh Apply ref",
                selector,
                click.data["clicked"]?.jsonPrimitive?.contentOrNull,
            )
            assertEquals(
                "the page must count exactly one click",
                "\"click-count:1\"",
                harness.eval(view, "document.getElementById('count').textContent"),
            )
            assertEquals(
                "the page's own click counter must be exactly one",
                "\"1\"",
                harness.eval(view, "String(window.clicks)"),
            )
            val afterClick = harness.snapshot("upscale-after-click")
            assertTrue("snapshot after the click failed: ${afterClick.errorCode}", afterClick.ok)
            assertEquals(
                "a later snapshot must not replay the click",
                "\"click-count:1\"",
                harness.eval(view, "document.getElementById('count').textContent"),
            )

            // Everything above proves the presentation and the agent's DOM/ref click path. A DOM
            // `nodes.get(ref).click()` never exercises Android's screen-coordinate touch mapping.
            // A person now takes the page over (presentation does not change) and taps Apply with a
            // real injected DOWN/UP. This is the native touch path; no DOM click or JS event is used.
            harness.takeover()
            assertFalse("takeover must revoke agent control", harness.state.agentOn)
            assertEquals("takeover must select manual mode", ProviderMode.MANUAL, harness.state.mode)
            assertNull("takeover must clear the grant", harness.presenter.registry.currentGrant())
            harness.await("takeover keeps the enlarged page presented", 10_000L) {
                harness.currentWebView() === view &&
                    harness.containerScale() > 1f &&
                    harness.viewSize() == (fixedWidth to fixedHeight)
            }
            assertEquals(
                "takeover must not reload the document",
                timeOrigin,
                evalString(harness, view, "String(performance.timeOrigin)"),
            )
            assertEquals("takeover must not replace the view", viewHandleBefore, harness.state.viewHandle)
            assertSame("takeover must keep the same WebView", view, harness.currentWebView())
            val metricsForTouch = readMetrics(harness, view)
            assertEquals(
                "takeover must keep the exact CSS layout/visual viewport/dpr; before=$metricsBefore now=$metricsForTouch",
                metricsBefore,
                metricsForTouch,
            )
            assertEquals(
                "the agent DOM click must still be the only click before the native tap",
                "\"click-count:1\"",
                harness.eval(view, "document.getElementById('count').textContent"),
            )

            // A real screen touch maps from the CSS Apply rect through the CSS visual viewport and
            // the presentation transform. Do not assume an integer CSS/native 1:1: Blink rounds the
            // layout viewport by a few px (on the reported 2.75 DPR device, native 1920x1080 but
            // innerWidth 1923/innerHeight 1081). The mapping used is:
            //   local = (cssCenter - visualViewport.offset) * visualViewport.scale * devicePixelRatio
            //   screen = getLocationOnScreen + local * view.scaleX/Y
            harness.await("the browser window must have focus for a real touch", 5_000L) {
                var focused = false
                instrumentation.runOnMainSync { focused = view.hasWindowFocus() }
                focused
            }
            val nativeGeometry = applyGeometry(harness, view)
            assertEquals("takeover must not move Apply horizontally", geometryBefore.left, nativeGeometry.left, 0.01)
            assertEquals("takeover must not move Apply vertically", geometryBefore.top, nativeGeometry.top, 0.01)
            assertEquals("takeover must not resize Apply", geometryBefore.width, nativeGeometry.width, 0.01)
            assertEquals("takeover must not change Apply height", geometryBefore.height, nativeGeometry.height, 0.01)
            assertEquals("the native target must stay a button", geometryBefore.type, nativeGeometry.type)

            val cssCenterX = nativeGeometry.left + nativeGeometry.width / 2.0
            val cssCenterY = nativeGeometry.top + nativeGeometry.height / 2.0
            val localX = (cssCenterX - metricsForTouch.offsetLeft) * metricsForTouch.scale * metricsForTouch.dpr
            val localY = (cssCenterY - metricsForTouch.offsetTop) * metricsForTouch.scale * metricsForTouch.dpr
            assertTrue(
                "the mapped Apply x=$localX must be inside the ${fixedWidth}px native canvas; metrics=$metricsForTouch",
                localX >= 0.0 && localX <= fixedWidth.toDouble(),
            )
            assertTrue(
                "the mapped Apply y=$localY must be inside the ${fixedHeight}px native canvas; metrics=$metricsForTouch",
                localY >= 0.0 && localY <= fixedHeight.toDouble(),
            )

            var viewLocation = IntArray(2)
            var viewScaleX = 0f
            var viewScaleY = 0f
            instrumentation.runOnMainSync {
                view.getLocationOnScreen(viewLocation)
                viewScaleX = view.scaleX
                viewScaleY = view.scaleY
            }
            assertTrue(
                "the native page must actually be enlarged: scaleX=$viewScaleX scaleY=$viewScaleY",
                viewScaleX > 1f && viewScaleY > 1f,
            )
            assertTrue(
                "the presentation scale must stay uniform: scaleX=$viewScaleX scaleY=$viewScaleY",
                abs(viewScaleX - viewScaleY) < 0.01f,
            )
            val screenX = viewLocation[0] + (localX * viewScaleX).toFloat()
            val screenY = viewLocation[1] + (localY * viewScaleY).toFloat()
            val metrics = harness.activity.resources.displayMetrics
            assertTrue(
                "the Apply screen x=$screenX must be on the ${metrics.widthPixels}px screen",
                screenX >= 0f && screenX < metrics.widthPixels,
            )
            assertTrue(
                "the Apply screen y=$screenY must be on the ${metrics.heightPixels}px screen",
                screenY >= 0f && screenY < metrics.heightPixels,
            )

            val downTime = SystemClock.uptimeMillis()
            sendPointer(MotionEvent.ACTION_DOWN, screenX, screenY, downTime, downTime)
            sendPointer(
                MotionEvent.ACTION_UP,
                screenX,
                screenY,
                maxOf(SystemClock.uptimeMillis(), downTime + 1),
                downTime,
            )
            harness.await("the real native Apply tap", 5_000L) {
                harness.eval(view, "document.getElementById('count').textContent") == "\"click-count:2\""
            }
            assertEquals(
                "one real native tap must add exactly one click",
                "\"2\"",
                harness.eval(view, "String(window.clicks)"),
            )
            assertEquals(
                "the native tap must not reload the document",
                timeOrigin,
                evalString(harness, view, "String(performance.timeOrigin)"),
            )
            assertEquals("the native tap must not replace the view", viewHandleBefore, harness.state.viewHandle)
            assertSame("the native tap must keep the same WebView", view, harness.currentWebView())
        } finally {
            harness.dispose()
        }
    }

    private data class PresentationSize(
        val width: Int,
        val height: Int,
        val committed: BrowserPresentationSnapshot,
        val live: BrowserPresentationSnapshot,
    )

    /**
     * The production capture's exact integer page rect, derived like BrowserCaptureGeometry:
     * ceil(left/top), floor(right/bottom), from the committed presentation. The live snapshot must
     * still equal it, so a stale rect can never be used for the PNG assertion.
     */
    private fun stableCapturePresentation(
        harness: BrowserAuthorityHarness,
        view: WebView,
    ): PresentationSize {
        fun sample(): PresentationSize? {
            var committed: BrowserPresentationSnapshot? = null
            var live: BrowserPresentationSnapshot? = null
            instrumentation.runOnMainSync {
                val host = view.parent as? BrowserViewportHost ?: return@runOnMainSync
                committed = host.committedPresentationSnapshot()
                live = host.presentationSnapshot()
            }
            val c = committed ?: return null
            val l = live ?: return null
            val left = ceil(c.pageLeft.toDouble()).toInt()
            val top = ceil(c.pageTop.toDouble()).toInt()
            val right = floor((c.pageLeft + c.pageWidth).toDouble()).toInt()
            val bottom = floor((c.pageTop + c.pageHeight).toDouble()).toInt()
            return PresentationSize((right - left).coerceAtLeast(0), (bottom - top).coerceAtLeast(0), c, l)
        }
        harness.await("the committed capture presentation equals live", 5_000L) {
            val current = sample()
            current != null && current.committed == current.live
        }
        val stable = sample() ?: throw AssertionError("no committed presentation after capture")
        assertEquals(
            "the captured presentation must still equal the live presentation",
            stable.committed,
            stable.live,
        )
        return stable
    }

    /** A real screen-coordinate touch, never a direct View.dispatchTouchEvent with local coordinates. */
    private fun sendPointer(action: Int, x: Float, y: Float, eventTime: Long, downTime: Long) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    private fun contentSize(activity: MainActivity): Pair<Int, Int> {
        var result = 0 to 0
        instrumentation.runOnMainSync {
            val content = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            result = content.width to content.height
        }
        return result
    }

    private fun evalString(harness: BrowserAuthorityHarness, view: WebView, script: String): String {
        val raw = harness.eval(view, script) ?: throw AssertionError("script result missing")
        return JSONTokener(raw).nextValue() as? String
            ?: throw AssertionError("script result is not a string")
    }

    private fun applyGeometry(harness: BrowserAuthorityHarness, view: WebView): ApplyGeometry {
        fun value(property: String): Double {
            val raw = evalString(
                harness,
                view,
                "String(document.getElementById('apply').getBoundingClientRect().$property)",
            )
            return raw.toDoubleOrNull() ?: throw AssertionError("Apply $property is not numeric: $raw")
        }
        return ApplyGeometry(
            tag = evalString(harness, view, "String(document.getElementById('apply').tagName)"),
            type = evalString(harness, view, "String(document.getElementById('apply').getAttribute('type'))"),
            left = value("left"),
            top = value("top"),
            width = value("width"),
            height = value("height"),
        )
    }

    private fun readMetrics(harness: BrowserAuthorityHarness, view: WebView): ViewportMetrics {
        val script = """
            (function(){
              var v = window.visualViewport || {};
              return JSON.stringify({
                innerWidth: innerWidth,
                innerHeight: innerHeight,
                visualWidth: v.width || 0,
                visualHeight: v.height || 0,
                scale: v.scale || 0,
                offsetLeft: v.offsetLeft || 0,
                offsetTop: v.offsetTop || 0,
                dpr: devicePixelRatio
              });
            })()
        """.trimIndent()
        val raw = harness.eval(view, script) ?: throw AssertionError("viewport metrics missing")
        val jsonText = JSONTokener(raw).nextValue() as? String
            ?: throw AssertionError("viewport metrics are not a JSON string")
        val obj = runCatching { Json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
            ?: throw AssertionError("viewport metrics are not a JSON object")
        fun number(name: String): Double =
            obj[name]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: throw AssertionError("viewport metric $name is missing")
        return ViewportMetrics(
            innerWidth = number("innerWidth"),
            innerHeight = number("innerHeight"),
            visualWidth = number("visualWidth"),
            visualHeight = number("visualHeight"),
            scale = number("scale"),
            offsetLeft = number("offsetLeft"),
            offsetTop = number("offsetTop"),
            dpr = number("dpr"),
        )
    }

    private fun snapshotElement(data: JsonObject, name: String): JsonObject? {
        val elements = data["elements"]?.jsonArray ?: return null
        for (element in elements) {
            val item = element.jsonObject
            if (item["name"]?.jsonPrimitive?.contentOrNull == name) return item
        }
        return null
    }
}
