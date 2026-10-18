package io.andchamber.app.ui.web

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.andchamber.app.ui.Strings
import io.andchamber.app.ui.components.ChamberMark
import io.andchamber.app.ui.theme.ChamberTextStyles

/** A labelled button for a panel. */
internal class PanelAction(val label: String, val onClick: () -> Unit)

/**
 * Centered status panel used for every full-screen state of the web screen (connecting, errors,
 * local server stopped). [progress]: null hides the bar, NaN shows an indeterminate bar.
 */
@Composable
internal fun StatusPanel(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    body: String? = null,
    detail: String? = null,
    progress: Float? = null,
    primary: PanelAction? = null,
    secondary: PanelAction? = null,
    tertiary: PanelAction? = null,
    breathingMark: Boolean = false,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(40.dp))
            } else {
                BreathingMark(animate = breathingMark)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (!body.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = detail,
                        style = ChamberTextStyles.codeSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(20.dp))
                val barModifier = Modifier.width(180.dp).height(4.dp).clip(CircleShape)
                if (progress.isNaN()) {
                    LinearProgressIndicator(
                        modifier = barModifier,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                    )
                } else {
                    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "panelProgress")
                    LinearProgressIndicator(
                        progress = { animated },
                        modifier = barModifier,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
            }
            if (primary != null || secondary != null) {
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                    secondary?.let {
                        OutlinedButton(
                            onClick = it.onClick,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(it.label) }
                    }
                    primary?.let {
                        Button(
                            onClick = it.onClick,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(it.label) }
                    }
                }
            }
            tertiary?.let {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = it.onClick) { Text(it.label) }
            }
        }
    }
}

@Composable
private fun BreathingMark(animate: Boolean) {
    val transition = rememberInfiniteTransition(label = "breathing")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathingAlpha",
    )
    ChamberMark(size = 48.dp, modifier = Modifier.alpha(if (animate) pulse else 1f))
}

/** Shown while the session is being prepared and until the page first paints. [name] null = this device. */
@Composable
internal fun ConnectingPanel(
    name: String?,
    address: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatusPanel(
        title = if (name == null) Strings.openingLocal else Strings.connectingTo(name),
        body = address,
        progress = Float.NaN,
        breathingMark = true,
        tertiary = PanelAction(Strings.back, onBack),
        modifier = modifier,
    )
}
