package io.andchamber.app.phone.android.overlay

import io.andchamber.app.notify.SessionNoticeKind
import io.andchamber.app.notify.SessionNoticeProjection
import io.andchamber.app.notify.SessionPromptDeliveryState
import io.andchamber.app.notify.SessionPromptProjection
import io.andchamber.app.phone.core.PhoneTimeLimits
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.overlay.progress.PhoneTaskSnapshot
import io.andchamber.app.phone.overlay.progress.PhoneTaskState

/** One answer option the overlay may show. Only [value] is sent back, through the caller's action. */
internal data class PhoneOverlayPromptChoice(
    val value: String,
    val label: String,
)

/** Delivery, not business success. The overlay only offers choices in [Ready]. */
internal enum class PhoneOverlayPromptDelivery {
    Checking,
    Ready,
    Sending,
    Accepted,
    Unknown,
    /** Duplicate-protection storage is full: this is read-only; no request was started. */
    Blocked,
    Refused,
    Unsupported,
    Superseded,
}

/**
 * Native view of the current task's notification question.
 *
 * The view is deliberately bound to the current task snapshot: the overlay can show a retained
 * task's question without a live lease, but never for another task or before the task's route
 * session is known. [generation] and [formId] are the frozen answer identity the person saw.
 */
internal data class PhoneOverlayPromptView(
    val formId: String,
    val generation: Long,
    val noticeTitle: String?,
    val noticeBody: String?,
    /** Secondary label (form/field title), e.g. "Questions" / "Next step". */
    val questionTitle: String?,
    /** The actual question to show prominently; fall back to [noticeBody] when null. */
    val questionDescription: String?,
    val choices: List<PhoneOverlayPromptChoice>,
    val delivery: PhoneOverlayPromptDelivery,
) {
    /** Only a ready single-choice question may be submitted; every other state opens the chat. */
    val canSubmit: Boolean get() = delivery == PhoneOverlayPromptDelivery.Ready && choices.isNotEmpty()
}

/**
 * Plain, non-question notification text for the current task. It is independent of the question
 * answer identity and never carries a form, a choice or an answer.
 */
internal data class PhoneOverlayNoticeView(
    val kind: PhoneOverlayNoticeKind,
    val title: String?,
    val body: String?,
    val generation: Long,
)

internal enum class PhoneOverlayNoticeKind {
    Ready,
    Error,
    Goal,
    RestartInterrupted,
}

/**
 * The exact native task owner a rendered question belongs to. It is frozen when the person taps and
 * checked again immediately before the one form reply, together with the local launch.
 */
internal data class PhoneTaskConversationOwner(
    val taskId: String,
    val runtimeId: String,
    val grantEpoch: Long,
    val routeSession: String,
    val launchId: Long,
)

/**
 * Why the conversation projection must be reconciled after a native task change.
 *
 * A different task key is a hard boundary: the old question and notice are cleared and never
 * re-adopted from the global pending table, even when the route session is the same. Only a new
 * question event may project a question for the new task. A new epoch of the *same* task may keep the
 * same task's question, but gets a fresh generation and a new pending check.
 */
internal data class ConversationReconcile(
    val invalidate: Boolean,
    /** Non-null only for the same task key; the same task/session may revalidate its own form. */
    val keepSession: String?,
    val refresh: Boolean,
)

internal fun conversationReconcile(
    previousKey: PhoneTaskKey?,
    previousEpoch: Long?,
    nextKey: PhoneTaskKey?,
    nextEpoch: Long?,
    nextRouteSession: String?,
): ConversationReconcile =
    when {
        previousKey == null && nextKey == null -> ConversationReconcile(false, null, false)
        // A new or cleared task is never an invitation to adopt the global pending form again.
        previousKey == null || nextKey == null || previousKey != nextKey ->
            ConversationReconcile(invalidate = true, keepSession = null, refresh = false)
        // Same task, new grant epoch: the question can belong to the same task, but only after a fresh
        // generation and a new pending check. A null route cannot be bound to anything.
        previousEpoch != nextEpoch && nextRouteSession != null ->
            ConversationReconcile(invalidate = true, keepSession = nextRouteSession, refresh = true)
        previousEpoch != nextEpoch ->
            ConversationReconcile(invalidate = true, keepSession = null, refresh = false)
        else -> ConversationReconcile(false, null, false)
    }

/** One user submit waiting for its final delivery fact; memory only, never an answer. */
internal data class PhonePromptSubmitWatch(
    val owner: PhoneTaskConversationOwner,
    val formId: String,
    val generation: Long,
)

/** A false submit never collapses the panel and never creates a watch. */
internal fun promptSubmitWatchFor(
    submitted: Boolean,
    owner: PhoneTaskConversationOwner,
    formId: String,
    generation: Long,
): PhonePromptSubmitWatch? =
    if (submitted) {
        PhonePromptSubmitWatch(owner = owner, formId = formId, generation = generation)
    } else {
        null
    }

/**
 * Only a failed, still-same-owner/same-answer attempt may reopen the panel read-only. A different
 * task/epoch/launch or a changed form/generation never reopens the old panel; Accepted stays collapsed.
 */
internal fun promptSubmitShouldExpand(
    watch: PhonePromptSubmitWatch?,
    sameOwner: Boolean,
    sameAnswerIdentity: Boolean,
    delivery: SessionPromptDeliveryState,
): Boolean {
    if (watch == null || !sameOwner || !sameAnswerIdentity) return false
    return delivery == SessionPromptDeliveryState.Unknown ||
        delivery == SessionPromptDeliveryState.Refused ||
        delivery == SessionPromptDeliveryState.Superseded ||
        delivery == SessionPromptDeliveryState.Blocked
}

