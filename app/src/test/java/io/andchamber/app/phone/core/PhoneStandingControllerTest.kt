package io.andchamber.app.phone.core

import io.andchamber.app.phone.standing.PhoneStandingGrantResult
import io.andchamber.app.phone.standing.PhoneStandingRefusal
import io.andchamber.app.phone.standing.PhoneStandingResume
import io.andchamber.app.phone.standing.PhoneStandingScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configure-once entry: a native-issued scope creates one bounded per-task lease, every request
 * gets a new epoch, and a stop/unknown/lock/connection/runtime event suspends standing requests until
 * a native person resume. The model can never construct the scope or the resume.
 */
class PhoneStandingControllerTest {
    private val target = "com.example.target"
    private val runtime = "launch-7"

    private class FakeDriver : PhoneDriver {
        override fun capabilities(): PhoneCapabilities = PhoneCapabilities()
        override suspend fun connect(deadlineMs: Long): PhoneConnection = PhoneConnection("conn-1", "launch-7")
        override suspend fun disconnect(deadlineMs: Long) = Unit
        override suspend fun observe(deadlineMs: Long): PhoneSnapshot = error("not used")
        override suspend fun capture(binding: PhoneObservationBinding, deadlineMs: Long): PhoneImage =
            error("not used")
        override suspend fun dispatch(request: PhoneDispatchRequest, deadlineMs: Long): PhoneDispatchReceipt =
            error("not used")
    }

    private class Fixture {
        var now = 10_000L
        val controller = PhoneController(driver = FakeDriver(), clock = { now })

        suspend fun connected(): Fixture {
            controller.enable()
            assertTrue(controller.connect(1_000) is PhoneConnectionResult.Connected)
            return this
        }
    }

    private fun scope(
        windowEndsMs: Long = 1_000_000L,
        perTaskMs: Long = 60_000L,
        packages: List<String> = listOf(target),
        actions: Set<PhoneActionKind> = setOf(PhoneActionKind.TAP),
        runtimeId: String = runtime,
    ) = PhoneStandingScope(
        runtimeId = runtimeId,
        sessionLabel = "ses-a",
        allowedPackages = packages,
        allowedActions = actions,
        windowEndsMs = windowEndsMs,
        perTaskMs = perTaskMs,
    )

    @Test
    fun `a standing scope creates a bounded new epoch without a per-task native grant`() = runTest {
        val fixture = Fixture().connected()

        val first = fixture.controller.grantFromPreauthorization(scope(), target)
        assertTrue(first is PhoneStandingGrantResult.Granted)
        val grant = (first as PhoneStandingGrantResult.Granted).grant
        assertEquals(target, grant.targetPackage)
        assertEquals(setOf(PhoneActionKind.TAP), grant.allowedActions)
        assertEquals(1L, grant.epoch)
        assertEquals(60_000L, grant.expiresAtMs - fixture.now)
        assertEquals(grant, fixture.controller.state.value.grant)

        fixture.now += 1_000
        val second = fixture.controller.grantFromPreauthorization(scope(), target)
        assertTrue(second is PhoneStandingGrantResult.Granted)
        assertEquals(2L, (second as PhoneStandingGrantResult.Granted).grant.epoch)
    }

    @Test
    fun `the per-task lease and the window both bound the grant`() = runTest {
        val fixture = Fixture().connected()

        val result =
            fixture.controller.grantFromPreauthorization(
                scope(windowEndsMs = fixture.now + 5_000, perTaskMs = 60_000),
                target,
            )

        val grant = (result as PhoneStandingGrantResult.Granted).grant
        assertEquals(5_000L, grant.expiresAtMs - fixture.now)
    }

    @Test
    fun `a paused task refuses a standing request until native resume`() = runTest {
        val fixture = Fixture().connected()
        assertTrue(fixture.controller.grantFromPreauthorization(scope(), target) is PhoneStandingGrantResult.Granted)
        fixture.controller.pause()

        val refused = fixture.controller.grantFromPreauthorization(scope(), target)
        assertEquals(PhoneStandingRefusal.PAUSED, (refused as PhoneStandingGrantResult.Refused).refusal)

        fixture.controller.resume()
        assertTrue(fixture.controller.grantFromPreauthorization(scope(), target) is PhoneStandingGrantResult.Granted)
    }

