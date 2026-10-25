package io.andchamber.app.phone.android

import io.andchamber.app.phone.tool.PhonePendingRequest
import io.andchamber.app.ui.phone.PhoneLastAction
import java.util.UUID

/**
 * A random identity for this process, used in every PendingIntent data URI.
 *
 * Request codes alone can collide (a hashed 64-bit epoch, restarts, wrap-around), and an Intent with
 * the same component/action/extras can be updated in place by `FLAG_UPDATE_CURRENT`. A per-process
 * nonce in the URI makes every attempt a distinct PendingIntent, and a receiver that does not see the
 * current nonce knows the intent came from an earlier process and ignores it.
 */
internal object PhoneProcessNonce {
    val value: String = UUID.randomUUID().toString().replace("-", "")

    /** True only for the exact nonce this process minted. */
    fun matches(candidate: String?): Boolean = candidate?.isNotEmpty() == true && candidate == value
}

/**
 * Data URI for a phone-control PendingIntent. [kind] is `pair` or `service`, [action] is a fixed
 * lowercase action name (never user text), [value] is the draft generation or lease epoch.
 */
internal fun phoneIntentData(
    kind: String,
    action: String,
    value: Long,
    nonce: String = PhoneProcessNonce.value,
): String = "andchamber-phone://$kind/$action/$value/$nonce"

/** The nonce part of [phoneIntentData], or null when the URI is not one of ours. */
internal fun phoneIntentNonce(data: String?): String? {
    val value = data ?: return null
    if (!value.startsWith("andchamber-phone://")) return null
    return value.substringAfterLast('/').takeIf { it.length == NONCE_CHARS }
}

private const val NONCE_CHARS = 32

/**
 * Whether a pairing result may still change the UI.
 *
 * A result that arrives after cancel, disable, or a newer attempt must not post "paired", must not
 * cancel the newer notification and must not touch state. The operation id is bumped on every
 * cancel/disable/begin, and the draft generation must still be the current one.
 */
internal fun pairingResultIsCurrent(
    operation: Long,
    activeOperation: Long?,
    draftGeneration: Long,
    currentGeneration: Long?,
    enabled: Boolean,
): Boolean = enabled && activeOperation == operation && currentGeneration == draftGeneration

/** Fixed technical token: the task the person confirmed changed while the stop entry came up. */
internal const val GRANT_TOKEN_CONFIRMATION_CHANGED = "grant_confirmation_changed"

/** Fixed technical token: the controller epoch moved before the route could be published. */
internal const val GRANT_TOKEN_EPOCH_CHANGED = "grant_epoch_changed"

/**
 * What the confirmed grant may be routed to.
 *
 * The revision is frozen at the moment the person confirms. Waiting for the foreground service must
 * never change the confirmed object: a proposal that appeared meanwhile is kept for the person, and a
 * proposal that changed is not silently downgraded to the manual (first-action-claim) route.
 */
internal sealed interface PhoneConfirmedProposal {
    /** The frozen proposal is still current and was taken; pin exactly its session. */
    data class Pinned(val revision: Long, val sessionLabel: String) : PhoneConfirmedProposal

    /** No proposal at confirmation and none appeared since: an explicit manual task. */
    data object Manual : PhoneConfirmedProposal

    /** The confirmed proposal is no longer current; the grant must fail and keep the newer one. */
    data object Changed : PhoneConfirmedProposal
}

internal fun resolveConfirmedProposal(
    frozenRevision: Long?,
    currentRevision: Long?,
    consume: (Long) -> PhonePendingRequest?,
): PhoneConfirmedProposal {
    if (frozenRevision == null) {
        // A manual confirmation: a proposal that appeared while the stop entry came up is not bound
        // to this task and must be left for the person to confirm separately.
        return if (currentRevision == null) PhoneConfirmedProposal.Manual else PhoneConfirmedProposal.Changed
    }
    if (currentRevision != frozenRevision) return PhoneConfirmedProposal.Changed
    val consumed = consume(frozenRevision) ?: return PhoneConfirmedProposal.Changed
    if (consumed.revision != frozenRevision) return PhoneConfirmedProposal.Changed
    return PhoneConfirmedProposal.Pinned(consumed.revision, consumed.sessionLabel)
}

