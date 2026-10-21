package io.andchamber.app.ui.browser

/**
 * Pointer storage is WebView-local physical pixels. Finger deltas accumulate there and native
 * events use that point. CSS is derived only after a measured snapshot exists.
 */
data class MouseProbeResult(val supported: Boolean, val reason: String? = null)

data class PointerUi(
    val viewHandle: Int,
    val geometryEpoch: Long,
    val pointerViewX: Float,
    val pointerViewY: Float,
    val geometryReady: Boolean,
)

enum class ModeSwitch { Unchanged, Applied, ReopenRequired }

enum class MouseAction { HOVER, PRESS, DRAG, RELEASE, SCROLL, CANCEL }

data class MouseEventPlan(
    val action: MouseAction,
    val viewX: Float,
    val viewY: Float,
    val cssX: Float = 0f,
    val cssY: Float = 0f,
    val scrollDelta: Float = 0f,
    val geometryGeneration: Long,
)

data class PointerSample(
    val action: Int,
    val x: Float,
    val y: Float,
    val pointerCount: Int,
    val down: Boolean,
    val timeMs: Long = 0L,
    val pointerId: Int = 0,
    val actionPointerId: Int = pointerId,
    val actionIndex: Int = 0,
)

class TouchpadGesture {
    var active = false
        private set
    var lastX = 0f
        private set
    var lastY = 0f
        private set
    var downAt = 0L
        private set
    var travelled = 0f
        private set
    var dragging = false
        private set
    var scrolling = false
        private set
    var primaryPointerId: Int? = null
        private set
    var retireRequired = false
        private set
    private var swallowing = false
    private var pendingX = 0f
    private var pendingY = 0f

    fun begin(x: Float, y: Float, timeMs: Long, pointerId: Int) {
        active = true
        lastX = x
        lastY = y
        downAt = timeMs
        travelled = 0f
        dragging = false
        scrolling = false
        primaryPointerId = pointerId
        swallowing = false
        pendingX = 0f
        pendingY = 0f
    }

    fun move(x: Float, y: Float): Pair<Float, Float> {
        val dx = x - lastX
        val dy = y - lastY
        lastX = x
        lastY = y
        travelled += kotlin.math.abs(dx) + kotlin.math.abs(dy)
        pendingX += dx
        pendingY += dy
        return pendingX to pendingY
    }

    fun consumed() {
        pendingX = 0f
        pendingY = 0f
    }

    fun startDrag() { dragging = true }
    fun startScroll() { scrolling = true }
    fun cancel() { active = false; dragging = false; scrolling = false; primaryPointerId = null; swallowing = false }
    fun retire() { retireRequired = true; cancel() }
    fun swallow() { swallowing = true; dragging = false }
    fun swallowing(): Boolean = swallowing
    fun primaryEnded() = swallowing && !active
}

object MouseInput {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3
    const val ACTION_POINTER_DOWN = 5
    const val ACTION_POINTER_UP = 6
    const val TAP_SLOP_PX = 24f
    const val DRAG_HOLD_MS = 350L
    const val WHEEL_PX_PER_NOTCH = 60f

    /**
     * Physical pointer planning. [ready] false drops the whole gesture until UP. A cancel clears
     * the button without synthesizing a click. Scroll does not move the physical pointer.
     */
    fun plan(
        sample: PointerSample,
        pointerViewX: Float,
        pointerViewY: Float,
        geometry: MeasuredGeometry?,
        gesture: TouchpadGesture,
        ready: Boolean,
    ): List<MouseEventPlan> {
        if (!ready || geometry == null || !geometry.ready) {
            if (sample.action == ACTION_UP || sample.action == ACTION_CANCEL) gesture.cancel()
            return emptyList()
        }
        val generation = geometry.generation
        return when (sample.action) {
            ACTION_DOWN -> {
                if (gesture.retireRequired || gesture.swallowing()) return emptyList()
                gesture.begin(sample.x, sample.y, sample.timeMs, sample.pointerId)
                emptyList()
            }
            ACTION_POINTER_DOWN -> {
                if (gesture.dragging) return emptyList()
                gesture.startScroll()
                gesture.begin(sample.x, sample.y, sample.timeMs, sample.pointerId)
                gesture.startScroll()
                emptyList()
            }
            ACTION_POINTER_UP -> releasePrimary(sample, pointerViewX, pointerViewY, geometry, gesture, generation)
            ACTION_MOVE -> move(sample, pointerViewX, pointerViewY, geometry, gesture, generation)
            ACTION_UP -> finish(sample, pointerViewX, pointerViewY, geometry, gesture, generation)
            ACTION_CANCEL -> {
                if (!gesture.dragging) {
                    gesture.cancel()
                    emptyList()
                } else {
                    gesture.retire()
                    listOf(at(MouseAction.CANCEL, pointerViewX, pointerViewY, geometry, generation))
                }
            }
            else -> emptyList()
        }
    }

