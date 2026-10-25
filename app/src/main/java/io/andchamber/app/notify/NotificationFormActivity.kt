package io.andchamber.app.notify

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.andchamber.app.MainActivity
import io.andchamber.app.R
import io.andchamber.app.data.LocalApiClient
import io.andchamber.app.ui.theme.AndChamberTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.android.get

/**
 * The full-screen form a "Fill form" notification action opens, for a form of several text fields
 * that a notification cannot collect. Not exported: only our own immutable PendingIntent starts it.
 *
 * It loads the form again, keeps the typed answers across rotation and failures, and submits them
 * all at once, at most once per tap. The outcome is applied to the notification: an accepted answer
 * or a form that is gone removes it; anything else keeps it with a plain status. The answers are
 * never written to the notification or to the log (no draft).
 */
class NotificationFormActivity : ComponentActivity() {
    private val holder: FormHolder by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val target = Target.from(intent)
        if (target == null) {
            finish()
            return
        }
        val model = holder.modelFor(target) { buildModel(target) }
        setContent {
            AndChamberTheme {
                LaunchedEffect(model) { model.start() }
                LaunchedEffect(model.closed) { if (model.closed) finish() }
                NotificationFormScreen(
                    model = model,
                    // The input identity is the form, not the notification revision: a keep (or a
                    // reopen/process restore) writes a new revision but must keep what was typed.
                    // Different form ids stay strictly apart.
                    stateKey = "${target.sessionId}:${target.formId}",
                    onClose = ::finish,
                    onOpenSession = { openSession(target) },
                )
            }
        }
    }

    /**
     * singleTask: a second "Fill form" tap reaches this screen. The same form keeps what is typed; a
     * different form starts over (a submit already in flight still finishes and updates its own
     * notification).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = Target.from(intent) ?: return
        if (next == Target.from(this.intent)) return
        setIntent(intent)
        recreate()
    }

    private fun buildModel(target: Target): NotificationFormModel {
        val api = get<LocalApiClient>()
        val notifier = get<OpenChamberNotifier>()
        val controller =
            NotificationFormController(
                transport = LocalApiNotificationFormTransport(api),
                sessionId = target.sessionId,
                directory = target.directory,
                formId = target.formId,
            )
        val effects = NotifierEffects(applicationContext, notifier, target)
        return NotificationFormModel(
            load = controller::load,
            submit = controller::submit,
            effects = effects,
            loadScope = holder.viewModelScope,
            // A submit that started finishes even if the screen is closed, so the notification still
            // gets its outcome. Main thread, so the model's state changes stay on the UI thread.
            submitScope = submitScope,
        )
    }

    private fun openSession(target: Target) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(OpenChamberNotifier.ACTION_OPEN_SESSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(OpenChamberNotifier.EXTRA_SESSION_ID, target.sessionId)
                .apply { target.directory?.let { putExtra(OpenChamberNotifier.EXTRA_DIRECTORY, it) } },
        )
        finish()
    }

    /**
     * The form a notification points at, from the intent extras. [revision] is the notification revision
     * the form was posted with; it is required, so an answer can only act while that notification is
     * still the one shown.
     */
    internal data class Target(
        val sessionId: String,
        val directory: String?,
        val notificationId: Int,
        val formId: String,
        val revision: String,
    ) {
        companion object {
            fun from(intent: Intent?): Target? {
                intent ?: return null
                if (intent.action != ACTION_ANSWER_FORM) return null
                val sessionId = intent.getStringExtra(NotificationActionReceiver.EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return null
                val formId = intent.getStringExtra(NotificationActionReceiver.EXTRA_FORM_ID)?.takeIf { it.isNotBlank() } ?: return null
                // Without the revision the answer could not tell its own notification from a newer one.
                val revision = intent.getStringExtra(OpenChamberNotifier.EXTRA_REVISION)?.takeIf { it.isNotBlank() } ?: return null
                if (!intent.hasExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID)) return null
                return Target(
                    sessionId = sessionId,
                    directory = intent.getStringExtra(NotificationActionReceiver.EXTRA_DIRECTORY)?.takeIf { it.isNotBlank() },
                    notificationId = intent.getIntExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, 0),
                    formId = formId,
                    revision = revision,
                )
            }
        }
    }

    /** Keeps the model across rotation, so a load or a submit in flight is not started again. */
    internal class FormHolder : ViewModel() {
        private var target: Target? = null
        private var model: NotificationFormModel? = null

        fun modelFor(
            target: Target,
            create: () -> NotificationFormModel,
        ): NotificationFormModel {
            model?.takeIf { this.target == target }?.let { return it }
            return create().also {
                this.target = target
                model = it
            }
        }
    }

    /** Applies the outcome to the notification. No draft: the answers never leave the screen. */
    private class NotifierEffects(
        private val context: Context,
        private val notifier: OpenChamberNotifier,
        private val target: Target,
    ) : NotificationFormEffects {
        /**
         * The revision this screen may still act on. A keep writes a new revision (the notification now
         * shows the status), so later retries and a later success use that one. The screen's stateKey
         * stays at the original target revision, so the typed answers are not cleared by a keep.
         */
        private var currentRevision: String = target.revision

        override fun answered() {
            notifier.cancelIfRevision(target.notificationId, currentRevision)
        }

        override fun gone() {
            notifier.cancelIfRevision(target.notificationId, currentRevision)
        }

        override fun keep(
            step: ActionStep,
            reason: String?,
        ) {
            val status =
                when (step) {
                    ActionStep.Send, ActionStep.Cancel -> return
                    ActionStep.KeepCheckFailed -> context.getString(R.string.notify_check_failed)
                    // A reason here is only a local required-field title. A refusal carries no server
                    // body, so it gets a fixed status instead of echoing anything.
                    ActionStep.KeepSendFailed ->
                        reason?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.reply_failed, it.take(MAX_REASON)) }
                            ?: context.getString(R.string.form_send_refused)
                    ActionStep.KeepResultUnknown -> context.getString(R.string.notify_result_unknown)
                    ActionStep.KeepOpenOnly -> context.getString(R.string.notify_answer_in_app)
                }
            val failure = FailureContext(target.sessionId, target.directory, draft = null)
            ActionDecisions.keepPlan(step, failure, status)?.let { plan ->
                notifier.keepIfRevision(target.notificationId, currentRevision, plan)?.let { currentRevision = it }
            }
        }
    }

    companion object {
        /** The action of the notification's "Fill form" PendingIntent. */
        const val ACTION_ANSWER_FORM = "io.andchamber.app.action.NOTIFY_ANSWER_FORM"
        private const val MAX_REASON = 120

        /** Outlives the screen; see [buildModel]. */
        private val submitScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
