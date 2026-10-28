package io.andchamber.app.phone.core

/**
 * Platform boundary for phone control.
 *
 * The contract contains exactly the bounded operations the controller may ask for. There is no
 * generic shell, arbitrary command, key event stream or intent surface. Pairing and transport are
 * driver-service concerns: the pure controller never pairs, and a paired/connected device is not
 * authority by itself.
 *
 * Implementations must:
 * - return only the foreground window of the device, never a background app;
 * - never use a generic shell to perform a node action;
 * - re-verify [PhoneDispatchRequest.binding] against the live package, window, rotation and
 *   geometry immediately before injecting an action, and return [PhoneDispatchReceipt.NotSent]
 *   when anything changed;
 * - report a [PhoneDispatchReceipt.Unknown] result instead of guessing after an uncertain call.
 */
interface PhoneDriver {
    /** Static capability report. It may be called while disconnected. */
    fun capabilities(): PhoneCapabilities

    /** Makes one bounded connection attempt. A connection is not a grant. */
    suspend fun connect(deadlineMs: Long): PhoneConnection

    /** Best-effort teardown. The controller has already revoked any lease before this call. */
    suspend fun disconnect(deadlineMs: Long)

    /** Current foreground package/window and a bounded accessibility snapshot. */
    suspend fun observe(deadlineMs: Long): PhoneSnapshot

    /**
     * Captures the display bound to [binding]. Must refuse with
     * [PhoneDriverFailure.TARGET_CHANGED]/[PhoneDriverFailure.WINDOW_CHANGED] rather than returning
     * an image of another window.
     */
    suspend fun capture(binding: PhoneObservationBinding, deadlineMs: Long): PhoneImage

    /**
     * Injects one bounded action. The driver must check the live package/window/rotation/geometry
     * against [request] before touching the device.
     */
    suspend fun dispatch(request: PhoneDispatchRequest, deadlineMs: Long): PhoneDispatchReceipt
}

/** Explicit per-action capability report. Every flag defaults to false: a driver must opt in. */
data class PhoneCapabilities(
    val observe: Boolean = false,
    val capture: Boolean = false,
    val tap: Boolean = false,
    val swipe: Boolean = false,
    val input: Boolean = false,
    /** Non-ASCII text input. A driver that cannot enter Unicode must leave this false. */
    val unicodeInput: Boolean = false,
    val back: Boolean = false,
) {
    fun supports(kind: PhoneActionKind): Boolean = when (kind) {
        PhoneActionKind.TAP -> tap
        PhoneActionKind.SWIPE -> swipe
        PhoneActionKind.INPUT -> input
        PhoneActionKind.BACK -> back
    }

    fun supportsUnicodeInput(): Boolean = input && unicodeInput
}

data class PhoneConnection(
    val connectionId: String,
    /** Runtime identity that owns this connection. A different runtime revokes the lease. */
    val runtimeId: String,
)

data class PhonePoint(val x: Int, val y: Int) {
    fun isWithin(geometry: PhoneGeometry): Boolean =
        x >= 0 && y >= 0 && x < geometry.displayWidth && y < geometry.displayHeight
}

data class PhoneRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun isWithin(geometry: PhoneGeometry): Boolean =
        left >= 0 && top >= 0 && right <= geometry.displayWidth &&
            bottom <= geometry.displayHeight && right > left && bottom > top
}

data class PhoneGeometry(
    val displayWidth: Int,
    val displayHeight: Int,
    /** Android display rotation: 0, 1, 2 or 3. */
    val rotation: Int,
)

/** One accessibility node. [label] is page content and is never copied into state or errors. */
data class PhoneNode(
    val ref: String,
    val role: String = "",
    val label: String = "",
    val bounds: PhoneRect,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val focused: Boolean = false,
)

/** Raw driver observation. The controller allocates the public observation id and generation. */
data class PhoneSnapshot(
    val packageName: String,
    val windowId: String,
    val geometry: PhoneGeometry,
    val nodes: List<PhoneNode>,
    val refs: Map<String, PhoneRect>,
)

/**
 * Everything an action or capture is bound to. The session label is deliberately absent: it only
 * routes a request and never authenticates one.
 */
