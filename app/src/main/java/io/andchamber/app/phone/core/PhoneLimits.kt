package io.andchamber.app.phone.core

/** Fixed bounds for the first phone-control version. Nothing here is a policy grant. */
object PhoneLimits {
    /** One driver call. Callers may ask for less; they never get more. */
    const val DEFAULT_CALL_MS: Long = 5_000L
    const val MAX_CALL_MS: Long = 15_000L

    /**
     * A finite lease/per-task duration is short-lived on purpose. The native UI chooses the exact
     * value in this range. The explicit [PhoneTimeLimits.PERMANENT] sentinel is the only value
     * outside it, and it can only come from native configuration after the person confirms it.
     */
    const val MIN_LEASE_MS: Long = 1_000L
    const val MAX_LEASE_MS: Long = 30 * 60 * 1_000L

    /** Request-id records per grant epoch. Full means new calls are refused; old records stay. */
    const val MAX_REQUESTS: Int = 256

    const val MAX_NODES: Int = 500
    const val MAX_NODE_LABEL_CHARS: Int = 200
    const val MAX_ROLE_CHARS: Int = 64
    const val MAX_REF_CHARS: Int = 64

    const val MAX_INPUT_CHARS: Int = 1_000
    const val MIN_SWIPE_MS: Int = 50
    const val MAX_SWIPE_MS: Int = 5_000

    const val MAX_DISPLAY_PX: Int = 10_000
    const val MAX_WINDOW_ID_CHARS: Int = 128
    const val MAX_REQUEST_ID_CHARS: Int = 128
    const val MAX_SESSION_LABEL_CHARS: Int = 128
    const val MAX_PACKAGE_CHARS: Int = 200

    /** Capture bytes are returned to the agent, so they stay bounded in the first version. */
    const val MAX_CAPTURE_BYTES: Int = 8 * 1_024 * 1_024
    val CAPTURE_MIME_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/webp")
}
