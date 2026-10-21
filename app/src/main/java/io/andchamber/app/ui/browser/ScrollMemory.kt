package io.andchamber.app.ui.browser

import kotlin.math.abs

data class WindowChangeToken(val transitionId: Long, val next: Boolean)

/** No geometry/ref epoch here: resizing legitimately changes those while retaining this page. */
internal data class ScrollPageIdentity(
    val instanceKey: String,
    val tabId: String,
    val viewId: String,
    val navigationTicket: Long,
    val documentWitness: String?,
    val interactionSeq: Long,
    val modeSeq: Long,
    val runtimeLaunchId: Long,
    val consoleGeneration: Long,
)

/** Only compared across the fresh capture await, never against a bookmark after resize. */
internal data class ScrollCaptureStamp(val sessionEpoch: Long, val geometryEpoch: Long, val refEpoch: Long)

/** Layout CSS scrollX/Y, not native pixels or visualViewport pageLeft/pageTop. */
data class ScrollAnchor(val x: Double, val y: Double) {
    fun valid(): Boolean = x.isFinite() && y.isFinite() && x >= 0 && y >= 0
}

enum class ScrollNotSavedReason {
    LOADING, NO_WITNESS, TIMEOUT, CAPTURE_FAILED, INVALID_COORDINATES, CAPTURE_EPOCH_CHANGED,
    NO_BOOKMARK, INTERACTION_CHANGED, MODE_CHANGED, DOCUMENT_CHANGED, CONTEXT_CHANGED, READBACK_FAILED,
    /** The window content never reported a target layout in its bounded registration deadline. */
    NO_TARGET_LAYOUT,
}

sealed interface ScrollSample {
    data class Captured(val anchor: ScrollAnchor) : ScrollSample
    data class Unavailable(val reason: ScrollNotSavedReason) : ScrollSample
}

sealed interface ScrollSave {
    data class Saved(val anchor: ScrollAnchor) : ScrollSave
    data class NotSaved(val reason: ScrollNotSavedReason) : ScrollSave
}

sealed interface WindowChange {
    data class Proceed(val token: WindowChangeToken, val saved: ScrollSave) : WindowChange
    data object Superseded : WindowChange
}

internal data class ScrollCaptureRequest internal constructor(
    val token: WindowChangeToken,
    val page: ScrollPageIdentity,
    val stamp: ScrollCaptureStamp,
)

internal data class ScrollRestorePlan internal constructor(
    val token: WindowChangeToken,
    val page: ScrollPageIdentity,
    val target: ScrollAnchor,
)

enum class ScrollDiscardReason { SUPERSEDED, PAGE_CHANGED, ALREADY_CONSUMED, NOT_RESTORING }

sealed interface RestoreResult {
    data class Restored(val saved: ScrollAnchor, val actual: ScrollAnchor) : RestoreResult
    data class Clamped(val saved: ScrollAnchor, val actual: ScrollAnchor) : RestoreResult
    data class Discarded(val reason: ScrollDiscardReason) : RestoreResult
    data class NotSaved(val reason: ScrollNotSavedReason) : RestoreResult
}

internal sealed interface ScrollRestore {
    data class Apply(val plan: ScrollRestorePlan) : ScrollRestore
    data class Skip(val result: RestoreResult) : ScrollRestore
}

/**
 * Main-owned policy for one expansion cycle. No WebView, coroutine, JS, authority, or pixel scale.
 * Presenter must capture fresh IO before changing constraints; it must verify stable geometry
 * before consumeRestore, and recheck identity/cancellation immediately before executing its plan.
 * observePage must run when intent/mode/document identity changes (not for ordinary scroll events).
 */
internal class ScrollMemory {
    private data class Bookmark(val page: ScrollPageIdentity, val anchor: ScrollAnchor)
    private var serial = 0L
    private var pending: ScrollCaptureRequest? = null
    private var current: WindowChangeToken? = null
    private var bookmark: Bookmark? = null
    private var missing = ScrollNotSavedReason.NO_BOOKMARK
    private var attemptedRestore: WindowChangeToken? = null
    private var restoring: ScrollRestorePlan? = null

    fun beginChange(next: Boolean, page: ScrollPageIdentity, stamp: ScrollCaptureStamp): ScrollCaptureRequest {
        observePage(page)
        val token = WindowChangeToken(nextSerial(), next)
        current = null
        restoring = null
        attemptedRestore = null
        if (next) { bookmark = null; missing = ScrollNotSavedReason.NO_BOOKMARK }
        return ScrollCaptureRequest(token, page, stamp).also { pending = it }
    }

    /** Restore needs no new capture: omit sample for next=false. Never overwrite the original anchor. */
    fun completeChange(
        request: ScrollCaptureRequest,
        currentPage: ScrollPageIdentity,
        currentStamp: ScrollCaptureStamp,
        sample: ScrollSample? = null,
    ): WindowChange {
        if (pending !== request || request.token.transitionId != serial) return WindowChange.Superseded
        if (request.page != currentPage) {
            observePage(currentPage)
            return WindowChange.Superseded
        }
        pending = null
        current = request.token
        if (request.token.next) {
            missing = when {
                currentStamp != request.stamp -> ScrollNotSavedReason.CAPTURE_EPOCH_CHANGED
                sample is ScrollSample.Unavailable -> sample.reason
                currentPage.documentWitness.isNullOrBlank() -> ScrollNotSavedReason.NO_WITNESS
                sample == null -> ScrollNotSavedReason.CAPTURE_FAILED
                sample is ScrollSample.Captured && !sample.anchor.valid() -> ScrollNotSavedReason.INVALID_COORDINATES
                else -> ScrollNotSavedReason.NO_BOOKMARK
            }
            if (currentStamp == request.stamp && !currentPage.documentWitness.isNullOrBlank() &&
                sample is ScrollSample.Captured && sample.anchor.valid()) {
                bookmark = Bookmark(currentPage, sample.anchor)
            }
        }
        return WindowChange.Proceed(request.token, bookmark?.let { ScrollSave.Saved(it.anchor) } ?: ScrollSave.NotSaved(missing))
    }

