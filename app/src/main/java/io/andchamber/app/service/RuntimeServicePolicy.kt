package io.andchamber.app.service

import io.andchamber.app.runtime.RuntimeState

/**
 * What the foreground service should do, decided from the controller's explicit [busy] signal and
 * its state. Pure so the lifecycle is unit-tested without Android.
 *
 *  - Stay foreground (and keep the wake lock) while work is queued or running, or while the server
 *    is Running or Starting (Starting covers a watchdog restart that is waiting out its backoff).
 *  - Stop only when no work is pending AND the state is terminal-at-rest: Stopped, NotInstalled,
 *    Failed or UnsupportedAbi. Intermediate Stopped (between install and start) never ends the service
 *    because busy is still true then.
 */
object RuntimeServicePolicy {
    enum class Action {
        /** Foreground with the wake lock held. */
        KeepRunning,

        /** Release the wake lock, leave the foreground and stop the service. */
        Finish,
    }

    fun decide(
        busy: Boolean,
        state: RuntimeState,
    ): Action =
        when {
            busy -> Action.KeepRunning
            state is RuntimeState.Running || state is RuntimeState.Starting -> Action.KeepRunning
            state is RuntimeState.Stopped ||
                state is RuntimeState.NotInstalled ||
                state is RuntimeState.Failed ||
                state is RuntimeState.UnsupportedAbi -> Action.Finish
            // Detecting, Installing, Stopping with nothing queued: transient, not a reason to stop.
            else -> Action.KeepRunning
        }

    /** The wake lock must be held exactly while the service is foreground. */
    fun wakeLockHeld(action: Action): Boolean = action == Action.KeepRunning
}
