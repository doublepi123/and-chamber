package io.andchamber.app.phone.core

import io.andchamber.app.phone.standing.PhoneStandingGrantResult
import io.andchamber.app.phone.standing.PhoneStandingRefusal
import io.andchamber.app.phone.standing.PhoneStandingScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The person's explicit "authorize all apps" standing choice: the scope carries no package list, the
 * target policy runs in all-apps mode (host, Settings and installers allowed; system UI, keyguard and
 * `android` always refused), and the grant freezes that mode for every later re-check.
 */
class PhoneStandingAllAppsControllerTest {
    private val host = "io.andchamber.app"
    private val runtime = "launch-7"

    /** A driver that really returns the observed package, so policy re-checks can be exercised. */
    private class PackageDriver(
        var packageName: String,
    ) : PhoneDriver {
        override fun capabilities(): PhoneCapabilities =
            PhoneCapabilities(observe = true, capture = true, tap = true, swipe = true, input = true, back = true)

        override suspend fun connect(deadlineMs: Long): PhoneConnection = PhoneConnection("conn-1", "launch-7")

        override suspend fun disconnect(deadlineMs: Long) = Unit

        override suspend fun observe(deadlineMs: Long): PhoneSnapshot {
            // A small WebView-like page: eight bounded nodes in one package, as the host's own
            // chat page really exposes (probe: 2890 bytes, 8 nodes).
            val nodes = (0 until 8).map { index ->
                PhoneNode(
                    ref = "n$index",
                    role = "view",
                    label = "row $index",
                    bounds = PhoneRect(0, index * 100, 1080, index * 100 + 80),
                    clickable = index == 0,
                )
            }
            return PhoneSnapshot(
                packageName = packageName,
                windowId = "window-1",
                geometry = PhoneGeometry(1080, 2400, 0),
                nodes = nodes,
                refs = nodes.associate { it.ref to it.bounds },
            )
        }

        override suspend fun capture(
            binding: PhoneObservationBinding,
            deadlineMs: Long,
        ): PhoneImage = PhoneImage(byteArrayOf(1), "image/png", 1080, 2400)

        override suspend fun dispatch(
            request: PhoneDispatchRequest,
            deadlineMs: Long,
        ): PhoneDispatchReceipt {
            dispatchCalls += 1
            return PhoneDispatchReceipt.Sent
        }

        var dispatchCalls: Int = 0
    }

    private class Fixture(packageName: String) {
        var now = 10_000L
        val driver = PackageDriver(packageName)
        val controller = PhoneController(driver = driver, clock = { now })

        suspend fun connected() = apply {
            controller.enable()
            assertTrue(controller.connect(1_000) is PhoneConnectionResult.Connected)
        }

        fun nextRequestId(): String = "r-${++requests}"
        private var requests = 0
    }

    private fun allAppsScope(
        packages: List<String> = emptyList(),
        allApps: Boolean = true,
    ) = PhoneStandingScope(
        runtimeId = runtime,
        sessionLabel = "ses-a",
        allowedPackages = packages,
        allowedActions = setOf(PhoneActionKind.TAP),
        windowEndsMs = 1_000_000L,
        perTaskMs = 60_000L,
        allApps = allApps,
    )

    @Test
    fun `an all apps scope grants the host and settings without a list`() = runTest {
        listOf(host, "com.android.settings", "com.android.permissioncontroller", "com.google.android.settings", "com.example.bank")
            .forEach { target ->
                val fixture = Fixture(target).connected()
                val result = fixture.controller.grantFromPreauthorization(allAppsScope(), target)
                assertTrue("$target must be grantable in all-apps mode", result is PhoneStandingGrantResult.Granted)
                assertTrue((result as PhoneStandingGrantResult.Granted).grant.allApps)
            }
    }

    @Test
    fun `an all apps scope refuses the secure surfaces and android itself`() = runTest {
        listOf("android", "com.android.systemui", "com.android.keyguard", "com.android.keyguard.something")
            .forEach { target ->
                val fixture = Fixture(target).connected()
                val result = fixture.controller.grantFromPreauthorization(allAppsScope(), target)
                assertEquals(
                    "$target must stay refused",
                    PhoneStandingRefusal.TARGET_NOT_ALLOWED,
                    (result as PhoneStandingGrantResult.Refused).refusal,
                )
                assertNull(fixture.controller.state.value.grant)
            }
        // A malformed package is still an invalid request, not an allowed target.
        val fixture = Fixture(host).connected()
        assertEquals(
            PhoneStandingRefusal.TARGET_NOT_ALLOWED,
            (fixture.controller.grantFromPreauthorization(allAppsScope(), "not a package") as PhoneStandingGrantResult.Refused).refusal,
        )
    }

