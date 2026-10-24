package io.andchamber.app.ui

import android.content.SharedPreferences
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.runtime.ServerTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/*
 * "Keep screen on while the local server runs": an opt-in, foreground-only screen hold.
 *
 * The screen is held on (FLAG_KEEP_SCREEN_ON on the activity's window, never a WakeLock) only while
 * all of these hold: the setting is on, the local server is Running, the activity is at least
 * STARTED, and no remote server's page is the one in view. Anything else (stopped, failed, not set
 * up, in the background, a remote page, the activity going away) lets the screen sleep normally.
 */

/** The pure rule; see the file comment. */
internal object ScreenAwakePolicy {
    fun keepScreenOn(
        enabled: Boolean,
        state: RuntimeState,
        lifecycle: Lifecycle.State,
        viewedTargetId: String?,
    ): Boolean =
        enabled &&
            state is RuntimeState.Running &&
            lifecycle.isAtLeast(Lifecycle.State.STARTED) &&
            !remoteInView(viewedTargetId)

    /**
     * [viewedTargetId] is the instance the app records as being viewed ([UiPrefs.KEY_LAST_TARGET_ID]):
     * null on Home, [ServerTarget.LOCAL_ID] on the local page, a remote's id on that remote's page.
     * Anything that isn't Home or the local page counts as a remote, so an unknown id never holds the
     * screen.
     */
    fun remoteInView(viewedTargetId: String?): Boolean = viewedTargetId != null && viewedTargetId != ServerTarget.LOCAL_ID
}

/**
 * The setting and what the screen hold needs to know about navigation, from one SharedPreferences
 * file ([UiPrefs.FILE]) shared with [UiPrefs]. One instance per process (a Koin singleton): the
 * activity and Home read the same flows, so they never disagree.
 *
 * [prefs] null (previews, tests) keeps everything in memory, off.
 */
internal class ScreenAwakeController(
    private val prefs: SharedPreferences?,
) {
    private val enabledState = MutableStateFlow(readEnabled())

    /** The user's choice. Off by default. */
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()

    private val viewedState = MutableStateFlow(readViewed())

    /**
     * The instance in view as [UiPrefs] records it (see [ScreenAwakePolicy.remoteInView]). Read, never
     * written, here; [UiPrefs] in the app root updates it on every navigation.
     */
    val viewedTargetId: StateFlow<String?> = viewedState.asStateFlow()

    // Kept in a field: SharedPreferences holds its listeners weakly.
    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_KEEP_SCREEN_ON -> enabledState.value = readEnabled()
                UiPrefs.KEY_LAST_TARGET_ID -> viewedState.value = readViewed()
                // clear() reports a null key (API 30+).
                null -> {
                    enabledState.value = readEnabled()
                    viewedState.value = readViewed()
                }
            }
        }

    init {
        prefs?.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
        prefs?.edit()?.putBoolean(KEY_KEEP_SCREEN_ON, enabled)?.apply()
    }

    private fun readEnabled(): Boolean = prefs?.getBoolean(KEY_KEEP_SCREEN_ON, false) ?: false

    private fun readViewed(): String? = prefs?.getString(UiPrefs.KEY_LAST_TARGET_ID, null)

    companion object {
        /** Own key in [UiPrefs.FILE]; [UiPrefs] never reads or writes it. */
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on_local"
    }
}

/** Turns FLAG_KEEP_SCREEN_ON on or off; a seam so the hold is tested without a window. */
internal fun interface ScreenOnFlag {
    fun set(on: Boolean)
}

internal fun Window.keepScreenOnFlag(): ScreenOnFlag =
    ScreenOnFlag { on ->
        if (on) {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

/**
 * Sets the flag only on a change, and clears only a flag it set itself: with the setting off it
 * never touches the window, so a flag someone else holds stays.
 */
internal class ScreenAwakeHold(
    private val flag: ScreenOnFlag,
) {
    var held: Boolean = false
        private set

    fun update(keep: Boolean) {
        if (keep == held) return
        held = keep
        flag.set(keep)
    }

    fun release() = update(false)
}

/** Whether to hold the screen now, from the four inputs of [ScreenAwakePolicy]. */
internal fun screenAwakeFlow(
    enabled: Flow<Boolean>,
    runtime: Flow<RuntimeState>,
    lifecycle: Flow<Lifecycle.State>,
    viewedTargetId: Flow<String?>,
): Flow<Boolean> =
    combine(enabled, runtime, lifecycle, viewedTargetId) { on, state, life, viewed ->
        ScreenAwakePolicy.keepScreenOn(on, state, life, viewed)
    }.distinctUntilChanged()

/** Follows [keep] until cancelled; the hold is always released on the way out. */
internal suspend fun holdScreenAwake(
    keep: Flow<Boolean>,
    hold: ScreenAwakeHold,
) {
    try {
        keep.collect(hold::update)
    } finally {
        hold.release()
    }
}

/**
 * Binds the screen hold to [window] for as long as this owner lives. Runs on the main thread and
 * outside Compose, so it still reacts after ON_STOP (when composition is paused); the owner's scope
 * ends at ON_DESTROY, which releases the hold.
 */
internal fun LifecycleOwner.keepScreenOnWhileLocalRuns(
    window: Window,
    enabled: StateFlow<Boolean>,
    runtime: StateFlow<RuntimeState>,
    viewedTargetId: StateFlow<String?>,
): Job {
    val hold = ScreenAwakeHold(window.keepScreenOnFlag())
    // lifecycleScope runs on Main.immediate, where window flags must be changed.
    return lifecycleScope.launch {
        holdScreenAwake(screenAwakeFlow(enabled, runtime, lifecycle.currentStateFlow, viewedTargetId), hold)
    }
}

/** What the Details row shows and does; null hides the row. */
internal class ScreenAwakeSetting(
    val enabled: Boolean,
    val onChange: (Boolean) -> Unit,
)

/** The app's own setting, or null in previews and wherever the app's singletons aren't running. */
@Composable
internal fun rememberScreenAwakeSetting(): ScreenAwakeSetting? {
    val inspection = LocalInspectionMode.current
    val controller =
        remember(inspection) {
            if (inspection) null else runCatching { GlobalContext.getOrNull()?.getOrNull<ScreenAwakeController>() }.getOrNull()
        } ?: return null
    val enabled by controller.enabled.collectAsState()
    return remember(controller, enabled) { ScreenAwakeSetting(enabled, controller::setEnabled) }
}
