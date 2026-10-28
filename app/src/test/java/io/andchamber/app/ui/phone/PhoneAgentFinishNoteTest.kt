package io.andchamber.app.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The agent's own clean finish is a light note, not the full "check the result" step. */
class PhoneAgentFinishNoteTest {
    private val notes = PhoneTarget("Notes", "com.example.notes")

    private fun ended(agentReported: Boolean, link: PhoneLink = PhoneLink.Connected) =
        PhoneScreenState(
            available = true,
            enabled = true,
            device = PhoneDeviceState(link = link),
            stopChannel = PhoneStopChannel.Verified,
            task = PhoneTask.Ended(PhoneTaskResult(notes, "Add a note", emptyList(), agentReported = agentReported)),
        )

    @Test
    fun `a person or uncertain end keeps the result step, an agent finish does not`() {
        assertEquals(PhoneStep.Result, phoneStep(ended(agentReported = false)))
        assertNull(agentFinishNote(ended(agentReported = false)))

        val done = ended(agentReported = true)
        assertNotEquals(PhoneStep.Result, phoneStep(done))
        assertEquals(PhoneStep.Grant, phoneStep(done))
        assertEquals(PhoneStep.Connect, phoneStep(ended(agentReported = true, link = PhoneLink.Lost)))
        assertEquals(notes, agentFinishNote(done)?.target)
    }

    @Test
    fun `no note without an ended task, or while a task runs or is stopped`() {
        val lease = PhoneLease(notes, "Add a note", setOf(PhoneActionScope.Tap), expiresAtMs = 600_000)
        assertNull(agentFinishNote(ended(true).copy(task = PhoneTask.None)))
        assertNull(agentFinishNote(ended(true).copy(task = PhoneTask.Active(notes, "Add a note"))))
        // A live or stopped control still wins the step.
        assertEquals(PhoneStep.Running, phoneStep(ended(true).copy(control = PhoneControl.Agent(lease))))
        assertEquals(
            PhoneStep.Halted,
            phoneStep(ended(true).copy(control = PhoneControl.Halted(lease, PhoneHaltReason.TakenOver, true, PhoneLastAction.NoneInFlight))),
        )
    }

    @Test
    fun `finish note copy exists in both languages without a verification label`() {
        val en = load("values/phone_strings.xml")
        val zh = load("values-zh/phone_strings.xml")
        val keys = listOf("phone_task_agent_done", "phone_agent_finish_title", "phone_agent_finish_body", "phone_agent_finish_app_fallback", "phone_agent_finish_dismiss")
        keys.forEach { k ->
            assertTrue("en lacks $k", en[k].orEmpty().isNotBlank())
            assertTrue("zh lacks $k", zh[k].orEmpty().isNotBlank())
        }
        assertEquals("代理报告已完成", zh["phone_agent_finish_title"])
        assertEquals("已完成", zh["phone_task_agent_done"])
        assertEquals("知道了", zh["phone_agent_finish_dismiss"])
        assertFalse(zh.getValue("phone_agent_finish_body").contains("核实"))
        assertFalse(en.getValue("phone_task_agent_done").contains("verified"))
        // The plain "ended, check the result" wording is still there for the other ends.
        assertTrue(zh.getValue("phone_task_ended").contains("请核对结果"))
        assertFalse(zh.getValue("phone_agent_finish_title").contains("已核实"))
    }

    private fun load(path: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(ROOT, path))
        val nodes = document.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i) as org.w3c.dom.Element
            node.getAttribute("name") to node.textContent
        }
    }

    private companion object {
        val ROOT = File("src/main/res")
    }
}
