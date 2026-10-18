package io.andchamber.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Returns [value], or the last non-null value seen when it becomes null (e.g. during exit animations). */
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { LastValue<T>() }
    if (value != null) holder.value = value
    return holder.value
}

private class LastValue<T : Any> {
    var value: T? = null
}

/** Fades and lifts content in once, [index] steps after the screen first appears. */
@Composable
internal fun Modifier.revealOnEnter(
    index: Int,
    enabled: Boolean,
): Modifier {
    if (!enabled) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        progress.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 16.dp.toPx()
    }
}

/** Copies text; shows a toast below Android 13, where the system has no copy confirmation. */
@Composable
internal fun rememberCopyAction(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return remember(clipboard, context) {
        { text: String ->
            clipboard.setText(AnnotatedString(text))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, Strings.copied, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * Asks for POST_NOTIFICATIONS on Android 13+ the first time it is invoked in this session, so the
 * runtime's foreground-service notification can show. Never blocks the caller's action.
 */
@Composable
internal fun rememberNotificationPermissionRequest(): () -> Unit {
    if (LocalInspectionMode.current || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return remember { {} }
    }
    val context = LocalContext.current
    val asked = rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context, launcher) {
        {
            val granted =
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted && !asked.value) {
                asked.value = true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
