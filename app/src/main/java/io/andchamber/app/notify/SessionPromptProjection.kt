package io.andchamber.app.notify

import io.andchamber.app.data.ApiResult
import io.andchamber.app.data.LocalApiClient
import io.andchamber.app.notify.NotificationPayload.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One visible option of a notification question. Only the value is sent back. */
data class SessionPromptChoice(
    val value: String,
    val label: String,
)

/**
 * What the native task conversation should show. It is memory only: it never carries a submitted
 * answer, a field default, a server response body, permission resources or page text.
 */
enum class SessionPromptDeliveryState {
    /** No question is being shown; the overlay must not offer an answer. */
    Idle,

    /** A pending read is in flight for the current question. */
    Checking,

    /** A single-choice form is pending and still matches the event that produced it. */
    Ready,

    /** One submit is in flight; a second tap must be ignored. */
    Sending,

    /** The server accepted the form reply. This is delivery only, never business success. */
    Accepted,

    /** The check or the send failed; the answer may or may not have been applied. Never auto-retry. */
    Unknown,

    /**
     * No request was started because the bounded duplicate-protection table is full. Read-only; the
     * person opens the chat. Old attempt records are never evicted to make room.
     */
    Blocked,

    /** The server refused the reply (3xx/4xx). It was explicitly not applied. */
    Refused,

    /** The form is no longer pending: it was answered or cancelled elsewhere. */
    Gone,

    /** The pending form exists but is not a supported single choice (open the chat instead). */
    Unsupported,

    /** The question/form changed while an answer was being prepared. Nothing was sent. */
    Superseded,
}

/**
 * Read-only snapshot of the current notification question. [generation] is the answer fence: a
 * submit must carry the exact generation, form id and value it was shown with.
 */
data class SessionPromptProjection(
    val launchId: Long? = null,
    val sessionId: String? = null,
    val directory: String? = null,
    val formId: String? = null,
    val schemaFingerprint: String? = null,
    val noticeTitle: String? = null,
    val noticeBody: String? = null,
    /** The form/field title (for a small secondary label). */
    val questionTitle: String? = null,
    /** The field description: the actual question the person must answer, shown as the main text. */
    val questionDescription: String? = null,
    val choices: List<SessionPromptChoice> = emptyList(),
    val generation: Long = 0L,
    val deliveryState: SessionPromptDeliveryState = SessionPromptDeliveryState.Idle,
)

/** A non-question notification kind the overlay may display as plain text. */
enum class SessionNoticeKind {
    Ready,
    Error,
    Goal,
    RestartInterrupted,
}

/**
 * Read-only, plain-text notification for the current task session. It is separate from the question
 * projection, so showing a notice never changes a pending answer identity or its generation.
 */
data class SessionNoticeProjection(
    val launchId: Long? = null,
    val sessionId: String? = null,
    val kind: SessionNoticeKind = SessionNoticeKind.Ready,
    val title: String? = null,
    val body: String? = null,
    val generation: Long = 0L,
)

/** The pending/send pair the conversation controller needs; tests use a fake. */
interface SessionPromptTransport {
    suspend fun pending(): JsonObject?

    suspend fun send(
        request: ApiRequest,
        directory: String?,
    ): ApiResult
}

/** Adapts the production local API client without changing it. */
class LocalApiSessionPromptTransport(
    private val api: LocalApiClient,
) : SessionPromptTransport {
    override suspend fun pending(): JsonObject? = api.pending()

    override suspend fun send(
        request: ApiRequest,
        directory: String?,
    ): ApiResult = api.send(request, directory)
}

/**
 * The native overlay's only view of the notification conversation. It never sends a broadcast and
 * never grants phone control; submit goes through the existing local transport once.
 */
interface SessionPromptPort {
    val projection: StateFlow<SessionPromptProjection>

    /** The latest plain notice for the current task session; independent of the question identity. */
    val notice: StateFlow<SessionNoticeProjection?>

    fun onNotification(event: DesktopNotification)

    /** Re-reads the pending form for the current question. No polling. */
    fun refresh()

    /**
     * Clears an old question when the native task owner changes. A different [keepSession] is never
     * adopted; the same session keeps the question but gets a new generation, so an old selection
     * can no longer submit.
     */
    fun invalidate(keepSession: String? = null)

