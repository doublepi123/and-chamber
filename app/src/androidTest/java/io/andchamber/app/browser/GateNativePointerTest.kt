package io.andchamber.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure actual helper used by LifecycleGateCommands.nativeNoPress; no device. */
class GateNativePointerTest {
    private fun ready(
        action: String,
        x: Float,
        y: Float,
        width: Int = 1440,
        height: Int = 3120,
        dy: Float? = null,
        originX: Float = 0f,
        originY: Float = 0f,
    ): GateNativePacket =
        (GateNativePointer.plan(action, x, y, width, height, dy, originX, originY) as GateNativePlan.Ready).packet

    @Test
    fun eventPointIsViewLocalAndNeverAddsTheWindowOrigin() {
        val packet = ready("hover", 60f, 60f, originX = 198f, originY = 1464f)
        assertEquals(60f, packet.x, 0f)
        assertEquals(60f, packet.y, 0f)
        assertEquals("view-local", packet.space)
        assertEquals("ACTION_HOVER_MOVE", packet.motionAction)
        assertTrue(packet.x != 198f + 60f)
        assertTrue(packet.y != 1464f + 60f)
    }

    @Test
    fun hoverActionsKeepMouseSourceToolAndZeroButtons() {
        assertEquals("ACTION_HOVER_ENTER", ready("enter", 10f, 20f).motionAction)
        assertEquals("ACTION_HOVER_EXIT", ready("exit", 10f, 20f).motionAction)
        assertEquals("ACTION_HOVER_MOVE", ready("hover", 10f, 20f).motionAction)
        for (action in listOf("enter", "exit", "hover")) {
            val packet = ready(action, 10f, 20f)
            assertEquals("SOURCE_MOUSE", packet.source)
            assertEquals("TOOL_TYPE_MOUSE", packet.tool)
            assertEquals(0, packet.buttonState)
            assertNull(packet.axisValue)
        }
    }

    @Test
    fun wheelKeepsTheExistingDyMappingAndButtonFreeMousePacket() {
        val down = ready("wheel", 10f, 20f, dy = -120f)
        assertEquals("ACTION_SCROLL", down.motionAction)
        assertEquals(120f, down.axisValue ?: Float.NaN, 0f)
        assertEquals(0, down.buttonState)
        assertEquals(10f, down.x, 0f)
        assertEquals(20f, down.y, 0f)
        val up = ready("wheel", 10f, 20f, dy = 120f)
        assertEquals(-120f, up.axisValue ?: Float.NaN, 0f)
    }

    @Test
    fun localViewportBoundsAreEnforced() {
        data class Bad(
            val action: String,
            val x: Float,
            val y: Float,
            val width: Int,
            val height: Int,
            val dy: Float?,
            val reason: String,
        )
        val accepted = ready("hover", 0f, 0f, width = 100, height = 200)
        assertEquals(0f, accepted.x, 0f)
        assertEquals(0f, accepted.y, 0f)
        for (bad in listOf(
            Bad("hover", -1f, 10f, 100, 100, null, "point-outside-view"),
            Bad("hover", 100f, 10f, 100, 100, null, "point-outside-view"),
            Bad("hover", 10f, 100f, 100, 100, null, "point-outside-view"),
            Bad("hover", 10f, 10f, 0, 100, null, "point-outside-view"),
            Bad("hover", 10f, 10f, 100, 0, null, "point-outside-view"),
            Bad("hover", Float.NaN, 10f, 100, 100, null, "bad-point"),
            Bad("hover", 10f, Float.POSITIVE_INFINITY, 100, 100, null, "bad-point"),
            Bad("unknown", 10f, 10f, 100, 100, null, "unknown-native-action"),
            Bad("wheel", 10f, 10f, 100, 100, null, "bad-wheel"),
            Bad("wheel", 10f, 10f, 100, 100, Float.NaN, "bad-wheel"),
            Bad("wheel", 10f, 10f, 100, 100, 2001f, "bad-wheel"),
        )) {
            val plan = GateNativePointer.plan(bad.action, bad.x, bad.y, bad.width, bad.height, bad.dy)
            assertTrue("${bad.action}/${bad.x}/${bad.y}", plan is GateNativePlan.Rejected)
            assertEquals(bad.reason, (plan as GateNativePlan.Rejected).reason)
        }
    }
}
