package io.andchamber.app.ui.phone

import io.andchamber.app.ui.phone.overlay.overlayMinutesLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** "Permanent" is an explicit choice, travels as Long.MAX_VALUE, and never becomes a countdown. */
class PhonePermanentTimeTest {
    private val notes = PhoneTarget("Notes", "com.example.notes")

    @Test
    fun `the sentinel matches the native one and only that exact value counts`() {
        assertEquals(Long.MAX_VALUE, PhoneUiLimits.PERMANENT_MS)
        assertTrue(isPermanentTime(Long.MAX_VALUE))
        assertFalse(isPermanentTime(Long.MAX_VALUE - 1))
        assertFalse(isPermanentTime(8 * 3_600_000L))
    }

    @Test
    fun `defaults stay bounded and the old choices are kept`() {
        assertEquals(listOf(1, 4, 8), STANDING_WINDOW_HOURS)
        assertEquals(4, DEFAULT_STANDING_WINDOW_HOURS)
        assertEquals(listOf(2, 5, 10, 15), PhoneUiLimits.DURATION_CHOICES_MIN)
        assertEquals(5, PhoneUiLimits.DEFAULT_DURATION_MIN)
        // Permanent is offered last, as an extra choice.
        assertEquals(listOf(1, 4, 8, PERMANENT_CHOICE), STANDING_WINDOW_CHOICES)
        assertEquals(listOf(2, 5, 10, 15, PERMANENT_CHOICE), STANDING_PER_TASK_CHOICES)
        val fresh = PhoneStanding()
        assertFalse(fresh.perTaskPermanent)
        assertEquals(PhoneUiLimits.DEFAULT_DURATION_MIN, initialPerTaskChoice(fresh))
    }

    @Test
    fun `explicit permanent choices are sent as MAX_VALUE, finite ones as before`() {
        val both = standingRequest(listOf(notes), DEFAULT_ACTIONS, PERMANENT_CHOICE, PERMANENT_CHOICE)
        assertEquals(Long.MAX_VALUE, both.windowMs)
        assertEquals(Long.MAX_VALUE, both.perTaskMs)
        val windowOnly = standingRequest(listOf(notes), DEFAULT_ACTIONS, PERMANENT_CHOICE, 5)
        assertEquals(Long.MAX_VALUE, windowOnly.windowMs)
        assertEquals(5 * 60_000L, windowOnly.perTaskMs)
        val taskOnly = standingRequest(listOf(notes), DEFAULT_ACTIONS, 4, PERMANENT_CHOICE)
        assertEquals(4 * 3_600_000L, taskOnly.windowMs)
        assertEquals(Long.MAX_VALUE, taskOnly.perTaskMs)
        val finite = standingRequest(listOf(notes), DEFAULT_ACTIONS, 8, 15)
        assertEquals(8 * 3_600_000L, finite.windowMs)
        assertEquals(15 * 60_000L, finite.perTaskMs)
    }

    @Test
    fun `editing a saved permanent scope keeps it permanent, new and finite ones start bounded`() {
        assertEquals(DEFAULT_STANDING_WINDOW_HOURS, initialWindowChoice(PhoneStandingStatus.NotSet))
        assertEquals(PERMANENT_CHOICE, initialWindowChoice(PhoneStandingStatus.Active(Long.MAX_VALUE)))
        assertEquals(DEFAULT_STANDING_WINDOW_HOURS, initialWindowChoice(PhoneStandingStatus.Active(1_000_000L)))
        // A huge but finite end time is not the sentinel.
        assertEquals(DEFAULT_STANDING_WINDOW_HOURS, initialWindowChoice(PhoneStandingStatus.Active(Long.MAX_VALUE - 1)))
        assertEquals(DEFAULT_STANDING_WINDOW_HOURS, initialWindowChoice(PhoneStandingStatus.Expired))
        assertEquals(DEFAULT_STANDING_WINDOW_HOURS, initialWindowChoice(PhoneStandingStatus.Revoked))
        // Saving the reopened permanent choice sends the sentinel again, not 4 h.
        val resaved = standingRequest(listOf(notes), DEFAULT_ACTIONS, initialWindowChoice(PhoneStandingStatus.Active(Long.MAX_VALUE)), 5)
        assertEquals(Long.MAX_VALUE, resaved.windowMs)
    }

