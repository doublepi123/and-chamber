package io.andchamber.app.ui.browser

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits for two different accepted geometry samples that agree.
 *
 * [request] only asks for a probe. This function never changes the page.
 * A sample is accepted only when its serial is newer than the last accepted
 * one and at least the serial returned by the request that is still waiting.
 * A newer external serial is accepted. An older cached serial is not.
 * Timeout returns null, never the previous cache.
 */
internal suspend fun <T> awaitStableGeometryPair(
    request: () -> Long,
    latest: () -> T?,
    serial: (T) -> Long,
    matches: (T) -> Boolean,
    fingerprint: (T) -> Any,
    stillCurrent: () -> Boolean,
    timeoutMs: Long = 2_000,
): Pair<T, T>? = withTimeoutOrNull(timeoutMs) {
    if (!stillCurrent()) return@withTimeoutOrNull null
    var lastAcceptedSerial = 0L
    var first = awaitAccepted(
        wantedSerial = request(),
        lastAcceptedSerial = lastAcceptedSerial,
        latest = latest,
        serial = serial,
        matches = matches,
        stillCurrent = stillCurrent,
    ) ?: return@withTimeoutOrNull null
    while (stillCurrent()) {
        lastAcceptedSerial = serial(first)
        val second = awaitAccepted(
            wantedSerial = request(),
            lastAcceptedSerial = lastAcceptedSerial,
            latest = latest,
            serial = serial,
            matches = matches,
            stillCurrent = stillCurrent,
        ) ?: return@withTimeoutOrNull null
        if (!stillCurrent()) return@withTimeoutOrNull null
        if (fingerprint(first) == fingerprint(second)) return@withTimeoutOrNull first to second
        first = second
    }
    null
}

private suspend fun <T> awaitAccepted(
    wantedSerial: Long,
    lastAcceptedSerial: Long,
    latest: () -> T?,
    serial: (T) -> Long,
    matches: (T) -> Boolean,
    stillCurrent: () -> Boolean,
): T? {
    var pause = 16L
    while (stillCurrent()) {
        val sample = latest()
        if (sample != null) {
            val sampleSerial = serial(sample)
            if (sampleSerial >= wantedSerial && sampleSerial > lastAcceptedSerial && matches(sample)) {
                return sample
            }
        }
        delay(pause)
        pause = (pause * 2).coerceAtMost(32L)
    }
    return null
}
