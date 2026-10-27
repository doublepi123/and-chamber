package io.andchamber.app.phone.integration

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.andchamber.app.R
import io.andchamber.app.phone.android.PhoneControlNotifications
import io.andchamber.app.phone.android.PhoneControlReturnActivity
import io.andchamber.app.phone.android.PhoneControlService
import io.andchamber.app.phone.android.PhoneDiscoveryService
import io.andchamber.app.phone.android.PhoneIdentityStore
import io.andchamber.app.phone.android.PhonePairingActionReceiver
import io.andchamber.app.phone.android.PhonePairingSession
import io.andchamber.app.phone.android.PrefsPhoneIntegrationStore
import io.andchamber.app.phone.android.PhoneTargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Integration wiring that only a device can prove: the manifest entries the system needs, the
 * default-off persistence, the two notification channels, and a usable ADB identity.
 *
 * It never pairs, connects, grants or touches wireless debugging. The identity it creates uses the
 * production alias only when it already exists; a test identity uses its own alias and is deleted.
 */
@RunWith(AndroidJUnit4::class)
class PhoneIntegrationWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun foregroundServiceHasSpecialUseSubtype() {
        @Suppress("DEPRECATION")
        val info =
            context.packageManager.getServiceInfo(
                ComponentName(context, PhoneControlService::class.java),
                0,
            )
        assertFalse(info.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(
                info.foregroundServiceType and
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
            )
        }
    }

    @Test
    fun boundedDiscoveryServiceHasSpecialUseSubtypeAndMulticastPermission() {
        @Suppress("DEPRECATION")
        val info =
            context.packageManager.getServiceInfo(
                ComponentName(context, PhoneDiscoveryService::class.java),
                0,
            )
        assertFalse(info.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(
                info.foregroundServiceType and
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
            )
        }
        @Suppress("DEPRECATION")
        val declared =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
        assertTrue(declared.contains("android.permission.CHANGE_WIFI_MULTICAST_STATE"))
        // Local NSD on target 35 must not add a runtime nearby/location gate.
        assertFalse(declared.contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertFalse(declared.contains("android.permission.ACCESS_FINE_LOCATION"))
    }

    @Test
    fun pairingReceiverIsNotExported() {
        @Suppress("DEPRECATION")
        val info =
            context.packageManager.getReceiverInfo(
                ComponentName(context, PhonePairingActionReceiver::class.java),
                0,
            )
        assertFalse(info.exported)
    }

    @Test
    fun manifestQueriesLauncherActivitiesWithoutAllPackagesAccess() {
        val requested = context.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER),
            0,
        )
        assertTrue("launcher queries must be declared in the manifest", requested.isNotEmpty())
        @Suppress("DEPRECATION")
        val declared =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
        assertFalse(declared.contains("android.permission.QUERY_ALL_PACKAGES"))
        val fixture = "io.example.phonefixture"
        if (context.packageManager.getLaunchIntentForPackage(fixture) != null) {
            val targets = PhoneTargetCatalog.androidQuery(context, context.packageName)
            assertTrue("launcher without CATEGORY_DEFAULT was omitted", targets.any { it.packageName == fixture && !it.system })
        }
    }

    @Test
    fun notificationChannelsExistForControlAndPairing() {
        PhoneControlNotifications(context).ensureChannels()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel(PhoneControlNotifications.CHANNEL_CONTROL))
        assertNotNull(manager.getNotificationChannel(PhoneControlNotifications.CHANNEL_PAIRING))
    }

    @Test
    fun storeDefaultsOffAndBoundsTaskRecord() {
        val prefs = context.getSharedPreferences("phone_test_" + UUID.randomUUID(), Context.MODE_PRIVATE)
        val store = PrefsPhoneIntegrationStore(prefs)
        assertFalse(store.enabled())
        assertNull(store.task())

        store.setTask(
            io.andchamber.app.phone.android.PhoneTaskRecord(
                targetPackage = "com.example.notes",
                summary = "write a note",
                needsCheck = true,
            ),
        )
        val record = store.task()
        assertEquals("com.example.notes", record?.targetPackage)
        assertEquals(true, record?.needsCheck)

        // A malformed target is never stored, so a corrupted file cannot become a target.
        store.setTask(
            io.andchamber.app.phone.android.PhoneTaskRecord(
                targetPackage = "not a package",
                summary = "x",
                needsCheck = true,
            ),
        )
        assertEquals("com.example.notes", store.task()?.targetPackage)
        store.clearTask()
        assertNull(store.task())
    }

    @Test
    fun testIdentityIsConsistentRsaAndCanBeDeleted() {
        val alias = "andchamber.phone.test." + UUID.randomUUID()
        val identities = PhoneIdentityStore(context, alias)
        try {
            val identity = identities.identity("AndChamberTest")
            assertTrue(identity.isConsistent())
            assertEquals(64, identity.certificateFingerprint.length)
            // Same alias returns the same certificate, so the device sees one stable host identity.
            assertEquals(
                identity.certificateFingerprint,
                identities.identity("AndChamberTest").certificateFingerprint,
            )
        } finally {
            identities.forget()
        }
        assertFalse(identities.hasIdentity())
    }

    @Test
    fun leaseNotificationContainsOnlyAppLabelAndFixedPhase() {
        val notifications = PhoneControlNotifications(context)
        val active = notifications.running(epoch = 1L, appLabel = "Notes", paused = false)
        val paused = notifications.running(epoch = 1L, appLabel = "Notes", paused = true)
        val activeText = active.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val pausedText = paused.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue(activeText.isNotBlank())
        assertTrue(activeText != pausedText)
        // The task summary is not a parameter of the notification at all; this pins the intent.
        assertFalse(activeText.contains("buy milk"))
        assertFalse(pausedText.contains("buy milk"))
    }

    @Test
    fun pairingStepsHaveNoContentIntentAndOnlyTheirOwnRemoteInput() {
        val notifications = PhoneControlNotifications(context)
        val steps =
            listOf(
                // Searching never asks for the port: no RemoteInput at all.
                notifications.pairingSearching(3L) to 0,
                notifications.pairingPort(3L) to 1,
                notifications.pairingCode(3L) to 1,
                notifications.pairingBusy(3L) to 0,
            )
        steps.forEach { (posted, expectedRemote) ->
            // Opening another Activity here would close the system pairing dialog.
            assertNull(posted.contentIntent)
            val actions = posted.actions
            assertNotNull(actions)
            val remote = actions.orEmpty().filter { !it.remoteInputs.isNullOrEmpty() }
            assertEquals(expectedRemote, remote.size)
            remote.forEach { action ->
                assertEquals(false, action.allowGeneratedReplies)
                assertNotNull(action.actionIntent)
            }
            // Cancelling is always available and never carries an input.
            assertEquals(expectedRemote + 1, actions.size)
        }
    }

    @Test
    fun pairingNotificationActionsUseTheCurrentGenerationIdentity() {
        assertEquals(
            "same action and generation maps to one request-code slot",
            PhoneControlNotifications.requestCode(7L, 4),
            PhoneControlNotifications.requestCode(7L, 4),
        )
        assertNotEquals(
            "a new generation never shares the code action slot",
            PhoneControlNotifications.requestCode(7L, 4),
            PhoneControlNotifications.requestCode(8L, 4),
        )
        // Searching has exactly one action (Cancel) and no RemoteInput; the code step keeps one input.
        val searching = PhoneControlNotifications(context).pairingSearching(7L)
        assertEquals(1, searching.actions.orEmpty().size)
        assertTrue(searching.actions.orEmpty().single().remoteInputs.isNullOrEmpty())
    }

    @Test
    fun pairingDraftsDoNotKeepCodeAndRefuseStaleReplies() {
        var now = 0L
        val session = PhonePairingSession(clock = { now })
        val draft = session.begin("127.0.0.1")
        assertNull(session.current()?.pairingPort)
        now += 1_000L
        val port = session.acceptPort(draft.generation, "41234")
        assertTrue(port is PhonePairingSession.PortResult.Accepted)
        val code = session.acceptCode(draft.generation, "123456")
        assertTrue(code is PhonePairingSession.CodeResult.Ready)
        (code as PhonePairingSession.CodeResult.Ready).code.fill('\u0000')
        // Once the exchange started, the same draft accepts no second code.
        assertEquals(
            PhonePairingSession.CodeResult.NotReady,
            session.acceptCode(draft.generation, "654321"),
        )
    }

    @Test
    fun selfPostNotificationAppOpIsReadableWithoutExtraPermission() {
        // Read-only probe of this app's own UID. It changes nothing and touches no other package; the
        // parent runs the real deny/allow experiment outside this test.
        val ops = context.getSystemService(AppOpsManager::class.java)
        assertNotNull(ops)
        val op = "android:post_notification"
        @Suppress("DEPRECATION")
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
            } else {
                ops.checkOpNoThrow(op, Process.myUid(), context.packageName)
            }
        assertTrue(
            "platform must report one of the fixed modes, got $mode",
            mode in
                intArrayOf(
                    AppOpsManager.MODE_ALLOWED,
                    AppOpsManager.MODE_DEFAULT,
                    AppOpsManager.MODE_IGNORED,
                    AppOpsManager.MODE_ERRORED,
                    AppOpsManager.MODE_FOREGROUND,
                ).toList(),
        )
    }

    @Test
    fun returnActionIsAnInvisibleUnexportedActivity() {
        @Suppress("DEPRECATION")
        val info =
            context.packageManager.getActivityInfo(
                ComponentName(context, PhoneControlReturnActivity::class.java),
                0,
            )
        assertFalse(info.exported)
        assertTrue(info.flags and ActivityInfo.FLAG_NO_HISTORY != 0)
        assertEquals(
            "invisible return activity uses the no-display theme",
            android.R.style.Theme_NoDisplay,
            info.theme,
        )
    }

    @Test
    fun returnTargetsTheActivityAndPauseTargetsTheService() {
        val returnIntent = PhoneControlNotifications.returnIntentFor(context, 7L)
        assertEquals(PhoneControlReturnActivity::class.java.name, returnIntent.component?.className)
        assertEquals(PhoneControlService.ACTION_RETURN, returnIntent.action)
        // The nonce is part of the intent identity, not only the request code.
        assertTrue(returnIntent.dataString?.endsWith(io.andchamber.app.phone.android.PhoneProcessNonce.value) == true)
        val pauseIntent = PhoneControlNotifications.pauseIntentFor(context, 7L)
        assertEquals(PhoneControlService::class.java.name, pauseIntent.component?.className)
        assertEquals(PhoneControlService.ACTION_PAUSE, pauseIntent.action)
    }

    @Test
    fun leaseNotificationPhasesUseFixedTextAndNeverTheSummary() {
        val notifications = PhoneControlNotifications(context)
        val running = notifications.running(epoch = 3L, appLabel = "Notes", paused = false, pausing = false)
        val pausing = notifications.running(epoch = 3L, appLabel = "Notes", paused = false, pausing = true)
        val paused = notifications.running(epoch = 3L, appLabel = "Notes", paused = true, pausing = false)

        assertEquals(
            context.getString(R.string.phone_notif_running_title, "Notes"),
            running.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            context.getString(R.string.phone_notif_pausing_title),
            pausing.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            context.getString(R.string.phone_notif_paused_title),
            paused.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
        )
        // While pausing, the notification must not claim a settled pause; the bodies are fixed text.
        assertEquals(
            context.getString(R.string.phone_notif_pausing_body),
            pausing.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
        )
        assertEquals(
            context.getString(R.string.phone_notif_paused_body),
            paused.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
        )
        // No task summary can reach any phase: the API takes none and none of them echoes "buy milk".
        listOf(running, pausing, paused).forEach { posted ->
            val text = posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            assertFalse(text.contains("buy milk"))
        }
    }
}
