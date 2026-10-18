package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.process.Readiness.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
    private fun report(
        status: String? = "ok",
        ready: Boolean = false,
        running: Boolean = false,
        error: String? = null,
        serverId: String? = "id-1",
    ) = HealthReport(status, ready, running, error, serverId)

    @Test
    fun `running requires ok AND opencode ready`() {
        assertEquals(Verdict.Ready, Readiness.verdict(report(ready = true, running = true)))
        assertEquals(Verdict.Waiting, Readiness.verdict(report(ready = false, running = true)))
    }

    @Test
    fun `web server not ok keeps waiting`() {
        assertEquals(Verdict.Waiting, Readiness.verdict(report(status = "starting", ready = true)))
        assertEquals(Verdict.Waiting, Readiness.verdict(null))
    }

    @Test
    fun `opencode error while not running fails the start with its message`() {
        val verdict = Readiness.verdict(report(ready = false, running = false, error = "port 4096 busy"))
        assertEquals(Verdict.OpenCodeFailed("port 4096 busy"), verdict)
    }

    @Test
    fun `an error while opencode is still running is not yet a failure`() {
        assertEquals(Verdict.Waiting, Readiness.verdict(report(error = "transient", running = true)))
    }

    @Test
    fun `identity is stable when the server id does not change`() {
        assertTrue(Readiness.identityStable(listOf("a", "a", null, "a")))
        assertTrue(Readiness.identityStable(listOf(null, null)))
    }

    @Test
    fun `identity changing between polls means another instance answered`() {
        assertFalse(Readiness.identityStable(listOf("a", "b")))
    }

    @Test
    fun `health body parses the readiness fields`() {
        val parsed =
            HealthProbe.parse(
                """{"status":"ok","isOpenCodeReady":true,"openCodeRunning":true,"serverId":"srv-9","lastOpenCodeError":null}""",
            )!!

        assertEquals("ok", parsed.status)
        assertTrue(parsed.isOpenCodeReady)
        assertEquals("srv-9", parsed.serverId)
        assertNull(parsed.lastOpenCodeError)
    }

    @Test
    fun `a non-json body is not a health report`() {
        assertNull(HealthProbe.parse("<html>not openchamber</html>"))
    }
}
