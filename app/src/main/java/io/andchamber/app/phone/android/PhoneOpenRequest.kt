package io.andchamber.app.phone.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A one-shot "show the phone screen" request from the session notification or the paired
 * notification. The Activity asks for it; the navigation graph handles it. It carries no state and no
 * authority: it only decides which screen is shown.
 */
object PhoneOpenRequest {
    private val counter = MutableStateFlow(0)

    /** Strictly increasing; 0 means nothing was requested. */
    val requests: StateFlow<Int> = counter.asStateFlow()

    fun offer() {
        counter.value = counter.value + 1
    }
}
