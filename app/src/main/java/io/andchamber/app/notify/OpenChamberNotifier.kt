package io.andchamber.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import io.andchamber.app.MainActivity
import io.andchamber.app.R
import io.andchamber.app.service.RuntimeService
import io.andchamber.app.notify.NotificationPayload.notificationId

/**
 * Posts the native notifications. Channels are created once; a notification's id comes from its tag,
 * so a later event with the same tag updates it in place. Actions are added per kind (see
 * [NotificationActionReceiver]).
 */
class OpenChamberNotifier(
    private val context: Context,
) {
    private val revisions: NotificationRevisionGate by lazy {
        NotificationRevisionGate(readRevision = ::currentRevision, remove = { id -> NotificationManagerCompat.from(context).cancel(id) })
    }

    /**
     * Creates the channels, and re-applies their names on every call. A channel's name is fixed when it
     * is created, so a change of system language would otherwise leave the old name shown in settings.
     * Call this on every app start (see AndChamberApplication).
     */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf(
            Triple(Channel.AgentFinished, R.string.channel_agent_finished, NotificationManager.IMPORTANCE_DEFAULT),
            Triple(Channel.NeedsInput, R.string.channel_needs_input, NotificationManager.IMPORTANCE_HIGH),
            Triple(Channel.Errors, R.string.channel_errors, NotificationManager.IMPORTANCE_HIGH),
            Triple(Channel.Other, R.string.channel_other, NotificationManager.IMPORTANCE_DEFAULT),
        ).forEach { (channel, name, importance) ->
            val label = context.getString(name)
            val existing = manager.getNotificationChannel(channel.id)
            if (existing == null) {
                manager.createNotificationChannel(NotificationChannel(channel.id, label, importance))
            } else if (existing.name != label) {
                // Same id, new name: the user's importance and mute settings are kept.
                existing.name = label
                manager.createNotificationChannel(existing)
            }
        }
    }

    /** Posts an OpenChamber event. The caller has already decided it should be shown. */
    fun post(
        event: DesktopNotification,
        channel: Channel,
        questionAnswer: FormAnswer? = null,
    ) {
        if (!canPost()) return
        ensureChannels()
        val id = notificationId(event.tag)
        val builder = baseBuilder(channel, event.title, event.body, openIntent(event.sessionId, event.directory, id))
        // The original text, so a later status line replaces the previous one instead of piling up.
        builder.addExtras(Bundle().apply { putString(EXTRA_ORIGINAL_BODY, event.body) })
        // A newer post on this id gets a new revision, so an older "Sent" clean-up no longer matches it.
        // Actions are created inside the write because the form screen's PendingIntent carries the token:
        // an answer submitted through an older form must not cancel or overwrite a newer notification.
        revisions.publish(id, sent = false) { token ->
            if (questionAnswer != null) {
                addQuestionActions(builder, event, id, questionAnswer, token)
            } else {
                addActions(builder, event, id)
            }
            builder.addExtras(Bundle().apply { putString(EXTRA_REVISION, token) })
            notify(id, builder.build())
        }
    }

    /** A classified form: choice buttons, a text reply field, a form screen, or nothing (tap to open). */
    private fun addQuestionActions(
        builder: NotificationCompat.Builder,
        event: DesktopNotification,
        id: Int,
        answer: FormAnswer,
        revision: String,
    ) {
        when (answer) {
            is FormAnswer.Choice -> {
                answer.options.forEach { (value, label) ->
                    builder.addAction(
                        R.drawable.ic_notification,
                        label,
                        formIntent(answer.sessionId, event.directory, id, answer.formId, answer.fieldKey, value),
                    )
                }
            }
            is FormAnswer.Text -> {
                val input = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel(context.getString(R.string.action_reply)).build()
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_notification,
                        context.getString(R.string.action_reply),
                        formIntent(answer.sessionId, event.directory, id, answer.formId, answer.fieldKey, null),
                    ).addRemoteInput(input).build(),
                )
            }
            is FormAnswer.Fields -> {
                // A keep rebuilds from these extras and must attach a Fill form action for its own
                // revision, not the old PendingIntent copied with the notification.
                builder.addExtras(
                    Bundle().apply {
                        putString(EXTRA_FORM_STYLE, FORM_STYLE_FIELDS)
                        putString(EXTRA_FORM_ID, answer.formId)
                    },
                )
                builder.addAction(
                    R.drawable.ic_notification,
                    context.getString(R.string.action_fill_form),
                    formScreenIntent(answer.sessionId, event.directory, id, answer.formId, revision),
                )
            }
            FormAnswer.OpenOnly -> Unit
        }
    }

    /** Opens the form screen. The activity loads the form again and submits every field at once. */
    private fun formScreenIntent(
        sessionId: String,
        directory: String?,
        notificationId: Int,
        formId: String,
        revision: String,
    ): PendingIntent =
        NotificationActionIdentity.activityPendingIntent(
            context = context,
            spec = NotificationActionIdentity.Spec(
                action = NotificationFormActivity.ACTION_ANSWER_FORM,
                notificationId = notificationId,
                sessionId = sessionId,
                directory = directory,
                requestId = formId,
                // The revision is part of the identity and reaches the screen as an extra, so an answer
                // from an older notification can only act while that revision is still the shown one.
                decision = revision,
            ),
        )

    private fun formIntent(
        sessionId: String,
        directory: String?,
        notificationId: Int,
        formId: String,
        fieldKey: String,
        value: String?,
    ): PendingIntent {
        val intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_FORM)
                .putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(NotificationActionReceiver.EXTRA_FORM_ID, formId)
                .putExtra(NotificationActionReceiver.EXTRA_FIELD_KEY, fieldKey)
                .apply {
                    directory?.let { putExtra(NotificationActionReceiver.EXTRA_DIRECTORY, it) }
                    value?.let { putExtra(NotificationActionReceiver.EXTRA_VALUE, it) }
                }
        return PendingIntent.getBroadcast(
            context,
            (formId + fieldKey + (value ?: "")).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** Posts an AndChamber runtime alert (always on the Errors channel). Tap opens Home. */
    fun postAlert(alert: RuntimeAlert) {
        if (!canPost()) return
        ensureChannels()
        val id = notificationId("alert:${alert.key}")
        val builder = baseBuilder(Channel.Errors, alert.title, alert.body, openIntent(null, null, id))
        revisions.publish(id, sent = false) { token ->
            builder.addExtras(Bundle().apply { putString(EXTRA_REVISION, token) })
            notify(id, builder.build())
        }
    }

    /**
     * Replaces a notification in place with a short status line (e.g. "Sent"). The caller clears it
     * after a delay: a notification cannot clear itself, so the receiver owns that timer.
     */
    fun updateStatus(
        id: Int,
        channel: Channel,
        text: String,
    ): String? {
        if (!canPost()) return null
        return revisions.publish(id, sent = true) { token ->
            val builder = baseBuilder(channel, context.getString(R.string.app_name), text, null).setOnlyAlertOnce(true)
            builder.addExtras(Bundle().apply { putString(EXTRA_REVISION, token) })
            notify(id, builder.build())
            token
        }
    }

    /**
     * Removes notification [id] only if [token] is still its current revision. A newer post, alert, status
     * or kept draft on the same id has another token and is left alone. Read and removal share one lock
     * with every write, so there is no gap in which a newer write could land.
     */
    fun cancelIfCurrent(
        id: Int,
        token: String,
    ): Boolean = revisions.cancelIfCurrent(id, token)

    /**
     * Removes [id] only while the system still shows [token]. Unlike [cancelIfCurrent] it does not need a
     * pending "Sent", so an answer screen opened from a plain post can also clean up its own revision.
     * A newer post, alert, status or kept draft has another token and is left alone. False when stale.
     */
    fun cancelIfRevision(
        id: Int,
        token: String,
    ): Boolean = revisions.ifCurrent(id, token) { revisions.cancel(id) }

    /**
     * Keeps [id] with [plan] only while the system still shows [token], and returns the revision of the
     * kept notification; null when [token] is stale (nothing was written) or posting is not allowed.
     * A stale form screen must not overwrite a newer notification on the same id.
     */
    fun keepIfRevision(
        id: Int,
        token: String,
        plan: KeepPlan,
    ): String? {
        if (!canPost()) return null
        ensureChannels()
        var kept: String? = null
        val wrote =
            revisions.ifCurrent(id, token) {
                kept =
                    revisions.publish(id, sent = false) { next ->
                        keepLocked(id, plan, next)
                    }
            }
        return if (wrote) kept else null
    }

    /** Releases the pending "Sent" [token] of [id] without removing anything, when its clean-up did not run. */
    fun forgetSent(
        id: Int,
        token: String,
    ) = revisions.forget(id, token)

    /** Reads the revision that the notification with [id] currently carries, or null when unavailable. */
    private fun currentRevision(id: Int): String? =
        activeNotification(id)?.extras?.getString(EXTRA_REVISION)

    /**
     * Keeps notification [id] after an action didn't go through, with [plan]'s status. Rebuilt from the
     * system's own copy, so the title, channel, session tap and (if [KeepPlan.keepActions]) the original
     * buttons and reply field stay. The status replaces any earlier one, never piles up. A reply that
     * didn't go through is shown again (reply history and extras); nothing is sent again automatically.
     * If the notification was swiped away meanwhile, a small one on Errors still opens the session.
     * Returns the new revision of the kept notification, or null when nothing was posted: callers that
     * act again on the same screen must use that revision, since the old one is stale after a keep.
     */
    fun keep(
        id: Int,
        plan: KeepPlan,
    ): String? {
        if (!canPost()) return null
        ensureChannels()
        // Read the old notification and write the kept one under the same lock as every other write, so
        // the copy cannot be stale by the time it is posted, and a concurrent cancelIfCurrent sees one state.
        return revisions.publish(id, sent = false) { token -> keepLocked(id, plan, token) }
    }

    private fun keepLocked(
        id: Int,
        plan: KeepPlan,
        token: String,
    ): String {
        val original = activeNotification(id)
        val wasFields = original?.extras?.getString(EXTRA_FORM_STYLE) == FORM_STYLE_FIELDS
        val formId = original?.extras?.getString(EXTRA_FORM_ID)?.takeIf { it.isNotBlank() }
        val builder =
            if (original != null) {
                NotificationCompat.Builder(context, original).apply {
                    if (wasFields) {
                        // The copied Fields action carries the old revision. Replace it with one for this
                        // write's token, so a reopened screen can still act; without a form id, keep none.
                        clearActions()
                        if (plan.keepActions && formId != null) {
                            addAction(
                                R.drawable.ic_notification,
                                context.getString(R.string.action_fill_form),
                                formScreenIntent(plan.sessionId, plan.directory, id, formId, token),
                            )
                        }
                        // Explicit, so a second keep still knows this is a form screen.
                        addExtras(
                            Bundle().apply {
                                putString(EXTRA_FORM_STYLE, FORM_STYLE_FIELDS)
                                formId?.let { putString(EXTRA_FORM_ID, it) }
                            },
                        )
                    } else if (!plan.keepActions) {
                        clearActions()
                    }
                    // A tap must open the session, also if the original had no tap action.
                    if (original.contentIntent == null) setContentIntent(openIntent(plan.sessionId, plan.directory, id))
                }
            } else {
                baseBuilder(Channel.Errors, context.getString(R.string.app_name), "", openIntent(plan.sessionId, plan.directory, id))
            }
        val base = original?.extras?.getString(EXTRA_ORIGINAL_BODY).orEmpty()
        val draftLine = plan.draft?.let { context.getString(R.string.notify_your_reply, it) }
        val text = listOfNotNull(base.takeIf { it.isNotBlank() }, plan.status, draftLine).joinToString("\n\n")
        builder
            .setContentText(plan.status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .addExtras(Bundle().apply { putString(EXTRA_ORIGINAL_BODY, base) })
        if (plan.draft != null) {
            builder.setRemoteInputHistory(arrayOf<CharSequence>(plan.draft))
            builder.addExtras(Bundle().apply { putString(EXTRA_DRAFT, plan.draft) })
        }
        // Kept notifications get a new revision too: an older "Sent" clean-up must not remove the draft.
        builder.addExtras(Bundle().apply { putString(EXTRA_REVISION, token) })
        notify(id, builder.build())
        return token
    }

    /** The posted notification with [id], as the system holds it now; null when it is gone. */
    private fun activeNotification(id: Int): Notification? =
        runCatching {
            context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == id }?.notification
        }.getOrNull()

    fun cancel(id: Int) {
        // Under the same lock as every write: a token given out earlier stops matching once this returns.
        revisions.cancel(id)
    }

    private fun baseBuilder(
        channel: Channel,
        title: String,
        body: String,
        contentIntent: PendingIntent?,
    ): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Color.parseColor(ACCENT))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

    /** Adds the action buttons or the reply field that the event kind supports (see ActionPlan). */
    private fun addActions(
        builder: NotificationCompat.Builder,
        event: DesktopNotification,
        id: Int,
    ) {
        val sessionId = event.sessionId ?: return
        when (ActionPlan.forEvent(event)) {
            ActionPlan.Kind.Reply -> {
                val input = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel(context.getString(R.string.action_reply)).build()
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_notification,
                        context.getString(R.string.action_reply),
                        actionIntent(NotificationActionReceiver.ACTION_REPLY, sessionId, event.directory, id, null, null),
                    ).addRemoteInput(input).build(),
                )
            }
            ActionPlan.Kind.Permission -> {
                val requestId = NotificationPayload.permissionRequestId(event.tag, sessionId) ?: return
                listOf(
                    R.string.action_allow_once to "once",
                    R.string.action_always_allow to "always",
                    R.string.action_deny to "reject",
                ).forEach { (label, decision) ->
                    builder.addAction(
                        R.drawable.ic_notification,
                        context.getString(label),
                        actionIntent(NotificationActionReceiver.ACTION_PERMISSION, sessionId, event.directory, id, requestId, decision),
                    )
                }
            }
            // Question actions depend on the fetched form; they are added by the receiver's post-fetch step.
            ActionPlan.Kind.Question, ActionPlan.Kind.OpenOnly -> Unit
        }
    }

    private fun openIntent(
        sessionId: String?,
        directory: String?,
        requestCode: Int,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_SESSION)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply {
                    sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
                    directory?.let { putExtra(EXTRA_DIRECTORY, it) }
                }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(
        action: String,
        sessionId: String,
        directory: String?,
        notificationId: Int,
        requestId: String?,
        decision: String?,
    ): PendingIntent =
        // The explicit data URI, not the request code, keeps actions of the same session apart.
        NotificationActionIdentity.pendingIntent(
            context = context,
            spec = NotificationActionIdentity.Spec(
                action = action,
                notificationId = notificationId,
                sessionId = sessionId,
                directory = directory,
                requestId = requestId,
                decision = decision,
            ),
        )

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun notify(
        id: Int,
        notification: Notification,
    ) {
        if (!canPost()) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        const val ACTION_OPEN_SESSION = "io.andchamber.app.action.OPEN_SESSION"
        const val EXTRA_SESSION_ID = "andchamber.open_session"
        const val EXTRA_DIRECTORY = "andchamber.open_directory"
        private const val ACCENT = "#EDB449"

        /** Notification extras: the event's own text, and the last reply that didn't go through. */
        private const val EXTRA_ORIGINAL_BODY = "andchamber.original_body"
        private const val EXTRA_DRAFT = "andchamber.draft"

        /** The local revision of this notification (see [NotificationRevisionGate]). Not a protocol message id. */
        internal const val EXTRA_REVISION = "andchamber.revision"

        /** Marks a notification whose answer action is the form screen, with the form it fills. */
        internal const val EXTRA_FORM_STYLE = "andchamber.form_style"
        internal const val EXTRA_FORM_ID = "andchamber.form_id"
        private const val FORM_STYLE_FIELDS = "fields"
    }
}
