package io.andchamber.app.ui.browser

/**
 * One capture attempt. The image may leave only with the stamp sampled before the copy and confirmed
 * after it. A moved presentation, a new document, or a changed CSS fingerprint discards the bytes.
 */
data class CaptureStamp(
    val viewId: String,
    val documentId: String?,
    val ticket: Long,
    val refEpoch: Long,
    val presentation: CaptureFrame,
    val probe: GeometryProbe,
)

data class CaptureObservation(
    val viewId: String,
    val documentId: String?,
    val ticket: Long,
    val refEpoch: Long,
    val presentation: CaptureFrame,
)

data class CaptureBytes(val payload: ByteArray, val width: Int, val height: Int)

data class CaptureAttempt(val stamp: CaptureStamp, val image: CaptureBytes)

/** Outcome of sampling a stamp. A presentation move is transient and retryable; an unavailable
 * page or a changed identity is not. */
internal sealed interface CaptureStampResult {
    data class Ready(val stamp: CaptureStamp) : CaptureStampResult
    data object Moved : CaptureStampResult
    data object Unavailable : CaptureStampResult
}

internal enum class CaptureRefusal(val retryable: Boolean) {
    /** No stable presentation or probe to capture: detached, clipped, scaled out of bounds, or the
     * page stopped before the sample. Retrying cannot repair it. */
    UNAVAILABLE(false),

    /** The view, document, ticket or ref epoch changed. The caller must make a new request. */
    IDENTITY_CHANGED(false),

    /** Scale, drawn page rect, clip or window moved during this attempt. A bounded retry may win. */
    PRESENTATION_CHANGED(true),

    /** PixelCopy failed or the encoded image was refused. */
    COPY_FAILED(false),
}

internal sealed interface CaptureOutcome {
    data class Captured(val attempt: CaptureAttempt) : CaptureOutcome
    data class Refused(val reason: CaptureRefusal) : CaptureOutcome
}

/**
 * A presentation move only discards this attempt. It never clears refs, revokes a grant, or cancels
 * another agent request. The cap keeps a moving window from spinning capture.
 */
internal object CaptureRetry {
    const val MAX_ATTEMPTS = 3

    /** [attempts] is the number of attempts already made, including the one that just failed. */
    fun shouldRetry(outcome: CaptureOutcome, attempts: Int): Boolean =
        attempts < MAX_ATTEMPTS &&
            outcome is CaptureOutcome.Refused &&
            outcome.reason.retryable
}

/**
 * One guarded capture. The sample, the observation before the copy, the observation after it and a
 * fresh sample must all agree before the bytes may leave. A frame move is reported as retryable;
 * everything else is a stable refusal.
 */
internal suspend fun guardedCapture(
    sample: suspend () -> CaptureStampResult,
    current: () -> CaptureObservation?,
    copy: suspend (CaptureStamp) -> CaptureBytes?,
): CaptureOutcome {
    val before = when (val sampled = sample()) {
        is CaptureStampResult.Ready -> sampled.stamp
        CaptureStampResult.Moved -> return CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED)
        CaptureStampResult.Unavailable -> return CaptureOutcome.Refused(CaptureRefusal.UNAVAILABLE)
    }
    val atSample = current() ?: return CaptureOutcome.Refused(CaptureRefusal.UNAVAILABLE)
    if (!sameCaptureIdentity(before, atSample)) return CaptureOutcome.Refused(CaptureRefusal.IDENTITY_CHANGED)
    if (before.presentation != atSample.presentation) return CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED)
    val copied = copy(before)
    if (copied == null) {
        // A copy refused because the page moved is transient, not a broken capture path.
        val afterFailure = current()
        return if (afterFailure != null && sameCaptureIdentity(before, afterFailure) &&
            before.presentation != afterFailure.presentation
        ) {
            CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED)
        } else {
            CaptureOutcome.Refused(CaptureRefusal.COPY_FAILED)
        }
    }
    val afterCopy = current() ?: return CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED)
    if (!sameCaptureIdentity(before, afterCopy)) return CaptureOutcome.Refused(CaptureRefusal.IDENTITY_CHANGED)
    if (before.presentation != afterCopy.presentation) return CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED)
    val after = when (val resampled = sample()) {
        is CaptureStampResult.Ready -> resampled.stamp
        CaptureStampResult.Moved -> return CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED)
        CaptureStampResult.Unavailable -> return CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED)
    }
    if (!sameCaptureIdentity(before, observationOf(after))) return CaptureOutcome.Refused(CaptureRefusal.IDENTITY_CHANGED)
    if (after.presentation != before.presentation || after.probe != before.probe) {
        return CaptureOutcome.Refused(CaptureRefusal.PRESENTATION_CHANGED)
    }
    return CaptureOutcome.Captured(CaptureAttempt(before, copied))
}

internal fun sameCaptureIdentity(stamp: CaptureStamp, observed: CaptureObservation): Boolean =
    stamp.viewId == observed.viewId &&
        stamp.documentId == observed.documentId &&
        stamp.ticket == observed.ticket &&
        stamp.refEpoch == observed.refEpoch

internal fun observationOf(stamp: CaptureStamp): CaptureObservation =
    CaptureObservation(stamp.viewId, stamp.documentId, stamp.ticket, stamp.refEpoch, stamp.presentation)