/**
 * Projects the memory-only notification question into the overlay view, but only while it belongs to
 * the current native task session. A null route session is never replaced with another session: the
 * question is simply not shown.
 */
internal fun phoneOverlayPromptView(
    projection: SessionPromptProjection,
    snapshot: PhoneTaskSnapshot?,
    launchId: Long?,
): PhoneOverlayPromptView? {
    val sessionId = projection.sessionId ?: return null
    val projectionLaunch = projection.launchId ?: return null
    if (launchId == null || projectionLaunch != launchId) return null
    val task = snapshot ?: return null
    if (task.state == PhoneTaskState.ENDED_PENDING_CHECK) return null
    val routeSession = task.routeSession ?: return null
    if (routeSession != sessionId) return null
    val delivery = projection.deliveryState.toOverlayDelivery() ?: return null
    return PhoneOverlayPromptView(
        formId = projection.formId.orEmpty(),
        generation = projection.generation,
        noticeTitle = projection.noticeTitle,
        noticeBody = projection.noticeBody,
        questionTitle = projection.questionTitle,
        questionDescription = projection.questionDescription,
        choices = projection.choices.map { PhoneOverlayPromptChoice(it.value, it.label) },
        delivery = delivery,
    )
}

private fun SessionPromptDeliveryState.toOverlayDelivery(): PhoneOverlayPromptDelivery? =
    when (this) {
        // No question, or the form was answered elsewhere: the panel shows no answer surface.
        SessionPromptDeliveryState.Idle, SessionPromptDeliveryState.Gone -> null
        SessionPromptDeliveryState.Checking -> PhoneOverlayPromptDelivery.Checking
        SessionPromptDeliveryState.Ready -> PhoneOverlayPromptDelivery.Ready
        SessionPromptDeliveryState.Sending -> PhoneOverlayPromptDelivery.Sending
        SessionPromptDeliveryState.Accepted -> PhoneOverlayPromptDelivery.Accepted
        SessionPromptDeliveryState.Unknown -> PhoneOverlayPromptDelivery.Unknown
        SessionPromptDeliveryState.Blocked -> PhoneOverlayPromptDelivery.Blocked
        SessionPromptDeliveryState.Refused -> PhoneOverlayPromptDelivery.Refused
        SessionPromptDeliveryState.Unsupported -> PhoneOverlayPromptDelivery.Unsupported
        SessionPromptDeliveryState.Superseded -> PhoneOverlayPromptDelivery.Superseded
    }

/**
 * Maps a plain notice to the current task only. A null route is never replaced with a standing session,
 * and an old launch never leaks into the current surface.
 */
internal fun phoneOverlayNoticeView(
    notice: SessionNoticeProjection?,
    snapshot: PhoneTaskSnapshot?,
    launchId: Long?,
): PhoneOverlayNoticeView? {
    val value = notice ?: return null
    val noticeLaunch = value.launchId ?: return null
    if (launchId == null || noticeLaunch != launchId) return null
    val task = snapshot ?: return null
    val routeSession = task.routeSession ?: return null
    if (routeSession != value.sessionId) return null
    return PhoneOverlayNoticeView(
        kind = value.kind.toOverlayNoticeKind(),
        title = value.title,
        body = value.body,
        generation = value.generation,
    )
}

private fun SessionNoticeKind.toOverlayNoticeKind(): PhoneOverlayNoticeKind =
    when (this) {
        SessionNoticeKind.Ready -> PhoneOverlayNoticeKind.Ready
        SessionNoticeKind.Error -> PhoneOverlayNoticeKind.Error
        SessionNoticeKind.Goal -> PhoneOverlayNoticeKind.Goal
        SessionNoticeKind.RestartInterrupted -> PhoneOverlayNoticeKind.RestartInterrupted
    }

/**
 * True only while the frozen task/key/epoch/route and launch still own the current question. A new
 * task, a new epoch, a closed result or a changed projection all refuse the submit with zero requests.
 */
internal fun phoneTaskConversationStillOwns(
    owner: PhoneTaskConversationOwner,
    snapshot: PhoneTaskSnapshot?,
    launchId: Long?,
    projection: SessionPromptProjection,
): Boolean {
    val task = snapshot ?: return false
    if (task.state == PhoneTaskState.ENDED_PENDING_CHECK) return false
    if (task.key.taskId != owner.taskId) return false
    if (task.key.runtimeId != owner.runtimeId) return false
    if (task.grantEpoch != owner.grantEpoch) return false
    if (task.routeSession != owner.routeSession) return false
    if (launchId == null || launchId != owner.launchId) return false
    if (projection.sessionId != owner.routeSession) return false
    if (projection.launchId != owner.launchId) return false
    return true
}

/**
 * A permanent native grant has no countdown. It is exposed as [leaseEndsAtMs] = null plus the explicit
 * [leasePermanent] fact, never as a huge remaining-minute value. A finite grant (including a permanent
 * per-task duration still bounded by a finite standing window) keeps its real deadline.
 */
internal data class OverlayLeasePresentation(
    val leaseEndsAtMs: Long?,
    val leasePermanent: Boolean,
)

internal fun overlayLeasePresentation(expiresAtMs: Long?): OverlayLeasePresentation =
    if (expiresAtMs != null && PhoneTimeLimits.isPermanent(expiresAtMs)) {
        OverlayLeasePresentation(leaseEndsAtMs = null, leasePermanent = true)
    } else {
        OverlayLeasePresentation(leaseEndsAtMs = expiresAtMs, leasePermanent = false)
    }
