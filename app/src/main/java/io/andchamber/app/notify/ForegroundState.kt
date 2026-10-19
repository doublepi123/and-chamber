package io.andchamber.app.notify

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Whether the local OpenChamber page is on screen right now, so its own web UI already shows
 * OpenChamber's events. Abstracted so the suppression rule is tested directly.
 */
fun interface AppForeground {
    fun isInForeground(): Boolean
}

/**
 * Who reports that the local page is showing. One screen at a time owns the report: a new report
 * replaces the old owner, and an owner can only clear its own report, so a screen that is leaving
 * never hides the one that took over. Starts as "not showing": when in doubt a notification is posted
 * (one extra notification beats a missed one). Thread-safe; read from the notification thread.
 */
class PageVisibility {
    private var owner: Any? = null

    /** [owner] reports whether its local page is showing ([showing] true) or no longer ([showing] false). */
    @Synchronized
    fun report(
        owner: Any,
        showing: Boolean,
    ) {
        if (showing) {
            this.owner = owner
        } else if (this.owner === owner) {
            this.owner = null
        }
    }

    @Synchronized
    fun isShowing(): Boolean = owner != null
}

/** Process-wide report from the web screen (see ui/web/NotifyVisibility.kt). Memory only: false after a restart. */
val LocalPageVisibility = PageVisibility()

/**
 * Production source (symbol kept for the DI wiring): true only while the local OpenChamber page reports
 * itself on screen with nothing covering it ([LocalPageVisibility]) and the process is at least STARTED
 * (a guard in case a report is missed). Home, a remote server's page, any overlay, a page that hasn't
 * painted, a file picker on top, or the app in the background are all false: the event is posted.
 */
object ProcessForeground : AppForeground {
    override fun isInForeground(): Boolean =
        LocalPageVisibility.isShowing() &&
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
