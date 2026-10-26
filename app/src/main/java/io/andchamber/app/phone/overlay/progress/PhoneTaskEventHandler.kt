package io.andchamber.app.phone.overlay.progress

/**
 * One model-originated task event. The tool server has already passed the published route and
 * frozen the request epoch; the handler validates the native task owner again.
 *
 * [eventId] is the idempotency key. The native integration's store remembers it across tasks and
 * epochs, so a retry cannot replay an already handled event.
 */
sealed interface PhoneTaskEvent {
    val eventId: String

    data class Progress(
        override val eventId: String,
        val phase: String,
    ) : PhoneTaskEvent

    data class NeedUser(
        override val eventId: String,
        val reason: String,
    ) : PhoneTaskEvent

    data class Finish(
        override val eventId: String,
        val summary: String,
    ) : PhoneTaskEvent
}

/**
 * Sanitized native result of a real controller call. It deliberately has no observation, image,
 * input, node label or coordinates: only the fact that a read happened or a typed delivery result.
 */
sealed interface PhoneNativeCallOutcome {
    /** A read succeeded. [captured] is true for a screenshot; no image bytes are included. */
    data class Observed(val captured: Boolean) : PhoneNativeCallOutcome

    /** A mutation's delivery. SENT is not a business success. */
    data class Mutation(val delivery: PhoneMutationDelivery) : PhoneNativeCallOutcome

    /** The controller refused before a delivery fact; the store should not record observed/sent. */
    data object Refused : PhoneNativeCallOutcome
}

/** Typed reply from the native integration. No reply grants, revokes or starts anything by itself. */
sealed interface PhoneTaskEventReply {
    /** Unverified model progress was recorded; the task state did not change. */
    data class Recorded(val seq: Long) : PhoneTaskEventReply

    /** Native halt/revoke was confirmed, then the task became waiting for the person. */
    data class WaitingForUser(
        val seq: Long,
        val stop: PhoneStopAcknowledgement,
    ) : PhoneTaskEventReply

    /** Native halt/revoke was confirmed, then the task became ended pending verification. */
    data class EndedPendingCheck(
        val seq: Long,
        val stop: PhoneStopAcknowledgement,
    ) : PhoneTaskEventReply

    /** The native halt was requested but is not confirmed. Waiting/done must not be shown yet. */
    data class HaltUnconfirmed(val seq: Long? = null) : PhoneTaskEventReply

    data class Refused(val code: PhoneTaskEventError) : PhoneTaskEventReply
}

enum class PhoneTaskEventError {
    UNAVAILABLE,
    NO_TASK,
    STALE_TASK,
    EPOCH_CHANGED,
    SESSION_MISMATCH,
    DUPLICATE,
    CAPACITY,
    INVALID,
    TASK_NOT_ACTIVE,
}

/**
 * Native integration seam for model task events. Implementations use their [PhoneProgressStore]
 * and native halt/revoke confirmation; the tool server only sees this interface.
 *
 * The default [Unavailable] fails closed. It does not fake a revoked flag or access PhoneIntegration.
 */
interface PhoneTaskEventHandler {
    /** False when no native task-event bridge is installed. The tool then fails closed. */
    val available: Boolean get() = true

    /** Current native task for [epoch], or null. Read-only; no authority is created. */
    fun currentTask(epoch: Long): PhoneTaskKey?

    /**
     * Handles one already route/epoch-admitted model event with a typed reply. The implementation
     * must verify [epoch] is still the native current epoch and return `EPOCH_CHANGED` /
     * `STALE_TASK` for a stale event; it must never adopt a newer epoch.
     */
    suspend fun handle(
        key: PhoneTaskKey,
        epoch: Long,
        sessionLabel: String,
        event: PhoneTaskEvent,
    ): PhoneTaskEventReply

    /**
     * Native-only, called by the tool server after the router accepted this session for [epoch].
     * It exists so a manual (native-published, first-action) route can bind an initially unbound
     * task. The handler binds only if its store's route is still null, synchronously; [sessionLabel]
     * comes from the verified V2 context/router claim, never from a model phase message. It must use
     * [admittedEpoch], never a newer epoch. Default is a no-op.
     */
    fun onRouteClaimed(
        key: PhoneTaskKey,
        admittedEpoch: Long,
        sessionLabel: String,
    ) = Unit

    /**
     * Native-only, called by the tool server after a real `PhoneController.execute` returns. The
     * model cannot call this and cannot supply an outcome; [outcome] is sanitized. The handler must
     * use the admitted [admittedEpoch] (never a newer one); the store rejects a stale key/epoch.
     * The default is a no-op, so existing handlers remain compatible.
     */
    fun onNativeCallCompleted(
        key: PhoneTaskKey,
        admittedEpoch: Long,
        requestId: String,
        outcome: PhoneNativeCallOutcome,
    ) = Unit

    object Unavailable : PhoneTaskEventHandler {
        override val available: Boolean = false

        override fun currentTask(epoch: Long): PhoneTaskKey? = null

        override suspend fun handle(
            key: PhoneTaskKey,
            epoch: Long,
            sessionLabel: String,
            event: PhoneTaskEvent,
        ): PhoneTaskEventReply = PhoneTaskEventReply.Refused(PhoneTaskEventError.UNAVAILABLE)
    }
}
