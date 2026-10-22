package io.andchamber.app.ui.browser

import org.junit.Assert.*
import org.junit.Test

class ScrollMemoryTest {
    private val page = ScrollPageIdentity("local", "tab-a", "view-a", 10, "witness-a", 0, 0, 1, 2)
    private val stamp = ScrollCaptureStamp(10, 20, 30)
    private val origin = ScrollAnchor(12.0, 893.0)

    private fun save(memory: ScrollMemory, p: ScrollPageIdentity = page, anchor: ScrollAnchor = origin): WindowChange.Proceed {
        val request = memory.beginChange(true, p, stamp)
        return memory.completeChange(request, p, stamp, ScrollSample.Captured(anchor)) as WindowChange.Proceed
    }

    private fun restore(memory: ScrollMemory, p: ScrollPageIdentity = page): WindowChange.Proceed {
        // Resize legitimately changed all these epochs since capture; none is bookmark identity.
        val resized = ScrollCaptureStamp(11, 99, 100)
        val request = memory.beginChange(false, p, resized)
        return memory.completeChange(request, p, resized) as WindowChange.Proceed
    }

    @Test fun `893 survives automatic clamp 488 and restore plan is consumed once`() {
        val memory = ScrollMemory()
        val enlarged = save(memory)
        assertEquals(ScrollSave.Saved(origin), enlarged.saved)
        // Natural resize/scroll events are not observePage with a new interactionSeq.
        // The auto-clamped 488 is NOT a new capture or the saved anchor.
        memory.observePage(page)
        val restored = restore(memory)
        val plan = (memory.consumeRestore(restored.token, page) as ScrollRestore.Apply).plan
        assertEquals(893.0, plan.target.y, 0.0)
        assertTrue(memory.isRestoreCurrent(plan, page))
        assertEquals(ScrollRestore.Skip(RestoreResult.Discarded(ScrollDiscardReason.ALREADY_CONSUMED)),
            memory.consumeRestore(restored.token, page))
        assertEquals(RestoreResult.Restored(origin, origin), memory.completeRestore(plan, page, origin))
        assertFalse(memory.isRestoreCurrent(plan, page))
        assertEquals(RestoreResult.Discarded(ScrollDiscardReason.ALREADY_CONSUMED), memory.completeRestore(plan, page, origin))
    }

    @Test fun `actual readback 488 cannot be reported restored`() {
        val memory = ScrollMemory(); save(memory)
        val plan = (memory.consumeRestore(restore(memory).token, page) as ScrollRestore.Apply).plan
        val actual = ScrollAnchor(12.0, 488.0)
        assertEquals(RestoreResult.Clamped(origin, actual), memory.completeRestore(plan, page, actual))
    }

    @Test fun `only actual readback within one CSS pixel is restored`() {
        listOf(1.0, 1.01).forEach { delta ->
            val memory = ScrollMemory(); save(memory)
            val plan = (memory.consumeRestore(restore(memory).token, page) as ScrollRestore.Apply).plan
            val result = memory.completeRestore(plan, page, ScrollAnchor(12.0 + delta, 893.0 - delta))
            if (delta == 1.0) assertTrue(result is RestoreResult.Restored) else assertTrue(result is RestoreResult.Clamped)
        }
    }

    @Test fun `user or admitted agent intent discards old position before selecting a new position`() {
        val memory = ScrollMemory(); save(memory)
        val interacted = page.copy(interactionSeq = 1)
        memory.observePage(interacted)
        val result = restore(memory, interacted)
        assertEquals(ScrollSave.NotSaved(ScrollNotSavedReason.INTERACTION_CHANGED), result.saved)
        assertEquals(ScrollRestore.Skip(RestoreResult.NotSaved(ScrollNotSavedReason.INTERACTION_CHANGED)),
            memory.consumeRestore(result.token, interacted))
    }

    @Test fun `reads probes titles and window toggles do not invalidate bookmark`() {
        val memory = ScrollMemory(); save(memory)
        repeat(10) { memory.observePage(page) }
        val restored = restore(memory)
        assertEquals(ScrollSave.Saved(origin), restored.saved)
        assertEquals(origin, (memory.consumeRestore(restored.token, page) as ScrollRestore.Apply).plan.target)
    }

    @Test fun `every persistent identity component discards bookmark`() {
        val changed = listOf(
            page.copy(instanceKey = "another"), page.copy(tabId = "tab-b"), page.copy(viewId = "view-b"),
            page.copy(navigationTicket = 11), page.copy(documentWitness = "witness-b"),
            page.copy(modeSeq = 1), page.copy(runtimeLaunchId = 2), page.copy(consoleGeneration = 3),
        )
        changed.forEach { next ->
            val memory = ScrollMemory(); save(memory)
            memory.observePage(next)
            val result = restore(memory, next)
            assertTrue("identity must discard: $next", result.saved is ScrollSave.NotSaved)
            assertTrue(memory.consumeRestore(result.token, next) is ScrollRestore.Skip)
        }
    }

    @Test fun `context invalidation prevents restore even when same identifiers later return`() {
        val memory = ScrollMemory(); save(memory)
        val oldRestore = restore(memory)
        memory.invalidateContext()
        assertEquals(ScrollRestore.Skip(RestoreResult.Discarded(ScrollDiscardReason.SUPERSEDED)),
            memory.consumeRestore(oldRestore.token, page))
        assertEquals(ScrollSave.NotSaved(ScrollNotSavedReason.CONTEXT_CHANGED), restore(memory).saved)
    }

