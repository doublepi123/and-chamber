package io.andchamber.app.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Counts queued and running work and publishes `busy = count > 0`. Both the count and the published
 * flag change inside one lock, so no interleaving of begin/end calls from different threads can
 * publish false while work is still counted.
 */
class WorkCounter {
    private val lock = Any()
    private var count = 0
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    fun begin() {
        synchronized(lock) {
            count++
            mutableBusy.value = true
        }
    }

    fun end() {
        synchronized(lock) {
            check(count > 0) { "end() without begin()" }
            count--
            mutableBusy.value = count > 0
        }
    }

    /** Current number of counted items. For tests and diagnostics. */
    fun pending(): Int = synchronized(lock) { count }
}
