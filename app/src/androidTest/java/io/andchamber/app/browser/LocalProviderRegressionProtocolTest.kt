package io.andchamber.app.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure protocol checks; no device, runtime or host lane. */
class LocalProviderRegressionProtocolTest {
    private fun raw(
        kind: String = "tool",
        id: String = "cmd-1",
        runId: String = "run-12345",
        action: String? = "browser.snapshot",
        text: String? = null,
        input: JSONObject = JSONObject(),
        summary: JSONObject? = null,
    ) = JSONObject()
        .put("runId", runId)
        .put("id", id)
        .put("kind", kind)
        .apply {
            action?.let { put("action", it) }
            text?.let { put("text", it) }
            if (kind == "tool") put("input", input)
            summary?.let { put("summary", it) }
        }
        .toString()

    @Test
    fun validToolDeviceAndFinishCommandsParse() {
        val tool = LocalProviderRegressionProtocol.parse(raw(), "run-12345", emptySet())
        assertTrue(tool is LocalProviderRegressionProtocol.Parsed.Ok)
        assertEquals("browser.snapshot", (tool as LocalProviderRegressionProtocol.Parsed.Ok).command.action)

        val device = LocalProviderRegressionProtocol.parse(raw(kind = "device", text = "ui-target:Allow agent"), "run-12345", emptySet())
        assertTrue(device is LocalProviderRegressionProtocol.Parsed.Ok)

        val finish = LocalProviderRegressionProtocol.parse(
            raw(kind = "finish", summary = JSONObject().put("basic", true).put("cold", true)),
            "run-12345",
            emptySet(),
        )
        assertTrue(finish is LocalProviderRegressionProtocol.Parsed.Ok)
    }

    @Test
    fun staleRunDuplicateAndUnknownKindsAreRejected() {
        val stale = LocalProviderRegressionProtocol.parse(raw(runId = "old-run-1"), "run-12345", emptySet())
        assertEquals("stale-run", (stale as LocalProviderRegressionProtocol.Parsed.Rejected).reason)
        val duplicate = LocalProviderRegressionProtocol.parse(raw(), "run-12345", setOf("cmd-1"))
        assertEquals("duplicate-id", (duplicate as LocalProviderRegressionProtocol.Parsed.Rejected).reason)
        val unknown = LocalProviderRegressionProtocol.parse(raw(kind = "shell"), "run-12345", emptySet())
        assertEquals("unknown-kind", (unknown as LocalProviderRegressionProtocol.Parsed.Rejected).reason)
        val badInput = LocalProviderRegressionProtocol.parse(
            JSONObject().put("runId", "run-12345").put("id", "cmd-2").put("kind", "tool")
                .put("action", "browser.snapshot").put("input", "not-an-object").toString(),
            "run-12345",
            emptySet(),
        )
        assertEquals("invalid-input", (badInput as LocalProviderRegressionProtocol.Parsed.Rejected).reason)
    }

    @Test
    fun unknownToolActionAndArbitraryDeviceCommandsAreRejected() {
        val action = LocalProviderRegressionProtocol.parse(raw(action = "browser.eval"), "run-12345", emptySet())
        assertEquals("unknown-action", (action as LocalProviderRegressionProtocol.Parsed.Rejected).reason)
        for (text in listOf("eval:alert(1)", "shell:rm", "https://example.com", "ui-target:Delete", "fixture-field:password")) {
            val result = LocalProviderRegressionProtocol.parse(raw(kind = "device", text = text), "run-12345", emptySet())
            assertEquals("unknown-device-command", (result as LocalProviderRegressionProtocol.Parsed.Rejected).reason)
        }
    }

    @Test
    fun finishSummaryMustContainEveryRequiredCase() {
        val summary = JSONObject().put("basic", true).put("cold", true)
        assertEquals(emptyList<String>(), LocalProviderRegressionProtocol.missingCases(summary, listOf("basic", "cold")))
        assertEquals(listOf("mouse"), LocalProviderRegressionProtocol.missingCases(summary, listOf("basic", "mouse")))
        val failed = JSONObject().put("basic", false)
        assertEquals(listOf("basic"), LocalProviderRegressionProtocol.missingCases(failed, listOf("basic")))
    }

    @Test
    fun resultShapeKeepsRunIdAndIdAndNeverAddsDataOrErrorOnSuccess() {
        val success = LocalProviderRegressionProtocol.result("run-12345", "cmd-1", true, JSONObject().put("ok", true), null, null)
        assertEquals("run-12345", success.optString("runId"))
        assertEquals("cmd-1", success.optString("id"))
        assertTrue(success.optBoolean("ok"))
        assertTrue(!success.has("error"))
        val failed = LocalProviderRegressionProtocol.result("run-12345", "cmd-2", false, null, "failed", "nope")
        assertEquals("failed", failed.getJSONObject("error").optString("code"))
        assertEquals("run-12345", failed.optString("runId"))
    }

