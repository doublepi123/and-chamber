package io.andchamber.app.phone.android

import io.andchamber.app.phone.tool.PhonePendingRequest
import io.andchamber.app.runtime.RuntimeState
import io.andchamber.app.ui.phone.PhoneDiagnosticTokens
import io.andchamber.app.ui.phone.PhoneLastAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused guards for the races that only show up at runtime: an intent that outlives its attempt, a
 * pairing result that arrives after cancel, a service action for an older epoch, and a grant
 * confirmation that must not change while the foreground stop entry comes up.
 */
class PhoneWiringGuardsTest {
    @Test
    fun `intent data carries kind, value and this process nonce`() {
        val data = phoneIntentData("pair", "pair_port", 7L, nonce = "a".repeat(32))
        assertTrue(data.startsWith("andchamber-phone://pair/pair_port/7/"))
        assertEquals("a".repeat(32), phoneIntentNonce(data))
        assertNull(phoneIntentNonce("https://example.com/x"))
        assertNull(phoneIntentNonce(null))
    }

    @Test
    fun `two attempts never share a pending intent identity`() {
        val first = phoneIntentData("service", "pause", 3L, nonce = "b".repeat(32))
        val second = phoneIntentData("service", "pause", 4L, nonce = "b".repeat(32))
        val third = phoneIntentData("service", "pause", 3L, nonce = "c".repeat(32))
        assertTrue(first != second)
        assertTrue(first != third)
        assertTrue(PhoneProcessNonce.matches(PhoneProcessNonce.value))
        assertFalse(PhoneProcessNonce.matches("d".repeat(32)))
        assertFalse(PhoneProcessNonce.matches(null))
    }

    @Test
    fun `a late pairing result is ignored after cancel, disable or a newer attempt`() {
        assertTrue(pairingResultIsCurrent(5, 5, draftGeneration = 2, currentGeneration = 2, enabled = true))
        assertFalse(pairingResultIsCurrent(5, null, 2, 2, true))
        assertFalse(pairingResultIsCurrent(5, 6, 2, 2, true))
        assertFalse(pairingResultIsCurrent(5, 5, 2, 3, true))
        assertFalse(pairingResultIsCurrent(5, 5, 2, null, true))
        assertFalse(pairingResultIsCurrent(5, 5, 2, 2, false))
    }

    @Test
    fun `the notification gate needs the app-op, the permission and a usable channel`() {
        // Allowed app-op, granted permission, usable channel.
        assertEquals(
            PhoneNotificationsGate.AVAILABLE,
            phoneNotificationsGate(
                systemEnabled = true,
                permissionGranted = true,
                appOpMode = PhonePostNotificationMode.ALLOWED,
                channelAvailable = true,
            ),
        )
        // An independent app-op denial blocks even with the runtime permission granted.
        listOf(
            PhonePostNotificationMode.IGNORED,
            PhonePostNotificationMode.ERRORED,
            // Foreground-only is not a stop entry another app can see while it is in front.
            PhonePostNotificationMode.FOREGROUND,
        ).forEach { mode ->
            assertEquals(
                "app-op $mode must not count as cross-app availability",
                PhoneNotificationsGate.NOTIFICATIONS_DISABLED,
                phoneNotificationsGate(true, true, mode, true),
            )
        }
        // MODE_DEFAULT follows the real permission only.
        assertEquals(
            PhoneNotificationsGate.AVAILABLE,
            phoneNotificationsGate(true, true, PhonePostNotificationMode.DEFAULT, true),
        )
        assertEquals(
            PhoneNotificationsGate.NOTIFICATIONS_DISABLED,
            phoneNotificationsGate(true, false, PhonePostNotificationMode.DEFAULT, true),
        )
        // Permission missing, platform switch off, channel off.
        assertEquals(
            PhoneNotificationsGate.NOTIFICATIONS_DISABLED,
            phoneNotificationsGate(true, false, PhonePostNotificationMode.ALLOWED, true),
        )
        assertEquals(
            PhoneNotificationsGate.NOTIFICATIONS_DISABLED,
            phoneNotificationsGate(false, true, PhonePostNotificationMode.ALLOWED, true),
        )
        assertEquals(
            PhoneNotificationsGate.CHANNEL_DISABLED,
            phoneNotificationsGate(true, true, PhonePostNotificationMode.ALLOWED, false),
        )
        // Unreadable app-op is unknown, which callers treat as unavailable (fail closed).
        assertEquals(
            PhoneNotificationsGate.UNKNOWN,
            phoneNotificationsGate(true, true, null, true),
        )
        // The raw mode helper: DEFAULT only with the permission, everything except ALLOWED otherwise no.
        assertTrue(postNotificationAppOpAllows(PhonePostNotificationMode.ALLOWED, true))
        assertTrue(postNotificationAppOpAllows(PhonePostNotificationMode.DEFAULT, true))
        assertFalse(postNotificationAppOpAllows(PhonePostNotificationMode.DEFAULT, false))
        assertFalse(postNotificationAppOpAllows(PhonePostNotificationMode.IGNORED, true))
        assertFalse(postNotificationAppOpAllows(PhonePostNotificationMode.ERRORED, true))
        assertFalse(postNotificationAppOpAllows(PhonePostNotificationMode.FOREGROUND, true))
        assertFalse(postNotificationAppOpAllows(PhonePostNotificationMode.UNKNOWN, true))
    }

