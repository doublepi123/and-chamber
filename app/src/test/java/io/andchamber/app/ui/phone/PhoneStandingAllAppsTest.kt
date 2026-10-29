package io.andchamber.app.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** "All apps" is an explicit standing choice: no list, no cap, an empty explicit list on save. */
class PhoneStandingAllAppsTest {
    private val host = "io.andchamber.app"
    private val notes = PhoneTarget("Notes", "com.example.notes")
    private val tap = setOf(PhoneActionScope.Tap)

    private val connected =
        PhoneScreenState(
            available = true,
            enabled = true,
            hostPackage = host,
            device = PhoneDeviceState(link = PhoneLink.Connected),
            stopChannel = PhoneStopChannel.Verified,
            targets = listOf(notes),
            standing = PhoneStanding(),
        )

    @Test
    fun `all apps sends an empty list and the flag`() {
        val request = standingRequest(listOf(notes), tap, hours = 4, minutes = 5, allApps = true)
        assertTrue(request.allApps)
        assertTrue(request.apps.isEmpty())
        assertEquals(tap, request.actions)
        assertEquals(standingWindowMs(4), request.windowMs)
        assertEquals(standingPerTaskMs(5), request.perTaskMs)
    }

    @Test
    fun `explicit mode is unchanged`() {
        val request = standingRequest(listOf(notes), tap, hours = 4, minutes = 5)
        assertFalse(request.allApps)
        assertEquals(listOf(notes), request.apps)
        assertEquals(PhoneStandingRequest(listOf(notes), tap, standingWindowMs(4), standingPerTaskMs(5)), request)
        // Same blocks as before.
        assertNull(standingBlock(connected, listOf(notes), tap))
        assertEquals(StandingBlock.NoApps, standingBlock(connected, emptyList(), tap))
        val many = (1..MAX_STANDING_APPS + 1).map { PhoneTarget("A$it", "com.example.a$it") }
        assertEquals(StandingBlock.TooManyApps, standingBlock(connected, many, tap))
        assertEquals(StandingBlock.AppNotAllowed, standingBlock(connected, listOf(PhoneTarget("Me", host)), tap))
        assertEquals(StandingBlock.AppNotAllowed, standingBlock(connected, listOf(PhoneTarget("Settings", "com.android.settings")), tap))
    }

    @Test
    fun `all apps is not blocked by the app list, cap or per-app rules`() {
        assertNull(standingBlock(connected, emptyList(), tap, allApps = true))
        val many = (1..MAX_STANDING_APPS + 5).map { PhoneTarget("A$it", "com.example.a$it") }
        assertNull(standingBlock(connected, many, tap, allApps = true))
        assertNull(standingBlock(connected, listOf(PhoneTarget("Me", host), PhoneTarget("Settings", "com.android.settings")), tap, allApps = true))
    }

    @Test
    fun `all apps still needs a connection, a stop entry, actions and no running step`() {
        assertEquals(StandingBlock.NotConnected, standingBlock(connected.copy(device = PhoneDeviceState()), emptyList(), tap, allApps = true))
        assertEquals(
            StandingBlock.StopChannel,
            standingBlock(connected.copy(stopChannel = PhoneStopChannel.NotificationsDisabled), emptyList(), tap, allApps = true),
        )
        assertEquals(StandingBlock.NoActions, standingBlock(connected, emptyList(), emptySet(), allApps = true))
        assertEquals(StandingBlock.Busy, standingBlock(connected.copy(pending = PhonePending.Connecting), emptyList(), tap, allApps = true))
    }

    @Test
    fun `an all-apps scope covers any app, an explicit scope only its list`() {
        val all = PhoneStanding(allApps = true)
        assertTrue(standingCoversApp(all, "com.example.anything"))
        assertTrue(standingCoversApp(all, "com.android.settings"))
        assertTrue(standingCoversApp(all, host))
        val explicit = PhoneStanding(apps = listOf(notes))
        assertTrue(standingCoversApp(explicit, notes.packageName))
        assertFalse(standingCoversApp(explicit, "com.example.other"))
        assertFalse(standingCoversApp(PhoneStanding(), notes.packageName))
    }

    @Test
    fun `a stopped task in an all-apps scope can still continue`() {
        val lease = PhoneLease(PhoneTarget("Bank", "com.example.bank"), "Check", tap, expiresAtMs = 600_000)
        val halted =
            connected.copy(
                control = PhoneControl.Halted(lease, PhoneHaltReason.TakenOver, true, PhoneLastAction.NoneInFlight),
                standing =
                    PhoneStanding(
                        status = PhoneStandingStatus.Active(PhoneUiLimits.PERMANENT_MS),
                        actions = tap,
                        runtime = PhoneRuntimeStatus(PhoneToolStatus.Ready),
                        allApps = true,
                    ),
            )
        assertTrue(canContinueInChat(halted))
        assertFalse(canContinueInChat(halted.copy(standing = halted.standing!!.copy(allApps = false))))
    }

    @Test
    fun `all apps copy names the risks in both languages`() {
        val en = load("values/phone_strings.xml")
        val zh = load("values-zh/phone_strings.xml")
        assertEquals("所有 App（包括设置、支付和 AndChamber）", zh["phone_standing_all_apps"])
        assertEquals("所有 App", zh["phone_standing_all_apps_summary"])
        assertEquals("All apps (including Settings, payments and AndChamber)", en["phone_standing_all_apps"])
        val zhConfirm = zh.getValue("phone_standing_all_apps_confirm_body")
        listOf("任何应用", "系统设置", "支付", "银行", "密码", "AndChamber 本身", "应用列表", "一个应用", "锁屏", "接管", "停止入口", "手机控制页面").forEach {
            assertTrue("zh lacks '$it'", zhConfirm.contains(it))
        }
        val enConfirm = en.getValue("phone_standing_all_apps_confirm_body")
        listOf("any app", "Settings", "payment", "bank", "password", "AndChamber itself", "list of apps", "one app", "Locking the screen", "taking over", "stop entries", "phone-control page").forEach {
            assertTrue("en lacks '$it'", enConfirm.contains(it))
        }
    }

    private fun load(path: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(ROOT, path))
        val nodes = document.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i) as org.w3c.dom.Element
            node.getAttribute("name") to node.textContent
        }
    }

    private companion object {
        val ROOT = File("src/main/res")
    }
}
