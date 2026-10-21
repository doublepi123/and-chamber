package io.andchamber.app.ui.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WindowContentHandoffTest {
    private val source = Any()
    private val mount = Any()
    private val rect = WindowContentRect(198f, 1464f, 819, 252)
    private fun proceed(id: Long, next: Boolean) =
        WindowChange.Proceed(WindowChangeToken(id, next), ScrollSave.Saved(ScrollAnchor(0.0, 893.0)))
    private fun binding(token: WindowChangeToken, floor: Long) = WindowContentBinding(token, 42, "view-a", floor)

    @Test fun `Surface notification cannot consume the content transition`() = runBlocking {
        val window = WindowExpansionUiState()
        val change = proceed(1, true)
        window.change(true, { change }, {})
        var geometry = 0
        window.surfaceSettled { geometry++ }
        assertEquals(1, geometry)
        assertEquals(change.token, window.pendingChange)
        assertTrue(window.acknowledgeContent(change.token))
        assertFalse(window.acknowledgeContent(change.token))
    }

    @Test fun `Enlarge with no saved witness neither binds nor constructs a restore target`() = runBlocking {
        val handoff = WindowContentHandoff()
        val window = WindowExpansionUiState()
        var bindCalls = 0
        val result = window.change(true, {
            handoff.prepare(WindowChange.Proceed(WindowChangeToken(1, true),
                ScrollSave.NotSaved(ScrollNotSavedReason.NO_WITNESS)), source, 42) { _, _ -> bindCalls++; null }
        }, {}) as WindowChange.Proceed
        assertTrue(window.enlarged)
        assertEquals(0, bindCalls)
        assertEquals(ScrollSave.NotSaved(ScrollNotSavedReason.NO_WITNESS), result.saved)
        val allocation = handoff.allocated(handoff.rendered(result.token), source, mount, rect)
        assertEquals(WindowContentReport.GeometryOnly,
            handoff.report(allocation, source, mount, 42, true, window::acknowledgeContent))
        assertNull(window.pendingChange)
    }

    @Test fun `Restore binding missing does not flip the window or invalidate geometry`() = runBlocking {
        val handoff = WindowContentHandoff()
        val window = WindowExpansionUiState()
        window.change(true, { proceed(1, true) }, {})
        var geometry = 0
        val result = window.change(false, {
            handoff.prepare(proceed(2, false), source, 42) { _, _ -> null }
        }, { geometry++ })
        assertEquals(WindowChange.Superseded, result)
        assertTrue(window.enlarged)
        assertEquals(0, geometry)
    }

    @Test fun `old source mount handle and detached callback never acknowledge`() {
        val handoff = WindowContentHandoff()
        val change = proceed(2, false)
        handoff.prepare(change, source, 42, ::binding)
        val allocation = handoff.allocated(handoff.rendered(change.token), source, mount, rect)
        var acknowledgements = 0
        val ack: (WindowChangeToken) -> Boolean = { acknowledgements++; true }
        assertEquals(WindowContentReport.Ignore, handoff.report(allocation, Any(), mount, 42, true, ack))
        assertEquals(WindowContentReport.Ignore, handoff.report(allocation, null, mount, 42, true, ack))
        assertEquals(WindowContentReport.Ignore, handoff.report(allocation, source, Any(), 42, true, ack))
        assertEquals(WindowContentReport.Ignore, handoff.report(allocation, source, mount, 43, true, ack))
        assertEquals(WindowContentReport.Ignore, handoff.report(allocation, source, mount, 42, false, ack))
        assertEquals(0, acknowledgements)
    }

    @Test fun `new token cannot reuse an old content rectangle revision`() {
        val handoff = WindowContentHandoff()
        val old = handoff.allocated(null, source, mount, rect)
        val change = proceed(2, false)
        handoff.prepare(change, source, 42, ::binding)
        val render = handoff.rendered(change.token)!!
        assertEquals(old.revision, render.binding!!.afterLayoutRevision)
        val relabelled = WindowContentAllocation(render, source, mount, old.revision, old.rect)
        assertEquals(WindowContentReport.Ignore, handoff.report(relabelled, source, mount, 42, true) {
            fail("old geometry must not acknowledge a new token"); true
        })
    }

    @Test fun `same size fresh allocation reports immutable restore target exactly once`() = runBlocking {
        val handoff = WindowContentHandoff()
        val window = WindowExpansionUiState()
        handoff.allocated(null, source, mount, rect)
        window.change(true, { proceed(1, true) }, {})
        val change = window.change(false, { handoff.prepare(proceed(2, false), source, 42, ::binding) }, {}) as WindowChange.Proceed
        val render = handoff.rendered(change.token)!!
        val fresh = handoff.allocated(render, source, mount, rect) // identical size, real new callback
        val report = handoff.report(fresh, source, mount, 42, true, window::acknowledgeContent) as WindowContentReport.Restore
        assertSame(render.binding, report.target.binding)
        assertTrue(report.target.layoutRevision > render.binding!!.afterLayoutRevision)
        assertEquals(198f, report.target.windowLeftPx, 0f)
        assertEquals(1464f, report.target.windowTopPx, 0f)
        assertEquals(819, report.target.widthPx)
        assertEquals(252, report.target.heightPx)
        val duplicate = handoff.allocated(render, source, mount, rect)
        assertEquals(WindowContentReport.Ignore, handoff.report(duplicate, source, mount, 42, true, window::acknowledgeContent))
    }

    @Test fun `late callback cannot steal the next composition token or binding`() = runBlocking {
        val handoff = WindowContentHandoff()
        val old = proceed(2, false)
        handoff.prepare(old, source, 42, ::binding)
        val oldRender = handoff.rendered(old.token)
        val latest = proceed(3, true)
        handoff.prepare(latest, source, 42) { _, _ -> error("enlarge must not bind") }
        val late = handoff.allocated(oldRender, source, mount, rect)
        assertEquals(WindowContentReport.Ignore, handoff.report(late, source, mount, 42, true) {
            fail("stale render must not acknowledge"); true
        })
        val window = WindowExpansionUiState()
        window.change(true, { latest }, {})
        assertFalse(window.acknowledgeContent(old.token))
        assertEquals(latest.token, window.pendingChange)
    }

    @Test fun `newer actual callback makes an older allocation ineligible`() {
        val handoff = WindowContentHandoff()
        val change = proceed(2, false)
        handoff.prepare(change, source, 42, ::binding)
        val render = handoff.rendered(change.token)
        val old = handoff.allocated(render, source, mount, rect)
        handoff.allocated(render, source, mount, rect.copy(height = 300))
        assertEquals(WindowContentReport.Ignore, handoff.report(old, source, mount, 42, true) {
            fail("older layout revision"); true
        })
    }

    @Test fun `ordinary drag or IME allocation has no token and no restore`() {
        val handoff = WindowContentHandoff()
        val allocation = handoff.allocated(null, source, mount, rect)
        assertEquals(WindowContentReport.GeometryOnly, handoff.report(allocation, source, mount, 42, true) {
            fail("ordinary geometry cannot consume transition"); true
        })
    }

    @Test fun `no callback means no observation no target and no fabricated completion`() {
        val handoff = WindowContentHandoff()
        val change = proceed(2, false)
        handoff.prepare(change, source, 42, ::binding)
        assertEquals(0L, handoff.lastActualContentRevision)
        assertNotNull(handoff.rendered(change.token))
        // Surface, prompt and retired body never call allocated(). No frame is inferred from native state.
        assertEquals(0L, handoff.lastActualContentRevision)
    }

    @Test fun `invalid content size or coordinates cannot acknowledge`() {
        val handoff = WindowContentHandoff()
        val change = proceed(2, false)
        handoff.prepare(change, source, 42, ::binding)
        listOf(rect.copy(width = 0), rect.copy(height = -1), rect.copy(top = Float.NaN)).forEach { bad ->
            val allocation = handoff.allocated(handoff.rendered(change.token), source, mount, bad)
            assertEquals(WindowContentReport.Ignore, handoff.report(allocation, source, mount, 42, true) {
                fail("invalid rect"); true
            })
        }
    }
}
