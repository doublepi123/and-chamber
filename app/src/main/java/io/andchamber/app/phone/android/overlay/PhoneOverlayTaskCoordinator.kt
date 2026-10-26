package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.android.PhoneIntegration
import io.andchamber.app.phone.android.PhoneOverlayTaskBinding
import io.andchamber.app.phone.overlay.progress.PhoneNativeCallOutcome
import io.andchamber.app.phone.overlay.progress.PhoneProgressReason
import io.andchamber.app.phone.overlay.progress.PhoneProgressStore
import io.andchamber.app.phone.overlay.progress.PhoneStopAcknowledgement
import io.andchamber.app.phone.overlay.progress.PhoneTaskEvent
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventHandler
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventReply
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.overlay.progress.PhoneTaskSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native binding between the controller's task and the progress store.
 *
 * The native-confirmed binding is published synchronously by [PhoneIntegration] before a tool route
 * can be claimed, so [currentTask] never depends on an asynchronous state collector: the first real
 * action cannot see `NO_TASK` merely because Compose has not collected yet. Model events are
 * validated again and, for need_user/finish, the native stop of exactly that task/epoch is confirmed
 * on the main thread before anything becomes waiting/ended. The model never claims a delivery or a
 * business result; those only come from the tool lane's native-only callbacks.
 *
 * The tool server calls [onRouteClaimed]/[onNativeCallCompleted] from its HTTP thread. Those touch
 * only the thread-safe task adapter/store; the integration's main-only routing memory is updated with
 * a main-thread hop after the manual route was bound.
 */
internal class PhoneOverlayTaskCoordinator(
    private val integration: PhoneIntegration,
    store: PhoneProgressStore = PhoneProgressStore(clock = integration.monotonicClock),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : PhoneTaskEventHandler,
    PhoneTaskNativeFacts {
    private val adapter =
        PhoneTaskProgressAdapter(
            store = store,
            stop =
                PhoneTaskStopPort { binding, intent ->
                    when (intent) {
                        PhoneTaskStopIntent.TAKEOVER ->
                            integration.stopTaskExact(binding, PhoneProgressReason.TAKEOVER)
                        PhoneTaskStopIntent.END -> integration.endTaskExact(binding)
                    }
                },
        )

    /** Native-only progress for the overlay panel; null while no native-confirmed task exists. */
    val progress: StateFlow<PhoneTaskProgressView?> = adapter.progress

    override fun onTaskBinding(binding: PhoneOverlayTaskBinding?) = adapter.onTaskBinding(binding)

    override fun onNativeLeaseDropped(
        taskId: String,
        runtimeId: String,
        epoch: Long,
        reason: PhoneProgressReason,
        ended: Boolean,
        ack: PhoneStopAcknowledgement,
    ): Boolean = adapter.onNativeLeaseDropped(taskId, runtimeId, epoch, reason, ended, ack)

    /**
     * Tool server (HTTP thread), synchronously after the router accepted a first manual claim. The
     * store route is bound here and now; the integration's routing memory is main-only, so it is
     * updated after this returns. Never called with a model-supplied session.
     */
    override fun onRouteClaimed(
        key: PhoneTaskKey,
        admittedEpoch: Long,
        sessionLabel: String,
    ) {
        val adopted = adapter.onRouteClaimed(key, admittedEpoch, sessionLabel) ?: return
        scope.launch(Dispatchers.Main.immediate) {
            integration.onTaskRouteAdopted(adopted, sessionLabel)
        }
    }

    /** Tool server (HTTP thread): one sanitized native result, recorded for exactly this owner. */
    override fun onNativeCallCompleted(
        key: PhoneTaskKey,
        admittedEpoch: Long,
        requestId: String,
        outcome: PhoneNativeCallOutcome,
    ) {
        adapter.onNativeCallCompleted(key, admittedEpoch, requestId, outcome)
    }

    override fun currentTask(epoch: Long): PhoneTaskKey? = adapter.currentTask(epoch)

    /**
     * Main thread. Ends exactly the retained native task/epoch (person's End after a takeover,
     * need_user or unknown outcome, or a live task). The integration persists the needs-check record;
     * the store then gets the native end fact. A wrong/stale owner returns false and touches nothing.
     */
    fun endRetainedTask(
        key: PhoneTaskKey,
        epoch: Long,
    ): Boolean {
        val binding = adapter.bindingFor(key, epoch) ?: return false
        val ack = integration.endTaskExact(binding) ?: return false
        adapter.onNativeLeaseDropped(
            taskId = binding.taskId,
            runtimeId = binding.runtimeId,
            epoch = binding.epoch,
            reason = PhoneProgressReason.USER_REQUESTED,
            ended = true,
            ack = ack,
        )
        return true
    }

    /** Latest snapshot for a native projection (overlay events); read-only. */
    fun snapshot(): PhoneTaskSnapshot? = adapter.snapshot()

    /**
     * One model event, already route/epoch-admitted by the tool server. It hops to the main thread
     * first: the native owner, the controller lease and the store are main-thread facts. An old
     * task/key/epoch/route event can therefore never revoke or clear a newer grant.
     */
    override suspend fun handle(
        key: PhoneTaskKey,
        epoch: Long,
        sessionLabel: String,
        event: PhoneTaskEvent,
    ): PhoneTaskEventReply =
        withContext(Dispatchers.Main.immediate) {
            adapter.handleOnMain(key, epoch, sessionLabel, event)
        }
}

/**
 * Defers creating the native task handler until the tool actually needs it, so the DI graph
 * (integration → proposal store → tool binding → task handler → integration) has no construction cycle.
 */
internal class LazyPhoneTaskEventHandler(
    private val provider: () -> PhoneTaskEventHandler,
) : PhoneTaskEventHandler {
    private val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { provider() }

    override val available: Boolean get() = delegate.available

    override fun currentTask(epoch: Long): PhoneTaskKey? = delegate.currentTask(epoch)

    override suspend fun handle(
        key: PhoneTaskKey,
        epoch: Long,
        sessionLabel: String,
        event: PhoneTaskEvent,
    ): PhoneTaskEventReply = delegate.handle(key, epoch, sessionLabel, event)

    // The interface defaults are no-ops; a lazy wrapper must forward them or the tool's native facts
    // never reach the real handler.
    override fun onRouteClaimed(
        key: PhoneTaskKey,
        admittedEpoch: Long,
        sessionLabel: String,
    ) = delegate.onRouteClaimed(key, admittedEpoch, sessionLabel)

    override fun onNativeCallCompleted(
        key: PhoneTaskKey,
        admittedEpoch: Long,
        requestId: String,
        outcome: PhoneNativeCallOutcome,
    ) = delegate.onNativeCallCompleted(key, admittedEpoch, requestId, outcome)
}

/**
 * Defers the native task sink the same way: the integration is constructed before the task adapter,
 * but only calls the sink when a grant is really confirmed.
 */
internal class LazyPhoneTaskNativeFacts(
    private val provider: () -> PhoneTaskNativeFacts,
) : PhoneTaskNativeFacts {
    private val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { provider() }

    override fun onTaskBinding(binding: PhoneOverlayTaskBinding?) = delegate.onTaskBinding(binding)

    override fun onNativeLeaseDropped(
        taskId: String,
        runtimeId: String,
        epoch: Long,
        reason: PhoneProgressReason,
        ended: Boolean,
        ack: PhoneStopAcknowledgement,
    ): Boolean = delegate.onNativeLeaseDropped(taskId, runtimeId, epoch, reason, ended, ack)
}