    @Test
    fun `the notification return action needs this process nonce and the live epoch`() {
        assertTrue(returnActionAccepted(nonceMatches = true, actionEpoch = 9, liveEpoch = 9))
        assertFalse(returnActionAccepted(nonceMatches = false, actionEpoch = 9, liveEpoch = 9))
        // An older action must never revoke a newer lease.
        assertFalse(returnActionAccepted(nonceMatches = true, actionEpoch = 8, liveEpoch = 9))
        // No live lease: nothing to stop; the screen is reachable from the app entry.
        assertFalse(returnActionAccepted(nonceMatches = true, actionEpoch = 9, liveEpoch = null))
    }

    @Test
    fun `a service action only applies to the live lease and stops a stale instance`() {
        assertTrue(serviceActionIsCurrent(9, 9))
        assertFalse(serviceActionIsCurrent(9, 10))
        assertFalse(serviceActionIsCurrent(9, null))
        assertFalse(serviceShouldStop(9, 9))
        assertTrue(serviceShouldStop(9, 10))
        assertTrue(serviceShouldStop(9, null))
    }

    @Test
    fun `blocker tokens match the screen's diagnostic tokens`() {
        // The banner and the grant gate read these exact strings; drift would silently disable both.
        assertEquals(
            PhoneDiagnosticTokens.RUNTIME_RESTART_REQUIRED,
            PhoneAgentToolBlocker.RUNTIME_RESTART_REQUIRED.token,
        )
        assertEquals(
            PhoneDiagnosticTokens.RUNTIME_NOT_RUNNING,
            PhoneAgentToolBlocker.RUNTIME_NOT_RUNNING.token,
        )
        assertEquals("screen_locked", PhoneDiagnosticTokens.SCREEN_LOCKED)
        assertEquals("target_unavailable", PhoneDiagnosticTokens.TARGET_UNAVAILABLE)
    }

    @Test
    fun `the phone plugin switch is sampled, never captured`() {
        val store = MemoryPhoneIntegrationStore()
        val enabledNow = phonePluginEnabledNow(store)
        assertFalse("off by default", enabledNow())
        store.setEnabled(true)
        assertTrue("a toggle is visible to the next sample", enabledNow())
        store.setEnabled(false)
        assertFalse(enabledNow())
    }

    @Test
    fun `production probes map runtime state and tool readiness without inventing a blocker`() {
        var state: RuntimeState = RuntimeState.Running("http://127.0.0.1:3000", 3000, 1L)
        var ready = false
        val probes = RuntimePhoneToolReadiness(runtimeState = { state }, toolReadyProbe = { ready })

        assertEquals(PhoneRuntimePresence.RUNNING, probes.runtimePresence())
        assertEquals(false, probes.toolReady())
        assertEquals(
            PhoneAgentToolBlocker.RUNTIME_RESTART_REQUIRED,
            agentToolBlocker(enabled = true, probes.runtimePresence(), probes.toolReady()),
        )

        ready = true
        assertNull(agentToolBlocker(enabled = true, probes.runtimePresence(), probes.toolReady()))

        state = RuntimeState.Stopped
        assertEquals(PhoneRuntimePresence.NOT_RUNNING, probes.runtimePresence())
        assertEquals(
            PhoneAgentToolBlocker.RUNTIME_NOT_RUNNING,
            agentToolBlocker(enabled = true, probes.runtimePresence(), probes.toolReady()),
        )

        state = RuntimeState.Starting("server")
        assertEquals(PhoneRuntimePresence.TRANSITIONING, probes.runtimePresence())
        assertNull(agentToolBlocker(enabled = true, probes.runtimePresence(), probes.toolReady()))
    }

