package io.andchamber.app.ui.components

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.andchamber.app.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.andchamber.app.ui.rememberCopyAction
import io.andchamber.app.ui.theme.ChamberTextStyles
import io.andchamber.app.ui.theme.ChamberTheme
import kotlinx.coroutines.flow.filter

/**
 * Terminal-style view of the runtime log tail. Follows new output while the user is at the bottom
 * and stops following once they scroll up to read.
 */
@Composable
internal fun LogConsole(
    lines: List<String>,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 240.dp,
) {
    val colors = ChamberTheme.colors
    val listState = rememberLazyListState()
    val copy = rememberCopyAction()
    // Follow new output until the user scrolls away from the end; resume when they come back.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { scrolling -> !scrolling }
            .collect { follow = !listState.canScrollForward }
    }
    // Keyed on the list itself: once the tail is full its size stops changing.
    LaunchedEffect(lines) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.terminalBackground,
        border = BorderStroke(1.dp, colors.terminalBorder),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pluralStringResource(R.plurals.log_lines, lines.size, lines.size),
                    style = ChamberTextStyles.codeSmall,
                    color = colors.terminalMuted,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { copy(lines.joinToString("\n")) },
                    enabled = lines.isNotEmpty(),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.copy_log),
                        tint = colors.terminalMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.log_empty),
                    style = ChamberTextStyles.log,
                    color = colors.terminalMuted,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxHeight)
                                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    ) {
                        itemsIndexed(lines) { _, line ->
                            Text(
                                text = line.ifEmpty { " " },
                                style = ChamberTextStyles.log,
                                color = line.logColor(colors.terminalForeground),
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun String.logColor(default: Color): Color {
    val upper = take(16).uppercase()
    return when {
        upper.startsWith("ERROR") || upper.startsWith("FATAL") || upper.startsWith("E/") ->
            MaterialTheme.colorScheme.error
        upper.startsWith("WARN") || upper.startsWith("W/") -> ChamberTheme.colors.warning
        startsWith("$ ") || startsWith("> ") -> MaterialTheme.colorScheme.primary
        else -> default
    }
}
