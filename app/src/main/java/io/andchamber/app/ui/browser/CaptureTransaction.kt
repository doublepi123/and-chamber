package io.andchamber.app.ui.browser

/**
 * One capture attempt. The image may leave only with the stamp sampled before the copy and confirmed
 * after it. A moved frame, a new document, or a changed CSS fingerprint discards the bytes.
 */
data class CaptureStamp(
    val viewId: String,
    val documentId: String?,
    val ticket: Long,
    val refEpoch: Long,
    val frame: NativeFrame,
    val probe: GeometryProbe,
)

data class CaptureObservation(
    val viewId: String,
    val documentId: String?,
    val ticket: Long,
    val refEpoch: Long,
    val frame: NativeFrame,
)

data class CaptureBytes(val payload: ByteArray, val width: Int, val height: Int)

data class CaptureAttempt(val stamp: CaptureStamp, val image: CaptureBytes)

internal suspend fun guardedCapture(
    sample: suspend () -> CaptureStamp?,
    current: () -> CaptureObservation,
    copy: suspend (CaptureStamp) -> CaptureBytes?,
): CaptureAttempt? {
    val before = sample() ?: return null
    if (!sameCapture(before, current())) return null
    val copied = copy(before) ?: return null
    if (!sameCapture(before, current())) return null
    val after = sample() ?: return null
    if (after != before) return null
    return CaptureAttempt(before, copied)
}

internal fun sameCapture(stamp: CaptureStamp, observed: CaptureObservation): Boolean =
    stamp.viewId == observed.viewId &&
        stamp.documentId == observed.documentId &&
        stamp.ticket == observed.ticket &&
        stamp.refEpoch == observed.refEpoch &&
        stamp.frame == observed.frame
