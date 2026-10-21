package io.andchamber.app.ui.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserTouchFocusTest {
    private val view = Any()
    private fun eligible() = BrowserTouchFocusState(
        touched = view, original = view, current = view, selectedTabPresent = true,
        shown = true, retired = false, hostAttached = true, foreground = true,
        attached = true, viewShown = true, windowFocused = true, enabled = true,
        focusable = true, focusableInTouchMode = true, mouseMode = false,
        injectingMouse = false, mousePressActive = false, focused = false,
    )

    @Test fun admittedDownRequestsOnceAfterTakeoverAndContinuesTouch() {
        val order = mutableListOf<String>()
        val result = handoffBrowserTouchFocus(true, true,
            { order += "takeover" },
            { order += "read"; eligible() },
            { order += "request"; true })
        assertEquals(listOf("takeover", "read", "request"), order)
        assertEquals(BrowserTouchFocusResult.REQUESTED, result)
        assertFalse(result.consumeTouch)
    }

    @Test fun deniedAdmissionDoesNotReadOrRequestOrTakeOver() {
        val result = handoffBrowserTouchFocus(true, false,
            { error("takeover") }, { error("read") }, { error("request") })
        assertEquals(BrowserTouchFocusResult.SKIPPED, result)
        assertFalse(result.consumeTouch)
    }

    @Test fun moveUpAndOtherNonDownEventsDoNotReadOrRequest() {
        // The listener maps every action except ACTION_DOWN to false.
        val result = handoffBrowserTouchFocus(false, true,
            { error("takeover") }, { error("read") }, { error("request") })
        assertEquals(BrowserTouchFocusResult.SKIPPED, result)
        assertFalse(result.consumeTouch)
    }

    @Test fun staleOrUnsafeReadbackNeverRequestsFocus() {
        val state = eligible()
        val refused = listOf(
            state.copy(touched = Any()), state.copy(original = Any()),
            state.copy(current = Any()), state.copy(current = null), state.copy(touched = null),
            state.copy(selectedTabPresent = false), state.copy(shown = false),
            state.copy(retired = true), state.copy(hostAttached = false),
            state.copy(foreground = false), state.copy(attached = false),
            state.copy(viewShown = false), state.copy(windowFocused = false),
            state.copy(enabled = false), state.copy(focusable = false),
            state.copy(focusableInTouchMode = false), state.copy(mouseMode = true),
            state.copy(injectingMouse = true), state.copy(mousePressActive = true),
        )
        refused.forEachIndexed { index, sample ->
            val result = handoffBrowserTouchFocus(true, true, {}, { sample }, { error("request $index") })
            assertEquals("case $index", BrowserTouchFocusResult.SKIPPED, result)
            assertFalse(result.consumeTouch)
        }
    }

    @Test fun sourceChangedDuringTakeoverIsRecheckedBeforeRequest() {
        var state = eligible()
        val result = handoffBrowserTouchFocus(true, true,
            { state = state.copy(current = Any()) }, { state }, { error("old view request") })
        assertEquals(BrowserTouchFocusResult.SKIPPED, result)
    }

    @Test fun detachedOrRetiredDuringTakeoverIsRecheckedBeforeRequest() {
        for (retired in listOf(false, true)) {
            var state = eligible()
            val result = handoffBrowserTouchFocus(true, true,
                { state = if (retired) state.copy(retired = true) else state.copy(attached = false) },
                { state }, { error("unsafe request") })
            assertEquals(BrowserTouchFocusResult.SKIPPED, result)
        }
    }

    @Test fun alreadyFocusedDoesNotRepeatRequest() {
        val result = handoffBrowserTouchFocus(true, true, {},
            { eligible().copy(focused = true) }, { error("duplicate request") })
        assertEquals(BrowserTouchFocusResult.ALREADY_FOCUSED, result)
        assertFalse(result.consumeTouch)
    }

    @Test fun requestFailureIsNotSuccessAndDoesNotConsumeTouch() {
        var requests = 0
        val state = eligible()
        val result = handoffBrowserTouchFocus(true, true, {}, { state }, { requests++; false })
        assertEquals(1, requests)
        assertEquals(BrowserTouchFocusResult.REFUSED, result)
        assertEquals(eligible(), state)
        assertFalse(result.consumeTouch)
    }
}
