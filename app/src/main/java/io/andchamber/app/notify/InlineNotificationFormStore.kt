package io.andchamber.app.notify

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One notification's staged inline-form answers. Internal on purpose: [InlineFormProjection] is the
 * only thing a renderer ever sees, and it carries no value from [values]. This type has no data-class
 * `toString`, so an accidental log line cannot print the answers either.
 */
internal class InlineNotificationFormDraft(
    val key: InlineFormKey,
    val token: String,
    var form: FormAnswer.Fields?,
    var status: InlineFormStatus,
    var touchedAt: Long,
) {
    var index: Int = 0

    /** The generation the next action must carry. Bumped on every accepted state change. */
    var generation: Long = 0

    /** A final Reply/Skip was accepted and still has to reach the server (or already failed). */
    var pendingSend: Boolean = false

    /** True only while the request is in flight; such a draft is never evicted. */
    @Volatile
    var sending: Boolean = false

    /** Raw-schema fingerprint the staged answers were collected under; empty until a form is known. */
    var schema: String = ""

    /** Exactly what the user entered, keyed by form field. Never trimmed. */
    val values: MutableMap<String, String> = HashMap()

    /** Fields the user explicitly skipped (optional only), so a default is not used for them. */
    val skipped: MutableSet<String> = HashSet()

    fun bump() {
        generation += 1
    }

    /** Debug-safe: the staged answers are deliberately absent. */
    override fun toString(): String =
        "InlineNotificationFormDraft(key=$key, token=$token, index=$index, status=$status, generation=$generation)"
}

/**
 * Process-wide storage for inline form drafts. Bounded (32 drafts) and idle-expiring (10 minutes), so a
 * long-running process cannot accumulate draft text. A draft that is sending is never chosen for
 * eviction, so capacity pressure cannot drop an in-flight answer and let a duplicate action send again;
 * when every slot is in flight the new draft is refused and the caller falls back to the app's renderer.
 *
 * [serialized] is the concurrency boundary: every begin/act for one notification id runs under that
 * id's own mutex, so two receivers or a duplicate broadcast cannot interleave a pending check with a
 * send on the same notification, while different notifications proceed in parallel. The store state
 * itself (capacity, TTL, the in-flight flag) stays under [lock], so concurrent forms cannot corrupt it.
 * The store is intentionally not persisted: after process loss an old notification step cannot pretend
 * to restore answers. The caller begins again from a fresh pending read, which starts at the first
 * field and never submits anything by itself.
 */
class InlineNotificationFormStore(
    private val capacity: Int = 32,
    private val ttlMillis: Long = 10 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val maxDrafts = capacity.coerceAtLeast(1)
    private val locks = Array(LOCK_STRIPES) { Mutex() }
    private val lock = Any()
    private val drafts = LinkedHashMap<Int, InlineNotificationFormDraft>(16, 0.75f, true)

    /**
     * Serializes begin/act for one notification id with a fixed stripe lock. Different ids run in
     * parallel unless their hash picks the same stripe; a collision only queues, and the table never
     * shrinks or swaps the lock for an id (no eviction gap).
     */
    internal suspend fun <T> serialized(
        notificationId: Int,
        block: suspend () -> T,
    ): T = lockFor(notificationId).withLock { block() }

    private fun lockFor(notificationId: Int): Mutex = locks[Math.floorMod(notificationId, LOCK_STRIPES)]

    /** The live draft for a notification id, or null when absent/expired. Key and token are checked by the caller. */
    internal fun find(notificationId: Int): InlineNotificationFormDraft? =
        synchronized(lock) {
            val draft = drafts[notificationId] ?: return@synchronized null
            if (expired(draft)) {
                drafts.remove(notificationId)
                return@synchronized null
            }
            draft
        }

    /**
     * Stores a fresh draft, replacing any draft of the same notification id (a new form on the same
     * notification overwrites the old one). Returns null when the store is full of in-flight drafts.
     */
    internal fun newDraft(
        key: InlineFormKey,
        token: String,
        form: FormAnswer.Fields?,
        status: InlineFormStatus,
        schema: String = "",
    ): InlineNotificationFormDraft? =
        synchronized(lock) {
            purgeExpired()
            val previous = drafts[key.notificationId]
            if (previous != null && previous.sending) return@synchronized null
            val replaced = previous != null
            if (!replaced && drafts.size >= maxDrafts) {
                val victim = drafts.entries.firstOrNull { !it.value.sending } ?: return@synchronized null
                drafts.remove(victim.key)
            }
            drafts.remove(key.notificationId)
            InlineNotificationFormDraft(key, token, form, status, now()).also {
                it.schema = schema
                drafts[key.notificationId] = it
            }
        }

    internal fun touch(draft: InlineNotificationFormDraft) {
        synchronized(lock) {
            draft.touchedAt = now()
        }
    }

    /** Removes [draft] only if it is still the entry stored for its notification. */
    internal fun remove(draft: InlineNotificationFormDraft) {
        synchronized(lock) {
            if (drafts[draft.key.notificationId] === draft) drafts.remove(draft.key.notificationId)
        }
    }

    internal fun size(): Int =
        synchronized(lock) {
            drafts.size
        }

    private fun purgeExpired() {
        drafts.entries.removeAll { expired(it.value) }
    }

    private fun expired(draft: InlineNotificationFormDraft): Boolean = !draft.sending && now() - draft.touchedAt > ttlMillis

    companion object {
        /** Fixed lock stripes: bounded memory, stable per id, hash collisions only queue. */
        private const val LOCK_STRIPES = 64

        /** The store every receiver in this process shares; tests inject their own instance. */
        val shared: InlineNotificationFormStore = InlineNotificationFormStore()
    }
}
