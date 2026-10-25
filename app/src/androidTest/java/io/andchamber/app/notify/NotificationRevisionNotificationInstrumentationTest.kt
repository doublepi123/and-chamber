package io.andchamber.app.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real notification: a Fields form is posted, then kept with a new revision. The kept notification's
 * Fill form action must carry that new revision, so reopening it can still act while the old one cannot.
 */
@RunWith(AndroidJUnit4::class)
class NotificationRevisionNotificationInstrumentationTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    private val fields =
        FormAnswer.Fields(
            formId = "frm_pair",
            sessionId = "ses_1",
            title = "Deploy",
            fields =
                listOf(
                    FormTextField("env", "Environment", null, required = true, null, "dev"),
                    FormTextField("branch", "Branch", null, required = false, null, null),
                ),
        )

    @Test
    fun aKeptFieldsNotificationReopensWithItsNewRevision() {
        grantNotificationPermission()
        assertTrue(
            "POST_NOTIFICATIONS is not granted, so a real notification cannot be posted",
            canPost(),
        )

        val notifier = OpenChamberNotifier(app)
        val tag = "revision-test-${System.nanoTime()}"
        val id = NotificationPayload.notificationId(tag)
        val event =
            DesktopNotification(
                kind = DesktopNotification.Kind.Question,
                title = "Question",
                body = "Fill the form",
                tag = tag,
                sessionId = "ses_1",
                directory = "/repo",
            )
        try {
            notifier.post(event, Channel.NeedsInput, fields)
            val posted = active(id)
            assertNotNull("the notification was not posted", posted)
            val oldToken = posted!!.extras.getString(OpenChamberNotifier.EXTRA_REVISION)
            assertNotNull("the posted notification has no revision", oldToken)
            assertEquals("fields", posted.extras.getString(OpenChamberNotifier.EXTRA_FORM_STYLE))
            assertEquals("frm_pair", posted.extras.getString(OpenChamberNotifier.EXTRA_FORM_ID))
            assertEquals(
                "the posted action carries its own revision",
                formPending(id, oldToken!!),
                posted.actions.single().actionIntent,
            )

            val keptToken =
                notifier.keepIfRevision(
                    id,
                    oldToken,
                    KeepPlan(
                        status = "Couldn't send",
                        keepActions = true,
                        sessionId = "ses_1",
                        directory = "/repo",
                        draft = null,
                    ),
                )
            assertNotNull("a current revision is kept", keptToken)
            assertNotEquals("the keep writes a new revision", oldToken, keptToken)

            val kept = awaitActive(id, keptToken)
            assertNotNull("the kept notification is missing", kept)
            assertEquals(keptToken, kept!!.extras.getString(OpenChamberNotifier.EXTRA_REVISION))
            val expectedNew = formPending(id, keptToken!!)
            assertEquals(
                "a reopened screen must get the keep's revision, not the copied old action",
                expectedNew,
                kept.actions.single().actionIntent,
            )
            assertNotEquals(
                "the old PendingIntent must not be reused",
                formPending(id, oldToken),
                kept.actions.single().actionIntent,
            )

            // The old screen is stale; the reopened one can still clear the notification.
            assertFalse("an old revision cannot clear the kept notification", notifier.cancelIfRevision(id, oldToken))
            assertNotNull("the kept notification must survive the stale cancel", active(id))
            assertTrue("the keep's revision can clear it", notifier.cancelIfRevision(id, keptToken))
            assertNull("the notification is gone", awaitActive(id, null))
        } finally {
            notifier.cancel(id)
        }
    }

    private fun formPending(
        notificationId: Int,
        revision: String,
    ): PendingIntent =
        NotificationActionIdentity.activityPendingIntent(
            app,
            NotificationActionIdentity.Spec(
                action = NotificationFormActivity.ACTION_ANSWER_FORM,
                notificationId = notificationId,
                sessionId = "ses_1",
                directory = "/repo",
                requestId = "frm_pair",
                decision = revision,
            ),
        )

    private fun active(id: Int): Notification? =
        app.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.id == id }
            ?.notification

    /** The system updates asynchronously; wait for [token] (or for removal when it is null). */
    private fun awaitActive(
        id: Int,
        token: String?,
    ): Notification? {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val current = active(id)
            if (token == null) {
                if (current == null) return null
            } else if (current?.extras?.getString(OpenChamberNotifier.EXTRA_REVISION) == token) {
                return current
            }
            Thread.sleep(20)
        }
        return active(id)
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    /** Instrumentation runs as the app; grant the runtime permission through the shell. */
    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val command = "pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}"
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
            ).use { it.readBytes() }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!canPost() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }
}
