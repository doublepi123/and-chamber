package io.andchamber.app.phone.android

/**
 * The in-memory draft of one explicit pairing attempt.
 *
 * Main path: [begin] starts at [Stage.SEARCHING]. A local `_adb-tls-pairing._tcp` endpoint arrives from
 * bounded discovery and [acceptEndpoint] moves the draft to [Stage.AWAITING_CODE]; the notification
 * then asks only for the six-digit code. The person still types that code: this class never reads a
 * system property, host ADB state or a desktop key, and it never persists a port or code.
 *
 * Legacy/manual fallback: [beginManual] starts at [Stage.AWAITING_PORT] and keeps the old typed-port
 * reply. Nothing on the main path can reach that stage by default.
 *
 * [generation] makes stale notification replies harmless: a reply for an older generation is refused
 * instead of starting (or consuming) a newer attempt, and [ttlMs] clears a draft the person abandoned.
 * Losing the discovered endpoint sends the draft back to [Stage.SEARCHING], which closes the code gate.
 * Cancelling only drops this draft; it never claims that the device's system pairing window, or any
 * authorization it granted, was revoked.
 */
internal class PhonePairingSession(
    private val clock: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    enum class Stage { SEARCHING, AWAITING_PORT, AWAITING_CODE, PAIRING }

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

    sealed interface EndpointResult {
        data class Accepted(val draft: Draft) : EndpointResult

        data object Stale : EndpointResult

        data object Expired : EndpointResult

        data object InvalidPort : EndpointResult

        /** This draft is not searching for an endpoint (manual fallback or code/busy stage). */
        data object NotSearching : EndpointResult
    }

    sealed interface CodeResult {
        /** The one-shot code to pass to the driver. The receiver must clear [code] afterwards. */
        class Ready(val draft: Draft, val code: CharArray) : CodeResult

        data object Stale : CodeResult

        data object Expired : CodeResult

        data object InvalidCode : CodeResult

        /** No endpoint has arrived yet, or a pairing call is already running. */
        data object NotReady : CodeResult
    }

    private var generation = 0L
    private var draft: Draft? = null

    /** Main path: no port is expected; discovery must supply the local endpoint. */
    @Synchronized
    fun begin(host: String): Draft = beginAt(host, Stage.SEARCHING)

    /** Legacy/manual fallback: the person types the pairing port. */
    @Synchronized
    fun beginManual(host: String): Draft = beginAt(host, Stage.AWAITING_PORT)

    private fun beginAt(
        host: String,
        stage: Stage,
    ): Draft {
        val now = clock()
        generation += 1
        val next =
            Draft(
                generation = generation,
                host = host,
                stage = stage,
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

    /**
     * Takes the single current local endpoint from bounded discovery.
     *
     * Only [Stage.SEARCHING] accepts it. A later endpoint for an attempt already waiting for the code
     * must first be invalidated through [endpointLost], so a second candidate can never overwrite the
     * configured port silently.
     */
    @Synchronized
    fun acceptEndpoint(
        generation: Long,
        host: String,
        port: Int,
    ): EndpointResult {
        val now = clock()
        val live = liveDraft(now)
        if (live == null) return EndpointResult.Expired
        if (live.generation != generation) return EndpointResult.Stale
        if (live.stage != Stage.SEARCHING) return EndpointResult.NotSearching
        if (port !in 1..65_535) return EndpointResult.InvalidPort
        val next =
            live.copy(
                host = host,
                stage = Stage.AWAITING_CODE,
                pairingPort = port,
                updatedAtMs = now,
            )
        draft = next
        return EndpointResult.Accepted(next)
    }

    /** The discovered endpoint or its service advertisement went away: close the code gate. */
    @Synchronized
    fun endpointLost(generation: Long): Boolean {
        val now = clock()
        val live = liveDraft(now) ?: return false
        if (live.generation != generation) return false
        if (live.stage != Stage.AWAITING_CODE) return false
        draft = live.copy(stage = Stage.SEARCHING, pairingPort = null, updatedAtMs = now)
        return true
    }

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

    /** Puts a manual draft back to the typed-port step after a failed exchange. */
    @Synchronized
    fun retryPort(generation: Long): Draft? {
        val live = liveDraft(clock()) ?: return null
        if (live.generation != generation) return null
        val next = live.copy(stage = Stage.AWAITING_PORT, pairingPort = null, updatedAtMs = clock())
        draft = next
        return next
    }

    /** Native retry of the discovery path; a new endpoint must arrive again. */
    @Synchronized
    fun retrySearch(generation: Long): Draft? {
        val live = liveDraft(clock()) ?: return null
        if (live.generation != generation) return null
        val next = live.copy(stage = Stage.SEARCHING, pairingPort = null, updatedAtMs = clock())
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
