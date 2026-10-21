package io.andchamber.app.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.andchamber.app.R
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * App-internal floating window. Dragging the title does not take the page back. Enlarge and restore
 * only rearrange the same page. Closing, or moving the window fully off screen, drops control.
 */
@Composable
internal fun BrowserFloatWindow(
    title: String,
    status: String,
    agentOn: Boolean,
    canAllow: Boolean,
    desktopOn: Boolean,
    mouseOn: Boolean,
    mousePressActive: Boolean = false,
    isPagePressActive: () -> Boolean = { mousePressActive },
    onAllow: () -> Unit,
    onTakeOver: () -> Unit,
    onClose: () -> Unit,
    onPresentationBlocked: (Boolean) -> Unit,
    onGeometryChanged: () -> Unit = {},
    onGeometrySettled: () -> Unit,
    beforeExpansionChange: suspend (Boolean) -> WindowChange,
    onScrollNotSaved: (ScrollNotSavedReason) -> Unit,
    onDesktopMode: (Boolean) -> Unit = {},
    onMouseMode: (Boolean) -> Unit = {},
    bottomClearance: Dp = 72.dp,
    content: @Composable (WindowChangeToken?, (WindowChangeToken) -> Boolean, Boolean) -> Unit,
) {
    val expansion = remember { WindowExpansionUiState() }
    val scope = rememberCoroutineScope()
    val enlarged = expansion.enlarged
    val latestPressCheck by rememberUpdatedState(isPagePressActive)
    fun requestExpansion(next: Boolean) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = expansion.change(next, beforeExpansionChange, onGeometryChanged,
                canChange = { !latestPressCheck() })
            if (result is WindowChange.Proceed) {
                (result.saved as? ScrollSave.NotSaved)?.let { onScrollNotSaved(it.reason) }
            }
        }
    }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    var confirmDesktop by remember { mutableStateOf<Boolean?>(null) }
    var requestedWidth by remember { mutableStateOf<Float?>(null) }
    var requestedHeight by remember { mutableStateOf<Float?>(null) }
    var lastBounds by remember { mutableStateOf<Rect?>(null) }
    var blockedLatch by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // Parent WebScreen consumes system/cutout/IME target insets once. Its constraints already
    // describe the keyboard-safe content area; subtract only the composer here.
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight
        val tablet = maxWidth >= 600.dp
        // WebScreen supplies measured composer clearance (or its phone/tablet fallback).
        // Never cap a measured multiline composer to the old tablet constant.
        val clearance = bottomClearance
        val bounds = editableFloatBounds(floatWindowBounds(maxWidth.value, maxHeight.value, clearance.value, tablet,
            requestedWidth, requestedHeight))
        val latestBounds by rememberUpdatedState(bounds)
        val tight = presentationBlocked(blockedLatch, bounds)
        SideEffect {
            // This also gates the retired placeholder (which has no AndroidView update).
            // The native content update independently gates BEFORE its visibility/focus changes.
            onPresentationBlocked(tight)
            if (tight) blockedLatch = true
        }
        val width = if (enlarged) Modifier.fillMaxWidth() else Modifier.width(bounds.width.dp)
        val height = if (enlarged) Modifier.fillMaxSize() else Modifier.height(bounds.height.dp)
        // Capture the token rendered by THIS composition. A late old layout callback cannot
        // read a newer token from mutable state and pretend it belongs to that layout.
        val renderedChange = expansion.pendingChange
        LaunchedEffect(maxWidth, maxHeight, clearance, tight) {
            onGeometryChanged()
            // Presentation blocking keeps the selected mount; it must not background/hide the host.
        }
        // Small window sits above the chat composer; enlarged fills the content area.
        val above = Modifier.padding(bottom = clearance)
        Box(modifier = Modifier.fillMaxSize().then(above), contentAlignment = Alignment.BottomEnd) {
            Surface(
                modifier = width.then(height).offset {
                    with(density) { IntOffset(bounds.x(dragX, enlarged).dp.roundToPx(), bounds.y(dragY, enlarged).dp.roundToPx()) }
                }.testTag("BrowserFloatSurface").semantics { contentDescription = "BrowserFloatSurface" }.onGloballyPositioned { coordinates ->
                    val actual = coordinates.boundsInWindow()
                    if (actual != lastBounds) {
                        lastBounds = actual
                        expansion.surfaceSettled(onGeometrySettled)
                    }
                },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp,
            ) {
                Box {
                Column(Modifier.then(if (tight) Modifier.clearAndSetSemantics {} else Modifier)
                    .pointerInput(tight) {
                        if (tight) awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(48.dp).pointerInput(enlarged, containerWidth, containerHeight) {
                            var dragAccepted = false
                            detectDragGestures(
                                onDragStart = {
                                    dragAccepted = !latestPressCheck()
                                    if (dragAccepted) onGeometryChanged()
                                },
                                onDragEnd = { if (dragAccepted && !latestPressCheck()) onGeometrySettled() },
                                onDragCancel = { if (dragAccepted && !latestPressCheck()) onGeometrySettled() },
                            ) { change, drag ->
                                change.consume()
                                if (!enlarged && dragAccepted && !latestPressCheck()) {
                                    onGeometryChanged()
                                    dragX = latestBounds.clampX(dragX + drag.x / density.density)
                                    dragY = latestBounds.clampY(dragY + drag.y / density.density)
                                }
                            }
                        }.padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title.ifBlank { stringResource(R.string.browser_entry_title) },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { requestExpansion(!enlarged) },
                            enabled = !expansion.busy && !mousePressActive,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(if (enlarged) R.string.browser_restore else R.string.browser_enlarge)) }
                        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.browser_turn_off))
                        }
                        Box {
                            IconButton(onClick = { if (!latestPressCheck()) menu = true }, enabled = !mousePressActive,
                                modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(
                                    if (mousePressActive) R.string.browser_finish_drag_first else R.string.browser_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (desktopOn) R.string.browser_phone_layout else R.string.browser_desktop_layout)) },
                                    enabled = !mousePressActive && !expansion.busy,
                                    onClick = { if (!latestPressCheck()) { menu = false; confirmDesktop = !desktopOn } },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (mouseOn) R.string.browser_touch_mode else R.string.browser_mouse_mode)) },
                                    enabled = desktopOn && !mousePressActive && !expansion.busy,
                                    onClick = { if (!latestPressCheck()) { menu = false; onMouseMode(!mouseOn) } },
                                )
                            }
                        }
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        minLines = 2,
                        maxLines = 2,
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp)),
                    ) { content(renderedChange, expansion::acknowledgeContent, tight) }
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onAllow, enabled = !tight && canAllow && !agentOn, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.browser_turn_on))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onTakeOver, enabled = agentOn, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.browser_take_over))
                        }
                        if (!enlarged) {
                            val resizeLabel = stringResource(R.string.browser_resize_window)
                            Box(Modifier.size(48.dp).semantics { contentDescription = resizeLabel }.pointerInput(containerWidth, containerHeight) {
                                var resizeAccepted = false
                                detectDragGestures(onDragStart = {
                                    resizeAccepted = !latestPressCheck()
                                    if (resizeAccepted) onGeometryChanged()
                                },
                                    onDragEnd = { if (resizeAccepted && !latestPressCheck()) onGeometrySettled() },
                                    onDragCancel = { if (resizeAccepted && !latestPressCheck()) onGeometrySettled() }) { change, drag ->
                                    change.consume()
                                    if (!resizeAccepted || latestPressCheck()) return@detectDragGestures
                                    onGeometryChanged()
                                    // Bottom-right anchored window: drag up/left to grow, down/right to shrink.
                                    val current = latestBounds
                                    requestedWidth = (current.width - drag.x / density.density)
                                        .coerceIn(minOf(240f, current.areaWidth), current.areaWidth)
                                    requestedHeight = (current.height - drag.y / density.density)
                                        .coerceIn(minOf(176f, current.areaHeight), current.areaHeight)
                                }
                            }, contentAlignment = Alignment.Center) {
                                Text("↔", modifier = Modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                // The content call above never leaves its slot. The opaque UI is backed by native
                // INVISIBLE + descendant/input/accessibility blocking, not merely an alpha overlay.
                if (tight) {
                    Surface(Modifier.fillMaxSize().testTag("BrowserFloatTight")
                        .semantics { contentDescription = "BrowserFloatTight" },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.browser_keyboard_tight), style = MaterialTheme.typography.bodyMedium)
                            Row {
                                TextButton(onClick = {
                                    if (latestPressCheck()) return@TextButton
                                    if (releasePresentationLatch(true, latestBounds)) blockedLatch = false
                                    requestExpansion(true)
                                }, enabled = !expansion.busy && !mousePressActive) {
                                    Text(stringResource(R.string.browser_enlarge))
                                }
                                TextButton(onClick = {
                                    if (latestPressCheck()) return@TextButton
                                    onClose()
                                    blockedLatch = false
                                    onPresentationBlocked(false)
                                }, enabled = !mousePressActive) {
                                    Text(stringResource(R.string.browser_turn_off))
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
    confirmDesktop?.let { requested ->
        AlertDialog(
            onDismissRequest = { confirmDesktop = null },
            title = { Text(stringResource(R.string.browser_mode_change_title)) },
            text = { Text(stringResource(R.string.browser_mode_change_body)) },
            confirmButton = { TextButton(enabled = !mousePressActive && !expansion.busy, onClick = {
                if (latestPressCheck()) return@TextButton
                confirmDesktop = null
                onGeometryChanged()
                onDesktopMode(requested)
            }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { confirmDesktop = null }) { Text(stringResource(R.string.cancel)) } },
        )
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
