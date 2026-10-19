package io.andchamber.app.ui.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.andchamber.app.notify.LocalPageVisibility
import io.andchamber.app.notify.NotifyPolicy

/**
 * Tells the notification code whether the local OpenChamber page is really on screen, so only then
 * are OpenChamber's events left to the page itself (see NotifyPolicy.localPageShowing).
 *
 * Uses this screen's own lifecycle (the navigation entry): RESUMED only while it is the current
 * screen, not during a transition, and not behind another activity such as a file picker. Any
 * [overlayShown] (starting, errors, connecting, isolation, not painted yet) counts as not showing.
 * Leaving the screen clears the report; another screen's report is never cleared.
 */
@Composable
internal fun LocalPageVisibilityEffect(
    isLocal: Boolean,
    overlayShown: Boolean,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateAsState()
    val showing = NotifyPolicy.localPageShowing(isLocal, state.isAtLeast(Lifecycle.State.RESUMED), overlayShown)
    val owner = remember { Any() }
    DisposableEffect(owner, showing) {
        LocalPageVisibility.report(owner, showing)
        onDispose { LocalPageVisibility.report(owner, false) }
    }
}
