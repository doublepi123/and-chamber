package io.andchamber.app.ui.browser

/**
 * Raw-input fence for the selected native view. Each new view identity gets a new [TouchpadGesture]
 * and admits nothing until a fresh ACTION_DOWN arrives on that view. A MOVE, UP or CANCEL left over
 * from a previous view is refused and can never become a click on the new one.
 *
 * A DOWN opens the gesture window; MOVE/UP/CANCEL are admitted only while it is open. An UP or CANCEL
 * closes it. Repeating a DOWN while it is open (the UI listener and the mouse path both ask) stays
 * admitted and does not change the gesture.
 */
data class RawInputEventKey(
    val action: Int,
    val downTime: Long,
    val eventTime: Long,
    val deviceId: Int,
    val source: Int,
    val changedPointerId: Int,
    val pointerIds: List<Int>,
    val x: Float,
    val y: Float,
)

internal class InputEventMemo {
    private var event: Any? = null
    private var key: RawInputEventKey? = null
    private var owner: Any? = null
    private var result = false

    fun recall(event: Any, key: RawInputEventKey, owner: Any?): Boolean? =
        if (event === this.event && key == this.key && owner === this.owner) result else null

    fun remember(event: Any, key: RawInputEventKey, owner: Any?, result: Boolean) {
        this.event = event
        this.key = key
        this.owner = owner
        this.result = result
    }

    fun clear() {
        event = null
        key = null
        owner = null
        result = false
    }
}

internal class InputAdmission {
    private var owner: Any? = null
    private var open = false

    var gesture: TouchpadGesture = TouchpadGesture()
        private set

    /** Returns true when the owner identity changed; the gesture is then new and waits for a DOWN. */
    fun bind(view: Any?): Boolean {
        if (view === owner) return false
        owner = view
        gesture = TouchpadGesture()
        open = false
        return true
    }

    fun admits(action: Int): Boolean = when (action) {
        MouseInput.ACTION_DOWN -> {
            open = true
            true
        }
        MouseInput.ACTION_UP, MouseInput.ACTION_CANCEL -> {
            val admitted = open
            open = false
            admitted
        }
        else -> open
    }
}
