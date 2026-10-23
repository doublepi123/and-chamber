package io.andchamber.app.ui.browser

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import kotlin.math.min

/** Smallest scale the page is drawn at; keeps the transform invertible for touch dispatch. */
internal const val MIN_VIEWPORT_SCALE = 0.01f

/** Quiet time after the last move or scale change before listeners hear about it. */
private const val SETTLE_MS = 120L

/**
 * How the fixed-size page is drawn inside its container: uniform [scale] around the page's top-left
 * corner, then moved by [offsetX]/[offsetY] (container pixels). The page itself keeps its size.
 */
internal data class ViewportTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    /** A point in page (WebView-local) pixels, in container pixels. */
    fun toContainerX(pageX: Float): Float = offsetX + scale * pageX
    fun toContainerY(pageY: Float): Float = offsetY + scale * pageY
}

/**
 * Fits a [viewportWidth] x [viewportHeight] page into a [containerWidth] x [containerHeight] box:
 * the whole page always stays visible (never cropped), it is never enlarged past 1:1, and spare
 * room is split evenly on both sides, across and down (a wide computer-layout page sits in the
 * middle of a tall phone screen). A container exactly the viewport's size gets scale 1 and no
 * offset. Unknown viewport sizes draw 1:1.
 */
internal fun fixedViewportTransform(
    containerWidth: Int,
    containerHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
): ViewportTransform {
    if (viewportWidth <= 0 || viewportHeight <= 0) return ViewportTransform(1f, 0f, 0f)
    if (containerWidth <= 0 || containerHeight <= 0) return ViewportTransform(MIN_VIEWPORT_SCALE, 0f, 0f)
    val scale = min(containerWidth.toFloat() / viewportWidth, containerHeight.toFloat() / viewportHeight)
        .coerceIn(MIN_VIEWPORT_SCALE, 1f)
    val offsetX = ((containerWidth - viewportWidth * scale) / 2f).coerceAtLeast(0f)
    val offsetY = ((containerHeight - viewportHeight * scale) / 2f).coerceAtLeast(0f)
    return ViewportTransform(scale, offsetX, offsetY)
}

/**
 * Where the page is drawn right now, for capture and pointer mapping. The page keeps a fixed
 * [viewportWidthPx] x [viewportHeightPx] size; only [scale] and the window position change when
 * the window is moved, resized, or switched between small window and full screen.
 *
 * - [pageLeft]/[pageTop]/[pageWidth]/[pageHeight]: the drawn (scaled) page in window pixels.
 * - [visible]: the part of the drawn page that is not clipped by any ancestor, in window pixels
 *   (same coordinates as [pageLeft]/[pageTop]), or null when none of it is visible.
 * - [revision]: advances on every change of scale, offset or window position.
 *
 * A window pixel (wx, wy) maps to page pixel ((wx - pageLeft) / scale, (wy - pageTop) / scale).
 */
internal data class BrowserPresentationSnapshot(
    val revision: Long,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val scale: Float,
    val pageLeft: Float,
    val pageTop: Float,
    val pageWidth: Float,
    val pageHeight: Float,
    val visible: Rect?,
    /** The whole drawn page is inside [visible] (to half a pixel). */
    val fullyVisible: Boolean,
)

/**
 * Implemented by the WebView's direct parent. `(webView.parent as? BrowserViewportHost)`.
 *
 * [presentationSnapshot] is the live presentation. [committedPresentationSnapshot] is the last
 * presentation a real frame commit reported, and [requestPresentationCommit] asks the platform for
 * one. Capture accepts only a live presentation that equals a committed one, because a new layout
 * or pre-draw revision does not mean the window's PixelCopy buffer has been submitted yet.
 */
internal interface BrowserViewportHost {
    fun presentationSnapshot(): BrowserPresentationSnapshot?

    /** The last presentation confirmed by a frame commit, or null. */
    fun committedPresentationSnapshot(): BrowserPresentationSnapshot?

