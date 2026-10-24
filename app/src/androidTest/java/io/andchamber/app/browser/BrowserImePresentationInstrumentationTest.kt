package io.andchamber.app.browser

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.MainActivity
import io.andchamber.app.R
import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.ui.browser.BrowserCaptureGeometry
import io.andchamber.app.ui.browser.BrowserFloatPage
import io.andchamber.app.ui.browser.BrowserPresentationSnapshot
import io.andchamber.app.ui.browser.BrowserViewportHost
import io.andchamber.app.ui.browser.toPresentationGeometry
import io.andchamber.app.ui.theme.AndChamberTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
import kotlin.math.roundToInt

/**
 * Real IME presentation regression for the production Compose browser page.
 *
 * The page is opened through the production presenter. The test mounts the real
 * [io.andchamber.app.ui.browser.BrowserFloatPage] in a test-owned [ComposeView] whose parent applies
 * the same insets as WebScreen (system bars, cutout and the IME target). It is put in full screen
 * through the production "Full screen" control, so this is the Compose layout.imeOpen path, not a
 * resizeHost imitation. The harness's showProductionBrowserUi is not used because it lacks that
 * inset parent; mounting it too would double-mount the page.
 *
 * A real native touch focuses a test input added below the fold in the example.com fixture. The
 * test waits for WindowInsetsCompat.Type.ime() to be visible (the device must have
 * show_ime_with_hard_keyboard=1 and a focused window), then checks that:
 * - the keyboard-closed presentation scale did not shrink; the native canvas, CSS layout and
 *   timeOrigin are unchanged, and the grant is not revoked or replaced (an IME is not a takeover);
 * - the focused input is visible above the real keyboard;
 * - while the page is clipped, production capture fails closed (page unavailable, not permission
 *   denied), and it succeeds again after the keyboard closes;
 * - closing the keyboard restores scale, position and visual metrics.
 *
 * A final explicit takeover is a separate person action, not an IME effect. No production API is
 * added and no insets are mocked; a missing IME is a test failure, never a skip.
 *
 * This is a behavior test for the production IME presentation change. It does not call a new
 * keyboard API; until that production change is present, the scale/capture assertions are expected
 * to fail rather than being weakened or skipped.
 */
