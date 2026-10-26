package io.andchamber.app.phone.android.overlay

/** Which edge the capsule is docked to. Owned by the manager, not by composition. */
internal enum class PhoneOverlaySide { LEFT, RIGHT }

/** Why the agent stopped, as a native display fact. Mapped to the designer model at the surface. */
internal enum class PhoneOverlayStopReason {
    Expired,
    NativeOpened,
    TargetLeft,
    ScreenLocked,
    ConnectionLost,
    RuntimeChanged,
    OverlayUnreachable,
    AgentAskedForYou,
}

/**
 * The overlay's display state, owned by the manager on the main thread.
 *
 * [generation] identifies the task-display owner: it changes only when a new display owner starts
 * or the window is deliberately detached, never for a temporary read-hide. [revision] identifies one
 * geometry/content generation and changes on expand, end-confirm, side moves, drags and phase
 * changes, so old observations never apply to a newer layout. A bounded read restore is only
 * owner/generation-bound and restores the current state, so a pause/takeover during the read is not
 * lost and the old RUNNING snapshot is never brought back.
 */
internal data class PhoneOverlayWindowState(
    val ownerKey: String,
    val generation: Long = 0L,
    val revision: Long = 0L,
    val phase: PhoneOverlayPhase = PhoneOverlayPhase.IDLE,
    val targetLabel: String? = null,
    val summary: String? = null,
    val leaseEndsAtMs: Long? = null,
    val expanded: Boolean = false,
    val endConfirm: Boolean = false,
    val side: PhoneOverlaySide = PhoneOverlaySide.RIGHT,
    val offsetY: Int = 0,
    /** Why the last confirmed stop happened; null for a plain pause/take-over. */
    val stopReason: PhoneOverlayStopReason? = null,
    /** A stopped task is kept for the person; this never means the same as ended. */
    val retained: Boolean = false,
    /** Offer the configure-once "continue beside the chat" entry (native confirmation only). */
    val canContinueInChat: Boolean = false,
) {
    /** Stable, discoverable window title. The driver may match this against its dumpsys witness. */
    val windowTitle: String get() = overlayWindowTitle(ownerKey, generation)

    fun withPhase(
        phase: PhoneOverlayPhase,
        label: String? = targetLabel,
        summary: String? = this.summary,
        leaseEndsAtMs: Long? = this.leaseEndsAtMs,
    ): PhoneOverlayWindowState =
        if (this.phase == phase && this.targetLabel == label && this.summary == summary && this.leaseEndsAtMs == leaseEndsAtMs) {
            this
        } else {
            copy(
                phase = phase,
                targetLabel = label,
                summary = summary,
                leaseEndsAtMs = leaseEndsAtMs,
                // A panel opened for a stop cannot stay open after the task's phase changed.
                endConfirm = endConfirm && phase == PhoneOverlayPhase.RUNNING,
                revision = revision + 1,
            )
        }

    fun withExpanded(expanded: Boolean): PhoneOverlayWindowState =
        copy(expanded = expanded, endConfirm = if (expanded) endConfirm else false, revision = revision + 1)

    fun withEndConfirm(confirming: Boolean): PhoneOverlayWindowState =
        copy(expanded = true, endConfirm = confirming, revision = revision + 1)

    /** A new task-display owner: a fresh generation, reset geometry and no stale confirmation. */
    fun withNewOwner(ownerKey: String): PhoneOverlayWindowState =
        copy(
            ownerKey = ownerKey,
            generation = generation + 1,
            revision = revision + 1,
            expanded = false,
            endConfirm = false,
            offsetY = 0,
            stopReason = null,
            retained = false,
            canContinueInChat = false,
        )

    fun withSide(side: PhoneOverlaySide): PhoneOverlayWindowState =
        if (this.side == side) this else copy(side = side, revision = revision + 1)

    /** Stop reason/retention/chat-continuation facts shown beside the phase; never a grant. */
    fun withStopFacts(
        stopReason: PhoneOverlayStopReason?,
        retained: Boolean,
        canContinueInChat: Boolean,
    ): PhoneOverlayWindowState =
        if (this.stopReason == stopReason && this.retained == retained &&
            this.canContinueInChat == canContinueInChat
        ) {
            this
        } else {
            copy(
                stopReason = stopReason,
                retained = retained,
                canContinueInChat = canContinueInChat,
                revision = revision + 1,
            )
        }

    fun withDrag(
        side: PhoneOverlaySide,
        offsetY: Int,
    ): PhoneOverlayWindowState = copy(side = side, offsetY = offsetY, revision = revision + 1)
}

/** The window title used as the real, stable token; random UUIDs cannot be matched by a witness. */
internal fun overlayWindowTitle(
    ownerKey: String,
    generation: Long,
): String = "andchamber-phone-overlay:$ownerKey:$generation"

/** Approximate window footprint used for clamping before a layout pass reports real sizes. */
internal data class PhoneOverlayGeometry(
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val topInsetPx: Int,
    val bottomInsetPx: Int,
    val sideMarginPx: Int,
    val collapsedHeightPx: Int,
    val expandedHeightPx: Int,
    val expandedWidthPx: Int,
)