    @Test
    fun `the two scope shapes may not mix`() = runTest {
        val fixture = Fixture(host).connected()
        // all-apps with a package list is invalid shape.
        assertEquals(
            PhoneStandingRefusal.INVALID,
            (fixture.controller.grantFromPreauthorization(allAppsScope(listOf(host)), host) as PhoneStandingGrantResult.Refused).refusal,
        )
        // Explicit mode without a list stays invalid, exactly as before.
        assertEquals(
            PhoneStandingRefusal.INVALID,
            (fixture.controller.grantFromPreauthorization(allAppsScope(packages = emptyList(), allApps = false), host) as PhoneStandingGrantResult.Refused).refusal,
        )
        // Explicit mode still refuses the host by name even when it is listed.
        assertEquals(
            PhoneStandingRefusal.TARGET_NOT_ALLOWED,
            (fixture.controller.grantFromPreauthorization(allAppsScope(listOf(host), allApps = false), host) as PhoneStandingGrantResult.Refused).refusal,
        )
    }

    @Test
    fun `a frozen all apps grant re-checks the host with the same mode`() = runTest {
        val fixture = Fixture(host).connected()
        val grant =
            (fixture.controller.grantFromPreauthorization(allAppsScope(), host) as PhoneStandingGrantResult.Granted).grant
        assertEquals(host, grant.targetPackage)
        assertTrue("the grant must freeze the all-apps mode", grant.allApps)

        // Observe: the snapshot re-check uses the frozen mode, so the host is not TARGET_FORBIDDEN.
        val observed =
            fixture.controller.execute(
                PhoneAgentRequest(
                    requestId = fixture.nextRequestId(),
                    epoch = grant.epoch,
                    sessionLabel = "ses-a",
                    action = PhoneAgentAction.Observe,
                ),
            )
        assertTrue("host observe must use the frozen all-apps mode: $observed", observed is PhoneCallResult.Observed)
        val observation = (observed as PhoneCallResult.Observed).observation
        assertEquals(host, observation.binding.packageName)
        assertEquals("the real host page is a small 8-node WebView-like tree", 8, observation.nodes.size)
        assertEquals(8, observation.refs.size)
        assertEquals(observation.nodes.map { it.ref }.toSet(), observation.refs.keys)

        val tap =
            fixture.controller.execute(
                PhoneAgentRequest(
                    requestId = fixture.nextRequestId(),
                    epoch = grant.epoch,
                    sessionLabel = "ses-a",
                    action = PhoneAgentAction.TapRef("n1"),
                    observation = observation.binding,
                ),
            )
        assertTrue("host tap must use the frozen all-apps mode: $tap", tap is PhoneCallResult.Dispatched)
    }

    @Test
    fun `an explicit grant never widens to the host on a later re-check`() = runTest {
        val fixture = Fixture(host).connected()
        // An ordinary grant for a normal package keeps explicit-list policy: the driver reports the
        // host, so the observation is refused instead of being accepted.
        val grant = fixture.controller.grant("com.example.target", setOf(PhoneActionKind.TAP), 60_000L)
        val epoch = (grant as PhoneGrantResult.Granted).grant.epoch
        assertFalse(grant.grant.allApps)

        val observed =
            fixture.controller.execute(
                PhoneAgentRequest(
                    requestId = fixture.nextRequestId(),
                    epoch = epoch,
                    sessionLabel = "ses-a",
                    action = PhoneAgentAction.Observe,
                ),
            )
        assertEquals(
            PhoneErrorCode.TARGET_MISMATCH,
            (observed as PhoneCallResult.Refused).error,
            // The package mismatch is the first gate; the host itself must never be observed.
        )
        assertNull(fixture.controller.state.value.observation)
    }

