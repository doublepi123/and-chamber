package io.andchamber.app.ui.browser

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One measured geometry. Layout CSS, visual CSS and WebView-local pixels are different numbers.
 * [devicePixelRatio] is the page-reported DPR and is never invented from view width / 1024.
 * [scale] is view content width divided by visual viewport width. It is the only ratio used to
 * derive CSS coordinates from a physical pointer.
 */
data class MeasuredGeometry(
    val generation: Long,
    val viewId: String,
    val tabId: String?,
    val ticket: Long,
    val documentId: String?,
    val probeSerial: Long,
    val viewWidthPx: Int,
    val viewHeightPx: Int,
    val windowLeft: Int,
    val windowTop: Int,
    val visibleLeft: Int,
    val visibleTop: Int,
    val visibleRight: Int,
    val visibleBottom: Int,
    val innerWidth: Int,
    val innerHeight: Int,
    val clientWidth: Int,
    val clientHeight: Int,
    val visualWidth: Float,
    val visualHeight: Float,
    val offsetLeft: Float,
    val offsetTop: Float,
    val pageLeft: Float,
    val pageTop: Float,
    val scrollX: Float,
    val scrollY: Float,
    val devicePixelRatio: Float,
    val visualScale: Float,
    val mode: String,
) {
    val ready: Boolean get() = viewWidthPx > 1 && visualWidth > 0f && documentId != null
    val scale: Float get() = if (visualWidth <= 0f) 0f else viewWidthPx / visualWidth

    fun clientX(pointerViewX: Float): Float = offsetLeft + pointerViewX / scale
    fun clientY(pointerViewY: Float): Float = offsetTop + pointerViewY / scale
    fun pageX(pointerViewX: Float): Float = pageLeft + pointerViewX / scale
    fun pageY(pointerViewY: Float): Float = pageTop + pointerViewY / scale
}

data class GeometryProbe(
    val documentId: String?,
    val innerWidth: Int,
    val innerHeight: Int,
    val clientWidth: Int,
    val clientHeight: Int,
    val scrollX: Float,
    val scrollY: Float,
    val devicePixelRatio: Float,
    val visualWidth: Float,
    val visualHeight: Float,
    val offsetLeft: Float,
    val offsetTop: Float,
    val pageLeft: Float,
    val pageTop: Float,
    val visualScale: Float,
)

/** Identity a probe must still name before it may replace the current snapshot. */
data class ProbeIdentity(
    val viewId: String,
    val tabId: String?,
    val ticket: Long,
    val documentId: String?,
    val generation: Long,
    val probeSerial: Long,
)

object BrowserGeometry {
    fun accept(probe: GeometryProbe, identity: ProbeIdentity, current: ProbeIdentity): Boolean =
        probe.documentId != null &&
            identity.viewId == current.viewId &&
            identity.tabId == current.tabId &&
            identity.ticket == current.ticket &&
            identity.documentId == current.documentId &&
            identity.generation == current.generation &&
            identity.probeSerial == current.probeSerial &&
            probe.documentId == current.documentId

    fun fromProbe(
        probe: GeometryProbe,
        identity: ProbeIdentity,
        viewWidthPx: Int,
        viewHeightPx: Int,
        windowLeft: Int,
        windowTop: Int,
        visibleLeft: Int,
        visibleTop: Int,
        visibleRight: Int,
        visibleBottom: Int,
        mode: String,
    ): MeasuredGeometry = MeasuredGeometry(
        generation = identity.generation,
        viewId = identity.viewId,
        tabId = identity.tabId,
        ticket = identity.ticket,
        documentId = probe.documentId,
        probeSerial = identity.probeSerial,
        viewWidthPx = viewWidthPx,
        viewHeightPx = viewHeightPx,
        windowLeft = windowLeft,
        windowTop = windowTop,
        visibleLeft = visibleLeft,
        visibleTop = visibleTop,
        visibleRight = visibleRight,
        visibleBottom = visibleBottom,
        innerWidth = probe.innerWidth,
        innerHeight = probe.innerHeight,
        clientWidth = probe.clientWidth,
        clientHeight = probe.clientHeight,
        visualWidth = probe.visualWidth,
        visualHeight = probe.visualHeight,
        offsetLeft = probe.offsetLeft,
        offsetTop = probe.offsetTop,
        pageLeft = probe.pageLeft,
        pageTop = probe.pageTop,
        scrollX = probe.scrollX,
        scrollY = probe.scrollY,
        devicePixelRatio = probe.devicePixelRatio,
        visualScale = probe.visualScale,
        mode = mode,
    )
}

/** Whole-pixel rectangle in window coordinates. Kept out of [android.graphics.Rect] so capture
 * rules can be asserted on the JVM without a device. */
data class CaptureRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val empty: Boolean get() = width <= 0 || height <= 0

    /** This rectangle covers all of [other]. */
    fun contains(other: CaptureRect): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

