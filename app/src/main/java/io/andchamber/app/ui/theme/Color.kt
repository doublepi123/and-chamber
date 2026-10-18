package io.andchamber.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * Warm ink + amber, taken from OpenChamber's own chrome (#151313 background, #EDB449 accent) so
 * the native shell and the web UI read as one product. Dynamic color is intentionally not used.
 */

internal val DarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFEDB449),
        onPrimary = Color(0xFF241A08),
        primaryContainer = Color(0xFF3D2F14),
        onPrimaryContainer = Color(0xFFF6D58F),
        inversePrimary = Color(0xFF8F5A00),
        secondary = Color(0xFFC8B9A3),
        onSecondary = Color(0xFF2B241B),
        secondaryContainer = Color(0xFF2F2A25),
        onSecondaryContainer = Color(0xFFE7DCCB),
        tertiary = Color(0xFF8FB8D8),
        onTertiary = Color(0xFF0E2433),
        tertiaryContainer = Color(0xFF1C2E3B),
        onTertiaryContainer = Color(0xFFCBE3F5),
        background = Color(0xFF151313),
        onBackground = Color(0xFFECE6DA),
        surface = Color(0xFF151313),
        onSurface = Color(0xFFECE6DA),
        surfaceVariant = Color(0xFF2D2A28),
        onSurfaceVariant = Color(0xFFA9A196),
        surfaceTint = Color(0xFFEDB449),
        inverseSurface = Color(0xFFECE6DA),
        inverseOnSurface = Color(0xFF1E1C1B),
        error = Color(0xFFEE8A78),
        onError = Color(0xFF3A0F08),
        errorContainer = Color(0xFF3A1F1B),
        onErrorContainer = Color(0xFFF8C6BC),
        outline = Color(0xFF6E6560),
        outlineVariant = Color(0xFF2E2A28),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF34302D),
        surfaceContainer = Color(0xFF1E1C1B),
        surfaceContainerHigh = Color(0xFF262321),
        surfaceContainerHighest = Color(0xFF302C2A),
        surfaceContainerLow = Color(0xFF1A1817),
        surfaceContainerLowest = Color(0xFF0F0D0C),
        surfaceDim = Color(0xFF151313),
    )

internal val LightColors: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF8F5A00),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFF8E3BD),
        onPrimaryContainer = Color(0xFF3E2700),
        inversePrimary = Color(0xFFEDB449),
        secondary = Color(0xFF6B5D4B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEDE3D3),
        onSecondaryContainer = Color(0xFF2A2117),
        tertiary = Color(0xFF2D6A93),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFD5E7F5),
        onTertiaryContainer = Color(0xFF0B2A3F),
        background = Color(0xFFFBF5EA),
        onBackground = Color(0xFF231C19),
        surface = Color(0xFFFBF5EA),
        onSurface = Color(0xFF231C19),
        surfaceVariant = Color(0xFFEDE5D9),
        onSurfaceVariant = Color(0xFF665B53),
        surfaceTint = Color(0xFF8F5A00),
        inverseSurface = Color(0xFF35302C),
        inverseOnSurface = Color(0xFFF6EFE4),
        error = Color(0xFFB3372A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFDE0D9),
        onErrorContainer = Color(0xFF5C1A12),
        outline = Color(0xFF9A8E83),
        outlineVariant = Color(0xFFE3D9CC),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFFFCF6),
        surfaceContainer = Color(0xFFF6EFE3),
        surfaceContainerHigh = Color(0xFFF1E9DC),
        surfaceContainerHighest = Color(0xFFEBE2D4),
        surfaceContainerLow = Color(0xFFFFFCF6),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFE9E1D5),
    )

/** Colors Material 3 has no slot for: status tones, cards and the log terminal. */
@Immutable
internal data class ChamberColors(
    val card: Color,
    val cardBorder: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val idle: Color,
    val terminalBackground: Color,
    val terminalForeground: Color,
    val terminalMuted: Color,
    val terminalBorder: Color,
)

internal val DarkChamberColors =
    ChamberColors(
        card = Color(0xFF1C1A19),
        cardBorder = Color(0xFF2B2726),
        success = Color(0xFF8CC79A),
        successContainer = Color(0xFF1F3423),
        onSuccessContainer = Color(0xFFC9EBD0),
        warning = Color(0xFFEDB449),
        idle = Color(0xFF7A716B),
        terminalBackground = Color(0xFF0F0D0C),
        terminalForeground = Color(0xFFD6CFC3),
        terminalMuted = Color(0xFF857C72),
        terminalBorder = Color(0xFF26221F),
    )

internal val LightChamberColors =
    ChamberColors(
        card = Color(0xFFFFFDF8),
        cardBorder = Color(0xFFE6DCCE),
        success = Color(0xFF2F7A45),
        successContainer = Color(0xFFD9EEDC),
        onSuccessContainer = Color(0xFF0E3A1C),
        warning = Color(0xFF9A5B00),
        idle = Color(0xFF9A8E83),
        terminalBackground = Color(0xFFF1EBE2),
        terminalForeground = Color(0xFF3B332E),
        terminalMuted = Color(0xFF7A6F66),
        terminalBorder = Color(0xFFE2D8CA),
    )