    @Test
    fun `an all apps task follows an allowed handoff and acts on the fresh snapshot`() = runTest {
        val fixture = Fixture(host).connected()
        val grant =
            (fixture.controller.grantFromPreauthorization(allAppsScope(), host) as PhoneStandingGrantResult.Granted).grant

        // The launched app hands off to QQ's OAuth page: a different but allowed package.
        fixture.driver.packageName = "com.tencent.mobileqq"
        val observed =
            fixture.controller.execute(
                PhoneAgentRequest(
                    requestId = fixture.nextRequestId(),
                    epoch = grant.epoch,
                    sessionLabel = "ses-a",
                    action = PhoneAgentAction.Observe,
                ),
            )
        assertTrue("the handoff must be observed: $observed", observed is PhoneCallResult.Observed)
        val observation = (observed as PhoneCallResult.Observed).observation
        assertEquals("the observation keeps the real package, not the launched one", "com.tencent.mobileqq", observation.binding.packageName)
        assertEquals("com.tencent.mobileqq", fixture.controller.state.value.observation?.binding?.packageName)

        val tap =
            fixture.controller.execute(
                PhoneAgentRequest(
                    requestId = fixture.nextRequestId(),
                    epoch = grant.epoch,
                    sessionLabel = "ses-a",
                    action = PhoneAgentAction.TapRef("n0"),
                    observation = observation.binding,
                ),
            )
        assertTrue("following is acted on through a fresh snapshot: $tap", tap is PhoneCallResult.Dispatched)
        assertEquals(1, fixture.driver.dispatchCalls)
    }

    @Test
    fun `an all apps task never follows a secure surface`() = runTest {
        listOf("com.android.systemui", "com.android.keyguard", "android").forEach { surface ->
            val fixture = Fixture(host).connected()
            val grant =
                (fixture.controller.grantFromPreauthorization(allAppsScope(), host) as PhoneStandingGrantResult.Granted).grant
            fixture.driver.packageName = surface
            val observed =
                fixture.controller.execute(
                    PhoneAgentRequest(
                        requestId = fixture.nextRequestId(),
                        epoch = grant.epoch,
                        sessionLabel = "ses-a",
                        action = PhoneAgentAction.Observe,
                    ),
                )
            assertEquals(
                surface,
                PhoneErrorCode.TARGET_FORBIDDEN,
                (observed as PhoneCallResult.Refused).error,
            )
            assertNull(fixture.controller.state.value.observation)
        }
    }

    @Test
    fun `an explicit scope still refuses another allowed package`() = runTest {
        val fixture = Fixture("com.example.target").connected()
        val scope =
            PhoneStandingScope(
                runtimeId = runtime,
                sessionLabel = "ses-a",
                allowedPackages = listOf("com.example.target"),
                allowedActions = setOf(PhoneActionKind.TAP),
                windowEndsMs = 1_000_000L,
                perTaskMs = 60_000L,
            )
        val grant =
            (fixture.controller.grantFromPreauthorization(scope, "com.example.target") as PhoneStandingGrantResult.Granted).grant
        fixture.driver.packageName = "com.tencent.mobileqq"
        val observed =
            fixture.controller.execute(
                PhoneAgentRequest(
                    requestId = fixture.nextRequestId(),
                    epoch = grant.epoch,
                    sessionLabel = "ses-a",
                    action = PhoneAgentAction.Observe,
                ),
            )
        assertEquals(PhoneErrorCode.TARGET_MISMATCH, (observed as PhoneCallResult.Refused).error)
        assertNull(fixture.controller.state.value.observation)
    }

    @Test
    fun `a stale binding after an app switch cannot act on the old page`() = runTest {
        val fixture = Fixture("com.example.one").connected()
        val grant =
            (fixture.controller.grantFromPreauthorization(allAppsScope(), "com.example.one") as PhoneStandingGrantResult.Granted).grant

        val first =
            fixture.controller.execute(
                PhoneAgentRequest(
                    requestId = fixture.nextRequestId(),
                    epoch = grant.epoch,
                    sessionLabel = "ses-a",
                    action = PhoneAgentAction.Observe,
                ),
            ) as PhoneCallResult.Observed

        // The app switches again: the old page's binding is no longer the current observation.
        fixture.driver.packageName = "com.example.two"
        val second =
            fixture.controller.execute(
                PhoneAgentRequest(
                    requestId = fixture.nextRequestId(),
                    epoch = grant.epoch,
                    sessionLabel = "ses-a",
                    action = PhoneAgentAction.Observe,
                ),
            ) as PhoneCallResult.Observed
        assertEquals("com.example.two", second.observation.binding.packageName)

        val staleTap =
            fixture.controller.execute(
                PhoneAgentRequest(
                    requestId = fixture.nextRequestId(),
                    epoch = grant.epoch,
                    sessionLabel = "ses-a",
                    action = PhoneAgentAction.TapRef("n0"),
                    observation = first.observation.binding,
                ),
            )
        assertEquals(
            PhoneErrorCode.STALE_OBSERVATION,
            (staleTap as PhoneCallResult.Refused).error,
        )
        assertEquals("an old page is never injected", 0, fixture.driver.dispatchCalls)
    }
}
