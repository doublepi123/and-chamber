package io.andchamber.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * OpenChamber defaults to a monospace UI font, so headings and labels use the system monospace
 * face for the same "terminal" feel, while body copy stays in the system sans for readability.
 */
internal val MonoFamily: FontFamily = FontFamily.Monospace
private val SansFamily: FontFamily = FontFamily.SansSerif

internal val ChamberTypography =
    Typography(
        displaySmall =
            TextStyle(
                fontFamily = MonoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                letterSpacing = (-0.5).sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = MonoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 21.sp,
                lineHeight = 28.sp,
                letterSpacing = (-0.2).sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = SansFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = SansFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.1.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = SansFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = SansFamily,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = SansFamily,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                letterSpacing = 0.15.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = SansFamily,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                letterSpacing = 0.2.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = SansFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = MonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.2.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = MonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 1.2.sp,
            ),
    )

/** Monospace styles for addresses, versions and log output. */
internal object ChamberTextStyles {
    val code = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 18.sp)
    val codeSmall = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 16.sp)
    val log = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 15.sp)
}