    @Test
    fun `a native block suspends the scope until the exact runtime resumes`() = runTest {
        val fixture = Fixture().connected()
        fixture.controller.blockStanding(PhoneStandingBlockReason.TAKEOVER)

        val blocked = fixture.controller.grantFromPreauthorization(scope(), target)
        assertEquals(PhoneStandingRefusal.STANDING_BLOCKED, (blocked as PhoneStandingGrantResult.Refused).refusal)
        assertTrue(fixture.controller.state.value.standingBlocked)
        assertEquals(PhoneStandingBlockReason.TAKEOVER, fixture.controller.state.value.standingBlockReason)

        assertFalse(
            fixture.controller.resumeStanding(
                PhoneStandingResume(runtimeId = "launch-other", sessionLabel = "ses-a"),
            ),
        )
        assertTrue(fixture.controller.state.value.standingBlocked)

        assertTrue(fixture.controller.resumeStanding(PhoneStandingResume(runtimeId = runtime, sessionLabel = "ses-a")))
        assertFalse(fixture.controller.state.value.standingBlocked)
        assertNull(fixture.controller.state.value.standingBlockReason)
        assertTrue(fixture.controller.grantFromPreauthorization(scope(), target) is PhoneStandingGrantResult.Granted)
    }

    @Test
    fun `lock and runtime change set a standing block`() = runTest {
        val fixture = Fixture().connected()
        fixture.controller.onDeviceLocked()
        assertTrue(fixture.controller.state.value.standingBlocked)
        assertEquals(PhoneStandingBlockReason.DEVICE_LOCKED, fixture.controller.state.value.standingBlockReason)

        fixture.controller.resumeStanding(PhoneStandingResume(runtimeId = runtime, sessionLabel = "ses-a"))
        fixture.controller.onRuntimeChanged("launch-8")
        assertTrue(fixture.controller.state.value.standingBlocked)
        assertEquals(PhoneStandingBlockReason.RUNTIME_CHANGED, fixture.controller.state.value.standingBlockReason)
    }

    @Test
    fun `a scope for another runtime or a forbidden target is refused`() = runTest {
        val fixture = Fixture().connected()

        val otherRuntime = fixture.controller.grantFromPreauthorization(scope(runtimeId = "launch-8"), target)
        assertEquals(
            PhoneStandingRefusal.RUNTIME_CHANGED,
            (otherRuntime as PhoneStandingGrantResult.Refused).refusal,
        )

        val host = fixture.controller.grantFromPreauthorization(scope(packages = listOf("io.andchamber.app")), "io.andchamber.app")
        assertEquals(PhoneStandingRefusal.TARGET_NOT_ALLOWED, (host as PhoneStandingGrantResult.Refused).refusal)

        val notInScope = fixture.controller.grantFromPreauthorization(scope(), "com.example.other")
        assertEquals(
            PhoneStandingRefusal.TARGET_NOT_ALLOWED,
            (notInScope as PhoneStandingGrantResult.Refused).refusal,
        )
    }

    @Test
    fun `an expired window or an invalid scope is refused`() = runTest {
        val fixture = Fixture().connected()

        val expired = fixture.controller.grantFromPreauthorization(scope(windowEndsMs = fixture.now), target)
        assertEquals(PhoneStandingRefusal.EXPIRED, (expired as PhoneStandingGrantResult.Refused).refusal)

        val invalid =
            fixture.controller.grantFromPreauthorization(
                scope(actions = emptySet()),
                target,
            )
        assertEquals(PhoneStandingRefusal.INVALID, (invalid as PhoneStandingGrantResult.Refused).refusal)

        listOf(0L, -1L, PhoneLimits.MAX_LEASE_MS + 1L).forEach { perTaskMs ->
            val badPerTask = fixture.controller.grantFromPreauthorization(scope(perTaskMs = perTaskMs), target)
            assertEquals(
                "perTaskMs=$perTaskMs",
                PhoneStandingRefusal.INVALID,
                (badPerTask as PhoneStandingGrantResult.Refused).refusal,
            )
        }
    }

