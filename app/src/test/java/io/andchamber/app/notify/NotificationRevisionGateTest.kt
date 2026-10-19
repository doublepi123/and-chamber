package io.andchamber.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs the production NotificationRevisionGate. The "system" is an in-memory map id -> extras token, and it
 * can lag behind the gate: [publishedToSystem] is what the system has actually shown so far.
 */
class NotificationRevisionGateTest {
    private class FakeSystem {
        val shown = LinkedHashMap<Int, String>()
        val removed = mutableListOf<Int>()
        var readFailure = false

        fun readRevision(id: Int): String? {
            if (readFailure) throw SecurityException("notification access revoked")
            return shown[id]
        }

        fun remove(id: Int) {
            removed += id
            shown.remove(id)
        }
    }

    private var counter = 0
    private val system = FakeSystem()
    private val state = NotificationRevisionState()
    private val gate =
        NotificationRevisionGate(
            readRevision = system::readRevision,
            remove = system::remove,
            newToken = { "rev-${++counter}" },
            state = state,
        )

    /** Posts a notification the way the notifier does: under the gate, system updated at once. */
    private fun post(id: Int, sent: Boolean = false): String =
        gate.publish(id, sent) { token ->
            system.shown[id] = token
            token
        }

    @Test
    fun sentCleanupRemovesItsOwnRevision() {
        val sent = post(7, sent = true)
        assertTrue(gate.cancelIfCurrent(7, sent))
        assertNull(system.shown[7])
    }

    @Test
    fun newPostOnSameIdSurvivesOldSentCleanup() {
        val sent = post(7, sent = true)
        post(7)
        assertFalse(gate.cancelIfCurrent(7, sent))
        assertTrue(system.shown.containsKey(7))
    }

    @Test
    fun keptDraftSurvivesOldSentCleanup() {
        val sent = post(7, sent = true)
        gate.publish(7, sent = false) { token -> system.shown[7] = token }
        assertFalse(gate.cancelIfCurrent(7, sent))
        assertTrue(system.shown.containsKey(7))
    }

    @Test
    fun newStatusInvalidatesEarlierSent() {
        val first = post(7, sent = true)
        val second = post(7, sent = true)
        assertFalse(gate.cancelIfCurrent(7, first))
        assertTrue(gate.cancelIfCurrent(7, second))
    }

    @Test
    fun explicitCancelInvalidatesOutstandingSent() {
        val sent = post(7, sent = true)
        gate.cancel(7)
        post(7)
        assertFalse(gate.cancelIfCurrent(7, sent))
        assertTrue(system.shown.containsKey(7))
    }

    @Test
    fun asyncPostNotYetVisibleToSystemStillBlocksOldCleanup() {
        // The new post is committed in the gate, but the system still reports the old "Sent" token.
        val sent = post(7, sent = true)
        val newer = "pending-newer"
        gate.publish(7, sent = false) { token -> token } // committed by the gate; the system is not updated yet
        // Simulate the system lagging: it still shows the old Sent, so the old token matches the system.
        assertEquals(sent, system.shown[7])
        assertFalse(gate.cancelIfCurrent(7, sent))
        assertTrue("old clean-up must not remove anything", system.removed.isEmpty())
        // Later, the system catches up with the newer post and is not removed by any stale clean-up.
        system.shown[7] = newer
        assertFalse(gate.cancelIfCurrent(7, sent))
        assertEquals(newer, system.shown[7])
    }

    @Test
    fun unreadableSystemNeverRemovesAndConsumesNothingElse() {
        val sent = post(7, sent = true)
        system.readFailure = true
        assertFalse(gate.cancelIfCurrent(7, sent))
        system.readFailure = false
        assertTrue(system.shown.containsKey(7))
        assertTrue(system.removed.isEmpty())
    }

    @Test
    fun absentNotificationIsNotRemoved() {
        assertFalse(gate.cancelIfCurrent(9, "rev-x"))
        assertTrue(system.removed.isEmpty())
    }

    @Test
    fun forgetReleasesPendingWithoutRemoving() {
        val sent = post(7, sent = true)
        gate.forget(7, sent)
        assertFalse(gate.cancelIfCurrent(7, sent))
        assertTrue(system.shown.containsKey(7))
    }

    @Test
    fun sharedStateAcrossInstancesIsOneAuthority() {
        // Two notifiers share one state, as the process-wide singleton does.
        val other = NotificationRevisionGate(system::readRevision, system::remove, { "rev-other" }, state)
        val sent = post(7, sent = true)
        other.publish(7, sent = false) { token -> system.shown[7] = token }
        assertFalse(gate.cancelIfCurrent(7, sent))
        assertTrue(system.shown.containsKey(7))
    }

    @Test
    fun writeCannotInterleaveBetweenCheckAndRemoval() {
        // The check reads the system's "Sent" and then waits on a latch. A concurrent write for the same id
        // must wait for the lock, so the event order is check -> remove -> write, with no sleep-based guess.
        val sent = post(7, sent = true)
        val checkEntered = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val raced =
            NotificationRevisionGate(
                readRevision = { id ->
                    events += "check"
                    val value = system.shown[id]
                    checkEntered.countDown()
                    releaseCheck.await(5, TimeUnit.SECONDS)
                    value
                },
                remove = { id ->
                    events += "remove"
                    system.remove(id)
                },
                newToken = { "race-${++counter}" },
                state = NotificationRevisionState(),
            )
        raced.publish(7, sent = true) { token -> system.shown[7] = token; token }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val cancel = pool.submit<Boolean> { raced.cancelIfCurrent(7, system.shown[7]!!) }
            assertTrue(checkEntered.await(5, TimeUnit.SECONDS))
            val writeStarted = CountDownLatch(1)
            val write =
                pool.submit {
                    writeStarted.countDown()
                    raced.publish(7, sent = false) { token ->
                        events += "write"
                        system.shown[7] = token
                    }
                }
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            releaseCheck.countDown()
            assertTrue(cancel.get(5, TimeUnit.SECONDS))
            write.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("check", "remove", "write"), events.toList())
            assertTrue(system.shown.containsKey(7))
        } finally {
            pool.shutdownNow()
        }
    }
}
