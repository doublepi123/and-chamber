package io.andchamber.app.ui.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** A different query creates a new blank document; a fragment alone is only same-document navigation. */
internal fun gatewayHandoffBlank(ticket: Long): String = "about:blank?andchamber-gateway=$ticket"

internal fun isGatewayHandoffBlank(url: String?): Boolean =
    url?.matches(Regex("about:blank\\?andchamber-gateway=[1-9][0-9]*")) == true

/**
 * Correlates main-frame errors with the latest issued/started navigation, not a previous document.
 * WebResourceRequest has no loader id: two requests for exactly the same URL remain indistinguishable.
 */
internal class MainFrameErrorGuard {
    private var owner: Any? = null
    private var generation = -1
    private var expectedUrl: String? = null

    fun expect(owner: Any, generation: Int, url: String) {
        this.owner = owner
        this.generation = generation
        expectedUrl = url
    }

    fun started(owner: Any, generation: Int, url: String?, visibleUrl: String?) {
        // Chromium error documents are not a replacement target navigation. In particular, their
        // history/start callbacks can arrive while the failed HTTP request is still being reported.
        if (url == null || (!url.startsWith("https://", true) && !url.startsWith("http://", true))) return
        if (this.owner !== owner || this.generation != generation) return
        // The issued target need not commit to fail. A different redirect/history URL must match
        // the view before it can replace that target; internal error documents never replace it.
        if (url != expectedUrl && url != visibleUrl) return
        expectedUrl = url
    }

    fun accepts(owner: Any, generation: Int, url: String, quarantined: Boolean): Boolean =
        !quarantined && !isGatewayHandoffBlank(url) && this.owner === owner && this.generation == generation &&
            url == expectedUrl
}

/** Main-thread-owned ticket state; no Android APIs or polling. Failed tickets stay quarantined. */
internal class GatewayHandoff(private val timeoutMillis: Long = 5_000) {
    private class Ticket(val id: Long, val owner: Any, val generation: Int) {
        val barrier = CompletableDeferred<Boolean>()
        var valid = true
        var blankStarted = false
    }

    private var sequence = 0L
    private var current: Ticket? = null
    val ticket: Long get() = current?.id ?: 0L
    val blocksDocuments: Boolean get() = current != null

    fun begin(owner: Any, generation: Int): Long {
        current?.takeIf { it.valid && it.owner === owner && it.generation == generation }?.let { return it.id }
        current?.let { cancel(it.id) }
        return Ticket(++sequence, owner, generation).also { current = it }.id
    }

    fun matches(id: Long, owner: Any, generation: Int): Boolean =
        current?.let { it.id == id && it.valid && it.owner === owner && it.generation == generation } == true

    fun started(id: Long, owner: Any, generation: Int) {
        if (matches(id, owner, generation)) current?.blankStarted = true
    }

    fun complete(id: Long, owner: Any, generation: Int): Boolean {
        if (!matches(id, owner, generation)) return false
        val active = current ?: return false
        return active.blankStarted && active.barrier.complete(true)
    }

    suspend fun await(id: Long): Boolean {
        val active = current?.takeIf { it.id == id && it.valid } ?: return false
        val ready = try {
            withTimeoutOrNull(timeoutMillis) { active.barrier.await() } == true
        } catch (_: CancellationException) {
            false
        }
        if (!ready) cancel(id)
        return ready && current === active && active.valid
    }

    fun cancel(id: Long) {
        current?.takeIf { it.id == id }?.let {
            it.valid = false
            it.barrier.complete(false)
        }
    }

    /** Only the authenticated load can end quarantine, and only after its blank barrier. */
    fun finish(id: Long, owner: Any, generation: Int): Boolean {
        if (!matches(id, owner, generation)) return false
        val active = current ?: return false
        if (!active.barrier.isCompleted || active.barrier.isCancelled) return false
        current = null
        return true
    }
}

/** Duplicate/stale callbacks never spend another preparation, even after a successful round resets it. */
internal class GatewayHandoffBudget {
    enum class Claim { Start, Duplicate, Rejected }
    private var lastTicket = 0L
    var used = 0
        private set

    fun claim(ticket: Long): Claim = when {
        ticket > 0 && ticket == lastTicket -> Claim.Duplicate
        ticket <= lastTicket || used >= 2 -> Claim.Rejected
        else -> {
            lastTicket = ticket
            used++
            Claim.Start
        }
    }

    fun reset() { used = 0 }

    /** User-initiated recovery gets a fresh budget, but cannot re-consume any prior ticket. */
    fun manualRetry() { used = 0 }
}
