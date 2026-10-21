package io.andchamber.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputEventMemoTest {
    private val event = Any()
    private val down = RawInputEventKey(0, 1L, 1L, 3, 4, 7, listOf(7), 1f, 2f)
    private val up = down.copy(action = 1, eventTime = 2L)
    private val move = down.copy(action = 2, eventTime = 3L, changedPointerId = 11, pointerIds = listOf(7, 11))

    @Test
    fun `the same recycled object with a new key is not the same decision`() {
        val memo = InputEventMemo()
        val source = Any()
        memo.remember(event, down, source, true)
        assertEquals(true, memo.recall(event, down, source))
        assertNull(memo.recall(event, up, source))
        memo.remember(event, up, source, true)
        assertEquals(true, memo.recall(event, up, source))
        assertNull(memo.recall(event, move, source))
    }

    @Test
    fun `a cached decision does not transfer to a retired or new source`() {
        val memo = InputEventMemo()
        val source = Any()
        memo.remember(event, down, source, true)
        memo.clear()
        assertNull(memo.recall(event, down, source))
        val fresh = Any()
        memo.remember(event, down, fresh, true)
        assertEquals(true, memo.recall(event, down, fresh))
        assertNull(memo.recall(event, down, source))
        assertFalse(memo.recall(event, move, fresh) == true)
    }
}
