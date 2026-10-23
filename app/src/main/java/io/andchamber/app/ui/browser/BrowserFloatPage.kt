package io.andchamber.app.ui.browser

import android.view.MotionEvent
import android.view.View
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.webkit.WebView
import android.widget.FrameLayout
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.andchamber.app.R
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.browser.UrlDecision
import kotlinx.coroutines.launch

/**
 * Projection of [NativeBrowserPresenter]. It does not create a page, a registry, or a URL load of
 * its own: navigation goes through the presenter's explicit open. The presenter owns the WebView;
 * releasing the AndroidView only detaches it.
 *
 * The page is held at one fixed phone-sized viewport by [FixedViewportContainer]; the window only
 * scales it. Window changes (move, resize, full screen, small window) are presentation only and
 * never reach the grant, the page's layout, its refs or its scroll position.
 */
@Composable
internal fun BrowserFloatPage(
    presenter: NativeBrowserPresenter,
    bottomClearance: Dp = 88.dp,
    /**
     * Something of the app is drawn over the page in the same window (the Open web page card, whose
     * address may carry a sign-in token). The page is then not presented: input and capture are
     * gated exactly as for a folded window, without revoking, and come back when it closes.
     */
    presentationOccluded: Boolean = false,
) {
    val state by presenter.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ui = remember(presenter) { BrowserWindowUiState() }
    var clearingSiteData by remember(presenter) { mutableStateOf(false) }
    // The drawn page's scale and offset, for the mouse pointer overlay.
    var transform by remember(presenter) { mutableStateOf(ViewportTransform(1f, 0f, 0f)) }

    // The presenter asks for the small window once per new value (an agent bringing back a hidden
    // window, or an agent open). A full screen the person chose while the window stays shown is
    // never undone by ordinary agent actions: those do not advance the serial.
    var seenRequest by remember(presenter) { mutableStateOf(state.presentationRequestSerial) }
    LaunchedEffect(state.presentationRequestSerial) {
        if (state.presentationRequestSerial != seenRequest) {
            seenRequest = state.presentationRequestSerial
            ui.fullRequested = false
        }
    }

    if (!state.shown) {
        // Hidden by the person: the page is kept. A slim tab on the leading edge brings it back,
        // presentation only (no grant). It sits away from the chat header and the composer.
        if (state.hidden && state.tabs.isNotEmpty() && state.hostAttached && !state.paused) {
            BrowserShowTab(onShow = { presenter.restore() })
        }
        return
    }

    val tab = state.tabs.firstOrNull { it.id == state.selectedTabId }
    val view = presenter.currentView() as? WebView
    val accessibilityEnabled = rememberAccessibilityEnabled()
    val statusResource = browserFloatStatusResource(
        pageError = state.pageError,
        agentOn = state.agentOn,
        pageReady = state.pageReady,
        pageRetired = state.pageRetired,
        mouseMode = state.mouseMode,
        accessibilityEnabled = accessibilityEnabled,
        awaitingAllow = state.awaitingAllow,
    )
    // No resource: the page's own error text, shown as it was reported.
    val status = if (statusResource != null) stringResource(statusResource) else state.pageError.orEmpty()
    // The address the page actually shows (the WebView's own main-frame URL), never a typed or
    // agent-reported value; read again on every presenter update (load, ready, navigation).
    val address = browserShownAddress(view?.url, tab?.url)
    val owner = tab?.owner
    // Address bar and Reload are the person's own navigation. They go through one presenter seam
    // (see [BrowserManualNavigation]) so the UI never loads a URL into the WebView itself.
    val manual = remember(presenter) { BrowserManualNavigation(presenter) }
    fun navigate(url: String) {
        scope.launch { manual.navigate(url) }
    }
    fun reload() {
        scope.launch { manual.reload() }
    }

    BrowserFloatWindow(
        ui = ui,
        title = view?.title.orEmpty(),
        address = address,
        status = status,
        agentOn = state.agentOn,
        // Resume is offered only when it can work: a live agent connection and a ready page.
        canResume = state.connected && !state.pageRetired && !state.mousePressActive &&
            browserFloatCanAllow(state.eligible, state.pageReady, owner),
        loading = !state.pageReady && !state.pageRetired && state.pageError == null,
        desktopOn = state.desktopMode,
        mouseOn = state.mouseMode,
        mousePressActive = state.mousePressActive,
        isPagePressActive = { presenter.state.value.mousePressActive },
        onResume = { scope.launch { presenter.resumeAgent() } },
        onTakeOver = { presenter.takeover() },
        onHide = { presenter.background() },
        onClosePage = { presenter.close(state.selectedTabId.orEmpty()) },
        onNavigate = if (owner.isNullOrBlank() || state.pageRetired) null else ::navigate,
        onReload = if (address != null && !owner.isNullOrBlank() && !state.pageRetired) ::reload else null,
        // No Back: every navigation here is a fresh controlled load in a new page, so there is no
        // history that can be replayed safely. A Back button would be a promise it can't keep.
        // One gate for both reasons: the window's own layout and an overlay on top of it. Neither
        // path may clear the other's block.
        onPresentationBlocked = { presenter.setPresentationBlocked(it || presentationOccluded) },
        // Modes live in the presenter, so the window, the agent tools and a recreated screen agree.
        onDesktopMode = { presenter.setDesktopMode(it) },
        onMouseMode = { presenter.setMouseMode(it) },
        onClearSiteData = {
            if (!clearingSiteData && !presenter.state.value.mousePressActive) {
                clearingSiteData = true
                scope.launch {
                    try {
                        val result = BrowserSiteDataClearer(PresenterSiteDataOps(presenter)).clear()
                        Toast.makeText(context, browserSiteDataResultResource(result), Toast.LENGTH_SHORT).show()
                    } finally {
                        clearingSiteData = false
                    }
                }
            }
        },
        clearingSiteData = clearingSiteData,
        bottomClearance = bottomClearance,
    ) { layoutBlocked, viewportPx ->
        val presentationBlocked = layoutBlocked || presentationOccluded
        // A real viewport change (rotation, split screen) resizes the page; that one is a layout
        // change. Window presentation changes never reach here.
        var lastViewport by remember(presenter) { mutableStateOf(viewportPx) }
        LaunchedEffect(viewportPx) {
            if (viewportPx != lastViewport) {
                lastViewport = viewportPx
                presenter.onGeometryChanged()
            }
        }
        Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
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
                    val page = presenter.currentView()
                    if (page == null) {
                        Text(stringResource(R.string.browser_unavailable_body))
                    } else {
                        BrowserPageMount(
                            presenter = presenter,
                            view = page,
                            viewportPx = viewportPx,
                            presentationBlocked = presentationBlocked,
                            onTransform = { transform = it },
                        )
                    }
                }
                val pointer = state.pointerUi
                if (state.mouseMode && pointer.geometryReady && pointer.viewHandle == state.viewHandle) {
                    MousePointer(transform.toContainerX(pointer.pointerViewX), transform.toContainerY(pointer.pointerViewY))
                }
            }
        }
    }
}

