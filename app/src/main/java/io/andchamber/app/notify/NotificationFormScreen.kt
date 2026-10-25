package io.andchamber.app.notify

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.andchamber.app.R
import io.andchamber.app.ui.theme.AndChamberTheme
import io.andchamber.app.ui.theme.ChamberTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

/** What the form screen shows as a whole. */
internal sealed interface FormPhase {
    data object Loading : FormPhase

    data class Ready(val form: FormAnswer.Fields) : FormPhase

    /** The pending state couldn't be read; the user may try again. */
    data object CheckFailed : FormPhase

    /** The form is still pending, but only the app can answer it. */
    data object Unavailable : FormPhase
}

/** The last submit that didn't go through. The typed answers always stay on screen. */
internal sealed interface FormStatus {
    /** Nothing was sent: the pending state couldn't be read. Sending again is safe. */
    data object CheckFailed : FormStatus

    /** OpenChamber refused the answer; it was not applied. */
    data class Refused(val reason: String?) : FormStatus

    /** It may or may not have arrived. The next send checks the pending state first. */
    data object Unknown : FormStatus

    /** The form can no longer be answered here; only the app can. Sending is off. */
    data object OpenOnly : FormStatus
}

/** What a tap on Send did. */
internal sealed interface SendAttempt {
    data object Started : SendAttempt

    /** Ignored: already sending, closed, or not answerable here. Nothing was sent. */
    data object Ignored : SendAttempt

    /** Required fields still blank, in form order. Nothing was sent. */
    data class Missing(val keys: List<String>) : SendAttempt
}

/**
 * What happens outside the screen. The activity maps these to the notification (cancel or keep). The
 * typed answers are never passed along: a kept notification has no draft.
 */
internal interface NotificationFormEffects {
    /** The answer was accepted: remove the notification. */
    fun answered()

    /** The form was answered or closed elsewhere: remove the notification. */
    fun gone()

    /** Keep the notification with the status for [step]. Never [ActionStep.Send] or [ActionStep.Cancel]. */
    fun keep(
        step: ActionStep,
        reason: String?,
    )
}

/**
 * The form screen's state: one load, then at most one submit in flight. Used from the main thread, so
 * the in-flight check and the state change happen together and a double tap cannot send twice. The
 * typed answers are not held here; the screen keeps them (rememberSaveable), so a failure never
 * clears them.
 *
 * [submitScope] may outlive the window (the activity passes a process scope): a submit that started
 * finishes even if the screen is closed, so the notification still gets its outcome.
 */
