package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.android.PhoneOverlayTaskBinding
import io.andchamber.app.phone.overlay.progress.PhoneMutationDelivery
import io.andchamber.app.phone.overlay.progress.PhoneNativeCallOutcome
import io.andchamber.app.phone.overlay.progress.PhoneProgressReason
import io.andchamber.app.phone.overlay.progress.PhoneProgressStore
import io.andchamber.app.phone.overlay.progress.PhoneStopAcknowledgement
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.overlay.progress.PhoneTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native phone-screen End must turn an UNKNOWN live/retained task into a real native
 * ENDED_PENDING_CHECK fact with one publish, without changing the model finish/overlay End flows and
 * without touching a newer task from a stale owner. The adapter/store are the production classes;
 * only the integration's freeze/end/publish steps are injected here.
 */
class PhoneTaskNativeEndHelperTest {
    private val binding =
        PhoneOverlayTaskBinding(
            taskId = "task-1",
            runtimeId = "launch-1",
            epoch = 7L,
            routeSession = "ses-a",
            summary = "Book a table",
            packageName = "com.example.target",
        )
    private val key = PhoneTaskKey("task-1", "launch-1")
    private val ack = PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true)

    private fun adapterWithUnknown(): PhoneTaskProgressAdapter {
        val adapter =
            PhoneTaskProgressAdapter(
                store = PhoneProgressStore(clock = { 1_000L }),
                stop = PhoneTaskStopPort { _, _ -> null },
            )
        adapter.onTaskBinding(binding)
        adapter.onNativeCallCompleted(
            key,
            7L,
            "call-1",
            PhoneNativeCallOutcome.Mutation(PhoneMutationDelivery.UNKNOWN),
        )
        assertEquals(PhoneTaskState.UNKNOWN_OUTCOME, view(adapter).snapshot.state)
        return adapter
    }

    private fun view(adapter: PhoneTaskProgressAdapter) = checkNotNull(adapter.progress.value)

    @Test
    fun `an unknown live task ends through the native UI entry with one native ended fact`() {
        val adapter = adapterWithUnknown()
        var frozen = 0
        var ended: PhoneOverlayTaskBinding? = null
        var published = 0
        var cleaned = 0
        val helper =
            PhoneTaskNativeEndHelper(
                freeze = { frozen += 1; binding },
                endExact = { candidate -> ended = candidate; ack },
                publish = { endedBinding, endedAck ->
                    published += 1
                    adapter.onNativeLeaseDropped(
                        taskId = endedBinding.taskId,
                        runtimeId = endedBinding.runtimeId,
                        epoch = endedBinding.epoch,
                        reason = PhoneProgressReason.USER_REQUESTED,
                        ended = true,
                        ack = endedAck,
                    )
                },
                finishWithoutFact = { cleaned += 1 },
            )

        assertTrue(helper.endFromNativeUi())

        assertEquals(1, frozen)
        assertEquals(binding, ended)
        assertEquals(1, published)
        assertEquals(0, cleaned)
        val snapshot = view(adapter).snapshot
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, snapshot.state)
        assertNull(snapshot.business)
        // The task projection (and its record) is retained; nothing was cleared by the end.
        assertEquals(key, adapter.currentTask(7L))
    }

    @Test
    fun `a retained no-grant task ends through the same native UI entry`() {
        val adapter = adapterWithUnknown()
        val retained = binding.copy(routeSession = null)
        var published = 0
        val helper =
            PhoneTaskNativeEndHelper(
                freeze = { retained },
                endExact = { ack },
                publish = { endedBinding, endedAck ->
                    published += 1
                    adapter.onNativeLeaseDropped(
                        taskId = endedBinding.taskId,
                        runtimeId = endedBinding.runtimeId,
                        epoch = endedBinding.epoch,
                        reason = PhoneProgressReason.USER_REQUESTED,
                        ended = true,
                        ack = endedAck,
                    )
                },
                finishWithoutFact = { error("retained end must not fall back to cleanup") },
            )

        assertTrue(helper.endFromNativeUi())

        assertEquals(1, published)
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, view(adapter).snapshot.state)
        assertNull(view(adapter).snapshot.business)
        assertEquals(key, adapter.currentTask(7L))
    }

    @Test
    fun `a second native end cannot publish twice`() {
        val adapter = adapterWithUnknown()
        var endCalls = 0
        var published = 0
        val helper =
            PhoneTaskNativeEndHelper(
                freeze = { binding },
                endExact = { if (endCalls++ == 0) ack else null },
                publish = { endedBinding, endedAck ->
                    published += 1
                    adapter.onNativeLeaseDropped(
                        taskId = endedBinding.taskId,
                        runtimeId = endedBinding.runtimeId,
                        epoch = endedBinding.epoch,
                        reason = PhoneProgressReason.USER_REQUESTED,
                        ended = true,
                        ack = endedAck,
                    )
                },
                finishWithoutFact = { error("task binding exists") },
            )

        assertTrue(helper.endFromNativeUi())
        assertFalse(helper.endFromNativeUi())

        assertEquals(2, endCalls)
        assertEquals(1, published)
        assertEquals(PhoneTaskState.ENDED_PENDING_CHECK, view(adapter).snapshot.state)
    }

    @Test
    fun `a stale owner end never publishes or touches a newer task`() {
        val store = PhoneProgressStore(clock = { 1_000L })
        val adapter = PhoneTaskProgressAdapter(store, PhoneTaskStopPort { _, _ -> null })
        val newTask = binding.copy(taskId = "task-2", epoch = 8L, routeSession = "ses-b")
        adapter.onTaskBinding(newTask)
        var published = 0
        var cleaned = 0
        val helper =
            PhoneTaskNativeEndHelper(
                freeze = { binding },
                // The integration's endTaskExact rejects a key/epoch that is no longer the current owner.
                endExact = { null },
                publish = { _, _ -> published += 1 },
                finishWithoutFact = { cleaned += 1 },
            )

        assertFalse(helper.endFromNativeUi())

        assertEquals(0, published)
        assertEquals(0, cleaned)
        val snapshot = view(adapter).snapshot
        assertEquals("task-2", snapshot.key.taskId)
        assertEquals(PhoneTaskState.ACTIVE, snapshot.state)
    }

    @Test
    fun `no current task keeps the original cleanup and never invents an end`() {
        var published = 0
        var cleaned = 0
        val helper =
            PhoneTaskNativeEndHelper(
                freeze = { null },
                endExact = { error("there is no binding to end") },
                publish = { _, _ -> published += 1 },
                finishWithoutFact = { cleaned += 1 },
            )

        assertFalse(helper.endFromNativeUi())

        assertEquals(0, published)
        assertEquals(1, cleaned)
    }
}
