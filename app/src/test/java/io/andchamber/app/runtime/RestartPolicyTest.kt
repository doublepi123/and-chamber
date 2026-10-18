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
        assertNull(policy.nextDelayMillis())
    }

    @Test
    fun `reset restarts the backoff for the next crash`() {
        val policy = RestartPolicy(listOf(1_000L, 2_000L))
        policy.nextDelayMillis()
        policy.nextDelayMillis()
        assertNull(policy.nextDelayMillis())

        policy.reset()

        assertEquals(1_000L, policy.nextDelayMillis())
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
