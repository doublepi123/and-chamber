package io.andchamber.app.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.andchamber.app.R
import kotlin.math.roundToInt

/**
 * App-internal floating window. Dragging the title does not take the page back. Resizing or
 * enlarging does not load a new page. Closing drops control.
 */
@Composable
internal fun BrowserFloatWindow(
    title: String,
    status: String,
    agentOn: Boolean,
    canAllow: Boolean,
    onAllow: () -> Unit,
    onTakeOver: () -> Unit,
    onClose: () -> Unit,
    onHidden: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var enlarged by remember { mutableStateOf(false) }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    var hidden by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val width = if (enlarged) Modifier.fillMaxWidth() else Modifier.widthIn(max = 360.dp).fillMaxWidth(0.92f)
    val height = if (enlarged) Modifier.fillMaxSize() else Modifier.heightIn(max = 280.dp).height(240.dp)
    if (hidden) return
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        Surface(
            modifier = width.then(height).offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                dragX += drag.x
                                dragY += drag.y
                                val off = kotlin.math.abs(dragX) > size.width || kotlin.math.abs(dragY) > size.height
                                if (off && !hidden) {
                                    hidden = true
                                    onHidden()
                                }
                            }
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title.ifBlank { stringResource(R.string.browser_entry_title) },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    TextButton(onClick = { enlarged = !enlarged }) {
                        Text(stringResource(if (enlarged) R.string.browser_restore else R.string.browser_enlarge))
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.browser_turn_off)) }
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                    content()
                }
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onAllow, enabled = canAllow && !agentOn) {
                        Text(stringResource(R.string.browser_turn_on))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onTakeOver, enabled = agentOn) {
                        Text(stringResource(R.string.browser_take_over))
                    }
                }
            }
        }
    }
}

internal fun floatWidthDp(screenWidthDp: Int, tablet: Boolean): Int =
    if (tablet) 360 else minOf(328, screenWidthDp - 32)

internal fun floatHeightDp(availableHeightDp: Int): Int = minOf(240, (availableHeightDp * 0.42f).toInt())
