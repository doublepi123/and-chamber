package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneConnection
import io.andchamber.app.phone.core.PhoneConnectionResult
import io.andchamber.app.phone.core.PhoneController
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriver
import io.andchamber.app.phone.core.PhoneImage
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneSnapshot
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.standing.PhoneStandingGrantResult
import io.andchamber.app.phone.standing.PhoneStandingScope
import io.andchamber.app.ui.phone.PhoneActionScope
import io.andchamber.app.ui.phone.PhoneTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The post-grant start transaction: cancellation at the stop-entry wait or the first Observe must
 * run the exact rollback once and rethrow, never leaving a live grant, route or task. A newer owner
 * established meanwhile must survive an old attempt's cancellation.
 */
class PhoneStandingStartCancelGuardTest {
    private val targetPackage = "com.example.notes"
    private val target = PhoneTarget("Notes", targetPackage)
    private val leaseFacts =
        PhoneLeaseFacts(
            target = target,
            summary = "write a note",
            actions = setOf(PhoneActionScope.Tap),
        )

    private class FakeDriver : PhoneDriver {
        override fun capabilities(): PhoneCapabilities = PhoneCapabilities()

        override suspend fun connect(deadlineMs: Long): PhoneConnection =
            PhoneConnection("conn-1", "launch-7")

        override suspend fun disconnect(deadlineMs: Long) = Unit

        override suspend fun observe(deadlineMs: Long): PhoneSnapshot = error("not used")

        override suspend fun capture(binding: PhoneObservationBinding, deadlineMs: Long): PhoneImage =
            error("not used")

        override suspend fun dispatch(request: PhoneDispatchRequest, deadlineMs: Long): PhoneDispatchReceipt =
            error("not used")
    }

    private class Owner(
        var taskKey: PhoneTaskKey? = null,
        var retainedEpoch: Long? = null,
        var lease: PhoneLeaseFacts? = null,
        var route: String? = null,
        var taskSaved: Boolean = false,
    ) {
        var rollbacks: Int = 0
        var lastKey: PhoneTaskKey? = null
    }

    private fun scope() =
        PhoneStandingScope(
            runtimeId = "launch-7",
            sessionLabel = "ses-a",
            allowedPackages = listOf(targetPackage),
            allowedActions = setOf(PhoneActionKind.TAP),
            windowEndsMs = 1_000_000L,
            perTaskMs = 60_000L,
        )

    private suspend fun connectedController(): Pair<PhoneController, Long> {
        val controller = PhoneController(driver = FakeDriver(), clock = { 1_000L })
        controller.enable()
        assertTrue(controller.connect(1_000) is PhoneConnectionResult.Connected)
        val granted = controller.grantFromPreauthorization(scope(), targetPackage)
        assertTrue(granted is PhoneStandingGrantResult.Granted)
        return controller to (granted as PhoneStandingGrantResult.Granted).grant.epoch
    }

    /** Mirrors the integration's exact-owner rollback: only this epoch/key/lease is cleared. */
    private fun exactRollback(
        controller: PhoneController,
        owner: Owner,
    ): (Long, PhoneTaskKey?, PhoneLeaseFacts?) -> Unit =
        { epoch, key, rememberedLease ->
            owner.rollbacks += 1
            owner.lastKey = key
            val identity = owner.taskKey
            val keyMatches = key != null && identity != null && identity == key
            val newerIdentity = key != null && identity != null && !keyMatches
            val exact =
                standingRollbackTargets(
                    requestedEpoch = epoch,
                    liveEpoch = controller.state.value.grant?.epoch,
                    retainedEpoch = owner.retainedEpoch,
                    identityMatchesRequestedKey = keyMatches,
                    identityOwnedByNewerTask = newerIdentity,
                )
            if (exact) {
                if (controller.state.value.grant?.epoch == epoch) controller.revoke()
                if (rememberedLease == null || owner.lease === rememberedLease) owner.lease = null
                if (keyMatches || owner.retainedEpoch == epoch) {
                    owner.taskKey = null
                    owner.retainedEpoch = null
                    owner.route = null
                    owner.taskSaved = false
                }
            }
        }

