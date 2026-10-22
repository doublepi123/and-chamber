package io.andchamber.app.ui.browser

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
