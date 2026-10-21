package io.andchamber.app.ui.browser

import android.view.MotionEvent
import android.view.View
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.FrameLayout
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.andchamber.app.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Projection of [NativeBrowserPresenter]. It does not create a page, a registry, or a URL load.
 * The presenter owns the WebView; releasing the AndroidView only detaches it.
 */
@Composable
internal fun BrowserFloatPage(
    presenter: NativeBrowserPresenter,
    bottomClearance: Dp = 88.dp,
) {
    val state by presenter.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Main-only observation counter survives target-view keys; no geometry/ref epoch guessing.
    val contentHandoff = remember(presenter) { WindowContentHandoff() }
    if (!state.shown) return
    val context = LocalContext.current
    // Toasts do not resize the page or feed back into geometry/scroll restoration.
    LaunchedEffect(presenter) {
        presenter.windowRestoreResult.drop(1).collect { result ->
            browserScrollRestoreResource(result)?.let { resource ->
                if (presenter.state.value.shown) Toast.makeText(context, resource, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val tab = state.tabs.firstOrNull { it.id == state.selectedTabId }
    val accessibilityEnabled = rememberAccessibilityEnabled()
    val statusResource = browserFloatStatusResource(
        pageError = state.pageError,
        agentOn = state.agentOn,
        pageReady = state.pageReady,
        pageRetired = state.pageRetired,
        mouseMode = state.mouseMode,
        accessibilityEnabled = accessibilityEnabled,
    )
    // No resource: the page's own error text, shown as it was reported.
    val status = if (statusResource != null) stringResource(statusResource) else state.pageError.orEmpty()
    BrowserFloatWindow(
        title = tab?.title.orEmpty(),
        status = status,
        agentOn = state.agentOn,
        canAllow = !state.pageRetired && !state.mousePressActive &&
            browserFloatCanAllow(state.eligible, state.pageReady, tab?.owner),
        mousePressActive = state.mousePressActive,
        isPagePressActive = { presenter.state.value.mousePressActive },
        onAllow = { scope.launch { presenter.allow() } },
        onTakeOver = { presenter.takeover() },
        onClose = { presenter.close(state.selectedTabId.orEmpty()) },
        onPresentationBlocked = { presenter.setPresentationBlocked(it) },
        onGeometryChanged = { presenter.onGeometryChanged() },
        onGeometrySettled = { presenter.onGeometrySettled() },
        beforeExpansionChange = { next ->
            val prepared = presenter.beforeWindowExpansionChange(next)
            currentCoroutineContext().ensureActive()
            contentHandoff.prepare(prepared, presenter.currentView(), presenter.state.value.viewHandle) { token, floor ->
                presenter.bindWindowContent(token, floor)
            }
        },
        onScrollNotSaved = { reason ->
            Toast.makeText(context, browserScrollNotSavedResource(reason), Toast.LENGTH_SHORT).show()
        },
        // Modes live in the presenter, so the window, the agent tools and a recreated screen agree.
        desktopOn = state.desktopMode,
        mouseOn = state.mouseMode,
        onDesktopMode = { presenter.setDesktopMode(it) },
        onMouseMode = { presenter.setMouseMode(it) },
        bottomClearance = bottomClearance,
    ) { renderedChange, acknowledge, presentationBlocked ->
        // Immutable render request, captured now rather than looked up in a later callback.
        val contentRender = contentHandoff.rendered(renderedChange)
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        if (state.pageRetired) {
            BrowserRetiredPage(onReopen = {
                val result = presenter.reopenRetiredPage()
                browserReopenFailureResource(result.ok, result.message)?.let {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                }
                // Core state alone removes the retired placeholder; never infer a grant here.
            })
        } else {
        key(state.viewHandle, state.viewGeneration) {
            val view = presenter.currentView()
            if (view == null) {
                androidx.compose.material3.Text(stringResource(R.string.browser_unavailable_body))
            } else {
                val mount = remember(view) { BrowserViewMount() }
                AndroidView(
                    factory = { context ->
                        presenter.onGeometryChanged()
                        // The old container may not be released yet; a view can have only one parent.
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.setOnTouchListener { touched, event ->
                            if (!browserInputSourceMatches(touched, view) ||
                                !browserInputSourceMatches(touched, presenter.currentView())) {
                                // Old mounts must not route input or takeover into the selected page.
                                true
                            } else if (presenter.isInjectingMouse()) {
                                // Our own synthetic mouse event: let the WebView handle it.
                                false
                            } else if (!presenter.admitPageInput(event)) {
                                // Consume retired-page input and leftover MOVE/UP before any
                                // takeover or forwarding. Core requires a legitimate fresh DOWN.
                                true
                            } else if (presenter.state.value.mouseMode) {
                                // Mouse mode: the finger is a touchpad. The presenter takes over first,
                                // then injects real mouse events; the raw touch never reaches the page.
                                presenter.mouseInput(event, touched.width, touched.height)
                                true
                            } else {
                                handoffBrowserTouchFocus(
                                    isDown = event.actionMasked == MotionEvent.ACTION_DOWN,
                                    // The admission branch above succeeded for this exact event.
                                    inputAdmitted = true,
                                    beforeFocus = {
                                        presenter.notePageInteraction()
                                        presenter.takeover()
                                    },
                                    readState = {
                                        // Takeover may synchronously detach/retire a page. Never use
                                        // the composition's state or transfer focus to a replacement.
                                        val latest = presenter.state.value
                                        BrowserTouchFocusState(
                                            touched = touched,
                                            original = view,
                                            current = presenter.currentView(),
                                            selectedTabPresent = latest.tabs.any { it.id == latest.selectedTabId },
                                            shown = latest.shown,
                                            retired = latest.pageRetired,
                                            hostAttached = latest.hostAttached,
                                            foreground = presenter.foreground && !latest.presentationBlocked,
                                            attached = view.isAttachedToWindow,
                                            viewShown = view.isShown,
                                            windowFocused = view.hasWindowFocus(),
                                            enabled = view.isEnabled,
                                            focusable = view.isFocusable,
                                            focusableInTouchMode = view.isFocusableInTouchMode,
                                            mouseMode = latest.mouseMode,
                                            injectingMouse = presenter.isInjectingMouse(),
                                            mousePressActive = latest.mousePressActive,
                                            focused = view.hasFocus(),
                                        )
                                    },
                                    // Flags already allow touch focus; do not change touch mode,
                                    // show the IME, or infer grant/readiness from this return value.
                                    requestFocus = { view.requestFocus() },
                                ).consumeTouch
                            }
                        }
                        BrowserPageContainer(context).also { container ->
                            mount.container = container
                            container.addView(view, FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                            ))
                        }
                    },
                    update = { container ->
                        // Synchronous main-thread ordering: revoke/cancel BEFORE hiding or ending
                        // editing. Core's idempotent setter does not restore authority on false.
                        applyBrowserPresentation(
                            blocked = presentationBlocked,
                            gate = presenter::setPresentationBlocked,
                            applyNative = { container.setBlocked(it) },
                        )
                    },
                    modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                        val container = mount.container ?: return@onGloballyPositioned
                        val position = coordinates.positionInWindow()
                        val rect = WindowContentRect(position.x, position.y, coordinates.size.width, coordinates.size.height)
                        // This is the full actual content allocation, not clipped Surface bounds or
                        // a copy of the current native child frame. A same-size fresh callback counts.
                        val allocation = contentHandoff.allocated(contentRender, view, container, rect)
                        when (val report = contentHandoff.report(
                            allocation, presenter.currentView(), view.parent, presenter.state.value.viewHandle,
                            coordinates.isAttached && view.isAttachedToWindow && view.isShown, acknowledge,
                        )) {
                            WindowContentReport.Ignore -> Unit
                            WindowContentReport.GeometryOnly -> {
                                val bounds = Rect(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height)
                                if (mount.bounds != bounds || renderedChange != null) {
                                    mount.bounds = bounds
                                    val revision = ++mount.revision
                                    // Ordinary drag/IME/Enlarge notification only. Posting is not used
                                    // as evidence of a new restore allocation and carries no target.
                                    view.postOnAnimation {
                                        if (mount.revision == revision && mount.container === container &&
                                            view.parent === container && view === presenter.currentView()) {
                                            presenter.onGeometrySettled()
                                        }
                                    }
                                }
                            }
                            is WindowContentReport.Restore -> {
                                mount.bounds = Rect(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height)
                                ++mount.revision
                                val target = report.target
                                scope.launch {
                                    if (mount.container === container && view.parent === container &&
                                        view.isAttachedToWindow && view === presenter.currentView()) {
                                        presenter.onWindowContentSettled(target)
                                    }
                                }
                            }
                        }
                    },
                    onRelease = { released ->
                        ++mount.revision
                        // Releasing A after reattaching its page to B must not remove B's listener.
                        if (view.parent === released) {
                            view.setOnTouchListener(null)
                            released.removeView(view)
                        }
                        if (mount.container === released) mount.container = null
                    },
                )
            }
        }
         val pointer = state.pointerUi
         if (state.mouseMode && pointer.geometryReady && pointer.viewHandle == state.viewHandle) {
             MousePointer(pointer.pointerViewX, pointer.pointerViewY)
         }
        }
        }
    }
    LaunchedEffect(state.shown) {
        if (!state.shown) presenter.background()
    }
}

/** Referential view identity only; null (including a retired current page) never matches. */
internal fun browserInputSourceMatches(source: Any?, current: Any?): Boolean =
    source != null && source === current

/** Availability is a projection only; allow() still owns all native admission checks. */
internal fun browserFloatCanAllow(eligible: Boolean, pageReady: Boolean, owner: String?): Boolean =
    eligible && pageReady && !owner.isNullOrBlank()

/** Exact internal messages only. Do not reinterpret arbitrary website error descriptions. */
internal fun browserFloatErrorResource(error: String?): Int? = when (error) {
    "mouse-gesture-interrupted" -> R.string.browser_mouse_gesture_interrupted
    "mode-reopen-required" -> R.string.browser_mode_reopen_required
    "reopen-required", "Re-open this page before allowing control" -> R.string.browser_reopen_required
    "mouse unsupported" -> R.string.browser_mouse_unsupported
    else -> null
}

/**
 * The status line. Null means the page's own error text, shown as it was reported. The first match wins:
 * 1. A page error: retired page, reopen required, layout change, mouse unsupported, or the
 *    page's own load error, such as a refused address or an untrusted certificate.
 * 2. Agent control is on. Who controls the page always shows.
 * 3. Mouse mode while an accessibility service is on. Websites get no hover then; clicks, drags and
 *    scrolling still work. A retired page has nothing to hover and never shows it. Loading does not
 *    replace it, so the line stays put while links load.
 * 4. Page loading.
 * 5. Browsing by hand.
 * With Mouse mode or accessibility off, the line is exactly what it was before the notice existed.
 * When the keyboard covers the window, its own notice hides this line.
 */
internal fun browserFloatStatusResource(
    pageError: String?,
    agentOn: Boolean,
    pageReady: Boolean,
    pageRetired: Boolean,
    mouseMode: Boolean,
    accessibilityEnabled: Boolean,
): Int? = when {
    pageError != null -> browserFloatErrorResource(pageError)
    agentOn -> R.string.browser_status_on
    mouseMode && accessibilityEnabled && !pageRetired -> R.string.browser_mouse_hover_accessibility
    !pageReady -> R.string.browser_page_loading
    else -> R.string.browser_float_status_manual
}

internal fun browserScrollNotSavedResource(reason: ScrollNotSavedReason): Int = when (reason) {
    ScrollNotSavedReason.LOADING, ScrollNotSavedReason.NO_WITNESS -> R.string.browser_scroll_waiting
    else -> R.string.browser_scroll_not_saved
}

internal fun browserReopenFailureResource(ok: Boolean, message: String?): Int? =
    if (ok) null else browserFloatErrorResource(message) ?: R.string.browser_reopen_failed

internal fun browserScrollRestoreResource(result: RestoreResult?): Int? = when (result) {
    is RestoreResult.Clamped -> R.string.browser_scroll_clamped
    is RestoreResult.NotSaved -> R.string.browser_scroll_not_restored
    // Successful readback needs no toast. Superseded/intentional discard must not imply success.
    is RestoreResult.Restored, is RestoreResult.Discarded, null -> null
}

/**
 * Whether any accessibility service is on, kept current while the window is shown. Chromium keeps
 * hover from the page while one is on, and the Mouse mode status line has to say so.
 */
@Composable
private fun rememberAccessibilityEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var enabled by remember(manager) { mutableStateOf(manager?.isEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { enabled = it }
        manager?.addAccessibilityStateChangeListener(listener)
        // A change between composition and registration reached no listener: read it once more.
        enabled = manager?.isEnabled == true
        onDispose { manager?.removeAccessibilityStateChangeListener(listener) }
    }
    return enabled
}

/** Main-thread-only container ownership. Not Compose state: bounds reports cannot recompose it. */
private class BrowserViewMount {
    var container: FrameLayout? = null
    var bounds: Rect? = null
    var revision: Long = 0L
}

/** Kept in the same AndroidView slot while IME/layout changes. INVISIBLE retains measurement. */
private class BrowserPageContainer(context: Context) : FrameLayout(context) {
    private var blocked = false
    fun setBlocked(next: Boolean) {
        if (blocked == next) return
        blocked = next
        descendantFocusability = if (next) FOCUS_BLOCK_DESCENDANTS else FOCUS_BEFORE_DESCENDANTS
        importantForAccessibility = if (next) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (next) {
            // End this edit once; don't focus anything on recovery or touch the DOM with JS.
            clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(windowToken, 0)
        }
        visibility = if (next) View.INVISIBLE else View.VISIBLE
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = blocked || super.dispatchTouchEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = blocked || super.dispatchGenericMotionEvent(event)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = blocked || super.dispatchKeyEvent(event)
}

/** Core supplies WebView-local physical pixels; only the arrow's visual size uses dp. */
@Composable
private fun MousePointer(viewX: Float, viewY: Float) {
    Canvas(Modifier.fillMaxSize()) {
        if (!viewX.isFinite() || !viewY.isFinite()) return@Canvas
        val arrow = 18.dp.toPx()
        translate(left = viewX, top = viewY) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(0f, arrow)
                lineTo(arrow * .32f, arrow * .72f)
                lineTo(arrow * .72f, arrow * .72f)
                close()
            }
            drawPath(path, Color.White)
            drawPath(path, Color.Black, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}
