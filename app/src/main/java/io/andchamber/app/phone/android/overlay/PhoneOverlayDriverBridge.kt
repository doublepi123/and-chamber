package io.andchamber.app.phone.android.overlay

import io.andchamber.app.phone.core.PhoneRect
import io.andchamber.app.phone.overlay.safety.OverlayExpectation
import io.andchamber.app.phone.overlay.safety.OverlayPhase
import io.andchamber.app.phone.overlay.safety.PhoneOverlayBarrier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Driver-facing overlay barrier for the production phone-control engine.
 *
 * The engine is created lazily on IO. This bridge always exists, but every getter defers to the
 * already-built native coordinator: [overlayRequired] is the native mode gate (Overlay selected, or
 * this manager still owns a surface), [manager] is the one manager surface, and the authority is a
 * thread-safe native snapshot. No model input and no Compose/View construction happens here.
 *
 * [required] is dynamic: an explicitly notification-only mode with no own surface returns false and
 * keeps the original driver path. A selected Overlay whose surface is lost still returns true, so the
 * driver refuses instead of silently reading through a window it cannot protect.
 *
 * [onRefusedWhileRequired] freezes the lease epoch and display owner captured at operation entry,
 * posts to the main thread and only reports a safety loss if that same epoch/owner is still current.
 * A failure of an old operation can therefore never revoke a newer grant. The callback only asks the
 * native side to revoke; it never claims that an in-flight action was rolled back.
 *
 * [makeWay] is the driver's bounded occlusion escape: it delegates to the already-built coordinator,
 * which may only move its own capsule for the current live task. A throw or a missing coordinator is
 * a `false` (the driver then refuses the obstructed action), never an exception into the driver.
 */
internal class PhoneOverlayDriverBridge(
    private val overlayRequired: () -> Boolean,
    private val manager: () -> PhoneOverlayBarrier?,
    private val authorityEpoch: () -> Long?,
    private val displayOwner: () -> String?,
    private val onSafetyLost: (epoch: Long, owner: String?) -> Unit,
    /** Native make-way request; never grants, renews, resumes or revokes. */
    makeWay: suspend (List<PhoneRect>, PhoneRect, Long) -> Boolean = { _, _, _ -> false },
    /** Live task target for the target-hides-overlay fallback; null when no live task owns it. */
    liveTaskTarget: () -> String? = { null },
    /** Verified notification stop entry for the live task; false keeps the driver fail-closed. */
    notificationFallback: () -> Boolean = { false },
    /** A post, not an inline call: the failed driver operation and the controller result settle first. */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : PhoneOverlayBarrier {
    private val requestMakeWay = makeWay
    private val readLiveTaskTarget = liveTaskTarget
    private val readNotificationFallback = notificationFallback

    /** Immutable authority captured before one driver operation. */
    data class OperationGuard(
        val epoch: Long?,
        val owner: String?,
    )

    /** Null on the original no-overlay path; otherwise a frozen owner snapshot for this operation. */
    fun beginOperation(deadlineMs: Long): OperationGuard? =
        if (!required(deadlineMs)) null else OperationGuard(authorityEpoch(), displayOwner())

    override fun required(deadlineMs: Long): Boolean =
        try {
            overlayRequired()
        } catch (_: Exception) {
            // A throwing mode gate is overlay-required, exactly like the driver's own fallback.
            true
        }

    override fun expectation(deadlineMs: Long): OverlayExpectation {
        val surface =
            manager() ?: throw IllegalStateException("overlay manager is not available")
        return surface.expectation(deadlineMs)
    }

    /** Read-only live task target; any failure is "no target", which keeps the fallback closed. */
    override fun liveTargetPackage(): String? =
        try {
            readLiveTaskTarget()
        } catch (_: Exception) {
            null
        }

    /** Read-only stop-entry fact; any failure keeps the driver on the overlay path. */
    override fun notificationFallbackAllowed(): Boolean =
        try {
            readNotificationFallback()
        } catch (_: Exception) {
            false
        }

    override suspend fun <T> withContentHidden(
        owner: String,
        generation: Long,
        revision: Long,
        phase: OverlayPhase,
        deadlineMs: Long,
        block: suspend () -> T,
    ): T {
        val surface =
            manager() ?: throw IllegalStateException("overlay manager is not available")
        return surface.withContentHidden(owner, generation, revision, phase, deadlineMs, block)
    }

    /** Native-only capsule move; any failure is a plain `false`, not a safety loss. */
    override suspend fun makeWay(
        avoid: List<PhoneRect>,
        currentFrame: PhoneRect,
        deadlineMs: Long,
    ): Boolean =
        try {
            requestMakeWay(avoid, currentFrame, deadlineMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    /**
     * Called after a POLICY_DENIED from an overlay-required operation (observe/capture/dispatch).
     * The callback is posted to the main thread and only reported if the same epoch and display owner
     * are still current. It never retries, never restores a lease and never claims a rollback. Being a
     * post, it also survives cancellation of the failed operation's coroutine.
     */
    fun onRefusedWhileRequired(guard: OperationGuard) {
        val epoch = guard.epoch ?: return
        scope.launch {
            if (authorityEpoch() != epoch || displayOwner() != guard.owner) return@launch
            try {
                onSafetyLost(epoch, guard.owner)
            } catch (_: Exception) {
                // A failed revoke request is not evidence that the lease stopped; the next operation
                // fails closed again and the native screen still shows the retained task.
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
}