data class PhoneObservationBinding(
    val observationId: String,
    val generation: Long,
    val packageName: String,
    val windowId: String,
    val rotation: Int,
    val geometry: PhoneGeometry,
)

data class PhoneObservation(
    val binding: PhoneObservationBinding,
    val nodes: List<PhoneNode>,
    val refs: Map<String, PhoneRect>,
    val observedAtMs: Long,
)

/** Raw screenshot bytes. Equality is identity-based; callers must not compare captures by value. */
class PhoneImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
)

data class PhoneCapture(
    val binding: PhoneObservationBinding,
    val image: PhoneImage,
    val capturedAtMs: Long,
)

/**
 * Non-suspending admission check the driver calls immediately before each actual injection.
 * It never creates or extends authority: `null` means the already-admitted action may still be
 * performed; a returned [PhoneErrorCode] must prevent injection. A driver that already touched the
 * device (for example, focused an input field) and then sees a refusal must report
 * [PhoneDispatchReceipt.Unknown], not `NotSent`.
 *
 * On a non-null result, a driver that has not touched the device returns
 * `NotSent(ADMISSION_REFUSED)`; one that already touched it (for example, focused an input field)
 * returns `Unknown(ADMISSION_REFUSED)`.
 */
fun interface PhoneAdmissionGuard {
    fun check(): PhoneErrorCode?

    companion object {
        /** Fail-closed default for a request built without a controller-issued guard. */
        val DENY: PhoneAdmissionGuard = PhoneAdmissionGuard { PhoneErrorCode.CANCELLED }
    }
}

data class PhoneDispatchRequest(
    val binding: PhoneObservationBinding,
    val action: PhoneDriverAction,
    val guard: PhoneAdmissionGuard = PhoneAdmissionGuard.DENY,
)

/** The only injectable actions. Coordinates are display pixels bound to the observation geometry. */
sealed interface PhoneDriverAction {
    /**
     * [target] is the observed element bounds for a tap by ref. Pages keep changing (timers,
     * carousels, labels), so the driver does not require identical content; it requires the same
     * real window and, when [target] is set, a live element with exactly these bounds.
     */
    data class Tap(val at: PhonePoint, val target: PhoneRect? = null) : PhoneDriverAction

    data class Swipe(
        val from: PhonePoint,
        val to: PhonePoint,
        val durationMs: Int,
    ) : PhoneDriverAction

    /**
     * Focus [focus] first when present, then enter [text] into the focused editable field.
     * A driver without Unicode support must return [PhoneDispatchReceipt.NotSent] with
     * [PhoneDriverFailure.UNSUPPORTED] for non-ASCII text and must never claim [PhoneDispatchReceipt.Sent]
     * for text it did not enter.
     */
    data class Input(
        val text: String,
        val focus: PhonePoint? = null,
    ) : PhoneDriverAction

    data object Back : PhoneDriverAction
}

sealed interface PhoneDispatchReceipt {
    /** The driver injected the action. This does not claim the app's business effect. */
    data object Sent : PhoneDispatchReceipt

    /** The driver refused before touching the device. */
    data class NotSent(val failure: PhoneDriverFailure) : PhoneDispatchReceipt

    /** The action may or may not have reached the device. */
    data class Unknown(val failure: PhoneDriverFailure? = null) : PhoneDispatchReceipt
}

/** Fixed driver failures. No failure carries page text, a serial, a path or a raw command. */
enum class PhoneDriverFailure {
    NOT_CONNECTED,
    UNSUPPORTED,
    TARGET_CHANGED,
    WINDOW_CHANGED,
    GEOMETRY_CHANGED,
    POLICY_DENIED,
    INVALID_REQUEST,
    /** The controller-issued admission guard refused immediately before injection. */
    ADMISSION_REFUSED,
    TIMEOUT,
    CANCELLED,
    OUTCOME_UNKNOWN,
    /** The read-only hierarchy service reported the fixed AOSP idle-state error, not XML. */
    HIERARCHY_NOT_IDLE,
    /**
     * The read-only ADB service transaction hit its watchdog (open or read). The exact connection
     * is abandoned and no longer usable for control.
     */
    READ_TIMEOUT,
    DRIVER,
}

class PhoneDriverException(
    val failure: PhoneDriverFailure,
) : Exception(failure.name)
