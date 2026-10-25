package io.andchamber.app.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The total broadcast budget is scoped to the new inline path, not retrofitted onto classic actions. */
class NotificationActionReceiverBudgetTest {
    @Test
    fun `only inline actions get the new total budget`() {
        assertTrue(NotificationActionReceiver.usesInlineBudget(NotificationActionReceiver.ACTION_INLINE_FORM))
        listOf(
            NotificationActionReceiver.ACTION_REPLY,
            NotificationActionReceiver.ACTION_FORM,
            NotificationActionReceiver.ACTION_PERMISSION,
            null,
        ).forEach { action ->
            assertFalse("$action must keep its old semantics", NotificationActionReceiver.usesInlineBudget(action))
        }
    }
}