    @Test fun `late capture cannot overwrite newer expansion cycle`() {
        val memory = ScrollMemory()
        val old = memory.beginChange(true, page, stamp)
        val fresh = save(memory, anchor = ScrollAnchor(0.0, 200.0))
        assertTrue(fresh.token.transitionId > old.token.transitionId)
        assertEquals(WindowChange.Superseded, memory.completeChange(old, page, stamp, ScrollSample.Captured(origin)))
        val plan = (memory.consumeRestore(restore(memory).token, page) as ScrollRestore.Apply).plan
        assertEquals(200.0, plan.target.y, 0.0)
    }

    @Test fun `late settled token cannot restore during a new cycle`() {
        val memory = ScrollMemory(); save(memory)
        val old = restore(memory)
        save(memory, anchor = ScrollAnchor(0.0, 55.0))
        assertEquals(ScrollRestore.Skip(RestoreResult.Discarded(ScrollDiscardReason.SUPERSEDED)),
            memory.consumeRestore(old.token, page))
        assertEquals(55.0, (memory.consumeRestore(restore(memory).token, page) as ScrollRestore.Apply).plan.target.y, 0.0)
    }

    @Test fun `context or interaction changed during capture supersedes UI toggle`() {
        val memory = ScrollMemory()
        val request = memory.beginChange(true, page, stamp)
        assertEquals(WindowChange.Superseded,
            memory.completeChange(request, page.copy(interactionSeq = 1), stamp, ScrollSample.Captured(origin)))
        val next = memory.beginChange(true, page, stamp)
        memory.invalidateContext()
        assertEquals(WindowChange.Superseded, memory.completeChange(next, page, stamp, ScrollSample.Captured(origin)))
    }

    @Test fun `capture epochs checked only across the capture await`() {
        listOf(stamp.copy(sessionEpoch = 11), stamp.copy(geometryEpoch = 21), stamp.copy(refEpoch = 31)).forEach { changed ->
            val memory = ScrollMemory(); val request = memory.beginChange(true, page, stamp)
            val result = memory.completeChange(request, page, changed, ScrollSample.Captured(origin)) as WindowChange.Proceed
            assertEquals(ScrollSave.NotSaved(ScrollNotSavedReason.CAPTURE_EPOCH_CHANGED), result.saved)
        }
    }

    @Test fun `loading timeout and null IO sample are explicit not saved and still proceed`() {
        listOf(ScrollNotSavedReason.LOADING, ScrollNotSavedReason.TIMEOUT, ScrollNotSavedReason.CAPTURE_FAILED).forEach { reason ->
            val memory = ScrollMemory(); val request = memory.beginChange(true, page, stamp)
            val result = memory.completeChange(request, page, stamp, ScrollSample.Unavailable(reason)) as WindowChange.Proceed
            assertEquals(ScrollSave.NotSaved(reason), result.saved)
        }
        val memory = ScrollMemory(); val noWitness = page.copy(documentWitness = null)
        assertEquals(ScrollSave.NotSaved(ScrollNotSavedReason.NO_WITNESS), save(memory, noWitness).saved)
        val nullCapture = memory.beginChange(true, page, stamp)
        assertEquals(ScrollSave.NotSaved(ScrollNotSavedReason.CAPTURE_FAILED),
            (memory.completeChange(nullCapture, page, stamp, null) as WindowChange.Proceed).saved)
    }

    @Test fun `nonfinite negative CSS sample is not saved`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0).forEach { invalid ->
            listOf(ScrollAnchor(invalid, 893.0), ScrollAnchor(0.0, invalid)).forEach { point ->
                val memory = ScrollMemory()
                assertEquals(ScrollSave.NotSaved(ScrollNotSavedReason.INVALID_COORDINATES), save(memory, anchor = point).saved)
            }
        }
    }

    @Test fun `changed identity invalidates a claimed restore before IO`() {
        val memory = ScrollMemory(); save(memory)
        val plan = (memory.consumeRestore(restore(memory).token, page) as ScrollRestore.Apply).plan
        val changed = page.copy(interactionSeq = 1)
        memory.observePage(changed)
        assertFalse(memory.isRestoreCurrent(plan, changed))
        assertEquals(RestoreResult.Discarded(ScrollDiscardReason.PAGE_CHANGED), memory.completeRestore(plan, changed, origin))
    }

    @Test fun `missing or invalid actual readback never counts as restored`() {
        listOf(null, ScrollAnchor(0.0, Double.NaN), ScrollAnchor(-1.0, 893.0)).forEach { actual ->
            val memory = ScrollMemory(); save(memory)
            val plan = (memory.consumeRestore(restore(memory).token, page) as ScrollRestore.Apply).plan
            assertEquals(RestoreResult.NotSaved(ScrollNotSavedReason.READBACK_FAILED), memory.completeRestore(plan, page, actual))
        }
    }

    @Test fun `cancel applies only to the current transition and invalidates late callbacks`() {
        val memory = ScrollMemory(); val first = save(memory)
        val second = save(memory)
        memory.cancel(first.token)
        assertEquals(origin, (restore(memory).saved as ScrollSave.Saved).anchor)
        val pending = memory.beginChange(true, page, stamp)
        assertTrue(pending.token.transitionId > second.token.transitionId)
        memory.cancel(pending.token)
        assertEquals(WindowChange.Superseded, memory.completeChange(pending, page, stamp, ScrollSample.Captured(origin)))
    }

    @Test fun `enlarge settled never scrolls and restore without bookmark is explicit`() {
        val memory = ScrollMemory(); val enlarged = save(memory)
        assertEquals(ScrollRestore.Skip(RestoreResult.Discarded(ScrollDiscardReason.NOT_RESTORING)),
            memory.consumeRestore(enlarged.token, page))
        val empty = ScrollMemory()
        assertEquals(ScrollSave.NotSaved(ScrollNotSavedReason.NO_BOOKMARK), restore(empty).saved)
    }
}