/** Where the window goes and how large it may be. Pure so placement can be tested without Android. */
internal data class PhoneOverlayPlacement(
    val widthPx: Int,
    val heightPx: Int,
    /** From the docked edge; 0 means flush with the side margin. */
    val horizontalMarginPx: Int,
    val topPx: Int,
)

internal fun overlayPlacement(
    state: PhoneOverlayWindowState,
    geometry: PhoneOverlayGeometry,
): PhoneOverlayPlacement {
    val width = if (state.expanded) minOf(geometry.expandedWidthPx, geometry.screenWidthPx - 2 * geometry.sideMarginPx) else 0
    val availableHeight = (geometry.screenHeightPx - geometry.topInsetPx - geometry.bottomInsetPx -
        2 * geometry.sideMarginPx).coerceAtLeast(1)
    val height = minOf(if (state.expanded) geometry.expandedHeightPx else geometry.collapsedHeightPx, availableHeight)
    val minTop = geometry.topInsetPx + geometry.sideMarginPx
    val maxTop = (geometry.screenHeightPx - geometry.bottomInsetPx - height - geometry.sideMarginPx).coerceAtLeast(minTop)
    return PhoneOverlayPlacement(
        widthPx = width.coerceAtLeast(0),
        heightPx = height,
        horizontalMarginPx = geometry.sideMarginPx,
        topPx = state.offsetY.coerceIn(minTop, maxTop),
    )
}

/** Drag release: a drop on the bottom end zone asks for the end confirmation; otherwise snap. */
internal fun overlayDragRelease(
    state: PhoneOverlayWindowState,
    dragX: Float,
    dragY: Float,
    geometry: PhoneOverlayGeometry,
): PhoneOverlayWindowState {
    val bottom = geometry.screenHeightPx - geometry.bottomInsetPx
    val height = if (state.expanded) geometry.expandedHeightPx else geometry.collapsedHeightPx
    val endZoneTop = bottom - (geometry.screenHeightPx / 5).coerceAtLeast(height)
    if (dragY >= endZoneTop && state.phase == PhoneOverlayPhase.RUNNING) {
        return state.withEndConfirm(confirming = true)
    }
    val side = if (dragX < geometry.screenWidthPx / 2f) PhoneOverlaySide.LEFT else PhoneOverlaySide.RIGHT
    return state.withDrag(side = side, offsetY = dragY.toInt())
}

/** Clamp after rotation/IME changes; a panel that grew past the new bottom is pulled back in. */
internal fun overlayReflow(
    state: PhoneOverlayWindowState,
    geometry: PhoneOverlayGeometry,
): PhoneOverlayWindowState {
    val placement = overlayPlacement(state, geometry)
    return if (placement.topPx == state.offsetY) state else state.withDrag(state.side, placement.topPx)
}

/** Why a read-hide was refused. Fixed values, never a label or geometry. */
internal enum class PhoneOverlayHideRefusal { NOT_ATTACHED, HIDDEN_FOR_READ, OWNER_CHANGED, GENERATION_CHANGED, REVISION_CHANGED }

/**
 * Whether a driver read may hide this window. The owner, generation and revision must all match and
 * the window must be attached; a stale read never hides a newer layout.
 */
internal fun overlayHideRefusal(
    requestOwner: String,
    requestGeneration: Long,
    requestRevision: Long,
    current: PhoneOverlayWindowState,
    attached: Boolean,
    hiddenForRead: Boolean,
): PhoneOverlayHideRefusal? =
    when {
        !attached -> PhoneOverlayHideRefusal.NOT_ATTACHED
        hiddenForRead -> PhoneOverlayHideRefusal.HIDDEN_FOR_READ
        requestOwner != current.ownerKey -> PhoneOverlayHideRefusal.OWNER_CHANGED
        requestGeneration != current.generation -> PhoneOverlayHideRefusal.GENERATION_CHANGED
        requestRevision != current.revision -> PhoneOverlayHideRefusal.REVISION_CHANGED
        else -> null
    }

/**
 * Restore only the exact same display owner/generation, and only if still wanted. The current
 * manager state is authoritative: a revision change during the read (pause/takeover/drag/new
 * control facts) is restored at its latest value, never the old read snapshot. A new owner or a
 * deliberate detach refuses the restore entirely.
 */
internal fun overlayRestoreRefusal(
    requestOwner: String,
    requestGeneration: Long,
    current: PhoneOverlayWindowState,
    attached: Boolean,
    detached: Boolean,
): PhoneOverlayHideRefusal? =
    when {
        detached -> PhoneOverlayHideRefusal.NOT_ATTACHED
        attached -> null
        requestOwner != current.ownerKey -> PhoneOverlayHideRefusal.OWNER_CHANGED
        requestGeneration != current.generation -> PhoneOverlayHideRefusal.GENERATION_CHANGED
        else -> null
    }
