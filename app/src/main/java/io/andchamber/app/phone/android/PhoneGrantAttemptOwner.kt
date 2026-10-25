package io.andchamber.app.phone.android

import io.andchamber.app.phone.tool.PhonePendingRequest
import io.andchamber.app.ui.phone.PhoneDiagnosticTokens
import io.andchamber.app.ui.phone.PhonePending

/**
 * Identity for one native grant confirmation attempt.
 *
 * A grant attempt is not authority. Its only job is to decide whether the continuation that froze
 * the person's confirmation at the click may still consume the proposal, publish the route, commit
 * the task, or clean up after a failure. Every stop control invalidates the current attempt, and a
 * newer grant replaces it. The controller's epoch check remains in place independently.
 *
 * All methods are called on the integration's main thread.
 */
internal class PhoneGrantAttemptOwner {
    class Attempt internal constructor(val id: Long)

    private var nextId = 0L
    private var current: Attempt? = null

    /** Starts a new attempt, invalidating the previous one. */
    fun begin(): Attempt {
        nextId += 1
        return Attempt(nextId).also { current = it }
    }

    fun isCurrent(attempt: Attempt): Boolean = current === attempt

    /**
     * Claims [attempt] for its one success commit or failure cleanup. Returns false when the attempt
     * was already superseded or invalidated; that continuation must not touch native state.
     */
    fun finish(attempt: Attempt): Boolean {
        if (current !== attempt) return false
        current = null
        return true
    }

    /** Invalidates the current attempt and returns it, or null when there is nothing to invalidate. */
    fun invalidate(): Attempt? {
        val previous = current ?: return null
        current = null
        return previous
    }
}

/** Pending value after an invalidation: an invalidated grant attempt never leaves "Granting". */
internal fun pendingAfterGrantInvalidation(
    invalidated: PhoneGrantAttemptOwner.Attempt?,
    pending: PhonePending?,
): PhonePending? =
    if (invalidated != null && pending == PhonePending.Granting) null else pending

/** What one frozen confirmation may still do after waiting for the foreground stop entry. */
internal sealed interface PhoneGrantAttemptOutcome {
    /** The attempt was superseded or invalidated. Touch no route, service, store, lease or facts. */
    data object Stale : PhoneGrantAttemptOutcome

    data class Ready(val confirmedSession: String?) : PhoneGrantAttemptOutcome

    data class Failed(val errorToken: String) : PhoneGrantAttemptOutcome
}

/**
 * Production grant-routing flow. Owner checks wrap the real [routeAfterStopEntry] wait so an
 * invalidated continuation cannot start the service, consume the proposal, or publish a route.
 */
internal suspend fun runGrantAttempt(
    owner: PhoneGrantAttemptOwner,
    attempt: PhoneGrantAttemptOwner.Attempt,
    epoch: Long,
    frozenRevision: Long?,
    frozenResumeSession: String?,
    awaitStopEntry: suspend () -> Boolean,
    currentEpoch: () -> Long?,
    currentRevision: () -> Long?,
    consume: (Long) -> PhonePendingRequest?,
    publish: (Long, String?) -> Boolean,
): PhoneGrantAttemptOutcome {
    if (!owner.isCurrent(attempt)) return PhoneGrantAttemptOutcome.Stale
    val routing =
        routeAfterStopEntry(
            epoch = epoch,
            frozenRevision = frozenRevision,
            frozenResumeSession = frozenResumeSession,
            awaitStopEntry = { owner.isCurrent(attempt) && awaitStopEntry() },
            currentEpoch = { if (owner.isCurrent(attempt)) currentEpoch() else null },
            currentRevision = { if (owner.isCurrent(attempt)) currentRevision() else null },
            consume = { revision -> if (owner.isCurrent(attempt)) consume(revision) else null },
            publish = { routeEpoch, label ->
                owner.isCurrent(attempt) && publish(routeEpoch, label)
            },
        )
    if (!owner.isCurrent(attempt)) return PhoneGrantAttemptOutcome.Stale
    return when (routing) {
        is PhoneGrantRouting.Ready -> PhoneGrantAttemptOutcome.Ready(routing.confirmedSession)
        PhoneGrantRouting.ServiceUnavailable ->
            PhoneGrantAttemptOutcome.Failed(PhoneIntegration.GRANT_TOKEN_SERVICE_UNAVAILABLE)
        PhoneGrantRouting.ConfirmationChanged ->
            PhoneGrantAttemptOutcome.Failed(GRANT_TOKEN_CONFIRMATION_CHANGED)
        PhoneGrantRouting.EpochChanged ->
            PhoneGrantAttemptOutcome.Failed(GRANT_TOKEN_EPOCH_CHANGED)
        PhoneGrantRouting.PublishFailed ->
            PhoneGrantAttemptOutcome.Failed(PhoneDiagnosticTokens.GRANT_ROUTE_UNAVAILABLE)
    }
}

/**
 * Single success/failure gate. A stale outcome does nothing; a current attempt is claimed exactly
 * once before its commit or failure cleanup, so a later stop cannot let an older path erase it.
 */
internal fun applyGrantAttemptOutcome(
    owner: PhoneGrantAttemptOwner,
    attempt: PhoneGrantAttemptOwner.Attempt,
    outcome: PhoneGrantAttemptOutcome,
    commit: (String?) -> Unit,
    fail: (String) -> Unit,
) {
    when (outcome) {
        PhoneGrantAttemptOutcome.Stale -> Unit
        is PhoneGrantAttemptOutcome.Ready ->
            if (owner.finish(attempt)) commit(outcome.confirmedSession)
        is PhoneGrantAttemptOutcome.Failed ->
            if (owner.finish(attempt)) fail(outcome.errorToken)
    }
}
