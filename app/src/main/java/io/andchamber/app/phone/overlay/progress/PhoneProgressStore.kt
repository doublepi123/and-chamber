package io.andchamber.app.phone.overlay.progress

import java.util.ArrayDeque

/**
 * Pure, bounded, single-task progress state for the native overlay facade.
 *
 * Native integration creates the task after the person confirmed a summary/target, updates the
 * grant epoch/route, and projects native observations, delivery and stop/end facts. The model can
 * only append unverified [PhoneProgressSource.MODEL] narration. The store never invents an event:
 * an empty task has no progress and no synthetic percentage.
 *
 * Every accepted event id is remembered for the lifetime of the store, across tasks and epochs.
 * When that table is full a new event is refused; old records are never evicted, so an already
 * handled event cannot be replayed by capacity pressure. The recent-event ring is separate and
 * keeps only the newest [eventCapacity] entries.
 *
 * This class is not an authority and has no Android dependency. It does not grant, revoke, read the
 * device or publish a route.
 */
class PhoneProgressStore(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val eventCapacity: Int = DEFAULT_EVENT_CAPACITY,
    private val eventIdCapacity: Int = DEFAULT_EVENT_ID_CAPACITY,
) {
    init {
        require(eventCapacity in 1..MAX_EVENT_CAPACITY) { "eventCapacity" }
        require(eventIdCapacity in 1..MAX_EVENT_ID_CAPACITY) { "eventIdCapacity" }
    }

    private val lock = Any()
    private var sequence = 0L
    private var lastTimestamp = Long.MIN_VALUE
    private var task: TaskRecord? = null
    /** Every task key this store has owned. Reusing one would resurrect an old owner. */
    private val seenKeys = LinkedHashSet<PhoneTaskKey>()
    private val recent = ArrayDeque<PhoneProgressEvent>()
    private val eventIds = LinkedHashSet<String>()

    /** Current native task for [epoch], or null. Read-only; never changes state. */
    fun currentTask(epoch: Long): PhoneTaskKey? =
        synchronized(lock) { task?.takeIf { it.grantEpoch == epoch }?.key }

    /** Last assigned event sequence (0 before the first event), for the native handler's reply. */
    fun lastSeq(): Long = synchronized(lock) { sequence }

    /** Latest [limit] events (default 3, clamped below [eventCapacity]) with native-only facts. */
    fun snapshot(limit: Int = DISPLAY_EVENTS): PhoneTaskSnapshot? =
        synchronized(lock) {
            val record = task ?: return null
            val count = limit.coerceIn(1, eventCapacity)
            PhoneTaskSnapshot(
                key = record.key,
                grantEpoch = record.grantEpoch,
                routeSession = record.routeSession,
                summary = record.summary,
                packageName = record.packageName,
                state = record.state,
                startedAtMs = record.startedAtMs,
                updatedAtMs = record.updatedAtMs,
                events = recent.toList().takeLast(count),
                stopAck = record.stopAck,
                finishReported = record.finishReported,
                business = record.business,
            )
        }

    /**
     * Native-only. Creates the one task after the person confirmed [summary] and [packageName].
     * A key already current or just ended is refused: a late callback cannot resurrect a task. A
     * different key is a new native task and replaces the old projection.
     */
    fun startTask(
        eventId: String,
        key: PhoneTaskKey,
        epoch: Long,
        routeSession: String?,
        summary: String,
        packageName: String,
    ): PhoneProgressApply =
        synchronized(lock) {
            eventRefusal(eventId)?.let { return it }
            if (epoch < 0 ||
                (routeSession != null && !validSession(routeSession)) ||
                !validFact(summary, MAX_FACT_CHARS) ||
                !validFact(packageName, MAX_FACT_CHARS)
            ) {
                return PhoneProgressApply.INVALID
            }
            if (key in seenKeys) return PhoneProgressApply.STALE_TASK
            if (seenKeys.size >= MAX_SEEN_KEYS) return PhoneProgressApply.CAPACITY
            val at = now()
            val record = TaskRecord(
                key = key,
                grantEpoch = epoch,
                routeSession = routeSession,
                summary = summary,
                packageName = packageName,
                state = PhoneTaskState.ACTIVE,
                startedAtMs = at,
                updatedAtMs = at,
            )
            task = record
            seenKeys.add(key)
            recent.clear()
            accept(eventId) {
                append(
                    record = record,
                    source = PhoneProgressSource.NATIVE,
                    kind = PhoneProgressKind.TASK_STARTED,
                    reason = PhoneProgressReason.USER_CONFIRMED,
                    text = null,
                    atMs = at,
                )
            }
        }

    /**
     * Native-only. A same epoch only binds an unbound manual route (no event, no state change). A
     * strictly newer epoch is native proof that a new grant exists; it restores ACTIVE, clears the
     * old stop ack and the current-grant business result, keeps the event history, and appends a
     * GRANT_UPDATED fact. An ENDED task is never resurrected; use a new task key.
     */
    fun updateEpoch(
        eventId: String,
        key: PhoneTaskKey,
        epoch: Long,
        routeSession: String?,
    ): PhoneProgressApply =
        synchronized(lock) {
            eventRefusal(eventId)?.let { return it }
            val record = task ?: return PhoneProgressApply.NO_TASK
            if (record.key != key) return PhoneProgressApply.STALE_TASK
            if (record.state == PhoneTaskState.ENDED_PENDING_CHECK) {
                return PhoneProgressApply.TASK_NOT_ACTIVE
            }
            if (epoch < record.grantEpoch) return PhoneProgressApply.EPOCH_CHANGED
            if (routeSession != null && !validSession(routeSession)) return PhoneProgressApply.INVALID
            if (epoch == record.grantEpoch && record.routeSession != null &&
                routeSession != record.routeSession
            ) {
                return PhoneProgressApply.SESSION_MISMATCH
            }
            if (epoch == record.grantEpoch) {
                // Same epoch: only fill an unbound manual route. Never restore ACTIVE, clear a
                // stop/unknown/waiting state, replace a bound owner, or add a progress event.
                if (record.routeSession == null && routeSession != null) {
                    record.routeSession = routeSession
                }
                return accept(eventId) { }
            }
            // A strictly newer epoch is native proof that a new grant exists after a halt or
            // unknown outcome. Only this native path restores real control; the tool cannot call
            // updateEpoch. Keep the event history, but the snapshot's current-grant business result
            // is cleared so old verified facts are not misreported as the new grant's completion.
            record.grantEpoch = epoch
            record.routeSession = routeSession
            record.state = PhoneTaskState.ACTIVE
            record.stopAck = null
            record.business = null
            record.finishReported = false
            accept(eventId) {
                append(
                    record,
                    PhoneProgressSource.NATIVE,
                    PhoneProgressKind.TASK_EPOCH_UPDATED,
                    PhoneProgressReason.GRANT_UPDATED,
                    null,
                )
            }
        }

    /** Native-only metadata: a bounded observation happened. No nodes, labels, refs or pixels. */
    fun recordNativeObserved(
        eventId: String,
        key: PhoneTaskKey,
        epoch: Long,
    ): PhoneProgressApply =
        synchronized(lock) {
            eventRefusal(eventId)?.let { return it }
            requireTask(key, epoch, null) { record ->
                accept(eventId) {
                    append(
                        record,
                        PhoneProgressSource.NATIVE,
                        PhoneProgressKind.OBSERVED,
                        PhoneProgressReason.OBSERVED,
                        null,
                    )
                }
            }
        }

    /** Native-only delivery fact. SENT/NOT_SENT/UNKNOWN never imply business success. */
    fun recordNativeMutation(
        eventId: String,
        key: PhoneTaskKey,
        epoch: Long,
        delivery: PhoneMutationDelivery,
    ): PhoneProgressApply =
        synchronized(lock) {
            eventRefusal(eventId)?.let { return it }
            requireTask(key, epoch, null) { record ->
                val kind = when (delivery) {
                    PhoneMutationDelivery.SENT -> PhoneProgressKind.MUTATION_SENT
                    PhoneMutationDelivery.NOT_SENT -> PhoneProgressKind.MUTATION_NOT_SENT
                    PhoneMutationDelivery.UNKNOWN -> PhoneProgressKind.MUTATION_UNKNOWN
                }
                val reason = when (delivery) {
                    PhoneMutationDelivery.SENT -> PhoneProgressReason.DELIVERY_SENT
                    PhoneMutationDelivery.NOT_SENT -> PhoneProgressReason.DELIVERY_NOT_SENT
                    PhoneMutationDelivery.UNKNOWN -> PhoneProgressReason.DELIVERY_UNKNOWN
                }
                accept(eventId) {
                    append(record, PhoneProgressSource.NATIVE, kind, reason, null, delivery = delivery)
                    if (delivery == PhoneMutationDelivery.UNKNOWN &&
                        (record.state == PhoneTaskState.ACTIVE ||
                            record.state == PhoneTaskState.UNKNOWN_OUTCOME)
                    ) {
                        record.state = PhoneTaskState.UNKNOWN_OUTCOME
                    }
                }
            }
        }

    /** Native-only verified business result, independent from delivery. */
    fun recordNativeBusiness(
        eventId: String,
        key: PhoneTaskKey,
        epoch: Long,
        result: PhoneBusinessResult,
    ): PhoneProgressApply =
        synchronized(lock) {
            eventRefusal(eventId)?.let { return it }
            requireTask(key, epoch, null) { record ->
                accept(eventId) {
                    append(
                        record,
                        PhoneProgressSource.NATIVE,
                        PhoneProgressKind.BUSINESS_RESULT,
                        if (result == PhoneBusinessResult.VERIFIED_DONE) {
                            PhoneProgressReason.VERIFIED_DONE
                        } else {
                            PhoneProgressReason.VERIFIED_NOT_DONE
                        },
                        null,
                        business = result,
                    )
                    record.business = result
                }
            }
        }

    /** Native-only. A stop/handoff request was sent; confirmation is still pending. */
    fun recordNativeStopRequested(
        eventId: String,
        key: PhoneTaskKey,
        epoch: Long,
        reason: PhoneProgressReason,
    ): PhoneProgressApply =
        synchronized(lock) {
            eventRefusal(eventId)?.let { return it }
            requireTask(key, epoch, null) { record ->
                if (record.state == PhoneTaskState.ENDED_PENDING_CHECK) {
                    return PhoneProgressApply.TASK_NOT_ACTIVE
                }
                if (reason !in STOP_REASONS) return PhoneProgressApply.INVALID
                record.state = PhoneTaskState.STOPPING
                accept(eventId) {
                    append(record, PhoneProgressSource.NATIVE, PhoneProgressKind.STOP_REQUESTED, reason, null)
                }
            }
        }

    /**
     * Native-only halt confirmation. It only says new operations/late reads are blocked; it never
     * claims sent actions were rolled back. The task is not ended here.
     */
    fun recordNativeStopped(
        eventId: String,
        key: PhoneTaskKey,
        epoch: Long,
        ack: PhoneStopAcknowledgement,
        reason: PhoneProgressReason = PhoneProgressReason.USER_REQUESTED,
    ): PhoneProgressApply =
        synchronized(lock) {
            eventRefusal(eventId)?.let { return it }
            requireTask(key, epoch, null) { record ->
                if (!ack.confirmed) return PhoneProgressApply.STOP_UNCONFIRMED
                if (record.state == PhoneTaskState.ENDED_PENDING_CHECK) {
                    return PhoneProgressApply.TASK_NOT_ACTIVE
                }
                if (reason !in STOP_REASONS) return PhoneProgressApply.INVALID
                record.stopAck = ack
                if (record.state == PhoneTaskState.ACTIVE ||
                    record.state == PhoneTaskState.UNKNOWN_OUTCOME
                ) {
                    record.state = PhoneTaskState.STOPPING
                }
                accept(eventId) {
                    append(
                        record,
                        PhoneProgressSource.NATIVE,
                        PhoneProgressKind.STOP_ACKNOWLEDGED,
                        reason,
                        null,
                    )
                }
            }
        }

    /** Native-only end projection. The result stays pending verification, never a success. */
    fun recordNativeEnded(
        eventId: String,
        key: PhoneTaskKey,
        epoch: Long,
        ack: PhoneStopAcknowledgement,
        reason: PhoneProgressReason,
    ): PhoneProgressApply =
        synchronized(lock) {
            eventRefusal(eventId)?.let { return it }
            requireTask(key, epoch, null) { record ->
                if (!ack.confirmed) return PhoneProgressApply.STOP_UNCONFIRMED
                if (record.state == PhoneTaskState.ENDED_PENDING_CHECK) {
                    return PhoneProgressApply.TASK_NOT_ACTIVE
                }
                if (reason !in STOP_REASONS) return PhoneProgressApply.INVALID
                record.state = PhoneTaskState.ENDED_PENDING_CHECK
                record.stopAck = ack
                accept(eventId) {
                    append(record, PhoneProgressSource.NATIVE, PhoneProgressKind.TASK_ENDED, reason, null)
                }
            }
        }

    /** Model self-report, bounded and always unverified. It cannot change native facts or state. */
    fun recordModelProgress(
        eventId: String,
        key: PhoneTaskKey,
        epoch: Long,
        sessionLabel: String,
        phase: String,
    ): PhoneProgressApply =
        synchronized(lock) {
            eventRefusal(eventId)?.let { return it }
            requireTask(key, epoch, sessionLabel) { record ->
                if (record.state != PhoneTaskState.ACTIVE &&
                    record.state != PhoneTaskState.UNKNOWN_OUTCOME
                ) {
                    return PhoneProgressApply.TASK_NOT_ACTIVE
                }
                if (!validText(phase)) return PhoneProgressApply.INVALID
                accept(eventId) {
                    append(
                        record,
                        PhoneProgressSource.MODEL,
                        PhoneProgressKind.MODEL_PROGRESS,
                        PhoneProgressReason.MODEL_REPORTED,
                        phase,
                    )
                }
            }
        }

    /**
     * Model request for the person. It is only accepted after a complete native stop
     * acknowledgement; otherwise nothing is recorded and the task does not become waiting.
     */
    fun recordModelNeedUser(
        eventId: String,
        key: PhoneTaskKey,
        epoch: Long,
        sessionLabel: String,
        reason: String,
        ack: PhoneStopAcknowledgement,
    ): PhoneProgressApply =
        synchronized(lock) {
            eventRefusal(eventId)?.let { return it }
            requireTask(key, epoch, sessionLabel) { record ->
                if (!ack.confirmed) return PhoneProgressApply.STOP_UNCONFIRMED
                if (record.state !in MODEL_REPORTING_STATES) return PhoneProgressApply.TASK_NOT_ACTIVE
                if (!validText(reason)) return PhoneProgressApply.INVALID
                record.state = PhoneTaskState.WAITING_USER
                record.stopAck = ack
                accept(eventId) {
                    append(
                        record,
                        PhoneProgressSource.MODEL,
                        PhoneProgressKind.MODEL_NEED_USER,
                        PhoneProgressReason.NEED_USER,
                        reason,
                    )
                }
            }
        }

    /**
     * Model end report. Only after a complete native stop acknowledgement. It marks the task ended
     * pending verification; it never stores a business success and cannot overwrite a native result.
     */
    fun recordModelFinish(
        eventId: String,
        key: PhoneTaskKey,
        epoch: Long,
        sessionLabel: String,
        summary: String,
        ack: PhoneStopAcknowledgement,
    ): PhoneProgressApply =
        synchronized(lock) {
            eventRefusal(eventId)?.let { return it }
            requireTask(key, epoch, sessionLabel) { record ->
                if (!ack.confirmed) return PhoneProgressApply.STOP_UNCONFIRMED
                if (record.state !in MODEL_REPORTING_STATES &&
                    record.state != PhoneTaskState.WAITING_USER
                ) {
                    return PhoneProgressApply.TASK_NOT_ACTIVE
                }
                if (!validText(summary)) return PhoneProgressApply.INVALID
                record.state = PhoneTaskState.ENDED_PENDING_CHECK
                record.finishReported = true
                record.stopAck = ack
                accept(eventId) {
                    append(
                        record,
                        PhoneProgressSource.MODEL,
                        PhoneProgressKind.MODEL_FINISH,
                        PhoneProgressReason.MODEL_REPORTED,
                        summary,
                    )
                }
            }
        }

    private fun eventRefusal(eventId: String): PhoneProgressApply? {
        if (!validEventId(eventId)) return PhoneProgressApply.INVALID
        if (eventId in eventIds) return PhoneProgressApply.DUPLICATE
        if (eventIds.size >= eventIdCapacity) return PhoneProgressApply.CAPACITY
        return null
    }

    /** Must run under [lock]. Returns a typed refusal instead of guessing a task. */
    private inline fun requireTask(
        key: PhoneTaskKey,
        epoch: Long,
        sessionLabel: String?,
        block: (TaskRecord) -> PhoneProgressApply,
    ): PhoneProgressApply {
        if (epoch < 0) return PhoneProgressApply.INVALID
        val record = task ?: return PhoneProgressApply.NO_TASK
        if (record.key != key) return PhoneProgressApply.STALE_TASK
        if (record.grantEpoch != epoch) return PhoneProgressApply.EPOCH_CHANGED
        if (sessionLabel != null && record.routeSession != null &&
            record.routeSession != sessionLabel
        ) {
            return PhoneProgressApply.SESSION_MISMATCH
        }
        return block(record)
    }

    private inline fun accept(
        eventId: String,
        block: () -> Unit,
    ): PhoneProgressApply {
        block()
        eventIds.add(eventId)
        return PhoneProgressApply.ACCEPTED
    }

    private fun append(
        record: TaskRecord,
        source: PhoneProgressSource,
        kind: PhoneProgressKind,
        reason: PhoneProgressReason,
        text: String?,
        delivery: PhoneMutationDelivery? = null,
        business: PhoneBusinessResult? = null,
        atMs: Long = now(),
    ) {
        sequence += 1
        val at = maxOf(atMs, record.updatedAtMs)
        record.updatedAtMs = at
        recent.addLast(
            PhoneProgressEvent(
                seq = sequence,
                task = record.key,
                grantEpoch = record.grantEpoch,
                source = source,
                kind = kind,
                reason = reason,
                text = text,
                delivery = delivery,
                business = business,
                atMs = at,
            ),
        )
        while (recent.size > eventCapacity) recent.removeFirst()
    }

    private fun now(): Long {
        val now = clock()
        val at = maxOf(now, lastTimestamp)
        lastTimestamp = at
        return at
    }

    private fun validEventId(value: String): Boolean =
        value.length in 1..MAX_EVENT_ID_CHARS && value.none(Char::isISOControl)

    private fun validSession(value: String): Boolean =
        value.length in 1..MAX_SESSION_CHARS && value.none(Char::isISOControl)

    private fun validFact(
        value: String,
        max: Int,
    ): Boolean = value.isNotBlank() && value.length <= max && value.none(Char::isISOControl)

    private fun validText(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_MODEL_TEXT_CHARS && value.none(Char::isISOControl)

    private data class TaskRecord(
        val key: PhoneTaskKey,
        var grantEpoch: Long,
        var routeSession: String?,
        val summary: String,
        val packageName: String,
        var state: PhoneTaskState,
        val startedAtMs: Long,
        var updatedAtMs: Long,
        var stopAck: PhoneStopAcknowledgement? = null,
        var finishReported: Boolean = false,
        var business: PhoneBusinessResult? = null,
    )

    companion object {
        const val DEFAULT_EVENT_CAPACITY = 32
        const val MAX_EVENT_CAPACITY = 64
        const val DISPLAY_EVENTS = 3
        const val MAX_EVENT_ID_CHARS = 128
        const val MAX_SESSION_CHARS = 128
        const val MAX_FACT_CHARS = 200
        const val MAX_MODEL_TEXT_CHARS = 120
        const val DEFAULT_EVENT_ID_CAPACITY = 256
        const val MAX_EVENT_ID_CAPACITY = 4096
        const val MAX_SEEN_KEYS = 64

        private val STOP_REASONS = setOf(
            PhoneProgressReason.USER_REQUESTED,
            PhoneProgressReason.TAKEOVER,
            PhoneProgressReason.LEASE_EXPIRED,
            PhoneProgressReason.HOST_RETURNED,
            PhoneProgressReason.CONNECTION_LOST,
            PhoneProgressReason.DEVICE_LOCKED,
            PhoneProgressReason.RUNTIME_CHANGED,
            PhoneProgressReason.OUTCOME_UNKNOWN,
        )

        private val MODEL_REPORTING_STATES = setOf(
            PhoneTaskState.ACTIVE,
            PhoneTaskState.UNKNOWN_OUTCOME,
            PhoneTaskState.STOPPING,
        )
    }
}