    /** Returns false when this submit was not started (wrong generation, already sending, ...). */
    fun submit(
        formId: String,
        generation: Long,
        value: String,
        fence: () -> Boolean,
    ): Boolean
}

/**
 * Turns one local `question` notification into a small projection of the current pending single-choice
 * form. The raw notification body is bounded here; the server's pending payload is never stored.
 *
 * The source is the local stdout event callback, which runs before notification suppression. The
 * pending state is read once for the event and again when the answer surface is opened or refreshed;
 * there is no periodic poll. A failed read is [SessionPromptDeliveryState.Unknown], never "answered".
 *
 * A submit sets Sending synchronously (so a second tap cannot start another request), re-reads the
 * pending form, and then sends exactly one form reply only while launch, session, directory, form id,
 * schema and the caller's task fence still match. 2xx means accepted, not business success; 0/5xx is
 * Unknown and is never retried automatically; 3xx/4xx is Refused. The response body is never stored.
 */
class SessionPromptController(
    private val transport: SessionPromptTransport,
    private val currentLaunchId: () -> Long?,
    /** The current native phone task session, when one is bound. Null accepts the event. */
    private val currentTaskSession: () -> String? = { null },
    /**
     * Where the caller's task/launch fence is evaluated. Production passes Main.immediate because
     * the overlay's task snapshot is Main-owned. Null runs it inline (JVM tests).
     */
    private val fenceDispatcher: CoroutineDispatcher? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SessionPromptPort {
    private val lock = Any()
    private val mutableProjection = MutableStateFlow(SessionPromptProjection())
    override val projection: StateFlow<SessionPromptProjection> = mutableProjection.asStateFlow()
    private val mutableNotice = MutableStateFlow<SessionNoticeProjection?>(null)
    override val notice: StateFlow<SessionNoticeProjection?> = mutableNotice.asStateFlow()

    private var generationCounter = 0L
    private var presentationToken = 0L
    private var noticeCounter = 0L

    /**
     * One attempt per launch/session/form id. It holds no value or answer. Entries are never evicted:
     * when the table is full, a new attempt is refused rather than forgetting an old one and allowing
     * a duplicate form reply. A new form id or a new launch has a different key.
     */
    private data class SubmitKey(
        val launchId: Long,
        val sessionId: String,
        val formId: String,
    )

    private val submitTombstones = LinkedHashMap<SubmitKey, SessionPromptDeliveryState>()

    override fun onNotification(event: DesktopNotification) {
        if (event.kind == DesktopNotification.Kind.Question) {
            onQuestion(event)
            return
        }
        val noticeKind = event.kind.toSessionNoticeKind() ?: return
        onNotice(event, noticeKind)
    }

    private fun onQuestion(event: DesktopNotification) {
        val sessionId = event.sessionId ?: return
        val launch = currentLaunchId() ?: return
        val taskSession = currentTaskSession()
        if (taskSession != null && taskSession != sessionId) return
        val (token, generation) =
            synchronized(lock) {
                presentationToken += 1
                generationCounter += 1
                mutableProjection.value =
                    SessionPromptProjection(
                        launchId = launch,
                        sessionId = sessionId,
                        directory = event.directory,
                        noticeTitle = bounded(event.title, MAX_NOTICE_TITLE),
                        noticeBody = bounded(event.body, MAX_NOTICE_BODY),
                        generation = generationCounter,
                        deliveryState = SessionPromptDeliveryState.Checking,
                    )
                presentationToken to generationCounter
            }
        readPending(token, launch, sessionId, initial = true)
    }

    /**
     * Plain notices are independent of the question: they never change its form, generation, choices
     * or tombstone. Permission and unknown kinds are deliberately not shown here (permissions keep
     * their original notification path). No permission reply is ever built or sent.
     */
    private fun onNotice(
        event: DesktopNotification,
        kind: SessionNoticeKind,
    ) {
        val sessionId = event.sessionId ?: return
        val launch = currentLaunchId() ?: return
        val taskSession = currentTaskSession()
        if (taskSession != null && taskSession != sessionId) return
        synchronized(lock) {
            noticeCounter += 1
            mutableNotice.value =
                SessionNoticeProjection(
                    launchId = launch,
                    sessionId = sessionId,
                    kind = kind,
                    title = bounded(event.title, MAX_NOTICE_TITLE),
                    body = bounded(event.body, MAX_NOTICE_BODY),
                    generation = noticeCounter,
                )
        }
    }

    override fun refresh() {
        val request =
            synchronized(lock) {
                val current = mutableProjection.value
                val sessionId = current.sessionId ?: return
                if (current.deliveryState == SessionPromptDeliveryState.Sending) return
                val launch = currentLaunchId()
                if (current.launchId == null || launch != current.launchId) {
                    invalidateLocked(keepSession = null)
                    return
                }
                val taskSession = currentTaskSession()
                if (taskSession != null && taskSession != sessionId) return
                presentationToken += 1
                mutableProjection.value = current.copy(deliveryState = SessionPromptDeliveryState.Checking)
                RefreshRequest(presentationToken, current.launchId, sessionId)
            }
        readPending(request.token, request.launchId, request.sessionId, initial = false)
    }

    override fun invalidate(keepSession: String?) {
        synchronized(lock) {
            presentationToken += 1
            generationCounter += 1
            mutableNotice.value = null
            val current = mutableProjection.value
            val launch = currentLaunchId()
            val keep =
                keepSession != null &&
                    current.sessionId == keepSession &&
                    current.launchId != null &&
                    current.launchId == launch
            mutableProjection.value =
                if (keep) {
                    current.copy(
                        generation = generationCounter,
                        choices = emptyList(),
                        deliveryState = SessionPromptDeliveryState.Checking,
                    )
                } else {
                    // A new task owner (or a cleared one) never inherits this form. Remember it so a
                    // duplicate question event for the same launch/session/form cannot rebind it.
                    retireFormLocked(current)
                    SessionPromptProjection(generation = generationCounter)
                }
        }
    }

    override fun submit(
        formId: String,
        generation: Long,
        value: String,
        fence: () -> Boolean,
    ): Boolean {
        val frozen =
            synchronized(lock) {
                val current = mutableProjection.value
                if (current.deliveryState != SessionPromptDeliveryState.Ready) return false
                if (current.sessionId == null || current.launchId == null) return false
                if (current.formId != formId || current.generation != generation) return false
                if (current.choices.none { it.value == value }) return false
                if (currentLaunchId() != current.launchId) return false
                val key = current.submitKeyOrNull() ?: return false
                val previous = submitTombstones[key]
                if (previous != null) {
                    // This form was already attempted; never offer or start a second reply.
                    mutableProjection.value = current.copy(choices = emptyList(), deliveryState = previous)
                    return false
                }
                if (submitTombstones.size >= MAX_SUBMIT_TOMBSTONES) {
                    // Fail closed: do not evict an existing attempt just to make room.
                    mutableProjection.value =
                        current.copy(choices = emptyList(), deliveryState = SessionPromptDeliveryState.Blocked)
                    return false
                }
                mutableProjection.value = current.copy(deliveryState = SessionPromptDeliveryState.Sending)
                current
            }
        scope.launch { runSubmit(frozen, value, fence) }
        return true
    }

    private fun readPending(
        token: Long,
        launch: Long,
        sessionId: String,
        initial: Boolean,
    ) {
        scope.launch {
            val pending =
                try {
                    transport.pending()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            applyPending(token, launch, sessionId, initial, pending)
        }
    }

    private fun applyPending(
        token: Long,
        launch: Long,
        sessionId: String,
        initial: Boolean,
        pending: JsonObject?,
    ) {
        synchronized(lock) {
            val current = mutableProjection.value
            if (presentationToken != token ||
                current.launchId != launch ||
                current.sessionId != sessionId ||
                current.deliveryState == SessionPromptDeliveryState.Sending
            ) {
                return
            }
            if (pending == null) {
                // A failed read is Unknown. It must never look like "already answered".
                mutableProjection.value =
                    current.copy(
                        choices = emptyList(),
                        deliveryState = SessionPromptDeliveryState.Unknown,
                    )
                return
            }
            val session = pending[sessionId]
            val sessionObject = session as? JsonObject
            val forms = sessionObject?.get("forms") as? JsonArray
            val form = PendingForms.singleFormFor(pending, sessionId)
            if (form == null) {
                // A settled question may remove the session entry altogether. A successful empty
                // pending read is different from a failed read (handled above), not an unsupported form.
                val empty = session == null ||
                    (sessionObject != null && (!sessionObject.containsKey("forms") || forms?.isEmpty() == true))
                val attempted = if (empty) current.submitKeyOrNull()?.let { submitTombstones[it] } else null
                val delivery =
                    when {
                        attempted != null -> attempted
                        empty -> SessionPromptDeliveryState.Gone
                        else -> SessionPromptDeliveryState.Unsupported
                    }
                val clearForm = delivery == SessionPromptDeliveryState.Gone
                mutableProjection.value =
                    current.copy(
                        formId = if (clearForm) null else current.formId,
                        schemaFingerprint = if (clearForm) null else current.schemaFingerprint,
                        choices = emptyList(),
                        deliveryState = delivery,
                    )
                return
            }
            if (form.text("sessionID") != sessionId || sessionId == "global") {
                // A form that claims another session (or the global scope) is never answered here.
                mutableProjection.value =
                    current.copy(
                        formId = null,
                        schemaFingerprint = null,
                        choices = emptyList(),
                        deliveryState = SessionPromptDeliveryState.Superseded,
                    )
                return
            }
            val classified = PendingForms.closedChoice(form)
            if (classified == null) {
                mutableProjection.value =
                    current.copy(
                        formId = form.text("id"),
                        schemaFingerprint = null,
                        choices = emptyList(),
                        deliveryState =
                            if (initial && current.formId == null) {
                                SessionPromptDeliveryState.Unsupported
                            } else {
                                SessionPromptDeliveryState.Superseded
                            },
                    )
                return
            }
            val schema = PendingForms.formSchemaFingerprint(form)
            if (schema == null) {
                mutableProjection.value =
                    current.copy(
                        formId = null,
                        schemaFingerprint = null,
                        choices = emptyList(),
                        deliveryState = SessionPromptDeliveryState.Unsupported,
                    )
                return
            }
            val choices = classified.options.map { SessionPromptChoice(it.first, it.second) }
            val field =
                (form["fields"] as? JsonArray)
                    ?.filterIsInstance<JsonObject>()
                    ?.firstOrNull { it.text("key") == classified.fieldKey }
            val formTitle = form.text("title").orEmpty()
            val formDescription = form.text("description").orEmpty()
            val fieldTitle = field?.text("title").orEmpty()
            val fieldDescription = field?.text("description").orEmpty()
            val questionTitle = bounded(fieldTitle.ifBlank { formTitle }, MAX_NOTICE_TITLE)
            val questionDescription = bounded(fieldDescription.ifBlank { formDescription }, MAX_NOTICE_BODY)
            val displayTitle = bounded(formTitle, MAX_NOTICE_TITLE) ?: current.noticeTitle
            // The description is the real question; a generic form/field title must not be all a
            // collapsed or plain notification body shows.
            val displayBody =
                bounded(
                    fieldDescription.ifBlank { formDescription }.ifBlank { fieldTitle },
                    MAX_NOTICE_BODY,
                ) ?: current.noticeBody
            val key = SubmitKey(launch, sessionId, classified.formId)
            val previous = submitTombstones[key]
            val blocked = submitTombstones.size >= MAX_SUBMIT_TOMBSTONES
            if (previous != null || blocked) {
                // A form reply was already attempted for this exact launch/session/form, or the bounded
                // table is full. Keep the display text but never offer Ready again.
                mutableProjection.value =
                    current.copy(
                        formId = classified.formId,
                        schemaFingerprint = schema,
                        noticeTitle = displayTitle,
                        noticeBody = displayBody,
                        questionTitle = questionTitle,
                        questionDescription = questionDescription,
                        choices = emptyList(),
                        deliveryState = previous ?: SessionPromptDeliveryState.Blocked,
                    )
                return
            }
            val changed =
                current.formId != classified.formId ||
                    current.schemaFingerprint != schema ||
                    current.choices != choices
            if (changed) generationCounter += 1
            mutableProjection.value =
                current.copy(
                    formId = classified.formId,
                    schemaFingerprint = schema,
                    noticeTitle = displayTitle,
                    noticeBody = displayBody,
                    questionTitle = questionTitle,
                    questionDescription = questionDescription,
                    choices = choices,
                    generation = if (changed) generationCounter else current.generation,
                    deliveryState = SessionPromptDeliveryState.Ready,
                )
        }
    }

    private suspend fun runSubmit(
        frozen: SessionPromptProjection,
        value: String,
        fence: () -> Boolean,
    ) {
        try {
            if (!fenced(fence) || !stillCurrent(frozen)) {
                finish(frozen, SessionPromptDeliveryState.Superseded)
                return
            }
            val pending = transport.pending()
            if (pending == null) {
                // No POST has happened: this is not a submit attempt and must not be tombstoned.
                finish(frozen, SessionPromptDeliveryState.Unknown)
                return
            }
            if (!stillCurrent(frozen)) {
                finish(frozen, SessionPromptDeliveryState.Superseded)
                return
            }
            if (!fenced(fence)) {
                finish(frozen, SessionPromptDeliveryState.Superseded)
                return
            }
            val sessionId = frozen.sessionId ?: return finish(frozen, SessionPromptDeliveryState.Superseded)
            val form = PendingForms.singleFormFor(pending, sessionId)
            if (form == null) {
                finish(frozen, SessionPromptDeliveryState.Gone)
                return
            }
            if (form.text("sessionID") != sessionId) {
                finish(frozen, SessionPromptDeliveryState.Superseded)
                return
            }
            val answer = PendingForms.closedChoice(form)
            if (answer == null) {
                finish(frozen, SessionPromptDeliveryState.Superseded)
                return
            }
            val schema = PendingForms.formSchemaFingerprint(form)
            if (answer.formId != frozen.formId ||
                answer.sessionId != sessionId ||
                schema == null ||
                schema != frozen.schemaFingerprint ||
                answer.options.none { it.first == value }
            ) {
                finish(frozen, SessionPromptDeliveryState.Superseded)
                return
            }
            if (!stillCurrent(frozen) || !fenced(fence)) {
                finish(frozen, SessionPromptDeliveryState.Superseded)
                return
            }
            // Atomically record the one attempt immediately before the bytes can leave.
            val refused = beginAttempt(frozen)
            if (refused != null) {
                finish(frozen, refused)
                return
            }
            // Exactly one request. The status only says delivery, never business success.
            val result =
                transport.send(
                    NotificationActions.formReply(sessionId, answer.formId, answer.fieldKey, value),
                    frozen.directory,
                )
            when (ActionDecisions.outcomeOf(result.status)) {
                SendOutcome.Sent -> finish(frozen, SessionPromptDeliveryState.Accepted)
                SendOutcome.Failed -> finish(frozen, SessionPromptDeliveryState.Refused)
                SendOutcome.Unknown -> finish(frozen, SessionPromptDeliveryState.Unknown)
            }
        } catch (e: CancellationException) {
            // A cancelled send may still have reached the server; never retry it automatically.
            finish(frozen, SessionPromptDeliveryState.Unknown)
            throw e
        } catch (_: Exception) {
            finish(frozen, SessionPromptDeliveryState.Unknown)
        }
    }

    private suspend fun fenced(fence: () -> Boolean): Boolean =
        if (fenceDispatcher == null) {
            fence()
        } else {
            withContext(fenceDispatcher) { fence() }
        }

    /**
     * Atomically records the one attempt immediately before the caller may send. Returns null only
     * when the tombstone was added; otherwise returns the state to show without sending.
     */
    private fun beginAttempt(frozen: SessionPromptProjection): SessionPromptDeliveryState? =
        synchronized(lock) {
            if (!stillCurrentLocked(frozen)) return SessionPromptDeliveryState.Superseded
            val key = frozen.submitKeyOrNull() ?: return SessionPromptDeliveryState.Superseded
            submitTombstones[key]?.let { return it }
            if (submitTombstones.size >= MAX_SUBMIT_TOMBSTONES) {
                return SessionPromptDeliveryState.Blocked
            }
            submitTombstones[key] = SessionPromptDeliveryState.Sending
            null
        }

    private fun stillCurrent(frozen: SessionPromptProjection): Boolean = synchronized(lock) { stillCurrentLocked(frozen) }

    private fun stillCurrentLocked(frozen: SessionPromptProjection): Boolean {
        val current = mutableProjection.value
        return current.deliveryState == SessionPromptDeliveryState.Sending &&
            current.generation == frozen.generation &&
            current.launchId == frozen.launchId &&
            current.sessionId == frozen.sessionId &&
            current.directory == frozen.directory &&
            current.formId == frozen.formId &&
            current.schemaFingerprint == frozen.schemaFingerprint
    }

    private fun finish(
        frozen: SessionPromptProjection,
        state: SessionPromptDeliveryState,
    ) {
        synchronized(lock) {
            // Update the attempt record even if a newer question already replaced the projection: the
            // form was still attempted and must never be offered again for this launch/session/form.
            if (state == SessionPromptDeliveryState.Accepted ||
                state == SessionPromptDeliveryState.Unknown ||
                state == SessionPromptDeliveryState.Refused
            ) {
                val key = frozen.submitKeyOrNull()
                if (key != null && submitTombstones[key] == SessionPromptDeliveryState.Sending) {
                    submitTombstones[key] = state
                }
            }
            val current = mutableProjection.value
            if (current.deliveryState != SessionPromptDeliveryState.Sending ||
                current.generation != frozen.generation
            ) {
                return
            }
            mutableProjection.value =
                current.copy(
                    choices = emptyList(),
                    formId = if (state == SessionPromptDeliveryState.Gone) null else current.formId,
                    schemaFingerprint =
                        if (state == SessionPromptDeliveryState.Gone) null else current.schemaFingerprint,
                    deliveryState = state,
                )
        }
    }

    /** Caller holds [lock]. */
    private fun invalidateLocked(keepSession: String?) {
        presentationToken += 1
        generationCounter += 1
        mutableNotice.value = null
        val current = mutableProjection.value
        val launch = currentLaunchId()
        val keep =
            keepSession != null &&
                current.sessionId == keepSession &&
                current.launchId != null &&
                current.launchId == launch
        mutableProjection.value =
            if (keep) {
                current.copy(
                    generation = generationCounter,
                    choices = emptyList(),
                    deliveryState = SessionPromptDeliveryState.Checking,
                )
            } else {
                retireFormLocked(current)
                SessionPromptProjection(generation = generationCounter)
            }
    }

    /** Caller holds [lock]. Bounded; never evicts and never overrides an attempt record. */
    private fun retireFormLocked(current: SessionPromptProjection) {
        val key = current.submitKeyOrNull() ?: return
        if (submitTombstones.containsKey(key)) return
        if (submitTombstones.size >= MAX_SUBMIT_TOMBSTONES) return
        submitTombstones[key] = SessionPromptDeliveryState.Superseded
    }

    private fun SessionPromptProjection.submitKeyOrNull(): SubmitKey? {
        val launch = launchId ?: return null
        val session = sessionId ?: return null
        val form = formId ?: return null
        return SubmitKey(launch, session, form)
    }

    private fun DesktopNotification.Kind.toSessionNoticeKind(): SessionNoticeKind? =
        when (this) {
            DesktopNotification.Kind.Ready -> SessionNoticeKind.Ready
            DesktopNotification.Kind.Error -> SessionNoticeKind.Error
            DesktopNotification.Kind.Goal -> SessionNoticeKind.Goal
            DesktopNotification.Kind.RestartInterrupted -> SessionNoticeKind.RestartInterrupted
            // Permissions keep their original notification actions; never projected as text/question.
            DesktopNotification.Kind.Question,
            DesktopNotification.Kind.Permission,
            DesktopNotification.Kind.Plugin,
            DesktopNotification.Kind.Unknown,
            -> null
        }

    private data class RefreshRequest(
        val token: Long,
        val launchId: Long,
        val sessionId: String,
    )

    private companion object {
        const val MAX_NOTICE_TITLE = 160
        const val MAX_NOTICE_BODY = 640
        const val MAX_SUBMIT_TOMBSTONES = 64

        fun bounded(
            text: String,
            max: Int,
        ): String? = text.takeIf { it.isNotBlank() }?.take(max)
    }
}
