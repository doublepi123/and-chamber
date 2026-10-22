package io.andchamber.app.ui.web

import androidx.compose.ui.res.stringResource
import io.andchamber.app.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.andchamber.app.ui.components.StatusDot
import io.andchamber.app.ui.components.StatusTone
import io.andchamber.app.ui.theme.ChamberTextStyles
import io.andchamber.app.ui.theme.ChamberTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val TouchWidth = 28.dp
private val TouchHeight = 60.dp

/**
 * A small tab on the trailing edge of the page: tap for Home / Reload, and Open web page / Open in
 * browser where offered; drag up or down to move it out of the way. Takes no layout space, and
 * fades back after a few seconds. [onOpenUrl] (open a page in this chat's browser window) and
 * [onOpenInBrowser] are left out of the menu when null.
 */
@Composable
internal fun EdgeHandle(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    title: String,
    address: String,
    onHome: () -> Unit,
    onReload: () -> Unit,
    onOpenInBrowser: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenUrl: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val range = with(density) { (maxHeight - TouchHeight).toPx() }.coerceAtLeast(1f)
        // Local state so consecutive drag deltas within one frame all apply.
        var position by remember { mutableFloatStateOf(fraction.coerceIn(0f, 1f)) }
        var menuOpen by remember { mutableStateOf(false) }
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val dragged by interaction.collectIsDraggedAsState()
        val active = pressed || dragged || menuOpen

        // Fully visible at first and while in use; then it steps back.
        var resting by remember { mutableStateOf(false) }
        LaunchedEffect(active) {
            resting = false
            if (!active) {
                delay(3_000)
                resting = true
            }
        }
        val alpha by animateFloatAsState(if (resting) 0.5f else 1f, tween(400), label = "handleAlpha")
        val tabWidth by animateDpAsState(if (active) 22.dp else 18.dp, label = "handleWidth")
        val gripColor by animateColorAsState(
            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            label = "handleGrip",
        )
        val shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)

        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, (position * range).roundToInt()) }
                    .systemGestureExclusion()
                    .size(width = TouchWidth, height = TouchHeight)
                    .draggable(
                        orientation = Orientation.Vertical,
                        interactionSource = interaction,
                        state =
                            rememberDraggableState { delta ->
                                position = (position + delta / range).coerceIn(0f, 1f)
                            },
                        onDragStopped = { onFractionChange(position) },
                    )
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.menu_description),
                    ) { menuOpen = true }
                    .alpha(alpha),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(tabWidth)
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f), shape)
                        .border(BorderStroke(1.dp, ChamberTheme.colors.cardBorder), shape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .background(gripColor, RoundedCornerShape(2.dp)),
                )
            }

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                offset = DpOffset(x = (-8).dp, y = 0.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(min = 220.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(tone = StatusTone.Live, size = 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = address,
                            style = ChamberTextStyles.codeSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = ChamberTheme.colors.cardBorder)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home)) },
                    leadingIcon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onHome()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reload)) },
                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onReload()
                    },
                )
                if (onOpenUrl != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_open_page)) },
                        leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onOpenUrl()
                        },
                    )
                }
                if (onOpenInBrowser != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_in_browser)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onOpenInBrowser()
                        },
                    )
                }
            }
        }
    }
}
