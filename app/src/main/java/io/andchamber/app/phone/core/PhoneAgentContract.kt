package io.andchamber.app.phone.core

/** Mutation classes the native UI can grant. Observe and capture stay inside the same lease. */
enum class PhoneActionKind {
    TAP,
    SWIPE,
    INPUT,
    BACK,
}

sealed interface PhoneAgentAction {
    data object Observe : PhoneAgentAction

    data object Capture : PhoneAgentAction

    data class TapRef(val ref: String) : PhoneAgentAction

    data class TapPoint(val at: PhonePoint) : PhoneAgentAction

    data class Swipe(
        val from: PhonePoint,
        val to: PhonePoint,
        val durationMs: Int,
    ) : PhoneAgentAction

    /**
     * Types into the currently focused field, or focuses [ref] first when it is present.
     * Non-ASCII text is refused with [PhoneErrorCode.UNICODE_UNSUPPORTED] unless the connected
     * driver declared `unicodeInput`; it is never silently reduced to ASCII.
     */
    data class Input(
        val text: String,
        val ref: String? = null,
    ) : PhoneAgentAction

    data object Back : PhoneAgentAction
}

/**
 * One agent call.
 *
 * [sessionLabel] only tells the notification/UI which session asked for routing. It is not
 * authentication and never changes the grant. [observation] is a precondition, not authority: it
 * must name the current observation generation and the same window/geometry the action was chosen
 * from.
 */
data class PhoneAgentRequest(
    val requestId: String,
    val epoch: Long,
    val sessionLabel: String,
    val action: PhoneAgentAction,
    val observation: PhoneObservationBinding? = null,
)

/** Delivery is separate from the business result. Only SENT is a driver acknowledgement. */
enum class PhoneDelivery {
    NOT_SENT,
    SENT,
    UNKNOWN,
}

/** The driver acknowledged injection of [action]. It never claims that the app did what was meant. */
data class PhoneBusinessResult(
    val action: PhoneActionKind,
    val deviceAcknowledged: Boolean,
)

sealed interface PhoneCallResult {
    val requestId: String
    val epoch: Long

    data class Observed(
        override val requestId: String,
        override val epoch: Long,
        val observation: PhoneObservation,
    ) : PhoneCallResult

    data class Captured(
        override val requestId: String,
        override val epoch: Long,
        val capture: PhoneCapture,
    ) : PhoneCallResult

    data class Dispatched(
        override val requestId: String,
        override val epoch: Long,
        val delivery: PhoneDelivery,
        val business: PhoneBusinessResult? = null,
        val error: PhoneErrorCode? = null,
    ) : PhoneCallResult

    data class Refused(
        override val requestId: String,
        override val epoch: Long,
        val error: PhoneErrorCode,
        val delivery: PhoneDelivery = PhoneDelivery.NOT_SENT,
    ) : PhoneCallResult
}

/** Fixed protocol errors. Messages never carry page text, labels, coordinates or app content. */
enum class PhoneErrorCode {
    DISABLED,
    NOT_GRANTED,
    LEASE_EXPIRED,
    EPOCH_CHANGED,
    PAUSED,
    OUTCOME_UNKNOWN,
    DUPLICATE,
    CAPACITY,
    /** One driver operation is already in flight; nothing is queued. */
    BUSY,
    TARGET_FORBIDDEN,
    TARGET_MISMATCH,
    WINDOW_CHANGED,
    GEOMETRY_CHANGED,
    STALE_OBSERVATION,
    OBSERVATION_REQUIRED,
    UNSUPPORTED_ACTION,
    /** Non-ASCII input was refused before dispatch because the driver lacks unicodeInput. */
    UNICODE_UNSUPPORTED,
    ACTION_NOT_GRANTED,
    INVALID_REQUEST,
    OUT_OF_BOUNDS,
    NOT_CONNECTED,
    DRIVER_UNAVAILABLE,
    DEADLINE,
    /**
     * Read-only observation never reached a stable hierarchy/page. The connection is not
     * necessarily lost and the caller must not treat this as a retry hint.
     */
    HIERARCHY_NOT_IDLE,
    /**
     * A read-only ADB service transaction hit its watchdog (open or read). The connection that
     * served it has been abandoned and the controller has already revoked control.
     */
    READ_TIMEOUT,
    CANCELLED,
}

data class PhoneGrant(
    val epoch: Long,
    val targetPackage: String,
    val allowedActions: Set<PhoneActionKind>,
    val grantedAtMs: Long,
    val expiresAtMs: Long,
)

sealed interface PhoneGrantResult {
    data class Granted(val grant: PhoneGrant) : PhoneGrantResult

    data class Refused(val error: PhoneErrorCode) : PhoneGrantResult
}

sealed interface PhoneConnectionResult {
    data class Connected(val connection: PhoneConnection) : PhoneConnectionResult

    data class Failed(val error: PhoneErrorCode) : PhoneConnectionResult
}
