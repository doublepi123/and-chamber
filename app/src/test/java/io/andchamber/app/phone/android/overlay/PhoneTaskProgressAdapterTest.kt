package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.android.PhoneOverlayTaskBinding
import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneController
import io.andchamber.app.phone.core.PhoneGrantResult
import io.andchamber.app.phone.overlay.progress.PhoneMutationDelivery
import io.andchamber.app.phone.overlay.progress.PhoneNativeCallOutcome
import io.andchamber.app.phone.overlay.progress.PhoneProgressKind
import io.andchamber.app.phone.overlay.progress.PhoneProgressReason
import io.andchamber.app.phone.overlay.progress.PhoneProgressSource
import io.andchamber.app.phone.overlay.progress.PhoneProgressStore
import io.andchamber.app.phone.overlay.progress.PhoneStopAcknowledgement
import io.andchamber.app.phone.overlay.progress.PhoneTaskEvent
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventError
import io.andchamber.app.phone.overlay.progress.PhoneTaskEventReply
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.overlay.progress.PhoneTaskState
import io.andchamber.app.phone.tool.FakePhoneDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production task adapter over the real progress store, driven by a fake native port. The fake stop
 * behaves like the integration: main-thread, exact owner/epoch, returns a receipt only when that
 * exact lease was live. These tests pin the native-owner facts that the HTTP tool thread relies on.
 */
