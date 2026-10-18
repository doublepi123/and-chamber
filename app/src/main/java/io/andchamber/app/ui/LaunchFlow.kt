package io.andchamber.app.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import io.andchamber.app.ui.components.ChamberMark
import kotlinx.coroutines.delay

/** The cover shows only the background this long, then fades the cube in. */
internal const val LAUNCH_MARK_DELAY_MS = 120L

/** The cover stays quiet this long before saying what it is waiting for. */
internal const val LAUNCH_CAPTION_DELAY_MS = 1_500L

/** Longest the cover stays up for detection; after that Home shows, with its "Checking" card. */
internal const val LAUNCH_DETECT_TIMEOUT_MS = 8_000L

/** Fade of the cover into whatever is underneath. */
internal const val LAUNCH_COVER_FADE_MS = 200

/** A tap on the app icon (as opposed to Recents or the notification). */
internal fun Intent.isLauncherLaunch(): Boolean = action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_LAUNCHER)

/** Small UI preferences, in their own file so they never mix with runtime state. */
internal class UiPrefs(
    private val prefs: SharedPreferences?,
) {
    private val autoOpen = mutableStateOf(prefs?.getBoolean(KEY_AUTO_OPEN_LOCAL, true) ?: true)

    /** Open the local OpenChamber when the app starts. On by default. */
    val autoOpenLocal: Boolean get() = autoOpen.value

    fun updateAutoOpenLocal(enabled: Boolean) {
        autoOpen.value = enabled
        prefs?.edit()?.putBoolean(KEY_AUTO_OPEN_LOCAL, enabled)?.apply()
    }

    companion object {
        const val FILE = "ui_prefs"
        const val KEY_AUTO_OPEN_LOCAL = "auto_open_local"
    }
}

/** Read once per activity; the file is tiny, and the launch decision needs it for frame one. */
@Composable
internal fun rememberUiPrefs(): UiPrefs {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    return remember(context, inspection) {
        val prefs =
            if (inspection) null else context.applicationContext.getSharedPreferences(UiPrefs.FILE, Context.MODE_PRIVATE)
        UiPrefs(prefs)
    }
}

/**
 * Neutral first frame of a launch that may open the local server: the app background and the
 * cube, nothing else, so neither Home nor a spinner flashes by. The cube fades in (no hard cut
 * after the system splash, and no flash when the decision takes only a few frames); "Checking…"
 * appears only if the wait gets noticeable. Swallows touches meant for the screens underneath.
 */
@Composable
internal fun LaunchCover(modifier: Modifier = Modifier) {
    var markShown by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LAUNCH_MARK_DELAY_MS)
        markShown = true
        delay(LAUNCH_CAPTION_DELAY_MS - LAUNCH_MARK_DELAY_MS)
        slow = true
    }
    LaunchCoverContent(markShown = markShown, slow = slow, modifier = modifier)
}

@Composable
internal fun LaunchCoverContent(
    markShown: Boolean,
    slow: Boolean,
    modifier: Modifier = Modifier,
) {
    val markFade by animateFloatAsState(if (markShown) 1f else 0f, tween(240), label = "launchMark")
    val captionAlpha by animateFloatAsState(if (slow) 1f else 0f, tween(400), label = "launchCaption")
    // The cube only starts breathing once the caption explains the wait; it begins at full
    // opacity, so there is no jump.
    val breathing: State<Float>? = if (slow) rememberBreathing() else null
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
    ) {
        ChamberMark(
            size = 56.dp,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = markFade * (breathing?.value ?: 1f) },
        )
        // Placed below the centred cube, so the cube never moves when the caption appears.
        Text(
            text = Strings.launchChecking,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .offset(y = 56.dp)
                    .graphicsLayer { alpha = captionAlpha },
        )
    }
}

@Composable
private fun rememberBreathing(): State<Float> {
    val transition = rememberInfiniteTransition(label = "launchBreathing")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "launchBreathingAlpha",
    )
}
