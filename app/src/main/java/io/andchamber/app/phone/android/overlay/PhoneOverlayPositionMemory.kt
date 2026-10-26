package io.andchamber.app.phone.android.overlay

/**
 * The person's chosen position, per rotation. Memory only: no summary, lease, phase, owner or refs.
 *
 * The overlay restores a posture when the device returns to that rotation; a phase change or a new
 * task never overwrites it. Bounded to the four display rotations, oldest evicted first.
 */
internal data class PhoneOverlayPosition(
    val side: PhoneOverlaySide,
    val offsetY: Int,
)

internal class PhoneOverlayPositionMemory(
    private val capacity: Int = ROTATIONS,
) {
    init {
        require(capacity in 1..ROTATIONS)
    }

    private val positions = LinkedHashMap<Int, PhoneOverlayPosition>()

    val size: Int get() = positions.size

    /** Ignores an impossible rotation index instead of growing without bound. */
    fun remember(
        rotation: Int,
        side: PhoneOverlaySide,
        offsetY: Int,
    ) {
        if (rotation !in 0 until ROTATIONS) return
        positions.remove(rotation)
        positions[rotation] = PhoneOverlayPosition(side = side, offsetY = offsetY)
        while (positions.size > capacity) {
            val oldest = positions.keys.firstOrNull() ?: break
            positions.remove(oldest)
        }
    }

    fun forRotation(rotation: Int): PhoneOverlayPosition? =
        if (rotation in 0 until ROTATIONS) positions[rotation] else null

    companion object {
        const val ROTATIONS = 4
    }
}

/**
 * A configuration callback may only act for the exact current display owner: this service instance
 * and this task owner. A late callback from an older notification/service is a pure no-op, and the
 * callback itself never grants, resumes or revives a stopped phase.
 */
internal fun overlayConfigurationOwnerAllowed(
    owner: String?,
    currentServiceOwner: String?,
): Boolean =
    PhoneOverlayDisplayOwner.matches(owner, currentServiceOwner) &&
        PhoneOverlayDisplayOwner.hasCurrentNonce(owner)