    @Test
    fun casesAcceptNonEmptySubsetsAndNameTheirScope() {
        val lifecycle = LocalProviderRegressionProtocol.parseCases(
            LocalProviderRegressionProtocol.LIFECYCLE_CASE_NAMES.joinToString(","),
        ) as LocalProviderRegressionProtocol.CasesParsed.Ok
        assertEquals("lifecycle", lifecycle.request.scope)
        assertTrue(lifecycle.request.partial)
        assertEquals(6, lifecycle.request.cases.size)

        val retirement = LocalProviderRegressionProtocol.parseCases(
            LocalProviderRegressionProtocol.RETIREMENT_CASE_NAMES.joinToString(","),
        ) as LocalProviderRegressionProtocol.CasesParsed.Ok
        assertEquals("retirement", retirement.request.scope)
        assertTrue(retirement.request.partial)

        val subset = LocalProviderRegressionProtocol.parseCases(
            "retirement-b-true-cancel-reopen,lifecycle-b5-enlarge-restore",
        ) as LocalProviderRegressionProtocol.CasesParsed.Ok
        assertEquals("custom", subset.request.scope)
        assertTrue(subset.request.partial)

        val full = LocalProviderRegressionProtocol.parseCases(
            LocalProviderRegressionProtocol.EXPECTED_CASES.reversed().joinToString(","),
        ) as LocalProviderRegressionProtocol.CasesParsed.Ok
        assertEquals("all", full.request.scope)
        assertTrue(!full.request.partial)
    }

    @Test
    fun casesRejectUnknownDuplicateAndEmptyNames() {
        for (raw in listOf(
            null,
            "",
            " , ",
            "lifecycle-b5-enlarge-restore,unknown-case",
            "lifecycle-b5-enlarge-restore,lifecycle-b5-enlarge-restore",
        )) {
            assertTrue(LocalProviderRegressionProtocol.parseCases(raw) is LocalProviderRegressionProtocol.CasesParsed.Rejected)
        }
        assertEquals(
            "cases-unknown:unknown-case",
            (LocalProviderRegressionProtocol.parseCases("retirement-a-primary-drag-lock,unknown-case") as LocalProviderRegressionProtocol.CasesParsed.Rejected).reason,
        )
        assertEquals(
            "cases-duplicate",
            (LocalProviderRegressionProtocol.parseCases(
                "retirement-a-primary-drag-lock,retirement-a-primary-drag-lock",
            ) as LocalProviderRegressionProtocol.CasesParsed.Rejected).reason,
        )
    }

    @Test
    fun statusMetadataUsesTheExactRequestForEveryPhaseAndNeverDefaults() {
        fun request(raw: String): LocalProviderRegressionProtocol.CaseRequest =
            (LocalProviderRegressionProtocol.parseCases(raw) as LocalProviderRegressionProtocol.CasesParsed.Ok).request

        val scenarios = listOf(
            request(LocalProviderRegressionProtocol.EXPECTED_CASES.joinToString(",")),
            request(LocalProviderRegressionProtocol.LIFECYCLE_CASE_NAMES.joinToString(",")),
            request(LocalProviderRegressionProtocol.RETIREMENT_CASE_NAMES.joinToString(",")),
            request("lifecycle-b5-enlarge-restore,retirement-b-true-cancel-reopen"),
        )
        val expectedScopes = listOf("all", "lifecycle", "retirement", "custom")
        val expectedPartials = listOf(false, true, true, true)
        val phases = listOf("booting", "await-ui", "ready", "failed", "done")

        for ((index, scenario) in scenarios.withIndex()) {
            assertEquals(expectedScopes[index], scenario.scope)
            assertEquals(expectedPartials[index], scenario.partial)
            for (phase in phases) {
                // Start with wrong/foreign values to prove the real metadata overwrites them and is
                // never read through a defaulting helper.
                val body = JSONObject()
                    .put("phase", phase)
                    .put("casePartial", "false")
                    .put("caseScope", "wrong")
                LocalProviderRegressionEvidence.applyCaseMetadata(body, scenario.cases, scenario.scope, scenario.partial)
                assertTrue(body.has("casePartial"))
                assertTrue(body.get("casePartial") is Boolean)
                assertEquals(scenario.partial, body.getBoolean("casePartial"))
                assertEquals(scenario.scope, body.getString("caseScope"))
                val cases = body.getJSONArray("expectedCases")
                assertEquals(scenario.cases.size, cases.length())
                for (position in 0 until cases.length()) {
                    assertEquals(scenario.cases[position], cases.getString(position))
                }
            }
        }
    }

