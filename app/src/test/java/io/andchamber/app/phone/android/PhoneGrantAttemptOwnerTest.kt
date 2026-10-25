package io.andchamber.app.phone.android

import io.andchamber.app.phone.tool.PhonePendingRequest
import io.andchamber.app.ui.phone.PhoneActionScope
import io.andchamber.app.ui.phone.PhonePending
import io.andchamber.app.ui.phone.PhoneTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused regressions for the grant-attempt owner: a finish, disable, lock or newer grant invalidates
 * the frozen confirmation, so a late continuation cannot stop a newer service or erase its task.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneGrantAttemptOwnerTest {
    @Test
    fun `a newer grant replaces the previous attempt and only one can finish`() {
        val owner = PhoneGrantAttemptOwner()
        val first = owner.begin()
        val second = owner.begin()

        assertFalse(owner.isCurrent(first))
        assertTrue(owner.isCurrent(second))
        assertFalse(owner.finish(first))
        assertTrue(owner.finish(second))
        assertFalse(owner.finish(second))
        assertNull(owner.invalidate())
    }

    @Test
    fun `an invalidated attempt never consumes or publishes its frozen proposal`() = runTest {
        val owner = PhoneGrantAttemptOwner()
        val attempt = owner.begin()
        val service = CompletableDeferred<Boolean>()
        var consumed = false
        var published = false
        val routing =
            async {
                runGrantAttempt(
                    owner = owner,
                    attempt = attempt,
                    epoch = 4,
                    frozenRevision = 3L,
                    frozenResumeSession = null,
                    awaitStopEntry = { service.await() },
                    currentEpoch = { 4L },
                    currentRevision = { 3L },
                    consume = {
                        consumed = true
                        PhonePendingRequest(3, "ses-a", "write", "com.example.notes", 1)
                    },
                    publish = { _, _ ->
                        published = true
                        true
                    },
                )
            }
        runCurrent()
        owner.invalidate()
        service.complete(true)

        assertEquals(PhoneGrantAttemptOutcome.Stale, routing.await())
        assertFalse("a stale attempt must not consume the frozen proposal", consumed)
        assertFalse("a stale attempt must not publish a route", published)
    }

    @Test
    fun `finish A then commit B leaves B grant route service task and facts untouched`() = runTest {
        val flow = GrantFlow()
        val serviceA = CompletableDeferred<Boolean>()
        val a = async { flow.runManualGrant(epoch = 1, serviceGate = serviceA) }
        runCurrent()
        assertEquals(1L, flow.serviceEpoch)

        flow.finish(ended = PhoneTaskRecord("com.example.old", "ended task", needsCheck = true))

        val serviceB = CompletableDeferred<Boolean>()
        val b = async { flow.runManualGrant(epoch = 2, serviceGate = serviceB) }
        runCurrent()
        assertEquals(2L, flow.serviceEpoch)
        serviceB.complete(true)
        b.await()
        val afterB = flow.snapshot()
        assertEquals(1, flow.commitCalls)
        assertEquals(0, flow.failCalls)

        // A's real stop-entry wait releases after B committed.
        serviceA.complete(true)
        a.await()

        assertEquals(afterB, flow.snapshot())
        assertEquals(1, flow.commitCalls)
        assertEquals(0, flow.failCalls)
    }

    @Test
    fun `finish A without B keeps the ended record when A returns late`() = runTest {
        val flow = GrantFlow()
        val serviceA = CompletableDeferred<Boolean>()
        val a = async { flow.runManualGrant(epoch = 1, serviceGate = serviceA) }
        runCurrent()
        assertEquals(1L, flow.serviceEpoch)

        val ended = PhoneTaskRecord("com.example.old", "ended task", needsCheck = true)
        flow.finish(ended = ended)
        val afterFinish = flow.snapshot()
        serviceA.complete(true)
        a.await()

        assertEquals(afterFinish, flow.snapshot())
        assertEquals(ended, flow.store.task())
        assertEquals(ended, flow.facts.task)
        assertEquals(0, flow.failCalls)
        assertEquals(1, flow.stopCalls)
    }

    @Test
    fun `a current attempt that really fails still revokes and stops`() = runTest {
        val flow = GrantFlow()
        val serviceA = CompletableDeferred<Boolean>()
        val a = async { flow.runManualGrant(epoch = 1, serviceGate = serviceA) }
        runCurrent()
        serviceA.complete(false)
        a.await()

        assertEquals(1, flow.failCalls)
        assertEquals(1, flow.revokeCalls)
        assertEquals(1, flow.stopCalls)
        assertNull(flow.facts.pending)
        assertNull(flow.facts.task)
        assertNull(flow.store.task())
        assertEquals(
            PhoneIntegration.GRANT_TOKEN_SERVICE_UNAVAILABLE,
            flow.facts.diagnostics.lastError,
        )
    }

    /**
     * Test harness around the production [runGrantAttempt]/[applyGrantAttemptOutcome] flow. The
     * service wait is a real Deferred, and commit/failure touch separate fake service, route, task
     * and facts so a late continuation is visible.
     */
    private class GrantFlow {
        val owner = PhoneGrantAttemptOwner()
        val store = MemoryPhoneIntegrationStore()
        var facts = PhoneScreenFacts(hostPackage = HOST, available = true, enabled = true)
        var currentEpoch: Long? = null
        var serviceEpoch: Long? = null
        var route: Pair<Long, String?>? = null
        var commitCalls = 0
        var failCalls = 0
        var revokeCalls = 0
        var stopCalls = 0

        suspend fun runManualGrant(epoch: Long, serviceGate: CompletableDeferred<Boolean>) {
            val attempt = owner.begin()
            facts =
                facts.copy(
                    pending = PhonePending.Granting,
                    lease = leaseFor(epoch),
                )
            currentEpoch = epoch
            val outcome =
                runGrantAttempt(
                    owner = owner,
                    attempt = attempt,
                    epoch = epoch,
                    frozenRevision = null,
                    frozenResumeSession = null,
                    awaitStopEntry = {
                        serviceEpoch = epoch
                        serviceGate.await()
                    },
                    currentEpoch = { currentEpoch },
                    currentRevision = { null },
                    consume = { error("a manual confirmation must not consume a proposal") },
                    publish = { routeEpoch, label ->
                        route = routeEpoch to label
                        true
                    },
                )
            applyGrantAttemptOutcome(
                owner = owner,
                attempt = attempt,
                outcome = outcome,
                commit = { confirmedSession ->
                    commitCalls += 1
                    val task =
                        PhoneTaskRecord(
                            targetPackage = "com.example.task-$epoch",
                            summary = "task-$epoch",
                            needsCheck = true,
                        )
                    store.setTask(task)
                    route = epoch to confirmedSession
                    facts =
                        facts.copy(
                            pending = null,
                            task = task,
                            lease = leaseFor(epoch),
                        )
                },
                fail = { token ->
                    failCalls += 1
                    if (currentEpoch == epoch) revokeCalls += 1
                    stopCalls += 1
                    store.clearTask()
                    facts =
                        facts.copy(
                            pending = null,
                            task = null,
                            lease = null,
                            diagnostics = facts.diagnostics.copy(lastError = token),
                        )
                },
            )
        }

        fun finish(ended: PhoneTaskRecord) {
            val invalidated = owner.invalidate()
            val nextPending = pendingAfterGrantInvalidation(invalidated, facts.pending)
            if (nextPending != facts.pending) {
                facts = facts.copy(pending = nextPending)
            }
            currentEpoch = null
            store.setTask(ended)
            facts = facts.copy(task = ended, lease = null)
            stopCalls += 1
        }

        fun snapshot(): Snapshot =
            Snapshot(
                currentEpoch = currentEpoch,
                serviceEpoch = serviceEpoch,
                route = route,
                task = store.task(),
                facts = facts,
                commitCalls = commitCalls,
                failCalls = failCalls,
                revokeCalls = revokeCalls,
                stopCalls = stopCalls,
            )

        private fun leaseFor(epoch: Long): PhoneLeaseFacts =
            PhoneLeaseFacts(
                target = PhoneTarget(label = "target-$epoch", packageName = "com.example.task-$epoch"),
                summary = "task-$epoch",
                actions = emptySet<PhoneActionScope>(),
            )
    }

    private data class Snapshot(
        val currentEpoch: Long?,
        val serviceEpoch: Long?,
        val route: Pair<Long, String?>?,
        val task: PhoneTaskRecord?,
        val facts: PhoneScreenFacts,
        val commitCalls: Int,
        val failCalls: Int,
        val revokeCalls: Int,
        val stopCalls: Int,
    )

    private companion object {
        const val HOST = "io.andchamber.app"
    }
}