@Stable
internal class NotificationFormModel(
    private val load: suspend () -> NotificationFormLoad,
    private val submit: suspend (FormAnswer.Fields, Map<String, String>) -> NotificationFormSubmission,
    private val effects: NotificationFormEffects,
    private val loadScope: CoroutineScope,
    private val submitScope: CoroutineScope = loadScope,
) {
    var phase: FormPhase by mutableStateOf(FormPhase.Loading)
        private set

    var status: FormStatus? by mutableStateOf(null)
        private set

    var submitting: Boolean by mutableStateOf(false)
        private set

    /** The screen should close: the answer went through, or the form is gone. */
    var closed: Boolean by mutableStateOf(false)
        private set

    private var started = false
    private var loadJob: Job? = null

    /** Loads the form once. Later calls do nothing (a rotation must not load again). */
    fun start() {
        if (started) return
        started = true
        runLoad()
    }

    /** After a failed read only. */
    fun retryLoad() {
        if (phase != FormPhase.CheckFailed || loadJob?.isActive == true) return
        phase = FormPhase.Loading
        runLoad()
    }

    private fun runLoad() {
        loadJob =
            loadScope.launch {
                when (val result = load()) {
                    is NotificationFormLoad.Ready -> phase = FormPhase.Ready(result.form)
                    NotificationFormLoad.Gone -> {
                        effects.gone()
                        closed = true
                    }
                    NotificationFormLoad.CheckFailed -> {
                        phase = FormPhase.CheckFailed
                        effects.keep(ActionStep.KeepCheckFailed, null)
                    }
                    NotificationFormLoad.Unavailable -> {
                        phase = FormPhase.Unavailable
                        effects.keep(ActionStep.KeepOpenOnly, null)
                    }
                }
            }
    }

    /** Sending is possible right now (the button state). */
    val canSend: Boolean
        get() = phase is FormPhase.Ready && !submitting && !closed && status != FormStatus.OpenOnly

    /**
     * Sends every field once. [values] holds what the screen shows for each field key. A missing
     * required value sends nothing.
     */
    fun send(values: Map<String, String>): SendAttempt {
        val form = (phase as? FormPhase.Ready)?.form ?: return SendAttempt.Ignored
        if (!canSend) return SendAttempt.Ignored
        val missing = PendingForms.missingRequiredFields(form, values)
        if (missing.isNotEmpty()) return SendAttempt.Missing(missing)
        submitting = true
        val answers = form.fields.associate { it.key to values[it.key].orEmpty() }
        submitScope.launch {
            try {
                apply(submit(form, answers))
            } finally {
                submitting = false
            }
        }
        return SendAttempt.Started
    }

    private fun apply(result: NotificationFormSubmission) {
        when (result.step) {
            ActionStep.Cancel -> {
                status = null
                effects.answered()
                closed = true
            }
            ActionStep.KeepCheckFailed -> keep(FormStatus.CheckFailed, result.step, result.reason)
            ActionStep.KeepSendFailed -> keep(FormStatus.Refused(result.reason), result.step, result.reason)
            ActionStep.KeepOpenOnly -> keep(FormStatus.OpenOnly, result.step, result.reason)
            ActionStep.KeepResultUnknown -> keep(FormStatus.Unknown, result.step, result.reason)
            // A submit never ends in Send. If it did, nothing is known, and nothing is sent again.
            ActionStep.Send -> keep(FormStatus.Unknown, ActionStep.KeepResultUnknown, result.reason)
        }
    }

    private fun keep(
        next: FormStatus,
        step: ActionStep,
        reason: String?,
    ) {
        status = next
        effects.keep(step, reason)
    }
}

/** Test tags, also exposed as resource ids for instrumentation tests. */
internal object NotificationFormTags {
    const val ROOT = "notify_form"
    const val CLOSE = "notify_form_close"
    const val SEND = "notify_form_send"
    const val STATUS = "notify_form_status"
    const val RETRY = "notify_form_retry"
    const val OPEN_SESSION = "notify_form_open_session"
    const val LOADING = "notify_form_loading"

    fun field(key: String) = "notify_form_field_$key"

    fun fieldError(key: String) = "notify_form_field_error_$key"
}

/** Longest server reason shown, the same limit the notification uses. */
private const val MAX_REASON = 120

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

