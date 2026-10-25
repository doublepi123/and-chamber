package io.andchamber.app.phone.android

import io.andchamber.app.phone.tool.PhonePendingRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one agent-proposed task the runtime's phone tool is waiting on.
 *
 * The integration only reads it to prefill the native screen (summary and a preselected package) and
 * takes it with [consume] when a person grants that task. Reading or consuming a proposal never
 * grants anything: it is untrusted model text that stays in native memory.
 */
interface PhoneProposalSource {
    val pending: StateFlow<PhonePendingRequest?>

    /** Dismisses exactly the revision the screen displayed; a stale revision changes nothing. */
    fun dismiss(revision: Long): Boolean

    /**
     * Takes exactly the displayed proposal and returns it, or null for a stale revision. Native only;
     * the returned session label is what a grant pins so a concurrent chat cannot claim the epoch.
     */
    fun consume(revision: Long): PhonePendingRequest?
}

/** For builds and tests without a phone tool: there is never a proposal. */
object NoPhoneProposals : PhoneProposalSource {
    override val pending: StateFlow<PhonePendingRequest?> = MutableStateFlow(null)

    override fun dismiss(revision: Long): Boolean = false

    override fun consume(revision: Long): PhonePendingRequest? = null
}

/**
 * Native-only routing publication for a freshly granted controller epoch.
 *
 * A grant that is not published may not be claimed by any chat, and the controller epoch is what the
 * tool checks. Publishing is routing, never authentication: it only decides which session is allowed
 * to act under that epoch. The person's confirmation is the grant itself; this call happens after it
 * and failure means the grant cannot be used, so the caller revokes instead of leaving a lease that
 * another chat could race to claim.
 */
fun interface PhoneGrantRoutePublisher {
    /**
     * Publishes [epoch] for [confirmedSessionLabel]: a label pins the consumed proposal's session,
     * null explicitly allows the first real action to claim it (a manual native task).
     * Returns false when no current launch can accept the route or the epoch is stale.
     */
    fun publishGrantRoute(epoch: Long, confirmedSessionLabel: String?): Boolean

    companion object {
        /** Fail-closed default: an unwired publisher refuses every route, so the caller revokes. */
        val Unavailable: PhoneGrantRoutePublisher = PhoneGrantRoutePublisher { _, _ -> false }
    }
}
