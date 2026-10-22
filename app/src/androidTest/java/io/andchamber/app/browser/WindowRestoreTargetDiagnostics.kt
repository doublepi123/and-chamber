package io.andchamber.app.browser

import org.json.JSONObject

/**
 * Test-only, read-only observation of the B5 restore target state. It is called from the instrumentation loop on the
 * Main thread through the existing diag path, and it never changes state:
 *  - no JavaScript is evaluated, no layout/measure/invalidate is requested, no delay, no grant or ref is touched;
 *  - only fields read by reflection (debug build, not minified) and existing public getters are used;
 *  - nothing is sent outside the instrumentation status file. Output is numeric, boolean, or a fixed unknown code.
 *  - Never emitted: view ids, witness/document ids, URLs, page text, tokens.
 *
 * A missing or unreadable field is [UNKNOWN], never a default 0 or false. The Node side keeps that code as a refusal
 * for diagnosis; it never feeds any pass predicate.
 */
internal object WindowRestoreTargetDiagnostics {
    /** Fixed code for a value that could not be read. Never a stand-in for a real zero or false. */
    const val UNKNOWN = "diagnosticUnknown"

    /**
     * Reads the presenter's private fields by name. Each field is read once. The presenter instance and the page are
     * the ones the caller already holds on the Main thread.
     */
    fun snapshot(presenter: io.andchamber.app.ui.browser.NativeBrowserPresenter, web: android.webkit.WebView?): JSONObject {
        val out = JSONObject()
        val target = readField(presenter, "windowRestoreTarget") as? io.andchamber.app.ui.browser.WindowContentTarget
        val context = readField(presenter, "windowContext")
        val page = readField(presenter, "page") as? io.andchamber.app.ui.browser.ControlledBrowserPage
        val geometry = readField(presenter, "geometry") as? io.andchamber.app.ui.browser.MeasuredGeometry

        out.put("targetPresent", target != null)
        if (target != null) {
            out.put("transitionId", target.binding.change.transitionId)
            out.put("next", target.binding.change.next)
            out.put("layoutRevision", target.layoutRevision)
            out.put("targetWindowLeftPx", target.windowLeftPx.toDouble())
            out.put("targetWindowTopPx", target.windowTopPx.toDouble())
            out.put("targetWidthPx", target.widthPx)
            out.put("targetHeightPx", target.heightPx)
        } else {
            out.put("transitionId", JSONObject.NULL)
            out.put("next", JSONObject.NULL)
            out.put("layoutRevision", JSONObject.NULL)
            out.put("targetWindowLeftPx", JSONObject.NULL)
            out.put("targetWindowTopPx", JSONObject.NULL)
            out.put("targetWidthPx", JSONObject.NULL)
            out.put("targetHeightPx", JSONObject.NULL)
        }

        // Floor and binding identity. The binding source is compared to the live page by identity, never by viewId text.
        val floor = if (context == null) null else readField(context, "floor")
        out.put("floor", floor ?: UNKNOWN)
        val binding = if (context == null) null else readField(context, "binding") as? io.andchamber.app.ui.browser.WindowContentBinding
        out.put("bindingPresent", binding != null)
        val bindingMatchesTarget = target != null && binding != null && target.binding === binding
        out.put("bindingMatchesTarget", bindingMatchesTarget)
        val bindingSourceMatchesCurrent = binding != null && page != null &&
            binding.sourceHandle == page.handle && binding.viewId == page.viewId()
        out.put("bindingSourceMatchesCurrent", bindingSourceMatchesCurrent)

        // The live source frame and latest measured sample. Read only; never used as the desired target.
        if (page != null) {
            val frame = page.nativeFrame()
            out.put("nativeWidth", frame.width)
            out.put("nativeHeight", frame.height)
            out.put("nativeWindowLeft", frame.windowLeft)
            out.put("nativeWindowTop", frame.windowTop)
            out.put("nativeVisibleLeft", frame.visibleLeft)
            out.put("nativeVisibleTop", frame.visibleTop)
            out.put("nativeVisibleRight", frame.visibleRight)
            out.put("nativeVisibleBottom", frame.visibleBottom)
            val latest = page.latestGeometry()
            if (latest == null) {
                out.put("latestPresent", false)
                out.put("latestSerial", JSONObject.NULL)
                out.put("latestEpoch", JSONObject.NULL)
            } else {
                out.put("latestPresent", true)
                out.put("latestSerial", latest.serial)
                out.put("latestEpoch", latest.epoch)
                out.put("latestFrameWidth", latest.frame.width)
                out.put("latestFrameHeight", latest.frame.height)
                out.put("latestFrameWindowLeft", latest.frame.windowLeft)
                out.put("latestFrameWindowTop", latest.frame.windowTop)
                out.put("latestInnerWidth", latest.probe.innerWidth)
                out.put("latestInnerHeight", latest.probe.innerHeight)
                out.put("latestClientWidth", latest.probe.clientWidth)
                out.put("latestClientHeight", latest.probe.clientHeight)
                out.put("latestVisualWidth", latest.probe.visualWidth.toDouble())
                out.put("latestVisualHeight", latest.probe.visualHeight.toDouble())
                out.put("latestScrollX", latest.probe.scrollX.toDouble())
                out.put("latestScrollY", latest.probe.scrollY.toDouble())
                out.put("latestDevicePixelRatio", latest.probe.devicePixelRatio.toDouble())
                out.put("latestVisualScale", latest.probe.visualScale.toDouble())
                // Accepted means what the presenter's own acceptedGeometry checks: the same serial, generation and a ready entry.
                val accepted = geometry
                val sampleAccepted = accepted != null && accepted.ready &&
                    accepted.probeSerial == latest.serial && accepted.generation == readLong(presenter, "geometryGeneration")
                out.put("sampleAccepted", sampleAccepted)
            }
        } else {
            out.put("nativeWidth", JSONObject.NULL)
            out.put("latestPresent", JSONObject.NULL)
            out.put("sampleAccepted", JSONObject.NULL)
        }

        // Accepted geometry as the presenter holds it (not the latest sample).
        if (geometry != null) {
            out.put("acceptedPresent", true)
            out.put("acceptedReady", geometry.ready)
            out.put("acceptedProbeSerial", geometry.probeSerial)
            out.put("acceptedViewWidthPx", geometry.viewWidthPx)
            out.put("acceptedViewHeightPx", geometry.viewHeightPx)
        } else {
            out.put("acceptedPresent", false)
        }

        // The actual android.view.View geometry, only when a live WebView is attached. No requestLayout or measure.
        if (web != null && web.isAttachedToWindow) {
            out.put("viewMeasuredWidth", web.measuredWidth)
            out.put("viewMeasuredHeight", web.measuredHeight)
            out.put("viewActualWidth", web.width)
            out.put("viewActualHeight", web.height)
            out.put("viewScaleX", web.scaleX.toDouble())
            out.put("viewScaleY", web.scaleY.toDouble())
            out.put("viewTranslationX", web.translationX.toDouble())
            out.put("viewTranslationY", web.translationY.toDouble())
            val parent = web.parent as? android.view.View
            if (parent != null) {
                out.put("parentMeasuredWidth", parent.measuredWidth)
                out.put("parentMeasuredHeight", parent.measuredHeight)
                out.put("parentActualWidth", parent.width)
                out.put("parentActualHeight", parent.height)
            } else {
                out.put("parentMeasuredWidth", UNKNOWN)
                out.put("parentMeasuredHeight", UNKNOWN)
                out.put("parentActualWidth", UNKNOWN)
                out.put("parentActualHeight", UNKNOWN)
            }
        } else {
            out.put("viewMeasuredWidth", UNKNOWN)
            out.put("viewMeasuredHeight", UNKNOWN)
            out.put("viewActualWidth", UNKNOWN)
            out.put("viewActualHeight", UNKNOWN)
        }
        return out
    }

    private fun readField(target: Any, name: String): Any? = runCatching {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.get(target)
    }.getOrNull()

    private fun readLong(target: Any, name: String): Long? = (readField(target, name) as? Long)
}