/** What a confirmed grant may do after the foreground stop entry came up. */
internal sealed interface PhoneGrantRouting {
    /** The frozen object is still current and its route was published; [confirmedSession] is bound. */
    data class Ready(val confirmedSession: String?) : PhoneGrantRouting

    /** The stop entry could not be proven; the grant must be revoked. */
    data object ServiceUnavailable : PhoneGrantRouting

    /** The task the person confirmed changed while waiting; the newer proposal is left untouched. */
    data object ConfirmationChanged : PhoneGrantRouting

    /** The controller epoch moved while waiting; a newer grant must not be touched. */
    data object EpochChanged : PhoneGrantRouting

    /** No chat could be routed to this epoch; the grant must be revoked. */
    data object PublishFailed : PhoneGrantRouting
}

/**
 * The exact order a confirmed grant follows after the controller admitted its epoch: prove the
 * foreground stop entry, re-check the frozen epoch and revision, then publish the route.
 *
 * Suspending on [awaitStopEntry] lets a test hold this open and change the proposal underneath: the
 * confirmed object is the one frozen at the click, so a proposal that appears or changes meanwhile
 * never gets bound, and [publish] is never called with a stale or different session.
 */
internal suspend fun routeAfterStopEntry(
    epoch: Long,
    frozenRevision: Long?,
    frozenResumeSession: String?,
    awaitStopEntry: suspend () -> Boolean,
    currentEpoch: () -> Long?,
    currentRevision: () -> Long?,
    consume: (Long) -> PhonePendingRequest?,
    publish: (Long, String?) -> Boolean,
): PhoneGrantRouting {
    if (!awaitStopEntry()) return PhoneGrantRouting.ServiceUnavailable
    if (currentEpoch() != epoch) return PhoneGrantRouting.EpochChanged
    val confirmedSession =
        when (val resolved = resolveConfirmedProposal(frozenRevision, currentRevision()) { consume(it) }) {
            is PhoneConfirmedProposal.Pinned -> resolved.sessionLabel
            PhoneConfirmedProposal.Manual -> frozenResumeSession
            PhoneConfirmedProposal.Changed -> return PhoneGrantRouting.ConfirmationChanged
        }
    // Re-check right before publishing: the epoch may have moved during the revision resolution.
    if (currentEpoch() != epoch) return PhoneGrantRouting.EpochChanged
    return if (publish(epoch, confirmedSession)) {
        PhoneGrantRouting.Ready(confirmedSession)
    } else {
        PhoneGrantRouting.PublishFailed
    }
}

/**
 * A halt never upgrades "the last action may still apply" to "nothing happened" just because the
 * controller settled or revoked the lease. A person's acknowledgement survives the next halt.
 */
internal fun latchedHaltLastAction(
    existing: PhoneLastAction?,
    actionInFlight: Boolean,
    derived: PhoneLastAction,
    acknowledged: Boolean,
): PhoneLastAction =
    when {
        actionInFlight -> PhoneLastAction.MayStillApply
        existing == PhoneLastAction.MayStillApply -> PhoneLastAction.MayStillApply
        existing == PhoneLastAction.UserChecked && acknowledged -> PhoneLastAction.UserChecked
        else -> derived
    }

/**
 * "I'll take over" is a stop, not a pause: a paused lease still allows Observe/Capture, so the whole
 * lease is revoked before the confirmed app is brought forward. A remembered task without a live
 * grant still opens the app; it never creates a new grant.
 */
internal data class PhoneTakeoverPlan(
    val revokeGrant: Boolean,
    val launchTarget: String?,
)

internal fun takeoverPlan(
    hasGrant: Boolean,
    rememberedTarget: String?,
): PhoneTakeoverPlan =
    PhoneTakeoverPlan(
        revokeGrant = hasGrant,
        launchTarget = rememberedTarget,
    )

