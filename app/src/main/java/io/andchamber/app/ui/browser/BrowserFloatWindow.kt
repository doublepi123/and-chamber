package io.andchamber.app.ui.browser

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.andchamber.app.R
import io.andchamber.app.ui.components.StatusDot
import io.andchamber.app.ui.components.StatusTone
import io.andchamber.app.ui.theme.ChamberTextStyles
import io.andchamber.app.ui.web.BrowserUrlCheck
import io.andchamber.app.ui.web.checkBrowserUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Where the person put the browser window. Hoisted by [BrowserFloatPage] so hiding and showing the
 * window keeps it. Presentation only: none of it reaches the page, the grant or the scroll.
 */
internal class BrowserWindowUiState {
    var fullRequested by mutableStateOf(false)
    var placement by mutableStateOf(FloatPlacement())
}

/**
 * The in-app browser window: a small movable window over the chat, or full screen.
 *
 * The page always keeps the same fixed phone-sized viewport ([browserViewportDp]); the window only
 * scales it. Moving, resizing, full screen and small window are presentation only: they never call
 * takeover, revoke, a geometry invalidation, a reload or a scroll restore, so an agent that is in
 * control keeps working through all of them. Hide, Close page and Take over are the only window
 * actions that stop the agent, and they do it through the presenter.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun BrowserFloatWindow(
    ui: BrowserWindowUiState,
    title: String,
    /** The address of the page actually shown (the main-frame URL), or null before it is known. */
    address: String?,
    status: String,
    agentOn: Boolean,
    canResume: Boolean,
    /**
     * The agent keeps a read-only grant on this page (an unconfirmed action). Take over is offered
     * so the person can revoke it; [agentOn] stays false.
     */
    agentReadOnly: Boolean = false,
    /**
     * The app has confirmed the page needs a fresh load before the agent can use it again (an
     * earlier action's outcome is unknown). Resume is then replaced by Reopen.
     */
    reopenNeeded: Boolean = false,
    /** Reopen can run now (known address, no press, presentable, not already reopening). */
    canReopen: Boolean = false,
    loading: Boolean,
    desktopOn: Boolean,
    mouseOn: Boolean,
    mousePressActive: Boolean = false,
    isPagePressActive: () -> Boolean = { mousePressActive },
    onResume: () -> Unit,
    onTakeOver: () -> Unit,
    /** The person's own Reopen tap: one fresh load of the shown address. Never a replay. */
    onReopen: () -> Unit = {},
    onHide: () -> Unit,
    onClosePage: () -> Unit,
    /** Opens a checked address in this window. Null hides address editing. */
    onNavigate: ((String) -> Unit)? = null,
    /** Loads the shown address again. Null hides Reload. */
    onReload: (() -> Unit)? = null,
    onPresentationBlocked: (Boolean) -> Unit,
    onDesktopMode: (Boolean) -> Unit = {},
    onMouseMode: (Boolean) -> Unit = {},
    /** Runs only after the person confirms. Null hides the menu item. */
    onClearSiteData: (() -> Unit)? = null,
    clearingSiteData: Boolean = false,
    /** Height of the chat composer above the keyboard; a small window stays above it. */
    bottomClearance: Dp = 72.dp,
    /**
     * The open pages, in order, from [browserTabEntries]. With two or more, the address shows a
     * compact "2/3" that opens the page list. Presentation only: selecting goes through
     * [onSelectTab], closing through [onCloseTab].
     */
    tabs: List<BrowserTabEntry> = emptyList(),
    onSelectTab: (String) -> Unit = {},
    onCloseTab: (String) -> Unit = {},
    /** Opens a checked address as a new page, keeping the shown one. Null hides New page. */
    onNewTab: ((String) -> Unit)? = null,
    /**
     * Computer layout's page size in CSS px (the agent's choice, default 1920 x 1080), or null in
     * phone layout. It changes only the page's fixed size and the window's proportions; full
     * screen, small window and the window's place stay as they are.
     */
    desktopViewport: IntSize? = null,
    /**
     * The page area. [blocked]: not presented (folded). [viewportPx]: the fixed page size.
     * [keyboardReferencePx]: while the keyboard is open, the page area this window has with the
     * keyboard closed (the page keeps that presentation's scale); null while it is closed.
     */
    content: @Composable (blocked: Boolean, viewportPx: IntSize, keyboardReferencePx: IntSize?) -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val latestPressCheck by rememberUpdatedState(isPagePressActive)
    var menu by remember { mutableStateOf(false) }
    var confirmDesktop by remember { mutableStateOf<Boolean?>(null) }
    var confirmClearSiteData by remember { mutableStateOf(false) }
    var tabList by remember { mutableStateOf(false) }
    // What the address area is editing: the shown page's address, or a new page's (empty start).
    var editTarget by remember { mutableStateOf<AddressEditTarget?>(null) }
    val editing = editTarget != null
    val tabPosition = browserTabPosition(tabs)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The parent (WebScreen) already removed system bars, cutout and the keyboard's target inset
        // (it changes once per show/hide, not per animation frame). Read here, inside the
        // constraints' own subcomposition, the same target inset belongs to the same layout pass as
        // maxHeight, so adding it back gives the keyboard-closed height without a mismatched frame.
        val imeBottom = WindowInsets.imeAnimationTarget.getBottom(density)
        val barsBottom = WindowInsets.systemBars.union(WindowInsets.displayCutout).getBottom(density)
        val imeOverlapDp = with(density) { (imeBottom - barsBottom).coerceAtLeast(0).toDp().value }
        val safeWidth = maxWidth.value
        val safeHeight = maxHeight.value + imeOverlapDp
        // The fixed page size is taken with the keyboard closed and then kept while it is open, so
        // a keyboard (or a frame where the inset and the constraints disagree) never resizes the
        // page. A new width (rotation, split screen) or a keyboard-closed height change updates it.
        val stable = remember { StableViewport() }
        val phoneViewport = stable.update(safeWidth, safeHeight, imeOpen = imeOverlapDp > 0f)
        val desktop = desktopViewport != null
        val (pageW, pageH) = browserPageViewportPx(
            desktop, desktopViewport?.width ?: 0, desktopViewport?.height ?: 0,
            with(density) { phoneViewport.first.dp.roundToPx() }, with(density) { phoneViewport.second.dp.roundToPx() },
        )
        val viewportPx = IntSize(pageW, pageH)
        // The window lays out with the page's proportions; the page itself keeps its fixed size.
        val (vpW, vpH) = browserLayoutViewportDp(
            phoneViewport, desktop, desktopViewport?.width ?: 0, desktopViewport?.height ?: 0,
        )
        val layout = browserWindowLayout(
            safeWidth, safeHeight, imeOverlapDp, bottomClearance.value, vpW, vpH,
            fullRequested = ui.fullRequested, placement = ui.placement,
        )
        val latestLayout by rememberUpdatedState(layout)
        val blocked = layout.blocked
        // The keyboard only covers part of the window for a while: the page keeps the scale of the
        // same presentation with the keyboard closed (a full screen the keyboard forced is compared
        // with a keyboard-closed full screen, not the small window it replaced). Taken from the
        // keyboard-closed safe area and the keyboard's target state, so it is stable through the
        // keyboard animation and already known when the page is first shown with it open.
        val keyboardReferencePx = if (layout.imeOpen && !blocked) {
            val closed = browserWindowLayout(
                safeWidth, safeHeight, 0f, bottomClearance.value, vpW, vpH,
                fullRequested = layout.presentation == BrowserPresentation.Full, placement = ui.placement,
            )
            browserPageAreaDp(closed)?.let { (w, h) ->
                with(density) { IntSize(w.dp.roundToPx(), h.dp.roundToPx()) }
            }
        } else {
            null
        }
        SideEffect { onPresentationBlocked(blocked) }
        val full = layout.presentation == BrowserPresentation.Full
        val float = layout.presentation == BrowserPresentation.Float
        val action = browserControlAction(agentOn, reopenNeeded, canResume, agentReadOnly)
        // The list stays open while pages are closed one after another, and closes with its counter
        // (fewer than two pages) or when the window folds, so it never pops back up later.
        if (tabList && (blocked || tabs.size < 2)) SideEffect { tabList = false }

        // Back leaves full screen first, like a phone browser; it never hides or stops the agent.
        BackHandler(enabled = ui.fullRequested && !editing) { ui.fullRequested = false }
        BackHandler(enabled = editing) { editTarget = null }

        val window = layout.window
        Surface(
            modifier = Modifier
                .offset { IntOffset(window.left.dp.roundToPx(), window.top.dp.roundToPx()) }
                .width(window.width.dp)
                .height(window.height.dp)
                .testTag("BrowserFloatSurface")
                .semantics {
                    contentDescription = "BrowserFloatSurface"
                    // Device tests find the address, page counter and list by their test tags.
                    testTagsAsResourceId = true
                },
            shape = if (full) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = if (full) 0.dp else 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                // ---- Top bar: address and window buttons. Dragging it moves a small window. ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BROWSER_BAR_HEIGHT.dp)
                        .pointerInput(float) {
                            if (!float) return@pointerInput
                            detectDragGestures { change, drag ->
                                change.consume()
                                // Presentation only: no takeover, no revoke, no geometry reset.
                                ui.placement = movedFloatPlacement(
                                    latestLayout, ui.placement, drag.x / density.density, drag.y / density.density,
                                )
                            }
                        }
                        .padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val target = editTarget
                    val go: ((String) -> Unit)? = when (target) {
                        AddressEditTarget.Current -> onNavigate
                        AddressEditTarget.NewTab -> onNewTab
                        null -> null
                    }
                    // The entry went away while editing (page closed, owner gone): stop editing,
                    // so Back and the bar behave normally again.
                    if (target != null && go == null) SideEffect { editTarget = null }
                    if (target != null && go != null) {
                        // A new key per target: switching from editing this page to a new page
                        // starts a fresh field (empty for a new page), never the old text.
                        key(target) {
                            AddressEditor(
                                initial = if (target == AddressEditTarget.NewTab) "" else address.orEmpty(),
                                newTab = target == AddressEditTarget.NewTab,
                                modifier = Modifier.weight(1f),
                                onGo = { url ->
                                    editTarget = null
                                    go(url)
                                },
                                onRefused = { resource -> Toast.makeText(context, resource, Toast.LENGTH_SHORT).show() },
                                onCancel = { editTarget = null },
                            )
                        }
                    } else if (blocked) {
                        // Folded: there is no room for any page. Say so in place of the address;
                        // the only action offered is Hide.
                        Text(
                            text = stringResource(R.string.browser_keyboard_tight),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                .testTag("BrowserFloatTight")
                                .semantics { contentDescription = "BrowserFloatTight" },
                        )
                        IconButton(onClick = onHide, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.VisibilityOff, contentDescription = stringResource(R.string.browser_hide))
                        }
                    } else {
                        AddressLabel(
                            address = address,
                            title = title,
                            agentOn = agentOn,
                            modifier = Modifier.weight(1f),
                            onEdit = if (onNavigate != null) ({ editTarget = AddressEditTarget.Current }) else null,
                            // Shares the address pill; no extra bar and no extra 48dp button.
                            tabPosition = tabPosition,
                            onOpenTabs = { if (!latestPressCheck()) tabList = true },
                        )
                        run {
                            val fullLabel = stringResource(if (full) R.string.browser_restore else R.string.browser_enlarge)
                            // An automatic full screen (no room for a small window) has nothing to go back to.
                            if (!layout.autoFull || ui.fullRequested) {
                                IconButton(onClick = { ui.fullRequested = !full }, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        if (full) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                                        contentDescription = fullLabel,
                                    )
                                }
                            }
                        }
                        // Compact toolbar (keyboard open in full screen): the control action moves up
                        // here, so it is never out of reach.
                        if (!layout.showBottomBar) when (action) {
                            BrowserControlAction.TakeOver ->
                                IconButton(onClick = onTakeOver, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Rounded.PanTool, contentDescription = stringResource(R.string.browser_take_over),
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            BrowserControlAction.Reopen ->
                                IconButton(onClick = onReopen, enabled = canReopen, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.browser_reopen_page))
                                }
                            BrowserControlAction.Resume ->
                                IconButton(onClick = onResume, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Rounded.SmartToy, contentDescription = stringResource(R.string.browser_turn_on))
                                }
                            BrowserControlAction.None -> Unit
                        }
                        IconButton(onClick = onHide, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.VisibilityOff, contentDescription = stringResource(R.string.browser_hide))
                        }
                        run {
                            Box {
                                IconButton(
                                    onClick = { if (!latestPressCheck()) menu = true },
                                    enabled = !mousePressActive,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(
                                        if (mousePressActive) R.string.browser_finish_drag_first else R.string.browser_more))
                                }
                                BrowserMenu(
                                    expanded = menu,
                                    onDismiss = { menu = false },
                                    desktopOn = desktopOn,
                                    mouseOn = mouseOn,
                                    mousePressActive = mousePressActive,
                                    clearingSiteData = clearingSiteData,
                                    onReload = onReload?.let { reload -> { menu = false; reload() } },
                                    onNewTab = onNewTab?.let {
                                        { if (!latestPressCheck()) { menu = false; editTarget = AddressEditTarget.NewTab } }
                                    },
                                    onDesktop = { if (!latestPressCheck()) { menu = false; confirmDesktop = !desktopOn } },
                                    onMouse = { if (!latestPressCheck()) { menu = false; onMouseMode(!mouseOn) } },
                                    onClearSiteData = onClearSiteData?.let {
                                        { if (!latestPressCheck()) { menu = false; confirmClearSiteData = true } }
                                    },
                                    onClosePage = { menu = false; onClosePage() },
                                )
                                // The page list hangs from the top bar's end, like the menu, so it
                                // never takes a bar of its own and never reflows the page.
                                BrowserTabList(
                                    expanded = tabList && tabs.size >= 2 && !blocked,
                                    tabs = tabs,
                                    onDismiss = { tabList = false },
                                    onSelect = { id ->
                                        if (!latestPressCheck()) {
                                            tabList = false
                                            onSelectTab(id)
                                        }
                                    },
                                    onClose = { id -> if (!latestPressCheck()) onCloseTab(id) },
                                    onNewTab = onNewTab?.let {
                                        { if (!latestPressCheck()) { tabList = false; editTarget = AddressEditTarget.NewTab } }
                                    },
                                )
                            }
                        }
                    }
                }
                // ---- Page. The slot never leaves composition, so the WebView stays mounted. ----
                // The load line is drawn over the page so it never takes height from it: full
                // screen then shows the fixed viewport exactly 1:1.
                Box(
                    modifier = Modifier
                        .then(if (blocked) Modifier.height(0.dp) else Modifier.weight(1f, fill = true))
                        .fillMaxWidth()
                        .clip(if (full) RoundedCornerShape(0.dp) else RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .then(if (blocked) Modifier.clearAndSetSemantics {} else Modifier),
                ) {
                    content(blocked, viewportPx, keyboardReferencePx)
                    if (loading && !blocked) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                            trackColor = Color.Transparent,
                            gapSize = 0.dp,
                        )
                    }
                }
                // ---- Bottom bar: who is in control, and the one action that changes it. ----
                if (layout.showBottomBar) {
                    ControlBar(
                        status = status,
                        agentOn = agentOn,
                        action = action,
                        canReopen = canReopen,
                        resizable = float,
                        onResume = onResume,
                        onTakeOver = onTakeOver,
                        onReopen = onReopen,
                        onResize = { dx, dy ->
                            // The grip sits at the bottom-start corner: the window's top-end corner
                            // stays put, so dragging left/down grows the page and right/up shrinks it.
                            val current = latestLayout
                            val start = ui.placement.scale ?: current.scale
                            val scale = resizedFloatScale(
                                start, -dx, dy, vpW, vpH, current.minScale, current.maxScale,
                            )
                            val oldWidth = maxOf(vpW * start, MIN_FLOAT_WIDTH)
                            val right = (ui.placement.left ?: current.window.left) + oldWidth
                            val width = maxOf(vpW * scale, MIN_FLOAT_WIDTH)
                            ui.placement = FloatPlacement(
                                scale = scale,
                                left = right - width,
                                top = ui.placement.top ?: current.window.top,
                            )
                        },
                    )
                }
            }
        }
    }

    confirmDesktop?.let { requested ->
        AlertDialog(
            onDismissRequest = { confirmDesktop = null },
            title = { Text(stringResource(R.string.browser_mode_change_title)) },
            text = { Text(stringResource(R.string.browser_mode_change_body)) },
            confirmButton = {
                TextButton(enabled = !mousePressActive, onClick = {
                    if (latestPressCheck()) return@TextButton
                    confirmDesktop = null
                    onDesktopMode(requested)
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDesktop = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmClearSiteData && onClearSiteData != null) {
        AlertDialog(
            onDismissRequest = { confirmClearSiteData = false },
            title = { Text(stringResource(R.string.browser_clear_site_data_title)) },
            text = { Text(stringResource(R.string.browser_clear_site_data_body)) },
            confirmButton = {
                TextButton(
                    enabled = !mousePressActive && !clearingSiteData,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        if (latestPressCheck()) return@TextButton
                        confirmClearSiteData = false
                        onClearSiteData()
                    },
                ) { Text(stringResource(R.string.browser_clear_site_data_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClearSiteData = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The shown address: host first and strong, the rest muted, cut at the end. Tap to edit.
 *
 * With two or more pages a compact "2/3" sits at the pill's end. It is its own 48dp target (the
 * pill stays 40dp tall; the counter uses the full bar height), so it costs one button's width only
 * while there is more than one page, and the address itself stays one tap from editing.
 */
@Composable
private fun AddressLabel(
    address: String?,
    title: String,
    agentOn: Boolean,
    modifier: Modifier,
    onEdit: (() -> Unit)?,
    tabPosition: BrowserTabPosition? = null,
    onOpenTabs: () -> Unit = {},
) {
    val editLabel = stringResource(R.string.browser_address_edit)
    val parts = address?.let { splitAddress(it) }
    val fallback = title.ifBlank { stringResource(R.string.browser_entry_title) }
    // The accessible name is what the pill shows (the real address, else the title), never a fixed
    // word; the action label says that a tap edits it.
    val spoken = address ?: fallback
    val pillShape = RoundedCornerShape(20.dp)
    Box(modifier = modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
        // One 40dp pill behind both parts, so the counter reads as part of the address.
        Box(
            Modifier
                .matchParentSize()
                .padding(vertical = 4.dp)
                .clip(pillShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(pillShape)
                    .then(if (onEdit != null) Modifier.clickable(onClickLabel = editLabel, onClick = onEdit) else Modifier)
                    // Before the clearing modifier: semantics after it on this node are dropped.
                    .testTag(BROWSER_ADDRESS_TAG)
                    .clearAndSetSemantics {
                        contentDescription = spoken
                        if (onEdit != null) onClick(label = editLabel) { onEdit(); true }
                    }
                    .padding(start = 10.dp, end = if (tabPosition != null) 4.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(tone = if (agentOn) StatusTone.Live else StatusTone.Idle, size = 7.dp)
                Spacer(Modifier.width(6.dp))
                if (parts != null) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    text = if (parts == null) {
                        buildAnnotatedString { append(fallback) }
                    } else {
                        buildAnnotatedString {
                            append(parts.first)
                            withStyle(SpanStyle(color = muted)) { append(parts.second) }
                        }
                    },
                    style = ChamberTextStyles.code,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tabPosition != null) TabCounter(tabPosition, onOpenTabs)
        }
    }
}

/**
 * "2/3": which page is shown and how many are open. A tinted chip, so it is noticed next to the
 * monospace address, with tabular figures so it never jitters while switching. Opens the list.
 */
@Composable
private fun TabCounter(position: BrowserTabPosition, onOpen: () -> Unit) {
    val description = stringResource(R.string.browser_tabs_position, position.index, position.total)
    val showLabel = stringResource(R.string.browser_tabs_show)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = showLabel, onClick = onOpen)
            .testTag(BROWSER_TAB_COUNTER_TAG)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = showLabel) { onOpen(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(26.dp)
                .widthIn(min = 34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = browserTabPositionText(position),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
            )
        }
    }
}

/**
 * The open pages, hung from the top bar like the menu. Each row: an accent bar and tint on the shown
 * page, a meaningful title (the host when the page gave none) with its host below, and a 48dp close
 * button. Tapping a row shows that page; closing never switches by itself unless the shown page
 * is the one closed (the presenter picks the neighbour).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BrowserTabList(
    expanded: Boolean,
    tabs: List<BrowserTabEntry>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: (() -> Unit)?,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 240.dp, max = 320.dp)
            .semantics { testTagsAsResourceId = true }
            .testTag(BROWSER_TAB_LIST_TAG),
    ) {
        Text(
            text = stringResource(R.string.browser_tabs_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)
                .semantics { heading() },
        )
        tabs.forEachIndexed { index, tab -> TabRow(index, tab, onSelect, onClose) }
        if (onNewTab != null) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browser_new_tab)) },
                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = onNewTab,
                modifier = Modifier.testTag(BROWSER_TAB_LIST_NEW_TAG),
            )
        }
    }
}

@Composable
private fun TabRow(index: Int, tab: BrowserTabEntry, onSelect: (String) -> Unit, onClose: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val currentLabel = stringResource(R.string.browser_tab_current)
    val closeLabel = stringResource(R.string.browser_tab_close, tab.title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (tab.current) accent.copy(alpha = 0.10f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .selectable(selected = tab.current, role = Role.Tab, onClick = { onSelect(tab.id) })
                .semantics(mergeDescendants = true) {
                    if (tab.current) stateDescription = currentLabel
                }
                .testTag(browserTabItemTag(index))
                .padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The shown page carries a solid bar; the others keep the same indent, so titles align.
            Box(
                Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (tab.current) accent else Color.Transparent),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = tab.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (tab.current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (tab.current) accent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tab.host.isNotEmpty() && tab.host != tab.title) {
                    Text(
                        text = tab.host,
                        style = ChamberTextStyles.codeSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        IconButton(
            onClick = { onClose(tab.id) },
            modifier = Modifier.size(48.dp).testTag(browserTabCloseTag(index)),
        ) {
            Icon(Icons.Rounded.Close, contentDescription = closeLabel, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "https://a.example.com/x?y" -> ("a.example.com", "/x?y"); the scheme is always https here. */
internal fun splitAddress(url: String): Pair<String, String> {
    val rest = url.substringAfter("://", url)
    val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val tail = rest.substring(cut).let { if (it == "/") "" else it }
    return rest.substring(0, cut) to tail
}

/** What the address area is editing. */
internal enum class AddressEditTarget { Current, NewTab }

/** One row of the page list, display only. [title] is never blank. */
internal data class BrowserTabEntry(
    val id: String,
    val title: String,
    val host: String,
    val current: Boolean,
)

/** 1-based position of the shown page; only when there is more than one page. */
internal data class BrowserTabPosition(val index: Int, val total: Int)

/**
 * The page list from presenter state, in tab order. Title: the tab's own title, else (for the shown
 * page only) the WebView's title, else the host, else the generic name. Display text only.
 */
internal fun browserTabEntries(
    tabs: List<NativeBrowserTab>,
    selectedTabId: String?,
    shownTitle: String?,
    fallbackTitle: String,
): List<BrowserTabEntry> = tabs.map { tab ->
    val current = tab.id == selectedTabId
    val host = browserTabHost(tab.url)
    BrowserTabEntry(
        id = tab.id,
        title = browserTabTitle(tab.title, if (current) shownTitle else null, host, fallbackTitle),
        host = host,
        current = current,
    )
}

/** The first non-blank of: tab title, shown page title, host, generic name. Trimmed, one line. */
internal fun browserTabTitle(tabTitle: String?, shownTitle: String?, host: String, fallbackTitle: String): String =
    listOf(tabTitle, shownTitle, host).firstNotNullOfOrNull { candidate ->
        candidate?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
    } ?: fallbackTitle

/** The host of an address, without "www."; empty when there is none. */
internal fun browserTabHost(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return splitAddress(url.trim()).first.substringAfterLast('@').lowercase().removePrefix("www.")
}

/** Null below two pages (no counter is shown) or when no page is marked current. */
internal fun browserTabPosition(entries: List<BrowserTabEntry>): BrowserTabPosition? {
    if (entries.size < 2) return null
    val index = entries.indexOfFirst { it.current }
    if (index < 0) return null
    return BrowserTabPosition(index + 1, entries.size)
}

/** "2/3"; a three-digit count is capped so the chip keeps its width. */
internal fun browserTabPositionText(position: BrowserTabPosition): String {
    val total = if (position.total > 99) "99+" else position.total.toString()
    return "${position.index.coerceAtMost(99)}/$total"
}

internal const val BROWSER_ADDRESS_TAG = "BrowserAddress"
internal const val BROWSER_ADDRESS_FIELD_TAG = "BrowserAddressField"
internal const val BROWSER_NEW_TAB_FIELD_TAG = "BrowserNewTabField"
internal const val BROWSER_ADDRESS_CANCEL_TAG = "BrowserAddressCancel"
internal const val BROWSER_TAB_COUNTER_TAG = "BrowserTabCounter"
internal const val BROWSER_TAB_LIST_TAG = "BrowserTabList"
internal const val BROWSER_TAB_LIST_NEW_TAG = "BrowserTabListNew"
internal fun browserTabItemTag(index: Int) = "BrowserTabItem$index"
internal fun browserTabCloseTag(index: Int) = "BrowserTabClose$index"

@Composable
private fun AddressEditor(
    initial: String,
    modifier: Modifier,
    onGo: (String) -> Unit,
    onRefused: (Int) -> Unit,
    onCancel: () -> Unit,
    /** Editing a new page's address: empty start, its own name and a hint; the shown page stays. */
    newTab: Boolean = false,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val fieldLabel = stringResource(if (newTab) R.string.browser_new_tab_address else R.string.field_address)
    val placeholder = stringResource(R.string.browser_open_page_placeholder)
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    fun submit() {
        when (val check = checkBrowserUrl(value.text)) {
            // Close the keyboard with the editor, so the window does not stay folded by it.
            is BrowserUrlCheck.Ready -> { keyboard?.hide(); onGo(check.url) }
            is BrowserUrlCheck.Refused -> onRefused(R.string.browser_open_page_refused)
            else -> onRefused(R.string.browser_open_page_invalid)
        }
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (newTab && value.text.isEmpty()) {
                // A visual hint only; nothing is filled in or loaded for the person.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = placeholder,
                        style = ChamberTextStyles.code,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
            // Single line: a long address scrolls sideways while editing.
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = ChamberTextStyles.code.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .semantics { contentDescription = fieldLabel }
                    .testTag(if (newTab) BROWSER_NEW_TAB_FIELD_TAG else BROWSER_ADDRESS_FIELD_TAG),
            )
        }
        IconButton(onClick = { submit() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.browser_address_go))
        }
        IconButton(onClick = { keyboard?.hide(); onCancel() }, modifier = Modifier.size(48.dp).testTag(BROWSER_ADDRESS_CANCEL_TAG)) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.browser_address_cancel))
        }
    }
}

@Composable
private fun BrowserMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    desktopOn: Boolean,
    mouseOn: Boolean,
    mousePressActive: Boolean,
    clearingSiteData: Boolean,
    onReload: (() -> Unit)?,
    onNewTab: (() -> Unit)?,
    onDesktop: () -> Unit,
    onMouse: () -> Unit,
    onClearSiteData: (() -> Unit)?,
    onClosePage: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (onNewTab != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browser_new_tab)) },
                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                enabled = !mousePressActive,
                onClick = onNewTab,
            )
        }
        if (onReload != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browser_reload)) },
                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                enabled = !mousePressActive,
                onClick = onReload,
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(if (desktopOn) R.string.browser_phone_layout else R.string.browser_desktop_layout)) },
            enabled = !mousePressActive,
            onClick = onDesktop,
        )
        // Mouse mode exists only on the computer layout; it is left out rather than shown disabled.
        if (desktopOn) {
            DropdownMenuItem(
                text = { Text(stringResource(if (mouseOn) R.string.browser_touch_mode else R.string.browser_mouse_mode)) },
                enabled = !mousePressActive,
                onClick = onMouse,
            )
        }
        HorizontalDivider()
        if (onClearSiteData != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browser_clear_site_data), color = MaterialTheme.colorScheme.error) },
                enabled = !mousePressActive && !clearingSiteData,
                onClick = onClearSiteData,
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.browser_turn_off)) },
            leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
            onClick = onClosePage,
        )
    }
}