@RunWith(AndroidJUnit4::class)
class BrowserImePresentationInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

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

    private data class ViewFrame(
        val locationX: Int,
        val locationY: Int,
        val scaleX: Float,
        val scaleY: Float,
    )

    private data class CssRect(
        val left: Double,
        val top: Double,
        val width: Double,
        val height: Double,
    )

    private data class ScreenRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    private data class ImeState(
        val visible: Boolean,
        val bottom: Int,
        val rootBottom: Int,
    ) {
        val top: Int get() = rootBottom - bottom
    }

    @Test
    fun keyboardOpenDoesNotShrinkTheFullScreenPageAndCaptureFailsClosed() {
        val harness = BrowserAuthorityHarness(instrumentation, "ime-presentation")
        var productionUi: ComposeView? = null
        try {
            val opened = harness.openViewport(
                OpenChamberProviderFixtures.ORIGIN,
                "ime-open",
                "desktop",
                1440,
                2160,
            )
            assertTrue("desktop open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            val viewBeforeUi = harness.currentWebView() ?: throw AssertionError("browser view missing before the Compose mount")
            val timeOriginBeforeUi = evalString(harness, viewBeforeUi, "String(performance.timeOrigin)")

            // A previous failed run may have left the system keyboard visible. The reference must
            // come from a genuinely keyboard-closed layout, not an already shortened window.
            hideIme(viewBeforeUi)
            harness.await("the initial IME is hidden", 15_000L) {
                val ime = imeState(harness.activity)
                !ime.visible && ime.bottom == 0
            }

            productionUi = mountInsetParentBrowserUi(harness)
            ensureFullScreen(harness)
            val view = harness.currentWebView()
                ?: throw AssertionError("browser view missing after the production Compose mount")
            harness.await("the production page is mounted full screen", 10_000L) {
                var attached = false
                instrumentation.runOnMainSync { attached = view.isAttachedToWindow && view.isShown && view.width > 1 }
                attached && displayedWidth(view) >= contentWidth(harness.activity) * 0.9
            }
            assertSame("the production Compose mount must keep the same WebView", viewBeforeUi, view)
            assertEquals(
                "the production Compose mount must not reload the document",
                timeOriginBeforeUi,
                evalString(harness, view, "String(performance.timeOrigin)"),
            )
            assertEquals("the fixed native canvas must not change", 1440, view.width)
            assertEquals("the fixed native canvas must not change", 2160, view.height)
            assertTrue("the page must be in desktop mode", harness.state.desktopMode)
            assertFalse("the fresh page must not require reopen", harness.state.requiresReopen)

            val witnessBefore = evalString(harness, view, "String((window.__acWitness||{}).documentId||'')")
            assertTrue("the document witness must exist", witnessBefore.isNotBlank())
            val timeOriginBeforeInput = evalString(harness, view, "String(performance.timeOrigin)")
            addImeProbe(harness, view, topCssPx = 700.0)
            assertEquals(
                "adding the probe input must not change the document witness",
                witnessBefore,
                evalString(harness, view, "String((window.__acWitness||{}).documentId||'')"),
            )
            assertEquals(
                "adding the probe input must not reload the document",
                timeOriginBeforeInput,
                evalString(harness, view, "String(performance.timeOrigin)"),
            )

            // Baseline is the page immediately before the IME, after the test-only DOM probe exists.
            val metricsBefore = readMetrics(harness, view)
            assertTrue("CSS layout metrics must be usable: $metricsBefore", usable(metricsBefore))
            val frameBefore = viewFrame(view)
            assertTrue("the full-screen page must be laid out: scaleX=${frameBefore.scaleX}", frameBefore.scaleX > 0f)
            val timeOrigin = evalString(harness, view, "String(performance.timeOrigin)")
            val viewHandleBefore = harness.state.viewHandle
            val grantBefore = harness.presenter.registry.currentGrant()
            assertNotNull("the explicit open must carry a grant", grantBefore)
            assertTrue("the page must stay under agent control before the IME", harness.state.agentOn)
            android.util.Log.i(
                "AndChamberIme",
                "baseline scaleX=${frameBefore.scaleX} scaleY=${frameBefore.scaleY} " +
                    "pos=${frameBefore.locationX},${frameBefore.locationY} native=${view.width}x${view.height} " +
                    "css=${metricsBefore.innerWidth}x${metricsBefore.innerHeight} " +
                    "visual=${metricsBefore.visualWidth}x${metricsBefore.visualHeight} " +
                    "dpr=${metricsBefore.dpr}",
            )

            val inputBefore = cssRect(harness, view, IME_PROBE_ID)
            val inputScreenBefore = screenRectForCss(harness, view, metricsBefore, inputBefore)
            val visibleBefore = globalVisibleRect(view)
            assertTrue(
                "the probe input must be visible before the IME; rect=$inputScreenBefore visible=$visibleBefore",
                visibleBefore.contains(inputScreenBefore.centerX.roundToInt(), inputScreenBefore.centerY.roundToInt()),
            )

            // Prove production capture works before the IME; do not assume the failure is IME-only.
            val baselineAttempt = runCatching { harness.awaitCapture("ime-baseline-capture") }
            val baselineCapture = baselineAttempt.getOrNull()
            if (baselineCapture == null || !baselineCapture.ok) {
                val diagnostic = captureDiagnostic(view)
                android.util.Log.i("AndChamberIme", "baselineCaptureFailed ${baselineAttempt.exceptionOrNull()?.message} $diagnostic")
                throw AssertionError("baseline production capture must succeed before the IME; $diagnostic")
            }
            android.util.Log.i(
                "AndChamberIme",
                "baselineCapture ok=${baselineCapture.ok} mime=${baselineCapture.data["mime"]?.jsonPrimitive?.contentOrNull} " +
                    captureDiagnostic(view),
            )

            // This is a real screen touch. It focuses the input and lets the system IME open.
            harness.await("the browser window is focused for the native focus tap", 5_000L) {
                var focused = false
                instrumentation.runOnMainSync { focused = view.hasWindowFocus() }
                focused
            }
            val downTime = SystemClock.uptimeMillis()
            sendPointer(
                MotionEvent.ACTION_DOWN,
                inputScreenBefore.centerX,
                inputScreenBefore.centerY,
                downTime,
                downTime,
            )
            sendPointer(
                MotionEvent.ACTION_UP,
                inputScreenBefore.centerX,
                inputScreenBefore.centerY,
                maxOf(SystemClock.uptimeMillis(), downTime + 1),
                downTime,
            )

            try {
                harness.await("the real IME becomes visible", 15_000L) {
                    val ime = imeState(harness.activity)
                    ime.visible && ime.bottom > 0 && activeElementId(harness, view) == IME_PROBE_ID
                }
            } catch (error: AssertionError) {
                throw AssertionError(
                    "the real IME did not become visible; set show_ime_with_hard_keyboard=1 and keep the " +
                        "window focused. ${error.message}",
                )
            }
            harness.await("the focused probe input above the keyboard", 15_000L) {
                inputAboveKeyboard(harness, view, imeState(harness.activity))
            }

            val imeOpen = imeState(harness.activity)
            val metricsOpen = readMetrics(harness, view)
            val frameOpen = viewFrame(view)
            val visibleOpen = presentationVisibleRect(view)
            val imeTop = imeOpen.top
            android.util.Log.i(
                "AndChamberIme",
                "open scaleX=${frameOpen.scaleX} scaleY=${frameOpen.scaleY} " +
                    "pos=${frameOpen.locationX},${frameOpen.locationY} native=${view.width}x${view.height} " +
                    "css=${metricsOpen.innerWidth}x${metricsOpen.innerHeight} " +
                    "visual=${metricsOpen.visualWidth}x${metricsOpen.visualHeight} " +
                    "imeTop=$imeTop imeBottom=${imeOpen.bottom} visible=$visibleOpen " +
                    "displayed=${view.width * frameOpen.scaleX}x${view.height * frameOpen.scaleY}",
            )

            // IME presentation: no scale shrink, no native/CSS canvas change, no reload or new grant.
            assertTrue(
                "opening the IME must not shrink the page: before=${frameBefore.scaleX} " +
                    "after=${frameOpen.scaleX}",
                abs(frameOpen.scaleX - frameBefore.scaleX) <= 0.001f,
            )
            assertTrue(
                "opening the IME must not shrink the height scale: before=${frameBefore.scaleY} " +
                    "after=${frameOpen.scaleY}",
                abs(frameOpen.scaleY - frameBefore.scaleY) <= 0.001f,
            )
            assertTrue("the page scale must not grow with the IME", frameOpen.scaleX <= frameBefore.scaleX + 0.001f)
            assertEquals("the native canvas width must not change with the IME", 1440, view.width)
            assertEquals("the native canvas height must not change with the IME", 2160, view.height)
            assertTrue(
                "the displayed screen width must not shrink with the IME: " +
                    "before=${1920 * frameBefore.scaleX} after=${1920 * frameOpen.scaleX}",
                abs(1920 * frameOpen.scaleX - 1920 * frameBefore.scaleX) <= 1f,
            )
            assertTrue(
                "the page must not jump horizontally with the IME: before=${frameBefore.locationX} " +
                    "after=${frameOpen.locationX}",
                abs(frameOpen.locationX - frameBefore.locationX) <= 1,
            )
            assertEquals("the IME must not change CSS innerWidth", metricsBefore.innerWidth, metricsOpen.innerWidth, 0.0)
            assertEquals("the IME must not change CSS innerHeight", metricsBefore.innerHeight, metricsOpen.innerHeight, 0.0)
            assertEquals("the IME must not change visual viewport width", metricsBefore.visualWidth, metricsOpen.visualWidth, 0.0)
            assertEquals("the IME must not change visual viewport height", metricsBefore.visualHeight, metricsOpen.visualHeight, 0.0)
            assertEquals("the IME must not change visual scale", metricsBefore.scale, metricsOpen.scale, 0.0)
            assertEquals("the IME must not change dpr", metricsBefore.dpr, metricsOpen.dpr, 0.0)
            assertEquals(
                "the IME must not reload the document",
                timeOrigin,
                evalString(harness, view, "String(performance.timeOrigin)"),
            )
            assertEquals("the IME must not replace the view", viewHandleBefore, harness.state.viewHandle)
            assertSame("the IME must keep the same WebView", view, harness.currentWebView())
            assertTrue("the IME must not revoke agent control", harness.state.agentOn)
            assertEquals(
                "the IME must not replace the grant",
                grantBefore,
                harness.presenter.registry.currentGrant(),
            )
            assertFalse("the IME must not require reopen", harness.state.requiresReopen)

            val inputOpen = cssRect(harness, view, IME_PROBE_ID)
            val inputScreenOpen = screenRectForCss(harness, view, metricsOpen, inputOpen)
            assertTrue(
                "the focused input must be above the IME: input=${inputScreenOpen} imeTop=$imeTop",
                inputScreenOpen.centerY < imeTop,
            )
            assertTrue(
                "the focused input must be the active element",
                activeElementId(harness, view) == IME_PROBE_ID,
            )
            assertTrue(
                "the focused input must be inside the visible clipped page: " +
                    "input=$inputScreenOpen visible=$visibleOpen",
                visibleOpen.contains(inputScreenOpen.centerX.roundToInt(), inputScreenOpen.centerY.roundToInt()),
            )
            assertTrue(
                "the page must actually be clipped while the IME is open: " +
                    "visible=${visibleOpen.height()} displayed=${view.height * frameOpen.scaleY}",
                visibleOpen.height() < view.height * frameOpen.scaleY - 2f,
            )
            assertTrue(
                "the visible page must stop at the keyboard: visibleBottom=${visibleOpen.bottom} imeTop=$imeTop",
                visibleOpen.bottom <= imeTop + 2,
            )

            // Production capture must refuse the clipped page. The grant is still present, so this
            // cannot be an AUTHORIZATION_REQUIRED refusal masquerading as clipping.
            val clippedCapture = harness.capture("ime-clipped-capture")
            assertFalse("capture must fail closed while the page is clipped", clippedCapture.ok)
            assertTrue(
                "a clipped capture must not be reported as a permission denial",
                clippedCapture.errorCode != ProviderCodes.AUTHORIZATION_REQUIRED,
            )
            assertEquals(
                "a clipped capture must fail as page-unavailable",
                ProviderCodes.BROWSER_PAGE_UNAVAILABLE,
                clippedCapture.errorCode,
            )
            assertFalse("a clipped capture must not require reopen", harness.state.requiresReopen)
            android.util.Log.i(
                "AndChamberIme",
                "clippedCaptureDiagnostic code=${clippedCapture.errorCode} " + captureDiagnostic(view),
            )
            android.util.Log.i(
                "AndChamberIme",
                "clippedCapture ok=${clippedCapture.ok} code=${clippedCapture.errorCode} " +
                    "visible=$visibleOpen imeTop=$imeTop",
            )

            hideIme(view)
            harness.await("the IME is hidden", 15_000L) {
                val ime = imeState(harness.activity)
                !ime.visible && ime.bottom == 0
            }
            harness.await("IME close restores the exact presentation", 15_000L) {
                val frame = viewFrame(view)
                val metrics = readMetrics(harness, view)
                abs(frame.scaleX - frameBefore.scaleX) <= 0.001f &&
                    abs(frame.scaleY - frameBefore.scaleY) <= 0.001f &&
                    abs(frame.locationX - frameBefore.locationX) <= 1 &&
                    abs(frame.locationY - frameBefore.locationY) <= 1 &&
                    metrics == metricsBefore
            }
            val frameClosed = viewFrame(view)
            val metricsClosed = readMetrics(harness, view)
            assertEquals("closing the IME must restore the page scale", frameBefore.scaleX, frameClosed.scaleX, 0.001f)
            assertEquals("closing the IME must restore the page scale", frameBefore.scaleY, frameClosed.scaleY, 0.001f)
            assertTrue(
                "closing the IME must restore the page x: before=${frameBefore.locationX} after=${frameClosed.locationX}",
                abs(frameClosed.locationX - frameBefore.locationX) <= 1,
            )
            assertTrue(
                "closing the IME must restore the page y: before=${frameBefore.locationY} after=${frameClosed.locationY}",
                abs(frameClosed.locationY - frameBefore.locationY) <= 1,
            )
            assertEquals("closing the IME must restore the visual metrics", metricsBefore, metricsClosed)
            android.util.Log.i(
                "AndChamberIme",
                "closed scaleX=${frameClosed.scaleX} scaleY=${frameClosed.scaleY} " +
                    "pos=${frameClosed.locationX},${frameClosed.locationY} " +
                    "css=${metricsClosed.innerWidth}x${metricsClosed.innerHeight} " +
                    "visual=${metricsClosed.visualWidth}x${metricsClosed.visualHeight}",
            )
            assertEquals(
                "closing the IME must not reload the document",
                timeOrigin,
                evalString(harness, view, "String(performance.timeOrigin)"),
            )
            assertTrue("closing the IME must keep the grant", harness.state.agentOn)
            assertEquals(
                "closing the IME must keep the same grant",
                grantBefore,
                harness.presenter.registry.currentGrant(),
            )
            assertFalse("closing the IME must not require reopen", harness.state.requiresReopen)

            android.util.Log.i("AndChamberIme", "restoredBefore " + captureDiagnostic(view))
            val restoredAttempt = runCatching { harness.awaitCapture("ime-capture-restored") }
            val restored = restoredAttempt.getOrNull()
            if (restored == null || !restored.ok) {
                val reason = restoredAttempt.exceptionOrNull()?.message ?: restored?.errorCode ?: "no-result"
                val diagnostic = captureDiagnostic(view)
                android.util.Log.i("AndChamberIme", "restoredFailed reason=$reason $diagnostic")
                throw AssertionError("IME-close production capture did not recover: $reason; $diagnostic")
            }
            val restoredPresentation = stableCapturePresentation(harness, view)
            val displayedWidth = restoredPresentation.width
            val displayedHeight = restoredPresentation.height
            android.util.Log.i(
                "AndChamberIme",
                "restoredCapture page=${displayedWidth}x$displayedHeight " +
                    "committed=${restoredPresentation.committed == restoredPresentation.live}",
            )
            assertEquals("restored capture must be a PNG", "image/png", restored.data["mime"]?.jsonPrimitive?.contentOrNull)
            val base64 = restored.data["base64"]?.jsonPrimitive?.contentOrNull.orEmpty()
            assertTrue("restored capture must contain PNG bytes", base64.isNotEmpty())
            val imageBytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: throw AssertionError("restored capture is not a readable image")
            try {
                assertEquals(
                    "restored capture width must equal the committed page rect: " +
                        "png=${bitmap.width} page=$displayedWidth",
                    displayedWidth,
                    bitmap.width,
                )
                assertEquals(
                    "restored capture height must equal the committed page rect: " +
                        "png=${bitmap.height} page=$displayedHeight",
                    displayedHeight,
                    bitmap.height,
                )
            } finally {
                bitmap.recycle()
            }

            // Separate control: this is the person's explicit takeover, not the IME. Its revoke must
            // not be attributed to the keyboard.
            harness.takeover()
            assertFalse("an explicit takeover must revoke agent control", harness.state.agentOn)
            assertEquals("an explicit takeover must select manual mode", ProviderMode.MANUAL, harness.state.mode)
            assertNull("an explicit takeover must clear the grant", harness.presenter.registry.currentGrant())
        } finally {
            try {
                productionUi?.let { ui ->
                    instrumentation.runOnMainSync {
                        try {
                            ui.disposeComposition()
                        } finally {
                            (ui.parent as? ViewGroup)?.removeView(ui)
                        }
                    }
                }
            } finally {
                harness.dispose()
            }
        }
    }

    /**
     * The real BrowserFloatPage, mounted with WebScreen's inset parent. The harness's
     * showProductionBrowserUi omits this parent, so its maxHeight already includes the IME and the
     * page cannot reproduce the production shrink. This is the same reparenting, no production API.
     */
    @OptIn(ExperimentalLayoutApi::class)
    private fun mountInsetParentBrowserUi(harness: BrowserAuthorityHarness): ComposeView {
        lateinit var compose: ComposeView
        instrumentation.runOnMainSync {
            val content = harness.activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                ?: error("activity content view missing")
            compose = ComposeView(harness.activity)
            compose.setContent {
                AndChamberTheme {
                    val pageInsets = WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .union(WindowInsets.imeAnimationTarget)
                    Box(Modifier.fillMaxSize().windowInsetsPadding(pageInsets)) {
                        BrowserFloatPage(harness.presenter)
                    }
                }
            }
            content.addView(
                compose,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        return compose
    }

    private fun ensureFullScreen(harness: BrowserAuthorityHarness) {
        val enlarge = harness.activity.getString(R.string.browser_enlarge)
        val restore = harness.activity.getString(R.string.browser_restore)
        val enlargeControl = runCatching { harness.awaitUiLabel(enlarge, 5_000L) }.getOrNull()
        if (enlargeControl != null) {
            assertTrue("the production Full screen control must accept one click", harness.clickUiLabel(enlarge))
            harness.awaitUiLabel(restore, 10_000L)
        }
        // If auto-full on a short screen, the control is hidden; the displayed-width check below
        // still proves the page is not a small window.
    }

    private fun addImeProbe(harness: BrowserAuthorityHarness, view: WebView, topCssPx: Double) {
        val script = """
            (function(){
              var old = document.getElementById('$IME_PROBE_ID');
              if (old) old.remove();
              var input = document.createElement('input');
              input.id = '$IME_PROBE_ID';
              input.type = 'text';
              input.setAttribute('aria-label', 'IME probe');
              input.setAttribute('autocomplete', 'off');
              input.style.position = 'absolute';
              input.style.left = '40px';
              input.style.top = '${topCssPx}px';
              input.style.width = '320px';
              input.style.height = '56px';
              input.style.fontSize = '20px';
              input.style.background = '#fff';
              input.style.zIndex = '2147483647';
              document.body.appendChild(input);
              return 'added';
            })()
        """.trimIndent()
        val result = harness.eval(view, script) ?: throw AssertionError("IME probe input was not added")
        if (result.trim('"') != "added") throw AssertionError("IME probe input was not added: $result")
    }

    private fun inputAboveKeyboard(
        harness: BrowserAuthorityHarness,
        view: WebView,
        ime: ImeState,
    ): Boolean {
        if (!ime.visible || ime.bottom <= 0) return false
        if (activeElementId(harness, view) != IME_PROBE_ID) return false
        val metrics = readMetrics(harness, view)
        val rect = cssRect(harness, view, IME_PROBE_ID)
        val screen = screenRectForCss(harness, view, metrics, rect)
        val visible = globalVisibleRect(view)
        return screen.centerY < ime.top &&
            visible.contains(screen.centerX.roundToInt(), screen.centerY.roundToInt())
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

    private fun usable(metrics: ViewportMetrics): Boolean =
        metrics.innerWidth > 0 && metrics.innerWidth.isFinite() &&
            metrics.innerHeight > 0 && metrics.innerHeight.isFinite() &&
            metrics.visualWidth > 0 && metrics.visualHeight > 0 &&
            metrics.scale > 0 && metrics.scale.isFinite() &&
            metrics.dpr > 0 && metrics.dpr.isFinite() &&
            metrics.offsetLeft.isFinite() && metrics.offsetTop.isFinite()

    private fun cssRect(harness: BrowserAuthorityHarness, view: WebView, id: String): CssRect {
        val script = """
            (function(){
              var e = document.getElementById('$id');
              if (!e) return JSON.stringify(null);
              var r = e.getBoundingClientRect();
              return JSON.stringify({left:r.left, top:r.top, width:r.width, height:r.height});
            })()
        """.trimIndent()
        val raw = harness.eval(view, script) ?: throw AssertionError("$id rect missing")
        val jsonText = JSONTokener(raw).nextValue() as? String
            ?: throw AssertionError("$id rect is not a JSON string")
        val value = Json.parseToJsonElement(jsonText)
        val obj = value as? JsonObject ?: throw AssertionError("$id not found")
        fun number(name: String): Double =
            obj[name]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: throw AssertionError("$id $name is missing")
        return CssRect(number("left"), number("top"), number("width"), number("height"))
    }

    private fun activeElementId(harness: BrowserAuthorityHarness, view: WebView): String =
        evalString(harness, view, "String((document.activeElement||{}).id||'')")

    private fun viewFrame(view: WebView): ViewFrame {
        var location = IntArray(2)
        var scaleX = 0f
        var scaleY = 0f
        instrumentation.runOnMainSync {
            view.getLocationOnScreen(location)
            scaleX = view.scaleX
            scaleY = view.scaleY
        }
        return ViewFrame(location[0], location[1], scaleX, scaleY)
    }

    private fun screenRectForCss(
        harness: BrowserAuthorityHarness,
        view: WebView,
        metrics: ViewportMetrics,
        rect: CssRect,
    ): ScreenRect {
        val localLeft = (rect.left - metrics.offsetLeft) * metrics.scale * metrics.dpr
        val localTop = (rect.top - metrics.offsetTop) * metrics.scale * metrics.dpr
        val localRight = (rect.left + rect.width - metrics.offsetLeft) * metrics.scale * metrics.dpr
        val localBottom = (rect.top + rect.height - metrics.offsetTop) * metrics.scale * metrics.dpr
        val frame = viewFrame(view)
        return ScreenRect(
            frame.locationX + (localLeft * frame.scaleX).toFloat(),
            frame.locationY + (localTop * frame.scaleY).toFloat(),
            frame.locationX + (localRight * frame.scaleX).toFloat(),
            frame.locationY + (localBottom * frame.scaleY).toFloat(),
        )
    }

    private fun globalVisibleRect(view: WebView): Rect {
        var rect = Rect()
        instrumentation.runOnMainSync { view.getGlobalVisibleRect(rect) }
        return rect
    }

    /** The same clipped window rectangle production capture uses, not the WebView's full canvas. */
    private fun presentationVisibleRect(view: WebView): Rect {
        var rect: Rect? = null
        instrumentation.runOnMainSync {
            val host = view.parent as? BrowserViewportHost
            rect = host?.presentationSnapshot()?.visible
        }
        return requireNotNull(rect) { "production presentation has no visible page rectangle" }
    }

    private fun displayedWidth(view: WebView): Float = view.width * view.scaleX

    private fun contentWidth(activity: MainActivity): Int {
        var width = 0
        instrumentation.runOnMainSync {
            width = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)?.width ?: 0
        }
        return width
    }

    private fun imeState(activity: MainActivity): ImeState {
        var visible = false
        var bottom = 0
        var rootBottom = 0
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            val insets = ViewCompat.getRootWindowInsets(root)
            if (insets != null) {
                visible = insets.isVisible(WindowInsetsCompat.Type.ime())
                bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            }
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            rootBottom = location[1] + root.height
        }
        return ImeState(visible, bottom, rootBottom)
    }

    private fun hideIme(view: WebView) {
        instrumentation.runOnMainSync {
            val imm = view.context.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    /**
     * Numeric-only capture diagnosis: host live/committed snapshots, page rect, fullyVisible,
     * visible rect, root size, node flags, and whether production captureFrame/
     * captureCommittedFrame would return null. No URL, page text or document id.
     */
    private fun captureDiagnostic(view: WebView): String {
        var text = "host=missing"
        instrumentation.runOnMainSync {
            val host = view.parent as? BrowserViewportHost
            val live = host?.presentationSnapshot()
            val committed = host?.committedPresentationSnapshot()
            val rootWidth = view.rootView?.width ?: 0
            val rootHeight = view.rootView?.height ?: 0
            val liveGeometry = live?.toPresentationGeometry()
            val committedGeometry = committed?.toPresentationGeometry()
            val liveFrame = BrowserCaptureGeometry.captureFrame(liveGeometry, rootWidth, rootHeight)
            val committedFrame = BrowserCaptureGeometry.captureCommittedFrame(
                committedGeometry,
                liveGeometry,
                rootWidth,
                rootHeight,
            )
            fun snapshot(value: BrowserPresentationSnapshot?): String =
                if (value == null) {
                    "null"
                } else {
                    "rev=${value.revision} vp=${value.viewportWidthPx}x${value.viewportHeightPx} " +
                        "scale=${value.scale} page=${value.pageLeft},${value.pageTop}," +
                        "${value.pageWidth},${value.pageHeight} visible=${value.visible} " +
                        "fully=${value.fullyVisible}"
                }
            text = "node(attached=${view.isAttachedToWindow},shown=${view.isShown}," +
                "focused=${view.hasFocus()},hw=${view.isHardwareAccelerated}) " +
                "root=${rootWidth}x$rootHeight live=[${snapshot(live)}] " +
                "committed=[${snapshot(committed)}] pageRect=${liveGeometry?.pageBounds} " +
                "captureFrameNull=${liveFrame == null} captureCommittedNull=${committedFrame == null}"
        }
        return text
    }

    private data class PresentationSize(
        val width: Int,
        val height: Int,
        val committed: BrowserPresentationSnapshot,
        val live: BrowserPresentationSnapshot,
    )

    /**
     * The production capture's exact integer page rect from the committed presentation:
     * ceil(left/top), floor(right/bottom), the same inward rounding as BrowserCaptureGeometry.
     * The live presentation must still equal the committed one after capture.
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

    /** A real screen-coordinate touch, not a local View.dispatchTouchEvent. */
    private fun sendPointer(action: Int, x: Float, y: Float, eventTime: Long, downTime: Long) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    private fun evalString(harness: BrowserAuthorityHarness, view: WebView, script: String): String {
        val raw = harness.eval(view, script) ?: throw AssertionError("script result missing")
        return JSONTokener(raw).nextValue() as? String
            ?: throw AssertionError("script result is not a string")
    }

    private companion object {
        const val IME_PROBE_ID = "ime-probe"
    }
}
