package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.android.PhoneOverlayTaskBinding
import io.andchamber.app.phone.overlay.progress.PhoneMutationDelivery
import io.andchamber.app.phone.overlay.progress.PhoneNativeCallOutcome
import io.andchamber.app.phone.overlay.progress.PhoneProgressApply
import io.andchamber.app.phone.overlay.progress.PhoneProgressReason
import io.andchamber.app.phone.overlay.progress.PhoneProgressStore
import io.andchamber.app.phone.overlay.progress.PhoneStopAcknowledgement
import io.andchamber.app.phone.overlay.progress.PhoneTaskEvent
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventError
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventReply
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.overlay.progress.PhoneTaskSnapshot
import io.andchamber.app.phone.overlay.progress.PhoneTaskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Native-only projection for the overlay panel. [sentCount] counts accepted native SENT facts. */
internal data class PhoneTaskProgressView(
    val snapshot: PhoneTaskSnapshot,
    val sentCount: Int?,
)

/**
 * What a native stop should do. [TAKEOVER] revokes the lease for a hand-over (need_user); [END]
 * additionally ends the retained task with a native needs-check record (model finish / person end).
 */
internal enum class PhoneTaskStopIntent {
    TAKEOVER,
    END,
}

/**
 * Stops exactly one native task owner. Main thread only. Returns null when that owner/epoch is not
 * the live one, so a queued old event can never revoke a newer grant.
 */
internal fun interface PhoneTaskStopPort {
    fun stopExact(
        binding: PhoneOverlayTaskBinding,
        intent: PhoneTaskStopIntent,
    ): PhoneStopAcknowledgement?
}

/**
 * Native integration → task adapter sink. Both methods are called by [PhoneIntegration] on the main
 * thread (the tool lane reaches the adapter through [PhoneTaskEventHandler] instead).
 *
 * [onTaskBinding] is the synchronous native-confirmed task owner: the integration publishes it before
 * the tool route becomes claimable, so `currentTask` never depends on an asynchronous state collector.
 * [onNativeLeaseDropped] reports a lease that was really dropped for exactly this task/epoch.
 *
 * Neither method grants, resumes, reads the device or restores a lease. The default [Unavailable]
 * ignores everything.
 */
internal interface PhoneTaskNativeFacts {
    fun onTaskBinding(binding: PhoneOverlayTaskBinding?)

    fun onNativeLeaseDropped(
        taskId: String,
        runtimeId: String,
        epoch: Long,
        reason: PhoneProgressReason,
        ended: Boolean,
        ack: PhoneStopAcknowledgement,
    ): Boolean

    object Unavailable : PhoneTaskNativeFacts {
        override fun onTaskBinding(binding: PhoneOverlayTaskBinding?) = Unit

        override fun onNativeLeaseDropped(
            taskId: String,
            runtimeId: String,
            epoch: Long,
            reason: PhoneProgressReason,
            ended: Boolean,
            ack: PhoneStopAcknowledgement,
        ): Boolean = false
    }
}

/**
 * The production task adapter: native-confirmed binding, native stop receipts, sanitized result
 * facts, and the model-event gate.
 *
 * [onTaskBinding]/[onNativeLeaseDropped]/[handleOnMain] are main-thread calls; [currentTask],
 * [onRouteClaimed] and [onNativeCallCompleted] are also safe from the tool server's HTTP threads:
 * they only take this adapter's lock and the pure progress store. The model can only add bounded
 * unverified narration; delivery and business facts come only from the native callbacks. There is
 * no synthetic percentage, wall-clock deadline or inferred delivery.
 */