    @Test
    fun caseMetadataIsTheSameObjectInStatusAndEvidence() {
        val request = (LocalProviderRegressionProtocol.parseCases(
            LocalProviderRegressionProtocol.LIFECYCLE_CASE_NAMES.joinToString(","),
        ) as LocalProviderRegressionProtocol.CasesParsed.Ok).request
        val status = LocalProviderRegressionEvidence.applyCaseMetadata(
            JSONObject().put("phase", "ready"), request.cases, request.scope, request.partial,
        )
        val evidence = LocalProviderRegressionEvidence.build(
            "run-12345", false, "finish-case-missing", request.cases, request.scope, request.partial,
            JSONObject(), 1, 1, 2, null, emptyList(), 0,
        )
        assertEquals(evidence.getJSONArray("expectedCases").toString(), status.getJSONArray("expectedCases").toString())
        assertEquals(evidence.getString("caseScope"), status.getString("caseScope"))
        assertEquals(evidence.getBoolean("casePartial"), status.getBoolean("casePartial"))
        assertEquals(true, status.getBoolean("casePartial"))
        assertEquals("lifecycle", status.getString("caseScope"))
    }

    @Test
    fun fixtureTargetAndTestCookieCommandsAreFixed() {
        assertTrue(LocalProviderRegressionProtocol.deviceAllowed("fixture-target:box,123.5,456.25"))
        assertTrue(LocalProviderRegressionProtocol.deviceAllowed("fixture-target:next,0,0"))
        assertTrue(!LocalProviderRegressionProtocol.deviceAllowed("fixture-target:apply,1,1"))
        assertTrue(!LocalProviderRegressionProtocol.deviceAllowed("fixture-target:box,NaN,1"))
        assertTrue(LocalProviderRegressionProtocol.deviceAllowed("cookie-read"))
        assertTrue(LocalProviderRegressionProtocol.deviceAllowed("cookie-write:0123456789abcdef0123456789abcdef"))
        assertTrue(LocalProviderRegressionProtocol.deviceAllowed("cookie-write:m1abc2def3"))
        assertTrue(!LocalProviderRegressionProtocol.deviceAllowed("cookie-write:oc_other=1"))
        assertTrue(!LocalProviderRegressionProtocol.deviceAllowed("cookie-write:abc"))
        assertTrue(!LocalProviderRegressionProtocol.deviceAllowed("cookie-write:0123456789abcdef; path=/"))
        assertTrue(!LocalProviderRegressionProtocol.deviceAllowed("cookie-write:0123456789abcdef other=1"))
    }

    @Test
    fun nonFiniteNumbersAndUnknownGateActionsAreRejected() {
        for (text in listOf(
            "finger:NaN,10,20,10,0,4",
            "finger:1,Infinity,20,10,0,4",
            "two-finger-scroll:10,20,Infinity",
            "pinch:NaN,20",
            "gate-native:unknown,10,20",
            "gate-native:enter,10",
            "gate-native:wheel,10,20",
            "gate-finger:down,NaN,20",
            "tap-label:Delete",
        )) {
            val result = LocalProviderRegressionProtocol.parse(raw(kind = "device", text = text), "run-12345", emptySet())
            assertEquals("unknown-device-command", (result as LocalProviderRegressionProtocol.Parsed.Rejected).reason)
        }
        for (text in listOf(
            "two-finger-scroll:10,20,0",
            "finger:10,20,10,10,0,0",
            "finger:10,20,10,10,0,65",
        )) {
            assertTrue(!LocalProviderRegressionProtocol.deviceAllowed(text))
        }
    }

    @Test
    fun fixtureTapLabelsParseButUiLabelsStayHostTapOnly() {
        for (label in LocalProviderRegressionProtocol.FIXTURE_TAP_LABELS) {
            val parsed = LocalProviderRegressionProtocol.parse(raw(kind = "device", text = "tap-label:$label"), "run-12345", emptySet())
            assertTrue(parsed is LocalProviderRegressionProtocol.Parsed.Ok)
        }
        assertTrue(LocalProviderRegressionProtocol.deviceAllowed("tap-label:Allow agent"))
        assertTrue(!LocalProviderRegressionProtocol.deviceAllowed("tap-label:Delete"))
    }

    @Test
    fun finishEvidencePhaseIsDoneOnlyWhenComplete() {
        val done = LocalProviderRegressionEvidence.build(
            "run-12345", true, null, LocalProviderRegressionProtocol.EXPECTED_CASES,
            "all", false,
            JSONObject().put("basic", true), 3, 4, 7, "ses_test", listOf("ses_test"), 1,
        )
        assertEquals("done", done.optString("phase"))
        assertEquals("all", done.optString("caseScope"))
        assertTrue(!done.optBoolean("casePartial"))
        assertEquals(3, done.optInt("toolCommands"))
        assertEquals(4, done.optInt("deviceCommands"))
        assertEquals(LocalProviderRegressionEvidence.HOLD_PROBE, done.optString("geometryHoldProbe"))
        assertTrue(!done.has("dispatched"))
        val failed = LocalProviderRegressionEvidence.build(
            "run-12345", false, "finish-case-missing:basic", LocalProviderRegressionProtocol.RETIREMENT_CASE_NAMES,
            "retirement", true,
            JSONObject(), 0, 0, 0, null, emptyList(), 0,
        )
        assertEquals("failed", failed.optString("phase"))
        assertEquals("finish-case-missing:basic", failed.optString("failure"))
        assertEquals("retirement", failed.optString("caseScope"))
        assertTrue(failed.optBoolean("casePartial"))
    }
}