/** Status on the left, the single control action on the right, an optional resize grip first. */
@Composable
private fun ControlBar(
    status: String,
    agentOn: Boolean,
    action: BrowserControlAction,
    canReopen: Boolean,
    resizable: Boolean,
    onResume: () -> Unit,
    onTakeOver: () -> Unit,
    onReopen: () -> Unit,
    onResize: (dx: Float, dy: Float) -> Unit,
) {
    val density = LocalDensity.current
    // The drag gesture outlives recompositions; always call the newest resize logic.
    val resize by rememberUpdatedState(onResize)
    Row(
        modifier = Modifier.fillMaxWidth().height(BROWSER_BAR_HEIGHT.dp).padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (resizable) {
            val resizeLabel = stringResource(R.string.browser_resize_window)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = resizeLabel }
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            resize(drag.x / density.density, drag.y / density.density)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { ResizeGrip() }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = if (agentOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 6.dp),
        )
        when (action) {
            BrowserControlAction.TakeOver -> Button(
                onClick = onTakeOver,
                modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text(stringResource(R.string.browser_take_over), maxLines = 1) }
            BrowserControlAction.Reopen -> FilledTonalButton(
                onClick = onReopen,
                enabled = canReopen,
                modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text(stringResource(R.string.browser_reopen_page), maxLines = 1) }
            BrowserControlAction.Resume -> FilledTonalButton(
                onClick = onResume,
                modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text(stringResource(R.string.browser_turn_on), maxLines = 1) }
            BrowserControlAction.None -> Unit
        }
    }
}

