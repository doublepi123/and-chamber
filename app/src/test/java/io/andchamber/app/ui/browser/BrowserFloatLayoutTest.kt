package io.andchamber.app.ui.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserFloatLayoutTest {
    @Test fun `phone defaults reserve composer once`() {
        val b = floatWindowBounds(328f, 700f, 136f, false)
        assertEquals(328f, b.width, 0f)
        assertEquals(564f, b.areaHeight, 0f)
        assertTrue(b.height <= 240f)
        assertTrue(b.height <= b.areaHeight * .42f)
    }

    @Test fun `title can move above 48 without becoming hidden`() {
        val b = floatWindowBounds(328f, 700f, 136f, false)
        assertEquals(-100f, b.clampY(-100f), 0f)
        assertEquals(-(b.areaHeight - b.height), b.clampY(-10000f), 0f)
        assertEquals(0f, b.clampY(10000f), 0f)
    }

    @Test fun `enlarge ignores saved offsets and restore uses them`() {
        val b = floatWindowBounds(800f, 700f, 72f, true)
        assertEquals(0f, b.x(-70f, true), 0f)
        assertEquals(0f, b.y(-100f, true), 0f)
        assertEquals(-70f, b.x(-70f, false), 0f)
        assertEquals(-100f, b.y(-100f, false), 0f)
        assertEquals(360f, b.width, 0f)
        assertEquals(280f, b.height, 0f)
    }

    @Test fun `keyboard adjusted area clamps old position without double subtraction`() {
        val b = floatWindowBounds(328f, 360f, 136f, false)
        assertEquals(224f, b.areaHeight, 0f)
        assertTrue(b.height < 176f)
        assertEquals(0f, b.clampY(-500f) + b.areaHeight - b.height, 0.001f)
    }

    @Test fun `resize and rotation keep entire window inside bounds`() {
        val b = floatWindowBounds(300f, 200f, 136f, false, 600f, 500f)
        assertEquals(300f, b.width, 0f)
        assertEquals(64f, b.height, 0f)
        assertEquals(0f, b.clampX(-100f), 0f)
        assertEquals(0f, b.clampY(-100f), 0f)
        val zero = floatWindowBounds(0f, 10f, 136f, false)
        assertEquals(0f, zero.height, 0f)
    }

    @Test fun `measured multiline composer reserves more than old constant`() {
        val regular = floatWindowBounds(328f, 700f, 136f, false)
        val multiline = floatWindowBounds(328f, 700f, 300f, false)
        assertEquals(400f, multiline.areaHeight, 0f)
        assertTrue(multiline.areaHeight < regular.areaHeight)
        assertTrue(multiline.height < regular.height)
        // Window bottom never passes the measured top, even after dragging down.
        assertTrue(multiline.areaHeight + multiline.clampY(10000f) <= 400f)
        val keyboard = floatWindowBounds(328f, 330f, 220f, false)
        assertEquals(110f, keyboard.areaHeight, 0f)
        assertTrue(keyboard.height < 176f) // explicit enlarge/close panel, not invisible control
    }
}
