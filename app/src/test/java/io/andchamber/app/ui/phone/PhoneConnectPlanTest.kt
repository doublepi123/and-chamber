package io.andchamber.app.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The connect card without typed ports: what it offers, and that its retries never grant. */
class PhoneConnectPlanTest {
    private val base = PhoneScreenState(available = true, enabled = true)

    private fun state(
        device: PhoneDeviceState,
        pending: PhonePending? = null,
    ) = base.copy(device = device, pending = pending)

    private fun plan(
        device: PhoneDeviceState,
        pairAgain: Boolean = false,
        manualPair: Boolean = false,
        manualConnect: Boolean = false,
        pending: PhonePending? = null,
    ) = connectPlan(state(device, pending), pairAgain, manualPair, manualConnect)

    @Test
    fun `old states keep working without the new fields`() {
        // Previews and older tests build PhoneDeviceState without discovery fields.
        val d = PhoneDeviceState()
        assertEquals(PhoneDiscovery.Idle, d.pairingDiscovery)
        assertEquals(PhoneDiscovery.Idle, d.connectDiscovery)
        assertEquals(PhoneAutoConnect.Idle, d.autoConnect)
        assertNull(autoConnectStatus(d))
    }

    @Test
    fun `main path needs no port - first time pairs through the notification, no port fields`() {
        val p = plan(PhoneDeviceState(paired = null))
        assertEquals(ConnectPrimary.PairWithNotification, p.primary)
        assertTrue(p.showsPairing)
        assertFalse("pairing port only after the person asks", p.showsManualPairing)
        assertFalse("connection port only after the person asks", p.showsManualConnect)
        // The notification is for this phone; nothing else has to be typed first.
        assertEquals(PhoneUiLimits.DEFAULT_HOST, pairingHost(manualConnect = false, customHost = false, hostText = ""))
        // A stale typed address doesn't leak into the main path while the manual section is closed.
        assertEquals(PhoneUiLimits.DEFAULT_HOST, pairingHost(manualConnect = false, customHost = true, hostText = "192.0.2.9"))
    }

    @Test
    fun `already paired - the main button finds and connects, no form shown`() {
        val p = plan(PhoneDeviceState(paired = true))
        assertEquals(ConnectPrimary.AutoConnect, p.primary)
        assertTrue(p.offersAutoConnect && p.canAutoConnect)
        assertFalse(p.showsPairing)
        assertFalse(p.showsManualConnect)
        assertNull(p.pairingSearch)
    }

    @Test
    fun `known unpaired - no find-and-connect that would only be refused`() {
        val p = plan(PhoneDeviceState(paired = false))
        assertFalse(p.offersAutoConnect)
        assertFalse(p.canAutoConnect)
    }

    @Test
    fun `pair again reopens pairing as the main step`() {
        val p = plan(PhoneDeviceState(paired = true), pairAgain = true)
        assertEquals(ConnectPrimary.PairWithNotification, p.primary)
        assertTrue(p.showsPairing)
    }

    @Test
    fun `manual fallback appears only when the person opens it`() {
        assertTrue(plan(PhoneDeviceState(), manualPair = true).showsManualPairing)
        assertTrue(plan(PhoneDeviceState(paired = true), manualConnect = true).showsManualConnect)
        // Manual pairing is part of the pairing section; folded with it once paired.
        assertFalse(plan(PhoneDeviceState(paired = true), manualPair = true).showsManualPairing)
        // Another address only counts once the manual section is open, and must be valid.
        assertEquals("192.0.2.9", pairingHost(manualConnect = true, customHost = true, hostText = "192.0.2.9"))
        assertNull(pairingHost(manualConnect = true, customHost = true, hostText = "192.0.2.9:5555"))
    }

    @Test
    fun `pairing notice follows the real search - searching, then only the code`() {
        val waiting = PhoneDeviceState(pairingNotice = PhonePairingNotice.Waiting)
        assertEquals(PairingSearch.Searching, pairingSearch(waiting.copy(pairingDiscovery = PhoneDiscovery.Searching)))
        assertEquals(PairingSearch.EnterCode, pairingSearch(waiting.copy(pairingDiscovery = PhoneDiscovery.Ready)))
        // A native side that reports no search keeps the plain "enter the code" notice.
        assertEquals(PairingSearch.EnterCode, pairingSearch(waiting))
        assertNull(pairingSearch(PhoneDeviceState()))
        assertNull(pairingSearch(PhoneDeviceState(pairingNotice = PhonePairingNotice.NotificationsBlocked, pairingDiscovery = PhoneDiscovery.TimedOut)))
    }

    @Test
    fun `failed searches say why, also after the attempt ended`() {
        val waiting = PhoneDeviceState(pairingNotice = PhonePairingNotice.Waiting)
        assertEquals(PairingSearch.Problem(DiscoveryProblem.NotFound), pairingSearch(waiting.copy(pairingDiscovery = PhoneDiscovery.TimedOut)))
        assertEquals(PairingSearch.Problem(DiscoveryProblem.Unavailable), pairingSearch(waiting.copy(pairingDiscovery = PhoneDiscovery.Unavailable)))
        assertEquals(PairingSearch.Problem(DiscoveryProblem.Ambiguous), pairingSearch(waiting.copy(pairingDiscovery = PhoneDiscovery.Ambiguous)))
        assertEquals(
            PairingSearch.Problem(DiscoveryProblem.NotFound),
            pairingSearch(PhoneDeviceState(pairingDiscovery = PhoneDiscovery.TimedOut)),
        )
        assertEquals(PairingSearch.Problem(DiscoveryProblem.Ambiguous), plan(waiting.copy(pairingDiscovery = PhoneDiscovery.Ambiguous)).pairingSearch)
    }

