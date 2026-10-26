package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.overlay.safety.OverlayPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole hide-scheduling section is owner-bound. A cancellation after the main thread removed
 * the window but before the hide continuation resumed must still restore the current authority;
 * a refused hide must never execute the read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneOverlayReadHideTest {
    @Test
    fun `a main restore timeout is settled by an independent owner-bound cleanup`() = runTest {
        val tickets = PhoneOverlayReadHideTickets()
        val mainGate = CompletableDeferred<Unit>()
        var hidden = false
        var stopUnconfirmed = false
        var restoreResult: Boolean? = null
        var pendingCleanup: (() -> Unit)? = null

        val read =
            async {
                withOverlayReadHide(
                    lease = lease,
                    hide = {
                        tickets.install(lease)
                        hidden = true
                        awaitCancellation()
                    },
                    restore = { ticket ->
                        restoreResult =
                            settleReadHideWithTimeout(
                                lease = ticket,
                                tickets = tickets,
                                timeoutMs = 1_000L,
                                restore = {
                                    // Models Main never getting scheduled within 1s.
                                    mainGate.await()
                                    hidden = false
                                },
                                onTimeout = {
                                    // Models Handler.post: it runs independently after Main is free.
                                    pendingCleanup = {
                                        val settled = tickets.settleTimedOut(ticket)
                                        if (settled != null) {
                                            hidden = false
                                            stopUnconfirmed = true
                                        }
                                    }
                                },
                            )
                    },
                ) {
                    error("the read block must not run")
                }
            }

        runCurrent()
        assertTrue(hidden)
        read.cancel()
        advanceTimeBy(1_001L)

        assertEquals(false, restoreResult)
        assertNotNull("the timed-out ticket must be marked uncertain", tickets.uncertainLease)
        assertTrue("a timeout is not a completed restore", hidden)

        assertNotNull("the independent Main cleanup must be posted", pendingCleanup)
        pendingCleanup?.invoke()

        assertFalse("Main cleanup must clear the exact hidden ticket", hidden)
        assertTrue("native stop is reported uncertain/fail-closed, not a rollback", stopUnconfirmed)
        assertNull(tickets.uncertainLease)
        assertNull(tickets.activeLease())

        var cancelled = false
        try {
            read.await()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `a timed out old ticket never clears a newer ticket or its uncertainty`() {
        val tickets = PhoneOverlayReadHideTickets()
        tickets.install(lease)
        tickets.markTimedOut(lease)
        assertNotNull(tickets.uncertainLease)

        tickets.clear()
        val newer = lease.copy(owner = "grant-8", generation = 3, revision = 1)
        tickets.install(newer)
        tickets.markTimedOut(newer)

        assertNull("old timeout cleanup must not settle the newer ticket", tickets.settleTimedOut(lease))
        assertEquals(newer, tickets.activeLease())
        assertEquals(newer, tickets.uncertainLease)

        assertEquals(newer, tickets.settleTimedOut(newer))
        assertNull(tickets.activeLease())
        assertNull(tickets.uncertainLease)
    }

    @Test
    fun `restoration waits for the injected first frame before settling`() = runTest {
        val tickets = PhoneOverlayReadHideTickets()
        tickets.install(lease)
        var owner = lease.owner
        var generation = lease.generation
        var viewCurrent = true
        var settled = false
        val frame = CompletableDeferred<Unit>()

        val gate =
            async {
                val framed =
                    awaitCurrentReadFrame(
                        lease = lease,
                        tickets = tickets,
                        ownerKey = { owner },
                        generation = { generation },
                        viewIsCurrent = { viewCurrent },
                        awaitFrame = { frame.await() },
                    )
                if (framed && tickets.settle(lease)) settled = true
                framed
            }

        runCurrent()
        assertFalse("restoration must not settle before the view draws", settled)
        assertEquals("the ticket must still own the pending restore", lease, tickets.activeLease())

        frame.complete(Unit)
        assertTrue(gate.await())
        assertTrue(settled)
        assertNull(tickets.activeLease())
    }

    @Test
    fun `a new owner during the frame wait cannot settle the old ticket`() = runTest {
        val tickets = PhoneOverlayReadHideTickets()
        tickets.install(lease)
        var owner = lease.owner
        val generation = lease.generation
        val frame = CompletableDeferred<Unit>()

        val gate =
            async {
                awaitCurrentReadFrame(
                    lease = lease,
                    tickets = tickets,
                    ownerKey = { owner },
                    generation = { generation },
                    viewIsCurrent = { true },
                    awaitFrame = {
                        frame.await()
                        owner = "grant-8"
                    },
                )
            }

        runCurrent()
        frame.complete(Unit)
        assertFalse("a newer owner must refuse the old restore", gate.await())
        assertEquals("the old ticket is left for its own cleanup", lease, tickets.activeLease())
    }

    @Test
    fun `a new ticket during the frame wait is never settled by the old continuation`() = runTest {
        val tickets = PhoneOverlayReadHideTickets()
        tickets.install(lease)
        val newer = lease.copy(owner = "grant-8", generation = 3)
        val frame = CompletableDeferred<Unit>()

        val gate =
            async {
                awaitCurrentReadFrame(
                    lease = lease,
                    tickets = tickets,
                    ownerKey = { lease.owner },
                    generation = { lease.generation },
                    viewIsCurrent = { true },
                    awaitFrame = { frame.await() },
                )
            }

        runCurrent()
        tickets.clear()
        tickets.install(newer)
        frame.complete(Unit)
        assertFalse("the old read must not settle the new ticket", gate.await())
        assertEquals(newer, tickets.activeLease())
        assertNull(tickets.settleTimedOut(lease))
        assertEquals(newer, tickets.activeLease())
    }

    @Test
    fun `a cancelled frame wait does not settle or clear the ticket`() = runTest {
        val tickets = PhoneOverlayReadHideTickets()
        tickets.install(lease)
        val frame = CompletableDeferred<Unit>()

        val gate =
            async {
                awaitCurrentReadFrame(
                    lease = lease,
                    tickets = tickets,
                    ownerKey = { lease.owner },
                    generation = { lease.generation },
                    viewIsCurrent = { true },
                    awaitFrame = { frame.await() },
                )
            }

        runCurrent()
        gate.cancel()
        var cancelled = false
        try {
            gate.await()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals("a cancelled frame wait leaves the exact ticket", lease, tickets.activeLease())
        assertNull(tickets.uncertainLease)
    }

    private val lease =
        PhoneOverlayReadLease(
            owner = "grant-7",
            generation = 2,
            revision = 5,
            phase = OverlayPhase.COLLECTION_HIDDEN,
        )

    @Test
    fun `a cancel after main removal still restores and never runs the block`() = runTest {
        var removed = false
        var hidden = false
        var shown = true
        var restored = 0
        var restoredLease: PhoneOverlayReadLease? = null
        var blockRan = false

        val read =
            async {
                withOverlayReadHide(
                    lease = lease,
                    hide = {
                        // Models main having removed the real view, then the continuation never
                        // returning before the caller cancels.
                        removed = true
                        hidden = true
                        shown = false
                        awaitCancellation()
                    },
                    restore = {
                        restored += 1
                        restoredLease = it
                        hidden = false
                        shown = true
                    },
                ) {
                    blockRan = true
                }
            }

        runCurrent()
        assertTrue(removed)
        assertTrue(hidden)
        assertFalse(shown)

        read.cancel()
        var cancelled = false
        try {
            read.await()
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue("the caller cancellation still propagates", cancelled)
        assertEquals("the owner-bound restore must run exactly once", 1, restored)
        assertEquals("restore must keep the exact read owner ticket", lease, restoredLease)
        assertFalse("the window must not stay hidden forever", hidden)
        assertTrue(shown)
        assertFalse("a cancelled hide must not run the read", blockRan)
    }

    @Test
    fun `a refused hide throws before the block and leaves no restore ticket`() = runTest {
        var hidden = false
        var restored = 0
        var blockRan = false
        var failure: Throwable? = null

        try {
            withOverlayReadHide(
                lease = lease,
                hide = { false },
                restore = {
                    // The production restore is ticket-bound; a refused hide never installed one.
                    if (hidden) restored += 1
                },
            ) {
                blockRan = true
            }
        } catch (error: Throwable) {
            failure = error
        }

        assertTrue(failure is PhoneOverlayHideRefused)
        assertEquals("no removed window means no restore", 0, restored)
        assertFalse("a refused hide must not read through the overlay", blockRan)
    }
}