/**
 * The native presentation of the fixed page, sampled at capture time. Window pixels, like
 * [BrowserPresentationSnapshot], but without a framework Rect.
 *
 * [scale] is the native wrapper's display scale. [pageLeft]/[pageTop] are the drawn page's window
 * position and [pageWidth]/[pageHeight] its drawn size. [visible] is the part of the container that
 * no ancestor clips, in window coordinates, or null when nothing is visible.
 */
data class BrowserPresentationGeometry(
    val revision: Long,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val scale: Float,
    val pageLeft: Float,
    val pageTop: Float,
    val pageWidth: Float,
    val pageHeight: Float,
    val visible: CaptureRect?,
) {
    val pageRight: Float get() = pageLeft + pageWidth
    val pageBottom: Float get() = pageTop + pageHeight

    /** The drawn page's outward integer bounds: every pixel the page can paint. */
    val pageBounds: CaptureRect get() = CaptureRect(
        floor(pageLeft.toDouble()).toInt(),
        floor(pageTop.toDouble()).toInt(),
        ceil(pageRight.toDouble()).toInt(),
        ceil(pageBottom.toDouble()).toInt(),
    )
}

/**
 * One sampled presentation plus the exact window rectangle capture may copy. [rect] is rounded
 * inward from the drawn page, so the image never contains a pixel outside the page, the unclipped
 * container, or the window.
 */
data class CaptureFrame(
    val presentation: BrowserPresentationGeometry,
    val rect: CaptureRect,
    val windowWidth: Int,
    val windowHeight: Int,
)

object BrowserCaptureGeometry {
    /**
     * Resolves a sampled presentation into a copyable frame, or refuses.
     *
     * The whole drawn page must be inside the window and inside the unclipped visible area. Only
     * then is a rectangle returned; it is rounded inward so a half-pixel edge is dropped instead of
     * copying neighbouring app pixels. A merely clipped bounding box is never treated as the page.
     */
    fun captureFrame(
        presentation: BrowserPresentationGeometry?,
        windowWidth: Int,
        windowHeight: Int,
    ): CaptureFrame? {
        if (presentation == null) return null
        if (windowWidth <= 0 || windowHeight <= 0) return null
        if (presentation.viewportWidthPx <= 1 || presentation.viewportHeightPx <= 1) return null
        val scale = presentation.scale
        if (!scale.isFinite() || scale <= 0f) return null
        if (!presentation.pageLeft.isFinite() || !presentation.pageTop.isFinite()) return null
        if (!presentation.pageWidth.isFinite() || !presentation.pageHeight.isFinite()) return null
        if (presentation.pageWidth <= 0f || presentation.pageHeight <= 0f) return null
        // The wrapper may not claim a page size that differs from the measured fixed viewport.
        if (abs(presentation.pageWidth - presentation.viewportWidthPx * scale) > 0.5f) return null
        if (abs(presentation.pageHeight - presentation.viewportHeightPx * scale) > 0.5f) return null
        val window = CaptureRect(0, 0, windowWidth, windowHeight)
        val page = presentation.pageBounds
        val visible = presentation.visible ?: return null
        // The complete page, not the clipped remainder, must be on screen.
        if (!window.contains(page)) return null
        if (!visible.contains(page)) return null
        val rect = CaptureRect(
            ceil(presentation.pageLeft.toDouble()).toInt(),
            ceil(presentation.pageTop.toDouble()).toInt(),
            floor(presentation.pageRight.toDouble()).toInt(),
            floor(presentation.pageBottom.toDouble()).toInt(),
        )
        if (rect.empty) return null
        if (!window.contains(rect)) return null
        if (!visible.contains(rect)) return null
        return CaptureFrame(presentation, rect, windowWidth, windowHeight)
    }

    /**
     * Only a presentation the platform has committed to the compositor may be copied. [committed]
     * must equal [live] as a whole tuple. Revision alone is not proof: a parent can move before its
     * revision updates, and a newer revision alone does not mean a new window buffer was submitted.
     * A null committed value, or any tuple difference, refuses capture.
     */
    fun captureCommittedFrame(
        committed: BrowserPresentationGeometry?,
        live: BrowserPresentationGeometry?,
        windowWidth: Int,
        windowHeight: Int,
    ): CaptureFrame? {
        if (committed == null || live == null) return null
        if (committed != live) return null
        return captureFrame(live, windowWidth, windowHeight)
    }
}

/** Adapts the UI wrapper's snapshot to the capture-only mirror above. */
internal fun BrowserPresentationSnapshot.toPresentationGeometry(): BrowserPresentationGeometry =
    BrowserPresentationGeometry(
        revision = revision,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        scale = scale,
        pageLeft = pageLeft,
        pageTop = pageTop,
        pageWidth = pageWidth,
        pageHeight = pageHeight,
        visible = visible?.let { CaptureRect(it.left, it.top, it.right, it.bottom) },
    )