class PhoneTaskProgressAdapterTest {
    private val runtime = "launch-1"
    private val taskA = "task-A"
    private val taskB = "task-B"
    private val pkg = "com.example.target"
    private val summary = "Book a table for two"
    private val session = "ses-a"
    private val keyA = PhoneTaskKey(taskA, runtime)
    private val ack = PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true)

    private class FakeStop(var liveEpoch: Long?) : PhoneTaskStopPort {
        var calls = 0
        var lastEpoch: Long? = null
        val intents = mutableListOf<PhoneTaskStopIntent>()

        override fun stopExact(
            binding: PhoneOverlayTaskBinding,
            intent: PhoneTaskStopIntent,
        ): PhoneStopAcknowledgement? {
            calls++
            lastEpoch = binding.epoch
            intents += intent
            if (intent == PhoneTaskStopIntent.END) {
                // The integration also persists the needs-check record for an already stopped task;
                // a different live epoch is never ended by an old binding.
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
        route: String? = session,
        taskId: String = taskA,
    ): PhoneOverlayTaskBinding =
        PhoneOverlayTaskBinding(
            taskId = taskId,
            runtimeId = runtime,
            epoch = epoch,
            routeSession = route,
            summary = summary,
            packageName = pkg,
        )

    private fun view(fixture: Fixture) = checkNotNull(fixture.adapter.progress.value)

    @Test
    fun `the first synchronous binding creates the task before any state collector runs`() {
        val fixture = Fixture()

        fixture.adapter.onTaskBinding(binding(1))

        assertEquals(keyA, fixture.adapter.currentTask(1))
        assertNull(fixture.adapter.currentTask(2))
        val progress = view(fixture)
        assertEquals(PhoneTaskState.ACTIVE, progress.snapshot.state)
        assertEquals(listOf(PhoneProgressKind.TASK_STARTED), progress.snapshot.events.map { it.kind })
        assertNull(progress.sentCount)
        assertEquals(session, progress.snapshot.routeSession)
    }

    @Test
    fun `a resume keeps the task and its events across a new epoch`() {
        val fixture = Fixture()
        fixture.adapter.onTaskBinding(binding(1))
        fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.Progress("m1", "checking"))
        val firstSeq = view(fixture).snapshot.events.map { it.seq }

        fixture.adapter.onTaskBinding(binding(2))

        val resumed = view(fixture).snapshot
        assertEquals(2L, resumed.grantEpoch)
        assertTrue(resumed.events.map { it.seq }.containsAll(firstSeq))
        assertEquals(keyA, fixture.adapter.currentTask(2))
        val old =
            fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.Progress("m2", "late"))
        assertEquals(PhoneTaskEventError.EPOCH_CHANGED, (old as PhoneTaskEventReply.Refused).code)
        assertFalse(
            fixture.adapter.onNativeCallCompleted(
                keyA,
                1,
                "call-old",
                PhoneNativeCallOutcome.Observed(captured = false),
            ),
        )
    }

    @Test
    fun `a manual route is bound only by the native server's validated first claim`() {
        val fixture = Fixture()
        fixture.adapter.onTaskBinding(binding(1, route = null))
        // Before the claim is adopted the tool router is the gate; the store has no session yet.
        assertTrue(
            fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.Progress("m1", "working"))
                is PhoneTaskEventReply.Recorded,
        )

        val adopted = fixture.adapter.onRouteClaimed(keyA, 1, session)
        assertEquals(session, adopted?.routeSession)
        // The route is bound: a second, different session cannot take it over.
        assertNull(fixture.adapter.onRouteClaimed(keyA, 1, "ses-b"))

        assertEquals(session, view(fixture).snapshot.routeSession)
        val wrong = fixture.adapter.handleOnMain(keyA, 1, "ses-b", PhoneTaskEvent.NeedUser("n1", "check"))
        assertEquals(PhoneTaskEventError.SESSION_MISMATCH, (wrong as PhoneTaskEventReply.Refused).code)
        assertEquals(0, fixture.stop.calls)
    }

    @Test
    fun `need_user stops exactly the live epoch, then waits for the person`() {
        val fixture = Fixture()
        fixture.adapter.onTaskBinding(binding(1))

        val reply =
            fixture.adapter.handleOnMain(
                keyA,
                1,
                session,
                PhoneTaskEvent.NeedUser("n1", "Please check the code"),
            )

        assertTrue(reply is PhoneTaskEventReply.WaitingForUser)
        assertEquals(1, fixture.stop.calls)
        assertEquals(1L, fixture.stop.lastEpoch)
        assertEquals(listOf(PhoneTaskStopIntent.TAKEOVER), fixture.stop.intents)
        val snapshot = view(fixture).snapshot
        assertEquals(PhoneTaskState.WAITING_USER, snapshot.state)
        val kinds = snapshot.events.map { it.kind }
        assertTrue(
            kinds.indexOf(PhoneProgressKind.STOP_REQUESTED) <
                kinds.indexOf(PhoneProgressKind.STOP_ACKNOWLEDGED),
        )
        assertTrue(
            kinds.indexOf(PhoneProgressKind.STOP_ACKNOWLEDGED) <
                kinds.indexOf(PhoneProgressKind.MODEL_NEED_USER),
        )
        assertEquals(PhoneProgressSource.MODEL, snapshot.events.last().source)
    }

    @Test
    fun `an unconfirmed stop records no waiting and no finish`() {
        val fixture = Fixture(liveEpoch = 9L)
        fixture.adapter.onTaskBinding(binding(1))

        val need = fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.NeedUser("n1", "check"))
        assertTrue(need is PhoneTaskEventReply.HaltUnconfirmed)
        assertEquals(PhoneTaskState.ACTIVE, view(fixture).snapshot.state)
        assertEquals(1, fixture.stop.calls)

        val finish = fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.Finish("f1", "done"))
        assertTrue(finish is PhoneTaskEventReply.HaltUnconfirmed)
        assertEquals(PhoneTaskState.ACTIVE, view(fixture).snapshot.state)
        assertFalse(view(fixture).snapshot.finishReported)
    }

    @Test
    fun `finish stays pending verification and never claims a business success`() {
        val fixture = Fixture()
        fixture.adapter.onTaskBinding(binding(1))
        fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.NeedUser("n1", "check"))

        val reply =
            fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.Finish("f1", "I booked it"))

        assertTrue(reply is PhoneTaskEventReply.EndedPendingCheck)
        val snapshot = view(fixture).snapshot
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, snapshot.state)
        assertTrue(snapshot.finishReported)
        assertNull(snapshot.business)
        // A model finish must really end the native task (needs-check record), not just take over.
        assertEquals(2, fixture.stop.calls)
        assertEquals(
            listOf(PhoneTaskStopIntent.TAKEOVER, PhoneTaskStopIntent.END),
            fixture.stop.intents,
        )
        // The end is not a fabricated "you requested a stop": only the need_user takeover requested.
        val allEvents = checkNotNull(fixture.store.snapshot(10)).events
        assertEquals(
            1,
            allEvents.count { it.kind == PhoneProgressKind.STOP_REQUESTED },
        )
        assertEquals(PhoneProgressKind.MODEL_FINISH, allEvents.last().kind)
    }

    @Test
    fun `a model finish on a live task requests the native end`() {
        val fixture = Fixture()
        fixture.adapter.onTaskBinding(binding(1))

        val reply =
            fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.Finish("f1", "done"))

        assertTrue(reply is PhoneTaskEventReply.EndedPendingCheck)
        assertEquals(listOf(PhoneTaskStopIntent.END), fixture.stop.intents)
        val snapshot = view(fixture).snapshot
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, snapshot.state)
        assertTrue(snapshot.finishReported)
        assertNull(snapshot.business)
    }

    @Test
    fun `a resume after a stop must stop the new live epoch again`() {
        val fixture = Fixture()
        fixture.adapter.onTaskBinding(binding(1))
        fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.NeedUser("n1", "check"))
        assertEquals(1, fixture.stop.calls)

        // The person handed the same task back: a new epoch is live, so the old receipt is stale.
        fixture.stop.liveEpoch = 2
        fixture.adapter.onTaskBinding(binding(2))
        val reply =
            fixture.adapter.handleOnMain(keyA, 2, session, PhoneTaskEvent.Finish("f1", "I booked it"))

        assertTrue(reply is PhoneTaskEventReply.EndedPendingCheck)
        assertEquals(2, fixture.stop.calls)
        assertEquals(2L, fixture.stop.lastEpoch)
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, view(fixture).snapshot.state)
    }

    @Test
    fun `a queued old-task event cannot stop or change a newer task`() {
        val fixture = Fixture()
        fixture.adapter.onTaskBinding(binding(1))
        fixture.adapter.onTaskBinding(binding(2, route = "ses-b", taskId = taskB))

        val late =
            fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.NeedUser("n1", "check"))

        assertEquals(PhoneTaskEventError.STALE_TASK, (late as PhoneTaskEventReply.Refused).code)
        assertEquals(0, fixture.stop.calls)
        assertFalse(
            fixture.adapter.onNativeLeaseDropped(
                taskA,
                runtime,
                1,
                PhoneProgressReason.TAKEOVER,
                ended = false,
                ack = ack,
            ),
        )
        val snapshot = view(fixture).snapshot
        assertEquals(taskB, snapshot.key.taskId)
        assertEquals(PhoneTaskState.ACTIVE, snapshot.state)
    }

    @Test
    fun `native results are sanitized, deduplicated and count only accepted sends`() {
        val fixture = Fixture()
        fixture.adapter.onTaskBinding(binding(1))

        assertTrue(
            fixture.adapter.onNativeCallCompleted(
                keyA,
                1,
                "call-1",
                PhoneNativeCallOutcome.Mutation(PhoneMutationDelivery.SENT),
            ),
        )
        // The same request id is one fact; a retry must not count twice.
        assertTrue(
            fixture.adapter.onNativeCallCompleted(
                keyA,
                1,
                "call-1",
                PhoneNativeCallOutcome.Mutation(PhoneMutationDelivery.SENT),
            ),
        )
        assertEquals(1, view(fixture).sentCount ?: -1)
        assertEquals(
            1,
            view(fixture).snapshot.events.count { it.kind == PhoneProgressKind.MUTATION_SENT },
        )

        assertTrue(
            fixture.adapter.onNativeCallCompleted(
                keyA,
                1,
                "call-2",
                PhoneNativeCallOutcome.Mutation(PhoneMutationDelivery.UNKNOWN),
            ),
        )
        assertEquals(1, view(fixture).sentCount ?: -1)
        assertEquals(PhoneTaskState.UNKNOWN_OUTCOME, view(fixture).snapshot.state)
        assertTrue(
            fixture.adapter.onNativeCallCompleted(
                keyA,
                1,
                "call-3",
                PhoneNativeCallOutcome.Observed(captured = false),
            ),
        )
        assertEquals(1, view(fixture).sentCount ?: -1)
        // An accepted observation must reach the overlay flow immediately (events/updatedAt).
        assertEquals(
            PhoneProgressKind.OBSERVED,
            view(fixture).snapshot.events.last().kind,
        )
        // A refused call is not a delivery fact and never becomes an event.
        val beforeRefusal = view(fixture).snapshot.events.size
        assertFalse(
            fixture.adapter.onNativeCallCompleted(
                keyA,
                1,
                "call-refused",
                PhoneNativeCallOutcome.Refused,
            ),
        )
        assertEquals(beforeRefusal, view(fixture).snapshot.events.size)

        // Wrong owner/epoch facts are ignored.
        assertFalse(
            fixture.adapter.onNativeCallCompleted(
                PhoneTaskKey("task-X", runtime),
                1,
                "call-4",
                PhoneNativeCallOutcome.Mutation(PhoneMutationDelivery.SENT),
            ),
        )
        assertFalse(
            fixture.adapter.onNativeCallCompleted(
                keyA,
                2,
                "call-5",
                PhoneNativeCallOutcome.Mutation(PhoneMutationDelivery.SENT),
            ),
        )

        // Native facts carry no free text; the confirmed summary never leaks into event rows.
        val events = view(fixture).snapshot.events
        assertTrue(events.filter { it.source == PhoneProgressSource.NATIVE }.all { it.text == null })
        assertFalse(events.any { it.text?.contains(summary) == true })
    }

    @Test
    fun `a retained task can be ended after need_user without a live lease`() {
        val fixture = Fixture()
        fixture.adapter.onTaskBinding(binding(1))
        fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.NeedUser("n1", "check"))
        assertEquals(PhoneTaskState.WAITING_USER, view(fixture).snapshot.state)

        // The person's overlay End: the integration persists the native needs-check record and the
        // adapter records the exact-owner native end fact. No lease is re-created here.
        assertTrue(
            fixture.adapter.onNativeLeaseDropped(
                taskA,
                runtime,
                1,
                PhoneProgressReason.USER_REQUESTED,
                ended = true,
                ack = ack,
            ),
        )

        val snapshot = view(fixture).snapshot
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, snapshot.state)
        assertNull(snapshot.business)
    }

    @Test
    fun `a late native result cannot reopen an ended task or touch a new epoch`() {
        val fixture = Fixture()
        fixture.adapter.onTaskBinding(binding(1))
        fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.NeedUser("n1", "check"))
        assertTrue(
            fixture.adapter.onNativeLeaseDropped(
                taskA,
                runtime,
                1,
                PhoneProgressReason.USER_REQUESTED,
                ended = true,
                ack = ack,
            ),
        )
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, view(fixture).snapshot.state)

        // A late observation for the same ended epoch may add a fact, but it cannot reopen the task.
        assertTrue(
            fixture.adapter.onNativeCallCompleted(
                keyA,
                1,
                "late-obs",
                PhoneNativeCallOutcome.Observed(captured = false),
            ),
        )
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, view(fixture).snapshot.state)

        // A new task/epoch is not touched by an old result.
        fixture.adapter.onTaskBinding(binding(2, taskId = taskB, route = "ses-b"))
        assertFalse(
            fixture.adapter.onNativeCallCompleted(
                keyA,
                1,
                "late-2",
                PhoneNativeCallOutcome.Observed(captured = false),
            ),
        )
        assertEquals(taskB, view(fixture).snapshot.key.taskId)
        assertEquals(PhoneTaskState.ACTIVE, view(fixture).snapshot.state)
        // The exact-owner lookup used by the person's End cannot resolve the old task either.
        assertNull(fixture.adapter.bindingFor(keyA, 1))
        assertEquals(taskB, fixture.adapter.bindingFor(PhoneTaskKey(taskB, runtime), 2)?.taskId)
    }

    @Test
    fun `an abandoned binding hides the projection without resurrecting the old task`() {
        val fixture = Fixture()
        fixture.adapter.onTaskBinding(binding(1))
        fixture.adapter.onTaskBinding(null)

        assertNull(fixture.adapter.progress.value)
        assertNull(fixture.adapter.currentTask(1))

        // The old key is remembered: a late binding for it cannot resurrect the task.
        fixture.adapter.onTaskBinding(binding(1))
        assertNull(fixture.adapter.progress.value)
        assertNull(fixture.adapter.currentTask(1))
    }

    @Test
    fun `a need_user stop advances the core epoch but keeps the retained waiting state`() {
        val controller =
            PhoneController(
                driver = FakePhoneDriver(),
                clock = { 1_000L },
                newObservationId = { "obs-1" },
            )
        controller.enable()
        val granted = controller.grant(pkg, PhoneActionKind.values().toSet(), 60_000L)
        val epoch = (granted as PhoneGrantResult.Granted).grant.epoch
        val adapter =
            PhoneTaskProgressAdapter(
                store = PhoneProgressStore(clock = { 1_000L }),
                stop =
                    PhoneTaskStopPort { binding, intent ->
                        val live = controller.state.value.grant?.epoch
                        // The real integration stop path: exactly this live epoch, then revoke.
                        if (intent == PhoneTaskStopIntent.TAKEOVER && live == binding.epoch) {
                            controller.revoke()
                            PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true)
                        } else {
                            null
                        }
                    },
            )
        adapter.onTaskBinding(binding(epoch))

        val reply =
            adapter.handleOnMain(keyA, epoch, session, PhoneTaskEvent.NeedUser("n1", "check the code"))
        assertTrue(reply is PhoneTaskEventReply.WaitingForUser)

        // The core really advanced: a live-epoch lookup for the new epoch is a false negative...
        val liveEpoch = controller.state.value.epoch
        assertTrue(liveEpoch > epoch)
        assertNull(controller.state.value.grant)
        assertNull(adapter.currentTask(liveEpoch))
        // ...while the retained state the overlay shows still is the waiting task.
        assertEquals(PhoneTaskState.WAITING_USER, adapter.retainedTaskState())
        assertEquals(PhoneTaskState.WAITING_USER, adapter.snapshot()?.state)
        // The exact retained owner is still resolvable for a later person End; no grant was recreated.
        assertEquals(taskA, adapter.bindingFor(keyA, epoch)?.taskId)
        assertNull(controller.state.value.grant)
        // The retained read is a snapshot only: it did not restore a grant or a live task.
        assertEquals(
            PhoneTaskEventError.EPOCH_CHANGED,
            (
                adapter.handleOnMain(keyA, liveEpoch, session, PhoneTaskEvent.Progress("m-new", "x"))
                    as PhoneTaskEventReply.Refused
            ).code,
        )
        assertEquals(PhoneTaskState.WAITING_USER, adapter.retainedTaskState())
    }

    @Test
    fun `retained state is not faked after an end, an abandon or a new owner`() {
        val fixture = Fixture()
        fixture.adapter.onTaskBinding(binding(1))
        assertEquals(PhoneTaskState.ACTIVE, fixture.adapter.retainedTaskState())
        fixture.adapter.handleOnMain(keyA, 1, session, PhoneTaskEvent.NeedUser("n1", "check"))
        assertEquals(PhoneTaskState.WAITING_USER, fixture.adapter.retainedTaskState())

        // The person's overlay End: ended is retained, but it must never be shown as waiting.
        assertTrue(
            fixture.adapter.onNativeLeaseDropped(
                taskA,
                runtime,
                1,
                PhoneProgressReason.USER_REQUESTED,
                ended = true,
                ack = ack,
            ),
        )
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, fixture.adapter.retainedTaskState())

        // Dismiss/abandon: nothing retained, and a late binding for the old key cannot revive it.
        fixture.adapter.onTaskBinding(null)
        assertNull(fixture.adapter.retainedTaskState())
        fixture.adapter.onTaskBinding(binding(1))
        assertNull(fixture.adapter.retainedTaskState())

        // A new owner replaces the projection; the old waiting state never leaks into it.
        fixture.stop.liveEpoch = 2
        fixture.adapter.onTaskBinding(binding(2, taskId = taskB, route = "ses-b"))
        assertEquals(PhoneTaskState.ACTIVE, fixture.adapter.retainedTaskState())
        assertEquals(taskB, fixture.adapter.snapshot()?.key?.taskId)
    }
}