    @Test
    fun `a saved permanent per-task limit reopens as permanent, including the mapper stand-in`() {
        assertEquals(PERMANENT_CHOICE, initialPerTaskChoice(PhoneStanding(perTaskPermanent = true)))
        assertEquals(PERMANENT_CHOICE, initialPerTaskChoice(PhoneStanding(perTaskMinutes = Int.MAX_VALUE)))
        assertEquals(10, initialPerTaskChoice(PhoneStanding(perTaskMinutes = 10)))
        // An odd saved value falls back to the bounded default, never to permanent.
        assertEquals(PhoneUiLimits.DEFAULT_DURATION_MIN, initialPerTaskChoice(PhoneStanding(perTaskMinutes = 30)))
    }

    @Test
    fun `permanent deadlines never become a minute or second countdown`() {
        val now = 1_000_000L
        val active = PhoneStandingStatus.Active(Long.MAX_VALUE)
        assertTrue(standingWindowPermanent(active))
        assertNull(standingMinutesLeft(active, now))
        assertFalse(standingWindowPermanent(PhoneStandingStatus.Active(now + 60_000)))
        assertEquals(1, standingMinutesLeft(PhoneStandingStatus.Active(now + 60_000), now))

        assertNull(secondsLeftInLastMinute(Long.MAX_VALUE, now))
        assertNull(minutesLeft(Long.MAX_VALUE, now))
        assertEquals(TimeLeft.NoLimit, timeLeft(Long.MAX_VALUE, now))
        val lease = PhoneLease(notes, "x", setOf(PhoneActionScope.Tap), Long.MAX_VALUE)
        assertTrue(lease.permanent())
        assertFalse(lease.copy(expiresAtMs = now + 1).permanent())

        assertNull(overlayMinutesLeft(Long.MAX_VALUE, now))
        assertEquals(2, overlayMinutesLeft(now + 90_000, now))
    }

    @Test
    fun `huge finite deadlines saturate instead of wrapping into negative time`() {
        // One below the sentinel is finite: a real (huge) count, never negative, never "no limit".
        val nearMax = Long.MAX_VALUE - 1
        assertEquals(Int.MAX_VALUE, minutesLeft(nearMax, 0))
        assertEquals(TimeLeft.Minutes(Int.MAX_VALUE), timeLeft(nearMax, 0))
        // A negative clock must not wrap the difference.
        assertEquals(Int.MAX_VALUE, minutesLeft(nearMax, -10))
        assertEquals(0, minutesLeft(Long.MIN_VALUE, 0))
        assertEquals(0, secondsLeftInLastMinute(5, 10))
        assertEquals(42, secondsLeftInLastMinute(41_500, 0))
        assertEquals(1, ceilUnits(1, 60_000))
        assertEquals(Int.MAX_VALUE, ceilUnits(Long.MAX_VALUE - 1, 1_000))
        assertEquals(Int.MAX_VALUE, overlayMinutesLeft(nearMax, 0))
        assertEquals(0, overlayMinutesLeft(Long.MIN_VALUE, 0))
    }

    @Test
    fun `permanent is branched before multiplying, odd values never overflow or become permanent`() {
        assertEquals(Long.MAX_VALUE, standingPerTaskMs(PERMANENT_CHOICE))
        assertEquals(Long.MAX_VALUE, standingWindowMs(PERMANENT_CHOICE))
        // Int.MAX_VALUE * 60_000 would be a bogus finite value; it falls back to the default instead.
        assertEquals(PhoneUiLimits.DEFAULT_DURATION_MIN * 60_000L, standingPerTaskMs(Int.MAX_VALUE))
        assertEquals(DEFAULT_STANDING_WINDOW_HOURS * 3_600_000L, standingWindowMs(Int.MAX_VALUE))
        assertEquals(PhoneUiLimits.DEFAULT_DURATION_MIN * 60_000L, standingPerTaskMs(-1))
    }

