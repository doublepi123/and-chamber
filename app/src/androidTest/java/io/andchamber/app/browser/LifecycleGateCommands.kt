package io.andchamber.app.browser

import android.app.Instrumentation
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val BRIDGE_NAME = "LifecycleEventRecorder"
private const val MAX_DOCS_PER_VIEW = 32
private val ORIGIN_PATTERN = Regex("[0-9]{1,15}(\\.[0-9]{1,6})?")
private val CLICK_TARGETS = setOf("next", "box")

/**
 * Instrumentation-owned recorder. One instance lives for the whole instrumentation run, so a retired
 * view keeps its record after destroy. A view is ready only after its document-start script calls
 * [installed] through its own bridge. Attaching a view is not readiness.
 */
internal class LifecycleEventRecorder {
    private val lock = Any()
    private val ids = IdentityHashMap<View, Int>()
    private val views = linkedMapOf<Int, ViewRecord>()
    private var nextId = 1
    private var lastRetired: Int? = null

    private class DocRecord(val origin: String) {
        var clicks = 0
        var lastTarget: String? = null
    }

    private class ViewRecord(val id: Int) {
        val docs = linkedMapOf<String, DocRecord>()
        var currentDoc: String? = null
        var retired = false
        var readyAtRetire = false
    }

    /** Bound to one view when attached. The page cannot choose or change which view it reports for. */
    private inner class Bridge(private val view: ViewRecord) {
        @JavascriptInterface
        fun installed(timeOrigin: String?): Boolean = acceptInstalled(view, timeOrigin)

        @JavascriptInterface
        fun record(timeOrigin: String?, target: String?): Boolean = acceptClick(view, timeOrigin, target)
    }

    /** Main thread. Adds the view's own bridge and the document-start script. Does not mark it ready. */
    fun attach(view: WebView, script: String): Boolean {
        val record = synchronized(lock) {
            val existing = ids[view]
            val id = existing ?: nextId.also { nextId += 1 }
            if (existing == null) ids[view] = id
            views.getOrPut(id) { ViewRecord(id) }
        }
        view.addJavascriptInterface(Bridge(record), BRIDGE_NAME)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        WebViewCompat.addDocumentStartJavaScript(view, script, setOf("https://example.com"))
        return true
    }

    private fun acceptInstalled(view: ViewRecord, raw: String?): Boolean {
        val origin = validOrigin(raw) ?: return false
        synchronized(lock) {
            if (view.retired) return false
            if (view.docs[origin] == null) {
                if (view.docs.size >= MAX_DOCS_PER_VIEW) return false
                view.docs[origin] = DocRecord(origin)
            }
            view.currentDoc = origin
            return true
        }
    }

    private fun acceptClick(view: ViewRecord, raw: String?, target: String?): Boolean {
        val origin = validOrigin(raw) ?: return false
        synchronized(lock) {
            // A click counts for any document already installed on this view, current or not, retired or not.
            // Only a document that was never installed here is refused. Retirement never hides a late click.
            val doc = view.docs[origin] ?: return false
            doc.clicks += 1
            doc.lastTarget = target?.takeIf { it in CLICK_TARGETS }
            return true
        }
    }

    private fun validOrigin(raw: String?): String? {
        if (raw == null || raw.length > 24 || !ORIGIN_PATTERN.matches(raw)) return null
        val value = raw.toDoubleOrNull() ?: return null
        return raw.takeIf { value.isFinite() && value > 0.0 && value < 1e15 }
    }

    /** True only after this view's document-start handshake for its current, non-retired document. */
    fun ready(view: View?): Boolean = synchronized(lock) {
        val id = view?.let { ids[it] } ?: return@synchronized false
        val record = views[id] ?: return@synchronized false
        !record.retired && record.currentDoc != null
    }

    /** Called when the presenter destroys a page. Keeps the record; remembers it as the last retired view. */
    fun noteRetired(view: View?) {
        synchronized(lock) {
            val id = view?.let { ids[it] } ?: return
            val record = views[id] ?: return
            if (record.retired) return
            record.readyAtRetire = record.currentDoc != null
            record.retired = true
            lastRetired = id
        }
    }