/** The full-screen form opened from a notification. */
@Composable
internal fun NotificationFormScreen(
    model: NotificationFormModel,
    onClose: () -> Unit,
    onOpenSession: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The full identity of the form shown (for example session, form id and revision). Typed answers
     * are saved under it: the same identity keeps them across rotation, failures and recreation; any
     * other identity starts from the form's defaults and never sees them. Null uses the form id.
     */
    stateKey: String? = null,
) {
    NotificationFormContent(
        phase = model.phase,
        status = model.status,
        submitting = model.submitting,
        canSend = model.canSend,
        onSend = model::send,
        onRetryLoad = model::retryLoad,
        onClose = onClose,
        onOpenSession = onOpenSession,
        modifier = modifier,
        stateKey = stateKey,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NotificationFormContent(
    phase: FormPhase,
    status: FormStatus?,
    submitting: Boolean,
    canSend: Boolean,
    onSend: (Map<String, String>) -> SendAttempt,
    onRetryLoad: () -> Unit,
    onClose: () -> Unit,
    onOpenSession: () -> Unit,
    modifier: Modifier = Modifier,
    /** See [NotificationFormScreen]. Null uses the form id. */
    stateKey: String? = null,
) {
    // One saved-state scope per form identity. A plain rememberSaveable restores by position, not by
    // its inputs, so a screen recreated for another form would get the previous form's answers back.
    // Hoisted above the phase switch so its position (and so its saved state) does not depend on it.
    val answerScopes = rememberSaveableStateHolder()
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier =
            modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag(NotificationFormTags.ROOT),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
        ) {
            FormTopBar(onClose = onClose)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (phase) {
                    FormPhase.Loading -> LoadingState()
                    FormPhase.CheckFailed ->
                        MessageState(
                            title = stringResource(R.string.form_check_failed_title),
                            body = stringResource(R.string.notify_check_failed),
                            error = true,
                        ) {
                            Button(
                                onClick = onRetryLoad,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(NotificationFormTags.RETRY),
                            ) { Text(stringResource(R.string.try_again)) }
                            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.form_close))
                            }
                        }
                    FormPhase.Unavailable ->
                        MessageState(
                            title = stringResource(R.string.form_unavailable_title),
                            body = stringResource(R.string.form_unavailable_body),
                            error = false,
                        ) {
                            Button(
                                onClick = onOpenSession,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(NotificationFormTags.OPEN_SESSION),
                            ) { Text(stringResource(R.string.form_open_session)) }
                        }
                    is FormPhase.Ready -> {
                        val identity = stateKey ?: phase.form.formId
                        answerScopes.SaveableStateProvider(identity) {
                            FormReady(
                                form = phase.form,
                                identity = identity,
                                status = status,
                                submitting = submitting,
                                canSend = canSend,
                                onSend = onSend,
                                onOpenSession = onOpenSession,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormTopBar(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.testTag(NotificationFormTags.CLOSE)) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.form_close))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.form_kicker).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(NotificationFormTags.LOADING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.form_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A short message with actions, for a form that can't be shown. Scrolls on short screens. */
@Composable
private fun MessageState(
    title: String,
    body: String,
    error: Boolean,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Icon(
                imageVector = if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                contentDescription = null,
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(NotificationFormTags.STATUS),
            )
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

private val stateMapSaver =
    listSaver<SnapshotStateMap<String, String>, String>(
        save = { map -> map.flatMap { (key, value) -> listOf(key, value) } },
        restore = { flat -> mutableStateMapOf<String, String>().apply { flat.chunked(2).forEach { (k, v) -> put(k, v) } } },
    )

private val stateListSaver =
    listSaver<SnapshotStateList<String>, String>(
        save = { it.toList() },
        restore = { mutableStateListOf<String>().apply { addAll(it) } },
    )

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FormReady(
    form: FormAnswer.Fields,
    /** The form identity; the caller scopes saved state to it (SaveableStateProvider). */
    identity: String,
    status: FormStatus?,
    submitting: Boolean,
    canSend: Boolean,
    onSend: (Map<String, String>) -> SendAttempt,
    onOpenSession: () -> Unit,
) {
    // Answers survive rotation, recreation and a failed send for the same identity. A new identity
    // starts from the form's defaults (empty when there is none).
    val values =
        rememberSaveable(identity, saver = stateMapSaver) {
            mutableStateMapOf<String, String>().apply { form.fields.forEach { put(it.key, it.defaultValue.orEmpty()) } }
        }
    // Required fields flagged by the last Send; a field's flag clears once it has text.
    val flagged = rememberSaveable(identity, saver = stateListSaver) { mutableStateListOf<String>() }
    val focusRequesters = remember(identity) { form.fields.associate { it.key to FocusRequester() } }
    val bringers = remember(identity) { form.fields.associate { it.key to BringIntoViewRequester() } }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    fun send() {
        when (val attempt = onSend(values.toMap())) {
            is SendAttempt.Missing -> {
                flagged.clear()
                flagged.addAll(attempt.keys)
                val first = attempt.keys.first()
                focusRequesters[first]?.requestFocus()
                scope.launch { bringers[first]?.bringIntoView() }
            }
            SendAttempt.Started -> {
                flagged.clear()
                // The keyboard goes away so the progress and any status are in view.
                focusManager.clearFocus()
                keyboard?.hide()
            }
            SendAttempt.Ignored -> Unit
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 4.dp, bottom = 24.dp)) {
                Text(
                    text = form.title.ifBlank { stringResource(R.string.form_untitled) },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.form_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    form.fields.forEachIndexed { index, field ->
                        val last = index == form.fields.lastIndex
                        FormFieldInput(
                            field = field,
                            value = values[field.key].orEmpty(),
                            onValueChange = { text ->
                                values[field.key] = text
                                if (text.isNotBlank()) flagged.remove(field.key)
                            },
                            missing = field.key in flagged,
                            readOnly = submitting,
                            imeAction = if (last) ImeAction.Send else ImeAction.Next,
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            onSend = { send() },
                            focusRequester = focusRequesters.getValue(field.key),
                            bringIntoView = bringers.getValue(field.key),
                        )
                    }
                }
            }
        }
        FormBottomBar(
            status = status,
            submitting = submitting,
            enabled = canSend,
            onSend = { send() },
            onOpenSession = onOpenSession,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FormFieldInput(
    field: FormTextField,
    value: String,
    onValueChange: (String) -> Unit,
    missing: Boolean,
    readOnly: Boolean,
    imeAction: ImeAction,
    onNext: () -> Unit,
    onSend: () -> Unit,
    focusRequester: FocusRequester,
    bringIntoView: BringIntoViewRequester,
) {
    val requiredLabel = stringResource(R.string.form_required)
    Column(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = field.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (field.required) {
                Spacer(Modifier.width(8.dp))
                RequiredBadge(requiredLabel)
            }
        }
        field.description?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        val description = if (field.required) "${field.title}, $requiredLabel" else field.title
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = description }
                    .testTag(NotificationFormTags.field(field.key)),
            readOnly = readOnly,
            placeholder = field.placeholder?.let { { Text(it) } },
            isError = missing,
            supportingText =
                if (missing) {
                    {
                        Text(
                            text = stringResource(R.string.form_required_missing),
                            modifier = Modifier.testTag(NotificationFormTags.fieldError(field.key)),
                        )
                    }
                } else {
                    null
                },
            // Answers are often codes or names: no autocorrect or forced capitals. Long text wraps.
            maxLines = 5,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = imeAction,
                ),
            keyboardActions = KeyboardActions(onNext = { onNext() }, onSend = { onSend() }),
        )
    }
}

