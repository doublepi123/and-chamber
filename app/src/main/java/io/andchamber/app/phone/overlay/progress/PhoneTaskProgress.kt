package io.andchamber.app.phone.overlay.progress

/** Identity of one native-confirmed task. Created only by native integration, never by the model. */
data class PhoneTaskKey(
    val taskId: String,
    val runtimeId: String,
) {
    init {
        require(taskId.length in 1..MAX_ID_CHARS && taskId.none(Char::isISOControl)) { "taskId" }
        require(runtimeId.length in 1..MAX_ID_CHARS && runtimeId.none(Char::isISOControl)) { "runtimeId" }
    }

    companion object {
        const val MAX_ID_CHARS = 128
    }
}

/** Where an event came from. Model events are always unverified self-report. */
enum class PhoneProgressSource {
    NATIVE,
    MODEL,
}

/** Typed event kind. Native facts and model narration never share a kind. */
enum class PhoneProgressKind {
    TASK_STARTED,
    TASK_EPOCH_UPDATED,
    OBSERVED,
    MUTATION_SENT,
    MUTATION_NOT_SENT,
    MUTATION_UNKNOWN,
    BUSINESS_RESULT,
    STOP_REQUESTED,
    STOP_ACKNOWLEDGED,
    TASK_ENDED,
    MODEL_PROGRESS,
    MODEL_NEED_USER,
    MODEL_FINISH,
}

/** Typed reason. No page text, labels, coordinates, input or account data belongs here. */
enum class PhoneProgressReason {
    NONE,
    USER_CONFIRMED,
    GRANT_UPDATED,
    OBSERVED,
    USER_REQUESTED,
    TAKEOVER,
    LEASE_EXPIRED,
    HOST_RETURNED,
    CONNECTION_LOST,
    DEVICE_LOCKED,
    RUNTIME_CHANGED,
    OUTCOME_UNKNOWN,
    DELIVERY_SENT,
    DELIVERY_NOT_SENT,
    DELIVERY_UNKNOWN,
    VERIFIED_DONE,
    VERIFIED_NOT_DONE,
    MODEL_REPORTED,
    NEED_USER,
}

/** Delivery is separate from the business result. SENT never means the task succeeded. */
enum class PhoneMutationDelivery {
    SENT,
    NOT_SENT,
    UNKNOWN,
}

/** A native-verified business result. The tool's ok or a model finish is never one of these. */
enum class PhoneBusinessResult {
    VERIFIED_DONE,
    VERIFIED_NOT_DONE,
}

/**
 * What native control confirmed after a halt. [sentActionsMayStillApply] is always true: this is
 * not a rollback claim. It only says new reads/writes are blocked and late reads are not delivered.
 */
data class PhoneStopAcknowledgement(
    val newOperationsBlocked: Boolean,
    val lateReadsBlocked: Boolean,
    val sentActionsMayStillApply: Boolean = true,
) {
    val confirmed: Boolean get() = newOperationsBlocked && lateReadsBlocked
}

enum class PhoneTaskState {
    ACTIVE,
    /** The last mutation may or may not have landed; the task is not a success. */
    UNKNOWN_OUTCOME,
    /** A stop/handoff was requested, but native control has not confirmed it yet. */
    STOPPING,
    /** Native control confirmed a handoff and the person is handling the phone. */
    WAITING_USER,
    /** The agent reported the end (or native ended it); the result is still pending verification. */
    ENDED_PENDING_CHECK,
}

/**
 * One bounded, sanitized event. [text] exists only for model events and is always unverified.
 * Native events carry typed facts only: no screenshots, refs, labels, input or coordinates.
 */
data class PhoneProgressEvent(
    val seq: Long,
    val task: PhoneTaskKey,
    val grantEpoch: Long,
    val source: PhoneProgressSource,
    val kind: PhoneProgressKind,
    val reason: PhoneProgressReason,
    val text: String?,
    val delivery: PhoneMutationDelivery? = null,
    val business: PhoneBusinessResult? = null,
    val atMs: Long,
)

/**
 * Native-only projection for the UI facade. It is never returned by the tool `status`. Its
 * [summary], [packageName] and event text are for the native detail panel only; do not copy them
 * into a notification or a log by default.
 */
data class PhoneTaskSnapshot(
    val key: PhoneTaskKey,
    val grantEpoch: Long,
    /** Null until native binds a manual task's first-action route; the router still gates it. */
    val routeSession: String?,
    val summary: String,
    val packageName: String,
    val state: PhoneTaskState,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    /** Latest [limit] events, chronological. */
    val events: List<PhoneProgressEvent>,
    val stopAck: PhoneStopAcknowledgement?,
    val finishReported: Boolean,
    /**
     * Native verified result for the current grant. A strictly newer epoch clears this snapshot
     * field so an old success is not misreported as the new grant's result; the historical
     * BUSINESS_RESULT events stay in [events].
     */
    val business: PhoneBusinessResult?,
)

/** Result of one store operation. Only [ACCEPTED] records or changes anything. */
enum class PhoneProgressApply {
    ACCEPTED,
    DUPLICATE,
    CAPACITY,
    NO_TASK,
    STALE_TASK,
    EPOCH_CHANGED,
    SESSION_MISMATCH,
    INVALID,
    TASK_NOT_ACTIVE,
    STOP_UNCONFIRMED,
}
