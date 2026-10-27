package io.andchamber.app.phone.android

import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import kotlinx.coroutines.CancellationException

/**
 * One post-grant start transaction. It remembers only the epoch/key/lease this attempt created, so
 * a cancellation at the stop-entry wait or the first Observe can perform the exact rollback once
 * and then rethrow the original cancellation.
 *
 * The rollback callback is the integration's existing exact-owner rollback. This guard never grants,
 * retries, reports success or logs an exception; when the rollback itself fails, the original
 * cancellation still wins.
 */
internal class PhoneStandingStartCancelGuard(
    private val epoch: Long,
    private val rollback: (epoch: Long, key: PhoneTaskKey?, rememberedLease: PhoneLeaseFacts?) -> Unit,
) {
    private var key: PhoneTaskKey? = null
    private var rememberedLease: PhoneLeaseFacts? = null

    /** Records the key only after this attempt really published its task binding. */
    fun bind(key: PhoneTaskKey) {
        this.key = key
    }

    /** Records the exact lease this attempt set, so cleanup cannot clear a newer one. */
    fun rememberLease(lease: PhoneLeaseFacts?) {
        rememberedLease = lease
    }

    suspend fun <T> transaction(body: suspend () -> T): T =
        try {
            body()
        } catch (cancelled: CancellationException) {
            try {
                rollback(epoch, key, rememberedLease)
            } catch (_: Exception) {
                // A rollback failure must not replace the caller's cancellation.
            }
            // The caller's cancellation is the outcome; it never becomes success, a retry or a
            // different exception.
            throw cancelled
        }
}
