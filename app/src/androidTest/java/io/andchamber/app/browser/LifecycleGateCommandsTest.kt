package io.andchamber.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure model checks for the gate-finger stream. Compiled with androidTest only; this round does not
 * run it on a device. Reusing the production InputAdmission unit tests covers the Core side.
 */
class LifecycleGateCommandsTest {
    private var now = 1_000L
    private fun stream() = GateTouchStream { now }

    private fun sent(step: GateStep): GateMotion {
        assertTrue("expected sent but was $step", step is GateStep.Sent)
        return (step as GateStep.Sent).motion
    }

    private fun rejected(step: GateStep): String {
        assertTrue("expected rejected but was $step", step is GateStep.Rejected)
        return (step as GateStep.Rejected).reason
    }

    @Test
    fun secondaryThenPrimaryLiftKeepsPointerCountConsistent() {
        val s = stream()
        assertEquals(1, sent(s.apply("down", 10f, 10f)).count)
        val second = sent(s.apply("secondary-down", 50f, 10f))
        assertEquals(2, second.count)
        assertEquals(1, second.actionIndex)
        assertEquals(GATE_SECONDARY_ID, second.actionPointerId)
        now += 10
        val lift = sent(s.apply("primary-up", 10f, 10f))
        assertEquals(2, lift.count)
        assertEquals(0, lift.actionIndex)
        assertEquals(GATE_PRIMARY_ID, lift.actionPointerId)
        assertTrue("count is read before the state changes", lift.actionIndex < lift.count)
        // Primary left first: the remaining pointer is the secondary 11.
        val move = sent(s.apply("move", 60f, 10f))
        assertEquals(1, move.count)
        assertEquals(listOf(GATE_SECONDARY_ID), move.ids.toList())
        val up = sent(s.apply("up", 60f, 10f))
        assertEquals(1, up.count)
        assertEquals(GATE_SECONDARY_ID, up.actionPointerId)
    }

    @Test
    fun primaryLiftLeavesSecondaryAsSinglePointer() {
        val s = stream()
        s.apply("down", 10f, 10f)
        s.apply("secondary-down", 50f, 10f)
        val lift = sent(s.apply("primary-up", 10f, 10f))
        assertEquals(2, lift.count)
        assertEquals(0, lift.actionIndex)
        // MOVE is the remaining pointer 11 only.
        assertEquals(1, sent(s.apply("move", 70f, 10f)).count)
    }

    @Test
    fun secondaryLiftLeavesPrimaryAlone() {
        val s = stream()
        s.apply("down", 10f, 10f)
        s.apply("secondary-down", 50f, 10f)
        val lift = sent(s.apply("secondary-up", 50f, 10f))
        assertEquals(2, lift.count)
        assertEquals(1, lift.actionIndex)
        assertEquals(GATE_SECONDARY_ID, lift.actionPointerId)
        val move = sent(s.apply("move", 20f, 10f))
        assertEquals(listOf(GATE_PRIMARY_ID), move.ids.toList())
        assertEquals(1, sent(s.apply("up", 20f, 10f)).count)
    }

    @Test
    fun singleFingerMoveAndUpUsePrimaryId() {
        val s = stream()
        s.apply("down", 10f, 10f)
        val move = sent(s.apply("move", 30f, 10f))
        assertEquals(1, move.count)
        assertEquals(GATE_PRIMARY_ID, move.actionPointerId)
        val up = sent(s.apply("up", 30f, 10f))
        assertEquals(1, up.count)
        assertEquals(GATE_PRIMARY_ID, up.actionPointerId)
    }

    @Test
    fun duplicateLiftIsRefusedWithoutStateChange() {
        val s = stream()
        s.apply("down", 10f, 10f)
        s.apply("secondary-down", 50f, 10f)
        sent(s.apply("primary-up", 10f, 10f))
        assertEquals("lift-needs-two-pointers", rejected(s.apply("primary-up", 10f, 10f)))
        assertEquals(1, sent(s.apply("move", 70f, 10f)).count)
    }

    @Test
    fun duplicateSecondaryAndNonFinitePointsAreRefused() {
        val s = stream()
        s.apply("down", 10f, 10f)
        s.apply("secondary-down", 50f, 10f)
        assertEquals("duplicate-secondary", rejected(s.apply("secondary-down", 60f, 10f)))
        assertEquals("bad-point", rejected(s.apply("move", Float.NaN, 10f)))
        assertEquals("bad-point", rejected(s.apply("move", Float.POSITIVE_INFINITY, 10f)))
    }

    @Test
    fun timestampsAreMonotonicAndNearClock() {
        val s = stream()
        val down = sent(s.apply("down", 10f, 10f))
        val move = sent(s.apply("move", 20f, 10f))
        assertEquals(down.downTime, move.downTime)
        assertTrue(move.eventTime > down.eventTime)
        assertTrue("never far in the future", move.eventTime - now < 5)
    }

    @Test
    fun cancelClosesSequenceAndRefusesLaterMove() {
        val s = stream()
        s.apply("down", 10f, 10f)
        assertEquals(MOTION_CANCEL_CODE, sent(s.apply("cancel", 10f, 10f)).action)
        assertEquals("not-open", rejected(s.apply("move", 20f, 10f)))
    }

    private companion object {
        const val MOTION_CANCEL_CODE = 3
    }
}