/** Returning to the host drops the lease, including a paused one; pause is not an exemption. */
internal fun hostReturnDropsLease(
    hasGrant: Boolean,
    paused: Boolean,
): Boolean = hasGrant

/** The POST_NOTIFICATION app-op mode reported for this app's own UID. */
internal enum class PhonePostNotificationMode {
    ALLOWED,
    DEFAULT,
    IGNORED,
    ERRORED,

    /** Allowed only while this app is in the foreground: not usable as a cross-app stop entry. */
    FOREGROUND,
    UNKNOWN,
}

/** What the notification gate concluded. Anything but [AVAILABLE] blocks a new grant. */
internal enum class PhoneNotificationsGate {
    AVAILABLE,
    NOTIFICATIONS_DISABLED,
    CHANNEL_DISABLED,
    UNKNOWN,
}

/**
 * Whether this app-op mode permits posting that a person can see while another app is in front.
 *
 * `MODE_ALLOWED` is the only unconditional yes. `MODE_DEFAULT` means "follow the runtime permission",
 * so it is usable only when that permission is really granted. `MODE_IGNORED`, `MODE_ERRORED` and
 * `MODE_FOREGROUND` are not cross-app availability (the last one only works while this app is in
 * front, which is exactly when the stop entry is not needed).
 */
internal fun postNotificationAppOpAllows(
    mode: PhonePostNotificationMode,
    permissionGranted: Boolean,
): Boolean =
    when (mode) {
        PhonePostNotificationMode.ALLOWED -> true
        PhonePostNotificationMode.DEFAULT -> permissionGranted
        else -> false
    }

/**
 * Combines the platform-wide switch, the runtime permission, this app's POST_NOTIFICATION app-op and
 * the control channel. An unreadable app-op is [PhoneNotificationsGate.UNKNOWN], which the caller must
 * treat as "cannot guarantee a stop entry" and therefore fail closed; it is never assumed allowed.
 *
 * This decides whether the app *may* post. It is not evidence that a notification was shown or read.
 */
internal fun phoneNotificationsGate(
    systemEnabled: Boolean,
    permissionGranted: Boolean,
    appOpMode: PhonePostNotificationMode?,
    channelAvailable: Boolean,
): PhoneNotificationsGate =
    when {
        !systemEnabled -> PhoneNotificationsGate.NOTIFICATIONS_DISABLED
        appOpMode == null -> PhoneNotificationsGate.UNKNOWN
        !permissionGranted -> PhoneNotificationsGate.NOTIFICATIONS_DISABLED
        !postNotificationAppOpAllows(appOpMode, permissionGranted) -> PhoneNotificationsGate.NOTIFICATIONS_DISABLED
        !channelAvailable -> PhoneNotificationsGate.CHANNEL_DISABLED
        else -> PhoneNotificationsGate.AVAILABLE
    }

/**
 * Whether the notification's Return action may still stop the lease.
 *
 * Both the process nonce and the live epoch must match. A stale action (older process, older grant,
 * or a lease that is already gone) must finish without touching state and without opening AndChamber,
 * so it can never revoke a newer grant.
 */
internal fun returnActionAccepted(
    nonceMatches: Boolean,
    actionEpoch: Long,
    liveEpoch: Long?,
): Boolean = nonceMatches && liveEpoch != null && actionEpoch == liveEpoch

/**
 * Whether a service action belongs to the live lease. [ACTION_START] uses the same check, so a stale
 * intent cannot resurrect a foreground service for a grant that no longer exists.
 */
internal fun serviceActionIsCurrent(
    actionEpoch: Long,
    liveEpoch: Long?,
): Boolean = liveEpoch != null && actionEpoch == liveEpoch

/**
 * The lease epoch a service instance is allowed to serve. A newer grant means this instance must stop
 * without revoking the newer lease.
 */
internal fun serviceShouldStop(
    servedEpoch: Long,
    liveEpoch: Long?,
): Boolean = liveEpoch == null || liveEpoch != servedEpoch