/** The one control action the window offers. */
internal enum class BrowserControlAction { TakeOver, Reopen, Resume, None }

/**
 * Which control action to show. The agent in control, or still holding a read-only grant after an
 * unconfirmed action: Take over (it revokes). The page needs a fresh load (confirmed by the app,
 * not page text): Reopen, never Resume, because Resume would be refused. Otherwise Resume when it
 * can work, or nothing.
 */
internal fun browserControlAction(
    agentOn: Boolean,
    reopenNeeded: Boolean,
    canResume: Boolean,
    agentReadOnly: Boolean = false,
): BrowserControlAction =
    when {
        agentOn || agentReadOnly -> BrowserControlAction.TakeOver
        reopenNeeded -> BrowserControlAction.Reopen
        canResume -> BrowserControlAction.Resume
        else -> BrowserControlAction.None
    }

/** Three short diagonal strokes in the bottom-start corner, the usual sign for "drag to resize". */
@Composable
private fun ResizeGrip() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val stroke = 1.6.dp.toPx()
        for (i in 1..3) {
            val d = w * i / 3.2f
            drawLine(color, start = androidx.compose.ui.geometry.Offset(0f, w - d),
                end = androidx.compose.ui.geometry.Offset(d, w), strokeWidth = stroke)
        }
    }
}

