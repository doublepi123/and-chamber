package io.andchamber.app.ui.web

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GatewayHandoffTest {
    @Test
    fun `latest issued navigation failure is accepted while previous committed page is visible`() {
        val guard = MainFrameErrorGuard()
        val view = Any()
        val previous = "https://example.com/previous"
        val failed = "https://example.com/network-error"
        guard.expect(view, 1, previous)
        guard.started(view, 1, previous, previous)
        guard.expect(view, 1, failed)
        // WebView may retain the previous committed URL even after starting the failed navigation.
        guard.started(view, 1, failed, previous)
        assertTrue(guard.accepts(view, 1, failed, false))
        assertFalse(guard.accepts(view, 1, previous, false))
    }

    @Test
    fun `Chromium error document callbacks cannot clobber the real failed target`() {
        val guard = MainFrameErrorGuard()
        val view = Any()
        val failed = "https://example.com/network-error"
        guard.expect(view, 1, failed)
        guard.started(view, 1, failed, failed)
        // Both start and history callbacks use this guard and may expose Chromium's error document.
        repeat(2) { guard.started(view, 1, "chrome-error://chromewebdata/", "chrome-error://chromewebdata/") }
        assertTrue(guard.accepts(view, 1, failed, false))
        assertFalse(guard.accepts(view, 1, "chrome-error://chromewebdata/", false))
    }

    @Test
    fun `failure before commit still rejects stale owners generations quarantine and internal blank`() {
        val guard = MainFrameErrorGuard()
        val view = Any()
        val failed = "https://example.com/network-error"
        guard.expect(view, 2, failed)
        guard.started(view, 2, failed, "https://example.com/previous")
        guard.started(view, 2, gatewayHandoffBlank(1), gatewayHandoffBlank(1))
        assertFalse(guard.accepts(view, 2, gatewayHandoffBlank(1), false))
        assertFalse(guard.accepts(view, 2, "https://example.com/old", false))
        assertFalse(guard.accepts(Any(), 2, failed, false))
        assertFalse(guard.accepts(view, 1, failed, false))
        assertFalse(guard.accepts(view, 2, failed, true))
        assertTrue(guard.accepts(view, 2, failed, false))
    }

    @Test
    fun `begin coalesces the current ticket until its authenticated load finishes`() = runTest {
        val state = GatewayHandoff()
        val owner = Any()
        val ticket = state.begin(owner, 1)
        assertTrue(ticket > 0)
        assertEquals(ticket, state.begin(owner, 1))
        state.started(ticket, owner, 1)
        assertTrue(state.complete(ticket, owner, 1))
        assertTrue(state.await(ticket))
        assertEquals(ticket, state.begin(owner, 1))
        assertTrue(state.blocksDocuments)
        assertTrue(state.finish(ticket, owner, 1))
        assertFalse(state.blocksDocuments)
        assertTrue(state.begin(owner, 1) > ticket)
    }

    @Test
    fun `authentication waits for a started and completed blank document`() = runTest {
        val state = GatewayHandoff()
        val owner = Any()
        val ticket = state.begin(owner, 1)
        val waiting = async { state.await(ticket) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertFalse(state.complete(ticket, owner, 1))
        assertFalse(state.finish(ticket, owner, 1))
        state.started(ticket, owner, 1)
        assertFalse(waiting.isCompleted)
        assertTrue(state.complete(ticket, owner, 1))
        assertTrue(waiting.await())
        assertFalse(state.complete(ticket, owner, 1))
    }

    @Test
    fun `stale tickets views and generations cannot start or complete a newer barrier`() = runTest {
        val state = GatewayHandoff()
        val oldOwner = Any()
        val newOwner = Any()
        val old = state.begin(oldOwner, 1)
        val current = state.begin(newOwner, 2)
        assertFalse(state.await(old))
        state.started(old, oldOwner, 1)
        state.started(current, oldOwner, 2)
        state.started(current, newOwner, 1)
        assertFalse(state.complete(old, oldOwner, 1))
        assertFalse(state.complete(current, newOwner, 2))
        state.started(current, newOwner, 2)
        assertFalse(state.complete(current, oldOwner, 2))
        assertFalse(state.complete(current, newOwner, 1))
        assertTrue(state.complete(current, newOwner, 2))
        assertTrue(state.await(current))
    }

    @Test
    fun `cancel only invalidates its own current ticket and keeps documents quarantined`() = runTest {
        val state = GatewayHandoff()
        val owner = Any()
        val old = state.begin(owner, 1)
        state.cancel(old)
        val current = state.begin(owner, 1)
        state.cancel(old)
        state.started(current, owner, 1)
        assertTrue(state.complete(current, owner, 1))
        assertTrue(state.await(current))
        state.cancel(current)
        assertFalse(state.await(current))
        assertFalse(state.finish(current, owner, 1))
        assertTrue(state.blocksDocuments)
    }

    @Test
    fun `timeout fails closed without a fixed delay or blocking the coroutine dispatcher`() = runTest {
        val state = GatewayHandoff()
        val owner = Any()
        val ticket = state.begin(owner, 1)
        assertFalse(state.await(ticket))
        assertEquals(5_000, testScheduler.currentTime)
        assertTrue(state.blocksDocuments)
        state.started(ticket, owner, 1)
        assertFalse(state.complete(ticket, owner, 1))
        assertFalse(state.finish(ticket, owner, 1))
    }

    @Test
    fun `cancelled waiter invalidates the barrier and an invalid ticket never waits`() = runTest {
        val state = GatewayHandoff()
        val owner = Any()
        assertFalse(state.await(-1))
        val ticket = state.begin(owner, 1)
        val waiting = async { state.await(ticket) }
        runCurrent()
        waiting.cancelAndJoin()
        state.started(ticket, owner, 1)
        assertFalse(state.complete(ticket, owner, 1))
        assertFalse(state.await(ticket))
        assertTrue(state.blocksDocuments)
    }

    @Test
    fun `budget accepts each ticket once and never retries beyond two gateway rounds`() {
        val budget = GatewayHandoffBudget()
        assertEquals(GatewayHandoffBudget.Claim.Rejected, budget.claim(-1))
        assertEquals(GatewayHandoffBudget.Claim.Start, budget.claim(1))
        assertEquals(GatewayHandoffBudget.Claim.Duplicate, budget.claim(1))
        assertEquals(1, budget.used)
        assertEquals(GatewayHandoffBudget.Claim.Start, budget.claim(2))
        assertEquals(GatewayHandoffBudget.Claim.Rejected, budget.claim(1))
        assertEquals(GatewayHandoffBudget.Claim.Rejected, budget.claim(3))
        assertEquals(2, budget.used)
        budget.reset()
        assertEquals(GatewayHandoffBudget.Claim.Duplicate, budget.claim(2))
        assertEquals(0, budget.used)
        assertEquals(GatewayHandoffBudget.Claim.Start, budget.claim(3))
    }
}