    fun snapshot(current: View?): JSONObject = synchronized(lock) {
        val docs = JSONObject()
        var clicks = 0
        views.values.forEach { record ->
            docs.put(record.id.toString(), documentsOf(record).also { array ->
                for (i in 0 until array.length()) clicks += array.getJSONObject(i).getInt("clicks")
            })
        }
        val last = lastRetired?.let { views[it] }?.let { record ->
            JSONObject().put("viewId", record.id).put("readyAtRetire", record.readyAtRetire).put("documents", documentsOf(record))
        }
        JSONObject()
            .put("recorderReady", ready(current))
            .put("currentViewId", current?.let { ids[it] } ?: JSONObject.NULL)
            .put("totalClicks", clicks)
            .put("recordedDocuments", docs)
            .put("lastRetired", last ?: JSONObject.NULL)
    }

    private fun documentsOf(record: ViewRecord): JSONArray {
        val array = JSONArray()
        record.docs.values.forEach { doc ->
            array.put(
                JSONObject()
                    .put("origin", doc.origin)
                    .put("installed", true)
                    .put("clicks", doc.clicks)
                    .put("lastTarget", doc.lastTarget ?: JSONObject.NULL)
                    .put("current", !record.retired && record.currentDoc == doc.origin),
            )
        }
        return array
    }
}

internal const val GATE_PRIMARY_ID = 7
internal const val GATE_SECONDARY_ID = 11

private const val MOTION_DOWN = 0
private const val MOTION_UP = 1
private const val MOTION_MOVE = 2
private const val MOTION_CANCEL = 3
private const val MOTION_POINTER_DOWN = 5
private const val MOTION_POINTER_UP = 6
private const val POINTER_INDEX_SHIFT = 8
private const val COORD_LIMIT = 100_000f

/** One native event as it must be delivered. [count] is the pointer count of this event. */
internal class GateMotion(
    val action: Int,
    val actionIndex: Int,
    val actionPointerId: Int,
    val ids: IntArray,
    val xs: FloatArray,
    val ys: FloatArray,
    val downTime: Long,
    val eventTime: Long,
) {
    val count: Int get() = ids.size
}

internal sealed interface GateStep {
    data class Sent(val motion: GateMotion) : GateStep
    data class Rejected(val reason: String) : GateStep
}

/**
 * Pure model of one two-finger gesture. Pointer order follows native pointer index order: a lifted
 * pointer shifts the later ones down. Every event checks its action index against the pointer count
 * before the state changes. Refused commands change nothing. Event times are strictly increasing and
 * never more than a few milliseconds ahead of the clock.
 */
internal class GateTouchStream(private val clock: () -> Long) {
    private val active = LinkedHashMap<Int, Pair<Float, Float>>()
    private var downTime = 0L
    private var lastEventTime = 0L
    var open = false
        private set

    fun apply(command: String, x: Float, y: Float): GateStep {
        if (!x.isFinite() || !y.isFinite() || abs(x) > COORD_LIMIT || abs(y) > COORD_LIMIT) {
            return GateStep.Rejected("bad-point")
        }
        return when (command) {
            "down" -> down(x, y)
            "move" -> move(x, y)
            "secondary-down" -> secondaryDown(x, y)
            "secondary-up" -> lift(GATE_SECONDARY_ID, x, y)
            "primary-up" -> lift(GATE_PRIMARY_ID, x, y)
            "up" -> up()
            "cancel" -> cancel()
            else -> GateStep.Rejected("unknown-action")
        }
    }

    private fun down(x: Float, y: Float): GateStep {
        active.clear()
        active[GATE_PRIMARY_ID] = x to y
        open = true
        val time = nextTime()
        downTime = time
        return GateStep.Sent(snapshot(MOTION_DOWN, 0, GATE_PRIMARY_ID, time))
    }

