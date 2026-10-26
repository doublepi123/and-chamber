package io.andchamber.app.runtime.process

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePluginRegistrationProbeTest {
    @Test
    fun `success returns true without retrying`() = runBlocking {
        var attempts = 0
        val probe =
            PhonePluginRegistrationProbe(
                attempt = {
                    attempts += 1
                    true
                },
                timeoutMs = 5_000L,
                retryDelayMs = 100L,
                clock = { 0L },
                pause = { error("a successful probe must not pause") },
            )
        assertTrue(probe.awaitRegistered(1L))
        assertTrue(attempts == 1)
    }

    @Test
    fun `bounded failure stops at the deadline`() = runBlocking {
        var now = 0L
        var attempts = 0
        val probe =
            PhonePluginRegistrationProbe(
                attempt = {
                    attempts += 1
                    false
                },
                timeoutMs = 1_000L,
                retryDelayMs = 200L,
                clock = { now },
                pause = { sleepMs -> now += sleepMs },
            )
        assertFalse(probe.awaitRegistered(1L))
        assertTrue("must keep retrying until the bound", attempts >= 2)
        assertTrue("must not run past the deadline", now >= 1_000L)
    }

    @Test
    fun `cancellation is not converted into an ordinary failure`() = runBlocking {
        val cancelled = kotlinx.coroutines.CancellationException("cancelled")
        val probe =
            PhonePluginRegistrationProbe(
                attempt = { throw cancelled },
                timeoutMs = 5_000L,
                retryDelayMs = 100L,
                clock = { 0L },
                pause = { error("cancellation must not pause") },
            )
        val thrown =
            runCatching { probe.awaitRegistered(1L) }.exceptionOrNull()
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun `only a 2xx json output with ok true and the exact id is registration`() {
        assertTrue(
            phonePluginRegistrationAccepted(
                200,
                """{"output":{"ok":true,"id":"andchamber_phone"}}""",
            ),
        )
        assertTrue(
            phonePluginRegistrationAccepted(
                204,
                """{"output":{"id":"andchamber_phone","ok":true},"extra":1}""",
            ),
        )
        // Non-2xx, malformed, wrong id, ok false or a non-boolean ok are all failures.
        listOf(
            0,
            401,
            500,
        ).forEach { status ->
            assertFalse(phonePluginRegistrationAccepted(status, """{"output":{"ok":true,"id":"andchamber_phone"}}"""))
        }
        listOf(
            "",
            "not-json",
            """{"output":null}""",
            """{"output":{"ok":true}}""",
            """{"output":{"ok":true,"id":"other"}}""",
            """{"output":{"ok":false,"id":"andchamber_phone"}}""",
            """{"output":{"ok":"true","id":"andchamber_phone"}}""",
            """{"ok":true,"id":"andchamber_phone"}""",
        ).forEach { body ->
            assertFalse("body must not count as registration: $body", phonePluginRegistrationAccepted(200, body))
        }
    }
}
