package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.android.PhoneProcessNonce

/**
 * Identity of one display-service instance, carried by its notification intents.
 *
 * A fixed request code plus an action is not an identity: an old notification could then close a new
 * task. The owner combines this process's nonce, a per-instance id and the opaque task-display owner,
 * and travels both in the intent data URI and as an extra. A receiver only acts for the exact current
 * owner; anything else is a no-op.
 */
internal object PhoneOverlayDisplayOwner {
    private const val SEPARATOR = "|"
    private const val URI_PREFIX = "andchamber-phone-overlay://"

    fun mint(
        nonce: String,
        instance: Long,
        taskOwner: String,
    ): String = "$nonce$SEPARATOR$instance$SEPARATOR$taskOwner"

    fun matches(
        incoming: String?,
        current: String?,
    ): Boolean = incoming != null && current != null && incoming == current

    /** True only when the owner was minted by this process. */
    fun hasCurrentNonce(owner: String?): Boolean = owner?.substringBefore(SEPARATOR) == PhoneProcessNonce.value

    /** The opaque task-display owner part (`<ownerKey>:<generation>`), or null. */
    fun taskOwner(owner: String?): String? = owner?.substringAfter(SEPARATOR, "")?.substringAfter(SEPARATOR, "")?.takeIf { it.isNotEmpty() }

    fun closeIntentData(owner: String): String = "$URI_PREFIX" + "close/" + owner

    fun openIntentData(owner: String): String = "$URI_PREFIX" + "open/" + owner

    /** Owner encoded in one of our display URIs, if this is one of ours. */
    fun fromData(data: String?): String? {
        val value = data ?: return null
        if (!value.startsWith(URI_PREFIX)) return null
        return value.substringAfter(URI_PREFIX + "close/", "")
            .ifEmpty { value.substringAfter(URI_PREFIX + "open/", "") }
            .takeIf { it.isNotEmpty() }
    }
}
