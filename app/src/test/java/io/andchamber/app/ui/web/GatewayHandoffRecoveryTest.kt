package io.andchamber.app.ui.web

import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayHandoffRecoveryTest {
    @Test
    fun `successive blank tickets change the document query and never just its fragment`() {
        val first = URI(gatewayHandoffBlank(1))
        val next = URI(gatewayHandoffBlank(2))
        assertEquals(null, first.fragment)
        assertEquals(null, next.fragment)
        assertNotEquals(first.schemeSpecificPart, next.schemeSpecificPart)
        assertTrue(isGatewayHandoffBlank(first.toString()))
        assertTrue(isGatewayHandoffBlank(next.toString()))
        assertFalse(isGatewayHandoffBlank("about:blank?andchamber-gateway=2#foreign"))
        assertFalse(isGatewayHandoffBlank("about:blank?other=2"))
        assertFalse(isGatewayHandoffBlank("data:text/html,blank"))
    }

    @Test
    fun `completed blank can be cancelled and retried on the same view with a fresh ticket`() = runTest {
        val state = GatewayHandoff()
        val view = Any()
        val first = state.begin(view, 1)
        state.started(first, view, 1)
        assertTrue(state.complete(first, view, 1))
        assertTrue(state.await(first))
        state.cancel(first)
        val next = state.begin(view, 1)
        assertNotEquals(gatewayHandoffBlank(first), gatewayHandoffBlank(next))
        assertFalse(state.complete(first, view, 1))
        state.started(next, view, 1)
        assertTrue(state.complete(next, view, 1))
        assertTrue(state.await(next))
    }

    @Test
    fun `automatic attempts remain capped but manual retry gives two fresh rounds`() {
        val budget = GatewayHandoffBudget()
        assertEquals(GatewayHandoffBudget.Claim.Start, budget.claim(1))
        assertEquals(GatewayHandoffBudget.Claim.Start, budget.claim(2))
        assertEquals(GatewayHandoffBudget.Claim.Rejected, budget.claim(3))
        assertEquals(2, budget.used)
        budget.manualRetry()
        assertEquals(0, budget.used)
        assertEquals(GatewayHandoffBudget.Claim.Start, budget.claim(3))
        assertEquals(GatewayHandoffBudget.Claim.Start, budget.claim(4))
        assertEquals(GatewayHandoffBudget.Claim.Rejected, budget.claim(5))
    }

    @Test
    fun `manual retry does not reconsume a duplicate or stale ticket`() {
        val budget = GatewayHandoffBudget()
        budget.claim(1)
        budget.claim(2)
        budget.manualRetry()
        assertEquals(GatewayHandoffBudget.Claim.Duplicate, budget.claim(2))
        assertEquals(GatewayHandoffBudget.Claim.Rejected, budget.claim(1))
        assertEquals(0, budget.used)
        assertEquals(GatewayHandoffBudget.Claim.Start, budget.claim(3))
        assertEquals(GatewayHandoffBudget.Claim.Duplicate, budget.claim(3))
        assertEquals(1, budget.used)
    }

    @Test
    fun `stale blank and old URL errors cannot poison the final page after quarantine ends`() {
        val guard = MainFrameErrorGuard()
        val view = Any()
        val old = "https://example.com/return"
        val final = "https://example.com/?session=test"
        guard.expect(view, 1, old)
        guard.started(view, 1, old, old)
        guard.expect(view, 1, final)
        guard.started(view, 1, final, final)
        assertFalse(guard.accepts(view, 1, gatewayHandoffBlank(1), false))
        assertFalse(guard.accepts(view, 1, old, false))
        assertTrue(guard.accepts(view, 1, final, false))
    }

    @Test
    fun `stale start callback cannot overwrite a newer expected URL`() {
        val guard = MainFrameErrorGuard()
        val view = Any()
        val final = "https://example.com/final"
        guard.expect(view, 1, final)
        guard.started(view, 1, "https://example.com/old", final)
        assertTrue(guard.accepts(view, 1, final, false))
        assertFalse(guard.accepts(view, 1, "https://example.com/old", false))
    }

    @Test
    fun `current URL failures are shown including errors before pageStarted`() {
        val guard = MainFrameErrorGuard()
        val view = Any()
        val requested = "https://example.com/new"
        guard.expect(view, 1, requested)
        assertTrue(guard.accepts(view, 1, requested, false))
        guard.started(view, 1, requested, requested)
        assertTrue(guard.accepts(view, 1, requested, false))
    }

    @Test
    fun `redirect navigation updates the expected URL but still rejects the original URL error`() {
        val guard = MainFrameErrorGuard()
        val view = Any()
        val start = "https://example.com/start"
        val redirected = "https://example.com/redirected"
        guard.expect(view, 1, start)
        guard.started(view, 1, redirected, redirected)
        assertTrue(guard.accepts(view, 1, redirected, false))
        assertFalse(guard.accepts(view, 1, start, false))
    }

    @Test
    fun `wrong views generations quarantined documents and superseded issued URLs are rejected`() {
        val guard = MainFrameErrorGuard()
        val view = Any()
        val url = "https://example.com/current"
        guard.expect(view, 1, url)
        guard.started(view, 1, url, url)
        assertFalse(guard.accepts(Any(), 1, url, false))
        assertFalse(guard.accepts(view, 2, url, false))
        assertFalse(guard.accepts(view, 1, url, true))
        // A visible URL alone does not supersede a failing issued navigation; a new issued load does.
        guard.expect(view, 1, "https://example.com/next")
        assertFalse(guard.accepts(view, 1, url, false))
    }
}