    private fun secondaryDown(x: Float, y: Float): GateStep {
        if (!open) return GateStep.Rejected("not-open")
        if (!active.containsKey(GATE_PRIMARY_ID)) return GateStep.Rejected("no-primary")
        if (active.containsKey(GATE_SECONDARY_ID)) return GateStep.Rejected("duplicate-secondary")
        if (active.size != 1) return GateStep.Rejected("too-many-pointers")
        val index = active.size
        active[GATE_SECONDARY_ID] = x to y
        val time = nextTime()
        return GateStep.Sent(snapshot(MOTION_POINTER_DOWN or (index shl POINTER_INDEX_SHIFT), index, GATE_SECONDARY_ID, time))
    }

    private fun move(x: Float, y: Float): GateStep {
        if (!open || active.isEmpty()) return GateStep.Rejected("not-open")
        val target = if (active.containsKey(GATE_PRIMARY_ID)) GATE_PRIMARY_ID else active.keys.first()
        active[target] = x to y
        val time = nextTime()
        return GateStep.Sent(snapshot(MOTION_MOVE, 0, active.keys.first(), time))
    }

    /** Lifts one of exactly two pointers. The count is read and checked before the pointer is removed. */
    private fun lift(leaving: Int, x: Float, y: Float): GateStep {
        if (!open) return GateStep.Rejected("not-open")
        if (active.size != 2 || !active.containsKey(GATE_PRIMARY_ID) || !active.containsKey(GATE_SECONDARY_ID)) {
            return GateStep.Rejected("lift-needs-two-pointers")
        }
        val count = active.size
        val index = active.keys.toList().indexOf(leaving)
        if (index !in 0 until count) return GateStep.Rejected("bad-index")
        active[leaving] = x to y
        val time = nextTime()
        val motion = snapshot(MOTION_POINTER_UP or (index shl POINTER_INDEX_SHIFT), index, leaving, time)
        active.remove(leaving)
        return GateStep.Sent(motion)
    }

    private fun up(): GateStep {
        if (!open) return GateStep.Rejected("not-open")
        if (active.size != 1) return GateStep.Rejected("up-needs-single-pointer")
        val id = active.keys.first()
        val time = nextTime()
        val motion = snapshot(MOTION_UP, 0, id, time)
        close()
        return GateStep.Sent(motion)
    }

    private fun cancel(): GateStep {
        if (!open || active.isEmpty()) return GateStep.Rejected("not-open")
        val time = nextTime()
        val motion = snapshot(MOTION_CANCEL, 0, active.keys.first(), time)
        close()
        return GateStep.Sent(motion)
    }

    private fun close() {
        open = false
        active.clear()
    }

    private fun nextTime(): Long {
        val time = maxOf(clock(), lastEventTime + 1)
        lastEventTime = time
        return time
    }

    private fun snapshot(action: Int, index: Int, actionPointer: Int, time: Long): GateMotion {
        val keys = active.keys.toIntArray()
        return GateMotion(
            action = action,
            actionIndex = index,
            actionPointerId = actionPointer,
            ids = keys,
            xs = FloatArray(keys.size) { active.getValue(keys[it]).first },
            ys = FloatArray(keys.size) { active.getValue(keys[it]).second },
            downTime = downTime,
            eventTime = time,
        )
    }
}