/**
 * The page area of a window layout in dp: the window minus its top bar and, when shown, its bottom
 * bar. Null when the page is not presented or there is no room.
 */
internal fun browserPageAreaDp(layout: BrowserWindowLayout): Pair<Float, Float>? {
    if (layout.blocked) return null
    val bars = BROWSER_BAR_HEIGHT * if (layout.showBottomBar) 2 else 1
    val width = layout.window.width
    val height = layout.window.height - bars
    return if (width > 0f && height > 0f) width to height else null
}

/**
 * The fixed page size in dp. Taken from the keyboard-closed safe area and held while the keyboard
 * is open, so showing, hiding or animating the keyboard never changes it.
 */
internal class StableViewport {
    private var width = -1f
    private var size: Pair<Float, Float>? = null

    fun update(safeWidth: Float, keyboardClosedHeight: Float, imeOpen: Boolean): Pair<Float, Float> {
        val current = size
        if (current == null || safeWidth != width || (!imeOpen && current != browserViewportDp(safeWidth, keyboardClosedHeight))) {
            width = safeWidth
            size = browserViewportDp(safeWidth, keyboardClosedHeight)
        }
        return size!!
    }
}

/** Main-owned UI ordering only. Bookmark identity, IO and cancellation admission stay in core. */
internal class WindowExpansionUiState {
    var enlarged by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var pendingChange by mutableStateOf<WindowChangeToken?>(null)
        private set

    suspend fun change(
        next: Boolean,
        prepare: suspend (Boolean) -> WindowChange,
        invalidateGeometry: () -> Unit,
        canChange: () -> Boolean = { true },
    ): WindowChange? {
        if (busy || next == enlarged || !canChange()) return null
        busy = true
        try {
            val prepared = prepare(next)
            currentCoroutineContext().ensureActive()
            if (!canChange()) return WindowChange.Superseded
            if (prepared is WindowChange.Proceed) {
                // No suspension from acceptance through invalidation and the layout-state flip.
                invalidateGeometry()
                enlarged = next
                pendingChange = prepared.token
            }
            return prepared
        } finally {
            busy = false
        }
    }

    /** Only the layout rendered with this exact token may hand it back to core, once. */
    fun consumeSettled(rendered: WindowChangeToken?): WindowChangeToken? {
        if (rendered == null || pendingChange != rendered || rendered.next != enlarged) return null
        pendingChange = null
        return rendered
    }

    /** Parent chrome placement is not evidence that the WebView content allocation is ready. */
    fun surfaceSettled(notifyGeometry: () -> Unit) = notifyGeometry()

    fun acknowledgeContent(rendered: WindowChangeToken): Boolean = consumeSettled(rendered) != null
}
