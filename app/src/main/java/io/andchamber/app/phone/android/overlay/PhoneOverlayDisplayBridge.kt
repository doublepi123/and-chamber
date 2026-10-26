package io.andchamber.app.phone.android.overlay

/**
 * The display service's only view of the native coordinator.
 *
 * The service never calls the coordinator type directly, so a task-lane rewrite of that file only
 * needs this small adapter to be re-pointed (one mechanical merge), not the service, owner identity or
 * close rules. The default fails closed: no owner, no start, no close.
 */
internal interface PhoneOverlayDisplayBridge {
    /** Opaque task-display owner (`<ownerKey>:<generation>`); never a summary. */
    fun taskOwner(): String

    fun onServiceStarted(owner: String)

    /** True only when the coordinator really closed the display; the service then may stop itself. */
    fun onCloseRequested(owner: String): Boolean

    fun onServiceDestroyed(owner: String)

    fun onPermissionLost(owner: String)

    /** Rotation/inset change for this exact owner. Layout only; never grants or resumes. */
    fun onConfigurationChanged(owner: String) = Unit

    object Unavailable : PhoneOverlayDisplayBridge {
        override fun taskOwner(): String = ""

        override fun onServiceStarted(owner: String) = Unit

        override fun onCloseRequested(owner: String): Boolean = false

        override fun onServiceDestroyed(owner: String) = Unit

        override fun onPermissionLost(owner: String) = Unit

        override fun onConfigurationChanged(owner: String) = Unit
    }
}

/**
 * Production wiring to the native coordinator.
 *
 * This is the only place in this lane that names coordinator display methods. If the task lane changes
 * their signatures or return values, update this adapter and report the exact patch; the service,
 * owner helper and their tests stay untouched.
 */
internal class CoordinatorDisplayBridge(
    private val coordinator: PhoneOverlayCoordinator,
) : PhoneOverlayDisplayBridge {
    override fun taskOwner(): String = coordinator.displayTaskOwner()

    override fun onServiceStarted(owner: String) = coordinator.onDisplayServiceStarted(owner)

    override fun onCloseRequested(owner: String): Boolean = coordinator.onCloseOverlay(owner)

    override fun onServiceDestroyed(owner: String) = coordinator.onDisplayServiceDestroyed(owner)

    override fun onPermissionLost(owner: String) = coordinator.onDisplayPermissionLost(owner)

    override fun onConfigurationChanged(owner: String) = coordinator.onConfigurationChanged(owner)
}