    @Test
    fun `per-task permanent inside a 4 h scope reads as config, while the grant counts down the scope`() {
        assertEquals(PerTaskDisplay.UntilScopeEnds, perTaskDisplay(perTaskPermanent = true, windowPermanent = false))
        assertEquals(PerTaskDisplay.NoLimit, perTaskDisplay(perTaskPermanent = true, windowPermanent = true))
        assertEquals(PerTaskDisplay.Minutes, perTaskDisplay(perTaskPermanent = false, windowPermanent = true))
        // The real grant ends with the scope: a finite deadline, so a countdown, not "no time limit".
        val now = 10_000L
        val grant = PhoneLease(notes, "x", setOf(PhoneActionScope.Tap), expiresAtMs = now + 4 * 3_600_000L)
        assertFalse(grant.permanent())
        assertEquals(TimeLeft.Minutes(240), timeLeft(grant.expiresAtMs, now))
        // Only a genuinely permanent grant (both permanent) has no countdown.
        assertEquals(TimeLeft.NoLimit, timeLeft(Long.MAX_VALUE, now))
    }

    @Test
    fun `a permanent scope is still a live standing scope, not a different readiness`() {
        val state =
            PhoneScreenState(
                available = true,
                enabled = true,
                device = PhoneDeviceState(link = PhoneLink.Connected),
                stopChannel = PhoneStopChannel.Verified,
                standing =
                    PhoneStanding(
                        status = PhoneStandingStatus.Active(Long.MAX_VALUE),
                        apps = listOf(notes),
                        perTaskPermanent = true,
                        runtime = PhoneRuntimeStatus(PhoneToolStatus.Ready),
                    ),
            )
        assertEquals(StandingReadiness.Ready, standingReadiness(state))
        // Losing the link still stops chat use, permanent or not.
        assertEquals(StandingReadiness.NotConnected, standingReadiness(state.copy(device = PhoneDeviceState(link = PhoneLink.Lost))))
        assertEquals(StandingReadiness.NotConnected, standingReadiness(state.copy(enabled = false)))
    }

    @Test
    fun `permanent copy says what still stops and that nothing resumes by itself`() {
        val en = load("values/phone_strings.xml")
        val zh = load("values-zh/phone_strings.xml")
        val enNote = en.getValue("phone_standing_permanent_note")
        listOf("time runs out", "Taking over", "locking the screen", "connection", "turning phone control off", "restarts", "doesn", "timeout")
            .forEach { assertTrue("en note lacks '$it'", enNote.contains(it)) }
        val zhNote = zh.getValue("phone_standing_permanent_note")
        listOf("不会因为时间到而结束", "接管", "锁屏", "连接断开", "关闭手机控制", "重启后", "不会自己恢复", "超时")
            .forEach { assertTrue("zh note lacks '$it'", zhNote.contains(it)) }
        assertEquals("永久", zh.getValue("phone_standing_permanent"))
        assertEquals("Permanent", en.getValue("phone_standing_permanent"))
        // Running a permanent task still lists the stops.
        assertTrue(zh.getValue("phone_running_auto_pause_permanent").contains("锁屏"))
        assertTrue(en.getValue("phone_running_auto_pause_permanent").contains("lock the screen"))
        // No promise that it runs forever.
        val all = (en.values + zh.values).joinToString("\n").lowercase()
        listOf("never stops", "runs forever", "永不停止", "一直运行").forEach { assertTrue("found '$it'", it !in all) }
        assertEquals("不限时", load("values-zh/phone_overlay_strings.xml").getValue("phone_overlay_no_time_limit"))
    }

    private fun load(path: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res", path))
        val nodes = document.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i) as org.w3c.dom.Element
            node.getAttribute("name") to node.textContent
        }
    }
}
