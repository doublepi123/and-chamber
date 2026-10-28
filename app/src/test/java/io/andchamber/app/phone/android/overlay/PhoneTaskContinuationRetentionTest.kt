package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.android.PhoneOverlayTaskBinding
import io.andchamber.app.phone.android.continuationCoreStoppedExact
import io.andchamber.app.phone.core.PhoneErrorCode
import io.andchamber.app.phone.overlay.progress.PhoneProgressKind
import io.andchamber.app.phone.overlay.progress.PhoneProgressReason
import io.andchamber.app.phone.overlay.progress.PhoneProgressStore
import io.andchamber.app.phone.overlay.progress.PhoneStopAcknowledgement
import io.andchamber.app.phone.overlay.progress.PhoneTaskEvent
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventReply
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.overlay.progress.PhoneTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed continuation is not an abandoned task. The production adapter/store must keep the same
 * task key, history and latest attempted epoch, so a later person-confirmed resume can bind a newer
 * epoch of the same task and expose `currentTask(newEpoch)` again.
 */
class PhoneTaskContinuationRetentionTest {
    private val runtime = "launch-1"
    private val taskA = "task-A"
    private val taskB = "task-B"
    private val pkg = "com.example.target"
    private val summary = "Book a table for two"
    private val session = "ses-a"
    private val keyA = PhoneTaskKey(taskA, runtime)
    private val keyB = PhoneTaskKey(taskB, runtime)
    private val ack = PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true)

    private class FakeStop(var liveEpoch: Long?) : PhoneTaskStopPort {
        override fun stopExact(
            binding: PhoneOverlayTaskBinding,
            intent: PhoneTaskStopIntent,
        ): PhoneStopAcknowledgement? {
            if (intent == PhoneTaskStopIntent.END || intent == PhoneTaskStopIntent.MODEL_FINISH) {
                if (liveEpoch != null && liveEpoch != binding.epoch) return null
                liveEpoch = null
                return PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true)
            }
            if (liveEpoch != binding.epoch) return null
            liveEpoch = null
            return PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true)
        }
    }

    private class Fixture(liveEpoch: Long? = 1L) {
        var now = 1_000L
        val stop = FakeStop(liveEpoch)
        val store = PhoneProgressStore(clock = { now })
        val adapter = PhoneTaskProgressAdapter(store = store, stop = stop)
    }

    private fun binding(
        epoch: Long,
        taskId: String = taskA,
    ): PhoneOverlayTaskBinding =
        PhoneOverlayTaskBinding(
            taskId = taskId,
            runtimeId = runtime,
            epoch = epoch,
            routeSession = session,
            summary = summary,
            packageName = pkg,
        )

    private fun fixture() = Fixture().also { it.adapter.onTaskBinding(binding(1)) }

    @Test
    fun `a failed continuation keeps the task and the next resume is not abandoned`() {
        val fixture = fixture()
        val waiting = fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.NeedUser("n1", "check"))
        assertTrue(waiting is PhoneTaskEventReply.WaitingForUser)
        assertEquals(PhoneTaskState.WAITING_USER, fixture.adapter.retainedTaskState())
        // The overlay intentionally shows only the latest three events; inspect the bounded store
        // ring, not that presentation slice, when checking retained task history.
        val firstEvents = fixture.store.snapshot(PhoneProgressStore.DEFAULT_EVENT_CAPACITY)!!.events.map { it.seq }

        // A new continuation attempt gets the same task at a newer epoch.
        fixture.adapter.onTaskBinding(binding(2))
        assertEquals(PhoneTaskState.ACTIVE, fixture.adapter.retainedTaskState())
        // Exact rollback of that attempt publishes a native stop receipt; it must not abandon the key.
        assertTrue(
            fixture.adapter.onNativeLeaseDropped(
                taskId = taskA,
                runtimeId = runtime,
                epoch = 2,
                reason = PhoneProgressReason.USER_REQUESTED,
                ended = false,
                ack = ack,
            ),
        )
        assertEquals(PhoneTaskState.STOPPING, fixture.adapter.retainedTaskState())
        assertNotNull(fixture.adapter.snapshot())
        assertEquals(keyA, fixture.adapter.snapshot()!!.key)
        assertEquals(2L, fixture.adapter.snapshot()!!.grantEpoch)
        assertEquals(keyA, fixture.adapter.currentTask(2))
        assertTrue(fixture.store.snapshot(PhoneProgressStore.DEFAULT_EVENT_CAPACITY)!!.events.map { it.seq }.containsAll(firstEvents))

        // A second person confirmation binds the next epoch of the same task.
        fixture.adapter.onTaskBinding(binding(3))
        assertEquals(keyA, fixture.adapter.currentTask(3))
        assertEquals(PhoneTaskState.ACTIVE, fixture.adapter.retainedTaskState())
        assertEquals(3L, fixture.adapter.snapshot()!!.grantEpoch)
        assertEquals(
            PhoneProgressKind.TASK_STARTED,
            fixture.store.snapshot(PhoneProgressStore.DEFAULT_EVENT_CAPACITY)!!.events.first().kind,
        )
    }

    @Test
    fun `an old failure cannot abandon or overwrite a newer task`() {
        val fixture = fixture()
        fixture.adapter.onTaskBinding(binding(2))
        fixture.adapter.onTaskBinding(binding(3, taskId = taskB))
        assertEquals(keyB, fixture.adapter.currentTask(3))
        assertNull(fixture.adapter.currentTask(2))

        assertFalse(
            fixture.adapter.onNativeLeaseDropped(
                taskId = taskA,
                runtimeId = runtime,
                epoch = 2,
                reason = PhoneProgressReason.USER_REQUESTED,
                ended = false,
                ack = ack,
            ),
        )
        assertEquals(keyB, fixture.adapter.snapshot()!!.key)
        assertEquals(PhoneTaskState.ACTIVE, fixture.adapter.retainedTaskState())
        assertEquals(keyB, fixture.adapter.currentTask(3))
    }

    @Test
    fun `only an explicit end or dismiss abandons the key`() {
        val fixture = fixture()
        val finish = fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.Finish("f1", "done"))
        assertTrue(finish is PhoneTaskEventReply.EndedPendingCheck)
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, fixture.adapter.retainedTaskState())

        // Closing/dismissing the result is the abandonment point.
        fixture.adapter.onTaskBinding(null)
        fixture.adapter.onTaskBinding(binding(2))
        assertNull(fixture.adapter.currentTask(2))
    }

    @Test
    fun `core-stopped read timeout is exact only for this attempt and this owner`() {
        assertTrue(
            continuationCoreStoppedExact(
                typedError = PhoneErrorCode.READ_TIMEOUT,
                liveEpoch = null,
                runtimeId = runtime,
                attemptEpoch = 2,
                key = keyA,
                retainedEpoch = 2,
                identityMatchesKey = true,
                haltStillSame = true,
                progressKeyMatches = true,
                progressEpoch = 2,
            ),
        )
        assertTrue(
            continuationCoreStoppedExact(
                typedError = PhoneErrorCode.NOT_CONNECTED,
                liveEpoch = null,
                runtimeId = runtime,
                attemptEpoch = 2,
                key = keyA,
                retainedEpoch = 2,
                identityMatchesKey = true,
                haltStillSame = true,
                progressKeyMatches = true,
                progressEpoch = 2,
            ),
        )
        // Unknown/no-grant alone is not enough.
        assertFalse(
            continuationCoreStoppedExact(
                typedError = PhoneErrorCode.OUTCOME_UNKNOWN,
                liveEpoch = null,
                runtimeId = runtime,
                attemptEpoch = 2,
                key = keyA,
                retainedEpoch = 2,
                identityMatchesKey = true,
                haltStillSame = true,
                progressKeyMatches = true,
                progressEpoch = 2,
            ),
        )
        assertFalse(
            continuationCoreStoppedExact(
                typedError = PhoneErrorCode.READ_TIMEOUT,
                liveEpoch = 3,
                runtimeId = runtime,
                attemptEpoch = 2,
                key = keyA,
                retainedEpoch = 2,
                identityMatchesKey = true,
                haltStillSame = true,
                progressKeyMatches = true,
                progressEpoch = 2,
            ),
        )
        assertFalse(
            continuationCoreStoppedExact(
                typedError = PhoneErrorCode.READ_TIMEOUT,
                liveEpoch = null,
                runtimeId = "launch-2",
                attemptEpoch = 2,
                key = keyA,
                retainedEpoch = 2,
                identityMatchesKey = true,
                haltStillSame = true,
                progressKeyMatches = true,
                progressEpoch = 2,
            ),
        )
        assertFalse(
            continuationCoreStoppedExact(
                typedError = PhoneErrorCode.READ_TIMEOUT,
                liveEpoch = null,
                runtimeId = runtime,
                attemptEpoch = 2,
                key = keyA,
                retainedEpoch = 1,
                identityMatchesKey = true,
                haltStillSame = true,
                progressKeyMatches = true,
                progressEpoch = 2,
            ),
        )
    }
}
