package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneStandingBlockReason
import io.andchamber.app.phone.core.PhoneTimeLimits
import io.andchamber.app.phone.standing.PhoneStandingApp
import io.andchamber.app.phone.standing.PhoneStandingRefusal
import io.andchamber.app.ui.phone.PhoneToolStatus
import io.andchamber.app.ui.phone.PhoneUiLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneStandingConfigTest {
    private val target = PhoneStandingApp("Notes", "com.example.notes")
    private val config =
        PhoneStandingConfig(
            apps = listOf(target),
            actions = setOf(PhoneActionKind.TAP),
            windowEndsAtWallMs = 10_000L,
            perTaskMs = 60_000L,
        )
    private val emptyBinding = PhoneStandingBindingState()

    @Test
    fun `only the core helper defines the permanent sentinel`() {
        assertEquals(PhoneTimeLimits.PERMANENT, PhoneUiLimits.PERMANENT_MS)
        assertTrue(PhoneTimeLimits.isPermanent(PhoneUiLimits.PERMANENT_MS))
        assertFalse(PhoneTimeLimits.isPermanent(PhoneTimeLimits.PERMANENT - 1L))
    }

    @Test
    fun `a saved scope is configuration, not a lease`() {
        assertTrue(config.isActive(nowWallMs = 9_999L))
        assertFalse(config.isActive(nowWallMs = 10_000L))
        assertFalse(config.copy(revoked = true).isActive(nowWallMs = 1L))
        // The store holds the same immutable config; no grant/epoch field exists here at all.
        val store = MemoryPhoneIntegrationStore()
        assertNull(store.standing())
        store.setStanding(config)
        assertEquals(config, store.standing())
        store.setStanding(config.copy(revoked = true))
        assertEquals(true, store.standing()?.revoked)
    }

    @Test
    fun `first request binds a runtime and session and then rejects another session`() {
        assertNull(refusal(binding = emptyBinding, session = "ses-a"))
        val bound = PhoneStandingBindingState(runtimeId = "launch-1", sessionLabel = "ses-a")
        assertNull(refusal(binding = bound, session = "ses-a"))
        assertEquals(
            PhoneStandingRefusal.SESSION_MISMATCH,
            refusal(binding = bound, session = "ses-b"),
        )
        assertEquals(
            PhoneStandingRefusal.RUNTIME_CHANGED,
            refusal(binding = bound, session = "ses-a", runtimeId = "launch-2"),
        )
    }

    @Test
    fun `a live retained or blocked task is never silently replaced`() {
        assertEquals(
            PhoneStandingRefusal.TASK_ACTIVE,
            refusal(binding = emptyBinding, live = true),
        )
        assertEquals(
            PhoneStandingRefusal.TASK_ACTIVE,
            refusal(binding = emptyBinding, retained = true),
        )
        // A native stop block wins: the chat must wait for the person, not start over.
        assertEquals(
            PhoneStandingRefusal.STANDING_BLOCKED,
            refusal(binding = emptyBinding, blocked = true),
        )
        assertEquals(
            PhoneStandingRefusal.STANDING_BLOCKED,
            refusal(binding = emptyBinding, live = true, blocked = true),
        )
    }

    @Test
    fun `expired revoked and missing scopes are refused before any task fact`() {
        assertEquals(
            PhoneStandingRefusal.NOT_CONFIGURED,
            refusal(config = config.copy(revoked = true)),
        )
        assertEquals(
            PhoneStandingRefusal.EXPIRED,
            refusal(config = config.copy(windowEndsAtWallMs = 1_000L), nowWallMs = 1_000L),
        )
        assertEquals(PhoneStandingRefusal.NOT_CONFIGURED, refusal(config = null))
    }

    @Test
    fun `bounds are checked before the native store is changed`() {
        assertTrue(validStandingBounds(windowMs = 60_000L, perTaskMs = 60_000L))
        assertTrue(validStandingBounds(windowMs = 3_600_000L, perTaskMs = 900_000L))
        assertFalse(validStandingBounds(windowMs = 0L, perTaskMs = 60_000L))
        assertFalse(validStandingBounds(windowMs = 3_600_000L, perTaskMs = 0L))
        assertFalse(validStandingBounds(windowMs = 120_000L, perTaskMs = 180_000L))
        assertFalse(validStandingBounds(windowMs = STANDING_MAX_WINDOW_MS + 1, perTaskMs = 60_000L))
    }

    @Test
    fun `either duration can be permanent but arbitrary huge values are refused`() {
        assertTrue(validStandingBounds(PhoneTimeLimits.PERMANENT, PhoneTimeLimits.PERMANENT))
        assertTrue(validStandingBounds(PhoneTimeLimits.PERMANENT, perTaskMs = 900_000L))
        // A permanent per-task limit stays bounded by a finite standing window in the controller.
        assertTrue(validStandingBounds(windowMs = 3_600_000L, perTaskMs = PhoneTimeLimits.PERMANENT))
        // The explicit permanent task sentinel must pass the old perTask <= window check; the core
        // truncates the task to the 4-hour scope end, not to a wrapped number.
        assertTrue(validStandingBounds(windowMs = 4L * 60L * 60L * 1_000L, perTaskMs = PhoneTimeLimits.PERMANENT))
        assertFalse(validStandingBounds(PhoneTimeLimits.PERMANENT - 1L, perTaskMs = 60_000L))
        assertFalse(validStandingBounds(PhoneTimeLimits.PERMANENT, perTaskMs = PhoneTimeLimits.PERMANENT - 1L))
        assertFalse(validStandingBounds(windowMs = 120_000L, perTaskMs = 180_000L))
    }

    @Test
    fun `a permanent scope restores MAX without arithmetic and never expires by time`() {
        val permanent =
            config.copy(
                windowEndsAtWallMs = PhoneTimeLimits.PERMANENT,
                perTaskMs = PhoneTimeLimits.PERMANENT,
            )
        assertTrue(permanent.isActive(nowWallMs = 1L))
        assertTrue(permanent.isActive(nowWallMs = PhoneTimeLimits.PERMANENT - 1L))
        assertFalse(permanent.copy(revoked = true).isActive(nowWallMs = 1L))

        val restored =
            restoredStandingState(
                config = permanent,
                nowWallMs = 1_700_000_000_000L,
                nowElapsedMs = 123_456L,
            )
        assertEquals(PhoneTimeLimits.PERMANENT, restored.endsAtElapsedMs)
        assertFalse(restored.expired)
        assertEquals(PhoneTimeLimits.PERMANENT, PhoneTimeLimits.expiresAt(1_700_000_000_000L, PhoneTimeLimits.PERMANENT))
    }

    @Test
    fun `a finite scope restores a bounded monotonic end and an elapsed one stays expired`() {
        val nowWall = 1_700_000_000_000L
        val finite = config.copy(windowEndsAtWallMs = nowWall + 60_000L, perTaskMs = 60_000L)
        val restored = restoredStandingState(finite, nowWallMs = nowWall, nowElapsedMs = 5_000L)
        assertEquals(5_000L + 60_000L, restored.endsAtElapsedMs)
        assertFalse(restored.expired)

        val elapsed =
            restoredStandingState(
                finite.copy(windowEndsAtWallMs = nowWall - 1L),
                nowWallMs = nowWall,
                nowElapsedMs = 5_000L,
            )
        assertEquals(5_000L, elapsed.endsAtElapsedMs)
        assertTrue(elapsed.expired)
        assertFalse(elapsed.config.isActive(nowWall))
    }

    @Test
    fun `restore never overflows on a corrupt far-future finite end`() {
        val corrupt =
            config.copy(
                windowEndsAtWallMs = PhoneTimeLimits.PERMANENT - 1L,
                perTaskMs = 60_000L,
            )
        val restored = restoredStandingState(corrupt, nowWallMs = 1L, nowElapsedMs = 10L)
        assertTrue("a corrupt finite end must fail closed", restored.expired)
        assertEquals(10L, restored.endsAtElapsedMs)
        assertTrue(restored.endsAtElapsedMs < PhoneTimeLimits.PERMANENT)
    }

    @Test
    fun `the persisted config round-trips both permanent durations`() {
        val permanent =
            config.copy(
                windowEndsAtWallMs = PhoneTimeLimits.PERMANENT,
                perTaskMs = PhoneTimeLimits.PERMANENT,
            )
        val raw = PrefsPhoneIntegrationStore.encodeStanding(permanent)
        val decoded = PrefsPhoneIntegrationStore.decodeStanding(raw)
        assertEquals(permanent, decoded)

        val finiteWindowPermanentTask =
            config.copy(
                windowEndsAtWallMs = 1_700_000_060_000L,
                perTaskMs = PhoneTimeLimits.PERMANENT,
            )
        assertEquals(
            finiteWindowPermanentTask,
            PrefsPhoneIntegrationStore.decodeStanding(
                PrefsPhoneIntegrationStore.encodeStanding(finiteWindowPermanentTask),
            ),
        )

        // Finite and revoked configurations keep their old round-trip behavior.
        val finite = config.copy(windowEndsAtWallMs = 1_700_000_060_000L, perTaskMs = 900_000L)
        assertEquals(
            finite,
            PrefsPhoneIntegrationStore.decodeStanding(
                PrefsPhoneIntegrationStore.encodeStanding(finite),
            ),
        )
        val revoked = finite.copy(revoked = true)
        assertEquals(
            revoked,
            PrefsPhoneIntegrationStore.decodeStanding(
                PrefsPhoneIntegrationStore.encodeStanding(revoked),
            ),
        )

        // A huge number that is not the exact sentinel is not a permanent duration.
        val corrupt = permanent.copy(perTaskMs = PhoneTimeLimits.PERMANENT - 1L)
        assertNull(
            PrefsPhoneIntegrationStore.decodeStanding(
                PrefsPhoneIntegrationStore.encodeStanding(corrupt),
            ),
        )
        val corruptWindow = permanent.copy(windowEndsAtWallMs = PhoneTimeLimits.PERMANENT - 1L)
        assertNull(
            PrefsPhoneIntegrationStore.decodeStanding(
                PrefsPhoneIntegrationStore.encodeStanding(corruptWindow),
                nowWallMs = 1_700_000_000_000L,
            ),
        )
    }

    @Test
    fun `transitioning or unknown runtime is never reported ready`() {
        assertEquals(
            PhoneToolStatus.Ready,
            phoneRuntimeStatus(PhoneRuntimePresence.RUNNING, toolReady = true, restarting = false).tool,
        )
        assertEquals(
            PhoneToolStatus.NeedsRestart,
            phoneRuntimeStatus(PhoneRuntimePresence.RUNNING, toolReady = false, restarting = false).tool,
        )
        assertEquals(
            PhoneToolStatus.Unknown,
            phoneRuntimeStatus(PhoneRuntimePresence.RUNNING, toolReady = null, restarting = false).tool,
        )
        assertEquals(
            PhoneToolStatus.Unknown,
            phoneRuntimeStatus(PhoneRuntimePresence.TRANSITIONING, toolReady = true, restarting = false).tool,
        )
        assertEquals(
            PhoneToolStatus.Restarting,
            phoneRuntimeStatus(PhoneRuntimePresence.TRANSITIONING, toolReady = false, restarting = true).tool,
        )
        assertEquals(
            PhoneToolStatus.Restarting,
            phoneRuntimeStatus(PhoneRuntimePresence.RUNNING, toolReady = true, restarting = true).tool,
        )
        assertEquals(
            PhoneToolStatus.RuntimeNotRunning,
            phoneRuntimeStatus(PhoneRuntimePresence.NOT_RUNNING, toolReady = null, restarting = false).tool,
        )
    }

    @Test
    fun `a failed start never rolls back a newer unrelated task`() {
        assertTrue(standingRollbackTargets(7, liveEpoch = 7, retainedEpoch = null, identityMatchesRequestedKey = false))
        assertTrue(standingRollbackTargets(7, liveEpoch = null, retainedEpoch = 7, identityMatchesRequestedKey = false))
        assertTrue(standingRollbackTargets(7, liveEpoch = null, retainedEpoch = null, identityMatchesRequestedKey = true))
        // A newer live epoch or a different retained task is not this start's rollback target.
        assertFalse(standingRollbackTargets(7, liveEpoch = 8, retainedEpoch = null, identityMatchesRequestedKey = false))
        assertFalse(standingRollbackTargets(7, liveEpoch = 8, retainedEpoch = 6, identityMatchesRequestedKey = false))
        // Even the old retained/key still looking familiar must not beat a newer live owner.
        assertFalse(standingRollbackTargets(7, liveEpoch = 8, retainedEpoch = 7, identityMatchesRequestedKey = true))
        assertFalse(
            standingRollbackTargets(
                7,
                liveEpoch = null,
                retainedEpoch = 7,
                identityMatchesRequestedKey = false,
                identityOwnedByNewerTask = true,
            ),
        )
    }

    @Test
    fun `old route abort and continuation never claim a newer live owner`() {
        assertTrue(standingExactLiveOwner(7, liveEpoch = 7, identityMatchesRequestedKey = true, identityOwnedByNewerTask = false))
        assertFalse(standingExactLiveOwner(7, liveEpoch = 8, identityMatchesRequestedKey = true, identityOwnedByNewerTask = false))
        assertFalse(standingExactLiveOwner(7, liveEpoch = 7, identityMatchesRequestedKey = false, identityOwnedByNewerTask = true))
        assertFalse(standingExactLiveOwner(7, liveEpoch = null, identityMatchesRequestedKey = true, identityOwnedByNewerTask = false))
        // A continuation that could not publish its own binding still owns only the exact live epoch.
        assertTrue(
            standingExactLiveOwner(
                7,
                liveEpoch = 7,
                identityMatchesRequestedKey = false,
                identityOwnedByNewerTask = false,
                requireIdentity = false,
            ),
        )
    }

    @Test
    fun `first confirmed restart clears only the runtime block when everything is clean`() {
        assertTrue(restartAllowed())
    }

    @Test
    fun `restart auto-unlock rejects anything but a clean first unbound config`() {
        assertFalse(restartAllowed(confirmed = false))
        assertFalse(restartAllowed(restartRevision = 1, currentRevision = 2))
        assertFalse(restartAllowed(restartRevision = null))
        assertFalse(restartAllowed(active = false))
        assertFalse(restartAllowed(unbound = false))
        assertFalse(restartAllowed(blocked = false))
        assertFalse(restartAllowed(reason = null))
        assertFalse(restartAllowed(reason = PhoneStandingBlockReason.TAKEOVER))
        assertFalse(restartAllowed(reason = PhoneStandingBlockReason.UNKNOWN_OUTCOME))
        assertFalse(restartAllowed(enabled = false))
        assertFalse(restartAllowed(paused = true))
        assertFalse(restartAllowed(unknown = true))
        assertFalse(restartAllowed(runtimeId = null))
        assertFalse(restartAllowed(running = false))
        assertFalse(restartAllowed(toolReady = false))
        assertFalse(restartAllowed(live = true))
        assertFalse(restartAllowed(halt = true))
        assertFalse(restartAllowed(retained = true))
        assertFalse(restartAllowed(lease = true))
        assertFalse(restartAllowed(owner = true))
    }

    private fun restartAllowed(
        confirmed: Boolean = true,
        restartRevision: Long? = 3,
        currentRevision: Long = 3,
        active: Boolean = true,
        unbound: Boolean = true,
        blocked: Boolean = true,
        reason: PhoneStandingBlockReason? = PhoneStandingBlockReason.RUNTIME_CHANGED,
        enabled: Boolean = true,
        paused: Boolean = false,
        unknown: Boolean = false,
        runtimeId: String? = "launch-2",
        running: Boolean = true,
        toolReady: Boolean = true,
        live: Boolean = false,
        halt: Boolean = false,
        retained: Boolean = false,
        lease: Boolean = false,
        owner: Boolean = false,
    ): Boolean =
        confirmedRestartMayClearRuntimeBlock(
            restartConfirmed = confirmed,
            restartRevision = restartRevision,
            currentRevision = currentRevision,
            configActive = active,
            bindingNeverBound = unbound,
            standingBlocked = blocked,
            standingBlockReason = reason,
            controllerEnabled = enabled,
            controllerPaused = paused,
            controllerUnknownBlocked = unknown,
            runtimeId = runtimeId,
            runtimeRunning = running,
            toolReady = toolReady,
            hasLiveGrant = live,
            hasHalt = halt,
            hasRetainedTask = retained,
            hasLease = lease,
            hasTaskOwner = owner,
        )

    private fun refusal(
        config: PhoneStandingConfig? = this.config,
        binding: PhoneStandingBindingState = emptyBinding,
        runtimeId: String = "launch-1",
        session: String = "ses-a",
        live: Boolean = false,
        retained: Boolean = false,
        blocked: Boolean = false,
        nowWallMs: Long = 9_000L,
    ): PhoneStandingRefusal? =
        standingRequestRefusal(
            config = config,
            nowWallMs = nowWallMs,
            runtimeId = runtimeId,
            sessionId = session,
            binding = binding,
            hasLiveTask = live,
            hasRetainedTask = retained,
            blocked = blocked,
        )
}