@Composable
private fun RequiredBadge(text: String) {
    val color = MaterialTheme.colorScheme.primary
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier =
            Modifier
                .background(color.copy(alpha = 0.12f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/** Pinned to the bottom, above the keyboard: the last status and the one Send button. */
@Composable
private fun FormBottomBar(
    status: FormStatus?,
    submitting: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onOpenSession: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
    ) {
        HorizontalDivider(color = ChamberTheme.colors.cardBorder)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                status?.let { StatusBanner(it, onOpenSession) }
                Button(
                    onClick = onSend,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(NotificationFormTags.SEND),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.form_sending))
                    } else {
                        Text(stringResource(R.string.form_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(
    status: FormStatus,
    onOpenSession: () -> Unit,
) {
    val text =
        when (status) {
            FormStatus.CheckFailed -> stringResource(R.string.notify_check_failed)
            is FormStatus.Refused ->
                status.reason?.trim()?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.reply_failed, it.take(MAX_REASON)) }
                    ?: stringResource(R.string.form_send_refused)
            FormStatus.Unknown -> stringResource(R.string.notify_result_unknown)
            FormStatus.OpenOnly -> stringResource(R.string.form_unavailable_body)
        }
    // An unknown result is a caution, not a failure: amber, not red.
    val caution = status == FormStatus.Unknown || status == FormStatus.OpenOnly
    val accent = if (caution) ChamberTheme.colors.warning else MaterialTheme.colorScheme.error
    val container = if (caution) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.errorContainer
    val onContainer = if (caution) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(container, MaterialTheme.shapes.medium)
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = if (caution) 2.dp else 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (caution) Icons.Rounded.Info else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.padding(top = 1.dp).size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = onContainer,
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(NotificationFormTags.STATUS),
            )
        }
        if (caution) {
            TextButton(
                onClick = onOpenSession,
                modifier = Modifier.align(Alignment.End).testTag(NotificationFormTags.OPEN_SESSION),
            ) { Text(stringResource(R.string.form_open_session)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

private val previewForm =
    FormAnswer.Fields(
        formId = "frm_preview",
        sessionId = "ses_preview",
        title = "Pair this device",
        fields =
            listOf(
                FormTextField("q0", "First code", "Shown on the other device, under Pairing.", true, "5 digits", null),
                FormTextField("q1", "Second code", null, true, "6 digits", null),
                FormTextField("q2", "Third code", "Leave empty if the other device shows only two codes.", false, null, null),
            ),
    )

private val previewFormZh =
    FormAnswer.Fields(
        formId = "frm_preview_zh",
        sessionId = "ses_preview",
        title = "配对这台设备：请输入另一台设备上显示的三组验证码",
        fields =
            listOf(
                FormTextField("q0", "第一组验证码（显示在另一台设备的“配对”页面顶部）", "如果看不到，请在另一台设备上重新打开配对页面。", true, "5 位数字", null),
                FormTextField("q1", "第二组验证码", null, true, null, "123456"),
                FormTextField("q2", "第三组验证码", "可选", false, null, null),
            ),
    )

@Composable
private fun PreviewForm(
    phase: FormPhase,
    status: FormStatus? = null,
    submitting: Boolean = false,
) {
    AndChamberTheme {
        NotificationFormContent(
            phase = phase,
            status = status,
            submitting = submitting,
            canSend = phase is FormPhase.Ready && !submitting && status != FormStatus.OpenOnly,
            onSend = { SendAttempt.Ignored },
            onRetryLoad = {},
            onClose = {},
            onOpenSession = {},
        )
    }
}

@Preview(name = "Form · ready", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 720)
@Composable
private fun FormReadyPreview() = PreviewForm(FormPhase.Ready(previewForm))

@Preview(name = "Form · zh, long, light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360, heightDp = 720, locale = "zh")
@Composable
private fun FormReadyZhPreview() = PreviewForm(FormPhase.Ready(previewFormZh))

@Preview(name = "Form · sending", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 720)
@Composable
private fun FormSendingPreview() = PreviewForm(FormPhase.Ready(previewForm), submitting = true)

@Preview(name = "Form · refused, short screen", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 480)
@Composable
private fun FormRefusedPreview() = PreviewForm(FormPhase.Ready(previewForm), status = FormStatus.Refused("Invalid code"))

@Preview(name = "Form · result unknown", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360, heightDp = 720)
@Composable
private fun FormUnknownPreview() = PreviewForm(FormPhase.Ready(previewForm), status = FormStatus.Unknown)

@Preview(name = "Form · landscape", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 720, heightDp = 360)
@Composable
private fun FormLandscapePreview() = PreviewForm(FormPhase.Ready(previewForm))

@Preview(name = "Form · loading", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 640)
@Composable
private fun FormLoadingPreview() = PreviewForm(FormPhase.Loading)

@Preview(name = "Form · check failed", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 640)
@Composable
private fun FormCheckFailedPreview() = PreviewForm(FormPhase.CheckFailed)

@Preview(name = "Form · app only", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360, heightDp = 640)
@Composable
private fun FormUnavailablePreview() = PreviewForm(FormPhase.Unavailable)
