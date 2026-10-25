package io.andchamber.app.phone.core

/** UI/notification view of the controller. [sequence] is strictly increasing for every publish. */
data class PhoneState(
    val sequence: Long = 0L,
    val lastEvent: PhoneEvent = PhoneEvent.INITIAL,
    val enabled: Boolean = false,
    val phase: PhonePhase = PhonePhase.DISABLED,
    val epoch: Long = 0L,
    val grant: PhoneGrant? = null,
    val connection: PhoneConnectionState = PhoneConnectionState.DISCONNECTED,
    val connectionId: String? = null,
    val runtimeId: String? = null,
    val capabilities: PhoneCapabilities? = null,
    /** No nodes or labels here: state is safe for notifications. */
    val observation: PhoneObservationSummary? = null,
    val paused: Boolean = false,
    val blocked: PhoneBlockReason? = null,
    /** True from mutation admission until its driver call settles or is cancelled. */
    val inFlightMutation: Boolean = false,
    /** Pause was requested while a mutation was in flight; it stays until that call settles. */
    val pausing: Boolean = false,
)

enum class PhonePhase {
    DISABLED,
    NO_GRANT,
    READY,
    PAUSED,
    BLOCKED,
}

enum class PhoneConnectionState {
    DISCONNECTED,
    CONNECTED,
}

enum class PhoneBlockReason {
    /** A mutating call timed out, was cancelled, or was reported uncertain. */
    UNKNOWN_OUTCOME,
}

data class PhoneObservationSummary(
    val binding: PhoneObservationBinding,
    val nodeCount: Int,
    val observedAtMs: Long,
)

enum class PhoneEvent {
    INITIAL,
    ENABLED,
    DISABLED,
    CONNECTED,
    DISCONNECTED,
    CONNECTION_LOST,
    GRANTED,
    REVOKED,
    PAUSED,
    RESUMED,
    LEASE_EXPIRED,
    LOCKED,
    HOST_RETURNED,
    RUNTIME_CHANGED,
    OBSERVED,
    CAPTURED,
    MUTATION_STARTED,
    MUTATION_SETTLED,
    OBSERVATION_INVALIDATED,
    OUTCOME_UNKNOWN,
    UNKNOWN_ACKNOWLEDGED,
}
