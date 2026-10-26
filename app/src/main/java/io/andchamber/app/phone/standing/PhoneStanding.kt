package io.andchamber.app.phone.standing

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneConnectionState
import io.andchamber.app.phone.core.PhoneGrant
import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.core.PhoneState
import io.andchamber.app.phone.core.PhoneTargetPolicy

/**
 * Why a chat request cannot use the standing scope. Fixed values only; no labels, packages, page
 * text or model text is carried here.
 */
enum class PhoneStandingRefusal {
    NOT_CONFIGURED,
    INVALID,
    EXPIRED,
    DISABLED,
    NOT_CONNECTED,
    RUNTIME_CHANGED,
    PAUSED,
    UNKNOWN_OUTCOME,
    /** A native confirmation is needed (need_user, takeover, lock, connection loss, runtime change). */
    STANDING_BLOCKED,
    TARGET_NOT_ALLOWED,
    ACTION_NOT_ALLOWED,

    /** Another chat session owns the current standing task/binding. Routing is not authentication. */
    SESSION_MISMATCH,

    /** A live or retained task already exists; it must be continued/ended natively, not replaced. */
    TASK_ACTIVE,

    /** The native start failed before a usable task existed; any lease it created was rolled back. */
    START_FAILED,
}

/**
 * Native-issued configure-once scope.
 *
 * It is created by the native phone UI (or the native binding) after the person confirms the app
 * set, action set, window and per-task duration. The model/tool surface never constructs one: the
 * tool `request` only reaches a native [PhoneTaskStarter]. [sessionLabel] routes the chat and is not
 * authentication. Each request inside the window gets its own task epoch and a bounded lease no
 * longer than [perTaskMs] or the remaining window.
 */
data class PhoneStandingScope internal constructor(
    val runtimeId: String,
    val sessionLabel: String,
    val allowedPackages: List<String>,
    val allowedActions: Set<PhoneActionKind>,
    /** Absolute elapsedRealtime end of the standing window. */
    val windowEndsMs: Long,
    /** Upper bound for one task's lease, within the standing window. */
    val perTaskMs: Long,
    /** Optional native binding revision; it only invalidates stale native callbacks. */
    val taskRevision: Long? = null,
)

/**
 * Native-only confirmation that the person resumed the standing scope after a stop. It is issued by
 * the native UI after an explicit person action; the model cannot create one. Session/revision are
 * routing/version fields, never authentication.
 */
data class PhoneStandingResume internal constructor(
    val runtimeId: String,
    val sessionLabel: String,
    val taskRevision: Long? = null,
)

sealed interface PhoneStandingGrantResult {
    data class Granted(val grant: PhoneGrant) : PhoneStandingGrantResult

    data class Refused(val refusal: PhoneStandingRefusal) : PhoneStandingGrantResult
}

/**
 * Pure admission for a chat request that uses the standing scope. The controller re-checks the same
 * facts under its lock before it creates the grant; this object performs no I/O and no Android work.
 */
object PhoneStandingPolicy {
    const val MAX_PACKAGES: Int = 16
    const val MAX_RUNTIME_CHARS: Int = 128
    const val MAX_SESSION_CHARS: Int = 128

    private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Static shape and window/per-task validation. */
    fun refusal(
        scope: PhoneStandingScope,
        targetPackage: String,
        now: Long,
        state: PhoneState,
        targetPolicy: PhoneTargetPolicy,
    ): PhoneStandingRefusal? {
        if (!validLabel(scope.runtimeId, MAX_RUNTIME_CHARS) ||
            !validLabel(scope.sessionLabel, MAX_SESSION_CHARS) ||
            scope.allowedPackages.size !in 1..MAX_PACKAGES ||
            scope.allowedPackages.distinct().size != scope.allowedPackages.size ||
            scope.allowedPackages.any { !PACKAGE_PATTERN.matches(it) } ||
            scope.allowedActions.isEmpty() ||
            scope.perTaskMs !in PhoneLimits.MIN_LEASE_MS..PhoneLimits.MAX_LEASE_MS ||
            (scope.taskRevision != null && scope.taskRevision < 0L)
        ) {
            return PhoneStandingRefusal.INVALID
        }
        if (scope.windowEndsMs <= now || perTaskTtl(scope, now) == null) {
            return PhoneStandingRefusal.EXPIRED
        }
        if (targetPackage !in scope.allowedPackages) return PhoneStandingRefusal.TARGET_NOT_ALLOWED
        if (targetPolicy.refusal(targetPackage) != null) return PhoneStandingRefusal.TARGET_NOT_ALLOWED

        if (!state.enabled) return PhoneStandingRefusal.DISABLED
        if (state.connection != PhoneConnectionState.CONNECTED) return PhoneStandingRefusal.NOT_CONNECTED
        if (state.runtimeId == null || state.runtimeId != scope.runtimeId) {
            return PhoneStandingRefusal.RUNTIME_CHANGED
        }
        if (state.standingBlocked) return PhoneStandingRefusal.STANDING_BLOCKED
        if (state.blocked != null) return PhoneStandingRefusal.UNKNOWN_OUTCOME
        if (state.paused) return PhoneStandingRefusal.PAUSED
        return null
    }

    /** Bounded per-task lease: at most the scope's per-task duration and the remaining window. */
    fun perTaskTtl(
        scope: PhoneStandingScope,
        now: Long,
    ): Long? {
        if (scope.windowEndsMs <= now) return null
        val ttl = minOf(scope.perTaskMs, scope.windowEndsMs - now)
        return ttl.takeIf { it >= PhoneLimits.MIN_LEASE_MS && it <= PhoneLimits.MAX_LEASE_MS }
    }

    private fun validLabel(
        value: String,
        max: Int,
    ): Boolean = value.length in 1..max && value.none(Char::isISOControl)
}
