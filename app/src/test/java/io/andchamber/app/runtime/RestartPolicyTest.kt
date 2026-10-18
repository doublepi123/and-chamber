package io.andchamber.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestartPolicyTest {
    @Test
    fun `backs off through each step then gives up`() {
        val policy = RestartPolicy(listOf(1_000L, 2_000L))

        assertEquals(1_000L, policy.nextDelayMillis())
        assertEquals(2_000L, policy.nextDelayMillis())
        assertNull(policy.nextDelayMillis())
    }

    @Test
    fun `manual reset starts from a full budget`() {
        val policy = RestartPolicy(listOf(1_000L, 2_000L))
        policy.nextDelayMillis()
        policy.nextDelayMillis()
        assertNull(policy.nextDelayMillis())

        policy.reset()

        assertEquals(1_000L, policy.nextDelayMillis())
    }

    @Test
    fun `budget is not refilled by a short healthy blip`() {
        val policy = RestartPolicy(listOf(1_000L), healthyResetMillis = 120_000L)
        policy.nextDelayMillis()
        policy.onHealthy(0L)
        policy.onHealthy(60_000L)

        assertNull(policy.nextDelayMillis())
    }

    @Test
    fun `budget refills only after the server stays healthy for the full window`() {
        val policy = RestartPolicy(listOf(1_000L), healthyResetMillis = 120_000L)
        policy.nextDelayMillis()
        policy.onHealthy(0L)
        policy.onHealthy(120_000L)

        assertEquals(1_000L, policy.nextDelayMillis())
    }

    @Test
    fun `a crash restarts the healthy window`() {
        val policy = RestartPolicy(listOf(1_000L, 1_000L), healthyResetMillis = 120_000L)
        policy.onHealthy(0L)
        policy.nextDelayMillis() // crash: window closes
        policy.onHealthy(100_000L) // new window starts here
        policy.onHealthy(150_000L) // only 50 s into the new window: budget must NOT refill

        // Two steps exist: one was consumed by the crash, so exactly one remains.
        assertEquals(1_000L, policy.nextDelayMillis())
        assertNull(policy.nextDelayMillis())
    }

    @Test
    fun `at least one backoff step is required`() {
        var rejected = false
        try {
            RestartPolicy(emptyList())
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertEquals(true, rejected)
    }
}