    /**
     * One-shot: samples the presentation in the next pre-draw and reports it after that frame is
     * committed to the compositor, scheduling a draw. Returns false when no reliable commit can be
     * reported (API < 29, software window, detached view, background thread, or a request already
     * pending). A false return is a capture refusal, never a guessed frame.
     */
    fun requestPresentationCommit(onCommitted: (BrowserPresentationSnapshot?) -> Unit): Boolean
}

/**
 * Holds the page WebView at a fixed phone-sized viewport and only scales it to the container. The
 * WebView is measured and laid out at the same size whatever the container does, so moving,
 * resizing or going full screen never resizes the page, never reflows its layout and never reloads
 * it. Touches are mapped back into page pixels by the platform, through the child's scale and
 * translation (ViewGroup applies the inverse matrix before the WebView sees an event).
 */
internal open class FixedViewportContainer(context: Context) : FrameLayout(context), BrowserViewportHost {
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var revision = 0L
    private val lastLocation = IntArray(2) { Int.MIN_VALUE }
    private val location = IntArray(2)
    private var committedSnapshot: BrowserPresentationSnapshot? = null

    /** Samples this container's presentation in pre-draw and reports it after the frame commits. */
    private val commitGate = NativeFrameCommitGate<BrowserPresentationSnapshot>(this) { presentationSnapshot() }

    var transform: ViewportTransform = ViewportTransform(1f, 0f, 0f)
        private set

    /** Called on the main thread after the drawn page moved or changed scale. Presentation only. */
    var onPresentationChanged: (() -> Unit)? = null

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        syncLocation()
        true
    }

    init {
        clipChildren = true
        clipToPadding = true
    }

    /** The fixed page size in pixels. Changing it is a real viewport change (rotation, new screen). */
    fun setViewport(widthPx: Int, heightPx: Int) {
        if (widthPx == viewportWidth && heightPx == viewportHeight) return
        viewportWidth = widthPx
        viewportHeight = heightPx
        requestLayout()
    }

    private fun pageWidth(containerWidth: Int) = if (viewportWidth > 0) viewportWidth else containerWidth
    private fun pageHeight(containerHeight: Int) = if (viewportHeight > 0) viewportHeight else containerHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val childWidth = View.MeasureSpec.makeMeasureSpec(pageWidth(width), View.MeasureSpec.EXACTLY)
        val childHeight = View.MeasureSpec.makeMeasureSpec(pageHeight(height), View.MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(childWidth, childHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = pageWidth(width)
        val h = pageHeight(height)
        val next = fixedViewportTransform(width, height, w, h)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            // Same bounds as before: the WebView sees no size change while the window changes.
            child.layout(0, 0, w, h)
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = next.scale
            child.scaleY = next.scale
            child.translationX = next.offsetX
            child.translationY = next.offsetY
        }
        if (next != transform) {
            transform = next
            markChanged()
        }
    }

    // A drag moves the window every frame; tell the listener once it has paused.
    private val settle = Runnable { onPresentationChanged?.invoke() }

    private fun markChanged() {
        revision += 1
        removeCallbacks(settle)
        postDelayed(settle, SETTLE_MS)
    }

    /**
     * What is drawn over or instead of the page changed without any move or scale (the page was
     * hidden or shown, an overlay came or went). Drops the committed tuple and advances [revision],
     * so a capture must wait for a fresh commit and can never reuse a frame from before the change.
     */
    protected fun invalidatePresentation() {
        committedSnapshot = null
        markChanged()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        commitGate.cancel()
        committedSnapshot = null
        removeCallbacks(settle)
        lastLocation[0] = Int.MIN_VALUE
        lastLocation[1] = Int.MIN_VALUE
        super.onDetachedFromWindow()
    }

    override fun presentationSnapshot(): BrowserPresentationSnapshot? {
        // A parent can move before its pre-draw listener bumps revision. Reading the live tuple
        // syncs that first, so revision is never used on its own to prove a position.
        syncLocation()
        return buildSnapshot()
    }

    override fun committedPresentationSnapshot(): BrowserPresentationSnapshot? = committedSnapshot

    override fun requestPresentationCommit(onCommitted: (BrowserPresentationSnapshot?) -> Unit): Boolean =
        commitGate.request { snapshot ->
            committedSnapshot = snapshot
            onCommitted(snapshot)
        }

    /** Keeps [revision] in step with a window move that has not reached pre-draw yet. */
    private fun syncLocation() {
        getLocationInWindow(location)
        if (location[0] != lastLocation[0] || location[1] != lastLocation[1]) {
            lastLocation[0] = location[0]
            lastLocation[1] = location[1]
            markChanged()
        }
    }

    private fun buildSnapshot(): BrowserPresentationSnapshot? {
        val child = if (childCount > 0) getChildAt(0) else return null
        if (!isAttachedToWindow || child.width <= 0 || child.height <= 0) return null
        // Every rectangle below is in this window's coordinates: the container's window position
        // plus local offsets. Local visible rect is clipped by all ancestors, then moved by the same
        // window position, so it never mixes screen and window coordinates.
        val t = transform
        val pageLeft = location[0] + t.offsetX
        val pageTop = location[1] + t.offsetY
        val pageWidth = child.width * t.scale
        val pageHeight = child.height * t.scale
        val local = Rect()
        val visible = if (isShown && getLocalVisibleRect(local)) {
            local.offset(location[0], location[1])
            val page = Rect(
                kotlin.math.floor(pageLeft).toInt(), kotlin.math.floor(pageTop).toInt(),
                kotlin.math.ceil(pageLeft + pageWidth).toInt(), kotlin.math.ceil(pageTop + pageHeight).toInt(),
            )
            if (local.intersect(page)) local else null
        } else {
            null
        }
        val fully = visible != null &&
            visible.left <= pageLeft && visible.top <= pageTop &&
            visible.right >= pageLeft + pageWidth - 0.5f && visible.bottom >= pageTop + pageHeight - 0.5f
        return BrowserPresentationSnapshot(
            revision, child.width, child.height, t.scale, pageLeft, pageTop, pageWidth, pageHeight, visible, fully,
        )
    }
}

