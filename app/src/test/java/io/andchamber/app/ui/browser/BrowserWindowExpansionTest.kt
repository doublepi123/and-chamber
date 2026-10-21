package io.andchamber.app.ui.browser

import io.andchamber.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class BrowserWindowExpansionTest {
    private fun prepared(id: Long, next: Boolean) =
        WindowChange.Proceed(WindowChangeToken(id, next), ScrollSave.Saved(ScrollAnchor(0.0, 893.0)))

    @Test fun `capture is awaited before invalidation and expansion and settlement is separate`() = runBlocking {
        val ui = WindowExpansionUiState()
        val sample = CompletableDeferred<WindowChange>()
        val steps = mutableListOf<String>()
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            ui.change(true, {
                steps += "prepare"
                assertTrue(ui.busy)
                assertFalse(ui.enlarged)
                sample.await().also { steps += "sample-returned" }
            }, {
                assertFalse(ui.enlarged)
                steps += "invalidate"
            })
        }
        assertFalse(ui.enlarged)
        assertNull(ui.pendingChange)
        assertEquals(listOf("prepare"), steps)
        val ready = prepared(1, true)
        sample.complete(ready)
        assertEquals(ready, work.await())
        assertEquals(listOf("prepare", "sample-returned", "invalidate"), steps)
        assertTrue(ui.enlarged)
        assertFalse(ui.busy)
        assertEquals(ready.token, ui.pendingChange)
        // No token is sent until that rendered layout supplies its own callback.
        assertNull(ui.consumeSettled(null))
        assertEquals(ready.token, ui.consumeSettled(ready.token))
        assertNull(ui.consumeSettled(ready.token))
    }

    @Test fun `a busy toggle cannot start a duplicate capture`() = runBlocking {
        val ui = WindowExpansionUiState()
        val sample = CompletableDeferred<WindowChange>()
        var calls = 0
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            ui.change(true, { calls++; sample.await() }, {})
        }
        assertNull(ui.change(true, { calls++; prepared(2, true) }, {}))
        assertEquals(1, calls)
        sample.complete(prepared(1, true)); work.await()
        assertFalse(ui.busy)
    }

    @Test fun `superseded capture does not invalidate or flip constraints`() = runBlocking {
        val ui = WindowExpansionUiState()
        var invalidated = false
        assertEquals(WindowChange.Superseded,
            ui.change(true, { WindowChange.Superseded }, { invalidated = true }))
        assertFalse(invalidated)
        assertFalse(ui.enlarged)
        assertFalse(ui.busy)
        assertNull(ui.pendingChange)
    }

    @Test fun `cancelled composition cannot apply a late capture or retain its busy lock`() = runBlocking {
        val ui = WindowExpansionUiState()
        val late = CompletableDeferred<Unit>()
        var invalidated = false
        val work = launch(start = CoroutineStart.UNDISPATCHED) {
            ui.change(true, {
                withContext(NonCancellable) { late.await(); prepared(1, true) }
            }, { invalidated = true })
        }
        work.cancel(); late.complete(Unit); work.join()
        assertFalse(invalidated)
        assertFalse(ui.enlarged)
        assertFalse(ui.busy)
        assertNull(ui.pendingChange)
    }

    @Test fun `old composition cannot consume a newer restore token`() = runBlocking {
        val ui = WindowExpansionUiState()
        val enlarge = prepared(1, true)
        ui.change(true, { enlarge }, {})
        val restore = prepared(2, false)
        ui.change(false, { restore }, {})
        assertNull(ui.consumeSettled(enlarge.token))
        assertEquals(restore.token, ui.pendingChange)
        assertEquals(restore.token, ui.consumeSettled(restore.token))
        val newer = prepared(3, true)
        ui.change(true, { newer }, {})
        assertNull(ui.consumeSettled(restore.token))
        assertEquals(newer.token, ui.pendingChange)
    }

    @Test fun `loading not saved may expand without claiming scroll preservation`() = runBlocking {
        val ui = WindowExpansionUiState()
        val notSaved = WindowChange.Proceed(WindowChangeToken(1, true), ScrollSave.NotSaved(ScrollNotSavedReason.LOADING))
        assertEquals(notSaved, ui.change(true, { notSaved }, {}))
        assertTrue(ui.enlarged)
        assertFalse(ui.busy)
        assertEquals(R.string.browser_scroll_waiting, browserScrollNotSavedResource(ScrollNotSavedReason.LOADING))
    }

    @Test fun `clamped and not saved have failure copy not restored copy or authorization error`() {
        assertEquals(R.string.browser_scroll_clamped,
            browserScrollRestoreResource(RestoreResult.Clamped(ScrollAnchor(0.0, 893.0), ScrollAnchor(0.0, 488.0))))
        assertEquals(R.string.browser_scroll_not_restored,
            browserScrollRestoreResource(RestoreResult.NotSaved(ScrollNotSavedReason.READBACK_FAILED)))
        assertNull(browserScrollRestoreResource(RestoreResult.Restored(ScrollAnchor(0.0, 893.0), ScrollAnchor(0.0, 893.0))))
        assertNull(browserScrollRestoreResource(RestoreResult.Discarded(ScrollDiscardReason.SUPERSEDED)))
        assertNull(browserScrollRestoreResource(null))
        assertEquals(R.string.browser_scroll_waiting, browserScrollNotSavedResource(ScrollNotSavedReason.NO_WITNESS))
        assertEquals(R.string.browser_scroll_not_saved, browserScrollNotSavedResource(ScrollNotSavedReason.TIMEOUT))
        assertTrue(browserFloatCanAllow(true, true, "session-a"))
    }
}
