package io.andchamber.app.ui

import io.andchamber.app.runtime.RuntimeState

/*
 * Pure launch rules, kept free of Android and Compose so they are unit-tested directly.
 */

/** Where a launch takes the user, once the runtime has looked at the disk. */
internal enum class LaunchTarget {
    /** The runtime is still detecting; decide later. */
    Wait,

    /** Show Home: nothing to open yet, or something needs the user's attention there. */
    Home,

    /** The local server is up or coming up: open it. */
    OpenLocal,

    /** Installed but stopped: start it, then open it. */
    StartAndOpenLocal,
}

internal fun launchTarget(state: RuntimeState): LaunchTarget =
    when (state) {
        RuntimeState.Detecting -> LaunchTarget.Wait
        is RuntimeState.Running, is RuntimeState.Starting -> LaunchTarget.OpenLocal
        RuntimeState.Stopped -> LaunchTarget.StartAndOpenLocal
        // Setup, failures and unsupported devices belong on Home, where their actions are; an
        // in-flight stop was asked for by the user (or the notification), so it isn't undone.
        RuntimeState.NotInstalled,
        RuntimeState.UnsupportedAbi,
        is RuntimeState.Installing,
        is RuntimeState.Failed,
        is RuntimeState.Stopping,
        -> LaunchTarget.Home
    }

/** How the app's first frames look on a launch. */
internal enum class LaunchStage {
    /** Launch cover up, waiting for detection; the screens aren't composed yet. */
    Waiting,

    /** Launch cover up while the app switches to the local server underneath it. */
    Opening,

    /** No cover; screens as usual. */
    Done,
}

/**
 * The stage for the first frame. A launch that will end on Home starts without the cover, so
 * nothing extra is ever shown; one that may open the local server covers Home from frame one.
 */
internal fun initialLaunchStage(
    launchPending: Boolean,
    autoOpenLocal: Boolean,
    state: RuntimeState,
): LaunchStage {
    if (!launchPending || !autoOpenLocal) return LaunchStage.Done
    return when (launchTarget(state)) {
        LaunchTarget.Wait -> LaunchStage.Waiting
        LaunchTarget.Home -> LaunchStage.Done
        LaunchTarget.OpenLocal, LaunchTarget.StartAndOpenLocal -> LaunchStage.Opening
    }
}

/**
 * A start that was just requested reads as Starting until the runtime reports back, so screens
 * don't show "Stopped" (or the old failure) for the moment the service takes to pick it up.
 * [pendingStartFrom] is the state the start was requested from, or null when none is pending.
 */
internal fun shownLocalState(
    state: RuntimeState,
    pendingStartFrom: RuntimeState?,
): RuntimeState {
    val waitingForRuntime =
        pendingStartFrom != null &&
            state == pendingStartFrom &&
            (state is RuntimeState.Stopped || state is RuntimeState.Failed)
    return if (waitingForRuntime) RuntimeState.Starting(detail = null) else state
}