    @Test
    fun `automatic connect reports only real states and blocks a second press`() {
        val paired = PhoneDeviceState(paired = true)
        assertEquals(AutoConnectStatus.Searching, autoConnectStatus(paired.copy(connectDiscovery = PhoneDiscovery.Searching)))
        assertEquals(AutoConnectStatus.Connecting, autoConnectStatus(paired.copy(connectDiscovery = PhoneDiscovery.Ready, autoConnect = PhoneAutoConnect.Running)))
        assertEquals(AutoConnectStatus.Failed, autoConnectStatus(paired.copy(connectDiscovery = PhoneDiscovery.Ready, autoConnect = PhoneAutoConnect.Failed)))
        assertEquals(AutoConnectStatus.Problem(DiscoveryProblem.NotFound), autoConnectStatus(paired.copy(connectDiscovery = PhoneDiscovery.TimedOut)))
        assertEquals(AutoConnectStatus.Problem(DiscoveryProblem.Ambiguous), autoConnectStatus(paired.copy(connectDiscovery = PhoneDiscovery.Ambiguous)))
        assertEquals(AutoConnectStatus.Problem(DiscoveryProblem.Unavailable), autoConnectStatus(paired.copy(connectDiscovery = PhoneDiscovery.Unavailable)))
        // Found is not connected: no status claims a link until the link itself says so.
        assertNull(autoConnectStatus(paired.copy(connectDiscovery = PhoneDiscovery.Ready)))
        assertNull(autoConnectStatus(paired.copy(link = PhoneLink.Connected, autoConnect = PhoneAutoConnect.Failed)))

        assertFalse(plan(paired.copy(connectDiscovery = PhoneDiscovery.Searching)).canAutoConnect)
        assertFalse(plan(paired.copy(autoConnect = PhoneAutoConnect.Running)).canAutoConnect)
        assertFalse(plan(paired.copy(link = PhoneLink.Connecting)).canAutoConnect)
        assertFalse(plan(paired, pending = PhonePending.Pairing).canAutoConnect)
        // After a failure the person may search again.
        assertTrue(plan(paired.copy(connectDiscovery = PhoneDiscovery.TimedOut)).canAutoConnect)
        assertTrue(plan(paired.copy(autoConnect = PhoneAutoConnect.Failed)).canAutoConnect)
    }

    @Test
    fun `search again only restarts the local search and never grants or continues`() {
        val calls = mutableListOf<String>()
        val actions =
            PhoneControlActions(
                onBeginPairingNotification = { calls += "pair:$it" },
                onAutoConnect = { calls += "auto" },
                onConnect = { calls += "connect" },
                onPair = { calls += "pairManual" },
                onGrant = { calls += "grant" },
                onSaveStanding = { calls += "saveStanding" },
                onContinueInChat = { calls += "continue" },
                onAcknowledgeChecked = { calls += "ack" },
            )
        retryDiscovery(DiscoveryKind.Pairing, actions)
        retryDiscovery(DiscoveryKind.Connect, actions)
        assertEquals(listOf("pair:${PhoneUiLimits.DEFAULT_HOST}", "auto"), calls)
    }

    @Test
    fun `reconnecting a halted task is offered, but resuming still needs a fresh confirmation`() {
        val lease = PhoneLease(PhoneTarget("Notes", "com.example.notes"), "x", setOf(PhoneActionScope.Tap), 1)
        val halted =
            base.copy(
                stopChannel = PhoneStopChannel.Verified,
                device = PhoneDeviceState(link = PhoneLink.Lost, paired = true, autoConnect = PhoneAutoConnect.Running),
                control = PhoneControl.Halted(lease, PhoneHaltReason.ConnectionLost, true, PhoneLastAction.NoneInFlight),
            )
        assertTrue(showsReconnect(halted))
        assertFalse(canOfferResume(halted))
    }

    @Test
    fun `the pairing code is never saved state and is cleared on stop`() {
        val source = File(SETUP_SOURCE).readText()
        val card = source.substring(source.indexOf("internal fun ConnectCard("), source.indexOf("private fun PairingNoticeBlock("))
        assertTrue("code must stay in plain remember", card.contains("var code by remember { mutableStateOf(\"\") }"))
        assertFalse("code must never be saveable", Regex("var code by rememberSaveable").containsMatchIn(card))
        assertTrue("code is cleared on ON_STOP", card.contains("LifecycleEventEffect(Lifecycle.Event.ON_STOP) { code = \"\" }"))
        assertTrue("code is cleared on a problem", card.contains("if (device.problem != null) code = \"\""))
        assertTrue("code is dropped right after handing it over", card.contains("code = \"\"\n                                    actions.onPair("))
    }

    private companion object {
        /** Gradle runs unit tests in the module directory. */
        const val SETUP_SOURCE = "src/main/java/io/andchamber/app/ui/phone/PhoneSetupCards.kt"
    }
}
