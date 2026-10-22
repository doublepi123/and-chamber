package io.andchamber.app.ui.browser

data class MouseDocumentIdentity(
    val viewId: String,
    val ticket: Long,
    val witness: String,
)

sealed interface MousePressOutcome {
    data object NoActivePress : MousePressOutcome
    data object DeferUntilRelease : MousePressOutcome
    data class RetireRequired(
        val originalViewIdentity: MouseDocumentIdentity,
        val reason: String,
        val documentPreserved: Boolean = false,
    ) : MousePressOutcome
}

class NativeMousePressState(val identity: MouseDocumentIdentity) {
    var activePress: Boolean = false
        private set
    var retired: Boolean = false
        private set
    var outcome: MousePressOutcome = MousePressOutcome.NoActivePress
        private set

    fun beforePressDispatch(constructed: Boolean, actionButtonApplied: Boolean): Boolean {
        if (retired || !constructed || !actionButtonApplied) {
            if (!activePress) outcome = MousePressOutcome.NoActivePress
            return false
        }
        activePress = true
        return true
    }

    fun releaseDelivered() {
        if (!retired && activePress) {
            activePress = false
            outcome = MousePressOutcome.NoActivePress
        }
    }

    fun interrupt(reason: String, unavoidable: Boolean): MousePressOutcome {
        if (retired) return outcome
        if (!activePress) {
            outcome = MousePressOutcome.NoActivePress
            return outcome
        }
        if (!unavoidable) {
            outcome = MousePressOutcome.DeferUntilRelease
            return outcome
        }
        retired = true
        activePress = false
        outcome = MousePressOutcome.RetireRequired(identity, reason, documentPreserved = false)
        return outcome
    }

    fun accepts(identity: MouseDocumentIdentity): Boolean = !retired && identity == this.identity
}
