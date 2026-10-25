package io.andchamber.app.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUiLogicTest {
    private val host = "io.andchamber.app"
    private val notes = PhoneTarget("Notes", "com.example.notes")
    private val lease = PhoneLease(notes, "Add a note", setOf(PhoneActionScope.Tap), expiresAtMs = 600_000)
    private val connected =
        PhoneScreenState(
            available = true,
            enabled = true,
            hostPackage = host,
            device = PhoneDeviceState(link = PhoneLink.Connected),
            stopChannel = PhoneStopChannel.Verified,
            targets = listOf(notes),
        )

    private fun halted(
        confirmed: Boolean,
        last: PhoneLastAction,
    ) = connected.copy(control = PhoneControl.Halted(lease, PhoneHaltReason.Paused, confirmed, last))

    @Test
    fun `production default shows nothing but the unavailable note`() {
        assertEquals(PhoneStep.Unavailable, phoneStep(PhoneScreenState()))
        assertFalse(PhoneScreenState().enabled)
    }

    @Test
    fun `one step at a time in the expected order`() {
        assertEquals(PhoneStep.Off, phoneStep(PhoneScreenState(available = true)))
        assertEquals(PhoneStep.Connect, phoneStep(connected.copy(device = PhoneDeviceState(link = PhoneLink.Lost))))
        assertEquals(PhoneStep.Grant, phoneStep(connected))
        assertEquals(PhoneStep.Running, phoneStep(connected.copy(control = PhoneControl.Agent(lease))))
        // A halt stays in front even when the link dropped, so the person sees what may still apply.
        assertEquals(
            PhoneStep.Halted,
            phoneStep(halted(true, PhoneLastAction.MayStillApply).copy(device = PhoneDeviceState(link = PhoneLink.Lost))),
        )
        val ended = connected.copy(task = PhoneTask.Ended(PhoneTaskResult(notes, "x", emptyList())))
        assertEquals(PhoneStep.Result, phoneStep(ended))
    }

    @Test
    fun `host, its test ids, system and settings apps are blocked`() {
        assertEquals(TargetBlock.Host, targetBlock(PhoneTarget("AndChamber", host), host))
        assertEquals(TargetBlock.Host, targetBlock(PhoneTarget("Test", "$host.debugtest"), host))
        assertEquals(TargetBlock.System, targetBlock(PhoneTarget("Settings", "com.android.settings"), host))
        assertEquals(TargetBlock.System, targetBlock(PhoneTarget("Perms", "com.google.android.permissioncontroller"), host))
        assertEquals(TargetBlock.System, targetBlock(PhoneTarget("Android", "android"), host))
        assertEquals(TargetBlock.System, targetBlock(PhoneTarget("Vendor", "com.vendor.launcher", system = true), host))
        assertEquals(TargetBlock.Invalid, targetBlock(PhoneTarget("Bad", "not a package"), host))
        assertNull(targetBlock(notes, host))
    }

    @Test
    fun `allowed apps are listed before blocked ones and search matches label or package`() {
        val list =
            listOf(
                PhoneTarget("Settings", "com.android.settings"),
                PhoneTarget("zeta", "com.example.zeta"),
                PhoneTarget("Alpha", "com.example.alpha"),
            )
        val shown = visibleTargets(list, host, "")
        assertEquals(listOf("Alpha", "zeta", "Settings"), shown.map { it.first.label })
        assertEquals(listOf("zeta"), visibleTargets(list, host, "ZET").map { it.first.label })
        assertEquals(listOf("Alpha"), visibleTargets(list, host, "example.alpha").map { it.first.label })
    }

    private val manySystem = (1..15).map { PhoneTarget("Sys $it", "com.android.sys$it") }
    private val fixture = PhoneTarget("PhoneFixture", "com.example.phonefixture")

    @Test
    fun `default picker lists only choosable apps and folds blocked ones into a count`() {
        val listing = targetListing(manySystem + fixture + PhoneTarget("Me", host), host, "", null)
        assertEquals(listOf(fixture), listing.rows.map { it.first })
        assertEquals(0, listing.moreAllowed)
        assertEquals(16, listing.hiddenBlocked)
    }

    @Test
    fun `default picker caps allowed rows and keeps the chosen app listed`() {
        val apps = (1..12).map { PhoneTarget("App %02d".format(it), "com.example.app$it") }
        val listing = targetListing(apps, host, "", null)
        assertEquals(TARGETS_DEFAULT_ROWS, listing.rows.size)
        assertEquals(12 - TARGETS_DEFAULT_ROWS, listing.moreAllowed)
        val chosen = targetListing(apps, host, "", "com.example.app12")
        assertEquals("com.example.app12", chosen.rows.first().first.packageName)
        assertEquals(12 - TARGETS_DEFAULT_ROWS - 1, chosen.moreAllowed)
    }

    @Test
    fun `a search shows matching blocked apps with their reason, a few at most`() {
        val settings = targetListing(manySystem + fixture, host, "Sys 1", null)
        // "Sys 1", "Sys 10".."Sys 15": allowed none, blocked capped.
        assertTrue(settings.rows.all { it.second == TargetBlock.System })
        assertEquals(TARGETS_SEARCH_BLOCKED_ROWS, settings.rows.size)
        assertEquals(7 - TARGETS_SEARCH_BLOCKED_ROWS, settings.hiddenBlocked)
        val mixed = targetListing(manySystem + fixture + PhoneTarget("Settings", "com.android.settings"), host, "fixture", null)
        assertEquals(listOf(fixture), mixed.rows.map { it.first })
        assertEquals(0, mixed.hiddenBlocked)
        // A blocked app never stays pinned as "chosen".
        assertTrue(targetListing(manySystem, host, "", "com.android.sys1").rows.isEmpty())
    }

    @Test
    fun `no grant without a verified stop entry, whatever else is filled in`() {
        PhoneStopChannel.entries.filter { it != PhoneStopChannel.Verified }.forEach { channel ->
            assertEquals(GrantBlock.StopChannel, grantBlock(connected.copy(stopChannel = channel), notes, "task", setOf(PhoneActionScope.Tap)))
        }
        assertNull(grantBlock(connected, notes, "task", setOf(PhoneActionScope.Tap)))
    }

    @Test
    fun `grant needs a connection, an allowed app, a task, an action and no pending step`() {
        val tap = setOf(PhoneActionScope.Tap)
        assertEquals(GrantBlock.NotConnected, grantBlock(connected.copy(device = PhoneDeviceState()), notes, "t", tap))
        assertEquals(GrantBlock.NoTarget, grantBlock(connected, null, "t", tap))
        assertEquals(GrantBlock.TargetNotAllowed, grantBlock(connected, PhoneTarget("Me", host), "t", tap))
        assertEquals(GrantBlock.NoSummary, grantBlock(connected, notes, "  ", tap))
        assertEquals(GrantBlock.SummaryTooLong, grantBlock(connected, notes, "x".repeat(PhoneUiLimits.MAX_SUMMARY_CHARS + 1), tap))
        assertEquals(GrantBlock.NoActions, grantBlock(connected, notes, "t", emptySet()))
        assertEquals(GrantBlock.Busy, grantBlock(connected.copy(pending = PhonePending.Connecting), notes, "t", tap))
    }

    @Test
    fun `resume is offered only after a confirmed stop with nothing that may still apply`() {
        assertFalse(canOfferResume(connected))
        assertFalse(canOfferResume(connected.copy(control = PhoneControl.Agent(lease))))
        assertFalse(canOfferResume(halted(confirmed = false, PhoneLastAction.NoneInFlight)))
        assertFalse(canOfferResume(halted(confirmed = true, PhoneLastAction.MayStillApply)))
        assertTrue(canOfferResume(halted(confirmed = true, PhoneLastAction.NoneInFlight)))
        assertTrue(canOfferResume(halted(confirmed = true, PhoneLastAction.UserChecked)))
        assertFalse(canOfferResume(halted(true, PhoneLastAction.NoneInFlight).copy(stopChannel = PhoneStopChannel.NotificationsDisabled)))
        assertFalse(canOfferResume(halted(true, PhoneLastAction.NoneInFlight).copy(device = PhoneDeviceState(link = PhoneLink.Lost))))
        assertFalse(canOfferResume(halted(true, PhoneLastAction.NoneInFlight).copy(pending = PhonePending.Granting)))
    }

    @Test
    fun `core halt flags never read as stopped while anything is unsettled`() {
        val settling = phoneHaltFacts(pausing = true, inFlightMutation = true, outcomeUnknown = false)
        assertEquals(PhoneHaltFacts(confirmed = false, lastAction = PhoneLastAction.MayStillApply), settling)
        // Either flag alone is enough to stay unconfirmed.
        assertFalse(phoneHaltFacts(pausing = true, inFlightMutation = false, outcomeUnknown = false).confirmed)
        assertFalse(phoneHaltFacts(pausing = false, inFlightMutation = true, outcomeUnknown = false).confirmed)
        // Settled but uncertain: stopped, yet the last action may still apply until the person checks.
        assertEquals(
            PhoneHaltFacts(true, PhoneLastAction.MayStillApply),
            phoneHaltFacts(pausing = false, inFlightMutation = false, outcomeUnknown = true),
        )
        assertEquals(
            PhoneHaltFacts(true, PhoneLastAction.UserChecked),
            phoneHaltFacts(pausing = false, inFlightMutation = false, outcomeUnknown = true, acknowledged = true),
        )
        // An acknowledgement never overrides an unsettled call.
        assertFalse(phoneHaltFacts(pausing = true, inFlightMutation = false, outcomeUnknown = true, acknowledged = true).confirmed)
        assertEquals(
            PhoneHaltFacts(true, PhoneLastAction.NoneInFlight),
            phoneHaltFacts(pausing = false, inFlightMutation = false, outcomeUnknown = false),
        )
        val facts = phoneHaltFacts(pausing = true, inFlightMutation = true, outcomeUnknown = false)
        assertFalse(canOfferResume(halted(facts.confirmed, facts.lastAction)))
    }

    private fun failed(token: String?) =
        connected.copy(
            device = connected.device.copy(problem = PhoneDeviceProblem.Other),
            diagnostics = PhoneDiagnostics(lastError = token),
        )

    @Test
    fun `a failed grant is explained with fixed copy, never the raw token`() {
        assertEquals(PhoneFeedback.ToolNotReady, phoneFeedback(failed(PhoneDiagnosticTokens.GRANT_ROUTE_UNAVAILABLE)))
        assertEquals(PhoneFeedback.ScreenLocked, phoneFeedback(failed(PhoneDiagnosticTokens.SCREEN_LOCKED)))
        assertEquals(PhoneFeedback.TargetUnavailable, phoneFeedback(failed(PhoneDiagnosticTokens.TARGET_UNAVAILABLE)))
        assertEquals(PhoneFeedback.GrantNotStarted, phoneFeedback(failed(null)))
        assertEquals(PhoneFeedback.GrantNotStarted, phoneFeedback(failed("DRIVER_UNAVAILABLE")))
    }

    @Test
    fun `feedback stays local and quiet when it would duplicate or mislead`() {
        // The connect step explains its own problems.
        assertNull(phoneFeedback(failed(null).copy(device = PhoneDeviceState(problem = PhoneDeviceProblem.Other))))
        // Specific problems are not generic feedback.
        assertNull(phoneFeedback(connected.copy(device = connected.device.copy(problem = PhoneDeviceProblem.ConnectRefused))))
        // While a new attempt runs, the old message is hidden.
        assertNull(phoneFeedback(failed(null).copy(pending = PhonePending.Granting)))
        assertNull(phoneFeedback(connected))
        // During a halt a generic failure never speaks for earlier actions.
        val haltedFailure = halted(true, PhoneLastAction.MayStillApply).copy(
            device = connected.device.copy(problem = PhoneDeviceProblem.Other),
        )
        assertEquals(PhoneFeedback.RequestNotDone, phoneFeedback(haltedFailure))
    }

    private fun gated(token: String) =
        connected.copy(
            device = connected.device.copy(problem = PhoneDeviceProblem.Other),
            diagnostics = PhoneDiagnostics(lastError = token),
        )

    @Test
    fun `runtime tokens map to a persistent notice and replace the generic feedback`() {
        val restart = gated(PhoneDiagnosticTokens.RUNTIME_RESTART_REQUIRED)
        val stopped = gated(PhoneDiagnosticTokens.RUNTIME_NOT_RUNNING)
        assertEquals(PhoneRuntimeGate.RestartRequired, phoneRuntimeGate(restart))
        assertEquals(PhoneRuntimeGate.NotRunning, phoneRuntimeGate(stopped))
        assertTrue(showsRuntimeGate(restart))
        // Never both notices for one cause.
        assertNull(phoneFeedback(restart))
        assertNull(phoneFeedback(stopped))
        // Shown on the connect step too (no problem field needed), hidden on the off page.
        val connecting = PhoneScreenState(available = true, enabled = true, diagnostics = PhoneDiagnostics(lastError = PhoneDiagnosticTokens.RUNTIME_NOT_RUNNING))
        assertTrue(showsRuntimeGate(connecting))
        assertFalse(showsRuntimeGate(connecting.copy(enabled = false)))
        // Cleared token, no notice.
        assertNull(phoneRuntimeGate(connected))
    }

    @Test
    fun `a runtime that isn't ready blocks granting and resuming, never pairing`() {
        val tap = setOf(PhoneActionScope.Tap)
        val restart = gated(PhoneDiagnosticTokens.RUNTIME_RESTART_REQUIRED)
        assertEquals(GrantBlock.RuntimeNotReady, grantBlock(restart, notes, "t", tap))
        val haltedGated = halted(true, PhoneLastAction.NoneInFlight)
            .copy(diagnostics = PhoneDiagnostics(lastError = PhoneDiagnosticTokens.RUNTIME_NOT_RUNNING))
        assertFalse(canOfferResume(haltedGated))
        // The connect step is not gated: pairing and connecting stay available.
        assertEquals(PhoneStep.Connect, phoneStep(restart.copy(device = PhoneDeviceState())))
    }

    @Test
    fun `the draft takes an agent proposal once and keeps later edits`() {
        val proposal = connected.copy(request = PhoneAgentRequest("Add milk", notes.packageName))
        val first = PhoneGrantDraft().withRequest(proposal)
        assertEquals("Add milk", first.summary)
        assertEquals(notes.packageName, first.packageName)
        val edited = first.copy(summary = "Add oat milk", minutes = 10)
        // The same proposal seen again (back from Home) leaves the person's edits alone.
        assertEquals(edited, edited.withRequest(proposal))
        // A new proposal replaces the text.
        assertEquals("Buy eggs", edited.withRequest(connected.copy(request = PhoneAgentRequest("Buy eggs"))).summary)
        // No proposal: an existing choice stays.
        assertEquals(edited, edited.withRequest(connected))
    }

    @Test
    fun `reading the screen needs a live lease and nothing in flight`() {
        assertFalse(canObserve(connected))
        assertFalse(canObserve(halted(true, PhoneLastAction.NoneInFlight)))
        assertTrue(canObserve(connected.copy(control = PhoneControl.Agent(lease))))
        assertFalse(canObserve(connected.copy(control = PhoneControl.Agent(lease, actionInFlight = true))))
        assertFalse(canObserve(connected.copy(control = PhoneControl.Agent(lease), pending = PhonePending.Observing)))
        assertFalse(
            canObserve(connected.copy(control = PhoneControl.Agent(lease), device = PhoneDeviceState(link = PhoneLink.Lost))),
        )
    }

    @Test
    fun `a halted task with the link down offers the connect form and still needs a fresh confirmation`() {
        val lost = PhoneDeviceState(link = PhoneLink.Lost, paired = true, problem = PhoneDeviceProblem.ConnectionLost)
        val down = halted(true, PhoneLastAction.NoneInFlight).copy(device = lost)
        // The halt stays in front (reason, task, stop controls), with the connect form under it.
        assertEquals(PhoneStep.Halted, phoneStep(down))
        assertTrue(showsReconnect(down))
        assertFalse(canOfferResume(down))
        // Also while a dispatched action may still apply: the transport can come back, the warning stays.
        val uncertain = halted(true, PhoneLastAction.MayStillApply).copy(device = lost)
        assertTrue(showsReconnect(uncertain))
        assertTrue(showsReconnect(halted(true, PhoneLastAction.NoneInFlight).copy(device = PhoneDeviceState(link = PhoneLink.Connecting))))

        // Connected again: the form goes away, the control is still Halted, nothing resumed.
        val back = down.copy(device = PhoneDeviceState(link = PhoneLink.Connected, paired = true))
        assertFalse(showsReconnect(back))
        assertEquals(PhoneStep.Halted, phoneStep(back))
        assertTrue(back.control is PhoneControl.Halted)
        // Continuing is offered, but only as the native confirmation the person must press.
        assertTrue(canOfferResume(back))
        // With the uncertain action unchecked, not even that.
        assertFalse(canOfferResume(uncertain.copy(device = back.device)))
    }

    @Test
    fun `scroll anchor changes on a new page, not within one`() {
        val connect = connected.copy(device = PhoneDeviceState(link = PhoneLink.Disconnected))
        // Connect -> Grant and Running -> Halted are new pages.
        assertTrue(phoneScrollAnchor(connect) != phoneScrollAnchor(connected))
        val running = connected.copy(control = PhoneControl.Agent(lease))
        val stopped = halted(true, PhoneLastAction.NoneInFlight)
        assertTrue(phoneScrollAnchor(running) != phoneScrollAnchor(stopped))
        // A reconnect inside Halted counts, so the kept task and Continue come back into view.
        val down = stopped.copy(device = PhoneDeviceState(link = PhoneLink.Lost))
        assertTrue(phoneScrollAnchor(down) != phoneScrollAnchor(stopped))
        // Connecting... and Lost are the same page: typing a port isn't interrupted.
        assertEquals(
            phoneScrollAnchor(down),
            phoneScrollAnchor(stopped.copy(device = PhoneDeviceState(link = PhoneLink.Connecting))),
        )
        assertEquals(phoneScrollAnchor(connect), phoneScrollAnchor(connect.copy(device = PhoneDeviceState(link = PhoneLink.Connecting))))
        // Progress, notices and countdown don't move the page.
        assertEquals(phoneScrollAnchor(connected), phoneScrollAnchor(connected.copy(pending = PhonePending.Granting, busy = PhoneBusy.RequestIgnored)))
        assertEquals(phoneScrollAnchor(running), phoneScrollAnchor(connected.copy(control = PhoneControl.Agent(lease, actionInFlight = true))))
    }

    @Test
    fun `the connect form is not added outside a halt`() {
        assertFalse(showsReconnect(connected.copy(device = PhoneDeviceState())))
        assertFalse(showsReconnect(connected.copy(control = PhoneControl.Agent(lease), device = PhoneDeviceState(link = PhoneLink.Lost))))
        assertFalse(showsReconnect(PhoneScreenState(available = true)))
    }

    @Test
    fun `a tool reporting ok never counts as checked`() {
        assertEquals(PhoneOverall.NeedsCheck, overallResult(emptyList()))
        assertEquals(PhoneOverall.NeedsCheck, overallResult(listOf(PhoneResultItem("a", PhoneCheck.AgentReported))))
        assertEquals(
            PhoneOverall.Partial,
            overallResult(listOf(PhoneResultItem("a", PhoneCheck.Verified), PhoneResultItem("b", PhoneCheck.Unknown))),
        )
        assertEquals(PhoneOverall.Verified, overallResult(listOf(PhoneResultItem("a", PhoneCheck.Verified))))
    }

    @Test
    fun `ports, hosts and pairing codes are parsed strictly`() {
        assertEquals(37_123, parsePort(" 37123 "))
        assertNull(parsePort("0"))
        assertNull(parsePort("70000"))
        assertNull(parsePort("abc"))
        assertEquals("127.0.0.1", parseHost(" 127.0.0.1 "))
        assertEquals("phone.local", parseHost("phone.local"))
        assertEquals("fe80::1%wlan0", parseHost("fe80::1%wlan0"))
        assertNull(parseHost("127.0.0.1:5555"))
        assertNull(parseHost("adb://127.0.0.1"))
        assertNull(parseHost("a b"))
        assertNull(parseHost(""))
        assertEquals("482916", parsePairingCode("482 916"))
        assertNull(parsePairingCode("48291"))
        assertNull(parsePairingCode("48291a"))
        assertEquals("482916", sanitizePairingCodeInput("48-29 16 77"))
    }

    @Test
    fun `button and keyboard Go share one connect validation and busy guard`() {
        assertEquals(ConnectSubmit.Ready(PhoneConnectRequest("127.0.0.1", 37_123)), connectRequest("37123", false, "ignored", busy = false))
        // The custom host is used only when chosen, and must be valid.
        assertEquals(ConnectSubmit.Ready(PhoneConnectRequest("192.0.2.5", 5555)), connectRequest("5555", true, "192.0.2.5", busy = false))
        assertEquals(ConnectSubmit.Invalid, connectRequest("5555", true, "192.0.2.5:5555", busy = false))
        assertEquals(ConnectSubmit.Invalid, connectRequest("", false, "", busy = false))
        assertEquals(ConnectSubmit.Invalid, connectRequest("70000", false, "", busy = false))
        // A running step swallows repeated presses: nothing is sent, no errors are raised.
        assertEquals(ConnectSubmit.Busy, connectRequest("37123", false, "", busy = true))
    }

    @Test
    fun `pair request never prints its code`() {
        val text = PhonePairRequest("127.0.0.1", 40_000, "482916").toString()
        assertFalse(text.contains("482916"))
    }

    @Test
    fun `countdown rounds up and switches to seconds in the last minute`() {
        assertEquals(5, minutesLeft(300_000, 0))
        assertEquals(5, minutesLeft(240_001, 0))
        assertEquals(0, minutesLeft(1_000, 2_000))
        assertNull(secondsLeftInLastMinute(120_000, 0))
        assertEquals(42, secondsLeftInLastMinute(41_500, 0))
        assertEquals(0, secondsLeftInLastMinute(0, 5))
    }

    @Test
    fun `the agent suggestion preselects only an allowed listed app`() {
        assertEquals(notes, preselectedTarget(connected.copy(request = PhoneAgentRequest("t", notes.packageName))))
        assertNull(preselectedTarget(connected.copy(request = PhoneAgentRequest("t", "com.example.missing"))))
        val withHost = connected.copy(targets = listOf(PhoneTarget("Me", host)), request = PhoneAgentRequest("t", host))
        assertNull(preselectedTarget(withHost))
    }
}
