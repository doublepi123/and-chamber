package io.andchamber.app.ui.browser

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
@OptIn(ExperimentalLayoutApi::class)
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
    var editing by remember { mutableStateOf(false) }

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
        val action = browserControlAction(agentOn, reopenNeeded, canResume)

        // Back leaves full screen first, like a phone browser; it never hides or stops the agent.
        BackHandler(enabled = ui.fullRequested && !editing) { ui.fullRequested = false }
        BackHandler(enabled = editing) { editing = false }

        val window = layout.window
        Surface(
            modifier = Modifier
                .offset { IntOffset(window.left.dp.roundToPx(), window.top.dp.roundToPx()) }
                .width(window.width.dp)
                .height(window.height.dp)
                .testTag("BrowserFloatSurface")
                .semantics { contentDescription = "BrowserFloatSurface" },
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
                    if (editing && onNavigate != null) {
                        AddressEditor(
                            initial = address.orEmpty(),
                            modifier = Modifier.weight(1f),
                            onGo = { url ->
                                editing = false
                                onNavigate(url)
                            },
                            onRefused = { resource -> Toast.makeText(context, resource, Toast.LENGTH_SHORT).show() },
                            onCancel = { editing = false },
                        )
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
                            onEdit = if (onNavigate != null) ({ editing = true }) else null,
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
                                    onDesktop = { if (!latestPressCheck()) { menu = false; confirmDesktop = !desktopOn } },
                                    onMouse = { if (!latestPressCheck()) { menu = false; onMouseMode(!mouseOn) } },
                                    onClearSiteData = onClearSiteData?.let {
                                        { if (!latestPressCheck()) { menu = false; confirmClearSiteData = true } }
                                    },
                                    onClosePage = { menu = false; onClosePage() },
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

/** The shown address: host first and strong, the rest muted, cut at the end. Tap to edit. */
@Composable
private fun AddressLabel(
    address: String?,
    title: String,
    agentOn: Boolean,
    modifier: Modifier,
    onEdit: (() -> Unit)?,
) {
    val editLabel = stringResource(R.string.browser_address)
    val parts = address?.let { splitAddress(it) }
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (onEdit != null) Modifier.clickable(onClickLabel = editLabel, onClick = onEdit) else Modifier)
            .semantics { contentDescription = editLabel }
            .padding(horizontal = 10.dp),
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
        val fallback = title.ifBlank { stringResource(R.string.browser_entry_title) }
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
}

/** "https://a.example.com/x?y" -> ("a.example.com", "/x?y"); the scheme is always https here. */
internal fun splitAddress(url: String): Pair<String, String> {
    val rest = url.substringAfter("://", url)
    val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val tail = rest.substring(cut).let { if (it == "/") "" else it }
    return rest.substring(0, cut) to tail
}

@Composable
private fun AddressEditor(
    initial: String,
    modifier: Modifier,
    onGo: (String) -> Unit,
    onRefused: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val fieldLabel = stringResource(R.string.field_address)
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    fun submit() {
        when (val check = checkBrowserUrl(value.text)) {
            is BrowserUrlCheck.Ready -> onGo(check.url)
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
                    .semantics { contentDescription = fieldLabel },
            )
        }
        IconButton(onClick = { submit() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.browser_address_go))
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
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
    onDesktop: () -> Unit,
    onMouse: () -> Unit,
    onClearSiteData: (() -> Unit)?,
    onClosePage: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
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
 * Which control action to show. The agent in control: Take over. The page needs a fresh load
 * (confirmed by the app, not page text): Reopen, never Resume, because Resume would be refused.
 * Otherwise Resume when it can work, or nothing.
 */
internal fun browserControlAction(agentOn: Boolean, reopenNeeded: Boolean, canResume: Boolean): BrowserControlAction =
    when {
        agentOn -> BrowserControlAction.TakeOver
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
