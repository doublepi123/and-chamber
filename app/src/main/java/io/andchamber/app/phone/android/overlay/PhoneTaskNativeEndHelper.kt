package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.android.PhoneOverlayTaskBinding
import io.andchamber.app.phone.overlay.progress.PhoneStopAcknowledgement

/**
 * One native phone-screen End.
 *
 * The integration freezes the exact native task before it clears any field, asks the existing
 * exact-owner end path to persist/revoke, and only then publishes one native end fact. The frozen
 * binding also survives cleanup of the retained epoch, so an UNKNOWN task can still become
 * ENDED_PENDING_CHECK and keep its needs-check record.
 *
 * This helper is deliberately not called by the model finish or the overlay end paths: they already
 * publish their own typed facts, and a second end would double-record or overwrite a model note.
 * With no current task binding it runs the original cleanup and publishes nothing.
 */
internal class PhoneTaskNativeEndHelper(
    /** Freezes the exact current task (live binding first, then retained identity/epoch/record). */
    private val freeze: () -> PhoneOverlayTaskBinding?,
    /** Existing exact-owner end path; null when it is not the current task/epoch. */
    private val endExact: (PhoneOverlayTaskBinding) -> PhoneStopAcknowledgement?,
    /** Publishes exactly one native ended fact for [endExact]'s confirmed ack. */
    private val publish: (PhoneOverlayTaskBinding, PhoneStopAcknowledgement) -> Unit,
    /** Original finish cleanup used when there is no task binding; it must not invent an end. */
    private val finishWithoutFact: () -> Unit,
) {
    /** True only when a frozen task was ended and its native ended fact was published. */
    fun endFromNativeUi(): Boolean {
        val binding = freeze()
        if (binding == null) {
            finishWithoutFact()
            return false
        }
        val ack = endExact(binding) ?: return false
        publish(binding, ack)
        return true
    }
}
