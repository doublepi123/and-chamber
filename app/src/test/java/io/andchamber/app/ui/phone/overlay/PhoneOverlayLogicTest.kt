package io.andchamber.app.ui.phone.overlay

import io.andchamber.app.ui.phone.PhoneStep
import io.andchamber.app.ui.phone.showsOverlaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneOverlayLogicTest {
    @Test
    fun `take over is the primary key while the agent holds or may still hold control`() {
        listOf(OverlayPhase.Running, OverlayPhase.Pausing, OverlayPhase.Paused).forEach {
            assertEquals(it.name, OverlayPrimary.TakeOver, overlayPrimary(it))
        }
    }

    @Test
    fun `pending stops disable the key instead of inviting repeat presses`() {
        listOf(OverlayPhase.Taking, OverlayPhase.NeedUserPending, OverlayPhase.Ending).forEach {
            assertEquals(it.name, OverlayPrimary.Disabled, overlayPrimary(it))
            assertTrue(isPending(it))
            assertTrue(panelActions(it).isEmpty())
        }
        assertTrue(isPending(OverlayPhase.Pausing))
        assertFalse(isPending(OverlayPhase.TakenOver))
    }

    @Test
    fun `nothing in the overlay grants or resumes`() {
        // There is no resume/grant action at all; continuing means opening the native page.
        OverlayPhase.entries.forEach { phase ->
            val names = panelActions(phase).map { it.name }
            assertFalse(phase.name, names.any { it.contains("Resume") || it.contains("Grant") || it.contains("Continue") })
        }
        assertEquals(OverlayPrimary.OpenNative, overlayPrimary(OverlayPhase.TakenOver))
        assertEquals(OverlayPrimary.OpenNative, overlayPrimary(OverlayPhase.NeedUser))
    }

    @Test
    fun `pause is offered only from running, and can be escalated to take over`() {
        OverlayPhase.entries.forEach { phase ->
            assertEquals(phase.name, phase == OverlayPhase.Running, OverlayAction.Pause in panelActions(phase))
        }
        assertEquals(listOf(OverlayAction.TakeOver), panelActions(OverlayPhase.Pausing))
    }

    @Test
    fun `unknown has no retry, unconfirmed only asks to stop again`() {
        assertFalse(panelActions(OverlayPhase.Unknown).contains(OverlayAction.RetryTakeOver))
        assertEquals(listOf(OverlayAction.OpenNative, OverlayAction.RetryTakeOver), panelActions(OverlayPhase.StopUnconfirmed))
    }

    @Test
    fun `the result card is closable only after revocation was confirmed`() {
        OverlayPhase.entries.forEach { phase ->
            assertEquals(phase.name, phase == OverlayPhase.Ended, OverlayAction.CloseResult in panelActions(phase))
        }
    }

    @Test
    fun `end confirmation shows only where end may be requested`() {
        val drop = PhoneOverlayState(phase = OverlayPhase.Running, endConfirm = true)
        assertTrue(showsPanel(drop))
        // A stale drop while a stop is pending or after the end doesn't open a second end path.
        assertFalse(showsPanel(drop.copy(phase = OverlayPhase.Ending)))
        assertFalse(showsPanel(drop.copy(phase = OverlayPhase.Ended)))
        assertFalse(canRequestEnd(OverlayPhase.Taking))
        assertTrue(canRequestEnd(OverlayPhase.TakenOver))
        assertFalse(showsPanel(PhoneOverlayState(phase = OverlayPhase.Running)))
        assertTrue(showsPanel(PhoneOverlayState(phase = OverlayPhase.Running, expanded = true)))
    }

    @Test
    fun `recent shows the newest three and results group by source`() {
        val events =
            listOf(
                OverlayEvent("a", OverlaySource.NativeObserved, 1),
                OverlayEvent("b", OverlaySource.Sent, 4),
                OverlayEvent("c", OverlaySource.AgentNote, 3),
                OverlayEvent("d", OverlaySource.SentUnknown, 2),
                OverlayEvent("e", OverlaySource.NativeConfirmed, 5),
            )
        assertEquals(listOf("e", "b", "c"), recentEvents(events).map { it.text })
        val groups = resultGroups(events)
        assertEquals(listOf("e", "a"), groups.native.map { it.text })
        assertEquals(listOf("b", "d"), groups.sent.map { it.text })
        assertEquals(listOf("c"), groups.agent.map { it.text })
    }

    @Test
    fun `minutes round up and are absent without a lease`() {
        assertNull(overlayMinutesLeft(null, 0))
        assertEquals(4, overlayMinutesLeft(180_001, 0))
        assertEquals(0, overlayMinutesLeft(10, 20))
        assertEquals(Ago.JustNow, ago(1_000, 3_000))
        assertEquals(Ago.Seconds(12), ago(0, 12_500))
        assertEquals(Ago.Minutes(2), ago(0, 150_000))
    }

    @Test
    fun `settings walk permission, test, confirm, mode in order`() {
        val base = PhoneOverlaySettingsState(available = true)
        assertEquals(OverlaySetupStep.Unavailable, overlaySetupStep(PhoneOverlaySettingsState()))
        assertEquals(OverlaySetupStep.Unavailable, overlaySetupStep(base.copy(permission = OverlayPermission.Unsupported)))
        assertEquals(OverlaySetupStep.Permission, overlaySetupStep(base))
        assertEquals(OverlaySetupStep.Permission, overlaySetupStep(base.copy(permission = OverlayPermission.NotGranted)))
        val granted = base.copy(permission = OverlayPermission.Granted)
        assertEquals(OverlaySetupStep.Test, overlaySetupStep(granted))
        assertEquals(OverlaySetupStep.Test, overlaySetupStep(granted.copy(test = OverlayTest.TimedOut)))
        assertEquals(OverlaySetupStep.ConfirmSeen, overlaySetupStep(granted.copy(test = OverlayTest.TapReceived)))
        assertEquals(OverlaySetupStep.ChooseMode, overlaySetupStep(granted.copy(test = OverlayTest.Seen)))
        assertEquals(OverlaySetupStep.On, overlaySetupStep(granted.copy(test = OverlayTest.Seen, mode = OverlayMode.OverlayAndNotification)))
    }

    @Test
    fun `permission alone never enables overlay mode, and revocation drops it`() {
        val granted = PhoneOverlaySettingsState(available = true, permission = OverlayPermission.Granted)
        assertFalse(canChooseOverlay(granted))
        assertFalse(canChooseOverlay(granted.copy(test = OverlayTest.TapReceived)))
        assertTrue(canChooseOverlay(granted.copy(test = OverlayTest.Seen)))
        assertFalse(canChooseOverlay(granted.copy(test = OverlayTest.Seen, pending = true)))
        // Revoked after choosing overlay: back to the permission step, never "On".
        val revoked = granted.copy(test = OverlayTest.Seen, mode = OverlayMode.OverlayAndNotification, permission = OverlayPermission.NotGranted)
        assertEquals(OverlaySetupStep.Permission, overlaySetupStep(revoked))
    }

    @Test
    fun `header reports the chosen mode with the truth, never notifications-only for a saved overlay choice`() {
        val overlay = PhoneOverlaySettingsState(available = true, mode = OverlayMode.OverlayAndNotification, permission = OverlayPermission.Granted)
        // Restart: overlay was chosen, permission kept, demo not run this time.
        assertEquals(OverlayHeader.OverlayNotRechecked, overlayHeader(overlay))
        assertEquals(io.andchamber.app.R.string.phone_overlay_settings_selected_unchecked, overlayHeader(overlay).text())
        assertTrue(overlayHeader(overlay).text() != io.andchamber.app.R.string.phone_overlay_settings_off)
        assertEquals(OverlayHeader.OverlayNotRechecked, overlayHeader(overlay.copy(test = OverlayTest.TimedOut)))
        assertEquals(OverlayHeader.OverlayOn, overlayHeader(overlay.copy(test = OverlayTest.Seen)))
        // Permission gone or not hostable: still the person's choice, reported as blocked.
        assertEquals(OverlayHeader.OverlayBlocked, overlayHeader(overlay.copy(permission = OverlayPermission.NotGranted, test = OverlayTest.Seen)))
        assertEquals(OverlayHeader.OverlayBlocked, overlayHeader(overlay.copy(available = false)))
        assertEquals(io.andchamber.app.R.string.phone_overlay_settings_selected_blocked_hint, overlayHeader(overlay.copy(permission = OverlayPermission.NotGranted)).hint())
        // Notifications only is reported as such whatever the permission.
        assertEquals(OverlayHeader.NotificationOnly, overlayHeader(overlay.copy(mode = OverlayMode.NotificationOnly, test = OverlayTest.Seen)))
        assertNull(OverlayHeader.NotificationOnly.hint())
    }

    @Test
    fun `notifications only can always be chosen, overlay only after permission and a passed test`() {
        val base = PhoneOverlaySettingsState(available = true)
        val denied = base.copy(permission = OverlayPermission.NotGranted)
        val failed = base.copy(permission = OverlayPermission.Granted, test = OverlayTest.NotSeen)
        val notRun = base.copy(permission = OverlayPermission.Granted)
        listOf(base, denied, failed, notRun, failed.copy(test = OverlayTest.TimedOut), notRun.copy(test = OverlayTest.TapReceived)).forEach {
            assertTrue("$it", showsModeChooser(it))
            assertTrue("$it", canChooseNotificationOnly(it))
            assertFalse("$it", canChooseOverlay(it))
        }
        assertFalse(canChooseNotificationOnly(denied.copy(pending = true)))
        // A saved overlay choice can be switched off even where the card can't otherwise be used.
        val unhostable = PhoneOverlaySettingsState(available = false, mode = OverlayMode.OverlayAndNotification)
        assertTrue(showsModeChooser(unhostable))
        assertFalse(canChooseOverlay(unhostable))
        assertFalse(showsModeChooser(PhoneOverlaySettingsState()))
        assertTrue(canChooseOverlay(notRun.copy(test = OverlayTest.Seen)))
    }

    @Test
    fun `settings card stays out of the way while off or while the agent acts`() {
        assertFalse(showsOverlaySettings(PhoneStep.Unavailable))
        assertFalse(showsOverlaySettings(PhoneStep.Off))
        assertFalse(showsOverlaySettings(PhoneStep.Running))
        assertTrue(showsOverlaySettings(PhoneStep.Connect))
        assertTrue(showsOverlaySettings(PhoneStep.Grant))
    }
}
