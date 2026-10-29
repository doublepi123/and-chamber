package io.andchamber.app.phone.android

import io.andchamber.app.phone.core.PhoneErrorCode
import io.andchamber.app.phone.standing.PhoneTaskStartFailureStage
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * One bounded observation read inside a standing start/continuation. A read never carries page
 * text, node labels or raw exceptions; only a fixed package identity or a protocol error.
 */
internal sealed interface PhoneStandingObserveRead {
    data class Observed(val packageName: String) : PhoneStandingObserveRead

    /** A real window transition or a target that is not foreground yet. */
    data class Retryable(val error: PhoneErrorCode) : PhoneStandingObserveRead

    /** The first terminal typed failure; it is never replaced by a later read. */
    data class Failed(val error: PhoneErrorCode) : PhoneStandingObserveRead
}

internal sealed interface PhoneStandingObserveResult {
    data object Observed : PhoneStandingObserveResult

    data class Failed(
        val stage: PhoneTaskStartFailureStage,
        val error: PhoneErrorCode?,
    ) : PhoneStandingObserveResult
}

/**
 * Only a real window transition or a target that is not foreground yet may be retried inside the
 * same budget. Hierarchy busy/timeout, read timeout, deadline, lost connection, cancellation,
 * security refusals and every other driver failure stop immediately with their first typed error.
 */
internal fun PhoneErrorCode.retryableStandingObserve(): Boolean =
    when (this) {
        PhoneErrorCode.WINDOW_CHANGED,
        PhoneErrorCode.GEOMETRY_CHANGED,
        PhoneErrorCode.TARGET_MISMATCH,
        -> true
        else -> false
    }

/** Fixed diagnostic token: a stage/error name only, never page/model text or a raw exception. */
internal fun standingStartFailureToken(
    stage: PhoneTaskStartFailureStage,
    error: PhoneErrorCode?,
): String =
    buildString {
        append("start_failed_")
        append(stage.name.lowercase(Locale.ROOT))
        if (error != null) {
            append('_')
            append(error.name.lowercase(Locale.ROOT))
        }
    }

/** Keeps the retryable/terminal decision in one place for every observation caller. */
internal fun PhoneErrorCode.toStandingObserveRead(): PhoneStandingObserveRead =
    if (retryableStandingObserve()) {
        PhoneStandingObserveRead.Retryable(this)
    } else {
        PhoneStandingObserveRead.Failed(this)
    }

/**
 * The shared observation budget. The caller computes one absolute monotonic deadline before any
 * overlay wait; this loop never grants a fresh budget per attempt. A retry waits at most
 * [retryDelayMs] and never past the deadline, and a read receives the real remaining milliseconds
 * so the controller's own per-call timeout cannot extend the total. Cancellation is not caught: a
 * cancelled caller stops without another attempt.
 */
internal suspend fun observeStandingWithinBudget(
    deadlineElapsedMs: Long,
    nowElapsedMs: () -> Long,
    acceptPackage: (String) -> Boolean,
    maxAttempts: Int = STANDING_OBSERVE_MAX_ATTEMPTS,
    retryDelayMs: Long = STANDING_OBSERVE_RETRY_MS,
    delayFor: suspend (Long) -> Unit = { delay(it) },
    read: suspend (remainingMs: Long) -> PhoneStandingObserveRead,
): PhoneStandingObserveResult {
    require(maxAttempts > 0) { "attempts" }
    require(retryDelayMs >= 0L) { "retry delay" }
    var lastRetryable: PhoneErrorCode? = null
    var attempt = 0
    while (attempt < maxAttempts) {
        if (attempt > 0) {
            val beforeWait = deadlineElapsedMs - nowElapsedMs()
            if (beforeWait <= 0L) return failedObserve(PhoneErrorCode.DEADLINE)
            delayFor(minOf(retryDelayMs, beforeWait))
        }
        val remaining = deadlineElapsedMs - nowElapsedMs()
        if (remaining <= 0L) return failedObserve(PhoneErrorCode.DEADLINE)
        attempt += 1
        when (val observed = read(remaining)) {
            is PhoneStandingObserveRead.Observed -> {
                if (acceptPackage(observed.packageName)) {
                    // A result that arrives past the shared deadline is never accepted.
                    return if (nowElapsedMs() <= deadlineElapsedMs) {
                        PhoneStandingObserveResult.Observed
                    } else {
                        failedObserve(PhoneErrorCode.DEADLINE)
                    }
                }
                lastRetryable = PhoneErrorCode.TARGET_MISMATCH
            }
            is PhoneStandingObserveRead.Retryable -> lastRetryable = observed.error
            is PhoneStandingObserveRead.Failed -> return failedObserve(observed.error)
        }
        if (nowElapsedMs() >= deadlineElapsedMs) return failedObserve(PhoneErrorCode.DEADLINE)
    }
    return failedObserve(lastRetryable ?: PhoneErrorCode.DEADLINE)
}

private fun failedObserve(error: PhoneErrorCode): PhoneStandingObserveResult.Failed =
    PhoneStandingObserveResult.Failed(
        stage = PhoneTaskStartFailureStage.OBSERVATION,
        error = error,
    )

internal const val STANDING_OBSERVE_MAX_ATTEMPTS: Int = 6
internal const val STANDING_OBSERVE_RETRY_MS: Long = 500L
