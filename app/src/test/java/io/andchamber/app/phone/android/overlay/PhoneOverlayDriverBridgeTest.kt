package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.overlay.safety.OverlayExpectation
import io.andchamber.app.phone.overlay.safety.OverlayPhase
import io.andchamber.app.phone.overlay.safety.PhoneOverlayBarrier
import io.andchamber.app.phone.core.PhoneRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production bridge is the only driver overlay seam. It must keep the original path for
 * notification-only/no-surface, fail closed when an overlay is selected but its surface is missing,
 * and only report a safety loss for the same lease epoch/display owner that started the operation.
 */
class PhoneOverlayDriverBridgeTest {
    private val expectation =
        OverlayExpectation(
            owner = "grant-7:2",
            generation = 2,
            revision = 4,
            phase = OverlayPhase.VISIBLE,
            windowToken = "andchamber-phone-overlay:grant-7:2",
            freshnessDeadlineMs = 10_000,
        )

    private class FakeManager(
        var expectation: OverlayExpectation,
    ) : PhoneOverlayBarrier {
        var hiddenPhases = mutableListOf<OverlayPhase>()
        var expectationCalls = 0

        override fun expectation(deadlineMs: Long): OverlayExpectation {
            expectationCalls++
            return expectation
        }

        override suspend fun <T> withContentHidden(
            owner: String,
            generation: Long,
            revision: Long,
            phase: OverlayPhase,
            deadlineMs: Long,
            block: suspend () -> T,
        ): T {
            hiddenPhases += phase
            return block()
        }
    }

