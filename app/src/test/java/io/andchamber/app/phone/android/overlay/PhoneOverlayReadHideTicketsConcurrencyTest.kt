package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.overlay.safety.OverlayPhase
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Atomicity contract for the read-hide ticket handoff. The production methods share the ticket's
 * short monitor (field-only synchronized methods); an old timeout cleanup can never clear a newer
 * ticket, and a newer install always drops the old uncertainty.
 */
class PhoneOverlayReadHideTicketsConcurrencyTest {
    private val old = lease("grant-7", 2)
    private val newer = lease("grant-8", 3)

    @Test
    fun `all ticket methods are synchronized on the ticket monitor`() {
        val publicMethods =
            PhoneOverlayReadHideTickets::class.java.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
        val names = publicMethods.map { it.name }.toSet()
        assertTrue(
            "unexpected ticket API: $names",
            names.containsAll(
                setOf(
                    "getUncertainLease",
                    "activeLease",
                    "install",
                    "settle",
                    "markTimedOut",
                    "settleTimedOut",
                    "clear",
                ),
            ),
        )
        publicMethods.forEach { method ->
            assertTrue(
                "${method.name} must use @Synchronized",
                Modifier.isSynchronized(method.modifiers),
            )
        }
    }

    @Test
    fun `install clears the old uncertainty and an old timeout cannot settle the new ticket`() {
        val tickets = PhoneOverlayReadHideTickets()
        tickets.install(old)
        assertTrue(tickets.markTimedOut(old))
        assertEquals(old, tickets.uncertainLease)

        // Main starts a new ticket. Its install must drop A's uncertainty atomically.
        tickets.install(newer)
        assertEquals(newer, tickets.activeLease())
        assertNull(tickets.uncertainLease)

        // A's late timeout cleanup has no authority over B.
        assertNull(tickets.settleTimedOut(old))
        assertEquals(newer, tickets.activeLease())
        assertNull(tickets.uncertainLease)

        // B's own timeout still belongs to B.
        assertTrue(tickets.markTimedOut(newer))
        assertEquals(newer, tickets.uncertainLease)
        assertEquals(newer, tickets.settleTimedOut(newer))
        assertNull(tickets.activeLease())
        assertNull(tickets.uncertainLease)
    }

    @Test
    fun `the ticket monitor queues an old timeout marker behind the new handoff`() {
        val tickets = PhoneOverlayReadHideTickets()
        tickets.install(old)
        assertTrue(tickets.markTimedOut(old))

        val monitorHeld = CountDownLatch(1)
        val doHandoff = CountDownLatch(1)
        val monitorReleased = CountDownLatch(1)
        val oldStarted = CountDownLatch(1)
        val oldDone = CountDownLatch(1)
        var holderSawMonitor = false
        var oldResult: PhoneOverlayReadLease? = null

        val holder =
            Thread {
                synchronized(tickets) {
                    holderSawMonitor = Thread.holdsLock(tickets)
                    monitorHeld.countDown()
                    check(doHandoff.await(5, TimeUnit.SECONDS)) { "handoff was not requested" }
                    // Reentrant while the monitor is held: Main installs B before releasing A's waiter.
                    tickets.clear()
                    tickets.install(newer)
                    tickets.markTimedOut(newer)
                }
                monitorReleased.countDown()
            }.apply { isDaemon = true }

        val oldCleanup =
            Thread {
                oldStarted.countDown()
                oldResult = tickets.settleTimedOut(old)
                oldDone.countDown()
            }.apply { isDaemon = true }

        try {
            holder.start()
            assertTrue("holder must acquire the ticket monitor", monitorHeld.await(2, TimeUnit.SECONDS))
            oldCleanup.start()
            assertTrue("old cleanup must start", oldStarted.await(2, TimeUnit.SECONDS))

            // A is queued behind the monitor while the handoff to B is performed under that monitor.
            assertFalse(
                "old timeout cleanup must wait for the monitored handoff",
                oldDone.await(200, TimeUnit.MILLISECONDS),
            )

            doHandoff.countDown()
            assertTrue("handoff must release", monitorReleased.await(2, TimeUnit.SECONDS))
            assertTrue("old cleanup must finish after the handoff", oldDone.await(2, TimeUnit.SECONDS))
        } finally {
            doHandoff.countDown()
            holder.join(2_000)
            oldCleanup.join(2_000)
        }

        assertTrue("the test held the real ticket monitor", holderSawMonitor)
        assertNull("A must not settle after B owns the ticket", oldResult)
        assertEquals(newer, tickets.activeLease())
        assertEquals(newer, tickets.uncertainLease)
    }

    @Test
    fun `bounded concurrent handoffs always leave B active and never A uncertain`() {
        val iterations = 250
        repeat(iterations) {
            val tickets = PhoneOverlayReadHideTickets()
            tickets.install(old)
            assertTrue(tickets.markTimedOut(old))

            val start = CountDownLatch(1)
            val oldCleanup =
                Thread {
                    start.await()
                    tickets.settleTimedOut(old)
                }.apply { isDaemon = true }
            val mainHandoff =
                Thread {
                    start.await()
                    tickets.clear()
                    tickets.install(newer)
                    tickets.markTimedOut(newer)
                }.apply { isDaemon = true }

            oldCleanup.start()
            mainHandoff.start()
            start.countDown()
            oldCleanup.join(2_000)
            mainHandoff.join(2_000)

            assertFalse("old cleanup thread did not finish", oldCleanup.isAlive)
            assertFalse("main handoff thread did not finish", mainHandoff.isAlive)
            assertEquals("iteration $it must end on B", newer, tickets.activeLease())
            assertEquals("iteration $it must not leave A uncertain", newer, tickets.uncertainLease)
        }
    }

    private fun lease(
        owner: String,
        generation: Long,
    ): PhoneOverlayReadLease =
        PhoneOverlayReadLease(
            owner = owner,
            generation = generation,
            revision = 5,
            phase = OverlayPhase.COLLECTION_HIDDEN,
        )
}
