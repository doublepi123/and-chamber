package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneConnection
import io.andchamber.app.phone.core.PhoneConnectionResult
import io.andchamber.app.phone.core.PhoneController
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriver
import io.andchamber.app.phone.core.PhoneErrorCode
import io.andchamber.app.phone.core.PhoneImage
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneSnapshot
import io.andchamber.app.phone.core.PhoneStandingBlockReason
import io.andchamber.app.phone.standing.PhoneStandingGrantResult
import io.andchamber.app.phone.standing.PhoneStandingRefusal
import io.andchamber.app.phone.standing.PhoneStandingResume
import io.andchamber.app.phone.standing.PhoneStandingScope
import io.andchamber.app.phone.standing.PhoneTaskContinuationFailure
import io.andchamber.app.phone.standing.PhoneTaskContinuationState
import io.andchamber.app.phone.standing.PhoneTaskStartFailureStage
import io.andchamber.app.ui.phone.PhoneContinueDelivery
import io.andchamber.app.ui.phone.PhoneContinueFailure
import io.andchamber.app.ui.phone.PhoneHaltReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The continuation attempt policy: a model need_user is its own stop reason, a pre-wake technical
 * failure is NotResumed (retryable by a fresh person confirmation), and only a wake attempt can
 * produce the one-shot Failed state.
 */
class PhoneContinuationPolicyTest {
    private val targetPackage = "com.example.notes"

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

    private fun scope() =
        PhoneStandingScope(
            runtimeId = "launch-7",
            sessionLabel = "ses-a",
            allowedPackages = listOf(targetPackage),
            allowedActions = setOf(PhoneActionKind.TAP),
            windowEndsMs = 1_000_000L,
            perTaskMs = 60_000L,
        )

    private suspend fun connectedController(): PhoneController {
        val controller = PhoneController(driver = FakeDriver(), clock = { 1_000L })
        controller.enable()
        assertTrue(controller.connect(1_000) is PhoneConnectionResult.Connected)
        return controller
    }

    private fun grant(controller: PhoneController): Long {
        val result = controller.grantFromPreauthorization(scope(), targetPackage)
        assertTrue(result is PhoneStandingGrantResult.Granted)
        return (result as PhoneStandingGrantResult.Granted).grant.epoch
    }

    @Test
    fun `model need_user and person takeover keep independent block reasons`() {
        assertEquals(PhoneStandingBlockReason.NEED_USER, continuationStandingBlockReason(PhoneHaltReason.NeedUser))
        assertEquals(PhoneStandingBlockReason.TAKEOVER, continuationStandingBlockReason(PhoneHaltReason.TakenOver))
        assertEquals(
            PhoneStandingBlockReason.UNKNOWN_OUTCOME,
            continuationStandingBlockReason(PhoneHaltReason.OutcomeUnknown),
        )
        assertEquals(
            PhoneStandingBlockReason.CONNECTION_LOST,
            continuationStandingBlockReason(PhoneHaltReason.ConnectionLost),
        )
        assertEquals(
            PhoneStandingBlockReason.CONNECTION_LOST,
            continuationStandingBlockReason(PhoneHaltReason.StopChannelLost),
        )
        assertEquals(
            PhoneStandingBlockReason.DEVICE_LOCKED,
            continuationStandingBlockReason(PhoneHaltReason.ScreenLocked),
        )
        assertEquals(
            PhoneStandingBlockReason.RUNTIME_CHANGED,
            continuationStandingBlockReason(PhoneHaltReason.RuntimeChanged),
        )
    }

    @Test
    fun `pre-wake failures are not-resumed with a fixed cause, never the one-shot wake failed`() {
        assertEquals(PhoneContinueDelivery.NotResumed, continuationPreWakeDelivery())
        assertEquals(
            PhoneContinueFailure.ScreenNotIdle,
            continuationFailureFor(PhoneTaskStartFailureStage.OBSERVATION, PhoneErrorCode.HIERARCHY_NOT_IDLE),
        )
        assertEquals(
            PhoneContinueFailure.ReadTimeout,
            continuationFailureFor(PhoneTaskStartFailureStage.OBSERVATION, PhoneErrorCode.READ_TIMEOUT),
        )
        listOf(
            PhoneErrorCode.WINDOW_CHANGED,
            PhoneErrorCode.GEOMETRY_CHANGED,
            PhoneErrorCode.TARGET_MISMATCH,
        ).forEach { code ->
            assertEquals(
                code.name,
                PhoneContinueFailure.WindowUnstable,
                continuationFailureFor(PhoneTaskStartFailureStage.OBSERVATION, code),
            )
        }
        assertEquals(
            PhoneContinueFailure.ConnectionLost,
            continuationFailureFor(PhoneTaskStartFailureStage.OBSERVATION, PhoneErrorCode.NOT_CONNECTED),
        )
        assertEquals(
            PhoneContinueFailure.Cancelled,
            continuationFailureFor(PhoneTaskStartFailureStage.OBSERVATION, PhoneErrorCode.CANCELLED),
        )
        assertEquals(
            PhoneContinueFailure.StopChannelUnavailable,
            continuationFailureFor(PhoneTaskStartFailureStage.STOP_ENTRY, null),
        )
        assertEquals(
            PhoneContinueFailure.TargetUnavailable,
            continuationFailureFor(PhoneTaskStartFailureStage.TARGET_LAUNCH, null),
        )
        assertEquals(
            PhoneContinueFailure.ScopeUnavailable,
            continuationFailureFor(null, null, PhoneStandingRefusal.EXPIRED),
        )
        assertEquals(
            PhoneContinueFailure.NotReady,
            continuationFailureFor(null, null, PhoneStandingRefusal.STANDING_BLOCKED),
        )
    }

