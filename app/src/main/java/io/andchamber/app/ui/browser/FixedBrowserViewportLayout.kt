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
 * The largest Float scale at which [viewport] pixels fit in [container] pixels: `viewport * scale`
 * is never more than [container]. A plain `container.toFloat() / viewport` can round up (1718 / 1080
 * gives 1.5907408, and 1080 x 1.5907408 is 1718.0001), so the drawn page would end a fraction of a
 * pixel past its container and capture, which needs the whole page inside it, would refuse. The
 * check is exact: a Float times an Int is exact in Double, and Float rounding of a product that is
 * not above the (exactly representable) container cannot go above it. Both sizes must be positive.
 */
internal fun fitAxisScale(container: Int, viewport: Int): Float {
    var scale = (container.toDouble() / viewport).toFloat()
    while (scale > 0f && scale.toDouble() * viewport > container) scale = Math.nextDown(scale)
    return scale
}

/**
 * Fits a [viewportWidth] x [viewportHeight] page into a [containerWidth] x [containerHeight] box
 * with one uniform scale: the whole page always stays visible (never cropped) and keeps its
 * proportions (never stretched). A container larger than the page enlarges it until one side
 * touches (a 1920 x 1080 computer-layout page on a landscape tablet fills the width or height), so
 * only the aspect difference is left as an even band on the other axis. A smaller container scales
 * it down the same way (a wide page sits in the middle of a tall phone screen). A container exactly
 * the viewport's size gets scale 1 and no offset; phone layout's page is the full-screen page area,
 * so it never needs enlarging. Only the drawn size changes: the page keeps its fixed size, so no
 * reflow or reload. Unknown viewport sizes draw 1:1.
 *
 * This is the keyboard-closed presentation. While the keyboard is open, [keyboardViewportTransform]
 * is used instead, so the keyboard never changes the page's scale.
 */
internal fun fixedViewportTransform(
    containerWidth: Int,
    containerHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
): ViewportTransform {
    if (viewportWidth <= 0 || viewportHeight <= 0) return ViewportTransform(1f, 0f, 0f)
    if (containerWidth <= 0 || containerHeight <= 0) return ViewportTransform(MIN_VIEWPORT_SCALE, 0f, 0f)
    // No upper bound: the container itself bounds the result, so the page can never be drawn
    // larger than the area it sits in.
    val scale = min(fitAxisScale(containerWidth, viewportWidth), fitAxisScale(containerHeight, viewportHeight))
        .coerceAtLeast(MIN_VIEWPORT_SCALE)
    val offsetX = ((containerWidth - viewportWidth * scale) / 2f).coerceAtLeast(0f)
    val offsetY = ((containerHeight - viewportHeight * scale) / 2f).coerceAtLeast(0f)
    return ViewportTransform(scale, offsetX, offsetY)
}

/**
 * The page while the keyboard is open. The keyboard only covers part of the window for a while, so
 * it changes what is visible, never the page's scale:
 *
 * - Scale is the keyboard-closed presentation's: [fixedViewportTransform] of the page area the same
 *   window has with the keyboard closed ([referenceWidth] x [referenceHeight], computed by the
 *   window layout from the keyboard-closed safe area, not sampled from an animating frame). Only a
 *   real width change (the window became narrower) can lower it, to fit the width; the height the
 *   keyboard takes away never does.
 * - The page stays centred across. Down, a page that still fits is centred; a taller page is cut by
 *   the container and moved so [anchorPageY] (page pixels, the tap that opened the keyboard) sits
 *   in the middle of the visible part, never moving an edge of the page inside the container. With
 *   no anchor the page's top is shown.
 *
 * The page is then usually not completely visible, so capture refuses (it needs the whole page).
 * Without a usable reference this falls back to [fixedViewportTransform].
 */
