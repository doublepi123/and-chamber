package io.andchamber.app.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real notifications for the inline form: what the system actually holds after [OpenChamberNotifier]
 * posts a step. No action is sent (no broadcast, no network): only the posted notification and its
 * PendingIntents are inspected, and revision-scoped writes are checked against the real system copy.
 */
@RunWith(AndroidJUnit4::class)
class InlineNotificationFormNotificationInstrumentationTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val notifier = OpenChamberNotifier(app)

    @Before
    fun grant() {
        grantNotificationPermission()
        assertTrue("POST_NOTIFICATIONS is not granted, so a real notification cannot be posted", canPost())
    }

    private fun key(id: Int) = InlineFormKey("ses_1", "/repo", "frm_1", id)

    private fun step(
        index: Int,
        count: Int,
        actions: Set<InlineFormActionKind>,
        status: InlineFormStatus = InlineFormStatus.Editing,
        token: String = "biz-token",
        generation: Long = 3,
    ) = InlineFormView(
        InlineFormProjection(
            outcome = InlineFormOutcome.Render,
            status = status,
            token = token,
            generation = generation,
            index = index,
            count = count,
            formTitle = "Pair this device",
            title = listOf("Pairing port", "Pairing code", "Connection port").getOrElse(index) { "Field" },
            description = "Shown in the system dialog",
            actions = actions,
        ),
    )

    private val required = setOf(InlineFormActionKind.Reply, InlineFormActionKind.Cancel)
    private val middle = setOf(InlineFormActionKind.Reply, InlineFormActionKind.Back, InlineFormActionKind.Skip, InlineFormActionKind.Cancel)

    @Test
    fun theFirstMultiFieldStepIsAQuietBroadcastReplyThatOpensNothing() =
        withId { id ->
            val revision = notifier.postInline(id, Channel.NeedsInput, key(id), step(0, 3, required))
            assertNotNull(revision)
            val posted = awaitActive(id, revision)!!

            assertNull("editing must not open anything on a tap", posted.contentIntent)
            assertNull(posted.fullScreenIntent)
            assertEquals("not auto-cancelled", 0, posted.flags and Notification.FLAG_AUTO_CANCEL)
            assertEquals(Channel.NeedsInput.id, posted.channelId)
            assertEquals(app.getString(io.andchamber.app.R.string.inline_step_title, 1, 3, "Pairing port"), posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("inline", posted.extras.getString(OpenChamberNotifier.EXTRA_FORM_STYLE))
            assertEquals("frm_1", posted.extras.getString(OpenChamberNotifier.EXTRA_FORM_ID))
            assertEquals(OpenChamberNotifier.PHASE_STEP, posted.extras.getString(OpenChamberNotifier.EXTRA_INLINE_PHASE))
            assertEquals(0, posted.extras.getInt(OpenChamberNotifier.EXTRA_INLINE_INDEX, -1))
            assertEquals(3, posted.extras.getInt(OpenChamberNotifier.EXTRA_INLINE_COUNT, -1))
            assertFalse(posted.extras.getBoolean(OpenChamberNotifier.EXTRA_INLINE_LAST, true))
            assertEquals("biz-token", posted.extras.getString(OpenChamberNotifier.EXTRA_INLINE_TOKEN))
            assertEquals(3L, posted.extras.getLong(OpenChamberNotifier.EXTRA_INLINE_GENERATION, -1))
            assertEquals("Editing", posted.extras.getString(OpenChamberNotifier.EXTRA_INLINE_STATUS))
            assertEquals("the business token is not the notification revision", revision, posted.extras.getString(OpenChamberNotifier.EXTRA_REVISION))
            assertNotEquals("biz-token", revision)
            assertNotNull("a swipe drops the draft", posted.deleteIntent)
            assertTrue("the delete intent is a broadcast", posted.deleteIntent.isBroadcastCompat())

            val actions = posted.actions.orEmpty()
            assertTrue("at most three actions", actions.size in 1..3)
            val reply = actions.first()
            assertEquals(app.getString(io.andchamber.app.R.string.inline_action_answer), reply.title.toString())
            assertEquals(1, reply.remoteInputs?.size)
            assertEquals(NotificationActionReceiver.KEY_REPLY, reply.remoteInputs!!.single().resultKey)
            assertEquals("Pairing port", reply.remoteInputs!!.single().label.toString())
            actions.forEach { action ->
                val compat = NotificationCompat.getAction(posted, actions.indexOf(action))!!
                assertFalse("${action.title} must not show UI", compat.showsUserInterface)
                assertTrue("${action.title} must be a broadcast", action.actionIntent.isBroadcastCompat())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    assertEquals(
                        "only the reply is mutable: ${action.title}",
                        action.remoteInputs != null,
                        !action.actionIntent.isImmutable,
                    )
                }
            }
            assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, NotificationCompat.getAction(posted, 0)!!.semanticAction)
            assertNull(posted.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY))
        }

    @Test
    fun aSingleFieldStepIsAnsweredAndSentFromTheFirstNotification() =
        withId { id ->
            val revision = notifier.postInline(id, Channel.NeedsInput, key(id), step(0, 1, required))
            val posted = awaitActive(id, revision)!!
            assertNull(posted.contentIntent)
            val reply = posted.actions.first()
            assertEquals(app.getString(io.andchamber.app.R.string.inline_action_answer_send), reply.title.toString())
            assertTrue(posted.extras.getBoolean(OpenChamberNotifier.EXTRA_INLINE_LAST, false))
            assertEquals(1, posted.extras.getInt(OpenChamberNotifier.EXTRA_INLINE_COUNT, -1))
            assertTrue("free-form input", reply.remoteInputs!!.single().allowFreeFormInput)
            assertNotNull(reply.remoteInputs)
            assertTrue(reply.actionIntent.isBroadcastCompat())
        }

    @Test
    fun aMiddleStepHasThreeButtonsAndNoFourth() =
        withId { id ->
            val revision = notifier.postInline(id, Channel.NeedsInput, key(id), step(1, 3, middle))
            val posted = awaitActive(id, revision)!!
            assertEquals(listOf(io.andchamber.app.R.string.inline_action_answer, io.andchamber.app.R.string.inline_action_back, io.andchamber.app.R.string.inline_action_skip).map { app.getString(it) }, posted.actions.map { it.title.toString() })
            assertEquals(app.getString(io.andchamber.app.R.string.inline_step_title, 2, 3, "Pairing code"), posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            val label = posted.actions.first().remoteInputs!!.single().label.toString()
            assertEquals(app.getString(io.andchamber.app.R.string.inline_input_optional, "Pairing code"), label)
        }

    @Test
    fun everyActionHasItsOwnPendingIntentAndARevisionMakesNewOnes() =
        withId { id ->
            val first = notifier.postInline(id, Channel.NeedsInput, key(id), step(1, 3, middle))!!
            val old = awaitActive(id, first)!!.actions.map { it.actionIntent }
            assertEquals("three distinct actions", 3, old.toSet().size)

            val second = notifier.updateInlineIfRevision(id, first, key(id), step(1, 3, middle))!!
            val new = awaitActive(id, second)!!.actions.map { it.actionIntent }
            old.zip(new).forEach { (a, b) -> assertNotEquals("a new revision never reuses an old action", a, b) }
        }

    @Test
    fun aStaleRevisionCannotUpdateOrCancelANewerStep() =
        withId { id ->
            val first = notifier.postInline(id, Channel.NeedsInput, key(id), step(0, 3, required))!!
            awaitActive(id, first)
            val second = notifier.updateInlineIfRevision(id, first, key(id), step(1, 3, middle))!!
            awaitActive(id, second)

            assertNull("the old revision is stale", notifier.updateInlineIfRevision(id, first, key(id), step(2, 3, required)))
            assertFalse(notifier.cancelIfRevision(id, first))
            assertFalse(notifier.isInlineRevisionCurrent(id, first))
            assertTrue(notifier.isInlineRevisionCurrent(id, second))
            assertEquals(app.getString(io.andchamber.app.R.string.inline_step_title, 2, 3, "Pairing code"), active(id)!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(notifier.hasNewerThan(id, first))
            assertFalse(notifier.hasNewerThan(id, second))
        }

    @Test
    fun noStepOrStatusCarriesHistoryDraftOrAnswer() =
        withId { id ->
            var revision = notifier.postInline(id, Channel.NeedsInput, key(id), step(0, 3, required))!!
            awaitActive(id, revision)
            val statuses =
                listOf(
                    InlineFormStatus.RequiredMissing,
                    InlineFormStatus.CheckFailed,
                    InlineFormStatus.SendFailed,
                    InlineFormStatus.ResultUnknown,
                    InlineFormStatus.SchemaChanged,
                )
            statuses.forEach { status ->
                val actions =
                    if (status == InlineFormStatus.CheckFailed || status == InlineFormStatus.ResultUnknown) {
                        setOf(InlineFormActionKind.Retry, InlineFormActionKind.Cancel)
                    } else {
                        required
                    }
                revision = notifier.updateInlineIfRevision(id, revision, key(id), step(2, 3, actions, status))!!
                val shown = awaitActive(id, revision)!!
                assertNull(shown.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY))
                assertNull(shown.extras.getString("andchamber.draft"))
                assertEquals(status.name, shown.extras.getString(OpenChamberNotifier.EXTRA_INLINE_STATUS))
                assertEquals(2, shown.extras.getInt(OpenChamberNotifier.EXTRA_INLINE_INDEX, -1))
                assertNull("$status must not open anything", shown.contentIntent)
                assertTrue(shown.actions.orEmpty().size <= 3)
                shown.actions.orEmpty().forEach { assertTrue(it.actionIntent.isBroadcastCompat()) }
            }
            revision = notifier.inlineSendingIfRevision(id, revision, key(id), retry = false)!!
            val sending = awaitActive(id, revision)!!
            val recovery = sending.actions.single()
            assertEquals(app.getString(io.andchamber.app.R.string.inline_action_resume), recovery.title.toString())
            assertTrue("sending recovery stays in the notification", recovery.actionIntent.isBroadcastCompat())
            assertTrue("checking status needs no mutable input", recovery.actionIntent.isImmutable)
            assertTrue("recovery must not collect or replay input", recovery.remoteInputs.isNullOrEmpty())
            assertFalse(NotificationCompat.getAction(sending, 0)!!.showsUserInterface)
            assertEquals(OpenChamberNotifier.PHASE_SENDING, sending.extras.getString(OpenChamberNotifier.EXTRA_INLINE_PHASE))
            assertEquals("frm_1", sending.extras.getString(OpenChamberNotifier.EXTRA_FORM_ID))
            assertFalse("no step position on a status", sending.extras.containsKey(OpenChamberNotifier.EXTRA_INLINE_INDEX))
            assertNull(sending.contentIntent)

            val done = notifier.inlineDoneIfRevision(id, revision, InlineFormDone.Sent)!!
            val shownDone = awaitActive(id, done)!!
            assertEquals(OpenChamberNotifier.PHASE_DONE_SENT, shownDone.extras.getString(OpenChamberNotifier.EXTRA_INLINE_PHASE))
            assertEquals("frm_1", shownDone.extras.getString(OpenChamberNotifier.EXTRA_FORM_ID))
            assertTrue(shownDone.actions.isNullOrEmpty())
            assertNull(shownDone.contentIntent)
            assertTrue("done is cleared by exactly its revision", notifier.cancelIfCurrent(id, done))
        }

    @Test
    fun theFallbackOnlyExplainsAndOpensOnATap() =
        withId { id ->
            val first = notifier.postInline(id, Channel.NeedsInput, key(id), step(0, 3, required))!!
            awaitActive(id, first)
            val fallback = notifier.inlineFallbackIfRevision(id, first, "ses_1", "/repo")!!
            val shown = awaitActive(id, fallback)!!
            assertEquals(OpenChamberNotifier.PHASE_FALLBACK, shown.extras.getString(OpenChamberNotifier.EXTRA_INLINE_PHASE))
            assertTrue(shown.actions.isNullOrEmpty())
            assertNotNull("tap to open the session", shown.contentIntent)
            assertTrue(shown.contentIntent.isActivityCompat())
        }

    @Test
    fun theLookupRetryIsABroadcast() =
        withId { id ->
            val revision = notifier.postInlineLookup(id, Channel.NeedsInput, "ses_1", "/repo")!!
            val shown = awaitActive(id, revision)!!
            assertEquals(OpenChamberNotifier.PHASE_LOOKUP, shown.extras.getString(OpenChamberNotifier.EXTRA_INLINE_PHASE))
            val retry = shown.actions.single()
            assertTrue(retry.actionIntent.isBroadcastCompat())
            assertFalse(NotificationCompat.getAction(shown, 0)!!.showsUserInterface)
        }

    // --- helpers ---

    private fun withId(block: (Int) -> Unit) {
        // Exercise real notifications without exceeding SystemUI's per-package enqueue rate.
        Thread.sleep(1_000)
        val id = NotificationPayload.notificationId("inline-test-${System.nanoTime()}")
        try {
            block(id)
        } finally {
            notifier.cancel(id)
        }
    }

    private fun android.app.PendingIntent.isBroadcastCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isBroadcast else true

    private fun android.app.PendingIntent.isActivityCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isActivity else true

    private fun active(id: Int): Notification? =
        app.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.id == id }
            ?.notification

    private fun awaitActive(
        id: Int,
        token: String?,
    ): Notification? {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val current = active(id)
            if (current?.extras?.getString(OpenChamberNotifier.EXTRA_REVISION) == token) {
                // Subsequent states model user actions, not a sub-millisecond notification flood.
                Thread.sleep(300)
                return current
            }
            Thread.sleep(20)
        }
        return active(id)?.takeIf { it.extras?.getString(OpenChamberNotifier.EXTRA_REVISION) == token }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

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