internal class LifecycleGateCommands(
    private val instrumentation: Instrumentation,
    private val recorder: LifecycleEventRecorder,
) {    private val stream = GateTouchStream { SystemClock.uptimeMillis() }
    private var sourceView: WebView? = null
    private var lastVisibleView: WebView? = null

    fun handle(text: String, web: WebView?): JSONObject? {
        if (text == "lifecycle-diag") return onMain { diag(web) }
        if (text.startsWith("gate-native:")) return nativeNoPress(text.removePrefix("gate-native:"), web)
        if (!text.startsWith("gate-finger:")) return null
        val parts = text.removePrefix("gate-finger:").split(",")
        if (parts.size != 3) return rejected("bad-arity")
        val x = parts[1].toFloatOrNull()
        val y = parts[2].toFloatOrNull()
        if (x == null || y == null || !x.isFinite() || !y.isFinite()) return rejected("bad-point")
        return finger(web, parts[0], x, y)
    }

    private fun rejected(reason: String) = JSONObject().put("phase", "gate-finger-rejected").put("sent", false).put("reason", reason)

    /**
     * Button-free native mouse events only: hover enter/move/exit and a wheel. They carry buttons=0 and never a
     * press, release or cancel, so they cannot start or finish a press. Delivered through the public
     * dispatchGenericMotionEvent on Main with **view-local** coordinates, never screen/window offsets. The
     * result is diagnostic; it is not a grant, click or DOM verdict.
     * Format: gate-native:<enter|exit|hover|wheel>,viewX,viewY[,dy]
     */
    private fun nativeNoPress(spec: String, web: WebView?): JSONObject {
        if (web == null) return nativeRejected("no-view")
        val parts = spec.split(",")
        val action = parts.getOrNull(0).orEmpty()
        val x = parts.getOrNull(1)?.toFloatOrNull()
        val y = parts.getOrNull(2)?.toFloatOrNull()
        if (x == null || y == null) return nativeRejected("bad-point")
        val rawDy = if (action == "wheel") parts.getOrNull(3)?.toFloatOrNull() else null
        val outcome = CompletableFuture<JSONObject>()
        Handler(Looper.getMainLooper()).post {
            try {
                val presenter = org.koin.core.context.GlobalContext.get()
                    .get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
                if (presenter.currentView() !== web) {
                    outcome.complete(nativeRejected("source-changed"))
                    return@post
                }
                val width = web.width
                val height = web.height
                val plan = GateNativePointer.plan(action, x, y, width, height, rawDy)
                if (plan is GateNativePlan.Rejected) {
                    outcome.complete(nativeRejected(plan.reason))
                    return@post
                }
                val packet = (plan as GateNativePlan.Ready).packet
                val motion = when (packet.motionAction) {
                    "ACTION_HOVER_ENTER" -> MotionEvent.ACTION_HOVER_ENTER
                    "ACTION_HOVER_EXIT" -> MotionEvent.ACTION_HOVER_EXIT
                    "ACTION_HOVER_MOVE" -> MotionEvent.ACTION_HOVER_MOVE
                    "ACTION_SCROLL" -> MotionEvent.ACTION_SCROLL
                    else -> {
                        outcome.complete(nativeRejected("unknown-native-action"))
                        return@post
                    }
                }
                val coords = MotionEvent.PointerCoords().apply {
                    // View-local: the event must not add getLocationOnScreen/window origin.
                    this.x = packet.x
                    this.y = packet.y
                    pressure = 0f
                    size = 0f
                    packet.axisValue?.let { setAxisValue(MotionEvent.AXIS_VSCROLL, it) }
                }
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(
                    now, now, motion, 1,
                    arrayOf(MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_MOUSE
                    }),
                    arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
                )
                val dispatched = try {
                    web.dispatchGenericMotionEvent(event)
                } finally {
                    event.recycle()
                }
                outcome.complete(JSONObject()
                    .put("phase", "gate-native")
                    .put("sent", true)
                    .put("action", packet.action)
                    .put("space", packet.space)
                    .put("x", packet.x)
                    .put("y", packet.y)
                    .put("width", packet.width)
                    .put("height", packet.height)
                    .put("source", packet.source)
                    .put("tool", packet.tool)
                    .put("button", packet.buttonState)
                    .put("buttons", packet.buttonState)
                    .put("axis", packet.axisValue?.let { value ->
                        JSONObject().put("axis", "AXIS_VSCROLL").put("value", value)
                    } ?: JSONObject.NULL)
                    .put("motionAction", packet.motionAction)
                    .put("dispatched", dispatched)
                    .put("sourceView", System.identityHashCode(web)))
            } catch (error: Throwable) {
                outcome.complete(nativeRejected("dispatch-failed:${error.javaClass.simpleName}"))
            }
        }
        return try {
            outcome.get(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            nativeRejected("dispatch-timeout")
        }
    }

    private fun nativeRejected(reason: String): JSONObject = JSONObject()
        .put("phase", "gate-native-rejected")
        .put("sent", false)
        .put("reason", reason)
        .put("dispatched", false)

    /** Runs [block] on the main thread and waits for it. Only the instrumentation loop calls this. */
    private fun <T> onMain(block: () -> T): T {
        val future = CompletableFuture<T>()
        Handler(Looper.getMainLooper()).post {
            try {
                future.complete(block())
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    private fun diag(web: WebView?): JSONObject {
        val presenter = org.koin.core.context.GlobalContext.get().get<io.andchamber.app.ui.browser.NativeBrowserPresenter>()
        val state = presenter.state.value
        val selected = state.tabs.firstOrNull { it.id == state.selectedTabId }
        val grant = presenter.registry.currentGrant()
        val granted = !state.pageRetired && grant != null && grant.tabId == state.selectedTabId && selected != null && grant.sessionId == selected.owner
        val restore = presenter.windowRestoreResult.value
        val restoreJson = when (restore) {
            null -> JSONObject.NULL
            is io.andchamber.app.ui.browser.RestoreResult.Restored -> JSONObject().put("kind", "Restored").put("actualX", restore.actual.x).put("actualY", restore.actual.y)
            is io.andchamber.app.ui.browser.RestoreResult.Clamped -> JSONObject().put("kind", "Clamped").put("actualX", restore.actual.x).put("actualY", restore.actual.y)
            is io.andchamber.app.ui.browser.RestoreResult.Discarded -> JSONObject().put("kind", "Discarded").put("reason", restore.reason.name)
            is io.andchamber.app.ui.browser.RestoreResult.NotSaved -> JSONObject().put("kind", "NotSaved").put("reason", restore.reason.name)
        }
        if (state.pageRetired && web == null) recorder.noteRetired(lastVisibleView)
        if (web != null) lastVisibleView = web
        val documents = recorder.snapshot(web)
        return JSONObject()
            .put("phase", "lifecycle-diag")
            .put("selectedTabId", state.selectedTabId ?: JSONObject.NULL)
            .put("tabIds", JSONArray(state.tabs.map { it.id }))
            .put("pageRetired", state.pageRetired)
            .put("agentOn", state.agentOn)
            .put("grantForSelected", granted)
            .put("mousePressActive", state.mousePressActive)
            .put("mode", state.mode.name)
            .put("desktopMode", state.desktopMode)
            .put("mouseMode", state.mouseMode)
            .put("pageReady", state.pageReady)
            .put("viewHandle", state.viewHandle)
            .put("documentEpoch", state.documentEpoch)
            .put("restore", restoreJson)
            .put("recorderReady", documents.get("recorderReady"))
            .put("recorderViewId", documents.get("currentViewId"))
            .put("totalClicks", documents.get("totalClicks"))
            .put("recordedDocuments", documents.get("recordedDocuments"))
            .put("lastRetired", documents.get("lastRetired"))
            .put("activeTouchSource", sourceView?.let { System.identityHashCode(it) } ?: JSONObject.NULL)
            .put("currentIdentity", web?.let { System.identityHashCode(it) } ?: JSONObject.NULL)
            // Read-only B5 observation (test-only, Main thread). Never used by any pass predicate.
            .put("restoreTarget", WindowRestoreTargetDiagnostics.snapshot(presenter, web))
    }

    private fun finger(web: WebView?, command: String, x: Float, y: Float): JSONObject {
        if (web == null) return rejected("no-view")
        if (command == "down") {
            // A fresh DOWN starts a new sequence on the view it arrives on, whatever was open before.
            sourceView = web
        } else if (sourceView !== web) {
            // Moves and lifts from an earlier view are refused here and never reach the new view.
            return rejected("source-changed")
        }
        val step = stream.apply(command, x, y)
        if (step is GateStep.Rejected) return rejected(step.reason)
        val motion = (step as GateStep.Sent).motion
        val loc = IntArray(2)
        val located = CompletableFuture<Unit>()
        Handler(Looper.getMainLooper()).post {
            web.getLocationOnScreen(loc)
            located.complete(Unit)
        }
        located.get(1, TimeUnit.SECONDS)
        val properties = Array(motion.count) { i ->
            MotionEvent.PointerProperties().apply {
                id = motion.ids[i]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(motion.count) { i ->
            MotionEvent.PointerCoords().apply {
                this.x = loc[0] + motion.xs[i]
                this.y = loc[1] + motion.ys[i]
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            motion.downTime, motion.eventTime, motion.action, motion.count, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        // Sent on the instrumentation thread, never on Main: sendPointerSync waits for the dispatch.
        instrumentation.sendPointerSync(event)
        event.recycle()
        if (!stream.open) sourceView = null
        return JSONObject()
            .put("phase", "gate-finger")
            .put("sent", true)
            .put("action", command)
            .put("pointerId", motion.actionPointerId)
            .put("index", motion.actionIndex)
            .put("count", motion.count)
            .put("active", JSONArray(motion.ids.toList()))
            .put("sourceView", System.identityHashCode(web))
    }
}

/**
 * Pure native hover/wheel packet plan. `dispatchGenericMotionEvent` consumes view-local
 * coordinates; the window/screen origin is deliberately not added to the event point. The origin
 * parameters exist only so tests can prove that a non-zero window origin is ignored.
 */
internal data class GateNativePacket(
    val action: String,
    val space: String,
    val x: Float,
    val y: Float,
    val width: Int,
    val height: Int,
    val dy: Float?,
    val axisValue: Float?,
    val motionAction: String,
    val source: String,
    val tool: String,
    val buttonState: Int,
)

internal sealed interface GateNativePlan {
    data class Ready(val packet: GateNativePacket) : GateNativePlan
    data class Rejected(val reason: String) : GateNativePlan
}

internal object GateNativePointer {
    private const val COORD_LIMIT = 100_000f
    private const val WHEEL_LIMIT = 2_000f

    @Suppress("UNUSED_PARAMETER")
    fun plan(
        action: String,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        dy: Float? = null,
        windowOriginX: Float = 0f,
        windowOriginY: Float = 0f,
    ): GateNativePlan {
        if (!x.isFinite() || !y.isFinite() || kotlin.math.abs(x) > COORD_LIMIT || kotlin.math.abs(y) > COORD_LIMIT) {
            return GateNativePlan.Rejected("bad-point")
        }
        if (width <= 0 || height <= 0 || x < 0f || y < 0f || x >= width.toFloat() || y >= height.toFloat()) {
            return GateNativePlan.Rejected("point-outside-view")
        }
        val motionAction = when (action) {
            "enter" -> "ACTION_HOVER_ENTER"
            "exit" -> "ACTION_HOVER_EXIT"
            "hover" -> "ACTION_HOVER_MOVE"
            "wheel" -> "ACTION_SCROLL"
            else -> return GateNativePlan.Rejected("unknown-native-action")
        }
        val axis = if (action == "wheel") {
            if (dy == null || !dy.isFinite() || kotlin.math.abs(dy) > WHEEL_LIMIT) {
                return GateNativePlan.Rejected("bad-wheel")
            }
            -dy
        } else {
            null
        }
        return GateNativePlan.Ready(
            GateNativePacket(
                action = action,
                space = "view-local",
                x = x,
                y = y,
                width = width,
                height = height,
                dy = dy,
                axisValue = axis,
                motionAction = motionAction,
                source = "SOURCE_MOUSE",
                tool = "TOOL_TYPE_MOUSE",
                buttonState = 0,
            ),
        )
    }
}