    @Test
    fun `a throwing readiness probe is unknown, not a restart request`() {
        val probes =
            RuntimePhoneToolReadiness(
                runtimeState = { RuntimeState.Running("u", 3000, 1L) },
                toolReadyProbe = { error("binding unavailable") },
            )
        assertNull(probes.toolReady())
        assertNull(agentToolBlocker(enabled = true, probes.runtimePresence(), probes.toolReady()))
    }

    @Test
    fun `only the current missing step is shown, and it clears by itself`() {
        assertNull(agentToolBlocker(enabled = false, presence = PhoneRuntimePresence.RUNNING, toolReady = false))
        assertEquals(
            PhoneAgentToolBlocker.RUNTIME_NOT_RUNNING,
            agentToolBlocker(enabled = true, presence = PhoneRuntimePresence.NOT_RUNNING, toolReady = null),
        )
        assertEquals(
            PhoneAgentToolBlocker.RUNTIME_RESTART_REQUIRED,
            agentToolBlocker(enabled = true, presence = PhoneRuntimePresence.RUNNING, toolReady = false),
        )
        assertNull(agentToolBlocker(enabled = true, presence = PhoneRuntimePresence.RUNNING, toolReady = true))
        assertNull(agentToolBlocker(enabled = true, presence = PhoneRuntimePresence.TRANSITIONING, toolReady = false))
        assertNull(agentToolBlocker(enabled = true, presence = PhoneRuntimePresence.RUNNING, toolReady = null))
        assertEquals("runtime_not_running", PhoneAgentToolBlocker.RUNTIME_NOT_RUNNING.token)
        assertEquals("runtime_restart_required", PhoneAgentToolBlocker.RUNTIME_RESTART_REQUIRED.token)
    }

    @Test
    fun `a halt keeps may-still-apply and user-checked across later stops`() {
        assertEquals(
            PhoneLastAction.MayStillApply,
            latchedHaltLastAction(
                existing = PhoneLastAction.MayStillApply,
                actionInFlight = false,
                derived = PhoneLastAction.NoneInFlight,
                acknowledged = false,
            ),
        )
        assertEquals(
            PhoneLastAction.MayStillApply,
            latchedHaltLastAction(
                existing = null,
                actionInFlight = true,
                derived = PhoneLastAction.NoneInFlight,
                acknowledged = false,
            ),
        )
        assertEquals(
            PhoneLastAction.UserChecked,
            latchedHaltLastAction(
                existing = PhoneLastAction.UserChecked,
                actionInFlight = false,
                derived = PhoneLastAction.NoneInFlight,
                acknowledged = true,
            ),
        )
        assertEquals(
            PhoneLastAction.NoneInFlight,
            latchedHaltLastAction(
                existing = null,
                actionInFlight = false,
                derived = PhoneLastAction.NoneInFlight,
                acknowledged = false,
            ),
        )
    }

    @Test
    fun `takeover always drops the lease but still opens a remembered task`() {
        assertEquals(
            PhoneTakeoverPlan(revokeGrant = true, launchTarget = "com.example.notes"),
            takeoverPlan(hasGrant = true, rememberedTarget = "com.example.notes"),
        )
        // The grant is already gone but the task is remembered: the button must still open the app.
        assertEquals(
            PhoneTakeoverPlan(revokeGrant = false, launchTarget = "com.example.notes"),
            takeoverPlan(hasGrant = false, rememberedTarget = "com.example.notes"),
        )
        assertNull(takeoverPlan(hasGrant = false, rememberedTarget = null).launchTarget)
    }

    @Test
    fun `returning to the host drops a paused lease too`() {
        // Pause is not an exemption: returning to AndChamber stops Observe/Capture as well.
        assertTrue(hostReturnDropsLease(hasGrant = true, paused = true))
        assertTrue(hostReturnDropsLease(hasGrant = true, paused = false))
        assertFalse(hostReturnDropsLease(hasGrant = false, paused = true))
    }

