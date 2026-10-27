package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneConnection
import io.andchamber.app.phone.core.PhoneConnectionResult
import io.andchamber.app.phone.core.PhoneConnectionState
import io.andchamber.app.phone.core.PhoneController
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriver
import io.andchamber.app.phone.core.PhoneImage
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneSnapshot
import io.andchamber.app.phone.core.PhoneStandingBlockReason
import io.andchamber.app.phone.core.PhoneTimeLimits
import io.andchamber.app.phone.standing.PhoneStandingApp
import io.andchamber.app.phone.standing.PhoneStandingGrantResult
import io.andchamber.app.phone.standing.PhoneStandingResume
import io.andchamber.app.phone.standing.PhoneStandingScope
import io.andchamber.app.ui.phone.PhonePending
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native "Resume chat use" gate. It must refuse every uncertain or still-owned state before the
 * controller is touched, and the one allowed call must really clear the standing block (and ordinary
 * pause) through the existing person primitive so a fresh request can start in the same scope.
 */
class PhoneStandingResumeTest {
    private val targetPackage = "com.example.notes"
    private val target = PhoneStandingApp("Notes", targetPackage)
    private val config =
        PhoneStandingConfig(
            apps = listOf(target),
            actions = setOf(PhoneActionKind.TAP),
            windowEndsAtWallMs = 10_000L,
            perTaskMs = 60_000L,
        )
    private val binding = PhoneStandingBindingState(runtimeId = "launch-7", sessionLabel = "ses-a")

    private val clean =
        StandingResumeFacts(
            available = true,
            enabled = true,
            controllerEnabled = true,
            config = config,
            nowWallMs = 9_000L,
            nowElapsedMs = 1_000L,
            endsAtElapsedMs = 5_000L,
            expired = false,
            runtimeId = "launch-7",
            binding = binding,
            runtimeRunning = true,
            toolReady = true,
            connectionConnected = true,
            stopChannelVerified = true,
            pending = null,
            continuationInFlight = false,
            continuationSending = false,
            hasGrant = false,
            hasHalt = false,
            hasLease = false,
            hasRetainedTask = false,
            hasTaskOwner = false,
            inFlightMutation = false,
            pausing = false,
            standingBlocked = true,
            standingBlockReason = PhoneStandingBlockReason.TAKEOVER,
            coreBlocked = false,
            haltMayStillApply = false,
            haltAcknowledged = false,
        )

    @Test
    fun `a clean paused scope passes and runs the resume callback exactly once`() {
        assertNull(standingResumeRefusal(clean))
        var calls = 0
        assertTrue(confirmedStandingResume(clean) { calls += 1; true })
        assertEquals(1, calls)
        // A refused callback is not magically reported as success.
        assertFalse(confirmedStandingResume(clean.copy(config = null)) { calls += 1; true })
        assertEquals(1, calls)
    }

    @Test
    fun `every guard refuses on its own and never runs the resume callback`() {
        val cases =
            linkedMapOf(
                "unavailable" to (clean.copy(available = false) to StandingResumeRefusal.DISABLED),
                "switch off" to (clean.copy(enabled = false) to StandingResumeRefusal.DISABLED),
                "controller off" to (clean.copy(controllerEnabled = false) to StandingResumeRefusal.DISABLED),
                "no config" to (clean.copy(config = null) to StandingResumeRefusal.NOT_CONFIGURED),
                "revoked" to (clean.copy(config = config.copy(revoked = true)) to StandingResumeRefusal.NOT_CONFIGURED),
                "wall expired" to (clean.copy(nowWallMs = 10_000L) to StandingResumeRefusal.EXPIRED),
                "projected expired" to (clean.copy(expired = true) to StandingResumeRefusal.EXPIRED),
                "monotonic expired" to (clean.copy(nowElapsedMs = 5_000L) to StandingResumeRefusal.EXPIRED),
                "no runtime" to (clean.copy(runtimeId = null) to StandingResumeRefusal.NOT_READY),
                "other runtime binding" to (clean.copy(binding = binding.copy(runtimeId = "launch-old")) to StandingResumeRefusal.BINDING_CHANGED),
                "runtime not running" to (clean.copy(runtimeRunning = false) to StandingResumeRefusal.NOT_READY),
                "tool not ready" to (clean.copy(toolReady = false) to StandingResumeRefusal.NOT_READY),
                "disconnected" to (clean.copy(connectionConnected = false) to StandingResumeRefusal.CONNECTION),
                "stop entry" to (clean.copy(stopChannelVerified = false) to StandingResumeRefusal.STOP_CHANNEL),
                "pending" to (clean.copy(pending = PhonePending.Resuming) to StandingResumeRefusal.BUSY),
                "continuation in flight" to (clean.copy(continuationInFlight = true) to StandingResumeRefusal.BUSY),
                "wake-up sending" to (clean.copy(continuationSending = true) to StandingResumeRefusal.BUSY),
                "live grant" to (clean.copy(hasGrant = true) to StandingResumeRefusal.TASK_OWNED),
                "halt" to (clean.copy(hasHalt = true) to StandingResumeRefusal.TASK_OWNED),
                "lease" to (clean.copy(hasLease = true) to StandingResumeRefusal.TASK_OWNED),
                "retained task" to (clean.copy(hasRetainedTask = true) to StandingResumeRefusal.TASK_OWNED),
                "task identity" to (clean.copy(hasTaskOwner = true) to StandingResumeRefusal.TASK_OWNED),
                "mutation in flight" to (clean.copy(inFlightMutation = true) to StandingResumeRefusal.UNSETTLED),
                "pause settling" to (clean.copy(pausing = true) to StandingResumeRefusal.UNSETTLED),
                "core unknown" to (clean.copy(coreBlocked = true) to StandingResumeRefusal.UNCHECKED),
                "unknown reason" to (clean.copy(standingBlockReason = PhoneStandingBlockReason.UNKNOWN_OUTCOME) to StandingResumeRefusal.UNCHECKED),
                "lost reason" to (clean.copy(standingBlockReason = null) to StandingResumeRefusal.UNCHECKED),
                "halt may still apply" to (clean.copy(hasHalt = false, haltMayStillApply = true) to StandingResumeRefusal.UNCHECKED),
                "no block" to (clean.copy(standingBlocked = false, standingBlockReason = null) to StandingResumeRefusal.NOT_PAUSED),
            )
        cases.forEach { (name, case) ->
            val (facts, expected) = case
            assertEquals(name, expected, standingResumeRefusal(facts))
            var calls = 0
            assertFalse(name, confirmedStandingResume(facts) { calls += 1; true })
            assertEquals("$name callback", 0, calls)
        }
    }