    private fun move(
        sample: PointerSample,
        pointerX: Float,
        pointerY: Float,
        geometry: MeasuredGeometry,
        gesture: TouchpadGesture,
        generation: Long,
    ): List<MouseEventPlan> {
        if (!gesture.active || gesture.swallowing() || sample.pointerId != gesture.primaryPointerId) return emptyList()
        val stepY = sample.y - gesture.lastY
        val (dx, dy) = gesture.move(sample.x, sample.y)
        if (!gesture.dragging && (sample.pointerCount >= 2 || gesture.scrolling)) {
            gesture.startScroll()
            gesture.consumed()
            val notches = -stepY / WHEEL_PX_PER_NOTCH
            return if (notches == 0f) emptyList() else listOf(at(MouseAction.SCROLL, pointerX, pointerY, geometry, generation, notches))
        }
        if (gesture.travelled < TAP_SLOP_PX) return emptyList()
        gesture.consumed()
        val nextX = (pointerX + dx).coerceIn(0f, geometry.viewWidthPx.toFloat())
        val nextY = (pointerY + dy).coerceIn(0f, geometry.viewHeightPx.toFloat())
        val startDrag = !gesture.dragging && sample.timeMs - gesture.downAt >= DRAG_HOLD_MS
        return if (startDrag) {
            gesture.startDrag()
            listOf(at(MouseAction.PRESS, pointerX, pointerY, geometry, generation), at(MouseAction.DRAG, nextX, nextY, geometry, generation))
        } else if (gesture.dragging) {
            listOf(at(MouseAction.DRAG, nextX, nextY, geometry, generation))
        } else {
            listOf(at(MouseAction.HOVER, nextX, nextY, geometry, generation))
        }
    }

    private fun releasePrimary(
        sample: PointerSample,
        pointerX: Float,
        pointerY: Float,
        geometry: MeasuredGeometry,
        gesture: TouchpadGesture,
        generation: Long,
    ): List<MouseEventPlan> {
        if (!gesture.dragging || sample.actionPointerId != gesture.primaryPointerId) return emptyList()
        gesture.swallow()
        return listOf(at(MouseAction.RELEASE, pointerX, pointerY, geometry, generation))
    }

    private fun finish(
        sample: PointerSample,
        pointerX: Float,
        pointerY: Float,
        geometry: MeasuredGeometry,
        gesture: TouchpadGesture,
        generation: Long,
    ): List<MouseEventPlan> {
        if (gesture.swallowing()) {
            gesture.cancel()
            return emptyList()
        }
        if (sample.pointerId != gesture.primaryPointerId && gesture.primaryPointerId != null) return emptyList()
        val result = when {
            !gesture.active -> emptyList()
            gesture.dragging -> listOf(at(MouseAction.RELEASE, pointerX, pointerY, geometry, generation))
            gesture.scrolling -> emptyList()
            gesture.travelled < TAP_SLOP_PX -> listOf(
                at(MouseAction.HOVER, pointerX, pointerY, geometry, generation),
                at(MouseAction.PRESS, pointerX, pointerY, geometry, generation),
                at(MouseAction.RELEASE, pointerX, pointerY, geometry, generation),
            )
            else -> emptyList()
        }
        gesture.cancel()
        return result
    }

    private fun at(
        action: MouseAction,
        viewX: Float,
        viewY: Float,
        geometry: MeasuredGeometry,
        generation: Long,
        scroll: Float = 0f,
    ) = MouseEventPlan(
        action = action,
        viewX = viewX,
        viewY = viewY,
        cssX = geometry.clientX(viewX),
        cssY = geometry.clientY(viewY),
        scrollDelta = scroll,
        geometryGeneration = generation,
    )
}