internal fun keyboardViewportTransform(
    containerWidth: Int,
    containerHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    referenceWidth: Int,
    referenceHeight: Int,
    anchorPageY: Float?,
): ViewportTransform {
    if (viewportWidth <= 0 || viewportHeight <= 0 || containerWidth <= 0 || containerHeight <= 0 ||
        referenceWidth <= 0 || referenceHeight <= 0
    ) {
        return fixedViewportTransform(containerWidth, containerHeight, viewportWidth, viewportHeight)
    }
    val closedScale = fixedViewportTransform(referenceWidth, referenceHeight, viewportWidth, viewportHeight).scale
    val scale = min(closedScale, fitAxisScale(containerWidth, viewportWidth)).coerceAtLeast(MIN_VIEWPORT_SCALE)
    val offsetX = ((containerWidth - viewportWidth * scale) / 2f).coerceAtLeast(0f)
    val spare = containerHeight - viewportHeight * scale
    val anchor = anchorPageY?.takeIf { it.isFinite() }?.coerceIn(0f, viewportHeight.toFloat())
    val offsetY = when {
        spare >= 0f -> spare / 2f
        anchor == null -> 0f
        else -> (containerHeight / 2f - anchor * scale).coerceIn(spare, 0f)
    }
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
 *
 * While the keyboard is open ([setKeyboard]) the page keeps its keyboard-closed scale and is cut by
 * this container instead of shrunk; see [keyboardViewportTransform].
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

    /**
     * The keyboard-closed page area of the current window, in pixels, while the keyboard is open;
     * null while it is closed. Comes from the window layout (keyboard target state, not an animation
     * frame), so it is already right when the keyboard is open before this container first lays out.
     */
    private var keyboardReference: Pair<Int, Int>? = null

    /**
     * The last tap on the page since the keyboard was last closed, in WebView content pixels
     * (view-local y plus the WebView's own scroll then), so a page scroll between the tap and the
     * keyboard opening is accounted for. A drag never becomes the anchor.
     */
    private var tapContentY: Float? = null

    /**
     * The anchor actually used while the keyboard is open, in view-local page pixels. Fixed when the
     * keyboard opens (or on a new tap while it is open) and then left alone, so the person's own
     * page scrolling or any other layout pass never moves the presentation under them.
     */
    private var keyboardAnchorY: Float? = null
    private var downX = Float.NaN
    private var downY = Float.NaN
    private var downViewY = Float.NaN
    private var downScrollY = 0
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /**
     * Keyboard state from the window layout. [referencePx] is the keyboard-closed page area of this
     * window (null when the keyboard is closed). Presentation only: never resizes the page.
     */
    fun setKeyboard(referencePx: Pair<Int, Int>?) {
        if (referencePx == keyboardReference) return
        val opening = keyboardReference == null && referencePx != null
        keyboardReference = referencePx
        if (referencePx == null) {
            tapContentY = null
            keyboardAnchorY = null
        } else if (opening) {
            // Only when the page holds focus: a keyboard for the app's own address field must not
            // move the page to an older tap.
            val child = if (childCount > 0) getChildAt(0) else null
            keyboardAnchorY = if (child != null && child.hasFocus()) tapContentY?.let { it - child.scrollY } else null
        }
        requestLayout()
    }

    /** Only reads the touch to remember where the page was tapped; never consumes or changes it. */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        val child = if (childCount > 0) getChildAt(0) else null
        val t = transform
        if (child != null && t.scale > 0f) {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downViewY = (event.y - t.offsetY) / t.scale
                    downScrollY = child.scrollY
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val tap = downY.isFinite() && downViewY.isFinite() &&
                        kotlin.math.abs(event.x - downX) <= touchSlop && kotlin.math.abs(event.y - downY) <= touchSlop
                    if (tap) {
                        tapContentY = downViewY + downScrollY
                        if (keyboardReference != null && keyboardAnchorY != downViewY) {
                            // Keyboard already open: a tap on another field brings that one into view.
                            keyboardAnchorY = downViewY
                            requestLayout()
                        }
                    }
                    downY = Float.NaN
                }
                android.view.MotionEvent.ACTION_CANCEL -> downY = Float.NaN
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun pageWidth(containerWidth: Int) = if (viewportWidth > 0) viewportWidth else containerWidth
    private fun pageHeight(containerHeight: Int) = if (viewportHeight > 0) viewportHeight else containerHeight

    private fun presentationFor(w: Int, h: Int): ViewportTransform {
        val reference = keyboardReference ?: return fixedViewportTransform(width, height, w, h)
        return keyboardViewportTransform(width, height, w, h, reference.first, reference.second, keyboardAnchorY)
    }

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
        val next = presentationFor(w, h)
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
