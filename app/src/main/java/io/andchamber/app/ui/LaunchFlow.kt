package io.andchamber.app.ui

import androidx.compose.ui.res.stringResource
import io.andchamber.app.R
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
import androidx.compose.runtime.mutableStateMapOf
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

    /** Open the last used OpenChamber when the app starts. On by default. */
    val autoOpenLocal: Boolean get() = autoOpen.value

    fun updateAutoOpenLocal(enabled: Boolean) {
        autoOpen.value = enabled
        prefs?.edit()?.putBoolean(KEY_AUTO_OPEN_LOCAL, enabled)?.apply()
    }

    private var lastId = prefs?.getString(KEY_LAST_TARGET_ID, null)

    /** Last page of every instance, by [ServerTarget] id. Snapshot state, so screens read the latest. */
    private val pages = mutableStateMapOf<String, RememberedPage>()

    init {
        val stored = prefs?.all.orEmpty()
        for ((key, value) in stored) {
            if (!key.startsWith(PAGE_PATH_PREFIX) || value !is String) continue
            val id = key.removePrefix(PAGE_PATH_PREFIX)
            val origin = stored[PAGE_ORIGIN_PREFIX + id] as? String ?: continue
            pages[id] = RememberedPage(value, origin)
        }
        // Before pages were kept per instance, only the last instance had one: move it over.
        val legacyPath = prefs?.getString(KEY_LEGACY_TARGET_PATH, null)
        val legacyOrigin = prefs?.getString(KEY_LEGACY_TARGET_ORIGIN, null)
        if (prefs != null && (legacyPath != null || legacyOrigin != null)) {
            val editor = prefs.edit().remove(KEY_LEGACY_TARGET_PATH).remove(KEY_LEGACY_TARGET_ORIGIN)
            val id = lastId
            if (id != null && legacyPath != null && legacyOrigin != null && id !in pages) {
                pages[id] = RememberedPage(legacyPath, legacyOrigin)
                editor.putString(PAGE_PATH_PREFIX + id, legacyPath).putString(PAGE_ORIGIN_PREFIX + id, legacyOrigin)
            }
            editor.apply()
        }
    }

    /** The instance the user last had open ([ServerTarget] id); null means they left from Home. */
    val lastTargetId: String? get() = lastId

    fun setLastTargetId(targetId: String) {
        if (lastId == targetId) return
        lastId = targetId
        prefs?.edit()?.putString(KEY_LAST_TARGET_ID, targetId)?.apply()
    }

    /** Forgets which instance was open (the user went Home); every instance keeps its page. */
    fun clearLastTarget() {
        if (lastId == null) return
        lastId = null
        prefs?.edit()?.remove(KEY_LAST_TARGET_ID)?.apply()
    }

    /** The last page of [targetId] (path and query only, `/?session=…`) and the origin it was on. */
    fun pageFor(targetId: String): RememberedPage? = pages[targetId]

    /** Records [path] on [origin] as the last page of [targetId]. */
    fun setPage(
        targetId: String,
        path: String,
        origin: String,
    ) {
        val page = RememberedPage(path, origin)
        if (pages[targetId] == page) return
        pages[targetId] = page
        prefs?.edit()
            ?.putString(PAGE_PATH_PREFIX + targetId, path)
            ?.putString(PAGE_ORIGIN_PREFIX + targetId, origin)
            ?.apply()
    }

    /** Drops the page of [targetId] (e.g. the server was removed). */
    fun forgetPage(targetId: String) {
        if (pages.remove(targetId) == null) return
        prefs?.edit()
            ?.remove(PAGE_PATH_PREFIX + targetId)
            ?.remove(PAGE_ORIGIN_PREFIX + targetId)
            ?.apply()
    }

    /** Drops the pages of instances not in [knownIds] (servers removed elsewhere). */
    fun retainPages(knownIds: Set<String>) {
        val stale = pages.keys.filterNot { it in knownIds }.toList()
        if (stale.isEmpty()) return
        val editor = prefs?.edit()
        for (id in stale) {
            pages.remove(id)
            editor?.remove(PAGE_PATH_PREFIX + id)?.remove(PAGE_ORIGIN_PREFIX + id)
        }
        editor?.apply()
    }

    companion object {
        const val FILE = "ui_prefs"
        const val KEY_AUTO_OPEN_LOCAL = "auto_open_local"
        const val KEY_LAST_TARGET_ID = "last_target_id"
        const val PAGE_PATH_PREFIX = "page_path:"
        const val PAGE_ORIGIN_PREFIX = "page_origin:"
        private const val KEY_LEGACY_TARGET_PATH = "last_target_path"
        private const val KEY_LEGACY_TARGET_ORIGIN = "last_target_origin"
    }
}

/** A remembered page: [path] (`/?session=…`) on the server's canonical [origin] at the time. */
internal data class RememberedPage(
    val path: String,
    val origin: String,
)

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
            text = stringResource(R.string.launch_checking),
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
