package io.andchamber.app.notify

import io.andchamber.app.runtime.FailurePhase
import io.andchamber.app.runtime.RuntimeState

/** An AndChamber-level error to show as a notification, with a stable key for dedupe. */
data class RuntimeAlert(
    /** Stable key: the same key is not shown twice until it has cleared. */
    val key: String,
    val title: String,
    val body: String,
)

/**
 * Turns controller state transitions and OpenCode health into alerts.
 *
 * - Failed(Install) → "Setup failed", Failed(Start) → "OpenChamber didn't start",
 *   Failed(Runtime) → "OpenChamber stopped". A user-initiated Stop is never an alert: Stop goes to
 *   Stopped/NotInstalled and never to Failed.
 * - While Running, a non-null OpenCode error with OpenCode not running → "OpenCode backend error".
 *   Repeats are suppressed until the error clears.
 */
class RuntimeErrorWatch(
    /**
     * Localised titles and bodies, given by the notifier from resources. The default keeps the
     * English technical text, for logic tests that do not need a Context.
     */
    private val titles: Titles = Titles.English,
) {
    /** The alert titles, keyed by what went wrong. */
    data class Titles(
        val setupFailed: String,
        val startFailed: String,
        val stopped: String,
        val opencodeError: String,
    ) {
        companion object {
            val English = Titles("Setup failed", "OpenChamber didn't start", "OpenChamber stopped", "OpenCode backend error")
        }
    }

    private var shownKeys = mutableSetOf<String>()

    /** Alerts to show for a state change. Empty when the state is not an error or was already shown. */
    fun onState(state: RuntimeState): List<RuntimeAlert> {
        val alert =
            (state as? RuntimeState.Failed)?.let { failed ->
                when (failed.phase) {
                    FailurePhase.Install ->
                        RuntimeAlert("install:${failed.reason}", titles.setupFailed, failed.reason)
                    FailurePhase.Start ->
                        RuntimeAlert("start:${failed.reason}", titles.startFailed, failed.reason)
                    FailurePhase.Runtime ->
                        RuntimeAlert("runtime:${failed.reason}", titles.stopped, failed.reason)
                }
            }
        return listOfNotNull(alert).filter { shownKeys.add(it.key) }
    }

    /**
     * Alerts from a `/health` reading while Running. [openCodeError] is OpenCode's last error;
     * [openCodeRunning] tells whether it is currently running. A cleared error re-arms the alert.
     */
    fun onHealth(
        openCodeError: String?,
        openCodeRunning: Boolean,
    ): List<RuntimeAlert> {
        val failing = !openCodeError.isNullOrBlank() && !openCodeRunning
        if (!failing) {
            shownKeys.removeAll { it.startsWith("opencode:") }
            return emptyList()
        }
        val key = "opencode:$openCodeError"
        val alert = RuntimeAlert(key, titles.opencodeError, openCodeError!!)
        return if (shownKeys.add(key)) listOf(alert) else emptyList()
    }

    /** Forget everything, e.g. when the user starts the runtime again. */
    fun reset() {
        shownKeys.clear()
    }
}
