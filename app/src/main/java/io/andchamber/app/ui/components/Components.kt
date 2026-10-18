package io.andchamber.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.andchamber.app.ui.Strings
import io.andchamber.app.ui.theme.ChamberTextStyles
import io.andchamber.app.ui.theme.ChamberTheme

/** A visual tone for runtime/server status. */
internal enum class StatusTone { Idle, Busy, Live, Error }

@Composable
internal fun StatusTone.color(): Color =
    when (this) {
        StatusTone.Idle -> ChamberTheme.colors.idle
        StatusTone.Busy -> ChamberTheme.colors.warning
        StatusTone.Live -> ChamberTheme.colors.success
        StatusTone.Error -> MaterialTheme.colorScheme.error
    }

/**
 * A small status dot. Busy pulses (work in progress); Live gets a steady halo, so a running
 * server doesn't keep the screen animating.
 */
@Composable
internal fun StatusDot(
    tone: StatusTone,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val color by animateColorAsState(tone.color(), label = "statusDotColor")
    if (tone == StatusTone.Busy) {
        val transition = rememberInfiniteTransition(label = "statusDotPulse")
        val pulse by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "statusDotPulseValue",
        )
        Canvas(modifier = modifier.size(size * 2.2f)) {
            val radius = size.toPx() / 2f
            drawCircle(color = color.copy(alpha = 0.4f * (1f - pulse)), radius = radius * (1f + 1.2f * pulse))
            drawCircle(color = color, radius = radius)
        }
    } else {
        val halo = tone == StatusTone.Live
        Canvas(modifier = modifier.size(size * 2.2f)) {
            val radius = size.toPx() / 2f
            if (halo) drawCircle(color = color.copy(alpha = 0.22f), radius = radius * 1.9f)
            drawCircle(color = color, radius = radius)
        }
    }
}

/** Status label in a tinted capsule: "● Running". */
@Composable
internal fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(tone.color(), label = "statusPillColor")
    Row(
        modifier =
            modifier
                .clip(CircleShape)
                .background(color.copy(alpha = 0.12f))
                .padding(start = 4.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(tone = tone, size = 6.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** Uppercase mono section header, like a terminal heading. */
@Composable
internal fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Flat card with a hairline border; the base surface of the control room. */
@Composable
internal fun ChamberCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(1.dp, accent?.copy(alpha = 0.45f) ?: ChamberTheme.colors.cardBorder)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = ChamberTheme.colors.card,
            border = border,
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = ChamberTheme.colors.card,
            border = border,
        ) {
            Column(content = content)
        }
    }
}

/** Label / value row for the details section. */
@Composable
internal fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = ChamberTextStyles.code,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * The OpenChamber cube drawn with Canvas: an open-top isometric box. Matches the web UI's logo
 * without bundling an image.
 */
@Composable
internal fun ChamberMark(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 28.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        // Logo geometry is defined on a 100x100 box centered on (0,0) at the cube center.
        val unit = this.size.minDimension / 100f
        translate(left = this.size.width / 2f, top = this.size.height / 2f) {
            scale(unit, unit, pivot = Offset.Zero) {
                val stroke = Stroke(width = 5f, join = StrokeJoin.Round)
                val left =
                    Path().apply {
                        moveTo(0f, 2f)
                        lineTo(-40f, -21f)
                        lineTo(-40f, 23f)
                        lineTo(0f, 46f)
                        close()
                    }
                val right =
                    Path().apply {
                        moveTo(0f, 2f)
                        lineTo(40f, -21f)
                        lineTo(40f, 23f)
                        lineTo(0f, 46f)
                        close()
                    }
                val top =
                    Path().apply {
                        moveTo(0f, -44f)
                        lineTo(-40f, -21f)
                        lineTo(0f, 2f)
                        lineTo(40f, -21f)
                        close()
                    }
                drawPath(left, color.copy(alpha = 0.22f), style = Fill)
                drawPath(right, color.copy(alpha = 0.42f), style = Fill)
                drawPath(left, color, style = stroke)
                drawPath(right, color, style = stroke)
                drawPath(top, color, style = stroke)
            }
        }
    }
}

/** Two-button confirmation dialog. [destructive] tints the confirm action red. */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = Strings.cancel,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors =
                    if (destructive) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
            ) {
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

/** Rounded icon tile used at the start of list rows. */
@Composable
internal fun IconTile(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(background),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            content()
        }
    }
}
