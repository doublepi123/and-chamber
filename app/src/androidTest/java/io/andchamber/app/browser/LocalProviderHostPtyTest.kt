package io.andchamber.app.browser

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure cold PTY handshake parser checks; the real handshake still comes from the host. */
class LocalProviderHostPtyTest {
    private fun body(runId: String = "run-cold-1", cold: Boolean = true, ok: Boolean = true, exitCode: Int = 0, report: JSONObject? = null) =
        JSONObject()
            .put("runId", runId)
            .put("cold", cold)
            .put("ok", ok)
            .put("exitCode", exitCode)
            .put("report", report ?: JSONObject()
                .put("tag", "X86_LOCAL_PROVIDER_PTY")
                .put("ok", true)
                .put("standaloneMarkerLine", true)
                .put("markersComplete", true)
                .put("doneLine", true)
                .put("gitVersion", "git version 2")
                .put("nodeVersion", "v24.18.1")
                .put("opencodeVersion", "opencode v2.0.32"))

    @Test
    fun currentRunWithRealReportPasses() {
        val result = LocalProviderRun.HostPtyHandshake.fromJson(body(), "run-cold-1")
        assertTrue(result.currentRun)
        assertTrue(result.ok)
    }

    @Test
    fun staleRunOrNonColdHandshakeIsIgnored() {
        val stale = LocalProviderRun.HostPtyHandshake.fromJson(body(runId = "old-run"), "run-cold-1")
        assertFalse(stale.currentRun)
        assertFalse(stale.ok)
        val notCold = LocalProviderRun.HostPtyHandshake.fromJson(body(cold = false), "run-cold-1")
        assertFalse(notCold.currentRun)
        assertFalse(notCold.ok)
    }

    @Test
    fun noExitZeroFailuresOrMissingMarkerVersionsNeverPass() {
        assertFalse(LocalProviderRun.HostPtyHandshake.fromJson(body(exitCode = 1), "run-cold-1").ok)
        assertFalse(LocalProviderRun.HostPtyHandshake.fromJson(body(ok = false), "run-cold-1").ok)
        val incomplete = JSONObject()
            .put("tag", "X86_LOCAL_PROVIDER_PTY")
            .put("ok", true)
            .put("standaloneMarkerLine", true)
            .put("markersComplete", false)
            .put("doneLine", true)
            .put("gitVersion", "git version 2")
            .put("nodeVersion", "v24.18.1")
            .put("opencodeVersion", "opencode v2.0.32")
        assertFalse(LocalProviderRun.HostPtyHandshake.fromJson(body(report = incomplete), "run-cold-1").ok)
        val noVersions = JSONObject()
            .put("ok", true)
            .put("standaloneMarkerLine", true)
            .put("markersComplete", true)
            .put("doneLine", true)
        assertFalse(LocalProviderRun.HostPtyHandshake.fromJson(body(report = noVersions), "run-cold-1").ok)
    }

    @Test
    fun handshakeEvidenceNeverCarriesCookieOrToken() {
        val result = LocalProviderRun.HostPtyHandshake.fromJson(body(), "run-cold-1")
        val text = result.evidence().toString()
        assertFalse(text.contains("cookie", ignoreCase = true))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("password", ignoreCase = true))
        assertTrue(text.contains("standaloneMarkerLine"))
    }
}
