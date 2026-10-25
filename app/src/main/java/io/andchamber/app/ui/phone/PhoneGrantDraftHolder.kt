package io.andchamber.app.ui.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The grant form's draft, held for the app process so it survives leaving this screen (for example
 * to start or restart OpenChamber from Home) and a step change while the runtime isn't ready.
 *
 * Memory only: never written to disk or saved instance state, and cleared when a grant starts or
 * phone control is turned off. Nothing here is a grant.
 */
internal object PhoneGrantDraftHolder {
    var draft: PhoneGrantDraft by mutableStateOf(PhoneGrantDraft())

    fun clear() {
        draft = PhoneGrantDraft()
    }
}
