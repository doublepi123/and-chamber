package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.android.PhoneOverlayTaskBinding
import io.andchamber.app.phone.overlay.progress.PhoneTaskState
import java.util.UUID

/**
 * Identity of one native-confirmed task, kept in memory only.
 *
 * A task id must survive a resume (a new lease epoch for the same task) but never survives into a
 * new task: a different task, a finished task or a different runtime always gets a new id. The
 * persisted needs-check record intentionally does not restore an id: after a process restart the
 * person sees the old result, but no old task can own events or a new grant. All methods are main
 * thread only; the id is opaque and carries no page text, target or account data.
 */
internal class PhoneTaskIdentity(
    private val newId: () -> String = { "task-" + UUID.randomUUID() },
) {
    private var taskId: String? = null
    private var runtimeId: String? = null

    /** The confirmed id/runtime while the native side still owns the task, otherwise null. */
    fun current(): Pair<String, String>? {
        val id = taskId ?: return null
        val runtime = runtimeId ?: return null
        return id to runtime
    }

    /**
     * Main thread. Called only when a route is really published. A resume in the same runtime keeps
     * the id; anything else starts a new task id. The binding is the native owner the task adapter
     * uses from that moment on.
     */
    fun bindOnPublish(
        epoch: Long,
        resumed: Boolean,
        runtimeId: String,
        routeSession: String?,
        summary: String,
        packageName: String,
    ): PhoneOverlayTaskBinding {
        val id =
            if (resumed && taskId != null && this.runtimeId == runtimeId) {
                checkNotNull(taskId)
            } else {
                newId()
            }
        taskId = id
        this.runtimeId = runtimeId
        return PhoneOverlayTaskBinding(
            taskId = id,
            runtimeId = runtimeId,
            epoch = epoch,
            routeSession = routeSession,
            summary = summary,
            packageName = packageName,
        )
    }

    fun matches(taskId: String, runtimeId: String): Boolean =
        this.taskId == taskId && this.runtimeId == runtimeId

    fun clear() {
        taskId = null
        runtimeId = null
    }
}

/**
 * The overlay phase a natively confirmed task state projects, or null when the task state adds
 * nothing. `UNKNOWN_OUTCOME` is a real native state (the last action may still apply); it is not a
 * generic take-over and must not be hidden by the legacy `PhoneTask.Ended` display record.
 */
internal fun overlayTaskPhase(state: PhoneTaskState?): PhoneOverlayPhase? =
    when (state) {
        PhoneTaskState.UNKNOWN_OUTCOME -> PhoneOverlayPhase.UNKNOWN
        PhoneTaskState.WAITING_USER -> PhoneOverlayPhase.WAITING_USER
        PhoneTaskState.ENDED_PENDING_CHECK -> PhoneOverlayPhase.NEEDS_CHECK
        else -> null
    }

/**
 * The one phase projection used by the overlay coordinator. Order is part of the guarantee:
 *
 * 1. a closed result stays closed while no new lease is live;
 * 2. only an explicit native/task-progress end ([PhoneTaskState.ENDED_PENDING_CHECK], already
 *    filtered to this task/epoch by the caller) may say "ended"; it wins over an older unknown;
 * 3. a confirmed waiting task (the newest native stop for this task) is projected next;
 * 4. an unknown outcome (store state or a core/native `lastAction == MayStillApply`/blocked fact)
 *    is shown even while a lease is still live: only modifications are stopped, so the UI must say
 *    "unknown", not "running/ended";
 * 5. a newer live agent, pause and take-over follow;
 * 6. the old `screen.task is PhoneTask.Ended` display record is deliberately **not** an input: it is
 *    a native-UI fact, not proof of a real end.
 *
 * A new epoch's ACTIVE task state therefore projects `RUNNING` and cannot be masked by an old
 * stop/unknown/ended record.
 */
internal fun overlayPhaseFor(
    agentLive: Boolean,
    pausing: Boolean,
    paused: Boolean,
    pausedConfirmed: Boolean,
    takenOver: Boolean,
    taskState: PhoneTaskState?,
    /** True for a core/native uncertain outcome (e.g. halt.lastAction == MayStillApply). */
    unknownOutcome: Boolean = false,
    closed: Boolean = false,
    /**
     * Explicit native need_user halt fact (reason NeedUser, confirmed, not MayStillApply, no live
     * epoch). It projects WAITING_USER even while the progress store is only STOPPING or its
     * snapshot is temporarily missing. Unknown/closed/Ended still win, and an explicit takeover is
     * never rewritten into "needs you" by a stale WAITING_USER projection.
     */
    explicitNeedUser: Boolean = false,
): PhoneOverlayPhase? {
    if (closed && !agentLive) return PhoneOverlayPhase.ENDED
    if (taskState == PhoneTaskState.ENDED_PENDING_CHECK) return PhoneOverlayPhase.NEEDS_CHECK
    if (unknownOutcome || taskState == PhoneTaskState.UNKNOWN_OUTCOME) {
        return PhoneOverlayPhase.UNKNOWN
    }
    if (explicitNeedUser) return PhoneOverlayPhase.WAITING_USER
    // A current WAITING_USER projection is the legacy need_user fact. An explicit native takeover
    // is newer source-of-truth and must not be relabelled by that stale projection.
    if (taskState == PhoneTaskState.WAITING_USER && !takenOver) return PhoneOverlayPhase.WAITING_USER
    if (agentLive) return if (pausing) PhoneOverlayPhase.PAUSING else PhoneOverlayPhase.RUNNING
    if (paused) return if (pausedConfirmed) PhoneOverlayPhase.PAUSED else PhoneOverlayPhase.PAUSING
    if (takenOver) return PhoneOverlayPhase.TAKEN_OVER
    return null
}