    private fun bridge(
        required: () -> Boolean,
        manager: () -> PhoneOverlayBarrier? = { null },
        epoch: () -> Long? = { 7L },
        owner: () -> String? = { "grant-7:2" },
        lost: MutableList<Pair<Long, String?>> = mutableListOf(),
        makeWay: (suspend (List<PhoneRect>, PhoneRect, Long) -> Boolean) = { _, _, _ -> false },
        liveTarget: () -> String? = { null },
        fallback: () -> Boolean = { false },
    ) = PhoneOverlayDriverBridge(
        overlayRequired = required,
        manager = manager,
        authorityEpoch = epoch,
        displayOwner = owner,
        onSafetyLost = { e, o -> lost += e to o },
        makeWay = makeWay,
        liveTaskTarget = liveTarget,
        notificationFallback = fallback,
        mainDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `the target-hides fallback facts are forwarded and a throw stays fail-closed`() {
        var target: String? = "com.android.settings"
        var allowed = true
        val bridge =
            bridge(
                required = { true },
                liveTarget = { target },
                fallback = { allowed },
            )
        assertEquals("com.android.settings", bridge.liveTargetPackage())
        assertTrue(bridge.notificationFallbackAllowed())

        target = null
        allowed = false
        assertNull(bridge.liveTargetPackage())
        assertFalse(bridge.notificationFallbackAllowed())

        val throwing =
            bridge(
                required = { true },
                liveTarget = { error("no coordinator") },
                fallback = { error("no stop channel") },
            )
        assertNull(throwing.liveTargetPackage())
        assertFalse(throwing.notificationFallbackAllowed())
    }

    @Test
    fun `constructing the bridge resolves neither the coordinator nor the authority`() {
        var resolved = 0
        val bridge =
            PhoneOverlayDriverBridge(
                overlayRequired = { resolved += 1; false },
                manager = { resolved += 10; null },
                authorityEpoch = { resolved += 100; 7L },
                displayOwner = { resolved += 1_000; "owner" },
                onSafetyLost = { _, _ -> resolved += 10_000 },
            )

        assertEquals(0, resolved)
        assertFalse(bridge.required(1_000))
        assertEquals(1, resolved)
    }

    @Test
    fun `notification only without a surface keeps the original driver path`() {
        val bridge = bridge(required = { false })

        assertFalse(bridge.required(1_000))
        assertNull(bridge.beginOperation(1_000))
        // expectation/withContentHidden are never used on the original path.
        assertEquals(0, bridge.beginOperation(1_000)?.epoch ?: 0)
    }

    @Test
    fun `a selected overlay with a missing surface still requires the guard and fails closed`() {
        val bridge = bridge(required = { true })

        assertTrue(bridge.required(1_000))
        assertEquals(7L, bridge.beginOperation(1_000)?.epoch)
        assertThrows(IllegalStateException::class.java) { bridge.expectation(1_000) }
    }

    @Test
    fun `the required gate follows the native mode dynamically`() {
        var required = false
        val bridge = bridge(required = { required })

        assertFalse(bridge.required(1_000))
        required = true
        assertTrue(bridge.required(1_000))
        assertTrue(bridge.beginOperation(1_000) != null)
    }

    @Test
    fun `a throwing mode gate is treated as overlay required`() {
        val bridge = bridge(required = { error("native gate unavailable") })

        assertTrue(bridge.required(1_000))
    }

    @Test
    fun `expectation and an intentional collection hide delegate to the manager without a loss`() = runTest {
        val manager = FakeManager(expectation)
        val lost = mutableListOf<Pair<Long, String?>>()
        val bridge = bridge(required = { true }, manager = { manager }, lost = lost)

        assertEquals(expectation, bridge.expectation(1_000))
        val result =
            bridge.withContentHidden(
                owner = expectation.owner,
                generation = expectation.generation,
                revision = expectation.revision,
                phase = OverlayPhase.COLLECTION_HIDDEN,
                deadlineMs = 10_000,
            ) { 42 }

        assertEquals(42, result)
        assertEquals(listOf(OverlayPhase.COLLECTION_HIDDEN), manager.hiddenPhases)
        assertTrue(lost.isEmpty())
    }

    @Test
    fun `make way delegates to the native request and is false without authority or on a throw`() = runTest {
        val avoid = listOf(PhoneRect(90, 440, 110, 460))
        val frame = PhoneRect(100, 400, 260, 560)
        var calls = 0
        val accepted = bridge(
            required = { true },
            makeWay = { rectangles, current, deadline ->
                calls += 1
                assertEquals(avoid, rectangles)
                assertEquals(frame, current)
                assertEquals(9_000L, deadline)
                true
            },
        )

        assertTrue(accepted.makeWay(avoid, frame, 9_000L))
        assertEquals(1, calls)

        val refused = bridge(required = { true })
        assertFalse(refused.makeWay(avoid, frame, 9_000L))

        val throwing = bridge(required = { true }, makeWay = { _, _, _ -> error("no coordinator") })
        assertFalse("a throwing make-way is a refusal, never a driver exception", throwing.makeWay(avoid, frame, 9_000L))
    }

    @Test
    fun `a failure for the current owner asks for one revoke`() = runTest {
        var epoch: Long? = 7L
        var owner: String? = "grant-7:2"
        val lost = mutableListOf<Pair<Long, String?>>()
        val bridge =
            bridge(
                required = { true },
                epoch = { epoch },
                owner = { owner },
                lost = lost,
            )
        val guard = bridge.beginOperation(1_000)!!

        bridge.onRefusedWhileRequired(guard)

        assertEquals(listOf(7L to "grant-7:2"), lost)
    }

    @Test
    fun `a stale epoch or owner failure cannot revoke a newer grant`() = runTest {
        var epoch: Long? = 7L
        var owner: String? = "grant-7:2"
        val lost = mutableListOf<Pair<Long, String?>>()
        val bridge = bridge(required = { true }, epoch = { epoch }, owner = { owner }, lost = lost)
        val guard = bridge.beginOperation(1_000)!!

        epoch = 8L
        owner = "grant-8:3"
        bridge.onRefusedWhileRequired(guard)

        // Same epoch but a different display owner is also stale.
        epoch = 7L
        bridge.onRefusedWhileRequired(guard)

        assertTrue(lost.isEmpty())
    }

    @Test
    fun `an operation captured without a live lease never reports a loss`() = runTest {
        var epoch: Long? = null
        val lost = mutableListOf<Pair<Long, String?>>()
        val bridge = bridge(required = { true }, epoch = { epoch }, lost = lost)
        val guard = bridge.beginOperation(1_000)!!

        bridge.onRefusedWhileRequired(guard)

        assertTrue(lost.isEmpty())
    }
}
