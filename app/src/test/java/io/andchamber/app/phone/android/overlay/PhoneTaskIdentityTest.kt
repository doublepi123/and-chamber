package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.overlay.progress.PhoneTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native task id is memory-only and survives exactly one thing: a resume of the same task in the
 * same runtime. A different runtime, a finished task or a new confirmation must start a new task, so
 * an old task can never own a new grant or its events.
 */
class PhoneTaskIdentityTest {
    private var next = 0
    private val identity = PhoneTaskIdentity { "task-${++next}" }

    private fun bind(
        epoch: Long,
        resumed: Boolean,
        runtime: String = "launch-1",
        route: String? = "ses-a",
    ) = identity.bindOnPublish(
        epoch = epoch,
        resumed = resumed,
        runtimeId = runtime,
        routeSession = route,
        summary = "Book a table",
        packageName = "com.example.target",
    )

    @Test
    fun `a new task gets a fresh id`() {
        val first = bind(1, resumed = false)
        val second = bind(2, resumed = false)

        assertEquals("task-1", first.taskId)
        assertEquals("task-2", second.taskId)
        assertNotEquals(first.taskId, second.taskId)
    }

    @Test
    fun `a resume in the same runtime keeps the id`() {
        val first = bind(1, resumed = false)
        val resumed = bind(2, resumed = true)

        assertEquals(first.taskId, resumed.taskId)
        assertEquals("launch-1", resumed.runtimeId)
        assertEquals(2L, resumed.epoch)
    }

    @Test
    fun `a different runtime always gets a new id`() {
        val first = bind(1, resumed = false, runtime = "launch-1")
        val other = bind(2, resumed = true, runtime = "launch-2")

        assertNotEquals(first.taskId, other.taskId)
        assertEquals("launch-2", other.runtimeId)
        assertEquals(other.taskId to "launch-2", identity.current())
    }

    @Test
    fun `a cleared task cannot be resumed under its old id`() {
        val first = bind(1, resumed = false)
        identity.clear()
        assertNull(identity.current())

        val nextTask = bind(2, resumed = true)

        assertNotEquals(first.taskId, nextTask.taskId)
    }

    @Test
    fun `matching requires the exact id and runtime`() {
        val first = bind(1, resumed = false)

        assertTrue(identity.matches(first.taskId, "launch-1"))
        assertFalse(identity.matches(first.taskId, "launch-2"))
        assertFalse(identity.matches("task-x", "launch-1"))
    }

    @Test
    fun `an acknowledged stop projects waiting, ended or unknown`() {
        assertEquals(PhoneOverlayPhase.WAITING_USER, overlayTaskPhase(PhoneTaskState.WAITING_USER))
        assertEquals(PhoneOverlayPhase.NEEDS_CHECK, overlayTaskPhase(PhoneTaskState.ENDED_PENDING_CHECK))
        assertEquals(PhoneOverlayPhase.UNKNOWN, overlayTaskPhase(PhoneTaskState.UNKNOWN_OUTCOME))
        assertNull(overlayTaskPhase(PhoneTaskState.ACTIVE))
        assertNull(overlayTaskPhase(PhoneTaskState.STOPPING))
        assertNull(overlayTaskPhase(null))
    }

