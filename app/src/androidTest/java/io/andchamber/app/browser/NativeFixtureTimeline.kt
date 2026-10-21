package io.andchamber.app.browser

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.andchamber.app.ui.browser.NativeBrowserPresenter

/** Main-thread only. Original fixture views, additive listeners, no focus/input/insets mutation. */
internal class NativeFixtureTimeline(
    private val presenter: () -> NativeBrowserPresenter?,
    private val activity: () -> Activity?,
) {
    private val ring = NativeFixtureTimelineRing(SystemClock::elapsedRealtime)
    private var binding: Binding? = null

    fun install(view: View) {
        binding?.close()
        binding = Binding(view).also { it.install() }
    }

    fun mark(kind: NativeFixtureTimelineRing.Kind) { binding?.sample(kind) }
    fun json(): String = ring.json()
    fun summary() = org.json.JSONObject().put("file", "timeline.json")
        .put("seq", ring.sequence).put("overflow", ring.overflow)
    fun close() { binding?.close(); binding = null }

    private inner class Binding(val view: View) : View.OnAttachStateChangeListener {
        private var closed = false
        private var tree: ViewTreeObserver? = null
        private var lastLayout: List<Long?>? = null
        private val focus = ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
            sample(NativeFixtureTimelineRing.Kind.FOCUS, old, new)
        }
        private val layout = ViewTreeObserver.OnGlobalLayoutListener {
            sample(NativeFixtureTimelineRing.Kind.LAYOUT)
        }
        fun install() {
            view.addOnAttachStateChangeListener(this)
            observeTree()
            sample(NativeFixtureTimelineRing.Kind.INSTALL, allowUnselected = true)
        }
        private fun observeTree() {
            unobserveTree()
            // Observe the window tree even while the target is detached (IME collapse is relevant).
            tree = (activity()?.window?.decorView ?: view).viewTreeObserver.also {
                it.addOnGlobalFocusChangeListener(focus)
                it.addOnGlobalLayoutListener(layout)
            }
        }
        private fun unobserveTree() {
            tree?.takeIf { it.isAlive }?.let {
                it.removeOnGlobalFocusChangeListener(focus)
                it.removeOnGlobalLayoutListener(layout)
            }
            tree = null
        }
        override fun onViewAttachedToWindow(v: View) {
            observeTree()
            sample(NativeFixtureTimelineRing.Kind.ATTACH)
        }
        override fun onViewDetachedFromWindow(v: View) {
            sample(NativeFixtureTimelineRing.Kind.DETACH)
            // A selected detached view can be reattached by Compose. A retired/replaced one cannot
            // write further events; remove all its listeners rather than following a stale source.
            if (presenter()?.currentView() !== view) close()
        }
        fun sample(kind: NativeFixtureTimelineRing.Kind, old: View? = null, new: View? = null,
                   allowUnselected: Boolean = false) {
            if (closed) return
            val owner = presenter()
            val current = owner?.currentView()
            if (!allowUnselected && current !== view) {
                close()
                return
            }
            val state = owner?.state?.value
            val decor = activity()?.window?.decorView
            val insets = decor?.let { ViewCompat.getRootWindowInsets(it) }
            val ime = WindowInsetsCompat.Type.ime()
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val rect = Rect()
            val visible = view.getGlobalVisibleRect(rect)
            var ancestor = view.parent as? View
            var holder: View? = null
            while (ancestor != null) {
                if (ancestor.javaClass.name == "androidx.compose.ui.viewinterop.ViewFactoryHolder" ||
                    ancestor.javaClass.name == "androidx.compose.ui.viewinterop.AndroidViewHolder") holder = ancestor
                ancestor = ancestor.parent as? View
            }
            fun id(v: Any?): Long? = v?.let { System.identityHashCode(it).toLong() }
            fun flag(v: Boolean?): Long? = v?.let { if (it) 1L else 0L }
            val values = listOf(
                state?.viewGeneration?.takeIf { current === view }, flag(owner?.let { current === view }),
                loc[0].toLong(), loc[1].toLong(), view.width.toLong(), view.height.toLong(),
                flag(view.isAttachedToWindow), flag(view.isShown), flag(view.hasFocus()), flag(view.hasWindowFocus()),
                id(activity()?.currentFocus), id(decor?.findFocus()), id(view.parent), id(holder),
                decor?.height?.toLong(), flag(insets?.isVisible(ime)), insets?.getInsets(ime)?.bottom?.toLong(),
                // Compose's target IME inset is not publicly observable from this native listener.
                null, rect.left.toLong(), rect.top.toLong(), rect.right.toLong(), rect.bottom.toLong(),
                flag(visible), flag(state?.shown), flag(state?.pageRetired), flag(state?.agentOn),
                id(old), id(new), null,
            )
            // Unchanged global-layout notifications add no evidence and can evict the first tap.
            if (kind == NativeFixtureTimelineRing.Kind.LAYOUT) {
                if (values == lastLayout) return
                lastLayout = values
            }
            ring.record(kind, System.identityHashCode(view), values)
        }
        fun close() {
            if (closed) return
            closed = true
            unobserveTree()
            view.removeOnAttachStateChangeListener(this)
        }
    }
}
