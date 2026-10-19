package io.andchamber.app.notify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules for notification buttons: what may be sent, cancelled or kept, and requireHidden. */
class NotificationDecisionsTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private val permissionPending = obj("""{"ses_1":{"permissions":[{"id":"per_9","action":"bash","resources":["npm test"]}]}}""")
    private val textForm = obj("""{"ses_1":{"forms":[{"id":"frm_1","sessionID":"ses_1","fields":[{"key":"name","type":"string"}]}]}}""")
    private val multiFieldForm =
        obj("""{"ses_1":{"forms":[{"id":"frm_1","sessionID":"ses_1","fields":[{"key":"a","type":"string"},{"key":"b","type":"string"}]}]}}""")
    private val nothingPending = obj("""{}""")

    // --- requireHidden (OpenChamber "hidden-only" vs "always") ---

    @Test
    fun `requireHidden defaults to true and only an explicit false turns it off`() {
        val prefix = NotificationPayload.PREFIX
        assertTrue(NotificationPayload.parseLine(prefix + """{"tag":"t"}""")!!.requireHidden)
        assertTrue(NotificationPayload.parseLine(prefix + """{"tag":"t","requireHidden":true}""")!!.requireHidden)
        assertTrue(NotificationPayload.parseLine(prefix + """{"tag":"t","requireHidden":null}""")!!.requireHidden)
        assertTrue(NotificationPayload.parseLine(prefix + """{"tag":"t","requireHidden":"false"}""")!!.requireHidden)
        assertFalse(NotificationPayload.parseLine(prefix + """{"tag":"t","requireHidden":false}""")!!.requireHidden)
    }

    @Test
    fun `always mode is posted even while the local page is showing`() {
        assertFalse(NotifyPolicy.leaveToPage(requireHidden = false, localPageShowing = true))
        assertTrue(NotifyPolicy.leaveToPage(requireHidden = true, localPageShowing = true))
        assertFalse(NotifyPolicy.leaveToPage(requireHidden = true, localPageShowing = false))
        val delivery = NotifyPolicy.deliveryFor(DesktopNotification.Kind.Ready, NotifyPolicy.leaveToPage(false, true))
        assertEquals(Delivery.Post(Channel.AgentFinished), delivery)
    }

    // --- reading the pending state ---

    @Test
    fun `a failed read is Unknown, never Gone`() {
        assertEquals(PendingCheck.Unknown, ActionDecisions.checkPermission(null, "ses_1", "per_9"))
        assertEquals(PendingCheck.Unknown, ActionDecisions.checkForm(null, "ses_1", "frm_1"))
    }

    @Test
    fun `a read that worked tells pending, gone and not answerable apart`() {
        assertEquals(PendingCheck.Pending, ActionDecisions.checkPermission(permissionPending, "ses_1", "per_9"))
        assertEquals(PendingCheck.Gone, ActionDecisions.checkPermission(nothingPending, "ses_1", "per_9"))
        assertEquals(PendingCheck.Gone, ActionDecisions.checkPermission(permissionPending, "ses_2", "per_9"))
        assertEquals(PendingCheck.Pending, ActionDecisions.checkForm(textForm, "ses_1", "frm_1"))
        assertEquals(PendingCheck.Gone, ActionDecisions.checkForm(textForm, "ses_1", "frm_other"))
        assertEquals(PendingCheck.NotAnswerable, ActionDecisions.checkForm(multiFieldForm, "ses_1", "frm_1"))
    }

    // --- before sending ---

    @Test
    fun `only a confirmed pending request is sent`() {
        assertEquals(ActionStep.Send, ActionDecisions.beforeSend(PendingCheck.Pending))
        assertEquals(ActionStep.Cancel, ActionDecisions.beforeSend(PendingCheck.Gone))
        assertEquals(ActionStep.KeepOpenOnly, ActionDecisions.beforeSend(PendingCheck.NotAnswerable))
        assertEquals(ActionStep.KeepCheckFailed, ActionDecisions.beforeSend(PendingCheck.Unknown))
    }

    @Test
    fun `a failed read keeps the buttons and sends nothing`() {
        val step = ActionDecisions.beforeSend(ActionDecisions.checkPermission(null, "ses_1", "per_9"))
        assertEquals(ActionStep.KeepCheckFailed, step)
        assertTrue("buttons stay for a later tap", step.keepsActions)
    }

    // --- after a send that didn't go through ---

    @Test
    fun `a refusal is a failure, while no answer or a server error is unknown`() {
        assertEquals(SendOutcome.Sent, ActionDecisions.outcomeOf(204))
        assertEquals(SendOutcome.Failed, ActionDecisions.outcomeOf(401))
        assertEquals(SendOutcome.Failed, ActionDecisions.outcomeOf(409))
        assertEquals(SendOutcome.Unknown, ActionDecisions.outcomeOf(0))
        assertEquals(SendOutcome.Unknown, ActionDecisions.outcomeOf(502))
    }

    @Test
    fun `nothing is ever sent again by itself after a failure`() {
        SendOutcome.entries.filter { it != SendOutcome.Sent }.forEach { outcome ->
            assertTrue(ActionDecisions.afterReply(outcome) != ActionStep.Send)
            PendingCheck.entries.forEach { check ->
                assertTrue("$outcome/$check", ActionDecisions.afterButton(outcome, check) != ActionStep.Send)
            }
        }
    }

    @Test
    fun `after a failed button send, gone cancels and anything else is kept`() {
        assertEquals(ActionStep.Cancel, ActionDecisions.afterButton(SendOutcome.Unknown, PendingCheck.Gone))
        assertEquals(ActionStep.KeepOpenOnly, ActionDecisions.afterButton(SendOutcome.Failed, PendingCheck.NotAnswerable))
        assertEquals(ActionStep.KeepSendFailed, ActionDecisions.afterButton(SendOutcome.Failed, PendingCheck.Pending))
        assertEquals(ActionStep.KeepResultUnknown, ActionDecisions.afterButton(SendOutcome.Unknown, PendingCheck.Pending))
        assertEquals(ActionStep.KeepResultUnknown, ActionDecisions.afterButton(SendOutcome.Unknown, PendingCheck.Unknown))
    }

    @Test
    fun `an unknown reply result asks the user to check, a refusal says it failed`() {
        assertEquals(ActionStep.KeepResultUnknown, ActionDecisions.afterReply(SendOutcome.Unknown))
        assertEquals(ActionStep.KeepSendFailed, ActionDecisions.afterReply(SendOutcome.Failed))
    }

    // --- what a kept notification carries ---

    @Test
    fun `a kept reply keeps its session, directory, reply field and the typed text`() {
        val context = FailureContext("ses_1", "/repo", "run the tests again")
        val plan = ActionDecisions.keepPlan(ActionStep.KeepResultUnknown, context, "status")!!
        assertEquals("ses_1", plan.sessionId)
        assertEquals("/repo", plan.directory)
        assertEquals("run the tests again", plan.draft)
        assertTrue(plan.keepActions)
        assertEquals("status", plan.status)
    }

    @Test
    fun `a form that can't be answered here keeps only the way into the session`() {
        val plan = ActionDecisions.keepPlan(ActionStep.KeepOpenOnly, FailureContext("ses_1", null, null), "s")!!
        assertFalse(plan.keepActions)
        assertEquals("ses_1", plan.sessionId)
    }

    @Test
    fun `send and cancel have no kept notification, and a blank draft is dropped`() {
        val context = FailureContext("ses_1", null, "  ")
        assertNull(ActionDecisions.keepPlan(ActionStep.Send, context, "s"))
        assertNull(ActionDecisions.keepPlan(ActionStep.Cancel, context, "s"))
        assertNull(ActionDecisions.keepPlan(ActionStep.KeepSendFailed, context, "s")!!.draft)
    }
}