    @Test
    fun `cancellation at the stop-entry wait leaves no grant route or task`() = runTest {
        val (controller, epoch) = connectedController()
        val owner =
            Owner(
                retainedEpoch = epoch,
                lease = leaseFacts,
                route = "standing-route",
                taskSaved = true,
            )
        val guard = PhoneStandingStartCancelGuard(epoch, exactRollback(controller, owner))
        guard.rememberLease(owner.lease)

        val job =
            launch {
                guard.transaction {
                    // No task binding yet: this models the stop-entry wait.
                    suspendCancellableCoroutine<Unit> { }
                }
            }
        yield()
        job.cancelAndJoin()

        assertEquals(1, owner.rollbacks)
        assertNull(owner.lastKey)
        assertNull(controller.state.value.grant)
        assertNull(owner.lease)
        assertNull(owner.route)
        assertFalse(owner.taskSaved)
    }

    @Test
    fun `cancellation at the first observe clears the bound epoch key lease route and task`() = runTest {
        val (controller, epoch) = connectedController()
        val key = PhoneTaskKey("task-1", "launch-7")
        val owner =
            Owner(
                taskKey = key,
                retainedEpoch = epoch,
                lease = leaseFacts,
                route = "standing-route",
                taskSaved = true,
            )
        val guard = PhoneStandingStartCancelGuard(epoch, exactRollback(controller, owner))
        guard.rememberLease(owner.lease)
        var reads = 0

        val job =
            launch {
                guard.transaction {
                    // The binding and route were published; the first Observe is now waiting.
                    guard.bind(key)
                    observeStandingWithinBudget(
                        deadlineElapsedMs = 15_000L,
                        nowElapsedMs = { 0L },
                        expectedPackage = targetPackage,
                    ) {
                        reads += 1
                        suspendCancellableCoroutine<PhoneStandingObserveRead> { }
                    }
                }
            }
        yield()
        job.cancelAndJoin()

        assertEquals(1, reads)
        assertEquals(1, owner.rollbacks)
        assertEquals(key, owner.lastKey)
        assertNull(controller.state.value.grant)
        assertNull(owner.taskKey)
        assertNull(owner.lease)
        assertNull(owner.route)
        assertFalse(owner.taskSaved)
    }

    @Test
    fun `an old cancellation never clears a newer owner`() = runTest {
        val (controller, oldEpoch) = connectedController()
        val oldKey = PhoneTaskKey("task-old", "launch-7")
        val owner =
            Owner(
                taskKey = oldKey,
                retainedEpoch = oldEpoch,
                lease = leaseFacts,
                route = "old-route",
                taskSaved = true,
            )
        val guard = PhoneStandingStartCancelGuard(oldEpoch, exactRollback(controller, owner))
        guard.rememberLease(owner.lease)

        val job =
            launch {
                guard.transaction {
                    guard.bind(oldKey)
                    suspendCancellableCoroutine<Unit> { }
                }
            }
        yield()

        // A newer attempt replaces the live owner before the old one is cancelled.
        controller.revoke()
        val newer = controller.grantFromPreauthorization(scope(), targetPackage)
        assertTrue(newer is PhoneStandingGrantResult.Granted)
        val newerEpoch = (newer as PhoneStandingGrantResult.Granted).grant.epoch
        val newerKey = PhoneTaskKey("task-new", "launch-7")
        val newerLease = leaseFacts.copy(summary = "newer task")
        owner.taskKey = newerKey
        owner.retainedEpoch = newerEpoch
        owner.lease = newerLease
        owner.route = "new-route"
        owner.taskSaved = true
        job.cancelAndJoin()

        assertEquals(1, owner.rollbacks)
        assertEquals(newerEpoch, controller.state.value.grant?.epoch)
        assertEquals(newerKey, owner.taskKey)
        assertSame(newerLease, owner.lease)
        assertEquals("new-route", owner.route)
        assertTrue(owner.taskSaved)
    }

    @Test
    fun `the original cancellation is rethrown even when cleanup throws`() = runTest {
        val expected = CancellationException("cancelled")
        var rollbacks = 0
        val guard =
            PhoneStandingStartCancelGuard(7L) { _, _, _ ->
                rollbacks += 1
                throw IllegalStateException("cleanup failure")
            }
        try {
            guard.transaction<Unit> { throw expected }
            fail("cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
        assertEquals(1, rollbacks)
    }
}