    /** Called only after core confirms the restore layout/probe is stable. Does not perform IO. */
    fun consumeRestore(token: WindowChangeToken, currentPage: ScrollPageIdentity): ScrollRestore {
        if (token != current || token.transitionId != serial) return skip(ScrollDiscardReason.SUPERSEDED)
        if (token.next) return skip(ScrollDiscardReason.NOT_RESTORING)
        if (attemptedRestore == token) return skip(ScrollDiscardReason.ALREADY_CONSUMED)
        attemptedRestore = token
        val saved = bookmark
        bookmark = null // Claim the plan once, before any caller can start restore IO.
        if (saved == null) return ScrollRestore.Skip(RestoreResult.NotSaved(missing))
        if (saved.page != currentPage) return skip(ScrollDiscardReason.PAGE_CHANGED)
        val plan = ScrollRestorePlan(token, currentPage, saved.anchor)
        restoring = plan
        return ScrollRestore.Apply(plan)
    }

    /** Only a real immediate scrollTo + readback can produce Restored, within one layout CSS px. */
    fun completeRestore(plan: ScrollRestorePlan, currentPage: ScrollPageIdentity, actual: ScrollAnchor?): RestoreResult {
        if (plan.token != current || plan.token.transitionId != serial) return RestoreResult.Discarded(ScrollDiscardReason.SUPERSEDED)
        if (plan.page != currentPage) {
            observePage(currentPage)
            return RestoreResult.Discarded(ScrollDiscardReason.PAGE_CHANGED)
        }
        if (restoring !== plan) return RestoreResult.Discarded(ScrollDiscardReason.ALREADY_CONSUMED)
        restoring = null
        if (actual == null || !actual.valid()) return RestoreResult.NotSaved(ScrollNotSavedReason.READBACK_FAILED)
        return if (abs(actual.x - plan.target.x) <= 1.0 && abs(actual.y - plan.target.y) <= 1.0) {
            RestoreResult.Restored(plan.target, actual)
        } else RestoreResult.Clamped(plan.target, actual)
    }

    /**
     * Read-only. True only for the pending restore transition that is still the current one, with a bookmark
     * still available to restore. It does not claim the bookmark and does not change policy.
     */
    fun isCurrentRestoreToken(token: WindowChangeToken): Boolean =
        token == current && token.transitionId == serial && !token.next && attemptedRestore != token

    /**
     * Ends the restore for [token] without any scroll: a registration deadline expired. After this the token can
     * never be consumed, so a late target or sample for it cannot start a restore. Only the current token is affected.
     */
    fun cancelRestoreToken(token: WindowChangeToken): Boolean {
        if (token != current || token.transitionId != serial || token.next) return false
        attemptedRestore = token
        return true
    }

    /** Core rechecks this and its native admission immediately before dispatching restore IO. */
    fun isRestoreCurrent(plan: ScrollRestorePlan, currentPage: ScrollPageIdentity): Boolean =
        restoring === plan && plan.token == current && plan.token.transitionId == serial && plan.page == currentPage

    /** Increment interactionSeq BEFORE human input or an admitted agent mutation, then call this. */
    fun observePage(page: ScrollPageIdentity) {
        bookmark?.takeIf { it.page != page }?.let {
            missing = changedReason(it.page, page)
            bookmark = null
        }
        if (pending?.page?.let { it != page } == true) {
            pending = null
            current = null
        }
        if (restoring?.page?.let { it != page } == true) restoring = null
    }

    /** Call on runtime/console/context loss even if it later returns to the same identifiers. */
    fun invalidateContext() {
        nextSerial()
        pending = null
        current = null
        bookmark = null
        restoring = null
        attemptedRestore = null
        missing = ScrollNotSavedReason.CONTEXT_CHANGED
    }

    /** Cancelled before-layout work must not remain eligible for a delayed settled callback. */
    fun cancel(token: WindowChangeToken) {
        if (token.transitionId == serial) invalidateContext()
    }

    private fun nextSerial(): Long {
        check(serial < Long.MAX_VALUE) { "window transition exhausted" }
        return ++serial
    }

    private fun skip(reason: ScrollDiscardReason) = ScrollRestore.Skip(RestoreResult.Discarded(reason))

    private fun changedReason(old: ScrollPageIdentity, new: ScrollPageIdentity): ScrollNotSavedReason = when {
        old.instanceKey != new.instanceKey || old.runtimeLaunchId != new.runtimeLaunchId ||
            old.consoleGeneration != new.consoleGeneration -> ScrollNotSavedReason.CONTEXT_CHANGED
        old.tabId != new.tabId || old.viewId != new.viewId || old.navigationTicket != new.navigationTicket ||
            old.documentWitness != new.documentWitness -> ScrollNotSavedReason.DOCUMENT_CHANGED
        old.modeSeq != new.modeSeq -> ScrollNotSavedReason.MODE_CHANGED
        else -> ScrollNotSavedReason.INTERACTION_CHANGED
    }
}
