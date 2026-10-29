package io.andchamber.app.phone.standing

import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneConnectionState
import io.andchamber.app.phone.core.PhoneGrant
import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.core.PhoneState
import io.andchamber.app.phone.core.PhoneTargetPolicy
import io.andchamber.app.phone.core.PhoneTimeLimits

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

    /**
     * The native phone-control page is the route in front, and the requested target is AndChamber
     * itself. Bringing the host forward would show that page, which applies the host-return stop, so
     * the start is refused before any grant/lease. The person must leave the page (Home or chat).
     */
    PHONE_SCREEN_OPEN,

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
    /**
     * The explicit person-confirmed app list, or empty when [allApps] is true. Never both: an
     * all-apps scope carries no list, so it cannot be mistaken for a named subset.
     */
    val allowedPackages: List<String>,
    val allowedActions: Set<PhoneActionKind>,
    /** Absolute elapsedRealtime end of the standing window, or the explicit permanent sentinel. */
    val windowEndsMs: Long,
    /**
     * Upper bound for one task's lease: a finite value within the short lease range, or the explicit
     * [PhoneTimeLimits.PERMANENT] sentinel. A finite window still bounds a permanent per-task value.
     */
    val perTaskMs: Long,
    /** Optional native binding revision; it only invalidates stale native callbacks. */
    val taskRevision: Long? = null,
    /**
     * The person explicitly authorized every launchable app. The target policy then runs in
     * all-apps mode and the membership check is skipped; [allowedPackages] must be empty.
     */
    val allApps: Boolean = false,
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
        // An explicit scope is a non-empty deduplicated package list; an all-apps scope is exactly
        // the absence of that list. Either shape may never carry the other's fields.
        val packagesValid =
            if (scope.allApps) {
                scope.allowedPackages.isEmpty()
            } else {
                scope.allowedPackages.size in 1..MAX_PACKAGES &&
                    scope.allowedPackages.distinct().size == scope.allowedPackages.size &&
                    scope.allowedPackages.all { PACKAGE_PATTERN.matches(it) }
            }
        if (!validLabel(scope.runtimeId, MAX_RUNTIME_CHARS) ||
            !validLabel(scope.sessionLabel, MAX_SESSION_CHARS) ||
            !packagesValid ||
            scope.allowedActions.isEmpty() ||
            (!PhoneTimeLimits.isPermanent(scope.perTaskMs) &&
                scope.perTaskMs !in PhoneLimits.MIN_LEASE_MS..PhoneLimits.MAX_LEASE_MS) ||
            (scope.taskRevision != null && scope.taskRevision < 0L)
        ) {
            return PhoneStandingRefusal.INVALID
        }
        if ((!PhoneTimeLimits.isPermanent(scope.windowEndsMs) && scope.windowEndsMs <= now) ||
            perTaskTtl(scope, now) == null
        ) {
            return PhoneStandingRefusal.EXPIRED
        }
        // All-apps mode has no membership list: the target policy (same mode) is the only name gate.
        if (!scope.allApps && targetPackage !in scope.allowedPackages) {
            return PhoneStandingRefusal.TARGET_NOT_ALLOWED
        }
        if (targetPolicy.refusal(targetPackage, allApps = scope.allApps) != null) {
            return PhoneStandingRefusal.TARGET_NOT_ALLOWED
        }

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

    /**
     * Bounded per-task lease.
     *
     * The two native bounds combine as follows:
     * - both finite: `min(perTaskMs, remaining window)`, still within the short lease range;
     * - finite per-task + permanent window: [PhoneStandingScope.perTaskMs];
     * - permanent per-task + finite window: exactly the remaining window, a finite derived grant
     *   that may legitimately exceed [PhoneLimits.MAX_LEASE_MS] (for example four hours);
     * - both permanent: [PhoneTimeLimits.PERMANENT].
     *
     * null means the window is over or has less than the minimum usable lease left. A finite
     * deadline is never turned into the permanent sentinel: [PhoneTimeLimits.expiresAt] would
     * refuse an overflow instead.
     */
    fun perTaskTtl(
        scope: PhoneStandingScope,
        now: Long,
    ): Long? {
        val windowPermanent = PhoneTimeLimits.isPermanent(scope.windowEndsMs)
        if (!windowPermanent && scope.windowEndsMs <= now) return null
        val windowRemaining =
            if (windowPermanent) PhoneTimeLimits.PERMANENT else scope.windowEndsMs - now
        if (PhoneTimeLimits.isPermanent(scope.perTaskMs)) {
            return windowRemaining.takeIf { it >= PhoneLimits.MIN_LEASE_MS }
        }
        val ttl = minOf(scope.perTaskMs, windowRemaining)
        return ttl.takeIf { it >= PhoneLimits.MIN_LEASE_MS && it <= PhoneLimits.MAX_LEASE_MS }
    }

    private fun validLabel(
        value: String,
        max: Int,
    ): Boolean = value.length in 1..max && value.none(Char::isISOControl)
}
