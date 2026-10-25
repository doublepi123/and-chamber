package io.andchamber.app.phone.android

/**
 * The in-memory draft of one notification-based pairing attempt.
 *
 * Two RemoteInput replies arrive in order: the transient pairing port first, then the six-digit code.
 * Nothing is persisted: no port, no code, no host. The code is handed to the driver as a `CharArray`
 * that the caller clears, and it never appears in `toString`.
 *
 * [generation] makes stale notification replies harmless: a reply for an older generation is refused
 * instead of starting (or consuming) a newer attempt, and [ttlMs] clears a draft the person abandoned.
 * Cancelling only drops this draft; it never claims that the device's system pairing window, or any
 * authorization it granted, was revoked.
 */
internal class PhonePairingSession(
    private val clock: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    enum class Stage { AWAITING_PORT, AWAITING_CODE, PAIRING }

    data class Draft(
        val generation: Long,
        val host: String,
        val stage: Stage,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val pairingPort: Int?,
    )

    sealed interface PortResult {
        data class Accepted(val draft: Draft) : PortResult

        data object Stale : PortResult

        data object Expired : PortResult

        data object InvalidPort : PortResult
    }

    sealed interface CodeResult {
        /** The one-shot code to pass to the driver. The receiver must clear [code] afterwards. */
        class Ready(val draft: Draft, val code: CharArray) : CodeResult

        data object Stale : CodeResult

        data object Expired : CodeResult

        data object InvalidCode : CodeResult

        /** The port has not been entered yet, or a pairing call is already running. */
        data object NotReady : CodeResult
    }

    private var generation = 0L
    private var draft: Draft? = null

    @Synchronized
    fun begin(host: String): Draft {
        val now = clock()
        generation += 1
        val next =
            Draft(
                generation = generation,
                host = host,
                stage = Stage.AWAITING_PORT,
                createdAtMs = now,
                updatedAtMs = now,
                pairingPort = null,
            )
        draft = next
        return next
    }

    /** The live draft, or null once expired. Does not resurrect an expired attempt. */
    @Synchronized
    fun current(): Draft? = liveDraft(clock())

    @Synchronized
    fun acceptPort(
        generation: Long,
        text: String,
    ): PortResult {
        val now = clock()
        val live = liveDraft(now)
        if (live == null) return PortResult.Expired
        if (live.generation != generation) return PortResult.Stale
        if (live.stage != Stage.AWAITING_PORT) return PortResult.Stale
        val port = parsePort(text) ?: return PortResult.InvalidPort
        val next = live.copy(stage = Stage.AWAITING_CODE, pairingPort = port, updatedAtMs = now)
        draft = next
        return PortResult.Accepted(next)
    }

    @Synchronized
    fun acceptCode(
        generation: Long,
        text: String,
    ): CodeResult {
        val now = clock()
        val live = liveDraft(now)
        if (live == null) return CodeResult.Expired
        if (live.generation != generation) return CodeResult.Stale
        if (live.stage != Stage.AWAITING_CODE) return CodeResult.NotReady
        val code = sanitizePairingCode(text) ?: return CodeResult.InvalidCode
        draft = live.copy(stage = Stage.PAIRING, updatedAtMs = now)
        return CodeResult.Ready(live.copy(stage = Stage.PAIRING, updatedAtMs = now), code)
    }

    /** Puts the draft back to the port step after a failed exchange, keeping the generation. */
    @Synchronized
    fun retryPort(generation: Long): Draft? {
        val live = liveDraft(clock()) ?: return null
        if (live.generation != generation) return null
        val next = live.copy(stage = Stage.AWAITING_PORT, pairingPort = null, updatedAtMs = clock())
        draft = next
        return next
    }

    @Synchronized
    fun finish(generation: Long): Boolean {
        val live = draft ?: return false
        if (live.generation != generation) return false
        draft = null
        return true
    }

    @Synchronized
    fun cancel() {
        draft = null
    }

    /** Clears and reports an expired draft. Returns the generation that expired, or null. */
    @Synchronized
    fun expireIfNeeded(): Long? {
        val live = draft ?: return null
        val now = clock()
        if (now - live.createdAtMs <= ttlMs) return null
        draft = null
        return live.generation
    }

    private fun liveDraft(now: Long): Draft? {
        val live = draft ?: return null
        if (now - live.createdAtMs > ttlMs) {
            draft = null
            return null
        }
        return live
    }

    companion object {
        /** Long enough to open the system dialog and read the code; short enough to go stale. */
        const val DEFAULT_TTL_MS: Long = 5 * 60_000L

        const val CODE_LENGTH = 6

        fun parsePort(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..65_535 }

        /** Digits only, exactly six. Returns null for anything else; the caller shows a fixed error. */
        fun sanitizePairingCode(text: String): CharArray? {
            val digits = text.filter(Char::isDigit)
            if (digits.length != CODE_LENGTH) return null
            return digits.toCharArray()
        }
    }
}

/** Fixed local outcome of a pairing attempt. No device text, host or port is carried. */
internal enum class PhonePairingOutcome {
    PAIRED,
    CODE_REJECTED,
    PORT_UNREACHABLE,
    POLICY_REFUSED,
    CANCELLED,
    TIMEOUT,
    UNAVAILABLE,
}
