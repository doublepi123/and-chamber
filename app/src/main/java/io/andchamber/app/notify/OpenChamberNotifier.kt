package io.andchamber.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
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
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf(
            Triple(Channel.AgentFinished, R.string.channel_agent_finished, NotificationManager.IMPORTANCE_DEFAULT),
            Triple(Channel.NeedsInput, R.string.channel_needs_input, NotificationManager.IMPORTANCE_HIGH),
            Triple(Channel.Errors, R.string.channel_errors, NotificationManager.IMPORTANCE_HIGH),
            Triple(Channel.Other, R.string.channel_other, NotificationManager.IMPORTANCE_DEFAULT),
        ).forEach { (channel, name, importance) ->
            if (manager.getNotificationChannel(channel.id) == null) {
                manager.createNotificationChannel(NotificationChannel(channel.id, context.getString(name), importance))
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
        if (questionAnswer != null) {
            addQuestionActions(builder, event, id, questionAnswer)
        } else {
            addActions(builder, event, id)
        }
        notify(id, builder.build())
    }

    /** A classified single form: choice buttons, a text reply field, or nothing (tap to open). */
    private fun addQuestionActions(
        builder: NotificationCompat.Builder,
        event: DesktopNotification,
        id: Int,
        answer: FormAnswer,
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
            FormAnswer.OpenOnly -> Unit
        }
    }

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
        notify(id, builder.build())
    }

    /**
     * Replaces a notification in place with a short status line (e.g. "Sent"). The caller clears it
     * after a delay: a notification cannot clear itself, so the receiver owns that timer.
     */
    fun updateStatus(
        id: Int,
        channel: Channel,
        text: String,
    ) {
        if (!canPost()) return
        val builder = baseBuilder(channel, context.getString(R.string.app_name), text, null).setOnlyAlertOnce(true)
        notify(id, builder.build())
    }

    fun cancel(id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
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
    ): PendingIntent {
        val intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, sessionId)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                .apply {
                    directory?.let { putExtra(NotificationActionReceiver.EXTRA_DIRECTORY, it) }
                    requestId?.let { putExtra(NotificationActionReceiver.EXTRA_REQUEST_ID, it) }
                    decision?.let { putExtra(NotificationActionReceiver.EXTRA_DECISION, it) }
                }
        // Mutable so the RemoteInput result can be attached for reply actions.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, (action + sessionId + (requestId ?: "") + (decision ?: "")).hashCode(), intent, flags)
    }

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
    }
}
