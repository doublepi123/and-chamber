package io.andchamber.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * Sets status/navigation bar icon contrast. The activity handles uiMode changes itself, so the
 * values set by enableEdgeToEdge() at launch would otherwise go stale when the theme flips.
 */
@Composable
internal fun SystemBarsEffect(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, darkIcons) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = darkIcons
            controller.isAppearanceLightNavigationBars = darkIcons
        }
        onDispose { }
    }
}

/** Keeps the window background in step with the theme, so resizes and transitions don't flash. */
@Composable
internal fun WindowBackgroundEffect(color: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, color) {
        view.context.findActivity()?.window?.setBackgroundDrawable(ColorDrawable(color.toArgb()))
        onDispose { }
    }
}
