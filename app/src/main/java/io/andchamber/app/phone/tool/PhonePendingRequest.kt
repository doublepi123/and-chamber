package io.andchamber.app.phone.tool

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one agent-proposed task that waits for native confirmation.
 *
 * [summary] and [packageName] are untrusted model text. They live only in this in-memory store and
 * are published to native code; they are never logged, never put into an error message, never
 * broadcast by `status`, and are not put into a notification by this layer. [revision] exists so a
 * native screen can consume or dismiss exactly the proposal it displayed, not a newer replacement.
 */
data class PhonePendingRequest(
    val revision: Long,
    val sessionLabel: String,
    val summary: String,
    val packageName: String?,
    val updatedAtMs: Long,
) {
    /** Never print untrusted task text or a chosen package into a log or crash report. */
    override fun toString(): String =
        "PhonePendingRequest(revision=$revision, sessionLabel=$sessionLabel, summary=<redacted>, " +
            "packageName=<redacted>, updatedAtMs=$updatedAtMs)"
}

/** Outcome of [PhonePendingRequestStore.propose]. */
enum class PhonePendingProposal {
    CREATED,
    UPDATED,

    /** Another chat session owns the pending proposal; it cannot be overwritten. */
    SESSION_BUSY,
}

/**
 * A single bounded pending proposal owned by the runtime binding.
 *
 * There is no queue: at most one proposal exists. A second session cannot replace it, and the same
 * session may explicitly update it while it is still pending. Native UI is the only caller of
 * [consume] and [dismiss]; the agent only ever calls [propose] through the tool server. Neither
 * method grants, enables, pairs or starts anything.
 */
class PhonePendingRequestStore(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()
    private var revision = 0L
    private val mutablePending = MutableStateFlow<PhonePendingRequest?>(null)

    /** The current proposal, or null. Native-only detail (summary lives here, not in status). */
    val pending: StateFlow<PhonePendingRequest?> = mutablePending.asStateFlow()

    /**
     * Creates the proposal or explicitly updates this session's still-pending proposal. A proposal
     * owned by another session is refused; nothing is overwritten.
     */
    fun propose(
        sessionLabel: String,
        summary: String,
        packageName: String?,
    ): PhonePendingProposal {
        require(summary.isNotBlank()) { "summary must not be blank" }
        require(summary.length <= PhoneToolProtocol.MAX_SUMMARY_CHARS) { "summary is too long" }
        require(summary.none(Char::isISOControl)) { "summary must not contain control characters" }
        require(packageName == null || (packageName.isNotBlank() && packageName.length <= PhoneToolProtocol.MAX_PACKAGE_CHARS)) {
            "package name is not bounded"
        }
        synchronized(lock) {
            val current = mutablePending.value
            if (current != null && current.sessionLabel != sessionLabel) {
                return PhonePendingProposal.SESSION_BUSY
            }
            revision += 1
            mutablePending.value = PhonePendingRequest(
                revision = revision,
                sessionLabel = sessionLabel,
                summary = summary,
                packageName = packageName,
                updatedAtMs = clock(),
            )
            return if (current == null) PhonePendingProposal.CREATED else PhonePendingProposal.UPDATED
        }
    }

    /**
     * Native only. Takes exactly the proposal that was displayed and clears it. A stale [revision]
     * returns null and leaves the newer proposal in place, so a screen can never start a task it did
     * not show.
     */
    fun consume(revision: Long): PhonePendingRequest? =
        synchronized(lock) {
            val current = mutablePending.value ?: return@synchronized null
            if (current.revision != revision) return@synchronized null
            mutablePending.value = null
            current
        }

    /** Native only. Discards exactly the displayed proposal; a stale revision changes nothing. */
    fun dismiss(revision: Long): Boolean =
        synchronized(lock) {
            val current = mutablePending.value ?: return@synchronized false
            if (current.revision != revision) return@synchronized false
            mutablePending.value = null
            true
        }

    /** Native/runtime owner only. Drops any proposal, e.g. when this launch stops. */
    fun clear() {
        synchronized(lock) { mutablePending.value = null }
    }

    fun snapshot(): PhonePendingRequest? = mutablePending.value
}