    @Test
    fun `an explicit takeover is not relabelled by a stale waiting projection and a live lease runs`() {
        // Explicit native TakenOver is newer source-of-truth than an old WAITING_USER projection.
        assertEquals(
            PhoneOverlayPhase.TAKEN_OVER,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = true,
                taskState = PhoneTaskState.WAITING_USER,
            ),
        )
        // A live ACTIVE task on the current lease is still the only live-run case.
        assertEquals(
            PhoneOverlayPhase.RUNNING,
            overlayPhaseFor(
                agentLive = true,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = true,
                taskState = PhoneTaskState.WAITING_USER,
            ),
        )
        // A fresh ACTIVE task on the live lease is the only live-run case.
        assertEquals(
            PhoneOverlayPhase.RUNNING,
            overlayPhaseFor(
                agentLive = true,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = true,
                taskState = PhoneTaskState.ACTIVE,
            ),
        )
        // An ended-pending task is a result card, not an automatic success.
        assertEquals(
            PhoneOverlayPhase.NEEDS_CHECK,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.ENDED_PENDING_CHECK,
            ),
        )
    }

    @Test
    fun `explicit confirmed need-user waits even with stopping or missing progress`() {
        assertEquals(
            PhoneOverlayPhase.WAITING_USER,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.STOPPING,
                explicitNeedUser = true,
            ),
        )
        assertEquals(
            PhoneOverlayPhase.WAITING_USER,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = null,
                explicitNeedUser = true,
            ),
        )
    }

    @Test
    fun `unknown closed and ended still win over explicit need-user`() {
        assertEquals(
            PhoneOverlayPhase.UNKNOWN,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.STOPPING,
                unknownOutcome = true,
                explicitNeedUser = true,
            ),
        )
        assertEquals(
            PhoneOverlayPhase.UNKNOWN,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.UNKNOWN_OUTCOME,
                explicitNeedUser = true,
            ),
        )
        assertEquals(
            PhoneOverlayPhase.ENDED,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.STOPPING,
                closed = true,
                explicitNeedUser = true,
            ),
        )
        assertEquals(
            PhoneOverlayPhase.NEEDS_CHECK,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.ENDED_PENDING_CHECK,
                explicitNeedUser = true,
            ),
        )
        // A newer live grant is not reopened by an old need-user flag.
        assertEquals(
            PhoneOverlayPhase.RUNNING,
            overlayPhaseFor(
                agentLive = true,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.ACTIVE,
                explicitNeedUser = false,
            ),
        )
    }

    @Test
    fun `unknown is shown with a live lease and is not masked by the legacy ended record`() {
        // The old `screen.task is PhoneTask.Ended` record is no longer an input to the pure phase.
        // A live unknown keeps the lease for observation only; the phase must say Unknown.
        assertEquals(
            PhoneOverlayPhase.UNKNOWN,
            overlayPhaseFor(
                agentLive = true,
                pausing = false,
                paused = true,
                pausedConfirmed = true,
                takenOver = true,
                taskState = PhoneTaskState.UNKNOWN_OUTCOME,
                unknownOutcome = true,
            ),
        )
        // Without any lease the unknown still stays visible instead of becoming a generic take-over.
        assertEquals(
            PhoneOverlayPhase.UNKNOWN,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = true,
                taskState = PhoneTaskState.UNKNOWN_OUTCOME,
            ),
        )
        // A core/native MayStillApply fact is enough even if the task projection is still ACTIVE.
        assertEquals(
            PhoneOverlayPhase.UNKNOWN,
            overlayPhaseFor(
                agentLive = true,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.ACTIVE,
                unknownOutcome = true,
            ),
        )
    }

    @Test
    fun `an explicit native end wins over an older unknown, without claiming a business result`() {
        assertEquals(
            PhoneOverlayPhase.NEEDS_CHECK,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = true,
                taskState = PhoneTaskState.ENDED_PENDING_CHECK,
                unknownOutcome = true,
            ),
        )
        // NEEDS_CHECK is a result card, not a verified business success; the phase carries no business.
    }

    @Test
    fun `a fresh active task on a new epoch runs and cannot be masked by old facts`() {
        assertEquals(
            PhoneOverlayPhase.RUNNING,
            overlayPhaseFor(
                agentLive = true,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = true,
                taskState = PhoneTaskState.ACTIVE,
                unknownOutcome = false,
            ),
        )
        assertEquals(
            PhoneOverlayPhase.PAUSING,
            overlayPhaseFor(
                agentLive = true,
                pausing = true,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.ACTIVE,
            ),
        )
    }

    @Test
    fun `waiting and ended project only for their current task state`() {
        assertEquals(
            PhoneOverlayPhase.WAITING_USER,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.WAITING_USER,
            ),
        )
        assertEquals(
            PhoneOverlayPhase.NEEDS_CHECK,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.ENDED_PENDING_CHECK,
            ),
        )
    }

    @Test
    fun `a legacy ended display record cannot mask the real state`() {
        // The pure projection has no legacy record input. An ACTIVE task with a stale take-over shows
        // TAKEN_OVER (never NEEDS_CHECK); a live ACTIVE runs; an UNKNOWN stays UNKNOWN.
        assertEquals(
            PhoneOverlayPhase.TAKEN_OVER,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = true,
                taskState = PhoneTaskState.ACTIVE,
            ),
        )
        assertEquals(
            PhoneOverlayPhase.RUNNING,
            overlayPhaseFor(
                agentLive = true,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.ACTIVE,
            ),
        )
        assertEquals(
            PhoneOverlayPhase.UNKNOWN,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.UNKNOWN_OUTCOME,
            ),
        )
        // `PhoneTask.Ended` remains for the native screen, but it is not a pure-phase fact.
    }

    @Test
    fun `a closed result is not reopened by a late task-state projection`() {
        // The person closed the result; a late waiting/ended/taken-over projection must not reopen it.
        assertEquals(
            PhoneOverlayPhase.ENDED,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = true,
                taskState = PhoneTaskState.WAITING_USER,
                closed = true,
            ),
        )
        assertEquals(
            PhoneOverlayPhase.ENDED,
            overlayPhaseFor(
                agentLive = false,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.UNKNOWN_OUTCOME,
                unknownOutcome = true,
                closed = true,
            ),
        )
        // A newer live lease is a new grant and may show the overlay again.
        assertEquals(
            PhoneOverlayPhase.RUNNING,
            overlayPhaseFor(
                agentLive = true,
                pausing = false,
                paused = false,
                pausedConfirmed = false,
                takenOver = false,
                taskState = PhoneTaskState.ACTIVE,
            ),
        )
    }
}
