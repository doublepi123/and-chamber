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
) : NotificationPoster {
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
    override fun post(
        event: DesktopNotification,
        channel: Channel,
        questionAnswer: FormAnswer?,
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

    /** Records a swipe of an inline Sending/Checking notification before its late result can redraw it. */
    fun markInlineRevisionDismissed(
        id: Int,
        token: String,
    ) = revisions.markDismissed(id, token)

    /**
     * Removes [id] only while the system still shows [token]. Unlike [cancelIfCurrent] it does not need a
     * pending "Sent", so an answer screen opened from a plain post can also clean up its own revision.
     * A newer post, alert, status or kept draft has another token and is left alone. False when stale.
     */
    override fun cancelIfRevision(
        id: Int,
        token: String,
    ): Boolean = revisions.ifCurrent(id, token) { revisions.cancel(id) }

    // --- Inline notification form (see InlineNotificationFormDelivery) ---

    override fun isInlineRevisionCurrent(
        id: Int,
        revision: String,
    ): Boolean = revisions.ifCurrent(id, revision) {}

    override suspend fun awaitShown(
        id: Int,
        revision: String,
    ): Boolean {
        val deadline = System.currentTimeMillis() + SHOWN_WAIT_MS
        while (true) {
            if (currentRevision(id) == revision) return true
            if (System.currentTimeMillis() >= deadline) return false
            kotlinx.coroutines.delay(SHOWN_POLL_MS)
        }
    }

    override fun hasNewerThan(
        id: Int,
        revision: String,
    ): Boolean =
        synchronized(SharedNotificationRevisions.lock) {
            val newest = SharedNotificationRevisions.latestWritten[id]
            if (newest != null && newest != revision) return@synchronized true
            val shown = currentRevision(id)
            shown != null && shown != revision
        }

    /**
     * The first step of an inline form. Replaces whatever the id showed (a new question on the same tag
     * starts a new interaction). It alerts like any question; later steps only update quietly.
     */
    override fun postInline(
        id: Int,
        channel: Channel,
        key: InlineFormKey,
        view: InlineFormView,
    ): String? {
        if (!canPost()) return null
        ensureChannels()
        return revisions.publish(id, sent = false) { token ->
            token.takeIf { tryNotify(id, inlineStepBuilder(channel.id, key, view, token, quiet = false).build()) }
        }.also { if (it == null) forgetLatest(id) }
    }

    /** A refused first post wrote nothing: trust what the system shows again instead of the lost token. */
    private fun forgetLatest(id: Int) {
        synchronized(SharedNotificationRevisions.lock) {
            SharedNotificationRevisions.latestWritten.remove(id)
        }
    }

    /**
     * The last answer was taken: show "Sending…" right away, with no buttons, while the controller
     * checks and sends. This ends the system's reply spinner quickly, so the typed text is not left on
     * screen, and a second tap cannot start anything. Like an editing step it opens nothing on a tap:
     * the user may still be in a system dialog that the answer is about. It can always be swiped away.
     */
    override fun inlineSendingIfRevision(
        id: Int,
        revision: String,
        key: InlineFormKey,
        retry: Boolean,
    ): String? =
        inlineWriteIfRevision(id, revision, sent = false) { token, channelId, _ ->
            baseBuilder(
                channelId,
                context.getString(R.string.form_kicker),
                context.getString(if (retry) R.string.inline_checking else R.string.form_sending),
                null,
            ).setOnlyAlertOnce(true)
                .setAutoCancel(false)
                // If the process dies (or the send times out) this notification still has an explicit
                // recovery broadcast: check pending and either continue or keep the unknown result.
                .addAction(inlineResumeAction(key, token))
                // Swiping a Sending/Checking notification discards its draft and blocks a late result
                // from redrawing it; it never re-sends anything.
                .setDeleteIntent(inlineDiscardIntent(key, token))
                .addExtras(inlineExtras(token, if (retry) PHASE_CHECKING else PHASE_SENDING, key.formId))
        }

    override fun updateInlineIfRevision(
        id: Int,
        revision: String,
        key: InlineFormKey,
        view: InlineFormView,
    ): String? =
        inlineWriteIfRevision(id, revision, sent = false) { token, channelId, _ ->
            inlineStepBuilder(channelId, key, view, token, quiet = true)
        }

    override fun inlineDoneIfRevision(
        id: Int,
        revision: String,
        done: InlineFormDone,
    ): String? =
        inlineWriteIfRevision(id, revision, sent = true) { token, channelId, formId ->
            inlineDoneBuilder(channelId, done, token, formId)
        }

    /**
     * Local-ownership recovery for a done status. The system may never have shown the "Sending…"
     * revision, so the normal scoped write cannot run; if the local revision still owns the id, post
     * the finished status anyway (it carries the same 10 s backstop). A newer revision wins.
     */
    override fun repostInlineDoneIfLatest(
        id: Int,
        revision: String,
        done: InlineFormDone,
    ): String? {
        if (!canPost()) return null
        ensureChannels()
        var written: String? = null
        revisions.publishIfLatest(id, revision) {
            val shown = activeNotification(id) ?: return@publishIfLatest Unit
            val channelId = shown.channelId
            val formId = shown.extras.getString(EXTRA_FORM_ID)?.takeIf { it.isNotBlank() }
            written =
                revisions.publish(id, sent = true) { token ->
                    token.takeIf { tryNotify(id, inlineDoneBuilder(channelId, done, token, formId).build()) }
                }
            if (written == null) {
                synchronized(SharedNotificationRevisions.lock) {
                    SharedNotificationRevisions.pendingSent.remove(id)
                    SharedNotificationRevisions.latestWritten[id] = revision
                }
            }
        }
        return written
    }

    private fun inlineDoneBuilder(
        channelId: String,
        done: InlineFormDone,
        token: String,
        formId: String?,
    ): NotificationCompat.Builder {
        val text =
            when (done) {
                InlineFormDone.Sent -> context.getString(R.string.inline_done_sent)
                InlineFormDone.Gone -> context.getString(R.string.inline_done_gone)
            }
        return baseBuilder(channelId, context.getString(R.string.app_name), text, null)
            .setOnlyAlertOnce(true)
            // A backstop: the receiver clears this exact revision; if it cannot, the system does.
            .setTimeoutAfter(DONE_TIMEOUT_MS)
            .addExtras(
                inlineExtras(
                    token,
                    when (done) {
                        InlineFormDone.Sent -> PHASE_DONE_SENT
                        InlineFormDone.Gone -> PHASE_DONE_GONE
                    },
                    formId,
                ),
            )
    }

    override fun postInlineLookup(
        id: Int,
        channel: Channel,
        sessionId: String,
        directory: String?,
    ): String? {
        if (!canPost()) return null
        ensureChannels()
        return revisions.publish(id, sent = false) { token ->
            token.takeIf { tryNotify(id, inlineLookupBuilder(channel.id, id, sessionId, directory, token, quiet = false).build()) }
        }.also { if (it == null) forgetLatest(id) }
    }

    override fun updateInlineLookupIfRevision(
        id: Int,
        revision: String,
        sessionId: String,
        directory: String?,
    ): String? =
        inlineWriteIfRevision(id, revision, sent = false) { token, channelId, _ ->
            inlineLookupBuilder(channelId, id, sessionId, directory, token, quiet = true)
        }

    override fun inlineFallbackIfRevision(
        id: Int,
        revision: String,
        sessionId: String,
        directory: String?,
    ): String? =
        inlineWriteIfRevision(id, revision, sent = false) { token, channelId, formId ->
            // Only says where to answer. The tap opens the session; nothing is opened by itself.
            baseBuilder(
                channelId,
                context.getString(R.string.form_unavailable_title),
                context.getString(R.string.notify_answer_in_app),
                openIntent(sessionId, directory, id),
            ).setOnlyAlertOnce(true)
                .addExtras(inlineExtras(token, PHASE_FALLBACK, formId))
        }

    /**
     * One revision-scoped inline write: runs only while [id] still shows [revision] and no newer local
     * write exists, under the gate lock, and gives the write a new revision (returned). The channel of
     * the shown notification is kept.
     *
     * If the system refuses the write, [revision] is made the newest local revision again (still under
     * the lock), so the notification on screen keeps working buttons. Its next tap is then behind the
     * draft and is answered by a fresh re-render of the true step, never by replaying its text.
     */
    private fun inlineWriteIfRevision(
        id: Int,
        revision: String,
        sent: Boolean,
        build: (token: String, channelId: String, formId: String?) -> NotificationCompat.Builder,
    ): String? {
        if (!canPost()) return null
        ensureChannels()
        var written: String? = null
        val ran =
            revisions.ifCurrent(id, revision) {
                val shown = activeNotification(id)
                val channelId = shown?.channelId ?: Channel.NeedsInput.id
                // The form id of the shown step, kept on a status that replaces it (for locating only).
                val formId = shown?.extras?.getString(EXTRA_FORM_ID)?.takeIf { it.isNotBlank() }
                written =
                    revisions.publish(id, sent) { token ->
                        if (tryNotify(id, build(token, channelId, formId).build())) token else null
                    }
                if (written == null) {
                    synchronized(SharedNotificationRevisions.lock) {
                        SharedNotificationRevisions.pendingSent.remove(id)
                        SharedNotificationRevisions.latestWritten[id] = revision
                    }
                }
            }
        return if (ran) written else null
    }

    override fun repostInlineIfLatest(
        id: Int,
        revision: String,
        key: InlineFormKey,
        view: InlineFormView,
    ): String? {
        if (!canPost()) return null
        ensureChannels()
        var written: String? = null
        revisions.publishIfLatest(id, revision) {
            val shown = activeNotification(id) ?: return@publishIfLatest Unit
            written =
                revisions.publish(id, sent = false) { token ->
                    token.takeIf { tryNotify(id, inlineStepBuilder(shown.channelId, key, view, token, quiet = true).build()) }
                }
            if (written == null) {
                // The recovery write itself failed: the local revision still owns the id, so the old
                // notification (if any) must keep being treated as current instead of going stale.
                synchronized(SharedNotificationRevisions.lock) {
                    SharedNotificationRevisions.latestWritten[id] = revision
                }
            }
        }
        return written
    }

    /** Posts [notification]; false when the system refused it (nothing changed on screen). */
    private fun tryNotify(
        id: Int,
        notification: Notification,
    ): Boolean =
        runCatching {
            notify(id, notification)
            true
        }.getOrDefault(false)

    /**
     * One inline step. Built fresh from the projection (never copied from the shown notification), so
     * no reply history, draft extra or earlier text can survive. While editing nothing opens on a tap,
     * the notification is not auto-cancelled, and every action is a broadcast that shows no UI: the
     * user stays where they are (for example a Settings dialog) for the whole form.
     */
    private fun inlineStepBuilder(
        channelId: String,
        key: InlineFormKey,
        view: InlineFormView,
        token: String,
        quiet: Boolean,
    ): NotificationCompat.Builder {
        val projection = view.projection
        val status = inlineStatus(projection)
        val hint = inlineHint(projection)
        val notice =
            when (view.notice) {
                InlineFormNotice.Restarted -> context.getString(R.string.inline_notice_restarted)
                null -> null
            }
        val description = projection.description?.takeIf { it.isNotBlank() }
        val formTitle = projection.formTitle?.takeIf { it.isNotBlank() }
        val body = listOfNotNull(notice, formTitle, description, status, hint).joinToString("\n\n")
        val builder =
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(Color.parseColor(ACCENT))
                .setContentTitle(inlineTitle(projection))
                .setContentText(notice ?: status ?: hint)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(null)
                .setAutoCancel(false)
                .setOngoing(false)
                .setOnlyAlertOnce(quiet)
                // No system-suggested replies or actions: they could show text, or open something.
                .setAllowSystemGeneratedContextualActions(false)
        InlineFormLayouts.buttons(projection).forEach { button ->
            builder.addAction(inlineAction(key, projection, token, button))
        }
        if (InlineFormLayouts.hasDismiss(projection)) {
            builder.setDeleteIntent(
                NotificationActionIdentity.inlinePendingIntent(
                    context,
                    inlineSpec(key, InlineFormWire.DISMISS, token, projection),
                    mutable = false,
                ),
            )
        }
        builder.addExtras(
            inlineExtras(token, PHASE_STEP, key.formId).apply {
                // Locating data for tests and the host harness. Never an answer, a default or a body:
                // the place of the step, its business token/generation and a fixed status code.
                putInt(EXTRA_INLINE_INDEX, projection.index)
                putInt(EXTRA_INLINE_COUNT, projection.count)
                putBoolean(EXTRA_INLINE_LAST, projection.isLast)
                projection.token?.let { putString(EXTRA_INLINE_TOKEN, it) }
                putLong(EXTRA_INLINE_GENERATION, projection.generation)
                putString(EXTRA_INLINE_STATUS, projection.status.name)
                view.notice?.let { putString(EXTRA_INLINE_NOTICE, it.name) }
            },
        )
        return builder
    }

    /** The extras every inline notification carries: revision, style, phase and (when known) form id. */
    private fun inlineExtras(
        token: String,
        phase: String,
        formId: String?,
    ): Bundle =
        Bundle().apply {
            putString(EXTRA_REVISION, token)
            putString(EXTRA_FORM_STYLE, FORM_STYLE_INLINE)
            putString(EXTRA_INLINE_PHASE, phase)
            formId?.let { putString(EXTRA_FORM_ID, it) }
        }

    /** The recovery action of a "Sending…"/"Checking…" notification: a broadcast, never an Activity. */
    private fun inlineResumeAction(
        key: InlineFormKey,
        revision: String,
    ): NotificationCompat.Action {
        val spec =
            NotificationActionIdentity.Spec(
                action = NotificationActionReceiver.ACTION_INLINE_FORM,
                notificationId = key.notificationId,
                sessionId = key.sessionId,
                directory = key.directory,
                formId = key.formId,
                revision = revision,
                command = InlineFormWire.RESUME,
            )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.inline_action_resume),
            NotificationActionIdentity.inlinePendingIntent(context, spec, mutable = false),
        ).setShowsUserInterface(false)
            .setAllowGeneratedReplies(false)
            .build()
    }

    /** The delete intent of a "Sending…"/"Checking…" notification: a broadcast, never an Activity. */
    private fun inlineDiscardIntent(
        key: InlineFormKey,
        revision: String,
    ): PendingIntent {
        val spec =
            NotificationActionIdentity.Spec(
                action = NotificationActionReceiver.ACTION_INLINE_FORM,
                notificationId = key.notificationId,
                sessionId = key.sessionId,
                directory = key.directory,
                formId = key.formId,
                revision = revision,
                command = InlineFormWire.DISCARD_PENDING,
            )
        return NotificationActionIdentity.inlinePendingIntent(context, spec, mutable = false)
    }

    private fun inlineAction(
        key: InlineFormKey,
        projection: InlineFormProjection,
        token: String,
        button: InlineFormButton,
    ): NotificationCompat.Action {
        val label =
            context.getString(
                when (button) {
                    InlineFormButton.Answer -> R.string.inline_action_answer
                    InlineFormButton.AnswerAndSend -> R.string.inline_action_answer_send
                    InlineFormButton.Back -> R.string.inline_action_back
                    InlineFormButton.Skip -> R.string.inline_action_skip
                    InlineFormButton.SkipAndSend -> R.string.inline_action_skip_send
                    InlineFormButton.Cancel -> R.string.inline_action_cancel
                    InlineFormButton.Retry -> R.string.inline_action_retry
                },
            )
        val intent =
            NotificationActionIdentity.inlinePendingIntent(
                context,
                inlineSpec(key, button.command, token, projection),
                mutable = button.carriesInput,
            )
        val action =
            NotificationCompat.Action.Builder(R.drawable.ic_notification, label, intent)
                .setShowsUserInterface(false)
                .setAllowGeneratedReplies(false)
        if (button.carriesInput) {
            val optional = InlineFormActionKind.Skip in projection.actions
            val field = projection.title?.takeIf { it.isNotBlank() } ?: label
            val hint = if (optional) context.getString(R.string.inline_input_optional, field) else field
            action
                .addRemoteInput(
                    RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY)
                        .setLabel(hint)
                        .setAllowFreeFormInput(true)
                        .build(),
                ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
        }
        return action.build()
    }

    /** "Couldn't read the form": an explicit retry that reads pending again; the tap opens the session. */
    private fun inlineLookupBuilder(
        channelId: String,
        id: Int,
        sessionId: String,
        directory: String?,
        token: String,
        quiet: Boolean,
    ): NotificationCompat.Builder {
        val spec =
            NotificationActionIdentity.Spec(
                action = NotificationActionReceiver.ACTION_INLINE_FORM,
                notificationId = id,
                sessionId = sessionId,
                directory = directory,
                revision = token,
                command = InlineFormWire.LOOKUP,
            )
        val retry =
            NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                context.getString(R.string.inline_action_retry),
                NotificationActionIdentity.inlinePendingIntent(context, spec, mutable = false),
            ).setShowsUserInterface(false).setAllowGeneratedReplies(false).build()
        return baseBuilder(
            channelId,
            context.getString(R.string.inline_lookup_title),
            context.getString(R.string.inline_lookup_body),
            openIntent(sessionId, directory, id),
        ).setOnlyAlertOnce(quiet)
            .addAction(retry)
            .addExtras(inlineExtras(token, PHASE_LOOKUP, null))
    }

    private fun inlineSpec(
        key: InlineFormKey,
        command: String,
        revision: String,
        projection: InlineFormProjection,
    ) = NotificationActionIdentity.Spec(
        action = NotificationActionReceiver.ACTION_INLINE_FORM,
        notificationId = key.notificationId,
        sessionId = key.sessionId,
        directory = key.directory,
        formId = key.formId,
        token = projection.token,
        generation = projection.generation,
        revision = revision,
        command = command,
    )

    /** `1/3 · Pairing port`; before the form is known, the form title or a generic line. */
    private fun inlineTitle(projection: InlineFormProjection): String {
        val field = projection.title?.takeIf { it.isNotBlank() }
        if (projection.count > 0 && field != null) {
            return context.getString(R.string.inline_step_title, projection.index + 1, projection.count, field)
        }
        return projection.formTitle?.takeIf { it.isNotBlank() } ?: context.getString(R.string.form_kicker)
    }

    /** A fixed status line. Never a server body, a value or a default. */
    private fun inlineStatus(projection: InlineFormProjection): String? =
        when (projection.status) {
            InlineFormStatus.RequiredMissing -> context.getString(R.string.inline_status_required)
            InlineFormStatus.CheckFailed -> context.getString(R.string.inline_status_check_failed)
            InlineFormStatus.SendFailed -> context.getString(R.string.inline_status_send_failed)
            InlineFormStatus.ResultUnknown -> context.getString(R.string.inline_status_result_unknown)
            InlineFormStatus.SchemaChanged -> context.getString(R.string.inline_status_schema_changed)
            InlineFormStatus.Editing, InlineFormStatus.Sent, InlineFormStatus.Gone, InlineFormStatus.Cancelled,
            InlineFormStatus.Unavailable, InlineFormStatus.Ignored,
            -> null
        }

    /** How this step relates to the single final send. */
    private fun inlineHint(projection: InlineFormProjection): String? {
        if (InlineFormActionKind.Reply !in projection.actions) return null
        return when {
            projection.count <= 1 -> context.getString(R.string.inline_hint_single)
            projection.isLast -> context.getString(R.string.inline_hint_last, projection.count)
            else -> context.getString(R.string.inline_hint_staged)
        }
    }

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
    ): NotificationCompat.Builder = baseBuilder(channel.id, title, body, contentIntent)

    private fun baseBuilder(
        channelId: String,
        title: String,
        body: String,
        contentIntent: PendingIntent?,
    ): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
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

        /** Marks an inline (field-by-field, in the notification) form notification. */
        internal const val FORM_STYLE_INLINE = "inline"

        /**
         * Non-sensitive locating extras of an inline notification, for tests and the host E2E harness
         * (read from the posted notification; there is no other diagnostic interface). None of them can
         * hold an answer, a default or a server body.
         *
         * [EXTRA_INLINE_PHASE] is on every inline notification: [PHASE_STEP], [PHASE_SENDING],
         * [PHASE_CHECKING], [PHASE_DONE_SENT], [PHASE_DONE_GONE], [PHASE_LOOKUP] or [PHASE_FALLBACK].
         * Only a step also has index (zero-based Int), count (Int), last (Boolean), token (String, the
         * random business token), generation (Long), status ([InlineFormStatus] name) and, after a
         * restart, notice ([InlineFormNotice] name).
         */
        const val EXTRA_INLINE_PHASE = "andchamber.inline_phase"
        const val EXTRA_INLINE_INDEX = "andchamber.inline_index"
        const val EXTRA_INLINE_COUNT = "andchamber.inline_count"
        const val EXTRA_INLINE_LAST = "andchamber.inline_last"
        const val EXTRA_INLINE_TOKEN = "andchamber.inline_token"
        const val EXTRA_INLINE_GENERATION = "andchamber.inline_generation"
        const val EXTRA_INLINE_STATUS = "andchamber.inline_status"
        const val EXTRA_INLINE_NOTICE = "andchamber.inline_notice"
        const val PHASE_STEP = "step"
        const val PHASE_SENDING = "sending"
        const val PHASE_CHECKING = "checking"
        const val PHASE_DONE_SENT = "done_sent"
        const val PHASE_DONE_GONE = "done_gone"
        const val PHASE_LOOKUP = "lookup"
        const val PHASE_FALLBACK = "fallback"

        /** The system removes a finished inline status by itself if the receiver could not. */
        private const val DONE_TIMEOUT_MS = 10_000L
        private const val SHOWN_WAIT_MS = 2_000L
        private const val SHOWN_POLL_MS = 25L
    }
}