internal class PhoneTaskProgressAdapter(
    private val store: PhoneProgressStore,
    private val stop: PhoneTaskStopPort,
) {
    private val lock = Any()
    private var bound: PhoneOverlayTaskBinding? = null
    private var sentCount: Int? = null

    /** The epoch whose native stop receipt was already recorded; null after a resume. */
    private var stoppedEpoch: Long? = null

    /** A binding the native side discarded. A late publish for the same key cannot resurrect it. */
    private var abandonedKey: PhoneTaskKey? = null

    private val mutableProgress = MutableStateFlow<PhoneTaskProgressView?>(null)
    val progress: StateFlow<PhoneTaskProgressView?> = mutableProgress.asStateFlow()

    /** Current native task for the frozen [epoch], or null. Never creates or changes anything. */
    fun currentTask(epoch: Long): PhoneTaskKey? =
        synchronized(lock) { bound?.takeIf { it.epoch == epoch }?.key() }

    /** Exact bound native binding for [key] and [epoch], or null. Thread-safe read-only snapshot. */
    fun bindingFor(
        key: PhoneTaskKey,
        epoch: Long,
    ): PhoneOverlayTaskBinding? =
        synchronized(lock) {
            bound?.takeIf { it.key() == key && it.epoch == epoch }
        }

    /**
     * Read-only retained state from the same bound binding and progress snapshot the overlay
     * shows. It is null unless the bound task and the current store snapshot are the same key, so
     * an abandoned binding, a different newer owner, or a stale store snapshot cannot leak an old
     * state. This is a display fact only: it never makes the task live and grants nothing.
     */
    fun retainedTaskState(): PhoneTaskState? =
        synchronized(lock) {
            val binding = bound ?: return null
            val snapshot = store.snapshot() ?: return null
            if (snapshot.key != binding.key()) return null
            snapshot.state
        }

    fun snapshot(): PhoneTaskSnapshot? = progress.value?.snapshot

    /**
     * Main thread. The native-confirmed task binding (or null when the native side discarded it).
     * A resume keeps the same task and its events; a new task replaces the projection. A late binding
     * for an old key can never resurrect it, because the store remembers every task key it owned.
     */
    fun onTaskBinding(binding: PhoneOverlayTaskBinding?) {
        synchronized(lock) {
            if (binding == null) {
                abandonedKey = bound?.key() ?: store.snapshot()?.key
                bound = null
                stoppedEpoch = null
                publishLocked()
                return
            }
            val key = binding.key()
            // The native side abandoned this owner (failed grant / dismissed result). A queued late
            // publish for the same key must not bring it back; only a different task id may.
            if (key == abandonedKey) {
                publishLocked()
                return
            }
            abandonedKey = null
            val current = store.snapshot()
            val apply =
                when {
                    current == null -> {
                        sentCount = null
                        store.startTask(
                            eventId = "bind-${key.taskId}-${binding.epoch}",
                            key = key,
                            epoch = binding.epoch,
                            routeSession = binding.routeSession,
                            summary = binding.summary,
                            packageName = binding.packageName,
                        )
                    }
                    current.key == key -> {
                        when {
                            binding.epoch < current.grantEpoch -> PhoneProgressApply.EPOCH_CHANGED
                            binding.epoch == current.grantEpoch &&
                                binding.routeSession == current.routeSession -> PhoneProgressApply.ACCEPTED
                            else ->
                                store.updateEpoch(
                                    eventId = "epoch-${key.taskId}-${binding.epoch}",
                                    key = key,
                                    epoch = binding.epoch,
                                    routeSession = binding.routeSession,
                                )
                        }
                    }
                    else -> {
                        // A different native-confirmed task replaces the projection.
                        sentCount = null
                        store.startTask(
                            eventId = "bind-${key.taskId}-${binding.epoch}",
                            key = key,
                            epoch = binding.epoch,
                            routeSession = binding.routeSession,
                            summary = binding.summary,
                            packageName = binding.packageName,
                        )
                    }
                }
            if (apply == PhoneProgressApply.ACCEPTED) bound = binding
            // A resume is a new epoch of the same task: the old stop receipt no longer proves that
            // the new lease was stopped. A re-publish of the stopped epoch keeps it.
            if (binding.epoch != stoppedEpoch) stoppedEpoch = null
            publishLocked()
        }
    }

    /**
     * Main thread. A native lease for exactly [taskId]/[runtimeId]/[epoch] was dropped and the
     * controller revoked it synchronously. The ack only says new operations and late reads are
     * blocked; sent actions are never claimed as rolled back.
     */
    fun onNativeLeaseDropped(
        taskId: String,
        runtimeId: String,
        epoch: Long,
        reason: PhoneProgressReason,
        ended: Boolean,
        ack: PhoneStopAcknowledgement,
    ): Boolean =
        synchronized(lock) {
            val current = bound ?: return false
            if (!current.matches(taskId, runtimeId, epoch)) return false
            if (!ack.confirmed) return false
            recordStopLocked(current, ack, reason, ended)
        }

    /**
     * Tool lane, HTTP thread: called synchronously right after the router accepted a first manual
     * claim. Only the store and this adapter's state are touched under [lock] (both thread-safe, no
     * Android/native field); the returned binding is handed to the coordinator, which updates the
     * main-only routing memory. A wrong key/epoch or an already bound different session is refused.
     */
    fun onRouteClaimed(
        key: PhoneTaskKey,
        admittedEpoch: Long,
        sessionLabel: String,
    ): PhoneOverlayTaskBinding? =
        synchronized(lock) {
            val current = bound ?: return null
            if (current.key() != key || current.epoch != admittedEpoch) return null
            val snapshot = store.snapshot() ?: return null
            if (snapshot.key != key || snapshot.grantEpoch != admittedEpoch) return null
            // Bind only a still-unbound manual route; a repeat or a pinned route changes nothing.
            if (snapshot.routeSession != null) return null
            val apply =
                store.updateEpoch(
                    eventId = "route-${key.taskId}-$admittedEpoch",
                    key = key,
                    epoch = admittedEpoch,
                    routeSession = sessionLabel,
                )
            if (apply != PhoneProgressApply.ACCEPTED && apply != PhoneProgressApply.DUPLICATE) {
                return null
            }
            val adopted = current.copy(routeSession = sessionLabel)
            bound = adopted
            publishLocked()
            adopted
        }

    /**
     * Tool lane, HTTP thread: the sanitized actual result of one admitted controller call. Only the
     * store and the adapter's count are touched under [lock]. The same request id is recorded once;
     * a refused call records nothing; only an accepted SENT mutation increases the sent count.
     */
    fun onNativeCallCompleted(
        key: PhoneTaskKey,
        admittedEpoch: Long,
        requestId: String,
        outcome: PhoneNativeCallOutcome,
    ): Boolean =
        synchronized(lock) {
            val current = bound ?: return false
            if (current.key() != key || current.epoch != admittedEpoch) return false
            val target = current.key()
            val apply =
                when (outcome) {
                    is PhoneNativeCallOutcome.Observed ->
                        store.recordNativeObserved(requestId, target, admittedEpoch)
                    is PhoneNativeCallOutcome.Mutation ->
                        store.recordNativeMutation(requestId, target, admittedEpoch, outcome.delivery)
                    PhoneNativeCallOutcome.Refused -> return false
                }
            if (apply == PhoneProgressApply.ACCEPTED) {
                if (outcome is PhoneNativeCallOutcome.Mutation) {
                    val previous = sentCount
                    sentCount =
                        when (outcome.delivery) {
                            PhoneMutationDelivery.SENT -> (previous ?: 0) + 1
                            else -> previous ?: 0
                        }
                }
                // Every accepted native fact must reach the overlay flow (including an observation,
                // which changes updatedAtMs/events but not the delivery count).
                publishLocked()
            }
            apply == PhoneProgressApply.ACCEPTED || apply == PhoneProgressApply.DUPLICATE
        }

    /**
     * Main thread. One model event that the tool server already admitted for the frozen epoch and
     * route. Need_user/finish first stop exactly this native owner; without a confirmed stop they are
     * not recorded and the reply is [PhoneTaskEventReply.HaltUnconfirmed]. The model never ends a
     * task on its own and never claims a business result.
     */
    fun handleOnMain(
        key: PhoneTaskKey,
        epoch: Long,
        sessionLabel: String,
        event: PhoneTaskEvent,
    ): PhoneTaskEventReply =
        synchronized(lock) {
            val current = bound ?: return PhoneTaskEventReply.Refused(PhoneTaskEventError.NO_TASK)
            if (current.key() != key) return PhoneTaskEventReply.Refused(PhoneTaskEventError.STALE_TASK)
            if (current.epoch != epoch) return PhoneTaskEventReply.Refused(PhoneTaskEventError.EPOCH_CHANGED)
            val snapshot = store.snapshot() ?: return PhoneTaskEventReply.Refused(PhoneTaskEventError.NO_TASK)
            if (snapshot.key != key || snapshot.grantEpoch != epoch) {
                return PhoneTaskEventReply.Refused(PhoneTaskEventError.EPOCH_CHANGED)
            }
            if (snapshot.routeSession != null && snapshot.routeSession != sessionLabel) {
                return PhoneTaskEventReply.Refused(PhoneTaskEventError.SESSION_MISMATCH)
            }
            when (event) {
                is PhoneTaskEvent.Progress -> {
                    val apply = store.recordModelProgress(event.eventId, key, epoch, sessionLabel, event.phase)
                    if (apply == PhoneProgressApply.ACCEPTED) publishLocked()
                    reply(apply)
                }
                is PhoneTaskEvent.NeedUser -> {
                    val ack = confirmedStopLocked(current, snapshot, PhoneTaskStopIntent.TAKEOVER)
                        ?: return PhoneTaskEventReply.HaltUnconfirmed()
                    val apply =
                        store.recordModelNeedUser(event.eventId, key, epoch, sessionLabel, event.reason, ack)
                    if (apply == PhoneProgressApply.ACCEPTED) {
                        publishLocked()
                        PhoneTaskEventReply.WaitingForUser(store.lastSeq(), ack)
                    } else {
                        reply(apply)
                    }
                }
                is PhoneTaskEvent.Finish -> {
                    // A model finish must really end the native task (persist the needs-check record),
                    // not merely take the lease over.
                    val ack = confirmedStopLocked(current, snapshot, PhoneTaskStopIntent.END)
                        ?: return PhoneTaskEventReply.HaltUnconfirmed()
                    val apply =
                        store.recordModelFinish(event.eventId, key, epoch, sessionLabel, event.summary, ack)
                    if (apply == PhoneProgressApply.ACCEPTED) {
                        publishLocked()
                        PhoneTaskEventReply.EndedPendingCheck(store.lastSeq(), ack)
                    } else {
                        reply(apply)
                    }
                }
            }
        }

    private fun recordStopLocked(
        binding: PhoneOverlayTaskBinding,
        ack: PhoneStopAcknowledgement,
        reason: PhoneProgressReason,
        ended: Boolean,
    ): Boolean {
        val key = binding.key()
        val epoch = binding.epoch
        val requested =
            store.recordNativeStopRequested("drop-${key.taskId}-$epoch", key, epoch, reason)
        val stopped =
            store.recordNativeStopped("dropack-${key.taskId}-$epoch", key, epoch, ack, reason)
        if (ended) {
            store.recordNativeEnded("end-${key.taskId}-$epoch", key, epoch, ack, reason)
        }
        if (acceptedOrDuplicate(stopped)) stoppedEpoch = epoch
        publishLocked()
        return acceptedOrDuplicate(requested) || acceptedOrDuplicate(stopped)
    }

    /**
     * Stops the exact owner. A repeated takeover reuses its own receipt; an END always asks the
     * native port because the native needs-check record must be persisted even when the lease is
     * already gone. A resume starts a new epoch and therefore always asks again.
     */
    private fun confirmedStopLocked(
        binding: PhoneOverlayTaskBinding,
        snapshot: PhoneTaskSnapshot,
        intent: PhoneTaskStopIntent,
    ): PhoneStopAcknowledgement? {
        if (intent == PhoneTaskStopIntent.TAKEOVER) {
            val existing = snapshot.stopAck
            if (stoppedEpoch == binding.epoch && existing?.confirmed == true) return existing
        }
        val fresh = stop.stopExact(binding, intent) ?: return null
        if (intent == PhoneTaskStopIntent.TAKEOVER) {
            // Need_user hands the lease back: record the native takeover facts before waiting.
            if (!recordStopLocked(binding, fresh, PhoneProgressReason.TAKEOVER, ended = false)) return null
        }
        // A model finish is not a person's "stop requested": the model event itself is the request,
        // and the native end is the acknowledged action recorded by recordModelFinish below.
        return fresh
    }

    private fun acceptedOrDuplicate(apply: PhoneProgressApply): Boolean =
        apply == PhoneProgressApply.ACCEPTED || apply == PhoneProgressApply.DUPLICATE

    private fun publishLocked() {
        val binding = bound
        val snapshot = store.snapshot()
        mutableProgress.value =
            if (binding == null || snapshot == null || snapshot.key != binding.key()) {
                null
            } else {
                PhoneTaskProgressView(snapshot, sentCount)
            }
    }

    private fun reply(apply: PhoneProgressApply): PhoneTaskEventReply =
        when (apply) {
            PhoneProgressApply.ACCEPTED -> PhoneTaskEventReply.Recorded(store.lastSeq())
            PhoneProgressApply.DUPLICATE -> PhoneTaskEventReply.Refused(PhoneTaskEventError.DUPLICATE)
            PhoneProgressApply.CAPACITY -> PhoneTaskEventReply.Refused(PhoneTaskEventError.CAPACITY)
            PhoneProgressApply.NO_TASK -> PhoneTaskEventReply.Refused(PhoneTaskEventError.NO_TASK)
            PhoneProgressApply.STALE_TASK -> PhoneTaskEventReply.Refused(PhoneTaskEventError.STALE_TASK)
            PhoneProgressApply.EPOCH_CHANGED -> PhoneTaskEventReply.Refused(PhoneTaskEventError.EPOCH_CHANGED)
            PhoneProgressApply.SESSION_MISMATCH ->
                PhoneTaskEventReply.Refused(PhoneTaskEventError.SESSION_MISMATCH)
            PhoneProgressApply.INVALID -> PhoneTaskEventReply.Refused(PhoneTaskEventError.INVALID)
            PhoneProgressApply.TASK_NOT_ACTIVE ->
                PhoneTaskEventReply.Refused(PhoneTaskEventError.TASK_NOT_ACTIVE)
            PhoneProgressApply.STOP_UNCONFIRMED -> PhoneTaskEventReply.HaltUnconfirmed()
        }

    private fun PhoneOverlayTaskBinding.key(): PhoneTaskKey =
        PhoneTaskKey(taskId = taskId, runtimeId = runtimeId)

    private fun PhoneOverlayTaskBinding.matches(
        taskId: String,
        runtimeId: String,
        epoch: Long,
    ): Boolean = this.taskId == taskId && this.runtimeId == runtimeId && this.epoch == epoch
}