@Composable
private fun BrowserPageMount(
    presenter: NativeBrowserPresenter,
    view: View,
    viewportPx: IntSize,
    presentationBlocked: Boolean,
    onTransform: (ViewportTransform) -> Unit,
) {
    AndroidView(
        factory = { context ->
            // The old container may not be released yet; a view can have only one parent.
            (view.parent as? ViewGroup)?.removeView(view)
            view.setOnTouchListener { touched, event -> routePageTouch(presenter, view, touched, event) }
            BrowserPageContainer(context).also { container ->
                container.setViewport(viewportPx.width, viewportPx.height)
                container.onPresentationChanged = {
                    onTransform(container.transform)
                    // The drawn page moved or changed scale; the page itself did not change. Only a
                    // fresh geometry reading is asked for: no refs dropped, no request cancelled,
                    // no grant touched (unlike onGeometryChanged).
                    if (view.parent === container && view === presenter.currentView()) presenter.onGeometrySettled()
                }
                container.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }
        },
        update = { container ->
            container.setViewport(viewportPx.width, viewportPx.height)
            // Synchronous main-thread ordering: the core gate first, then the native hide.
            applyBrowserPresentation(
                blocked = presentationBlocked,
                gate = presenter::setPresentationBlocked,
                applyNative = { container.setBlocked(it) },
            )
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { released ->
            // Releasing A after reattaching its page to B must not remove B's listener.
            if (view.parent === released) {
                view.setOnTouchListener(null)
                released.removeView(view)
            }
            released.onPresentationChanged = null
        },
    )
}

/**
 * Touches on the page. The platform has already mapped them through the page's scale into page
 * pixels. Touching, dragging or scrolling the page is not a takeover: the agent keeps control
 * until the person taps Take over, hides or closes the window.
 */
private fun routePageTouch(presenter: NativeBrowserPresenter, view: View, touched: View, event: MotionEvent): Boolean =
    if (!browserInputSourceMatches(touched, view) || !browserInputSourceMatches(touched, presenter.currentView())) {
        // Old mounts must not route input into the selected page.
        true
    } else if (presenter.isInjectingMouse()) {
        // Our own synthetic mouse event: let the WebView handle it.
        false
    } else if (!presenter.admitPageInput(event)) {
        // Retired-page input and leftover MOVE/UP; core requires a legitimate fresh DOWN.
        true
    } else if (presenter.state.value.mouseMode) {
        // Mouse mode: the finger is a touchpad; the raw touch never reaches the page.
        presenter.mouseInput(event, touched.width, touched.height)
        true
    } else {
        handoffBrowserTouchFocus(
            isDown = event.actionMasked == MotionEvent.ACTION_DOWN,
            inputAdmitted = true,
            beforeFocus = { presenter.notePageInteraction() },
            readState = {
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
            requestFocus = { view.requestFocus() },
        ).consumeTouch
    }

/** Address-bar and reload actions share the presenter's checked, same-tab navigation path. */
internal class BrowserManualNavigation(private val presenter: NativeBrowserPresenter) {
    suspend fun navigate(url: String): Boolean = presenter.navigateManual(url).ok
    suspend fun reload(): Boolean = presenter.reloadManual().ok
}

/** The shown page's own https address; the tab's recorded address only until the page has one. */
internal fun browserShownAddress(pageUrl: String?, tabUrl: String?): String? {
    fun trusted(url: String?) = url?.takeIf {
        it.isNotBlank() && BrowserUrlPolicy.canonicalOrigin(it) != null &&
            BrowserUrlPolicy().checkSyntax(it) is UrlDecision.Allow
    }
    return trusted(pageUrl) ?: trusted(tabUrl)
}

/** Slim tab on the leading edge that brings a hidden browser back. 48dp touch target. */
@Composable
private fun BrowserShowTab(onShow: () -> Unit) {
    val label = stringResource(R.string.browser_show)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = (maxHeight * 0.38f).coerceAtLeast(56.dp)
        val shape = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
        Box(
            modifier = Modifier
                .offset { IntOffset(0, top.roundToPx()) }
                .size(width = 48.dp, height = 48.dp)
                // Keeps a tap here from starting the system Back swipe.
                .systemGestureExclusion()
                .clickable(role = Role.Button, onClickLabel = label, onClick = onShow)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .width(30.dp)
                    .height(44.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f), shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
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
 * 2. The agent is in control. Who controls the page always shows.
 * 3. Mouse mode while an accessibility service is on. Websites get no hover then; clicks, drags and
 *    scrolling still work. A retired page has nothing to hover and never shows it. Loading does not
 *    replace it, so the line stays put while links load.
 * 4. Page loading.
 * 5. Legacy: the agent opened the page and control has not started yet.
 * 6. The agent is paused and the person is in control.
 */
internal fun browserFloatStatusResource(
    pageError: String?,
    agentOn: Boolean,
    pageReady: Boolean,
    pageRetired: Boolean,
    mouseMode: Boolean,
    accessibilityEnabled: Boolean,
    awaitingAllow: Boolean = false,
): Int? = when {
    pageError != null -> browserFloatErrorResource(pageError)
    agentOn -> R.string.browser_status_on
    mouseMode && accessibilityEnabled && !pageRetired -> R.string.browser_mouse_hover_accessibility
    !pageReady -> R.string.browser_page_loading
    awaitingAllow -> R.string.browser_float_status_agent_waiting
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

/**
 * The page's parent: fixed viewport, scaled presentation, and the input/focus/accessibility gate
 * used while the window is folded. INVISIBLE keeps the page measured at its fixed size.
 */
private class BrowserPageContainer(context: Context) : FixedViewportContainer(context) {
    private var blocked = false
    fun setBlocked(next: Boolean) {
        if (blocked == next) return
        blocked = next
        descendantFocusability = if (next) FOCUS_BLOCK_DESCENDANTS else FOCUS_BEFORE_DESCENDANTS
        importantForAccessibility = if (next) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (next && hasFocus()) {
            // End the page's own edit once; don't focus anything on recovery or touch the DOM with
            // JS. Only when the page has focus: the card covering it is about to open its keyboard.
            clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(windowToken, 0)
        }
        visibility = if (next) View.INVISIBLE else View.VISIBLE
        // Shown and hidden frames must never be confused by capture.
        invalidatePresentation()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = blocked || super.dispatchTouchEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = blocked || super.dispatchGenericMotionEvent(event)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = blocked || super.dispatchKeyEvent(event)
}

/** Pointer position in container pixels; only the arrow's visual size uses dp. */
@Composable
private fun MousePointer(x: Float, y: Float) {
    Canvas(Modifier.fillMaxSize()) {
        if (!x.isFinite() || !y.isFinite()) return@Canvas
        val arrow = 18.dp.toPx()
        translate(left = x, top = y) {
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