    @Test
    fun `acknowledging the halt makes it resumable but a lost reason stays fail closed`() {
        assertNull(standingResumeRefusal(clean.copy(haltMayStillApply = true, haltAcknowledged = true)))
        assertEquals(
            StandingResumeRefusal.UNCHECKED,
            standingResumeRefusal(clean.copy(standingBlockReason = null, haltAcknowledged = true)),
        )
    }

    @Test
    fun `finite windows expire and a permanent scope never expires by time`() {
        val permanent =
            config.copy(
                windowEndsAtWallMs = PhoneTimeLimits.PERMANENT,
                perTaskMs = PhoneTimeLimits.PERMANENT,
            )
        assertNull(
            standingResumeRefusal(
                clean.copy(
                    config = permanent,
                    nowWallMs = PhoneTimeLimits.PERMANENT - 1,
                    nowElapsedMs = PhoneTimeLimits.PERMANENT - 1,
                    endsAtElapsedMs = PhoneTimeLimits.PERMANENT,
                ),
            ),
        )
        // A finite saved window remains a finite deadline even when the elapsed clock is huge.
        assertEquals(
            StandingResumeRefusal.EXPIRED,
            standingResumeRefusal(clean.copy(nowWallMs = 10_000L, endsAtElapsedMs = Long.MAX_VALUE)),
        )
    }

    private class FakeDriver(var runtimeId: String = "launch-7") : PhoneDriver {
        override fun capabilities(): PhoneCapabilities = PhoneCapabilities()

        override suspend fun connect(deadlineMs: Long): PhoneConnection =
            PhoneConnection("conn-$runtimeId", runtimeId)

        override suspend fun disconnect(deadlineMs: Long) = Unit

        override suspend fun observe(deadlineMs: Long): PhoneSnapshot = error("not used")

        override suspend fun capture(binding: PhoneObservationBinding, deadlineMs: Long): PhoneImage =
            error("not used")

        override suspend fun dispatch(request: PhoneDispatchRequest, deadlineMs: Long): PhoneDispatchReceipt =
            error("not used")
    }

    private fun scope(runtimeId: String) =
        PhoneStandingScope(
            runtimeId = runtimeId,
            sessionLabel = "ses-a",
            allowedPackages = listOf(targetPackage),
            allowedActions = setOf(PhoneActionKind.TAP),
            windowEndsMs = 5_000L + 9_000L,
            perTaskMs = 60_000L,
        )

