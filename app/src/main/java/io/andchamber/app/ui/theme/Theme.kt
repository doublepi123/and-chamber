package io.andchamber.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

internal val LocalChamberColors = staticCompositionLocalOf { DarkChamberColors }

internal val ChamberShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(26.dp),
    )

/** Dark-first theme that follows the system setting. Dynamic color is off on purpose. */
@Composable
fun AndChamberTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extended = if (darkTheme) DarkChamberColors else LightChamberColors
    CompositionLocalProvider(LocalChamberColors provides extended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = ChamberTypography,
            shapes = ChamberShapes,
            content = content,
        )
    }
}

internal object ChamberTheme {
    val colors: ChamberColors
        @Composable
        @ReadOnlyComposable
        get() = LocalChamberColors.current
}