    @Test
    fun `only Idle or NotResumed may start another confirmation`() {
        assertTrue(continuationMayStart(PhoneContinueDelivery.Idle, inFlight = false))
        assertTrue(continuationMayStart(PhoneContinueDelivery.NotResumed, inFlight = false))
        assertFalse(continuationMayStart(PhoneContinueDelivery.Sending, inFlight = false))
        assertFalse(continuationMayStart(PhoneContinueDelivery.Delivered, inFlight = false))
        assertFalse(continuationMayStart(PhoneContinueDelivery.Failed, inFlight = false))
        assertFalse(continuationMayStart(PhoneContinueDelivery.Idle, inFlight = true))
        assertFalse(continuationMayStart(PhoneContinueDelivery.NotResumed, inFlight = true))
    }

    @Test
    fun `only a wake attempt can produce Delivered or Failed`() {
        assertEquals(PhoneContinueDelivery.Delivered, continuationWakeDelivery(delivered = true))
        assertEquals(PhoneContinueDelivery.Failed, continuationWakeDelivery(delivered = false))
        assertEquals(PhoneTaskContinuationState.DELIVERED, PhoneContinueDelivery.Delivered.toTaskContinuationState())
        assertEquals(PhoneTaskContinuationState.FAILED, PhoneContinueDelivery.Failed.toTaskContinuationState())
        assertEquals(PhoneTaskContinuationState.NOT_RESUMED, continuationPreWakeDelivery().toTaskContinuationState())
        assertEquals(
            PhoneTaskContinuationFailure.READ_TIMEOUT,
            PhoneContinueFailure.ReadTimeout.toTaskContinuationFailure(),
        )
    }

    @Test
    fun `a not-resumed failure can be confirmed again and creates a fresh grant`() = runTest {
        val controller = connectedController()
        val firstEpoch = grant(controller)
        controller.revoke()
        controller.blockStanding(PhoneStandingBlockReason.NEED_USER)
        assertTrue(controller.state.value.standingBlocked)

        // The first person confirmation got access back but the following setup failed: exact
        // rollback restored the original task/halt and left a NotResumed technical cause.
        assertTrue(
            controller.resumeStandingForPerson(
                PhoneStandingResume(runtimeId = "launch-7", sessionLabel = "ses-a"),
            ),
        )
        assertFalse(controller.state.value.standingBlocked)
        controller.blockStanding(PhoneStandingBlockReason.NEED_USER)
        assertEquals(PhoneStandingBlockReason.NEED_USER, controller.state.value.standingBlockReason)

        // A second person confirmation is allowed only because the delivery is NotResumed.
        assertTrue(continuationMayStart(PhoneContinueDelivery.NotResumed, inFlight = false))
        assertTrue(
            controller.resumeStandingForPerson(
                PhoneStandingResume(runtimeId = "launch-7", sessionLabel = "ses-a"),
            ),
        )
        assertFalse(controller.state.value.standingBlocked)
        val secondEpoch = grant(controller)
        assertTrue(secondEpoch > firstEpoch)
    }

    @Test
    fun `an old attempt cannot claim a newer live owner`() {
        assertFalse(
            standingExactLiveOwner(
                requestedEpoch = 1L,
                liveEpoch = 2L,
                identityMatchesRequestedKey = true,
                identityOwnedByNewerTask = false,
            ),
        )
        assertFalse(
            standingExactLiveOwner(
                requestedEpoch = 1L,
                liveEpoch = 1L,
                identityMatchesRequestedKey = false,
                identityOwnedByNewerTask = true,
            ),
        )
        assertTrue(
            standingExactLiveOwner(
                requestedEpoch = 1L,
                liveEpoch = 1L,
                identityMatchesRequestedKey = true,
                identityOwnedByNewerTask = false,
            ),
        )
    }
}