    @Test
    fun `the old native grant path remains available and the strict resume does not clear pause`() = runTest {
        val fixture = Fixture().connected()
        val legacy = fixture.controller.grant(target, setOf(PhoneActionKind.TAP), 5_000)
        assertTrue(legacy is PhoneGrantResult.Granted)
        assertFalse(fixture.controller.standingBlocked())

        fixture.controller.blockStanding(PhoneStandingBlockReason.TAKEOVER)
        fixture.controller.pause()
        fixture.controller.resumeStanding(PhoneStandingResume(runtimeId = runtime, sessionLabel = "ses-a"))
        // The strict native resume clears only the standing standby, not the core pause.
        assertTrue(fixture.controller.state.value.paused)
    }

    @Test
    fun `a person continuation clears the standing block and pause in one publish`() = runTest {
        val fixture = Fixture().connected()
        assertTrue(fixture.controller.grantFromPreauthorization(scope(), target) is PhoneStandingGrantResult.Granted)
        fixture.controller.blockStanding(PhoneStandingBlockReason.TAKEOVER)
        fixture.controller.pause()
        val sequence = fixture.controller.state.value.sequence

        assertTrue(
            fixture.controller.resumeStandingForPerson(
                PhoneStandingResume(runtimeId = runtime, sessionLabel = "ses-a"),
            ),
        )
        val resumed = fixture.controller.state.value
        assertFalse(resumed.standingBlocked)
        assertNull(resumed.standingBlockReason)
        assertFalse("a person continuation also clears the ordinary pause", resumed.paused)
        // One publish, no intermediate half-resumed state is observable.
        assertEquals(sequence + 1, resumed.sequence)
        assertEquals(PhoneEvent.STANDING_RESUMED, resumed.lastEvent)
    }

    @Test
    fun `a person continuation for another runtime changes nothing`() = runTest {
        val fixture = Fixture().connected()
        fixture.controller.blockStanding(PhoneStandingBlockReason.TAKEOVER)
        fixture.controller.pause()
        val before = fixture.controller.state.value

        assertFalse(
            fixture.controller.resumeStandingForPerson(
                PhoneStandingResume(runtimeId = "launch-other", sessionLabel = "ses-a"),
            ),
        )
        val after = fixture.controller.state.value
        assertEquals(before, after)
        assertTrue(after.standingBlocked)
        assertTrue(after.paused)
    }

    @Test
    fun `a permanent scope with a permanent per-task never expires by time`() = runTest {
        val fixture = Fixture().connected()

        val result =
            fixture.controller.grantFromPreauthorization(
                scope(
                    windowEndsMs = PhoneTimeLimits.PERMANENT,
                    perTaskMs = PhoneTimeLimits.PERMANENT,
                ),
                target,
            )
        val grant = (result as PhoneStandingGrantResult.Granted).grant
        assertEquals(PhoneTimeLimits.PERMANENT, grant.expiresAtMs)

        fixture.now = PhoneTimeLimits.PERMANENT - 1
        fixture.controller.refresh()

        assertEquals("a permanent lease was treated as expired", grant, fixture.controller.state.value.grant)
        assertEquals(PhoneEvent.GRANTED, fixture.controller.state.value.lastEvent)
    }

    @Test
    fun `a permanent per-task in a finite window stays bounded by the remaining window`() = runTest {
        val fixture = Fixture().connected()
        val fourHours = 4 * 60 * 60 * 1_000L
        val windowEndsMs = fixture.now + fourHours

        val result =
            fixture.controller.grantFromPreauthorization(
                scope(windowEndsMs = windowEndsMs, perTaskMs = PhoneTimeLimits.PERMANENT),
                target,
            )
        val grant = (result as PhoneStandingGrantResult.Granted).grant

        assertEquals(windowEndsMs, grant.expiresAtMs)
        assertTrue(
            "a finite window must not be capped at the old 30 minute maximum",
            grant.expiresAtMs - fixture.now > PhoneLimits.MAX_LEASE_MS,
        )

        fixture.now = windowEndsMs
        fixture.controller.refresh()
        assertNull(fixture.controller.state.value.grant)
        assertEquals(PhoneEvent.LEASE_EXPIRED, fixture.controller.state.value.lastEvent)

        val expired = fixture.controller.grantFromPreauthorization(
            scope(windowEndsMs = windowEndsMs, perTaskMs = PhoneTimeLimits.PERMANENT),
            target,
        )
        assertEquals(
            PhoneStandingRefusal.EXPIRED,
            (expired as PhoneStandingGrantResult.Refused).refusal,
        )
    }

