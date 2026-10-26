package io.andchamber.app.phone.overlay.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneProgressStoreTest {
    private val key = PhoneTaskKey("task-1", "runtime-1")
    private val otherKey = PhoneTaskKey("task-2", "runtime-1")
    private val session = "ses-a"
    private val summary = "Book a table for two"
    private val packageName = "com.example.target"
    private val ack = PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true)
    private val incompleteAck = PhoneStopAcknowledgement(newOperationsBlocked = false, lateReadsBlocked = true)

    private class Fixture {
        var now = 1_000L
        val store = PhoneProgressStore(clock = { now })
    }

    @Test
    fun `no task refuses model events and invents no progress`() {
        val fixture = Fixture()
        assertEquals(
            PhoneProgressApply.NO_TASK,
            fixture.store.recordModelProgress("m1", key, 5, session, "working"),
        )
        assertNull(fixture.store.snapshot())
        assertNull(fixture.store.currentTask(5))
    }

    @Test
    fun `native start creates one task and the model cannot overwrite its facts`() {
        val fixture = Fixture()
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.startTask("e1", key, 5, session, summary, packageName),
        )
        val started = fixture.store.snapshot()!!
        assertEquals(key, started.key)
        assertEquals(5L, started.grantEpoch)
        assertEquals(session, started.routeSession)
        assertEquals(summary, started.summary)
        assertEquals(packageName, started.packageName)
        assertEquals(PhoneTaskState.ACTIVE, started.state)
        assertEquals(1, started.events.size)
        assertEquals(PhoneProgressSource.NATIVE, started.events[0].source)
        assertEquals(PhoneProgressKind.TASK_STARTED, started.events[0].kind)
        assertNull(started.events[0].text)
        assertEquals(key, fixture.store.currentTask(5))
        assertNull(fixture.store.currentTask(6))

        fixture.now = 1_100L
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.recordModelProgress("m1", key, 5, session, "checking the form"),
        )
        val model = fixture.store.snapshot()!!
        assertEquals(summary, model.summary)
        assertEquals(packageName, model.packageName)
        assertEquals(PhoneTaskState.ACTIVE, model.state)
        assertEquals(PhoneProgressSource.MODEL, model.events.last().source)
        assertEquals(PhoneProgressKind.MODEL_PROGRESS, model.events.last().kind)
        assertEquals("checking the form", model.events.last().text)
        assertNull(model.business)
        assertFalse(model.finishReported)

        assertEquals(
            PhoneProgressApply.SESSION_MISMATCH,
            fixture.store.recordModelProgress("m2", key, 5, "ses-b", "not mine"),
        )
        assertEquals(2, fixture.store.snapshot()!!.events.size)
    }

    @Test
    fun `epoch updates keep the task and stale epochs cannot write or clear`() {
        val fixture = Fixture()
        assertEquals(PhoneProgressApply.ACCEPTED, fixture.store.startTask("e1", key, 5, session, summary, packageName))
        val before = fixture.store.snapshot()!!.events

        assertEquals(PhoneProgressApply.ACCEPTED, fixture.store.updateEpoch("u1", key, 6, session))
        assertEquals(key, fixture.store.currentTask(6))
        assertNull(fixture.store.currentTask(5))
        assertTrue(fixture.store.snapshot()!!.events.take(before.size) == before)
        assertEquals(summary, fixture.store.snapshot()!!.summary)

        val count = fixture.store.snapshot()!!.events.size
        assertEquals(
            PhoneProgressApply.EPOCH_CHANGED,
            fixture.store.recordModelProgress("m1", key, 5, session, "stale"),
        )
        assertEquals(
            PhoneProgressApply.EPOCH_CHANGED,
            fixture.store.recordNativeObserved("n1", key, 5),
        )
        assertEquals(count, fixture.store.snapshot()!!.events.size)
        assertEquals(
            PhoneProgressApply.SESSION_MISMATCH,
            fixture.store.updateEpoch("u2", key, 6, "ses-b"),
        )
    }

    @Test
    fun `a new task is not touched by late events for the old task`() {
        val fixture = Fixture()
        assertEquals(PhoneProgressApply.ACCEPTED, fixture.store.startTask("e1", key, 5, session, summary, packageName))
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.recordNativeEnded("end1", key, 5, ack, PhoneProgressReason.TAKEOVER),
        )
        fixture.now = 2_000L
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.startTask("e2", otherKey, 6, "ses-b", "Second task", "com.example.other"),
        )
        val second = fixture.store.snapshot()!!
        assertEquals(otherKey, second.key)
        assertEquals("Second task", second.summary)
        assertEquals(1, second.events.size)

        assertEquals(PhoneProgressApply.STALE_TASK, fixture.store.recordModelProgress("late", key, 5, session, "old"))
        assertEquals(PhoneProgressApply.STALE_TASK, fixture.store.recordNativeMutation("late2", key, 5, PhoneMutationDelivery.SENT))
        assertEquals(PhoneProgressApply.STALE_TASK, fixture.store.recordNativeObserved("late3", key, 5))
        assertEquals(PhoneProgressApply.STALE_TASK, fixture.store.startTask("e3", key, 5, session, summary, packageName))

        val after = fixture.store.snapshot()!!
        assertEquals(otherKey, after.key)
        assertEquals("Second task", after.summary)
        assertEquals(1, after.events.size)
    }

    @Test
    fun `a manual task may start without a route and bind it on the same epoch`() {
        val fixture = Fixture()
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.startTask("e1", key, 5, null, summary, packageName),
        )
        assertNull(fixture.store.snapshot()!!.routeSession)
        // The router has already claimed the first action for ses-a; the store accepts it while
        // the native route is still unbound.
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.recordModelProgress("m1", key, 5, "ses-a", "checking"),
        )
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.updateEpoch("u1", key, 5, "ses-a"),
        )
        assertEquals("ses-a", fixture.store.snapshot()!!.routeSession)
        assertEquals(
            PhoneProgressApply.SESSION_MISMATCH,
            fixture.store.recordModelProgress("m2", key, 5, "ses-b", "not mine"),
        )
    }

    @Test
    fun `a strictly newer native epoch restores active from waiting stopping and unknown`() {
        val cases = listOf(
            "waiting" to { store: PhoneProgressStore, at: Long ->
                assertEquals(
                    PhoneProgressApply.ACCEPTED,
                    store.recordNativeBusiness("biz", key, at, PhoneBusinessResult.VERIFIED_DONE),
                )
                assertEquals(
                    PhoneProgressApply.ACCEPTED,
                    store.recordModelNeedUser("need", key, at, session, "help", ack),
                )
            },
            "stopping" to { store: PhoneProgressStore, at: Long ->
                assertEquals(
                    PhoneProgressApply.ACCEPTED,
                    store.recordNativeStopRequested("stop", key, at, PhoneProgressReason.TAKEOVER),
                )
                assertEquals(
                    PhoneProgressApply.ACCEPTED,
                    store.recordNativeStopped("stopped", key, at, ack, PhoneProgressReason.TAKEOVER),
                )
            },
            "unknown" to { store: PhoneProgressStore, at: Long ->
                assertEquals(
                    PhoneProgressApply.ACCEPTED,
                    store.recordNativeMutation("unknown", key, at, PhoneMutationDelivery.UNKNOWN),
                )
            },
        )
        for ((name, arrange) in cases) {
            val fixture = Fixture()
            val store = PhoneProgressStore(clock = { fixture.now })
            assertEquals(PhoneProgressApply.ACCEPTED, store.startTask("start", key, 5, session, summary, packageName))
            arrange(store, 5)
            val before = store.snapshot()!!
            val expectedState = when (name) {
                "waiting" -> PhoneTaskState.WAITING_USER
                "stopping" -> PhoneTaskState.STOPPING
                else -> PhoneTaskState.UNKNOWN_OUTCOME
            }
            assertEquals(name, expectedState, before.state)
            assertTrue(before.stopAck == null || before.stopAck!!.confirmed)

            assertEquals(
                PhoneProgressApply.ACCEPTED,
                store.updateEpoch("new-epoch", key, 6, session),
            )
            val after = store.snapshot()!!
            assertEquals(name, PhoneTaskState.ACTIVE, after.state)
            assertEquals(6L, after.grantEpoch)
            assertNull("$name: old stop ack must be cleared", after.stopAck)
            assertNull("$name: old current-grant business must be cleared", after.business)
            assertFalse(after.finishReported)
            assertTrue(
                "$name: old event history must remain",
                after.events.any { it.seq == before.events.last().seq },
            )
            if (name == "waiting") {
                assertTrue(after.events.any { it.kind == PhoneProgressKind.BUSINESS_RESULT })
                assertTrue(after.events.any { it.kind == PhoneProgressKind.MODEL_NEED_USER })
            }
            assertEquals(
                "$name: model progress resumes after the new native grant",
                PhoneProgressApply.ACCEPTED,
                store.recordModelProgress("resume", key, 6, session, "working again"),
            )
            assertEquals(PhoneTaskState.ACTIVE, store.snapshot()!!.state)
        }
    }

    @Test
    fun `same epoch manual route binding only binds the route and never restores a state`() {
        val cases = listOf(
            "waiting" to { store: PhoneProgressStore, at: Long ->
                assertEquals(
                    PhoneProgressApply.ACCEPTED,
                    store.recordModelNeedUser("need", key, at, session, "help", ack),
                )
            },
            "stopping" to { store: PhoneProgressStore, at: Long ->
                assertEquals(
                    PhoneProgressApply.ACCEPTED,
                    store.recordNativeStopRequested("stop", key, at, PhoneProgressReason.TAKEOVER),
                )
                assertEquals(
                    PhoneProgressApply.ACCEPTED,
                    store.recordNativeStopped("stopped", key, at, ack, PhoneProgressReason.TAKEOVER),
                )
            },
            "unknown" to { store: PhoneProgressStore, at: Long ->
                assertEquals(
                    PhoneProgressApply.ACCEPTED,
                    store.recordNativeMutation("unknown", key, at, PhoneMutationDelivery.UNKNOWN),
                )
            },
        )
        for ((name, arrange) in cases) {
            val fixture = Fixture()
            val store = PhoneProgressStore(clock = { fixture.now })
            // A manual task starts with no route; the first action's session is bound later.
            assertEquals(PhoneProgressApply.ACCEPTED, store.startTask("start", key, 5, null, summary, packageName))
            arrange(store, 5)
            val before = store.snapshot()!!
            assertEquals(
                PhoneProgressApply.ACCEPTED,
                store.updateEpoch("bind-route", key, 5, session),
            )
            val after = store.snapshot()!!
            assertEquals("$name: route was bound", session, after.routeSession)
            assertEquals("$name: state must not be restored", before.state, after.state)
            assertEquals("$name: stop ack must not be cleared", before.stopAck, after.stopAck)
            assertEquals("$name: business must not be cleared", before.business, after.business)
            assertEquals("$name: no progress event for a route bind", before.events.size, after.events.size)
            if (before.state == PhoneTaskState.UNKNOWN_OUTCOME) {
                assertEquals(
                    "an unknown outcome stays visible; only a new native epoch clears it",
                    PhoneProgressApply.ACCEPTED,
                    store.recordModelProgress("p", key, 5, session, "still unknown"),
                )
                assertEquals(PhoneTaskState.UNKNOWN_OUTCOME, store.snapshot()!!.state)
            } else {
                assertEquals(
                    "$name: no model progress until a new native epoch",
                    PhoneProgressApply.TASK_NOT_ACTIVE,
                    store.recordModelProgress("p", key, 5, session, "still stopped"),
                )
            }
        }
    }

    @Test
    fun `an ended task refuses a new epoch and a new task needs a new key`() {
        val fixture = Fixture()
        val store = fixture.store
        assertEquals(PhoneProgressApply.ACCEPTED, store.startTask("start", key, 5, session, summary, packageName))
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            store.recordModelFinish("finish", key, 5, session, "ended", ack),
        )
        val ended = store.snapshot()!!
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, ended.state)
        assertEquals(
            PhoneProgressApply.TASK_NOT_ACTIVE,
            store.updateEpoch("new-epoch", key, 6, session),
        )
        assertEquals(
            PhoneProgressApply.TASK_NOT_ACTIVE,
            store.updateEpoch("bind", key, 5, session),
        )
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, store.snapshot()!!.state)
        assertEquals(ended.finishReported, store.snapshot()!!.finishReported)
        assertEquals(ended.stopAck, store.snapshot()!!.stopAck)

        fixture.now = 2_000L
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            store.startTask("new-task", otherKey, 6, "ses-b", "Second task", "com.example.other"),
        )
        assertEquals(PhoneTaskState.ACTIVE, store.snapshot()!!.state)
        assertEquals(
            PhoneProgressApply.STALE_TASK,
            store.startTask("old-task", key, 6, session, summary, packageName),
        )
    }

    @Test
    fun `old stop callbacks and old model events cannot make the new epoch waiting`() {
        val fixture = Fixture()
        val store = fixture.store
        assertEquals(PhoneProgressApply.ACCEPTED, store.startTask("start", key, 5, session, summary, packageName))
        assertEquals(PhoneProgressApply.ACCEPTED, store.updateEpoch("new-epoch", key, 6, session))

        assertEquals(
            PhoneProgressApply.EPOCH_CHANGED,
            store.recordNativeStopped("old-stopped", key, 5, ack, PhoneProgressReason.TAKEOVER),
        )
        assertEquals(
            PhoneProgressApply.EPOCH_CHANGED,
            store.recordNativeStopRequested("old-stop", key, 5, PhoneProgressReason.TAKEOVER),
        )
        assertEquals(
            PhoneProgressApply.EPOCH_CHANGED,
            store.recordModelNeedUser("old-need", key, 5, session, "old help", ack),
        )
        assertEquals(
            PhoneProgressApply.EPOCH_CHANGED,
            store.recordNativeMutation("old-mutation", key, 5, PhoneMutationDelivery.UNKNOWN),
        )

        val after = store.snapshot()!!
        assertEquals(PhoneTaskState.ACTIVE, after.state)
        assertEquals(6L, after.grantEpoch)
        assertNull(after.stopAck)
        assertTrue(after.events.none { it.kind == PhoneProgressKind.MODEL_NEED_USER })
        assertTrue(after.events.none { it.kind == PhoneProgressKind.STOP_REQUESTED })
    }

    @Test
    fun `capacity failure leaves the current state and route untouched`() {
        val fixture = Fixture()
        val store = PhoneProgressStore(clock = { fixture.now }, eventIdCapacity = 2)
        assertEquals(PhoneProgressApply.ACCEPTED, store.startTask("start", key, 5, null, summary, packageName))
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            store.recordNativeStopRequested("stop", key, 5, PhoneProgressReason.TAKEOVER),
        )
        val before = store.snapshot()!!
        assertEquals(
            PhoneProgressApply.CAPACITY,
            store.updateEpoch("new-epoch", key, 6, session),
        )
        assertEquals(PhoneTaskState.STOPPING, store.snapshot()!!.state)
        assertEquals(5L, store.snapshot()!!.grantEpoch)
        assertNull(store.snapshot()!!.routeSession)
        assertEquals(before.stopAck, store.snapshot()!!.stopAck)
        assertEquals(before.events.size, store.snapshot()!!.events.size)
        assertEquals(
            PhoneProgressApply.DUPLICATE,
            store.updateEpoch("start", key, 6, session),
        )
        assertEquals(PhoneTaskState.STOPPING, store.snapshot()!!.state)
    }

    @Test
    fun `a late stop result never clears a newer task`() {
        val fixture = Fixture()
        assertEquals(PhoneProgressApply.ACCEPTED, fixture.store.startTask("e1", key, 5, session, summary, packageName))
        assertEquals(PhoneProgressApply.ACCEPTED, fixture.store.recordNativeStopRequested("s1", key, 5, PhoneProgressReason.TAKEOVER))
        assertEquals(PhoneTaskState.STOPPING, fixture.store.snapshot()!!.state)

        // No timeout can turn an unconfirmed stop into waiting.
        fixture.now += 60_000L
        assertEquals(PhoneTaskState.STOPPING, fixture.store.snapshot()!!.state)

        assertEquals(
            PhoneProgressApply.STOP_UNCONFIRMED,
            fixture.store.recordModelNeedUser("n1", key, 5, session, "needs a person", incompleteAck),
        )
        assertEquals(PhoneTaskState.STOPPING, fixture.store.snapshot()!!.state)
        assertFalse(fixture.store.snapshot()!!.events.any { it.kind == PhoneProgressKind.MODEL_NEED_USER })

        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.recordModelNeedUser("n2", key, 5, session, "needs a person", ack),
        )
        assertEquals(PhoneTaskState.WAITING_USER, fixture.store.snapshot()!!.state)
        assertEquals(ack, fixture.store.snapshot()!!.stopAck)

        fixture.now = 70_000L
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.startTask("e2", otherKey, 6, "ses-b", "Second", "com.example.other"),
        )
        assertEquals(
            PhoneProgressApply.STALE_TASK,
            fixture.store.recordNativeStopped("late", key, 5, ack, PhoneProgressReason.TAKEOVER),
        )
        assertEquals(otherKey, fixture.store.snapshot()!!.key)
    }

    @Test
    fun `need_user only becomes waiting after a complete native stop acknowledgement`() {
        val fixture = Fixture()
        assertEquals(PhoneProgressApply.ACCEPTED, fixture.store.startTask("e1", key, 5, session, summary, packageName))
        assertEquals(
            PhoneProgressApply.STOP_UNCONFIRMED,
            fixture.store.recordModelNeedUser("n1", key, 5, session, "help", incompleteAck),
        )
        assertEquals(PhoneTaskState.ACTIVE, fixture.store.snapshot()!!.state)
        assertTrue(fixture.store.snapshot()!!.events.none { it.kind == PhoneProgressKind.MODEL_NEED_USER })

        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.recordModelNeedUser("n2", key, 5, session, "help", ack),
        )
        val waiting = fixture.store.snapshot()!!
        assertEquals(PhoneTaskState.WAITING_USER, waiting.state)
        val need = waiting.events.last()
        assertEquals(PhoneProgressSource.MODEL, need.source)
        assertEquals(PhoneProgressKind.MODEL_NEED_USER, need.kind)
        assertEquals("help", need.text)
        assertTrue(need.reason == PhoneProgressReason.NEED_USER)
        assertTrue(waiting.stopAck!!.sentActionsMayStillApply)

        assertEquals(
            PhoneProgressApply.TASK_NOT_ACTIVE,
            fixture.store.recordModelProgress("m3", key, 5, session, "still talking"),
        )
    }

    @Test
    fun `finish is pending verification and never a business success`() {
        val fixture = Fixture()
        assertEquals(PhoneProgressApply.ACCEPTED, fixture.store.startTask("e1", key, 5, session, summary, packageName))
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.recordNativeMutation("m1", key, 5, PhoneMutationDelivery.SENT),
        )
        assertNull(fixture.store.snapshot()!!.business)
        assertEquals(PhoneTaskState.ACTIVE, fixture.store.snapshot()!!.state)

        assertEquals(
            PhoneProgressApply.STOP_UNCONFIRMED,
            fixture.store.recordModelFinish("f1", key, 5, session, "done", incompleteAck),
        )
        assertEquals(PhoneTaskState.ACTIVE, fixture.store.snapshot()!!.state)
        assertFalse(fixture.store.snapshot()!!.finishReported)

        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.recordNativeBusiness("b1", key, 5, PhoneBusinessResult.VERIFIED_DONE),
        )
        assertEquals(PhoneBusinessResult.VERIFIED_DONE, fixture.store.snapshot()!!.business)
        assertEquals(PhoneTaskState.ACTIVE, fixture.store.snapshot()!!.state)

        fixture.now = 2_000L
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.recordModelFinish("f2", key, 5, session, "I have reported the end", ack),
        )
        val ended = fixture.store.snapshot()!!
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, ended.state)
        assertTrue(ended.finishReported)
        assertEquals(PhoneBusinessResult.VERIFIED_DONE, ended.business)
        val finish = ended.events.last()
        assertEquals(PhoneProgressSource.MODEL, finish.source)
        assertEquals(PhoneProgressKind.MODEL_FINISH, finish.kind)
        assertEquals("I have reported the end", finish.text)
        assertTrue(ended.events.none { it.kind == PhoneProgressKind.TASK_ENDED && it.source == PhoneProgressSource.MODEL })

        // A late native fact may update the result, but never revives the task.
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.recordNativeBusiness("b2", key, 5, PhoneBusinessResult.VERIFIED_NOT_DONE),
        )
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, fixture.store.snapshot()!!.state)
        assertEquals(PhoneBusinessResult.VERIFIED_NOT_DONE, fixture.store.snapshot()!!.business)
    }

    @Test
    fun `model text and native facts are bounded and separated`() {
        val fixture = Fixture()
        assertEquals(PhoneProgressApply.INVALID, fixture.store.startTask("e0", key, 5, session, " ", packageName))
        assertEquals(
            PhoneProgressApply.INVALID,
            fixture.store.startTask("e1", key, 5, session, "x".repeat(201), packageName),
        )
        assertEquals(PhoneProgressApply.ACCEPTED, fixture.store.startTask("e2", key, 5, session, summary, packageName))
        assertEquals(PhoneProgressApply.INVALID, fixture.store.recordModelProgress("m1", key, 5, session, " "))
        assertEquals(
            PhoneProgressApply.INVALID,
            fixture.store.recordModelProgress("m2", key, 5, session, "x".repeat(121)),
        )
        assertEquals(
            PhoneProgressApply.INVALID,
            fixture.store.recordModelProgress("m3", key, 5, session, "bad\u0001text"),
        )
        assertEquals(PhoneProgressApply.ACCEPTED, fixture.store.recordNativeObserved("o1", key, 5))
        assertNull(fixture.store.snapshot()!!.events.last().text)
        assertEquals(PhoneProgressSource.NATIVE, fixture.store.snapshot()!!.events.last().source)

        assertEquals(
            PhoneProgressApply.ACCEPTED,
            fixture.store.recordNativeMutation("mu1", key, 5, PhoneMutationDelivery.UNKNOWN),
        )
        val unknown = fixture.store.snapshot()!!
        assertEquals(PhoneTaskState.UNKNOWN_OUTCOME, unknown.state)
        assertEquals(PhoneMutationDelivery.UNKNOWN, unknown.events.last().delivery)
        assertNull(unknown.events.last().business)
        assertEquals(1, unknown.events.count { it.kind == PhoneProgressKind.MUTATION_UNKNOWN })
    }

    @Test
    fun `event ids are remembered across tasks and capacity refuses without eviction`() {
        val fixture = Fixture()
        val store = PhoneProgressStore(clock = { fixture.now }, eventIdCapacity = 2)
        assertEquals(PhoneProgressApply.ACCEPTED, store.startTask("e1", key, 5, session, summary, packageName))
        assertEquals(
            PhoneProgressApply.ACCEPTED,
            store.recordModelProgress("m1", key, 5, session, "one"),
        )
        assertEquals(
            PhoneProgressApply.CAPACITY,
            store.recordModelProgress("m2", key, 5, session, "two"),
        )
        assertEquals(
            PhoneProgressApply.DUPLICATE,
            store.recordModelProgress("m1", key, 5, session, "one again"),
        )
        assertEquals(
            "a full event-id table also refuses a new task",
            PhoneProgressApply.CAPACITY,
            store.startTask("e2", otherKey, 6, "ses-b", "Second", "com.example.other"),
        )
        assertEquals(key, store.snapshot()!!.key)
        assertEquals(2, store.snapshot()!!.events.size)
    }

    @Test
    fun `recent events keep thirty-two while display defaults to three and seq is monotonic`() {
        val fixture = Fixture()
        val store = PhoneProgressStore(clock = { fixture.now })
        assertEquals(PhoneProgressApply.ACCEPTED, store.startTask("e0", key, 5, session, summary, packageName))
        for (index in 1..40) {
            assertEquals(
                PhoneProgressApply.ACCEPTED,
                store.recordNativeObserved("o$index", key, 5),
            )
        }
        val display = store.snapshot()!!
        assertEquals(3, display.events.size)
        assertEquals(32, store.snapshot(32)!!.events.size)
        assertEquals(32, store.snapshot(100)!!.events.size)
        val sequences = store.snapshot(32)!!.events.map { it.seq }
        assertEquals(sequences.sorted(), sequences)
        assertTrue(sequences.zipWithNext().all { (a, b) -> b == a + 1L })
        val times = store.snapshot(32)!!.events.map { it.atMs }
        assertEquals(times.sorted(), times)
        assertEquals(sequences.last(), store.lastSeq())
    }
}
