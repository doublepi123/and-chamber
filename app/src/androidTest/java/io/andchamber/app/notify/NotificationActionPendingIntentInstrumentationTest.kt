package io.andchamber.app.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Creates real PendingIntents and checks the identity Android itself uses. The action broadcast is
 * captured with a local receiver; its reply text is empty and the delivered copy is package-scoped,
 * so the production receiver does no work and no network request is made.
 */
@RunWith(AndroidJUnit4::class)
class NotificationActionPendingIntentInstrumentationTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun reply(
        notificationId: Int,
        sessionId: String = "ses_1",
        directory: String? = null,
    ) = NotificationActionIdentity.Spec(
        action = NotificationActionReceiver.ACTION_REPLY,
        notificationId = notificationId,
        sessionId = sessionId,
        directory = directory,
    )

    private fun permission(
        requestId: String,
        decision: String,
        notificationId: Int = 101,
        sessionId: String = "ses_1",
    ) = NotificationActionIdentity.Spec(
        action = NotificationActionReceiver.ACTION_PERMISSION,
        notificationId = notificationId,
        sessionId = sessionId,
        requestId = requestId,
        decision = decision,
    )

    private fun pending(
        spec: NotificationActionIdentity.Spec,
        requestCode: Int = NotificationActionIdentity.requestCode(spec),
    ): PendingIntent = NotificationActionIdentity.pendingIntent(app, spec, requestCode = requestCode)

    @Test
    fun twoNotificationsOfTheSameSessionDoNotCollideAndCarryTheirOwnIds() {
        val first = pending(reply(101))
        val second = pending(reply(202))
        try {
            assertFalse("different notifications must not share a PendingIntent", first == second)
            assertEquals(101, deliveredNotificationId(deliverable(reply(101))))
            assertEquals(202, deliveredNotificationId(deliverable(reply(202))))
        } finally {
            first.cancel()
            second.cancel()
        }
    }

    @Test
    fun theSameIdentityReturnsTheSamePendingIntent() {
        val spec = reply(303)
        val first = pending(spec)
        val again = pending(spec)
        try {
            assertEquals(first, again)
        } finally {
            first.cancel()
        }
    }

    @Test
    fun differentIdentitiesDoNotCollideEvenWithTheSameRequestCode() {
        // The request code is only auxiliary: both get the same one on purpose.
        val first = pending(reply(101), requestCode = 4242)
        val second = pending(reply(202), requestCode = 4242)
        try {
            assertFalse("identity must come from the data URI, not the request code", first == second)
            assertEquals(101, deliveredNotificationId(deliverable(reply(101), requestCode = 4242)))
            assertEquals(202, deliveredNotificationId(deliverable(reply(202), requestCode = 4242)))
        } finally {
            first.cancel()
            second.cancel()
        }
    }

    @Test
    fun actionsStayMutableForRemoteInput() {
        val action = pending(reply(505))
        try {
            assertFalse("RemoteInput needs a mutable PendingIntent", action.isImmutable)
        } finally {
            action.cancel()
        }
    }

    @Test
    fun differentDirectoriesForTheSameNotificationStayApart() {
        val noDirectory = pending(reply(606, directory = null))
        val emptyDirectory = pending(reply(606, directory = ""))
        val first = pending(reply(606, directory = "/repo/a"))
        val second = pending(reply(606, directory = "/repo/b"))
        val again = pending(reply(606, directory = "/repo/a"))
        try {
            assertFalse("a null directory and an empty one are different targets", noDirectory == emptyDirectory)
            assertFalse("different directories must not share an action", first == second)
            assertFalse("no directory and a directory must differ", noDirectory == first)
            assertFalse("an empty directory and a directory must differ", emptyDirectory == first)
            assertEquals("the same directory keeps the same identity", first, again)
        } finally {
            noDirectory.cancel()
            emptyDirectory.cancel()
            first.cancel()
            second.cancel()
            again.cancel()
        }
    }

    @Test
    fun permissionRequestAndDecisionStayApart() {
        val once = pending(permission("per_1", "once"))
        val always = pending(permission("per_1", "always"))
        val otherRequest = pending(permission("per_2", "once"))
        try {
            assertFalse("decisions on one request must differ", once == always)
            assertFalse("requests on one notification must differ", once == otherRequest)
            assertFalse("the other request with another decision must differ", always == otherRequest)
        } finally {
            once.cancel()
            always.cancel()
            otherRequest.cancel()
        }
    }

    @Test
    fun differentSessionsWithTheSameNotificationIdStayApart() {
        val first = pending(reply(404, sessionId = "ses_1"))
        val second = pending(reply(404, sessionId = "ses_2"))
        try {
            assertFalse("one notification id in two sessions must not share an action", first == second)
        } finally {
            first.cancel()
            second.cancel()
        }
    }

    /**
     * A real PendingIntent with the production Intent (same data URI and extras), but addressed to
     * the app package instead of the explicit receiver. The package-only Intent reaches the local
     * receiver below, while [NotificationActionReceiver] (declared without an intent filter) is not
     * resolved and therefore makes no request. Package-only is explicit enough to keep FLAG_MUTABLE.
     */
    private fun deliverable(
        spec: NotificationActionIdentity.Spec,
        requestCode: Int = NotificationActionIdentity.requestCode(spec),
    ): PendingIntent {
        val intent =
            NotificationActionIdentity.intent(app, spec)
                .setComponent(null)
                .setPackage(app.packageName)
        return PendingIntent.getBroadcast(
            app,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** Sends the action (no RemoteInput text) and reads the notification id the Intent carried. */
    private fun deliveredNotificationId(pending: PendingIntent): Int {
        val delivered = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    delivered.set(intent.getIntExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, -1))
                    latch.countDown()
                }
            }
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(NotificationActionReceiver.ACTION_REPLY).apply {
                // The identity URI is part of the Intent, so the filter must accept its scheme.
                addDataScheme(NotificationActionIdentity.DATA_SCHEME)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            pending.send()
            assertTrue("action broadcast was not delivered", latch.await(5, TimeUnit.SECONDS))
        } finally {
            app.unregisterReceiver(receiver)
            pending.cancel()
        }
        return delivered.get()
    }
}