    @Test
    fun `a finite per-task in a permanent window stays a finite short lease`() = runTest {
        val fixture = Fixture().connected()

        val result =
            fixture.controller.grantFromPreauthorization(
                scope(
                    windowEndsMs = PhoneTimeLimits.PERMANENT,
                    perTaskMs = 60_000L,
                ),
                target,
            )
        val grant = (result as PhoneStandingGrantResult.Granted).grant

        assertEquals(60_000L, grant.expiresAtMs - fixture.now)
        fixture.now += 60_000L
        fixture.controller.refresh()
        assertNull(fixture.controller.state.value.grant)
        assertEquals(PhoneEvent.LEASE_EXPIRED, fixture.controller.state.value.lastEvent)
    }

    @Test
    fun `pause takeover lock connection loss and runtime change still stop a permanent scope`() = runTest {
        val permanent = scope(
            windowEndsMs = PhoneTimeLimits.PERMANENT,
            perTaskMs = PhoneTimeLimits.PERMANENT,
        )

        val paused = Fixture().connected()
        assertTrue(paused.controller.grantFromPreauthorization(permanent, target) is PhoneStandingGrantResult.Granted)
        paused.controller.pause()
        assertEquals(
            PhoneStandingRefusal.PAUSED,
            (paused.controller.grantFromPreauthorization(permanent, target) as PhoneStandingGrantResult.Refused).refusal,
        )

        val takeover = Fixture().connected()
        takeover.controller.blockStanding(PhoneStandingBlockReason.TAKEOVER)
        assertEquals(
            PhoneStandingRefusal.STANDING_BLOCKED,
            (takeover.controller.grantFromPreauthorization(permanent, target) as PhoneStandingGrantResult.Refused).refusal,
        )

        val locked = Fixture().connected()
        assertTrue(locked.controller.grantFromPreauthorization(permanent, target) is PhoneStandingGrantResult.Granted)
        locked.controller.onDeviceLocked()
        assertNull(locked.controller.state.value.grant)
        assertEquals(
            PhoneStandingRefusal.STANDING_BLOCKED,
            (locked.controller.grantFromPreauthorization(permanent, target) as PhoneStandingGrantResult.Refused).refusal,
        )

        val lost = Fixture().connected()
        assertTrue(lost.controller.grantFromPreauthorization(permanent, target) is PhoneStandingGrantResult.Granted)
        lost.controller.onConnectionLost()
        assertNull(lost.controller.state.value.grant)
        assertEquals(
            PhoneStandingRefusal.NOT_CONNECTED,
            (lost.controller.grantFromPreauthorization(permanent, target) as PhoneStandingGrantResult.Refused).refusal,
        )
        lost.controller.connect(1_000)
        assertEquals(
            "a reconnect must not restore a lease or clear the standing stop",
            PhoneStandingRefusal.STANDING_BLOCKED,
            (lost.controller.grantFromPreauthorization(permanent, target) as PhoneStandingGrantResult.Refused).refusal,
        )

        val changed = Fixture().connected()
        assertTrue(changed.controller.grantFromPreauthorization(permanent, target) is PhoneStandingGrantResult.Granted)
        changed.controller.onRuntimeChanged("launch-8")
        assertNull(changed.controller.state.value.grant)
        assertEquals(
            PhoneStandingBlockReason.RUNTIME_CHANGED,
            changed.controller.state.value.standingBlockReason,
        )
    }
}
