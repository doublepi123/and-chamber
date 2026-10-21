package io.andchamber.app.ui.browser

import io.andchamber.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BrowserRetiredUiTest {
    @Test fun `retirement copy does not reuse mode control or unsupported messages`() {
        assertEquals(R.string.browser_mouse_gesture_interrupted, browserFloatErrorResource("mouse-gesture-interrupted"))
        assertEquals(R.string.browser_mode_reopen_required, browserFloatErrorResource("mode-reopen-required"))
        assertEquals(R.string.browser_reopen_required, browserFloatErrorResource("reopen-required"))
        assertEquals(R.string.browser_mouse_unsupported, browserFloatErrorResource("mouse unsupported"))
        assertNull(browserFloatErrorResource("Website: mouse-gesture-interrupted"))
    }

    @Test fun `reopen failure follows the core reply and never claims a new page or grant`() {
        assertNull(browserReopenFailureResource(true, null))
        assertEquals(R.string.browser_reopen_failed, browserReopenFailureResource(false, null))
        assertEquals(R.string.browser_reopen_failed, browserReopenFailureResource(false, "native host missing"))
        assertEquals(R.string.browser_mode_reopen_required, browserReopenFailureResource(false, "mode-reopen-required"))
    }

    @Test fun `reopening requires explicit request and cannot double dispatch while busy`() = runBlocking {
        val action = RetiredReopenUiState()
        var calls = 0
        val result = CompletableDeferred<Unit>()
        assertEquals(0, calls)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { action.request { calls++; result.await() } }
        assertTrue(action.busy)
        action.request { calls++ }
        assertEquals(1, calls)
        result.complete(Unit); first.join()
        assertFalse(action.busy)
        assertEquals(1, calls) // no automatic second attempt, grant or local success flag
    }

    @Test fun `failed or cancelled reopen releases only the busy flag`() = runBlocking {
        val action = RetiredReopenUiState()
        val error = runCatching { action.request { error("core failure") } }.exceptionOrNull()
        assertEquals("core failure", error?.message)
        assertFalse(action.busy)
        val wait = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { action.request { wait.await() } }
        job.cancel(); job.join()
        assertFalse(action.busy)
    }

    @Test fun `active page press refuses expansion before capture or geometry change`() = runBlocking {
        val window = WindowExpansionUiState()
        var captured = false
        var geometry = false
        val result = window.change(true, {
            captured = true
            WindowChange.Proceed(WindowChangeToken(1, true), ScrollSave.NotSaved(ScrollNotSavedReason.LOADING))
        }, { geometry = true }, canChange = { false })
        assertNull(result)
        assertFalse(captured)
        assertFalse(geometry)
        assertFalse(window.enlarged)
        assertFalse(window.busy)
    }

    @Test fun `press starting during capture defers the toggle without a geometry mutation`() = runBlocking {
        val window = WindowExpansionUiState()
        var press = false
        var invalidated = false
        val capture = CompletableDeferred<WindowChange>()
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            window.change(true, { capture.await() }, { invalidated = true }, canChange = { !press })
        }
        press = true
        capture.complete(WindowChange.Proceed(WindowChangeToken(1, true), ScrollSave.Saved(ScrollAnchor(0.0, 893.0))))
        assertEquals(WindowChange.Superseded, work.await())
        assertFalse(invalidated)
        assertFalse(window.enlarged)
        assertNull(window.pendingChange)
        assertFalse(window.busy)
    }
}
