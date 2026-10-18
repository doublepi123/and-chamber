package io.andchamber.app.notify

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/** Whether the app process is in the foreground. Abstracted so the suppression rule is tested directly. */
fun interface AppForeground {
    fun isInForeground(): Boolean
}

/** Production source: the process lifecycle is at least STARTED (visible to the user). */
object ProcessForeground : AppForeground {
    override fun isInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