    /** The same mapping the integration uses, read from a real controller state. */
    private fun gate(
        controller: PhoneController,
        binding: PhoneStandingBindingState,
    ): StandingResumeFacts {
        val core = controller.state.value
        return StandingResumeFacts(
            available = true,
            enabled = true,
            controllerEnabled = core.enabled,
            config = config,
            nowWallMs = 9_000L,
            nowElapsedMs = 1_000L,
            endsAtElapsedMs = 5_000L,
            expired = false,
            runtimeId = core.runtimeId,
            binding = binding,
            runtimeRunning = true,
            toolReady = true,
            connectionConnected = core.connection == PhoneConnectionState.CONNECTED,
            stopChannelVerified = true,
            pending = null,
            continuationInFlight = false,
            continuationSending = false,
            hasGrant = core.grant != null,
            hasHalt = false,
            hasLease = false,
            hasRetainedTask = false,
            hasTaskOwner = false,
            inFlightMutation = core.inFlightMutation,
            pausing = core.pausing,
            standingBlocked = core.standingBlocked,
            standingBlockReason = core.standingBlockReason,
            coreBlocked = core.blocked != null,
            haltMayStillApply = false,
            haltAcknowledged = false,
        )
    }

    @Test
    fun `the reported runtime-changed block resumes once and a fresh request starts in the scope`() = runTest {
        val driver = FakeDriver()
        val controller = PhoneController(driver = driver, clock = { 1_000L })
        controller.enable()
        assertTrue(controller.connect(1_000) is PhoneConnectionResult.Connected)
        assertTrue(controller.grantFromPreauthorization(scope("launch-7"), targetPackage) is PhoneStandingGrantResult.Granted)

        // Runtime restart: the controller revokes and records the exact runtime-changed block.
        driver.runtimeId = "launch-8"
        controller.onRuntimeChanged("launch-8")
        assertTrue(controller.state.value.standingBlocked)
        assertEquals(PhoneStandingBlockReason.RUNTIME_CHANGED, controller.state.value.standingBlockReason)
        // Find and connect again on the new runtime.
        assertTrue(controller.connect(1_000) is PhoneConnectionResult.Connected)
        // observeController clears a binding from the old runtime before this point; the resume keeps
        // whatever same-runtime binding remains and never rebinds by itself.
        val currentBinding = PhoneStandingBindingState()
        val facts = gate(controller, currentBinding)
        assertNull(standingResumeRefusal(facts))

        var calls = 0
        val resumed =
            confirmedStandingResume(facts) {
                calls += 1
                controller.resumeStandingForPerson(
                    PhoneStandingResume(
                        runtimeId = controller.state.value.runtimeId!!,
                        sessionLabel = currentBinding.sessionLabel ?: "",
                    ),
                )
            }
        assertTrue(resumed)
        assertEquals(1, calls)
        assertFalse(controller.state.value.standingBlocked)
        assertNull(controller.state.value.standingBlockReason)
        // The saved configuration and the same-runtime binding are unchanged by the resume.
        assertEquals(config, facts.config)
        assertEquals(currentBinding, facts.binding)

        // A fresh chat request can now start in the same saved scope on the new runtime.
        val granted = controller.grantFromPreauthorization(scope("launch-8"), targetPackage)
        assertTrue(granted is PhoneStandingGrantResult.Granted)
    }

    @Test
    fun `a refused gate leaves the real standing block and binding exactly as they were`() = runTest {
        val driver = FakeDriver()
        val controller = PhoneController(driver = driver, clock = { 1_000L })
        controller.enable()
        controller.connect(1_000)
        controller.blockStanding(PhoneStandingBlockReason.TAKEOVER)
        val before = controller.state.value
        val heldBinding = binding
        val facts = gate(controller, heldBinding).copy(binding = heldBinding.copy(runtimeId = "launch-old"))
        assertEquals(StandingResumeRefusal.BINDING_CHANGED, standingResumeRefusal(facts))
        var calls = 0
        assertFalse(confirmedStandingResume(facts) { calls += 1; controller.resumeStandingForPerson(PhoneStandingResume("launch-7", "ses-a")) })
        assertEquals(0, calls)
        assertEquals(before.standingBlocked, controller.state.value.standingBlocked)
        assertEquals(before.standingBlockReason, controller.state.value.standingBlockReason)
        assertEquals(heldBinding, binding)
    }

    @Test
    fun `resume also clears the ordinary pause under the same lock so the next policy does not deadlock`() = runTest {
        val driver = FakeDriver()
        val controller = PhoneController(driver = driver, clock = { 1_000L })
        controller.enable()
        controller.connect(1_000)
        controller.pause()
        controller.blockStanding(PhoneStandingBlockReason.NEED_USER)
        assertTrue(controller.state.value.paused)
        val facts = gate(controller, binding)
        // paused alone is not an unchecked unknown: the person explicitly resumes.
        assertNull(standingResumeRefusal(facts))
        assertTrue(
            confirmedStandingResume(facts) {
                controller.resumeStandingForPerson(PhoneStandingResume("launch-7", "ses-a"))
            },
        )
        assertFalse(controller.state.value.paused)
        assertFalse(controller.state.value.standingBlocked)
        assertTrue(controller.grantFromPreauthorization(scope("launch-7"), targetPackage) is PhoneStandingGrantResult.Granted)
    }
}