    @Test
    fun `a proposal that changes while the stop entry comes up is not consumed`() =
        runTest {
            var revisionNow: Long? = 3L
            var consumed = false
            var published: String? = null
            val gate = CompletableDeferred<Unit>()
            val routing =
                async {
                    routeAfterStopEntry(
                        epoch = 7,
                        frozenRevision = 3L,
                        frozenResumeSession = null,
                        awaitStopEntry = {
                            gate.await()
                            true
                        },
                        currentEpoch = { 7L },
                        currentRevision = { revisionNow },
                        consume = {
                            consumed = true
                            PhonePendingRequest(3, "ses-a", "write", "com.example.notes", 1)
                        },
                        publish = { _, label ->
                            published = label
                            true
                        },
                    )
                }
            // The person confirmed revision 3; the agent replaces it while the service comes up.
            revisionNow = 4L
            gate.complete(Unit)
            assertEquals(PhoneGrantRouting.ConfirmationChanged, routing.await())
            assertFalse("the newer proposal must not be consumed", consumed)
            assertNull("no session may be bound", published)
        }

    @Test
    fun `the frozen proposal is the one published after the wait`() =
        runTest {
            var published: Pair<Long, String?>? = null
            val gate = CompletableDeferred<Unit>()
            val routing =
                async {
                    routeAfterStopEntry(
                        epoch = 9,
                        frozenRevision = 3L,
                        frozenResumeSession = null,
                        awaitStopEntry = {
                            gate.await()
                            true
                        },
                        currentEpoch = { 9L },
                        currentRevision = { 3L },
                        consume = { PhonePendingRequest(3, "ses-a", "write", "com.example.notes", 1) },
                        publish = { epoch, label ->
                            published = epoch to label
                            true
                        },
                    )
                }
            gate.complete(Unit)
            assertEquals(PhoneGrantRouting.Ready("ses-a"), routing.await())
            assertEquals(9L to "ses-a", published)
        }

    @Test
    fun `an epoch change during the wait fails before consuming or publishing`() =
        runTest {
            var epochNow: Long? = 10L
            var consumed = false
            var published = false
            val gate = CompletableDeferred<Unit>()
            val routing =
                async {
                    routeAfterStopEntry(
                        epoch = 10,
                        frozenRevision = 3L,
                        frozenResumeSession = null,
                        awaitStopEntry = {
                            gate.await()
                            true
                        },
                        currentEpoch = { epochNow },
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
            epochNow = 11L
            gate.complete(Unit)
            assertEquals(PhoneGrantRouting.EpochChanged, routing.await())
            assertFalse(consumed)
            assertFalse(published)
        }

    @Test
    fun `a manual confirmation does not bind a proposal that appears during the wait`() =
        runTest {
            var revisionNow: Long? = null
            var published = false
            val gate = CompletableDeferred<Unit>()
            val routing =
                async {
                    routeAfterStopEntry(
                        epoch = 5,
                        frozenRevision = null,
                        frozenResumeSession = null,
                        awaitStopEntry = {
                            gate.await()
                            true
                        },
                        currentEpoch = { 5L },
                        currentRevision = { revisionNow },
                        consume = { error("a manual task must not consume a proposal") },
                        publish = { _, _ ->
                            published = true
                            true
                        },
                    )
                }
            revisionNow = 6L
            gate.complete(Unit)
            assertEquals(PhoneGrantRouting.ConfirmationChanged, routing.await())
            assertFalse(published)
        }

    @Test
    fun `no stop entry means no consume and no publish`() =
        runTest {
            var consumed = false
            var published = false
            val routing =
                routeAfterStopEntry(
                    epoch = 2,
                    frozenRevision = null,
                    frozenResumeSession = null,
                    awaitStopEntry = { false },
                    currentEpoch = { 2L },
                    currentRevision = { null },
                    consume = {
                        consumed = true
                        null
                    },
                    publish = { _, _ ->
                        published = true
                        true
                    },
                )
            assertEquals(PhoneGrantRouting.ServiceUnavailable, routing)
            assertFalse(consumed)
            assertFalse(published)
        }
}