/**
 * Mechanical native draw-commit gate. [sample] runs in pre-draw, so it names what that traversal is
 * about to draw. The value is reported only after the platform says that frame was submitted to the
 * compositor. No delay or pre-draw alone is accepted as proof of the window buffer.
 */
internal class NativeFrameCommitGate<T : Any>(
    private val view: View,
    private val sample: () -> T?,
) {
    private var pending: T? = null
    private var waiting: ((T?) -> Unit)? = null
    private var observer: ViewTreeObserver? = null
    private var preDrawInstalled = false
    private var commitRegistered = false

    private val preDraw = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            pending = sample()
            // Keep the tuple for this traversal: a later pre-draw must not overwrite it before
            // this frame's commit callback reaches the main thread.
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            preDrawInstalled = false
            return true
        }
    }

    private val onCommit = Runnable {
        commitRegistered = false
        val value = pending
        val callback = waiting
        waiting = null
        detach()
        callback?.invoke(value)
    }

    /**
     * One outstanding request. False means the platform cannot report a real commit here; the
     * caller must fail closed instead of capturing an unconfirmed buffer.
     */
    fun request(callback: (T?) -> Unit): Boolean {
        if (waiting != null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (!view.isAttachedToWindow || !view.isHardwareAccelerated) return false
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        // The tree observer is shared by the whole window, so the root's is the same one while
        // attached. Ask for the window's frame commit: a bare smoke WebView uses this path too.
        val live = view.rootView.viewTreeObserver
        if (!live.isAlive) return false
        observer = live
        waiting = callback
        pending = null
        if (!preDrawInstalled) {
            live.addOnPreDrawListener(preDraw)
            preDrawInstalled = true
        }
        if (!runCatching { live.registerFrameCommitCallback(onCommit) }.isSuccess) {
            waiting = null
            detach()
            return false
        }
        commitRegistered = true
        view.invalidate()
        return true
    }

    fun cancel() {
        waiting = null
        pending = null
        detach()
    }

    private fun detach() {
        val live = observer ?: return
        if (live.isAlive) {
            if (preDrawInstalled) live.removeOnPreDrawListener(preDraw)
            if (commitRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { live.unregisterFrameCommitCallback(onCommit) }
            }
        }
        observer = null
        preDrawInstalled = false
        commitRegistered = false
    }
}
